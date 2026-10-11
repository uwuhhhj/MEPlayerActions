package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.network.ServerModelPresentation;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Direct gallery home adapted from OpenYSM-Updated PlayerModelScreen/ModelButton/IconButton,
 * revision 0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85 (MIT; see THIRD_PARTY_NOTICES).
 * Client and server tabs share the same source gallery; only an explicit use button applies an appearance.
 */
public final class PlayerModelScreen extends LocalAppearanceScreen {
    private static final Identifier SETTINGS_ICON = Identifier.of("meplayeractions", "textures/gui/settings.png");
    private static final String PRIVATE_CACHE_DESTINATION = "private-share-cache/";
    private final Map<ButtonWidget, String> roles = new IdentityHashMap<>();
    private final Set<String> serverFavorites = new HashSet<>();
    private boolean clientTab, advanced, initializedServerPresent, initializedServerOffline;
    private String initializedInstance = "";
    private long initializedCatalogRevision = Long.MIN_VALUE;
    private Map<String, String> initializedServerPreviews = Map.of();
    private Map<String, String> serverLabels = Map.of();
    private Map<String, ServerModelGalleryIndex.Entry> serverLocations = Map.of();
    private UsageSnapshot initializedUsage;

    private record UsageSnapshot(ClientRuntime.AppearanceSource source, String modelId, String hash,
            String pendingServer, String pendingLocal, String requestStatus, boolean canActivate, boolean playerHidden,
            boolean playerRuleKnown, boolean modelShown, boolean modelShowAllowed) {
    }

    public PlayerModelScreen(ClientRuntime runtime) {
        this(runtime, null);
    }

    public PlayerModelScreen(ClientRuntime runtime, Screen parent) {
        super(runtime, parent, Text.literal("玩家模型"));
        this.clientTab = !runtime.serverOwnModelPresent();
    }

    /** Owner upload management keeps the existing client-side cloud controls and permissions. */
    public void showUploadedPrivateModels() {
        this.clientTab = true;
        this.advanced = false;
        this.initializedServerPresent = super.runtime.serverOwnModelPresent();
        resetGallery();
        showUploadedModelsOnly();
    }

    @Override
    protected void init() {
        boolean present = super.runtime.serverOwnModelPresent();
        String instance = super.runtime.serverOwnModelInstance();
        if (!this.initializedServerPresent && present && this.clientTab) {
            this.clientTab = false;
            this.advanced = false;
            resetGallery();
        }
        this.initializedServerPresent = present;
        this.initializedInstance = instance;
        this.roles.clear();
        this.initializedServerOffline = super.runtime.serverCatalogOfflineMode();
        super.init();
        this.initializedCatalogRevision = super.runtime.serverCatalogRevision();
        this.initializedServerPreviews = serverPreviewAssets();
        this.initializedUsage = usageSnapshot();
    }

    @Override
    protected int galleryHeaderHeight(boolean compact) {
        return compact ? GalleryPanelLayout.HOME_COMPACT_HEADER_HEIGHT : GalleryPanelLayout.HOME_HEADER_HEIGHT;
    }

    @Override
    protected boolean clientGalleryVisible() {
        return !this.advanced;
    }

    @Override
    protected boolean localGallery() {
        return this.clientTab;
    }

    @Override
    protected List<ClientRuntime.Action> galleryModels() {
        if (this.clientTab) {
            return super.galleryModels();
        }

        List<ClientRuntime.Action> models = super.runtime.serverCatalog();
        LinkedHashMap<String, String> labels = new LinkedHashMap<String, String>();
        LinkedHashMap<String, ServerModelGalleryIndex.Entry> current = new LinkedHashMap<String, ServerModelGalleryIndex.Entry>();
        super.runtime.serverCatalogDirectory().forEach(model -> current.put(model.id(),
                new ServerModelGalleryIndex.Entry(model.id(), model.source(), model.folder(), false)));
        models.forEach(model -> {
            labels.put(model.id(), model.label());
            current.putIfAbsent(model.id(), new ServerModelGalleryIndex.Entry(model.id(), "unknown", "", true));
        });
        this.serverLabels = Map.copyOf(labels);
        this.serverLocations = Map.copyOf(current);
        return models.stream()
                .sorted(Comparator.comparingInt(
                        (ClientRuntime.Action model) -> ServerModelGalleryIndex.priority(current.get(model.id())))
                        .thenComparing(ClientRuntime.Action::id))
                .toList();
    }

    @Override
    protected List<ModelGroup> nonLocalGalleryFolders(List<ClientRuntime.Action> candidates, String directory) {
        ArrayList<LocalAppearanceScreen.ModelGroup> folders = new ArrayList<>(ServerModelGalleryIndex
                .folders(candidates.stream().map(model -> this.serverLocations.get(model.id())).filter(Objects::nonNull)
                        .toList(), directory)
                .stream().map(folder -> new ModelGroup(0, folder.path(), folder.label(), folder.count())).toList());
        if (directory.isEmpty()) {
            folders.add(new ModelGroup(0, PRIVATE_CACHE_DESTINATION, "私人分享缓存",
                    super.runtime.receivedPrivateModels().size()));
        }

        return List.copyOf(folders);
    }

    @Override
    protected boolean openGalleryDestination(ModelGroup group) {
        if (!this.clientTab && group.path().equals(PRIVATE_CACHE_DESTINATION)) {
            super.client.setScreen(new PrivateSharedCacheScreen(super.runtime, this));
            return true;
        }
        return false;
    }

    @Override
    protected boolean nonLocalGalleryDirect(String directory, String id) {
        ServerModelGalleryIndex.Entry location = this.serverLocations.get(id);
        return location != null && ServerModelGalleryIndex.direct(directory, location);
    }

    @Override
    protected boolean nonLocalGalleryContains(String directory, String id) {
        ServerModelGalleryIndex.Entry location = this.serverLocations.get(id);
        return location != null && ServerModelGalleryIndex.contains(directory, location);
    }

    @Override
    protected String nonLocalGalleryParent(String directory) {
        return ServerModelGalleryIndex.parent(directory);
    }

    @Override
    protected String galleryGroupTooltip(ModelGroup group) {
        if (group.path().equals(PRIVATE_CACHE_DESTINATION)) {
            return "私人分享缓存\n查看本机已经收到的私人模型\n只预览，不创建服务器伪装\n本人上传在客户端云朵中管理";
        }

        return this.clientTab ? super.galleryGroupTooltip(group)
                : ServerModelGalleryIndex.directoryLabel(group.path()) + "\n" + group.count() + " 个模型\n点击进入目录，仅浏览不下载";
    }

    @Override
    protected CompletableFuture<LocalModelLibrary.Loaded> loadGalleryPreview(String id) {
        return this.clientTab ? super.loadGalleryPreview(id)
                : CompletableFuture.completedFuture(super.runtime.serverModelForPreview(id));
    }

    @Override
    protected YsmModelProfile galleryProfile(String id) {
        if (this.clientTab) {
            return super.galleryProfile(id);
        }

        LocalModelLibrary.Loaded loaded = super.runtime.serverModelForPreview(id);
        return loaded == null ? YsmModelProfile.empty() : loaded.profile();
    }

    @Override
    protected String gallerySourceLabel(String id) {
        if (this.clientTab) {
            return super.gallerySourceLabel(id);
        }

        ServerModelGalleryIndex.Entry location = this.serverLocations.get(id);
        return location == null ? "服务器来源未分类" : ServerModelGalleryIndex.location(location);
    }

    @Override
    protected String galleryStatus() {
        if (this.clientTab) {
            return super.galleryStatus();
        }

        if (super.runtime.serverCatalogOfflineMode()) {
            return super.runtime.serverCatalogStatus() + " · 连接服务器后取得使用授权";
        }

        String request = super.runtime.serverDisguiseRequestStatus();
        return request.isEmpty() ? super.runtime.serverCatalogStatus() : request;
    }

    @Override
    protected String galleryPreviewUnavailableText(String id) {
        return this.clientTab ? super.galleryPreviewUnavailableText(id)
                : super.runtime.serverModelPresentation(id).placeholder();
    }

    @Override
    protected String galleryCardPlaceholder(String id) {
        if (this.clientTab) {
            return super.galleryCardPlaceholder(id);
        }

        ServerModelGalleryIndex.Entry location = this.serverLocations.get(id);
        if (location == null) {
            return "来源未分类";
        }

        return location.cachedOnly() ? "本机缓存" : switch (location.source()) {
        case "own" -> "MPA 模型";
        case "modelengine" -> "ME 伪装";
        default -> "来源未分类";
        };
    }

    @Override
    protected boolean galleryCardCloudVisible() {
        return true;
    }

    @Override
    protected String galleryCardCloudTooltip(String id) {
        return this.clientTab ? super.galleryCardCloudTooltip(id) : super.runtime.serverModelPresentation(id).tooltip();
    }

    @Override
    protected void galleryCardCloudClicked(String id) {
        if (this.clientTab) {
            super.galleryCardCloudClicked(id);
            return;
        }
        // The cloud selects a status/preview only. Only the explicit use button requests a disguise.
        browseModel(id);
        setGalleryMessage(super.runtime.serverModelPresentation(id).placeholder());
    }

    @Override
    protected void drawGalleryCardCloud(DrawContext context, String id, int x, int y) {
        if (this.clientTab) {
            super.drawGalleryCardCloud(context, id, x, y);
            return;
        }
        ServerModelPresentation.Display state = super.runtime.serverModelPresentation(id);
        CloudUploadIcon.drawServer(context, x, y, state.cloudColor(), state.cloud());
    }

    @Override
    protected boolean canUseGalleryModel(String id, LocalModelLibrary.Loaded loaded) {
        return this.clientTab ? super.canUseGalleryModel(id, loaded) : super.runtime.canRequestServerDisguise(id);
    }

    @Override
    protected GalleryModelUseState.State galleryUseState(String id, LocalModelLibrary.Loaded loaded) {
        if (this.clientTab) {
            return super.galleryUseState(id, loaded);
        }

        boolean current = super.runtime.currentAppearanceSource() == ClientRuntime.AppearanceSource.SERVER;
        return GalleryModelUseState.server(id, current ? super.runtime.currentAppearanceModelId() : "",
                super.runtime.pendingServerDisguiseModelId(), super.runtime.canRequestServerDisguise(id),
                !id.isEmpty() && super.runtime.serverModelHasPersistentId(id)
                        && super.runtime.serverPreferencesNeedApply(id));
    }

    @Override
    protected String galleryUseTooltip() {
        return this.clientTab ? super.galleryUseTooltip()
                : "请求使用所选服务器模型\n服务器先确认伪装，再检查可接管资源\n有可用资源才会授权客户端下载\n其他玩家可见服务器伪装";
    }

    @Override
    protected String galleryUseTooltip(GalleryModelUseState.State state) {
        if (!this.clientTab && super.runtime.serverCatalogOfflineMode()) {
            return "离线模式，仅可预览缓存\n需要连接服务器取得当前使用授权\n外观设置仍可保存于本机";
        }

        if (!this.clientTab && !state.current() && !state.waiting() && !state.canUse()) {
            if (super.runtime.serverCachedPreviewOnly(selectedModelId())) {
                return "本机缓存，仅供预览\n此模型不在当前服务器授权图库\n缓存不能代替使用授权";
            }

            if (!super.runtime.pendingServerDisguiseModelId().isEmpty()) {
                return "等待另一个伪装请求完成\n收到服务器确认后再切换";
            }

            if (!super.runtime.serverCatalogReady()) {
                return "等待服务器模型图库\n" + super.runtime.serverCatalogStatus();
            }

        }
        if (!this.clientTab) {
            return (state.current() ? (state.canUse() ? "保存的外观设置尚未应用\n点击应用后等待服务器确认" : "当前正在使用此服务器伪装\n当前使用不等于已开启本地接管")
                    : (state.waiting() ? "等待服务器确认伪装请求" : "点击请求使用所选服务器伪装")) + "\n"
                    + super.runtime.serverModelPresentation(selectedModelId()).tooltip();
        }

        return super.galleryUseTooltip(state);
    }

    @Override
    protected String galleryBrowsingMessage() {
        return this.clientTab ? super.galleryBrowsingMessage() : "浏览服务器模型；点击使用模型才伪装";
    }

    @Override
    protected boolean isFavoriteModel(String id) {
        return this.clientTab ? super.isFavoriteModel(id) : this.serverFavorites.contains(id);
    }

    @Override
    protected void toggleModelFavorite(String id) {
        if (this.clientTab) {
            super.toggleModelFavorite(id);
        } else if (!this.serverFavorites.remove(id)) {
            this.serverFavorites.add(id);
        }

    }

    @Override
    protected void buildSelectionControls() {
        if (this.clientTab) {
            super.buildSelectionControls();
            return;
        }
        super.favorite = null;
        super.use = button("使用模型", super.left + 5, super.bottom - 44, super.previewWidth - 10, this::useSelectedModel,
                galleryUseTooltip());
        this.roles.put(super.use, "useServerModel");
        super.settings = button("外观设置…", super.left + 5, super.bottom - 21, super.previewWidth - 10, () -> {
            if (super.runtime.serverModelHasPersistentId(selectedModelId())) {
                super.client.setScreen(new ServerAppearanceSettingsScreen(super.runtime, selectedModelId(), this));
            }
        }, "保存此服务器模型的伪装参数\n返回后点击使用模型才提交");
        this.roles.put(super.settings, "serverAppearanceSettings");
    }

    @Override
    public void useSelectedModel() {
        if (this.clientTab) {
            super.useSelectedModel();
            return;
        }
        String id = selectedModelId();
        if (!galleryUseState(id, super.runtime.serverModelForPreview(id)).canUse()) {
            return;
        }

        setGalleryMessage(super.runtime.requestServerDisguise(id) ? "" : "无法发送伪装请求，请稍后重试");
        updateSelectionButtons();
    }

    @Override
    protected boolean requiresLocalSource() {
        return false;
    }

    @Override
    protected boolean showRotationHint() {
        return false;
    }

    @Override
    protected String settingsButtonLabel(int buttonWidth) {
        return "外观设置…";
    }

    @Override
    protected void buildHeaderControls() {
        int sourceOffset = super.compactLayout ? 20 : 29;
        int half = (super.panelWidth - 6) / 2, y = super.top - sourceOffset;
        int sourceHeight = super.compactLayout ? 18 : 24;
        this.roles.put(
                flatButton(this.clientTab ? "客户端 ✓" : "客户端", super.left, y, half, sourceHeight,
                        () -> switchSource(true), "浏览客户端模型\n未上传模型仅本机使用\n已上传且匹配的模型会恢复分享\n云朵管理上传与存档", this.clientTab),
                "clientSource");
        String serverLabel = super.runtime.serverCatalogOfflineMode() ? (this.clientTab ? "服务端 · 离线" : "服务端 · 离线 ✓")
                : (this.clientTab ? "服务端" : "服务端 ✓");
        this.roles.put(flatButton(serverLabel, super.left + half + 6, y, super.panelWidth - half - 6, sourceHeight,
                () -> switchSource(false), super.runtime.serverCatalogOfflineMode() ? "离线模式\n浏览本机缓存并保存设置\n连接服务器后才能请求伪装"
                        : "浏览服务器可用模型\n点击使用后等待服务器确认",
                !this.clientTab), "serverSource");
    }

    @Override
    protected void buildFooterControls() {
        this.roles.put(button("返回", super.left, super.footerY, 46, this::close, "返回上一页"), "back");
        // Like OpenYSM ConfigCheckBoxForge / BooleanOptionRow, visible selection
        // comes from the actual option; the label also identifies each separate switch.
        boolean serverDisguise = super.runtime.serverOwnModelPresent();
        int toggleX = super.left + 50, toggleY = super.footerY;
        boolean hidePlayer = super.runtime.ownPlayerHideSetting(),
                playerRuleUnknown = serverDisguise && !super.runtime.serverOwnPlayerHideRuleKnown();
        String playerLabel = playerRuleUnknown ? "玩家·服务器控制" : (hidePlayer ? "玩家隐藏" : "玩家显示");
        int playerWidth = Math.max(44, super.textRenderer.getWidth(playerLabel) + 6), playerExtra = playerWidth - 44;
        ButtonWidget vanillaPlayer = flatButton(playerLabel, toggleX, toggleY, playerWidth, 20, () -> {
            if (super.runtime.serverOwnModelPresent()) {
                return;
            }

            super.runtime.options.hideVanillaPlayer = !super.runtime.options.hideVanillaPlayer;
            super.runtime.options.save();
            clearAndInit();
        }, serverDisguise
                ? (playerRuleUnknown ? "玩家由服务器控制\n未收到人物隐藏规则\n沿用原版服务器显示"
                        : "玩家由服务器控制\n当前规则：" + (hidePlayer ? "隐藏" : "显示") + "\n调整服务端外观设置后应用")
                : "只切换原版玩家\n装备与伪装分别控制", hidePlayer);
        vanillaPlayer.active = !serverDisguise;
        this.roles.put(vanillaPlayer, "vanillaPlayer");
        boolean hideEquipment = serverDisguise || super.runtime.options.hideVanillaEquipment;
        ButtonWidget vanillaEquipment = flatButton(hideEquipment ? "装备隐藏" : "装备显示", toggleX + 48 + playerExtra, toggleY,
                44, 20, () -> {
                    if (super.runtime.serverOwnModelPresent()) {
                        return;
                    }

                    super.runtime.options.hideVanillaEquipment = !super.runtime.options.hideVanillaEquipment;
                    super.runtime.options.save();
                    clearAndInit();
                }, serverDisguise ? "服务器伪装期间装备保持隐藏\n伪装模型由独立按钮控制" : "只切换盔甲、披风与鞘翅\n玩家与伪装分别控制", hideEquipment);
        vanillaEquipment.active = !serverDisguise;
        this.roles.put(vanillaEquipment, "vanillaEquipment");
        boolean modelShown = super.runtime.ownModelShowSetting(),
                modelShowAllowed = super.runtime.serverOwnModelShowAllowed();
        ButtonWidget ownModel = flatButton(modelShown ? "伪装显示" : "伪装隐藏", toggleX + 96 + playerExtra, toggleY, 44, 20,
                () -> {
                    if (!super.runtime.serverOwnModelShowAllowed()) {
                        return;
                    }

                    super.runtime.options.showSelf = !super.runtime.options.showSelf;
                    super.runtime.options.save();
                    clearAndInit();
                }, modelShowAllowed ? "只切换本人的伪装模型\n原版玩家与装备分别控制" : "伪装显示由服务器控制\n当前未允许本人可见\n本机显示偏好保留\n可在服务端外观设置请求本人可见并应用",
                modelShown);
        ownModel.active = modelShowAllowed;
        this.roles.put(ownModel, "selfVisibility");
        ButtonWidget gear = new ButtonWidget(super.left + 194 + playerExtra, super.footerY, 20, 20, Text.literal("⚙"),
                button -> {
                    this.advanced = !this.advanced;
                    clearAndInit();
                }, narration -> narration.get()) {
            @Override
            protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
                context.fill(getX(), getY(), getRight(), getBottom(), -12369342);
                if (super.hovered || isFocused()) {
                    context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
                }

                context.drawTexture(RenderPipelines.GUI_TEXTURED, SETTINGS_ICON, getX() + 2, getY() + 2, 0f, 0f, 16, 16,
                        32, 32, 32, 32);
            }
        };
        gear.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, this.advanced ? "返回图库" : "客户端设置\n渲染与轮盘选项"));
        this.roles.put(addDrawableChild(gear), "clientSettings");
    }

    @Override
    protected int footerStatusInset() {
        String label = super.runtime.serverOwnModelPresent() && !super.runtime.serverOwnPlayerHideRuleKnown()
                ? "玩家·服务器控制"
                : (super.runtime.ownPlayerHideSetting() ? "玩家隐藏" : "玩家显示");
        return 223 + Math.max(0, super.textRenderer.getWidth(label) + 6 - 44);
    }

    @Override
    protected void buildAlternateControls() {
        if (!this.advanced) {
            return;
        }

        int x = super.right + 8, w = super.rightWidth - 16, y = super.top + 30;
        boolean compact = super.bottom - super.top < 185;
        int controlWidth = compact ? (w - 4) / 2 : w;
        this.roles.put(flatButton(
                super.runtime.options.enabled ? (compact ? "渲染开启" : "客户端渲染：开启") : (compact ? "渲染关闭" : "客户端渲染：关闭"), x, y,
                controlWidth, 20, () -> {
                    super.runtime.toggleEnabled();
                    clearAndInit();
                }, "暂停客户端模型渲染\n恢复服务器或原版显示\n保留模型与轮盘设置", super.runtime.options.enabled), "clientRendering");
        if (!compact) {
            y += 25;
        }

        this.roles.put(button(
                super.runtime.wheelPreferences().keepOpen() ? (compact ? "轮盘保留" : "选择后保持轮盘：开启")
                        : (compact ? "轮盘收起" : "选择后保持轮盘：关闭"),
                compact ? x + controlWidth + 4 : x, y, controlWidth, () -> {
                    super.runtime.updateWheelPreferences(super.runtime.wheelPreferences()
                            .withKeepOpen(!super.runtime.wheelPreferences().keepOpen()));
                    clearAndInit();
                }, "选择动作后保持轮盘\n再次按 J 或返回关闭"), "keepOpen");
        y += 25;
        if (this.clientTab && super.runtime.canEditLocalAppearance()) {
            this.roles.put(button(super.runtime.localActionLocked() ? (compact ? "动作保留" : "移动时保留本地动作：开启")
                    : (compact ? "动作自动" : "移动时保留本地动作：关闭"), x, y, controlWidth, () -> {
                        super.runtime.setLocalActionLocked(!super.runtime.localActionLocked());
                        clearAndInit();
                    }, "移动时保留本地动作\n服务器动作由服务器决定"), "localActionLock");
            if (!compact) {
                y += 25;
            }

            boolean privateApplied = super.runtime.currentAppearanceSource() == ClientRuntime.AppearanceSource.CLIENT;
            this.roles.put(button(privateApplied ? (compact ? "本地开启" : "本地外观：开启") : (compact ? "本地关闭" : "本地外观：关闭"),
                    compact ? x + controlWidth + 4 : x, y, controlWidth, () -> {
                        if (!super.runtime.canActivateLocalAppearance()) {
                            return;
                        }

                        LocalAppearanceSettings current = super.runtime.localAppearance();
                        if (super.runtime.currentAppearanceSource() == ClientRuntime.AppearanceSource.CLIENT) {
                            super.runtime.updateLocalAppearance(new LocalAppearanceSettings(false, current.modelId(),
                                    current.scale(), current.offsetX(), current.offsetY(), current.offsetZ()));
                        }

                    else {
                            super.runtime.selectLocalModel(current.modelId());
                        }

                        clearAndInit();
                    }, "开启或关闭私人外观\n请先解除服务器伪装\n保留模型与设置"), "privateEnabled");
            this.roles.entrySet().stream().filter(entry -> entry.getValue().equals("privateEnabled"))
                    .forEach(entry -> entry.getKey().active = super.runtime.canActivateLocalAppearance());
            y += 25;
        }
        this.roles.put(button("返回图库", x, Math.max(y + 25, super.bottom - 22), w, () -> {
            this.advanced = false;
            clearAndInit();
        }, "关闭轮盘选项并回到当前来源"), "gallery");
    }

    private void switchSource(boolean local) {
        this.clientTab = local; // Tabs only browse; use buttons own appearance changes.
        this.advanced = false;
        resetGallery();
        clearAndInit();
    }

    @Override
    public void tick() {
        boolean offlineChanged = this.initializedServerOffline != super.runtime.serverCatalogOfflineMode();
        if (offlineChanged
                || !this.clientTab && this.initializedCatalogRevision != super.runtime.serverCatalogRevision()) {
            if (!this.clientTab) {
                refreshGalleryModels();
            }

            clearAndInit();
        }
        boolean present = super.runtime.serverOwnModelPresent();
        String instance = super.runtime.serverOwnModelInstance();
        if (!this.initializedServerPresent && present) {
            this.clientTab = false;
            this.advanced = false;
            resetGallery();
            clearAndInit();
        } else if (this.initializedServerPresent != present || !this.initializedInstance.equals(instance)) {
            setGalleryMessage("");
            clearAndInit();
        }
        if (!this.clientTab && !this.advanced) {
            Map<String, String> currentPreviews = serverPreviewAssets();
            if (!this.initializedServerPreviews.equals(currentPreviews)) {
                clearGalleryPreviews();
                clearAndInit();
            }
        }
        UsageSnapshot current = usageSnapshot();
        if (!current.equals(this.initializedUsage)) {
            boolean visibilityChanged = this.initializedUsage != null
                    && (current.playerHidden() != this.initializedUsage.playerHidden()
                            || current.playerRuleKnown() != this.initializedUsage.playerRuleKnown()
                            || current.modelShown() != this.initializedUsage.modelShown()
                            || current.modelShowAllowed() != this.initializedUsage.modelShowAllowed());
            setGalleryMessage("");
            this.initializedUsage = current;
            if (visibilityChanged) {
                clearAndInit();
            } else {
                updateSelectionButtons();
            }

        }
        super.tick();
    }

    private UsageSnapshot usageSnapshot() {
        return new UsageSnapshot(super.runtime.currentAppearanceSource(), super.runtime.currentAppearanceModelId(),
                super.runtime.currentAppearanceAssetHash(), super.runtime.pendingServerDisguiseModelId(),
                super.runtime.pendingLocalAppearanceModelId(), super.runtime.serverDisguiseRequestStatus(),
                super.runtime.canActivateLocalAppearance(), super.runtime.ownPlayerHideSetting(),
                super.runtime.serverOwnPlayerHideRuleKnown(), super.runtime.ownModelShowSetting(),
                super.runtime.serverOwnModelShowAllowed());
    }

    @Override
    protected void updateSelectionButtons() {
        super.updateSelectionButtons();
        if (!this.clientTab && super.settings != null) {
            super.settings.active = !selectedModelId().isEmpty()
                    && super.runtime.serverModelHasPersistentId(selectedModelId());
            super.settings.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width,
                    super.settings.active ? "保存此服务器模型的伪装参数\n返回后点击使用模型才提交" : "缓存尚无服务器模型 ID\n仅可预览，不能保存伪装参数"));
        }
    }

    /** Poll only the visible page and selection for already authorized resources; never requests a model. */
    private Map<String, String> serverPreviewAssets() {
        if (this.clientTab || this.advanced) {
            return Map.of();
        }

        HashSet<String> ids = new HashSet<>(visibleModelIds());
        if (!selectedModelId().isEmpty()) {
            ids.add(selectedModelId());
        }

        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        for (String id : ids) {
            LocalModelLibrary.Loaded loaded = super.runtime.serverModelForPreview(id);
            if (loaded != null) {
                result.put(id, loaded.hash());
            }

        }
        return Map.copyOf(result);
    }

    @Override
    protected void renderHeader(DrawContext context) {
        String suffix = galleryFilterLabel();
        if (!this.clientTab && suffix.isEmpty() && !groupPath().isEmpty()) {
            suffix = ServerModelGalleryIndex.directoryLabel(groupPath());
        }

        String caption = super.title.getString() + (!this.clientTab && !suffix.isEmpty() ? " · " + suffix : "");
        context.drawCenteredTextWithShadow(super.textRenderer,
                super.textRenderer.trimToWidth(caption, super.panelWidth), super.width / 2, super.titleY, 0xfff3f0e0);
    }

    @Override
    protected void renderAlternateContent(DrawContext context, float delta) {
        if (this.clientTab) {
            renderSelectedPreview(context, delta);
        } else {
            renderServerPreview(context, delta);
        }

        clipped(context, "客户端设置", super.right + 8, super.top + 8, super.rightWidth - 16, 0xfff3f0e0);
        clipped(context, super.runtime.privateSyncStatus(), super.right + 8, super.top + 19, super.rightWidth - 16,
                0xff92b9df);
    }

    @Override
    protected void renderSelectedPreview(DrawContext context, float delta) {
        if (this.clientTab) {
            super.renderSelectedPreview(context, delta);
            return;
        }
        String id = selectedModelId();
        if (id.isEmpty()) {
            clipped(context, "服务器模型", super.left + 6, super.top + 6, super.previewWidth - 12, 0xfff3f0e0);
            clipped(context, "请选择服务器模型", super.previewX + 3, super.previewY + super.previewH / 2, super.previewW - 6,
                    0xffc6d5e7);
            return;
        }
        LocalModelLibrary.Loaded loaded = super.runtime.serverModelForPreview(id);
        String name = this.serverLabels.getOrDefault(id, id);
        GalleryModelUseState.State state = galleryUseState(id, loaded);
        super.leftPreviewId = id;
        super.leftPreviewSource = state.current() ? "server-catalog-current" : "server-catalog-preview";
        clipped(context, name, super.left + 6, super.top + 6, super.previewWidth - 12, 0xfff3f0e0);
        clipped(context, state.waiting() ? "等待服务器确认"
                : (state.current() ? "当前使用"
                        : (super.runtime.serverCatalogOfflineMode() ? "离线缓存 · 仅预览"
                                : (super.runtime.serverCachedPreviewOnly(id) ? "本机缓存 · 尚未授权" : "预览 · 点击使用模型伪装"))),
                super.left + 6, super.top + 17, super.previewWidth - 12, 0xff92b9df);
        if (loaded == null) {
            renderServerResourceStatus(context, id, super.previewY, super.previewH);
            return;
        }
        String actualHash = state.current() ? super.runtime.currentAppearanceAssetHash() : "";
        if (!actualHash.isEmpty() && !actualHash.equals(loaded.hash())) {
            renderServerResourceStatus(context, id, super.previewY, super.previewH);
            return;
        }
        super.leftPreviewKey = "server-catalog:" + id + ":" + loaded.hash();
        super.leftPreviewHash = loaded.hash();
        int statusHeight = Math.min(72, Math.max(30, super.previewH / 3)),
                modelHeight = Math.max(20, super.previewH - statusHeight - 4);
        super.leftPreviewDrawn = super.preview.render(context, loaded.model(), super.leftPreviewKey, super.previewX,
                super.previewY, super.previewW, modelHeight, super.yaw, super.pitch, super.ticks + delta, Map.of(),
                loaded.previewAnimation(), loaded.profile(), ModelPreview.Context.SELECTED);
        if (super.leftPreviewDrawn) {
            super.drawnPreviewCount++;
        }

        else {
            clipped(context, "服务器模型预览暂不可用", super.previewX + 3, super.previewY + modelHeight / 2, super.previewW - 6,
                    0xffffc685);
        }

        renderServerResourceStatus(context, id, super.previewY + modelHeight + 4, statusHeight);
    }

    /** Narrow panels wrap the key facts first; the cloud/use tooltip retains full source and error details. */
    private void renderServerResourceStatus(DrawContext context, String id, int y, int height) {
        ServerModelPresentation.Display status = super.runtime.serverModelPresentation(id);
        ArrayList<ServerModelPresentation.Line> ordered = new ArrayList<ServerModelPresentation.Line>();
        for (String prefix : List.of("服务器资源：", "客户端资源：", "本地接管：")) {
            status.lines().stream().filter(line -> line.text().startsWith(prefix)).forEach(ordered::add);
        }

        status.lines().stream().filter(line -> !ordered.contains(line)).forEach(ordered::add);
        int maxLines = Math.max(1, height / 10), used = 0;
        context.enableScissor(super.previewX, y, super.previewX + super.previewW, y + height);
        for (int factIndex = 0; factIndex < ordered.size(); factIndex++) {
            ServerModelPresentation.Line fact = ordered.get(factIndex);
            List<OrderedText> wrapped = super.textRenderer.wrapLines(Text.literal(fact.text()),
                    Math.max(20, super.previewW - 6));
            int budget = factIndex < 3 ? Math.max(1, (maxLines - used) / Math.max(1, 3 - factIndex)) : maxLines - used;
            int count = 0;
            for (OrderedText line : wrapped) {
                if (used >= maxLines) {
                    break;
                }

                context.drawTextWithShadow(super.textRenderer, line, super.previewX + 3, y + used * 10, fact.color());
                used++;
                if (++count >= budget) {
                    break;
                }

            }
            if (used >= maxLines) {
                break;
            }

        }
        context.disableScissor();
    }

    private void renderServerPreview(DrawContext context, float delta) {
        String id = super.runtime.serverOwnModelId(), instance = super.runtime.serverOwnModelInstance();
        clipped(context, id.isEmpty() ? "服务器模型" : id, super.left + 6, super.top + 6, super.previewWidth - 12,
                0xfff3f0e0);
        clipped(context, "服务器当前绑定 · 接管状态独立显示", super.left + 6, super.top + 17, super.previewWidth - 12, 0xff92b9df);
        LocalModelLibrary.Loaded loaded = super.runtime.serverModelForPreview(id);
        int statusHeight = Math.min(72, Math.max(30, super.previewH / 3)),
                modelHeight = Math.max(20, super.previewH - statusHeight - 4);
        if (super.runtime.serverOwnModelPresent() && loaded != null) {
            super.leftPreviewId = id;
            super.leftPreviewKey = "server:" + instance + ":" + loaded.hash();
            super.leftPreviewSource = "server-bound";
            super.leftPreviewInstance = instance;
            super.leftPreviewHash = loaded.hash();
            super.leftPreviewDrawn = super.preview.render(context, loaded.model(), super.leftPreviewKey, super.previewX,
                    super.previewY, super.previewW, modelHeight, super.yaw, super.pitch, super.ticks + delta, Map.of(),
                    loaded.previewAnimation(), loaded.profile());
            if (super.leftPreviewDrawn) {
                super.drawnPreviewCount++;
            }

        }
        if (!super.leftPreviewDrawn) {
            renderServerResourceStatus(context, id, super.previewY, super.previewH);
        }

        else {
            renderServerResourceStatus(context, id, super.previewY + modelHeight + 4, statusHeight);
        }

    }

    @Override
    protected boolean rotationDisabled() {
        return this.clientTab && super.rotationDisabled();
    }

    public Map<String, Object> diagnostics() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> gallery = galleryDiagnostics();
        result.put("mode", this.clientTab ? "client" : "server");
        result.put("ownServerDisguise", super.runtime.serverOwnModelPresent());
        result.put("localModelControlsVisible", this.clientTab && super.runtime.canEditLocalAppearance());
        result.put("actionGrid", false);
        result.put("effectiveLocalMode", super.runtime.interactionLocalMode());
        result.put("advanced", this.advanced);
        result.put("galleryHome", true);
        result.put("galleryLayout", clientGalleryVisible());
        result.put("upstreamGallery", true);
        result.put("contentTop", super.top);
        result.put("contentBottom", super.bottom);
        result.put("scroll", 0);
        result.put("maximumScroll", 0);
        result.put("leftPreview", gallery.get("leftPreview"));
        result.put("cards", gallery.get("cards"));
        result.put("gallery", gallery);
        result.put("serverAppearance", super.runtime.ownServerAppearanceDiagnostics());
        result.put("serverCatalogRevision", super.runtime.serverCatalogRevision());
        result.put("serverCatalogStatus", super.runtime.serverCatalogStatus());
        result.put("privateSyncEnabled", super.runtime.privateSyncEnabled());
        result.put("privateSyncAvailable", super.runtime.privateSyncAvailable());
        result.put("privateSyncStatus", super.runtime.privateSyncStatus());
        result.put("privateSharing", super.runtime.privateSharingDiagnostics());
        result.put("widgets", children().stream().filter(value -> value instanceof ButtonWidget).map(value -> {
            ButtonWidget button = (ButtonWidget) value;
            LinkedHashMap<String, Object> widget = new LinkedHashMap<String, Object>();
            widget.put("label", button.getMessage().getString());
            widget.put("role", this.roles.getOrDefault(button, "galleryControl"));
            widget.put("x", button.getX());
            widget.put("y", button.getY());
            widget.put("width", button.getWidth());
            widget.put("height", button.getHeight());
            widget.put("visible", button.visible);
            widget.put("active", button.active);
            return Map.copyOf(widget);
        }).toList());
        result.put("wheelKeepOpen", super.runtime.wheelPreferences().keepOpen());
        result.put("localActionLocked", super.runtime.localActionLocked());
        return Map.copyOf(result);
    }
}
