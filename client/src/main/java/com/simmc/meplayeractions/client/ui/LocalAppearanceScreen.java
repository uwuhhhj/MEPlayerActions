package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.network.PrivateModelSyncClient;
import com.simmc.meplayeractions.client.network.PrivateUploadCatalogSnapshot;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Gallery layout/filtering/card navigation adapted from OpenYSM-Updated PlayerModelScreen,
 * revision 0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85 (MIT; see THIRD_PARTY_NOTICES).
 * Browsing is a GUI-only draft; only the explicit use/settings buttons apply an appearance.
 */
public class LocalAppearanceScreen extends Screen {
    private static final int MAX_LOADS = 3;
    // OpenYSM ModelButton(52, 90), PlayerModelScreen's 55/93 slot stride,
    // and ModelButton's 76-pixel entity viewport (ModelPreview crops only the entity to 70).
    private static final int CARD_WIDTH = GalleryPanelLayout.CARD_WIDTH, CARD_HEIGHT = GalleryPanelLayout.CARD_HEIGHT,
            CARD_GAP = GalleryPanelLayout.CARD_GAP, CARD_RENDER_HEIGHT = 76;
    protected static final Identifier GUI_ICONS = Identifier.of("meplayeractions", "textures/gui/icon.png");
    private static final Identifier PACK_ICON = Identifier.of("meplayeractions", "textures/gui/default_pack_icon.png");
    protected final ClientRuntime runtime;
    protected final Screen parent;
    protected final ModelPreview preview;
    private final Map<String, PreviewEntry> previews = new HashMap<>();
    private final List<ButtonWidget> gridWidgets = new ArrayList<>();
    private List<ClientRuntime.Action> models = List.of(), filtered = List.of(), visible = List.of();

    protected record ModelGroup(int source, String path, String label, int count) {
    }

    private List<ModelGroup> filteredGroups = List.of(), visibleGroups = List.of();
    private final Map<String, Integer> groupPages = new HashMap<>();
    private int groupSource;
    private String groupDirectory = "";
    private String serverGroupDirectory = "";
    private final GalleryPreviewSelection selection;
    private String query = "", message = "";
    private boolean favoritesOnly, uploadedOnly, activeView, rotating;
    private Set<String> confirmedUploads = Set.of();
    private Set<String> localSourceIds = Set.of();
    private List<PrivateUploadCatalogSnapshot.Model> savedUploads = List.of();
    private String observedDeleteId = "", observedDeleteStatus = "";
    private int sourceFilter, page, columns, rows, pageSize, inFlight;
    protected int drawnPreviewCount;
    protected int left, top, bottom, panelWidth, previewWidth, right, rightWidth, gridTop, gridBottom;
    protected int frameTop, frameBottom, titleY, footerY;
    protected boolean compactLayout;
    protected int previewX, previewY, previewW, previewH;
    protected boolean leftPreviewDrawn;
    protected String leftPreviewId = "", leftPreviewKey = "";
    protected String leftPreviewSource = "native-player", leftPreviewInstance = "", leftPreviewHash = "";
    private final Set<String> drawnCards = new HashSet<>();
    private long generation;
    protected float yaw = 0, pitch = -8, ticks;
    private TextFieldWidget search;
    protected ButtonWidget use, settings, favorite;
    private ButtonWidget upload;
    private ThreadPoolExecutor savedShareChecker;
    private String pendingSavedShare = "";
    private long savedShareRevision;

    public LocalAppearanceScreen(ClientRuntime runtime) {
        this(runtime, null);
    }

    public LocalAppearanceScreen(ClientRuntime runtime, Screen parent) {
        this(runtime, parent, Text.literal("本地模型图库"));
    }

    protected LocalAppearanceScreen(ClientRuntime runtime, Screen parent, Text title) {
        super(title);
        this.runtime = runtime;
        this.preview = new ModelPreview(runtime);
        this.parent = parent;
        this.selection = new GalleryPreviewSelection(runtime.localAppearance().modelId());
    }

    @Override
    protected void init() {
        this.activeView = true;
        GalleryPanelLayout.Bounds layout = GalleryPanelLayout.create(super.width, super.height,
                galleryHeaderHeight(false), galleryHeaderHeight(true));
        this.panelWidth = layout.panelWidth();
        this.left = layout.left();
        this.top = layout.top();
        this.bottom = layout.bottom();
        this.previewWidth = layout.previewWidth();
        this.right = layout.right();
        this.rightWidth = layout.rightWidth();
        this.frameTop = layout.frameTop();
        this.frameBottom = layout.frameBottom();
        this.titleY = layout.titleY();
        this.footerY = layout.footerY();
        this.compactLayout = layout.compact();
        this.previewX = this.left + 4;
        this.previewY = this.top + 25;
        this.previewW = this.previewWidth - 8;
        this.previewH = Math.max(30, this.bottom - this.previewY - (showRotationHint() ? 63 : 46));
        this.gridTop = this.top + 29;
        this.gridBottom = this.bottom - 26;
        this.columns = layout.columns();
        this.rows = layout.rows();
        this.pageSize = this.columns * this.rows;
        this.use = null;
        this.settings = null;
        this.favorite = null;
        this.upload = null;
        buildHeaderControls();
        if (clientGalleryVisible()) {
            buildGalleryControls();
        }

        else {
            this.search = null;
            this.visible = List.of();
            this.filtered = List.of();
            this.visibleGroups = List.of();
            this.filteredGroups = List.of();
            this.gridWidgets.clear();
            buildAlternateControls();
        }
        buildFooterControls();
    }

    protected int galleryHeaderHeight(boolean compact) {
        return compact ? 29 : 38;
    }

    protected boolean clientGalleryVisible() {
        return true;
    }

    protected boolean requiresLocalSource() {
        return true;
    }

    protected boolean showRotationHint() {
        return true;
    }

    protected String settingsButtonLabel(int buttonWidth) {
        return buttonWidth < 65 ? "设置" : "详情 / 设置";
    }

    protected boolean localGallery() {
        return true;
    }

    protected List<ClientRuntime.Action> galleryModels() {
        return this.runtime.localModels();
    }

    protected List<ModelGroup> nonLocalGalleryFolders(List<ClientRuntime.Action> candidates, String directory) {
        return List.of();
    }

    protected boolean nonLocalGalleryDirect(String directory, String id) {
        return true;
    }

    protected boolean nonLocalGalleryContains(String directory, String id) {
        return true;
    }

    protected String nonLocalGalleryParent(String directory) {
        return "";
    }

    /** Special read-only destinations do not become server model directories or command targets. */
    protected boolean openGalleryDestination(ModelGroup group) {
        return false;
    }

    protected String galleryFilterLabel() {
        return this.favoritesOnly ? "收藏 · 全部来源" : (this.query.isBlank() ? "" : "搜索 · 全部来源");
    }

    protected String galleryGroupTooltip(ModelGroup group) {
        return group.label() + " · " + group.count() + " 个模型\n" + group.path() + "\n点击进入模型目录";
    }

    protected CompletableFuture<LocalModelLibrary.Loaded> loadGalleryPreview(String id) {
        return this.uploadedOnly && !this.runtime.uploadedPrivateModelIds().contains(id)
                ? CompletableFuture.completedFuture(null)
                : this.runtime.loadLocalPreview(id);
    }

    protected YsmModelProfile galleryProfile(String id) {
        return this.runtime.localModelProfile(id);
    }

    protected String gallerySourceLabel(String id) {
        return this.uploadedOnly ? "本人服务器存档" : sourceLabel(id);
    }

    protected String galleryStatus() {
        return this.uploadedOnly ? this.runtime.privateUploadCatalogStatus() : this.runtime.localAppearanceStatus();
    }

    protected String galleryPreviewUnavailableText(String id) {
        return cloudModelHint(id).isEmpty() ? "正在加载所选模型…" : cloudModelHint(id);
    }

    protected String galleryCardPlaceholder(String id) {
        return localGallery() ? "加载中…" : "服务端模型";
    }

    /** Both sources use the same corner affordance; server cards expose cache status instead of uploading. */
    protected boolean galleryCardCloudVisible() {
        return localGallery();
    }

    protected String galleryCardCloudTooltip(String id) {
        return uploadTooltip(id);
    }

    protected void galleryCardCloudClicked(String id) {
        if (canUseCloudAction(id)) {
            browseModel(id);
            uploadSelectedModel();
        } else {
            setGalleryMessage(uploadTooltip(id));
        }

    }

    protected void drawGalleryCardCloud(DrawContext context, String id, int x, int y) {
        PrivateModelSyncClient.UploadState state = this.runtime.privateModelUploadState(id);
        boolean saved = storedPrivateModel(id) != null;
        int tint = this.runtime.deletingUploadedPrivateModel(id) || state.inProgress() ? 0xffffd589
                : (this.runtime.uploadedPrivateModelIds().contains(id) ? 0xff8df2b5
                        : (saved || state.inProgress() || state.published() ? 0xffffd589
                                : (canUploadModel(id) ? 0xfff3f0e0 : 0xff8a929c)));
        CloudUploadIcon.draw(context, x, y, tint, saved || state.published());
    }

    protected boolean canUseGalleryModel(String id, LocalModelLibrary.Loaded loaded) {
        return loaded != null && (!this.uploadedOnly || this.runtime.uploadedPrivateModelIds().contains(id));
    }

    protected String galleryUseTooltip() {
        return this.runtime.canActivateLocalAppearance() ? "使用所选模型，仅自己可见\n云朵按钮可上传分享" : "请先解除服务器伪装\n客户端与服务端外观不能同时使用";
    }

    protected GalleryModelUseState.State galleryUseState(String id, LocalModelLibrary.Loaded loaded) {
        if (!this.pendingSavedShare.isEmpty()) {
            return new GalleryModelUseState.State(
                    id.equals(this.pendingSavedShare) ? GalleryModelUseState.Phase.WAITING
                            : GalleryModelUseState.Phase.BLOCKED,
                    id.equals(this.pendingSavedShare) ? "核对分享资源" : "等待核对", false);
        }

        if (this.uploadedOnly && !this.runtime.uploadedPrivateModelIds().contains(id)) {
            return new GalleryModelUseState.State(GalleryModelUseState.Phase.BLOCKED, "使用模型", false);
        }

        if (matchedSavedModel(id) != null && this.runtime.privateModelUploadState(id).inProgress()) {
            return new GalleryModelUseState.State(GalleryModelUseState.Phase.WAITING, "正在恢复分享", false);
        }

        boolean current = this.runtime.currentAppearanceSource() == ClientRuntime.AppearanceSource.CLIENT;
        GalleryModelUseState.State state = GalleryModelUseState.local(id, loaded == null ? "" : loaded.hash(),
                current ? this.runtime.currentAppearanceModelId() : "",
                current ? this.runtime.currentAppearanceAssetHash() : "", this.runtime.pendingLocalAppearanceModelId(),
                this.runtime.canActivateLocalAppearance(), canUseGalleryModel(id, loaded));
        if (loaded != null && matchedSavedModel(id) != null && this.runtime.canShareLocalModel()
                && (state.current() || state.canUse())) {
            boolean published = this.runtime.privateModelUploadState(id).published();
            return new GalleryModelUseState.State(state.phase(),
                    state.current() ? (published ? "当前分享" : "恢复分享") : "使用并分享", !state.current() || !published);
        }
        return state;
    }

    protected String galleryUseTooltip(GalleryModelUseState.State state) {
        if (!this.pendingSavedShare.isEmpty()) {
            return "正在核对已上传模型的本机完整资源\n校验完成后重新检查发布权限\n尚未恢复分享";
        }

        if (matchedSavedModel(this.selection.modelId()) != null
                && this.runtime.privateModelUploadState(this.selection.modelId()).inProgress()) {
            return "正在恢复此存档的私人分享\n本机内容已匹配\n" + this.runtime.privateModelUploadState(this.selection.modelId()).message();
        }

        if (this.uploadedOnly && !cloudModelHint(this.selection.modelId()).isEmpty()) {
            return "云端已保存\n" + cloudModelHint(this.selection.modelId()).replace("云端已保存 · ", "")
                    + "\n本机内容一致时才能使用\n云朵按钮可管理存档与删除服务器资源";
        }

        if (matchedSavedModel(this.selection.modelId()) != null && this.runtime.canShareLocalModel()) {
            if (state.current() && this.runtime.privateModelUploadState(this.selection.modelId()).published()) {
                return "当前正在分享此私人模型\n获准模组观看者可见\n云朵存档管理可结束分享\n本地外观与服务器存档保留";
            }

            return "使用此已上传模型并恢复多人分享\n点击时核对本机完整文件\n服务器缓存可免去重复传输\n获准的模组观看者可见\n不创建服务器伪装";
        }
        if (state.current()) {
            return "当前正在使用此模型\n选择其他模型可切换";
        }

        if (state.waiting()) {
            return localGallery() ? "正在加载所选模型\n请等待加载完成" : "请求已发送\n等待服务器确认伪装";
        }

        if (localGallery()) {
            return galleryUseTooltip() + "\n未确认匹配存档或没有发布权限时，仅本机使用";
        }

        return galleryUseTooltip();
    }

    protected String galleryBrowsingMessage() {
        return "预览中，点击使用模型才应用";
    }

    protected boolean isFavoriteModel(String id) {
        return this.runtime.options.isFavorite(id);
    }

    protected void toggleModelFavorite(String id) {
        this.runtime.options.toggleFavorite(id);
    }

    protected void setGalleryMessage(String value) {
        this.message = value;
    }

    protected void refreshGalleryModels() {
        refreshModels(false);
    }

    protected void resetGallery() {
        cancelSavedShareCheck();
        clearGalleryPreviews();
        this.models = List.of();
        this.filtered = List.of();
        this.visible = List.of();
        this.groupSource = this.sourceFilter = this.page = 0;
        this.groupDirectory = this.serverGroupDirectory = this.query = this.message = "";
        this.favoritesOnly = this.uploadedOnly = false;
        this.groupPages.clear();
        this.selection.restore("");
    }

    protected void buildHeaderControls() {
    }

    protected void buildAlternateControls() {
    }

    private void buildGalleryControls() {
        if (this.models.isEmpty()) {
            refreshModels(false);
        }

        boolean searchFocused = this.search != null && this.search.isFocused();
        int toolsWidth = localGallery() ? 167 : 123;
        this.search = new TextFieldWidget(super.textRenderer, this.right + 5, this.top + 5,
                Math.max(35, this.rightWidth - toolsWidth), 20, Text.literal("搜索模型"));
        this.search.setMaxLength(80);
        this.search.setPlaceholder(Text.literal("搜索名称 / ID"));
        this.search.setText(this.query);
        this.search.setChangedListener(value -> {
            this.query = value;
            this.page = 0;
            rebuildGrid();
        });
        addDrawableChild(this.search);
        if (searchFocused) {
            setInitialFocus(this.search);
        }

        int favoritesX = this.right + this.rightWidth - (localGallery() ? 157 : 113);
        iconButton(this.favoritesOnly ? "★ 收藏" : "☆ 收藏", favoritesX, this.top + 5, 20, 0, 0, () -> {
            this.favoritesOnly = !this.favoritesOnly;
            this.page = 0;
            clearAndInit();
        }, "只显示收藏的模型\n卡片右上角可收藏");
        button(this.runtime.options.showModelIds ? "ID ✓" : "ID", favoritesX + 22, this.top + 5, 20, () -> {
            this.runtime.options.showModelIds = !this.runtime.options.showModelIds;
            this.runtime.options.save();
            clearAndInit();
        }, "优先在卡片上显示模型 ID");

        if (localGallery()) {
            cloudButton("已上传", this.right + this.rightWidth - 113, this.top + 5, 20, () -> {
                this.uploadedOnly = !this.uploadedOnly;
                this.groupSource = this.sourceFilter = this.page = 0;
                this.groupDirectory = "";
                clearGalleryPreviews();
                refreshModels(false);
                clearAndInit();
            }, () -> this.uploadedOnly, this.runtime::privateUploadCatalogReady,
                    () -> this.runtime.privateUploadCatalogReady() ? "只显示服务器已保存的本人模型\n本地文件须与保存内容一致"
                            : this.runtime.privateUploadCatalogStatus());
            iconButton(
                    this.groupSource > 0 ? "↑ 全部模型"
                            : new String[] { "全部来源", "内置", "本地 YSM", "本地 BB" }[this.sourceFilter],
                    this.right + this.rightWidth - 91, this.top + 5, 20, this.groupSource > 0 ? 0 : 32,
                    this.groupSource > 0 ? 32 : 0, () -> {
                        if (this.groupSource > 0) {
                            navigateUp();
                        } else {
                            this.sourceFilter = (this.sourceFilter + 1) % 4;
                            this.page = 0;
                            clearAndInit();
                        }
                    }, this.groupSource > 0 ? "返回上一级模型分组" : "切换模型来源筛选\n点击目录卡片进入分组");
            iconButton("文件夹", this.right + this.rightWidth - 69, this.top + 5, 20, 80, 0, () -> {
                Util.getOperatingSystem().open(this.runtime.localModelDirectory());
                this.message = "放入模型后点击刷新";
            }, "打开本地模型目录");
            button("刷新", this.right + this.rightWidth - 47, this.top + 5, 20, () -> {
                this.runtime.refreshLocalModelSources();
                refreshModels(true);
                this.page = 0;
                rebuildGrid();
                this.message = "已刷新本地模型";
            }, "重新扫描模型与预览\n保留当前使用的外观");
        } else {
            ButtonWidget up = iconButton("返回上级", this.right + this.rightWidth - 69, this.top + 5, 20, 0, 32,
                    this::navigateUp, this.serverGroupDirectory.isEmpty() ? "选择来源文件夹浏览模型" : "返回上一级模型目录");
            up.active = !this.serverGroupDirectory.isEmpty();
            iconButton("缓存目录", this.right + this.rightWidth - 47, this.top + 5, 20, 80, 0,
                    this::openServerCacheDirectory, "打开本机服务器模型缓存\nconfig/meplayeractions/cache");
        }
        iconButton("使用教程", this.right + this.rightWidth - 25, this.top + 5, 20, 80, 16,
                () -> super.client.setScreen(new ModelHelpScreen(this.runtime, this, !localGallery())),
                localGallery() ? "客户端模型教程\n导入、使用、上传分享与存档管理" : "服务端模型教程\n伪装、外观设置与客户端接管");
        buildSelectionControls();
        rebuildGrid();
    }

    protected void buildSelectionControls() {
        this.use = button("使用模型", this.left + 5, this.bottom - 44, this.previewWidth - 10, this::useSelectedModel,
                galleryUseTooltip());
        int half = (this.previewWidth - 13) / 2;
        this.settings = button(settingsButtonLabel(half), this.left + 5, this.bottom - 21, half,
                () -> super.client.setScreen(new ModelSettingsScreen(this.runtime, this.selection.modelId(), this)),
                "查看模型详情\n设置缩放、位置与作者配置");
        this.upload = cloudButton("上传分享", this.left + 8 + half, this.bottom - 21, half, this::uploadSelectedModel,
                () -> storedPrivateModel(this.selection.modelId()) != null
                        || this.runtime.privateModelUploadState(this.selection.modelId()).published(),
                () -> canUseCloudAction(this.selection.modelId()), () -> uploadTooltip(this.selection.modelId()),
                () -> this.runtime.deletingUploadedPrivateModel(this.selection.modelId()) ? "删除中"
                        : (storedPrivateModel(this.selection.modelId()) != null ? "已上传"
                                : (this.runtime.privateModelUploadState(this.selection.modelId()).published() ? "已分享"
                                        : "上传分享")));
    }

    protected void buildFooterControls() {
        button("返回", this.left, this.footerY, 52, this::close, "返回上一页");
        button("恢复默认", this.left + 56, this.footerY, 65, () -> {
            this.runtime.options.defaultHeaddress = true;
            this.runtime.options.defaultBlueTexture = false;
            LocalAppearanceSettings defaults = LocalAppearanceSettings.defaults();
            this.runtime.updateLocalAppearance(defaults);
            refreshModels(true);
            this.selection.restore(defaults.modelId());
            this.message = "已恢复默认，本地外观关闭";
            rebuildGrid();
        }, "恢复默认模型、缩放与位置\n关闭私人外观");
    }

    protected ButtonWidget button(String label, int x, int y, int w, Runnable action, String tooltip) {
        return flatButton(label, x, y, w, 20, action, tooltip, false);
    }

    protected ButtonWidget flatButton(String label, int x, int y, int w, int h, Runnable action, String tooltip,
            boolean selected) {
        ButtonWidget button = new ButtonWidget(x, y, Math.max(20, w), h, Text.literal(label), b -> action.run(),
                narration -> narration.get()) {
            @Override
            protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
                context.fill(getX(), getY(), getRight(), getBottom(), selected ? -14774017 : -12369342);
                if (super.hovered || isFocused()) {
                    context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
                }

                String text = LocalAppearanceScreen.super.textRenderer.trimToWidth(getMessage().getString(),
                        Math.max(1, getWidth() - 6));
                context.drawCenteredTextWithShadow(LocalAppearanceScreen.super.textRenderer, text,
                        getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2,
                        super.active ? 0xfff3f0e0 : 0xff8a929c);
            }
        };
        button.setTooltip(ModelUiTooltip.of(super.textRenderer, LocalAppearanceScreen.super.width, tooltip));
        return addDrawableChild(button);
    }

    protected ButtonWidget iconButton(String label, int x, int y, int w, int u, int v, Runnable action,
            String tooltip) {
        ButtonWidget button = new ButtonWidget(x, y, Math.max(20, w), 20, Text.literal(label), b -> action.run(),
                narration -> narration.get()) {
            @Override
            protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
                context.fill(getX(), getY(), getRight(), getBottom(), -12369342);
                if (super.hovered || isFocused()) {
                    context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
                }

                context.drawTexture(RenderPipelines.GUI_TEXTURED, GUI_ICONS, getX() + (getWidth() - 16) / 2, getY() + 2,
                        (float) u, (float) v, 16, 16, 256, 256);
            }
        };
        button.setTooltip(ModelUiTooltip.of(super.textRenderer, LocalAppearanceScreen.super.width, tooltip));
        return addDrawableChild(button);
    }

    private ButtonWidget cloudButton(String label, int x, int y, int w, Runnable action, BooleanSupplier selected,
            BooleanSupplier enabled, Supplier<String> tooltip) {
        return cloudButton(label, x, y, w, action, selected, enabled, tooltip,
                () -> selected.getAsBoolean() ? "已上传" : label);
    }

    private ButtonWidget cloudButton(String label, int x, int y, int w, Runnable action, BooleanSupplier selected,
            BooleanSupplier enabled, Supplier<String> tooltip, Supplier<String> caption) {
        ButtonWidget button = new ButtonWidget(x, y, Math.max(20, w), 20, Text.literal(label), b -> action.run(),
                narration -> narration.get()) {
            @Override
            protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
                super.active = enabled.getAsBoolean();
                setTooltip(ModelUiTooltip.of(LocalAppearanceScreen.super.textRenderer,
                        LocalAppearanceScreen.super.width, tooltip.get()));
                setMessage(net.minecraft.text.Text.literal(caption.get()));
                context.fill(getX(), getY(), getRight(), getBottom(), selected.getAsBoolean() ? -14774017 : -12369342);
                if (super.hovered || isFocused()) {
                    context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
                }

                CloudUploadIcon.draw(context, getX() + 2, getY() + 2, super.active ? 0xfff3f0e0 : 0xff8a929c,
                        selected.getAsBoolean());
                if (getWidth() > 24) {
                    clipped(context, getMessage().getString(), getX() + 20, getY() + 6, getWidth() - 22,
                            super.active ? 0xfff3f0e0 : 0xff8a929c);
                }

            }
        };
        button.active = enabled.getAsBoolean();
        button.setTooltip(ModelUiTooltip.of(super.textRenderer, LocalAppearanceScreen.super.width, tooltip.get()));
        return addDrawableChild(button);
    }

    private boolean canUploadModel(String id) {
        LocalAppearanceScreen.PreviewEntry entry = this.previews.get(id);
        return localGallery() && !id.isEmpty() && this.runtime.canShareLocalModel() && entry != null
                && entry.loaded != null && !this.runtime.privateModelUploadState(id).inProgress();
    }

    private boolean canUseCloudAction(String id) {
        if (id.isEmpty() || !this.pendingSavedShare.isEmpty() || this.runtime.deletingUploadedPrivateModel(id)) {
            return false;
        }

        if (storedPrivateModel(id) != null) {
            return this.runtime.privateUploadCatalogReady();
        }

        return this.runtime.privateModelUploadState(id).published() || canUploadModel(id);
    }

    private PrivateUploadCatalogSnapshot.Model storedPrivateModel(String id) {
        return this.runtime.uploadedPrivateModels().stream().filter(model -> model.modelId().equals(id)).findFirst()
                .orElse(null);
    }

    private PrivateUploadCatalogSnapshot.Model matchedSavedModel(String id) {
        if (!this.runtime.privateUploadCatalogReady() || this.runtime.deletingUploadedPrivateModel(id)) {
            return null;
        }

        PrivateUploadCatalogSnapshot.Model expected = storedPrivateModel(id);
        return expected != null && this.runtime.uploadedPrivateModelIds().contains(id) ? expected : null;
    }

    private String cloudModelHint(String id) {
        if (!this.uploadedOnly || storedPrivateModel(id) == null
                || this.runtime.uploadedPrivateModelIds().contains(id)) {
            return "";
        }

        if (!this.runtime.privateUploadCatalogReady()) {
            return "云端已保存 · 正在核对本机文件";
        }

        return this.localSourceIds.contains(id) ? "云端已保存 · 本机文件内容不同" : "云端已保存 · 本机未找到模型";
    }

    private String uploadTooltip(String id) {
        if (this.runtime.deletingUploadedPrivateModel(id)) {
            return "删除中\n等待服务器确认\n本地文件将保留";
        }

        if (storedPrivateModel(id) != null) {
            return "服务器已保存\n点击管理：再次分享、结束分享或删除\n再次分享需要本机完整内容一致\n删除会结束相关分享，本地文件保留\n"
                    + (this.runtime.uploadedPrivateModelIds().contains(id) ? "本机内容一致"
                            : (!this.runtime.privateUploadCatalogReady() ? "正在核对本机内容"
                                    : (this.localSourceIds.contains(id) ? "本机文件内容不同" : "本机未找到模型")))
                    + (this.runtime.privateUploadDeleteStatus(id).isEmpty() ? ""
                            : "\n" + this.runtime.privateUploadDeleteStatus(id));
        }

        if (this.runtime.privateModelUploadState(id).published()) {
            return "已分享，尚未确认服务器保存\n点击结束私人分享\n保留本地文件与服务器缓存";
        }

        if (!this.runtime.canActivateLocalAppearance()) {
            return "请先解除服务器伪装\n私人模型上传与服务器伪装不能同时使用";
        }

        if (!this.runtime.canShareLocalModel()) {
            return "当前无法上传私人模型\n" + this.runtime.privateSyncStatus();
        }

        if (id.isEmpty()) {
            return "先选择私人模型";
        }

        PrivateModelSyncClient.UploadState state = this.runtime.privateModelUploadState(id);
        return "使用此模型并上传分享\n获准的模组玩家可见\n原版玩家看见原版人物\n" + state.message() + (state.uploaded() ? "\n服务器已保存；点击可再次分享" : "");
    }

    private void uploadSelectedModel() {
        String id = this.selection.modelId();
        if (!canUseCloudAction(id)) {
            this.message = uploadTooltip(id);
            return;
        }
        PrivateUploadCatalogSnapshot.Model expected = storedPrivateModel(id);
        if (expected != null) {
            super.client.setScreen(new PrivateUploadManagementScreen(this.runtime, this, expected, label(id),
                    status -> this.message = status, () -> {
                        this.observedDeleteId = id;
                        this.observedDeleteStatus = this.runtime.privateUploadDeleteStatus(id);
                    }));
            return;
        }
        PrivateModelSyncClient.UploadState shared = this.runtime.privateModelUploadState(id);
        if (shared.published()) {
            PrivateModelSyncClient.PublicationIdentity publication = this.runtime.privatePublicationIdentity();
            if (publication == null || !publication.modelId().equals(id)) {
                this.message = "私人分享已变化，请重新选择";
                return;
            }
            super.client.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed) {
                    this.message = this.runtime.stopPrivatePublication(publication) ? "已结束私人分享，本地模型与服务器缓存保留"
                            : "私人分享已变化或结束，请重新确认；当前分享未改动";
                }
                super.client.setScreen(this);
            }, Text.literal("结束私人分享？"), Text.literal("其他玩家将不再显示你的私人模型。\n本地模型文件和已保存的服务器缓存保留。"), Text.literal("结束分享"),
                    Text.literal("取消")));
            return;
        }
        if (!canUploadModel(id)) {
            this.message = uploadTooltip(id);
            return;
        }
        this.message = this.runtime.uploadAndShareLocalModel(id) ? "" : "当前无法上传私人模型";
        updateSelectionButtons();
    }

    private void openServerCacheDirectory() {
        try {
            Files.createDirectories(this.runtime.localServerCacheDirectory());
            Util.getOperatingSystem().open(this.runtime.localServerCacheDirectory());
            this.message = "已打开本机服务器模型缓存";
        } catch (IOException | SecurityException unavailable) {
            this.message = "无法打开本机服务器模型缓存目录";
        }
    }

    private void refreshModels(boolean clear) {
        if (clear) {
            this.generation++;
            this.previews.clear();
            this.inFlight = 0;
            this.preview.clear();
        }
        this.models = List.copyOf(galleryModels());
        if (localGallery()) {
            this.localSourceIds = Set.copyOf(this.models.stream().map(ClientRuntime.Action::id).toList());
            if (this.uploadedOnly) {
                LinkedHashMap<String, ClientRuntime.Action> byId = new LinkedHashMap<String, ClientRuntime.Action>();
                this.models.forEach(model -> byId.put(model.id(), model));
                this.runtime.uploadedPrivateModels().forEach(model -> byId.putIfAbsent(model.modelId(),
                        new ClientRuntime.Action(model.modelId(), model.modelId())));
                this.models = List.copyOf(byId.values());
            }
        } else {
            while (!this.serverGroupDirectory.isEmpty() && this.models.stream()
                    .noneMatch(model -> nonLocalGalleryContains(this.serverGroupDirectory, model.id()))) {
                this.serverGroupDirectory = nonLocalGalleryParent(this.serverGroupDirectory);
                this.page = 0;
            }
        }

        this.selection.retain(this.models.stream().map(ClientRuntime.Action::id).toList());
    }

    private void rebuildGrid() {
        if (!clientGalleryVisible()) {
            return;
        }

        for (ButtonWidget widget : this.gridWidgets) {
            remove(widget);
        }

        this.gridWidgets.clear();
        String needle = this.query.strip().toLowerCase(Locale.ROOT);
        this.filtered = this.models.stream().filter(model -> (!this.favoritesOnly || isFavoriteModel(model.id()))
                && (!this.uploadedOnly || storedPrivateModel(model.id()) != null)
                && (!localGallery() || this.sourceFilter == 0 || source(model.id()) == this.sourceFilter)
                && (!localGallery() || this.groupSource == 0 || source(model.id()) == this.groupSource)
                && (!localGallery() || this.groupSource == 0 || ModelGalleryIndex.contains(groupPath(), model.id()))
                && (model.id().toLowerCase(Locale.ROOT).contains(needle)
                        || label(model.id()).toLowerCase(Locale.ROOT).contains(needle)
                        || model.label().toLowerCase(Locale.ROOT).contains(needle)))
                .toList();
        ArrayList<LocalAppearanceScreen.ModelGroup> groups = new ArrayList<ModelGroup>();
        if (localGallery() && this.groupSource == 0 && this.sourceFilter == 0 && !this.favoritesOnly
                && !this.uploadedOnly && needle.isEmpty()) {
            for (int kind = 1; kind <= 3; kind++) {
                int type = kind, count = (int) this.models.stream().filter(model -> source(model.id()) == type).count();
                if (count > 0) {
                    groups.add(new ModelGroup(type, new String[] { "", "builtin/", "ysm/", "bbmodel/" }[type],
                            new String[] { "", "内置模型", "YSM 模型", "BBModel 文件" }[type], count));
                }

            }
        }

        if (localGallery() && this.groupSource > 0 && !this.favoritesOnly && !this.uploadedOnly && needle.isEmpty()) {
            for (ModelGalleryIndex.Folder folder : ModelGalleryIndex
                    .folders(this.filtered.stream().map(ClientRuntime.Action::id).toList(), groupPath())) {
                groups.add(new ModelGroup(this.groupSource, folder.path(), folder.label(), folder.count()));
            }

            this.filtered = this.filtered.stream().filter(model -> ModelGalleryIndex.direct(groupPath(), model.id()))
                    .toList();
        }
        if (!localGallery() && !this.favoritesOnly && needle.isEmpty()) {
            groups.addAll(nonLocalGalleryFolders(this.filtered, this.serverGroupDirectory));
            this.filtered = this.filtered.stream()
                    .filter(model -> nonLocalGalleryDirect(this.serverGroupDirectory, model.id())).toList();
        }
        this.filteredGroups = List.copyOf(groups);
        this.page = Math.max(0, Math.min(this.page, pageCount() - 1));
        int start = this.page * this.pageSize;
        int end = start + this.pageSize, modelStart = Math.max(0, start - this.filteredGroups.size()),
                modelEnd = Math.max(0, end - this.filteredGroups.size());
        this.visibleGroups = this.filteredGroups.subList(Math.min(start, this.filteredGroups.size()),
                Math.min(end, this.filteredGroups.size()));
        this.visible = this.filtered.subList(Math.min(modelStart, this.filtered.size()),
                Math.min(modelEnd, this.filtered.size()));
        int cellW = CARD_WIDTH;
        int cellH = CARD_HEIGHT;
        for (int i = 0; i < this.visibleGroups.size(); i++) {
            LocalAppearanceScreen.GroupCard card = new GroupCard(this.visibleGroups.get(i),
                    this.right + 5 + i % this.columns * (cellW + CARD_GAP),
                    this.gridTop + i / this.columns * (cellH + CARD_GAP), cellW, cellH);
            this.gridWidgets.add(addDrawableChild(card));
        }
        for (int i = 0; i < this.visible.size(); i++) {
            int slot = i + this.visibleGroups.size();
            LocalAppearanceScreen.ModelCard card = new ModelCard(this.visible.get(i),
                    this.right + 5 + slot % this.columns * (cellW + CARD_GAP),
                    this.gridTop + slot / this.columns * (cellH + CARD_GAP), cellW, cellH);
            this.gridWidgets.add(addDrawableChild(card));
        }
        int navWidth = Math.min(62, (this.rightWidth - 58) / 2);
        ButtonWidget previous = button("上一页", this.right + 5, this.bottom - 21, navWidth, () -> {
            goToPage(this.page - 1);
        }, "浏览上一页模型");
        previous.active = this.page > 0;
        this.gridWidgets.add(previous);
        ButtonWidget next = button("下一页", this.right + this.rightWidth - navWidth - 5, this.bottom - 21, navWidth,
                () -> {
                    goToPage(this.page + 1);
                }, "浏览下一页模型");
        next.active = this.page + 1 < pageCount();
        this.gridWidgets.add(next);

        Set<String> retained = new HashSet<>();
        if (localGallery() && this.runtime.localAppearance().enabled()) {
            retained.add(this.runtime.localAppearance().modelId());
        }

        if (!this.selection.modelId().isEmpty()) {
            retained.add(this.selection.modelId());
        }

        this.visible.forEach(model -> retained.add(model.id()));
        this.previews.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) {
                return false;
            }

            if (entry.getValue().loaded != null) {
                this.preview.release(key(entry.getKey(), entry.getValue().loaded));
            }

            return true;
        });
        if (!this.selection.modelId().isEmpty()) {
            this.previews.computeIfAbsent(this.selection.modelId(), ignored -> new PreviewEntry());
        }

        if (localGallery() && this.runtime.localAppearance().enabled()) {
            this.previews.computeIfAbsent(this.runtime.localAppearance().modelId(), ignored -> new PreviewEntry());
        }

        this.visible.forEach(model -> this.previews.computeIfAbsent(model.id(), ignored -> new PreviewEntry()));
        updateSelectionButtons();
        pumpLoads();
    }

    /** These accessors also let the GUI harness assert selection/filter/page behavior without applying models. */
    public String selectedModelId() {
        return this.selection.modelId();
    }

    public List<String> visibleModelIds() {
        return this.visible.stream().map(ClientRuntime.Action::id).toList();
    }

    public List<String> visibleDirectoryPaths() {
        return this.visibleGroups.stream().map(ModelGroup::path).toList();
    }

    public int pageIndex() {
        return this.page;
    }

    public int pageCount() {
        return Math.max(1, (this.filtered.size() + this.filteredGroups.size() + Math.max(1, this.pageSize) - 1)
                / Math.max(1, this.pageSize));
    }

    public void setSearchQuery(String value) {
        if (this.search != null) {
            this.search.setText(value);
        } else {
            this.query = value;
        }
    }

    public void setFavoritesOnly(boolean value) {
        this.favoritesOnly = value;
        this.page = 0;
        clearAndInit();
    }

    public void goToPage(int index) {
        this.page = index;
        rebuildGrid();
        this.groupPages.put(groupPath(), this.page);
    }

    public String groupPath() {
        return !localGallery() ? this.serverGroupDirectory
                : (this.groupSource == 0 ? "" : ModelGalleryIndex.root(this.groupSource) + this.groupDirectory);
    }

    public void browseGroup(int source) {
        if (source < 1 || source > 3 || this.models.stream().noneMatch(model -> source(model.id()) == source)) {
            return;
        }

        this.groupPages.put(groupPath(), this.page);
        this.groupSource = source;
        this.groupDirectory = "";
        this.sourceFilter = 0;
        this.query = "";
        this.page = this.groupPages.getOrDefault(groupPath(), 0);
        clearAndInit();
    }

    /** Only current directory cards can be opened; an arbitrary string never addresses a file or network resource. */
    public boolean browseDirectory(String path) {
        LocalAppearanceScreen.ModelGroup group = this.filteredGroups.stream().filter(value -> value.path().equals(path))
                .findFirst().orElse(null);
        if (group == null) {
            return false;
        }

        browseGroup(group);
        return true;
    }

    private void browseGroup(ModelGroup group) {
        if (openGalleryDestination(group)) {
            return;
        }

        if (!localGallery()) {
            this.groupPages.put(groupPath(), this.page);
            this.serverGroupDirectory = group.path();
            this.query = "";
            this.page = this.groupPages.getOrDefault(groupPath(), 0);
            clearAndInit();
            return;
        }
        this.groupPages.put(groupPath(), this.page);
        this.groupSource = group.source();
        this.groupDirectory = group.path().substring(ModelGalleryIndex.root(this.groupSource).length());
        this.sourceFilter = 0;
        this.query = "";
        this.page = this.groupPages.getOrDefault(groupPath(), 0);
        clearAndInit();
    }

    public void navigateUp() {
        if (!localGallery()) {
            if (this.serverGroupDirectory.isEmpty()) {
                return;
            }

            this.groupPages.put(groupPath(), this.page);
            this.serverGroupDirectory = nonLocalGalleryParent(this.serverGroupDirectory);
            this.query = "";
            this.page = this.groupPages.getOrDefault(groupPath(), 0);
            clearAndInit();
            return;
        }
        if (this.groupSource == 0) {
            return;
        }

        this.groupPages.put(groupPath(), this.page);
        if (this.groupDirectory.isEmpty()) {
            this.groupSource = 0;
        }

        else {
            this.groupDirectory = ModelGalleryIndex.parent(groupPath())
                    .substring(ModelGalleryIndex.root(this.groupSource).length());
        }

        this.page = this.groupPages.getOrDefault(groupPath(), 0);
        clearAndInit();
    }

    public void toggleSelectedFavorite() {
        if (this.selection.modelId().isEmpty()) {
            return;
        }

        toggleModelFavorite(this.selection.modelId());
        rebuildGrid();
    }

    public int loadedPreviewCount() {
        return (int) this.previews.values().stream().filter(entry -> entry.loaded != null).count();
    }

    public int drawnPreviewCount() {
        return this.drawnPreviewCount;
    }

    public Map<String, Object> previewDiagnostics() {
        return this.preview.diagnostics();
    }

    public Map<String, Object> previewDiagnostics(float minU, float minV, float maxU, float maxV) {
        return this.preview.diagnostics(minU, minV, maxU, maxV);
    }

    /** Actual initialized card bounds and this frame's mesh submissions, rather than a synthetic UI plan. */
    public Map<String, Object> galleryDiagnostics() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("referenceRevision", "0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85");
        result.put("layout", "left-preview/right-search-model-cards");
        result.put("groupPath", groupPath());
        result.put("panel",
                Map.of("x", this.left, "y", this.frameTop, "width", this.panelWidth, "height",
                        this.frameBottom - this.frameTop, "columns", this.columns, "rows", this.rows, "compact",
                        this.compactLayout));
        result.put("search", this.query);
        result.put("favoritesOnly", this.favoritesOnly);
        result.put("uploadedOnly", this.uploadedOnly);
        result.put("sourceFilter", this.sourceFilter);
        result.put("page", this.page);
        result.put("pageCount", pageCount());
        result.put("selectedModelId", this.selection.modelId());
        result.put("leftPreview",
                Map.of("x", this.previewX, "y", this.previewY, "width", this.previewW, "height", this.previewH,
                        "modelId", this.leftPreviewId, "key", this.leftPreviewKey, "drawn", this.leftPreviewDrawn,
                        "appearanceSource", this.leftPreviewSource, "instance", this.leftPreviewInstance, "hash",
                        this.leftPreviewHash));
        result.put("cards", this.gridWidgets.stream().filter(widget -> widget instanceof ModelCard).map(widget -> {
            LocalAppearanceScreen.ModelCard card = (ModelCard) widget;
            LocalAppearanceScreen.PreviewEntry entry = this.previews.get(card.model.id());
            LinkedHashMap<String, Object> info = new LinkedHashMap<String, Object>();
            info.put("modelId", card.model.id());
            info.put("label", card.model.label());
            info.put("x", card.getX());
            info.put("y", card.getY());
            info.put("width", card.getWidth());
            info.put("height", card.getHeight());
            info.put("selected", this.selection.modelId().equals(card.model.id()));
            info.put("favorite", isFavoriteModel(card.model.id()));
            info.put("loaded", entry != null && entry.loaded != null);
            if (localGallery()) {
                info.put("upload", this.runtime.privateModelUploadState(card.model.id()).phase().name());
            }

            info.put("error", entry == null ? "" : entry.error);
            info.put("drawn", this.drawnCards.contains(card.model.id()));
            return Map.copyOf(info);
        }).toList());
        result.put("groups", this.gridWidgets.stream().filter(widget -> widget instanceof GroupCard).map(widget -> {
            LocalAppearanceScreen.GroupCard card = (GroupCard) widget;
            return Map.<String, Object>of("path", card.group.path(), "label", card.group.label(), "count",
                    card.group.count(), "x", card.getX(), "y", card.getY(), "width", card.getWidth(), "height",
                    card.getHeight());
        }).toList());
        result.put("preview", this.preview.diagnostics());
        return Map.copyOf(result);
    }

    public boolean browseModel(String id) {
        if (this.models.stream().noneMatch(model -> model.id().equals(id))) {
            return false;
        }

        boolean cancelled = !this.pendingSavedShare.isEmpty() && !this.pendingSavedShare.equals(id);
        if (cancelled) {
            cancelSavedShareCheck();
        }

        this.selection.browse(id);
        this.message = cancelled ? "已取消原模型的分享核对，当前仅预览" : galleryBrowsingMessage();
        this.yaw = 0;
        this.pitch = -8;
        LocalAppearanceScreen.PreviewEntry entry = this.previews.get(id);
        if (entry != null && entry.loaded != null) {
            this.preview.restart(key(id, entry.loaded), ModelPreview.Context.SELECTED);
        }

        rebuildGrid();
        return true;
    }

    public void useSelectedModel() {
        if (this.selection.modelId().isEmpty()) {
            return;
        }

        if (!this.runtime.canActivateLocalAppearance()) {
            this.message = "请先解除服务器伪装，再使用私人模型";
            return;
        }
        LocalAppearanceScreen.PreviewEntry entry = this.previews.get(this.selection.modelId());
        if (entry == null || entry.loaded == null) {
            this.message = "请等待模型预览加载完成";
            return;
        }
        if (!galleryUseState(this.selection.modelId(), entry.loaded).canUse()) {
            return;
        }

        PrivateUploadCatalogSnapshot.Model saved = matchedSavedModel(this.selection.modelId());
        if (saved != null && this.runtime.canShareLocalModel()) {
            restoreSavedShare(saved);
            return;
        }
        this.runtime.setPrivateSyncEnabled(false);
        this.runtime.selectLocalModel(this.selection.modelId());
        this.runtime.options.showSelf = true;
        this.runtime.options.save();
        this.message = "";
        updateSelectionButtons();
    }

    private void restoreSavedShare(PrivateUploadCatalogSnapshot.Model expected) {
        if (this.savedShareChecker == null) {
            this.savedShareChecker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                    runnable -> {
                        Thread thread = new Thread(runnable, "MPA-gallery-reshare-check");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
        }

        this.pendingSavedShare = expected.modelId();
        long revision = ++this.savedShareRevision;
        this.message = "正在核对已上传模型，尚未恢复分享";
        updateSelectionButtons();
        CompletableFuture<Boolean> checked;
        try {
            checked = CompletableFuture.supplyAsync(() -> {
                try {
                    return PrivateUploadSourceCheck.matches(this.runtime.localModelDirectory(), expected);
                } catch (IOException | RuntimeException failure) {
                    throw new CompletionException(failure);
                }
            }, this.savedShareChecker);
        } catch (RejectedExecutionException busy) {
            this.pendingSavedShare = "";
            this.message = "本机校验任务繁忙，请稍后重试";
            updateSelectionButtons();
            return;
        }
        checked.whenComplete((matches, error) -> super.client.execute(() -> {
            if (!this.activeView || revision != this.savedShareRevision) {
                return;
            }

            this.pendingSavedShare = "";
            if (!this.selection.modelId().equals(expected.modelId())) {
                this.message = "分享核对已取消：当前已选择其他模型";
            }

            else if (error != null || !Boolean.TRUE.equals(matches)) {
                this.message = "未恢复分享：本机完整资源已缺失或与服务器存档不同，请检查文件并刷新";
            }

            else if (!localGallery() || !expected.equals(matchedSavedModel(expected.modelId()))
                    || !this.runtime.canShareLocalModel() || !this.runtime.canActivateLocalAppearance()) {
                this.message = "未恢复分享：服务器存档、权限或当前伪装已变化，请重新选择";
            }

            else if (this.runtime.uploadAndShareLocalModel(expected.modelId())) {
                this.message = "已请求恢复私人分享，等待服务器确认";
            }

            else {
                this.message = "当前无法恢复分享：" + this.runtime.privateSyncStatus();
            }

            updateSelectionButtons();
        }));
    }

    private void cancelSavedShareCheck() {
        this.savedShareRevision++;
        this.pendingSavedShare = "";
        if (this.savedShareChecker != null) {
            this.savedShareChecker.shutdownNow();
            this.savedShareChecker = null;
        }
    }

    protected void updateSelectionButtons() {
        if (this.use == null) {
            return;
        }

        LocalAppearanceScreen.PreviewEntry entry = this.previews.get(this.selection.modelId());
        GalleryModelUseState.State state = galleryUseState(this.selection.modelId(),
                entry == null ? null : entry.loaded);
        this.use.active = state.canUse();
        this.use.setMessage(Text.literal(state.buttonLabel()));
        this.use.setTooltip(
                ModelUiTooltip.of(super.textRenderer, LocalAppearanceScreen.super.width, galleryUseTooltip(state)));
        if (this.settings != null) {
            this.settings.active = !this.selection.modelId().isEmpty() && (!this.uploadedOnly
                    || this.runtime.uploadedPrivateModelIds().contains(this.selection.modelId()));
            this.settings.setTooltip(ModelUiTooltip.of(super.textRenderer, LocalAppearanceScreen.super.width,
                    this.settings.active ? "查看模型详情\n设置缩放、位置与作者配置" : "无法配置此服务器存档\n需要本机存在相同内容的模型"));
        }
        if (this.favorite != null) {
            this.favorite.active = !this.selection.modelId().isEmpty();
            String favoriteText = isFavoriteModel(this.selection.modelId()) ? "★ 已收藏" : "☆ 收藏";
            if (this.favorite.getWidth() < 50) {
                favoriteText = isFavoriteModel(this.selection.modelId()) ? "★" : "☆";
            }

            this.favorite.setMessage(
                    Text.literal(super.textRenderer.trimToWidth(favoriteText, this.favorite.getWidth() - 8)));
        }
    }

    private void pumpLoads() {
        if (!this.activeView || (localGallery() && !this.runtime.canEditLocalAppearance())
                || this.inFlight >= MAX_LOADS) {
            return;
        }

        List<String> order = new ArrayList<>();
        if (clientGalleryVisible() && !this.selection.modelId().isEmpty()) {
            this.previews.computeIfAbsent(this.selection.modelId(), ignored -> new PreviewEntry());
            order.add(this.selection.modelId());
        }
        if (localGallery() && this.runtime.localAppearance().enabled()) {
            String currentId = this.runtime.localAppearance().modelId();
            this.previews.computeIfAbsent(currentId, ignored -> new PreviewEntry());
            if (!order.contains(currentId)) {
                order.add(currentId);
            }

        }
        if (!clientGalleryVisible() && order.isEmpty()) {
            return;
        }

        if (clientGalleryVisible()) {
            for (ClientRuntime.Action model : this.visible) {
                if (!order.contains(model.id())) {
                    order.add(model.id());
                }

            }

        }

        for (String id : order) {
            if (this.inFlight >= MAX_LOADS) {
                break;
            }

            PreviewEntry entry = this.previews.get(id);
            if (entry == null || entry.requested) {
                continue;
            }

            entry.requested = true;
            this.inFlight++;
            long requestGeneration = this.generation;
            GalleryPreviewSelection.Request selectionRequest = this.selection.request(id);
            loadGalleryPreview(id).whenComplete((loaded, error) -> super.client.execute(() -> {
                if (!this.activeView || this.generation != requestGeneration) {
                    return;
                }

                this.inFlight--;
                if (this.previews.get(id) == entry) {
                    entry.loaded = loaded;
                    entry.error = error == null ? "" : loadError(error);
                    if (loaded != null && this.selection.isSelectedRequest(selectionRequest)) {
                        this.preview.restart(key(id, loaded), ModelPreview.Context.SELECTED);
                    }

                    updateSelectionButtons();
                }
                pumpLoads();
            }));
        }
    }

    static String loadError(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }

        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? "模型预览加载失败" : detail.replace('\n', ' ').replace('\r', ' ');
    }

    static int source(String id) {
        return ModelGalleryIndex.source(id);
    }

    static String sourceLabel(String id) {
        return switch (source(id)) {
        case 2 -> "本地 YSM";
        case 3 -> "本地 BBModel";
        default -> "内置";
        };
    }

    private static String key(String id, LocalModelLibrary.Loaded loaded) {
        return id + ":" + loaded.hash();
    }

    private YsmModelProfile profile(String id) {
        LocalAppearanceScreen.PreviewEntry entry = this.previews.get(id);
        return entry != null && entry.loaded != null ? entry.loaded.profile() : galleryProfile(id);
    }

    private String label(String id) {
        String fallback = this.models.stream().filter(model -> model.id().equals(id)).map(ClientRuntime.Action::label)
                .findFirst().orElse(id);
        return profile(id).localized(super.client == null ? "zh_cn" : super.client.getLanguageManager().getLanguage(),
                "metadata.name", fallback);
    }

    protected void clipped(DrawContext context, String text, int x, int y, int w, int color) {
        context.drawTextWithShadow(super.textRenderer, super.textRenderer.trimToWidth(text, Math.max(0, w)), x, y,
                color);
    }

    @Override
    public void tick() {
        if (requiresLocalSource() && !this.runtime.canEditLocalAppearance()) {
            super.client.setScreen(new PlayerModelScreen(this.runtime));
            return;
        }
        if (localGallery()) {
            Set<String> uploads = this.runtime.uploadedPrivateModelIds();
            List<PrivateUploadCatalogSnapshot.Model> saved = this.runtime.uploadedPrivateModels();
            if (!uploads.equals(this.confirmedUploads) || !saved.equals(this.savedUploads)) {
                this.confirmedUploads = Set.copyOf(uploads);
                this.savedUploads = List.copyOf(saved);
                if (this.uploadedOnly) {
                    clearGalleryPreviews();
                    refreshModels(false);
                    rebuildGrid();
                }
            }
            if (!this.observedDeleteId.isEmpty()) {
                String status = this.runtime.privateUploadDeleteStatus(this.observedDeleteId);
                if (!status.equals(this.observedDeleteStatus)) {
                    this.observedDeleteStatus = status;
                    this.message = status;
                }
                if (!this.runtime.deletingUploadedPrivateModel(this.observedDeleteId)) {
                    if (status.isEmpty()) {
                        this.message = "未收到服务器删除确认，请重新连接后查看存档";
                    }

                    this.observedDeleteId = this.observedDeleteStatus = "";
                }
            }
        }
        updateSelectionButtons();
        this.ticks++;
        pumpLoads();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.preview.beginFrame(context);
        this.drawnPreviewCount = 0;
        this.leftPreviewDrawn = false;
        this.leftPreviewId = "";
        this.leftPreviewKey = "";
        this.drawnCards.clear();
        this.leftPreviewSource = "native-player";
        this.leftPreviewInstance = "";
        this.leftPreviewHash = "";
        context.fill(this.left - 4, this.frameTop - 2, this.left + this.panelWidth + 4, this.frameBottom + 2,
                0xCF10171F);
        renderHeader(context);
        context.fill(this.left, this.top, this.left + this.previewWidth, this.bottom, 0xE6222730);
        context.fill(this.right, this.top, this.right + this.rightWidth, this.bottom, 0xE6222730);
        if (clientGalleryVisible()) {
            renderGallery(context, delta);
        }

        else {
            renderAlternateContent(context, delta);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    protected void renderHeader(DrawContext context) {
        context.drawCenteredTextWithShadow(super.textRenderer, super.title, super.width / 2, this.titleY, 0xfff3f6ff);
        if (!this.compactLayout) {
            context.drawCenteredTextWithShadow(super.textRenderer, "本地模型 · 分享在玩家模型主页主动开启", super.width / 2,
                    this.titleY + 16, 0xffbccce0);
        }

    }

    protected void renderAlternateContent(DrawContext context, float delta) {
    }

    private void renderGallery(DrawContext context, float delta) {
        renderSelectedPreview(context, delta);
        if (this.visible.isEmpty() && this.visibleGroups.isEmpty()) {
            clipped(context,
                    this.uploadedOnly
                            ? (this.runtime.privateUploadCatalogReady() ? "暂无匹配的已上传模型"
                                    : this.runtime.privateUploadCatalogStatus())
                            : (this.favoritesOnly ? "暂无符合条件的收藏" : "没有符合条件的模型"),
                    this.right + 8, this.gridTop + 20, this.rightWidth - 16, 0xffbccce0);
        }

        context.drawCenteredTextWithShadow(super.textRenderer, (this.page + 1) + " / " + pageCount(),
                this.right + this.rightWidth / 2, this.bottom - 15, 0xffe6ecf4);
        String status = this.message.isEmpty() ? galleryStatus() : this.message;
        clipped(context, status, this.left + footerStatusInset(), this.footerY + 6,
                this.panelWidth - footerStatusInset(), 0xffffd589);
    }

    protected void renderSelectedPreview(DrawContext context, float delta) {
        boolean currentLocal = this.runtime.currentAppearanceSource() == ClientRuntime.AppearanceSource.CLIENT;
        if (localGallery() && !currentLocal && !this.selection.modelId().isEmpty()) {
            renderBrowsingPreview(context, delta, this.selection.modelId(),
                    this.previews.get(this.selection.modelId()));
            return;
        }
        ClientRuntime.GuiPreviewAppearance appearance = this.runtime.guiPreviewAppearance();
        if (currentLocal && appearance == null) {
            String id = this.runtime.currentAppearanceModelId(), hash = this.runtime.currentAppearanceAssetHash();
            LocalAppearanceScreen.PreviewEntry entry = this.previews.get(id);
            if (entry != null && entry.loaded != null && entry.loaded.hash().equals(hash)) {
                appearance = new ClientRuntime.GuiPreviewAppearance(id, "gallery-current:" + id, hash,
                        entry.loaded.model(), entry.loaded.profile());
            }

        }
        LocalAppearanceScreen.PreviewEntry selectedEntry = this.previews.get(this.selection.modelId());
        GalleryPreviewSelection.Target target = this.selection.target(appearance == null ? "" : appearance.modelId(),
                appearance == null ? "" : appearance.assetHash(),
                selectedEntry == null || selectedEntry.loaded == null ? null : selectedEntry.loaded.hash());
        if (target.source() == GalleryPreviewSelection.Source.SELECTED) {
            renderBrowsingPreview(context, delta, target.modelId(), selectedEntry);
            return;
        }
        if (target.source() == GalleryPreviewSelection.Source.VANILLA || appearance == null) {
            clipped(context, "原版玩家", this.left + 6, this.top + 6, this.previewWidth - 12, 0xfff3f6ff);
            clipped(context, "当前无替换模型", this.left + 6, this.top + 17, this.previewWidth - 12, 0xff92b9df);
            clipped(context, "原版外观 · 使用后显示模型", this.previewX + 3, this.previewY + this.previewH / 2, this.previewW - 6,
                    0xffc6d5e7);
            return;
        }
        String id = appearance.modelId();
        this.leftPreviewId = id;
        this.leftPreviewKey = id + ":" + appearance.assetHash();
        this.leftPreviewHash = appearance.assetHash();
        this.leftPreviewInstance = appearance.instance();
        this.leftPreviewSource = "runtime-current";
        clipped(context, label(id), this.left + 6, this.top + 6, this.previewWidth - 12, 0xfff3f6ff);
        boolean savedPrivate = localGallery() && matchedSavedModel(id) != null;
        PrivateModelSyncClient.UploadState uploadState = this.runtime.privateModelUploadState(id);
        String currentStatus = savedPrivate
                ? (uploadState.published() ? "当前分享" : (uploadState.inProgress() ? "当前本地使用 · 正在恢复分享" : "当前本地使用 · 分享未开启"))
                : "当前使用";
        clipped(context, currentStatus, this.left + 6, this.top + 17, this.previewWidth - 12, 0xff92b9df);
        LocalAppearanceScreen.PreviewEntry entry = this.previews.get(id);
        boolean sameAsset = entry != null && entry.loaded != null && entry.loaded.hash().equals(appearance.assetHash())
                && entry.loaded.profile().isYsm() == appearance.profile().isYsm();
        // The live binding is authoritative; a separately decoded GUI copy may share its exact validated content hash.
        BbModel model = sameAsset ? entry.loaded.model() : appearance.model();
        YsmModelProfile profile = sameAsset ? entry.loaded.profile() : appearance.profile();
        this.leftPreviewDrawn = this.preview.render(context, model, this.leftPreviewKey, this.previewX, this.previewY,
                this.previewW, this.previewH, this.yaw, this.pitch, this.ticks + delta,
                profile.isYsm() ? parameters(id) : Map.of(), profile.previewAnimation(), profile,
                ModelPreview.Context.OWNER);
        if (this.leftPreviewDrawn) {
            this.drawnPreviewCount++;
        }

        else {
            clipped(context, "当前模型预览暂不可用", this.previewX + 3, this.previewY + this.previewH / 2, this.previewW - 6,
                    0xffffc685);
        }

        if (showRotationHint()) {
            clipped(context, rotationDisabled() ? "作者固定正面视角" : "拖动旋转 · 双击回正", this.left + 6, this.bottom - 57,
                    this.previewWidth - 12, 0xffa7b5c8);
        }

    }

    private void renderBrowsingPreview(DrawContext context, float delta, String id, PreviewEntry entry) {
        this.leftPreviewId = id;
        this.leftPreviewSource = "selected-preview";
        clipped(context, label(id), this.left + 6, this.top + 6, this.previewWidth - 12, 0xfff3f6ff);
        GalleryModelUseState.State state = galleryUseState(id, entry == null ? null : entry.loaded);
        String status = !cloudModelHint(id).isEmpty() ? "云端存档 · 本机内容不匹配"
                : (state.current()
                        ? (state.buttonLabel().equals("当前分享") ? "当前分享"
                                : (state.buttonLabel().equals("恢复分享") ? "当前本地使用 · 分享未开启" : "当前使用"))
                        : (state.waiting()
                                ? (id.equals(this.pendingSavedShare) ? "正在核对分享资源"
                                        : (matchedSavedModel(id) != null
                                                && this.runtime.privateModelUploadState(id).inProgress() ? "正在恢复私人分享"
                                                        : "正在加载所选模型"))
                                : (this.runtime.canActivateLocalAppearance() ? "预览 · 点击使用模型应用" : "仅预览 · 请先解除服务器伪装")));
        clipped(context, status, this.left + 6, this.top + 17, this.previewWidth - 12, 0xff92b9df);
        if (entry == null || entry.loaded == null) {
            String unavailable = entry != null && !entry.error.isEmpty() ? "预览加载失败：" + entry.error
                    : galleryPreviewUnavailableText(id);
            clipped(context, unavailable, this.previewX + 3, this.previewY + this.previewH / 2, this.previewW - 6,
                    0xffffc685);
            return;
        }
        this.leftPreviewKey = key(id, entry.loaded);
        this.leftPreviewHash = entry.loaded.hash();
        YsmModelProfile profile = entry.loaded.profile();
        // The upstream PlayerTextureScreen owns a separate PlayerPreviewEntity. A browsing draft
        // shares the owner's read-only pose inputs while keeping its own model, clock and network-free controller.
        this.leftPreviewDrawn = this.preview.render(context, entry.loaded.model(), this.leftPreviewKey, this.previewX,
                this.previewY, this.previewW, this.previewH, this.yaw, this.pitch, this.ticks + delta,
                profile.isYsm() ? parameters(id) : Map.of(), entry.loaded.previewAnimation(), profile,
                ModelPreview.Context.SELECTED);
        if (this.leftPreviewDrawn) {
            this.drawnPreviewCount++;
        }

        else {
            clipped(context, "所选模型预览暂不可用", this.previewX + 3, this.previewY + this.previewH / 2, this.previewW - 6,
                    0xffffc685);
        }

        if (showRotationHint()) {
            clipped(context, rotationDisabled() ? "作者固定正面视角" : "拖动旋转 · 双击回正", this.left + 6, this.bottom - 57,
                    this.previewWidth - 12, 0xffa7b5c8);
        }

    }

    protected int footerStatusInset() {
        return 127;
    }

    private boolean insidePreview(double x, double y) {
        return x >= this.previewX && x < this.previewX + this.previewW && y >= this.previewY
                && y < this.previewY + this.previewH;
    }

    protected boolean rotationDisabled() {
        return false;
    }

    private Map<String, Double> parameters(String id) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (id.equals("openysm_default")) {
            values.put("variable.roaming.red_bow_headdress", this.runtime.options.defaultHeaddress ? 1d : 0d);
        }

        values.putAll(this.runtime.options.modelProfile(id).variables());
        return Map.copyOf(values);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && insidePreview(click.x(), click.y())) {
            if (rotationDisabled()) {
                this.rotating = false;
                return true;
            }
            this.rotating = true;
            if (doubled) {
                this.yaw = 0;
                this.pitch = -8;
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (this.rotating && click.button() == 0) {
            if (rotationDisabled()) {
                this.rotating = false;
                return true;
            }
            // OpenYSM PlayerTextureScreen.mouseDragged/adjustPitch: screen Y is inverted.
            this.yaw = (this.yaw + (float) dx * 1.5f) % 360;
            this.pitch = Math.max(-90, Math.min(90, this.pitch - (float) dy));
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (this.rotating && click.button() == 0) {
            this.rotating = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (clientGalleryVisible() && mouseX >= this.right && mouseX < this.right + this.rightWidth
                && mouseY >= this.gridTop && mouseY < this.bottom && vertical != 0) {
            goToPage(this.page + (vertical > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void removed() {
        this.activeView = false;
        this.rotating = false;
        cancelSavedShareCheck();
        clearGalleryPreviews();
    }

    protected void clearGalleryPreviews() {
        this.generation++;
        this.inFlight = 0;
        this.previews.clear();
        this.preview.clear();
        this.drawnCards.clear();
        this.leftPreviewDrawn = false;
    }

    protected final void showUploadedModelsOnly() {
        this.uploadedOnly = true;
        this.groupSource = this.sourceFilter = this.page = 0;
        this.groupDirectory = "";
        clearGalleryPreviews();
        refreshModels(false);
    }

    @Override
    public void close() {
        super.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static final class PreviewEntry {
        boolean requested;
        LocalModelLibrary.Loaded loaded;
        String error = "";
    }

    private final class GroupCard extends ButtonWidget {
        private final ModelGroup group;

        GroupCard(ModelGroup group, int x, int y, int w, int h) {
            super(x, y, w, h, net.minecraft.text.Text.literal(group.label()), button -> browseGroup(group),
                    DEFAULT_NARRATION_SUPPLIER);
            this.group = group;
            setTooltip(ModelUiTooltip.of(LocalAppearanceScreen.super.textRenderer, LocalAppearanceScreen.super.width,
                    galleryGroupTooltip(group)));
        }

        @Override
        protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(getX(), getY(), getRight(), getBottom(), super.hovered ? 0xff394b62 : 0xff303d4e);
            context.drawTexture(RenderPipelines.GUI_TEXTURED, PACK_ICON, getX(), getY(), 0f, 0f, getWidth(),
                    getHeight(), 52, 90, 52, 90);
            if (super.hovered || isFocused()) {
                context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -1982745);
            }

            drawCardLabel(context, getMessage(), getX(), getBottom(), 0xff555555, false);
        }
    }

    private final class ModelCard extends ButtonWidget {
        private final ClientRuntime.Action model;
        private String tooltipText = "";

        ModelCard(ClientRuntime.Action model, int x, int y, int w, int h) {
            super(x, y, w, h, net.minecraft.text.Text.literal(model.label()), button -> browseModel(model.id()),
                    DEFAULT_NARRATION_SUPPLIER);
            this.model = model;
            refreshLabel(-1, -1);
        }

        private void refreshLabel(int mouseX, int mouseY) {
            String label = label(this.model.id());
            setMessage(net.minecraft.text.Text.literal(label));
            String description = profile(this.model.id()).localized(
                    LocalAppearanceScreen.super.client == null ? "zh_cn"
                            : LocalAppearanceScreen.super.client.getLanguageManager().getLanguage(),
                    "metadata.description", "");
            ModelCardActionRegions.Target target = isMouseOver(mouseX, mouseY)
                    ? ModelCardActionRegions.target(mouseX - getX(), mouseY - getY(), getWidth(),
                            galleryCardCloudVisible())
                    : ModelCardActionRegions.Target.BROWSE;
            String tooltip = target == ModelCardActionRegions.Target.UPLOAD ? galleryCardCloudTooltip(this.model.id())
                    : (target == ModelCardActionRegions.Target.FAVORITE
                            ? (isFavoriteModel(this.model.id()) ? "取消收藏" : "收藏模型") + "\n" + label
                            : label + "\n" + gallerySourceLabel(this.model.id()) + "\n" + this.model.id()
                                    + (description.isBlank() ? "" : "\n" + description) + "\n点击预览\n右上角收藏"
                                    + (localGallery() ? "\n左上角上传／管理服务器存档"
                                            : (galleryCardCloudVisible() ? "\n左上角查看资源缓存与接管状态" : "")));
            if (!tooltip.equals(this.tooltipText)) {
                this.tooltipText = tooltip;
                setTooltip(ModelUiTooltip.of(LocalAppearanceScreen.super.textRenderer,
                        LocalAppearanceScreen.super.width, tooltip));
            }
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            switch (ModelCardActionRegions.target(click.x() - getX(), click.y() - getY(), getWidth(),
                    galleryCardCloudVisible())) {
            case FAVORITE -> {
                toggleModelFavorite(this.model.id());
                rebuildGrid();
            }
            case UPLOAD -> galleryCardCloudClicked(this.model.id());
            case BROWSE -> super.onClick(click, doubled);
            }
        }

        // PressableWidget.renderWidget is final: drawIcon owns the entire card, without drawButton's background.
        @Override
        protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
            refreshLabel(mouseX, mouseY);
            context.fill(getX(), getY(), getRight(), getBottom(), -12369342);
            LocalAppearanceScreen.PreviewEntry entry = LocalAppearanceScreen.this.previews.get(this.model.id());
            int imageHeight = Math.max(8, getHeight() - 20);
            if (entry != null && entry.loaded != null) {
                boolean nativeCard = NativeGuiPreviewCamera.appliesTo(entry.loaded.profile());
                boolean rendered;
                if (nativeCard) {
                    // ModelPreview applies the 70px crop to model geometry only; the author's
                    // background/foreground cover the full 52×90 card like ModelButton.
                    rendered = LocalAppearanceScreen.this.preview.render(context, entry.loaded.model(),
                            key(this.model.id(), entry.loaded), getX(), getY(), getWidth(), CARD_RENDER_HEIGHT, 0, 0,
                            LocalAppearanceScreen.this.ticks + delta, parameters(this.model.id()),
                            entry.loaded.previewAnimation(), entry.loaded.profile(), ModelPreview.Context.CARD);
                } else {
                    rendered = LocalAppearanceScreen.this.preview.render(context, entry.loaded.model(),
                            key(this.model.id(), entry.loaded), getX() + 2, getY() + 2, getWidth() - 4, imageHeight - 2,
                            0, -8, LocalAppearanceScreen.this.ticks + delta, parameters(this.model.id()),
                            entry.loaded.previewAnimation(), entry.loaded.profile(), ModelPreview.Context.CARD);
                }

                if (rendered) {
                    LocalAppearanceScreen.this.drawnPreviewCount++;
                    LocalAppearanceScreen.this.drawnCards.add(this.model.id());
                } else {
                    clipped(context, "预览不可用", getX() + 5, getY() + imageHeight / 2, getWidth() - 10, 0xffffc685);
                }

            } else {
                clipped(context, !cloudModelHint(this.model.id()).isEmpty()
                        ? (LocalAppearanceScreen.this.localSourceIds.contains(this.model.id()) ? "文件已改" : "本地未找到")
                        : (entry != null && !entry.error.isEmpty() ? "加载失败" : galleryCardPlaceholder(this.model.id())),
                        getX() + 5, getY() + imageHeight / 2, getWidth() - 10, 0xffc1cedc);
            }

            drawCardLabel(context,
                    net.minecraft.text.Text
                            .literal(LocalAppearanceScreen.this.runtime.options.showModelIds ? this.model.id()
                                    : getMessage().getString()),
                    getX(), getBottom(), 0xfff3f0e0, true);
            if (LocalAppearanceScreen.this.selection.modelId().equals(this.model.id()) || super.hovered
                    || isFocused()) {
                context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
            }

            context.drawTexture(RenderPipelines.GUI_TEXTURED, GUI_ICONS, getRight() - 16, getY(),
                    isFavoriteModel(this.model.id()) ? 16f : 0f, 0f, 16, 16, 256, 256);
            if (galleryCardCloudVisible()) {
                drawGalleryCardCloud(context, this.model.id(), getX() + 1, getY() + 1);
            }

        }
    }

    /** ModelButton / PackIconButton use 45px wrapping within the same 52px author frame. */
    private void drawCardLabel(DrawContext context, Text label, int x, int bottom, int color, boolean shadow) {
        List<OrderedText> lines = super.textRenderer.wrapLines(label, 45);
        int lineCount = Math.min(2, lines.size());
        for (int i = 0; i < lineCount; i++) {
            OrderedText line = lines.get(i);
            int y = lineCount > 1 ? bottom - 19 + i * 9 : bottom - 15;
            context.drawText(super.textRenderer, line, x + CARD_WIDTH / 2 - super.textRenderer.getWidth(line) / 2, y,
                    color, shadow);
        }
    }

}
