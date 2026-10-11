package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.MEPlayerActionsClient;
import com.simmc.meplayeractions.client.WheelPreferences;
import com.simmc.meplayeractions.client.model.YsmModelProfile;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Yarn 1.21.11 port of OpenYSM's classic AnimationRouletteScreen at 0306e1f (MIT).
 * Polygon slices, configuration slices, author forms, scrolling and flat/check/range widgets
 * follow AnimationRouletteScreen, FlatColorButton, ConfigCheckBox and AnimationSlider.
 * Runtime supplies MPA action authorization and atomic private-profile writes.
 * The mirrored left-side disguise controls are MPA additions, not upstream roulette features.
 * See THIRD_PARTY_NOTICES.md for the original license and source attribution.
 */
public final class AnimationWheelScreen extends Screen
{
    private static final int SLOTS = WheelSelection.PAGE_SIZE;
    private static final Identifier SETTINGS_ICON = Identifier.of("meplayeractions", "textures/gui/settings.png");
    private final ClientRuntime runtime;
    private final Screen parent;
    private final AtomicLong emittedVertices = new AtomicLong();
    private boolean localMode, locked, serverAvailable, ownServerDisguise, sourceInitialized, restorePageOnCatalog;
    private boolean releaseConsumed, releaseKeyDown, keyboardSelection, hoveredConfig, hoveredCenter;
    private int page, hovered = -1, centerX, centerY, inner, outer, releaseKey = GLFW.GLFW_KEY_J;
    private InputUtil.Type releaseType = InputUtil.Type.KEYSYM;
    private WheelSelection.RouletteLayout layout;
    private List<ClientRuntime.Action> actions = List.of();
    private List<Map<String, Object>> renderedPolygons = List.of();
    private final List<NavigationControl> navigationControls = new ArrayList<>();
    private final List<FormRow> formRows = new ArrayList<>();
    private final List<ClickableWidget> formWidgets = new ArrayList<>();
    private final Map<String, Double> formValues = new HashMap<>();
    private final List<String> categoryPath = new ArrayList<>();
    private final Map<String, Integer> categoryPages = new HashMap<>();
    private String message = "", configGroupId = "", serverInstance = "";
    private int configScroll, configContentHeight;
    private YsmModelProfile menuProfile;
    private ModelActionMenu menu;
    private ModelConfigSchema configSchema;
    private ModelConfigSchema.Group configGroup;
    private List<ModelActionMenu.Entry> localEntries = List.of();
    private FlatButton centerButton, scrollUpButton, scrollDownButton, undisguiseButton;

    public AnimationWheelScreen(ClientRuntime runtime)
    {
        this(runtime, true, null);
        beginHoldSelection();
    }

    /** The compatibility source argument never changes Runtime's per-instance action source. */
    public AnimationWheelScreen(ClientRuntime runtime, boolean localMode, Screen parent)
    {
        super(Text.literal("动作轮盘"));
        this.runtime = runtime;
        this.parent = parent;
        this.localMode = localMode;
        this.releaseConsumed = true;
    }

    public void setReleaseKey(int keyCode)
    {
        this.releaseKey = keyCode;
        this.releaseType = InputUtil.Type.KEYSYM;
        if (MEPlayerActionsClient.actionWheelKey != null)
        {
            InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(MEPlayerActionsClient.actionWheelKey);
            if (bound.getCode() == keyCode)
            {
                this.releaseType = bound.getCategory();
            }

        }
        beginHoldSelection();
    }

    public void setReleaseKey(InputUtil.Key key)
    {
        this.releaseKey = key.getCode();
        this.releaseType = key.getCategory();
        beginHoldSelection();
    }

    public void beginHoldSelection()
    {
        this.releaseConsumed = false;
        this.releaseKeyDown = true;
    }

    private boolean releaseMatches(KeyInput input)
    {
        return this.releaseType != InputUtil.Type.MOUSE
                && (this.releaseType == InputUtil.Type.SCANCODE ? input.scancode() : input.key()) == this.releaseKey;
    }

    /** Release dispatches once and cannot submit a previous appearance's selection. */
    public void releaseSelection()
    {
        this.releaseKeyDown = false;
        if (this.releaseConsumed || super.client.currentScreen != this)
        {
            return;
        }

        this.releaseConsumed = true;
        if (!sameSource())
        {
            return;
        }

        if (index(this.hovered) >= 0)
        {
            if (this.hoveredConfig)
            {
                openConfig(this.hovered);
            }
            else
            {
                select(this.hovered);
            }
        }
        else if (this.hoveredCenter)
        {
            activateCenter();
            if (!this.localMode && !this.locked)
            {
                close();
            }
        }
        else if (!this.locked)
        {
            close();
        }

    }

    private WheelPreferences.Source source()
    {
        return this.localMode ? WheelPreferences.Source.CLIENT : WheelPreferences.Source.SERVER;
    }

    private void clearHover()
    {
        this.hovered = -1;
        this.hoveredConfig = false;
        this.hoveredCenter = false;
        this.keyboardSelection = false;
    }

    private void clearConfig()
    {
        this.configGroup = null;
        this.configGroupId = "";
        this.configScroll = 0;
        this.configContentHeight = 0;
    }

    private void syncSource()
    {
        WheelPreferences preferences = this.runtime.wheelPreferences();
        boolean local = this.runtime.interactionLocalMode();
        if (!this.sourceInitialized || local != this.localMode)
        {
            this.localMode = local;
            this.page = preferences.page(source());
            this.sourceInitialized = true;
            this.restorePageOnCatalog = true;
            this.menu = null;
            this.menuProfile = null;
            this.configSchema = null;
            this.localEntries = List.of();
            this.categoryPages.clear();
            this.categoryPath.clear();
            clearConfig();
            clearHover();
            this.message = "";
        }
        this.ownServerDisguise = this.runtime.hasOwnServerDisguise();
        this.serverInstance = this.runtime.serverOwnModelInstance();
        this.locked = preferences.keepOpen();
    }

    private boolean sameSource()
    {
        if (this.localMode == this.runtime.interactionLocalMode()
                && this.serverInstance.equals(this.runtime.serverOwnModelInstance()))
        {
            return true;
        }

        clearHover();
        this.releaseConsumed = true;
        clearAndInit();
        return false;
    }

    @Override
    protected void init()
    {
        syncSource();
        this.navigationControls.clear();
        this.formRows.clear();
        this.formWidgets.clear();
        this.formValues.clear();
        this.scrollUpButton = null;
        this.scrollDownButton = null;
        this.serverAvailable = this.runtime.canUseServerActions();
        this.actions = currentActions();
        if (this.restorePageOnCatalog && !this.actions.isEmpty())
        {
            this.page = this.runtime.wheelPreferences().page(source());
            this.restorePageOnCatalog = false;
        }
        this.page = WheelSelection.clampPage(this.page, this.actions.size(), SLOTS);
        this.layout = WheelSelection.layout(super.width, super.height);
        this.centerX = this.layout.centerX();
        this.centerY = this.layout.centerY();
        this.inner = this.layout.scaled(25);
        this.outer = this.layout.scaled(105);
        int centerWidth = Math.max(20, this.layout.scaled(40)), centerHeight = Math.max(12, this.layout.scaled(20));
        this.centerButton = addControl("center", new FlatButton(this.centerX - centerWidth / 2,
                this.centerY - centerHeight / 2, centerWidth, centerHeight, centerLabel(), this::activateCenter));
        this.centerButton.active = this.localMode ? this.runtime.canUseLocalActions() : this.serverAvailable;
        this.centerButton.setTooltip(
                ModelUiTooltip.of(super.textRenderer, super.width, this.localMode ? "移动时保留本地动作" : "停止服务器手动动作"));
        AnimationWheelScreen.SettingsIconButton settings = addControl("settings",
                new SettingsIconButton(super.width - 28, 8, () ->
                {
                    this.releaseConsumed = true;
                    super.client.setScreen(new PlayerModelScreen(this.runtime, this));
                }));
        settings.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, "玩家模型设置"));
        // Match the existing flat navigation style; scope follows this exact screen source/instance.
        this.undisguiseButton = addControl(this.localMode ? "undisguise-private" : "undisguise-server",
                new FlatButton(this.layout.sourcePanelX(), this.layout.navigationY(), this.layout.panelWidth(),
                        this.layout.backHeight(), this.localMode ? "解除私人伪装" : "解除服务器伪装",
                        this::undisguiseCurrentSource));
        this.undisguiseButton.active = canUndisguiseCurrentSource();
        this.undisguiseButton.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width,
                this.localMode ? "关闭私人伪装\n保留模型、参数与皮肤" : "请求解除本人服务器伪装\n等待服务器确认\n外部伪装仅结束 MPA 接管"));
        int aw = this.layout.navigationWidth(), ah = this.layout.navigationHeight();
        AnimationWheelScreen.FlatButton previous = addControl("previous",
                new FlatButton(this.layout.panelX(), this.layout.navigationY(), aw, ah, "<", () -> changePage(-1)));
        AnimationWheelScreen.FlatButton next = addControl("next", new FlatButton(this.layout.panelRight() - aw,
                this.layout.navigationY(), aw, ah, ">", () -> changePage(1)));
        previous.active = this.page > 0;
        next.active = this.page + 1 < WheelSelection.pageCount(this.actions.size(), SLOTS);
        addControl("back", new FlatButton(this.layout.panelX(), this.layout.backY(), this.layout.panelWidth(),
                this.layout.backHeight(), "返回", () ->
                {
                    this.releaseConsumed = true;
                    close();
                }));
        if (this.configGroup != null && this.localMode && this.runtime.canEditLocalAppearance())
        {
            buildConfigForms();
        }

    }

    private String centerLabel()
    {
        if (!this.localMode)
        {
            return "停止";
        }

        String prefix = this.layout != null && this.layout.scaled(40) < 28 ? "锁:" : "锁定:";
        return prefix + (this.runtime.localActionLocked() ? "开" : "关");
    }

    private <T extends FlatButton> T addControl(String id, T widget)
    {
        this.navigationControls.add(new NavigationControl(id, widget));
        return addDrawableChild(widget);
    }

    private List<ClientRuntime.Action> currentActions()
    {
        if (!this.localMode)
        {
            return this.runtime.serverActions();
        }

        if (!this.runtime.canUseLocalActions())
        {
            return List.of();
        }

        YsmModelProfile profile = this.runtime.localModelProfile();
        if (profile != this.menuProfile)
        {
            this.menuProfile = profile;
            this.menu = null;
            this.configSchema = null;
            this.categoryPages.clear();
            this.categoryPath.clear();
            clearConfig();
            this.restorePageOnCatalog = true;
            try
            {
                if (profile != null && profile.isYsm())
                {
                    String language = super.client.getLanguageManager().getLanguage();
                    this.menu = new ModelActionMenu(profile, language);
                    this.configSchema = ModelConfigSchema.from(profile, language);
                }
            }
            catch (RuntimeException invalid)
            {
                this.message = "模型动作配置无法使用";
            }
        }
        if (this.menu == null)
        {
            this.localEntries = List.of();
            return this.runtime.localActions();
        }
        this.localEntries = this.menu.entries();
        return this.localEntries.stream().map(entry -> new ClientRuntime.Action(entry.id(), entry.label())).toList();
    }

    private int visibleSlots()
    {
        return WheelSelection.visibleSlots(this.page, this.actions.size());
    }

    private int index(int slot)
    {
        return WheelSelection.actionIndex(this.page, slot, this.actions.size(), SLOTS);
    }

    private String category()
    {
        return this.localMode && this.menu != null ? this.menu.categoryId() : "";
    }

    private void rememberPage()
    {
        if (this.localMode && this.menu != null)
        {
            this.categoryPages.put(category(), this.page);
        }

        if (!this.localMode || this.menu == null || this.menu.depth() == 0)
        {
            WheelPreferences preferences = this.runtime.wheelPreferences();
            if (preferences.page(source()) != this.page)
            {
                this.runtime.updateWheelPreferences(preferences.withPage(source(), this.page));
            }

        }
    }

    private void changePage(int direction)
    {
        int next = WheelSelection.clampPage(this.page + direction, this.actions.size(), SLOTS);
        if (next == this.page)
        {
            return;
        }

        this.page = next;
        this.restorePageOnCatalog = false;
        rememberPage();
        clearHover();
        this.releaseConsumed = true;
        clearAndInit();
    }

    private void changedCategory()
    {
        this.page = this.categoryPages.getOrDefault(category(),
                this.menu.depth() == 0 ? this.runtime.wheelPreferences().page(source()) : 0);
        this.restorePageOnCatalog = false;
        clearHover();
        clearConfig();
        this.releaseConsumed = true;
        clearAndInit();
    }

    private void select(int slot)
    {
        if (!sameSource())
        {
            return;
        }

        int selected = index(slot);
        if (selected < 0)
        {
            return;
        }

        if (this.localMode && this.menu != null)
        {
            ModelActionMenu.Entry entry = this.localEntries.get(selected);
            if (entry.category() || entry.back())
            {
                rememberPage();
                if (this.menu.enter(entry))
                {
                    if (entry.back())
                    {
                        if (!this.categoryPath.isEmpty())
                        {
                            this.categoryPath.removeLast();
                        }
                    }
                    else
                    {
                        this.categoryPath.add(entry.label());
                    }

                    changedCategory();
                }
                else
                {
                    this.message = "此动作分类不可进入";
                }

                return;
            }
        }
        String id = this.actions.get(selected).id();
        boolean played;
        if (this.localMode)
        {
            played = this.runtime.canUseLocalActions() && this.runtime.playLocal(id);
        }

        else
        {
            played = this.runtime.canUseServerActions();
            if (played)
            {
                this.runtime.request("play", id);
            }
        }
        if (!played)
        {
            this.message = this.localMode ? "选择并启用本地模型后可播放动作" : "等待服务器动作频道";
            return;
        }
        rememberPage();
        this.releaseConsumed = true;
        this.message = "已选择：" + this.actions.get(selected).label();
        if (!this.locked)
        {
            super.client.setScreen(this.parent);
        }

    }

    private boolean hasConfig(int slot)
    {
        int selected = index(slot);
        return this.localMode && this.runtime.canEditLocalAppearance() && this.menu != null && this.configSchema != null
                && selected >= 0 && !this.localEntries.get(selected).configGroup().isEmpty()
                && this.configSchema.group(this.localEntries.get(selected).configGroup()) != null;
    }

    private void openConfig(int slot)
    {
        if (!sameSource() || !hasConfig(slot))
        {
            return;
        }

        this.configGroupId = this.localEntries.get(index(slot)).configGroup();
        this.configGroup = this.configSchema.group(this.configGroupId);
        this.configScroll = 0;
        this.releaseConsumed = true;
        clearAndInit(); // This same Screen continues to render both roulette and author form.
    }

    private void activateCenter()
    {
        if (!sameSource())
        {
            return;
        }

        this.releaseConsumed = true;
        if (this.localMode)
        {
            if (this.runtime.canUseLocalActions())
            {
                this.runtime.setLocalActionLocked(!this.runtime.localActionLocked());
            }

            this.centerButton.setMessage(Text.literal(centerLabel()));
        }
        else
        {
            stop();
        }

    }

    private void stop()
    {
        if (!sameSource())
        {
            return;
        }

        if (this.localMode)
        {
            this.runtime.stopLocal();
        }

        else if (this.runtime.canUseServerActions())
        {
            this.runtime.request("stop", "");
        }

        else
        {
            this.message = "等待服务器动作频道";
            return;
        }
        this.releaseConsumed = true;
        this.message = "已停止";
    }

    private boolean canUndisguiseCurrentSource()
    {
        return this.localMode ? this.runtime.canEditLocalAppearance() && this.runtime.localAppearance().enabled()
                : this.runtime.canRequestServerUndisguise();
    }

    private void undisguiseCurrentSource()
    {
        if (!sameSource())
        {
            return;
        }

        this.releaseConsumed = true;
        clearHover();
        if (!canUndisguiseCurrentSource())
        {
            this.message = this.localMode ? "私人伪装未启用" : "当前没有可请求解除的服务器伪装";
            return;
        }
        if (this.localMode)
        {
            this.runtime.disableLocalAppearance();
            if (this.runtime.localAppearance().enabled())
            {
                this.message = "私人伪装未关闭";
                return;
            }
            if (this.runtime.serverOwnModelPresent())
            {
                this.runtime.selectInteractionSource(false);
            }

            super.client.setScreen(this.parent);
        }
        else if (!this.runtime.requestServerUndisguise())
        {
            this.message = "解除服务器伪装的请求未发送";
            return;
        }
        else
        {
            // A server request is complete only after the normal server state/unbind confirmation.
            this.message = "已请求解除服务器伪装，等待服务器确认";
            this.undisguiseButton.active = false;
        }
    }

    @Override
    public void tick()
    {
        boolean present = this.runtime.hasOwnServerDisguise(), available = this.runtime.canUseServerActions();
        YsmModelProfile previousProfile = this.menuProfile;
        List<ClientRuntime.Action> current = currentActions();
        if (this.localMode != this.runtime.interactionLocalMode()
                || !this.serverInstance.equals(this.runtime.serverOwnModelInstance())
                || present != this.ownServerDisguise || available != this.serverAvailable
                || previousProfile != this.menuProfile || !current.equals(this.actions))
        {
            clearHover();
            this.releaseConsumed = true;
            clearAndInit();
        }
        this.locked = this.runtime.wheelPreferences().keepOpen();
        if (this.centerButton != null)
        {
            this.centerButton.setMessage(Text.literal(centerLabel()));
        }

        if (this.undisguiseButton != null)
        {
            this.undisguiseButton.active = canUndisguiseCurrentSource();
        }

    }

    private void hover(double mouseX, double mouseY)
    {
        double x = (mouseX - this.centerX) / this.layout.scale(), y = (mouseY - this.centerY) / this.layout.scale();
        this.hovered = WheelSelection.classicSlotAt(x, y, 50, 100, visibleSlots());
        this.hoveredConfig = false;
        int configSlot = WheelSelection.classicSlotAt(x, y, 20, 50, visibleSlots());
        if (hasConfig(configSlot))
        {
            this.hovered = configSlot;
            this.hoveredConfig = true;
        }
        this.hoveredCenter = this.centerButton != null && this.centerButton.isMouseOver(mouseX, mouseY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY)
    {
        this.keyboardSelection = false;
        hover(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled)
    {
        if (this.releaseType == InputUtil.Type.MOUSE && click.button() == this.releaseKey)
        {
            if (!this.releaseKeyDown)
            {
                beginHoldSelection();
            }
            return true;
        }
        hover(click.x(), click.y());
        if (super.mouseClicked(click, doubled))
        {
            this.releaseConsumed = true;
            return true;
        }
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT)
        {
            return false;
        }

        if (index(this.hovered) >= 0)
        {
            if (this.hoveredConfig)
            {
                openConfig(this.hovered);
            }
            else
            {
                select(this.hovered);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(Click click)
    {
        if (this.releaseType == InputUtil.Type.MOUSE && click.button() == this.releaseKey)
        {
            releaseSelection();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical)
    {
        if (vertical == 0)
        {
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }

        if (mouseX >= this.layout.panelX() && this.configGroup != null)
        {
            scrollConfig(vertical > 0 ? -20 : 20);
            return true;
        }
        changePage(vertical > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input)
    {
        int key = input.key();
        if (releaseMatches(input))
        {
            if (!this.releaseKeyDown)
            {
                beginHoldSelection();
            }
            return true;
        }
        if (getFocused() instanceof SliderWidget && key != GLFW.GLFW_KEY_ESCAPE && super.keyPressed(input))
        {
            return true;
        }

        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_8)
        {
            this.hovered = key - GLFW.GLFW_KEY_1;
            this.keyboardSelection = true;
            this.hoveredConfig = false;
            this.hoveredCenter = false;
            select(this.hovered);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
        {
            if (getFocused() != null && super.keyPressed(input))
            {
                return true;
            }

            if (this.hoveredCenter)
            {
                activateCenter();
            }
            else
            {
                select(this.hovered);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_PAGE_UP)
        {
            changePage(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_PAGE_DOWN)
        {
            changePage(1);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean keyReleased(KeyInput input)
    {
        if (releaseMatches(input))
        {
            releaseSelection();
            return true;
        }
        return super.keyReleased(input);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        context.fill(0, 0, super.width, super.height, 0x35000000);
        if (!this.keyboardSelection)
        {
            hover(mouseX, mouseY);
        }

        context.drawCenteredTextWithShadow(super.textRenderer, this.localMode ? "本地动作" : "服务器动作", super.width / 2, 8,
                0xfff3f0e0);
        ArrayList<Map<String, Object>> polygons = new ArrayList<Map<String, Object>>();
        for (int slot = 0; slot < visibleSlots(); slot++)
        {
            boolean selected = this.hovered == slot && !this.hoveredConfig, config = hasConfig(slot);
            if (selected && config)
            {
                drawRadialSegment(context, polygons, slot, 50, 115, 0xf0ffb100, "action");
                drawRadialSegment(context, polygons, slot, 25, 50, 0x90000000, "action");
            }
            else
            {
                drawRadialSegment(context, polygons, slot, 25, selected ? 115 : 105, selected ? 0xf0ffb100 : 0x90000000,
                        "action");
            }

            if (config)
            {
                drawRadialSegment(context, polygons, slot, this.hoveredConfig && this.hovered == slot ? 15 : 25, 50,
                        this.hoveredConfig && this.hovered == slot ? 0xf000ceff : 0x7000ceff, "config");
            }

        }
        this.renderedPolygons = List.copyOf(polygons);
        renderRadialLabels(context);
        renderPathAndPage(context);
        super.render(context, mouseX, mouseY, delta);
        renderConfigForms(context, mouseX, mouseY, delta);
        if (this.configGroup == null)
        {
            String detail = this.actions.isEmpty() ? (this.localMode ? "暂无本地动作" : "等待服务器动作")
                    : (this.localMode ? "点击动作内侧 ⚙ 配置组件" : "服务器下发动作");
            context.drawWrappedTextWithShadow(super.textRenderer, Text.literal(detail), this.layout.panelX() + 2,
                    this.layout.viewportY() + 3, Math.max(1, this.layout.panelWidth() - 4), 0xffb8bec8);
        }
        context.drawCenteredTextWithShadow(super.textRenderer,
                super.textRenderer.trimToWidth(scrollHint(), Math.max(1, super.width - 24)), super.width / 2,
                super.height - 28, 0xffb8bec8);
        if (!this.message.isEmpty())
        {
            context.drawCenteredTextWithShadow(super.textRenderer,
                    super.textRenderer.trimToWidth(this.message, Math.max(1, super.width - 24)), super.width / 2,
                    super.height - 15, 0xfff3f0e0);
        }

        int selected = index(this.hovered);
        if (selected >= 0)
        {
            String description = this.localMode && this.menu != null ? this.localEntries.get(selected).description()
                    : "";
            context.drawTooltip(
                    super.textRenderer, Text.literal((this.hoveredConfig ? "配置 · " : "")
                            + this.actions.get(selected).label() + (description.isEmpty() ? "" : "\n" + description)),
                    mouseX, mouseY);
        }
    }

    private void drawRadialSegment(DrawContext context, List<Map<String, Object>> polygons, int slot,
            double innerRadius, double outerRadius, int color, String kind)
    {
        List<WheelSelection.Point> vertices = WheelSelection
                .polygon(slot, innerRadius * this.layout.scale(), outerRadius * this.layout.scale()).stream()
                .map(point -> new WheelSelection.Point(this.centerX + point.x(), this.centerY + point.y())).toList();
        context.state.addSimpleElement(
                RadialSliceRenderState.of(context.getMatrices(), vertices, color, null, this.emittedVertices));
        polygons.add(Map.of("slot", slot, "kind", kind, "color", color, "vertices",
                vertices.stream().map(point -> List.of(point.x(), point.y())).toList()));
    }

    private WheelSelection.Point pointAt(int slot, double radius)
    {
        WheelSelection.Point point = WheelSelection.labelPoint(slot, radius * this.layout.scale());
        return new WheelSelection.Point(this.centerX + point.x(), this.centerY + point.y());
    }

    private boolean showShortcutHints()
    {
        return this.page == 0 && (this.menu == null || this.menu.depth() == 0);
    }

    private List<OrderedText> wrappedLabel(int selected)
    {
        List<OrderedText> lines = super.textRenderer.wrapLines(Text.literal(this.actions.get(selected).label()),
                Math.max(32, this.layout.scaled(50)));
        return lines.size() <= 4 ? lines : lines.subList(0, 4);
    }

    private void renderRadialLabels(DrawContext context)
    {
        for (int slot = 0; slot < visibleSlots(); slot++)
        {
            int selected = index(slot);
            WheelSelection.Point point = pointAt(slot, 65);
            int x = Math.round(point.x()), y = Math.round(point.y()) - 4;
            List<OrderedText> lines = wrappedLabel(selected);
            int firstY = y - lines.size() * super.textRenderer.fontHeight + 2 + (showShortcutHints() ? 0 : 9);
            boolean category = this.localMode && this.menu != null
                    && (this.localEntries.get(selected).category() || this.localEntries.get(selected).back());
            for (OrderedText line : lines)
            {
                context.drawCenteredTextWithShadow(super.textRenderer, line, x, firstY,
                        category ? 0xffff6262 : 0xfff3f0e0);
                firstY += super.textRenderer.fontHeight;
            }
            // Actual Screen numeric bindings, without inventing unregistered author global hotkeys.
            if (showShortcutHints())
            {
                context.drawCenteredTextWithShadow(super.textRenderer, "[ " + (slot + 1) + " ]", x, y + 4, 0xffffff55);
            }

            if (hasConfig(slot))
            {
                WheelSelection.Point gear = pointAt(slot, 35);
                context.drawCenteredTextWithShadow(super.textRenderer, "⚙", Math.round(gear.x()),
                        Math.round(gear.y()) - 4, 0xffffc44f);
            }
        }
    }

    private String pathText()
    {
        String root = this.localMode ? "本地" : "服务器";
        return this.categoryPath.isEmpty() ? root : root + " > " + String.join(" > ", this.categoryPath);
    }

    private String scrollHint()
    {
        boolean paged = WheelSelection.pageCount(this.actions.size(), SLOTS) > 1;
        if (this.configGroup != null)
        {
            return paged ? "滚轮快捷调整轮盘：切页；右侧滚动配置" : "当前仅一页；右侧滚轮滚动配置";
        }

        return paged ? "滚轮快捷调整轮盘：切换动作页" : "当前仅一页；悬停选择或点击动作";
    }

    private void renderPathAndPage(DrawContext context)
    {
        int left = this.layout.panelX() + this.layout.navigationWidth() + 2,
                right = this.layout.panelRight() - this.layout.navigationWidth() - 2;
        context.drawCenteredTextWithShadow(super.textRenderer,
                super.textRenderer.trimToWidth("路径：" + pathText(), Math.max(1, right - left)), (left + right) / 2,
                this.layout.navigationY() + 2, 0xfff3f0e0);
        int pageTop = this.layout.navigationY() + Math.max(12, this.layout.scaled(15)),
                pageBottom = this.layout.navigationY() + this.layout.navigationHeight();
        context.fill(left, pageTop, right, pageBottom, 0xcf000000);
        context.drawCenteredTextWithShadow(super.textRenderer,
                (this.page + 1) + "/" + WheelSelection.pageCount(this.actions.size(), SLOTS), (left + right) / 2,
                pageTop + (pageBottom - pageTop - super.textRenderer.fontHeight) / 2, 0xff55ffff);
    }

    private void buildConfigForms()
    {
        String modelId = this.runtime.localAppearance().modelId();
        int contentWidth = this.layout.formWidth(), offset = 0, savedScroll = this.configScroll;
        this.configScroll = 0;
        Map<String, Double> variables = this.runtime.localModelVariables(modelId);
        for (ModelConfigSchema.Form form : this.configGroup.forms())
        {
            int top = offset;
            List<ClickableWidget> widgets = new ArrayList<>();
            if (form.kind() == ModelConfigSchema.Kind.CHECKBOX)
            {
                AuthorFormWidgets.Check widget = new AuthorFormWidgets.Check(super.textRenderer, this.layout.panelX(),
                        this.layout.viewportY() + offset, contentWidth, 12, form.title(), () -> readForm(form) > 0,
                        () -> applyForm(modelId, form.checkboxScript(readForm(form) <= 0), null, -1),
                        this::insideFormViewport);
                widget.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, form.description()));
                widgets.add(widget);
                registerFormWidget(widget);
                offset += 14;
            }
            else if (form.kind() == ModelConfigSchema.Kind.RANGE)
            {
                AuthorFormWidgets.Range widget = new AuthorFormWidgets.Range(super.textRenderer, this.layout.panelX(),
                        this.layout.viewportY() + offset, contentWidth, 15, form, readForm(form, variables),
                        requested -> applyForm(modelId, form.rangeScript(requested), null, -1),
                        this::insideFormViewport);
                widget.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, form.description()));
                widgets.add(widget);
                registerFormWidget(widget);
                offset += 17;
            }
            else
            {
                int maxLabel = 0;
                for (ModelConfigSchema.Radio choice : form.choices())
                {
                    maxLabel = Math.max(maxLabel, super.textRenderer.getWidth(choice.label()));
                }

                int columns = AuthorFormLayout.radioColumns(contentWidth, maxLabel, form.choices().size()),
                        cellWidth = Math.max(1, contentWidth / columns);
                offset += 14;
                for (int choice = 0; choice < form.choices().size(); choice++)
                {
                    int choiceIndex = choice;
                    AuthorFormWidgets.Check widget = new AuthorFormWidgets.Check(super.textRenderer,
                            this.layout.panelX() + choice % columns * cellWidth,
                            this.layout.viewportY() + offset + choice / columns * 14, cellWidth, 12,
                            form.choices().get(choice).label(), () -> form.selectedIndex(readForm(form)) == choiceIndex,
                            () -> applyForm(modelId, form.radioScript(choiceIndex), form.key(), choiceIndex),
                            this::insideFormViewport);
                    widget.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, form.description()));
                    widgets.add(widget);
                    registerFormWidget(widget);
                }
                offset += AuthorFormLayout.radioRows(form.choices().size(), columns) * 14 + 3;
            }
            this.formRows.add(new FormRow(form, top, List.copyOf(widgets)));
        }
        this.configContentHeight = offset;
        int sw = this.layout.scrollbarWidth(), sh = Math.max(22, this.layout.scaled(60));
        this.scrollUpButton = addControl("config-up", new FlatButton(this.layout.panelRight() - sw,
                this.layout.viewportY(), sw, sh, "↑", () -> scrollConfig(-50)));
        this.scrollDownButton = addControl("config-down", new FlatButton(this.layout.panelRight() - sw,
                this.layout.viewportY() + this.layout.viewportHeight() - sh, sw, sh, "↓", () -> scrollConfig(50)));
        scrollConfig(savedScroll);
    }

    private void registerFormWidget(ClickableWidget widget)
    {
        this.formWidgets.add(widget);
        addSelectableChild(widget);
    }

    private double readForm(ModelConfigSchema.Form form)
    {
        return readForm(form, this.runtime.localModelVariables(this.runtime.localAppearance().modelId()));
    }

    private double readForm(ModelConfigSchema.Form form, Map<String, Double> values)
    {
        if (this.formValues.containsKey(form.key()))
        {
            return this.formValues.get(form.key());
        }

        try
        {
            double value = this.runtime.readLocalModelExpression(this.runtime.localAppearance().modelId(),
                    form.expression(), values);
            if (Double.isFinite(value))
            {
                this.formValues.put(form.key(), value);
                return value;
            }
        }
        catch (RuntimeException invalid)
        {
        }
        this.formValues.put(form.key(), 0d);
        this.message = "模型配置值无法读取：" + form.title();
        return 0;
    }

    private boolean applyForm(String modelId, String script, String radioKey, int radioIndex)
    {
        this.releaseConsumed = true;
        if (!sameSource() || !this.localMode || !this.runtime.canEditLocalAppearance()
                || !this.runtime.localAppearance().modelId().equals(modelId))
        {
            return false;
        }

        Map<String, Integer> radios = new LinkedHashMap<>(this.runtime.options.modelProfile(modelId).radioSelections());
        if (radioKey != null)
        {
            radios.put(radioKey, radioIndex);
        }

        boolean accepted = this.runtime.runLocalScripts(modelId, List.of(script), radios);
        if (accepted)
        {
            this.formValues.clear();
        }

        this.message = accepted ? "已更新模型组件" : "模型组件设置未保存";
        return accepted;
    }

    private boolean insideFormViewport(double x, double y)
    {
        return this.configGroup != null && x >= this.layout.panelX() && x < this.layout.formRight()
                && y >= this.layout.viewportY() && y < this.layout.viewportY() + this.layout.viewportHeight();
    }

    private void scrollConfig(int amount)
    {
        int next = WheelSelection.clampScroll(this.configScroll + amount, this.configContentHeight,
                this.layout.viewportHeight());
        int shift = this.configScroll - next;
        this.configScroll = next;
        for (ClickableWidget widget : this.formWidgets)
        {
            widget.setY(widget.getY() + shift);
            widget.visible = widget.getBottom() > this.layout.viewportY()
                    && widget.getY() < this.layout.viewportY() + this.layout.viewportHeight();
            widget.active = widget.visible && this.runtime.canEditLocalAppearance()
                    && (!(widget instanceof AuthorFormWidgets.Range range)
                            || range.form.maximum() != range.form.minimum());
            if (!widget.visible && getFocused() == widget)
            {
                setFocused(null);
                setDragging(false);
            }
        }
        if (this.scrollUpButton != null)
        {
            this.scrollUpButton.active = this.configScroll > 0;
        }

        if (this.scrollDownButton != null)
        {
            this.scrollDownButton.active = this.configScroll < Math.max(0,
                    this.configContentHeight - this.layout.viewportHeight());
        }

    }

    private void renderConfigForms(DrawContext context, int mouseX, int mouseY, float delta)
    {
        if (this.configGroup == null)
        {
            return;
        }

        context.enableScissor(this.layout.panelX(), this.layout.viewportY(), this.layout.formRight(),
                this.layout.viewportY() + this.layout.viewportHeight());
        int clippedMouseY = insideFormViewport(mouseX, mouseY) ? mouseY : -1000;
        for (AnimationWheelScreen.FormRow row : this.formRows)
        {
            if (row.form.kind() == ModelConfigSchema.Kind.RADIO)

            {
                context.drawTextWithShadow(super.textRenderer,
                        super.textRenderer.trimToWidth(row.form.title(), this.layout.formWidth()), this.layout.panelX(),
                        this.layout.viewportY() + row.top - this.configScroll + 2, 0xfff3f0e0);
            }

        }

        for (ClickableWidget widget : this.formWidgets)
        {
            widget.render(context, mouseX, clippedMouseY, delta);
        }

        context.disableScissor();
    }

    private record NavigationControl(String id, FlatButton widget)
    {
    }

    private record FormRow(ModelConfigSchema.Form form, int top, List<ClickableWidget> controls)
    {
    }

    /** OpenYSM FlatColorButton's flat fill and cream hover outline, with native focus/narration. */
    private class FlatButton extends ButtonWidget
    {
        FlatButton(int x, int y, int width, int height, String label, Runnable action)
        {
            super(x, y, width, height, net.minecraft.text.Text.literal(label), button -> action.run(),
                    DEFAULT_NARRATION_SUPPLIER);
        }

        @Override
        protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta)
        {
            context.fill(getX(), getY(), getRight(), getBottom(), super.active ? -12369342 : 0x80525252);
            if (isHovered() || isFocused())
            {
                context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
            }

            context.drawCenteredTextWithShadow(AnimationWheelScreen.super.textRenderer,
                    AnimationWheelScreen.super.textRenderer.trimToWidth(getMessage().getString(),
                            Math.max(1, getWidth() - 4)),
                    getX() + getWidth() / 2,
                    getY() + (getHeight() - AnimationWheelScreen.super.textRenderer.fontHeight) / 2,
                    super.active ? 0xfff3f0e0 : 0xffa0a0a0);
        }
    }

    /** Same source settings texture and flat icon treatment as the existing gallery settings entry. */
    private final class SettingsIconButton extends FlatButton
    {
        SettingsIconButton(int x, int y, Runnable action)
        {
            super(x, y, 20, 20, "玩家模型设置", action);
        }

        @Override
        protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta)
        {
            context.fill(getX(), getY(), getRight(), getBottom(), -12369342);
            if (isHovered() || isFocused())
            {
                context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
            }

            context.drawTexture(RenderPipelines.GUI_TEXTURED, SETTINGS_ICON, getX() + 2, getY() + 2, 0f, 0f, 16, 16, 32,
                    32, 32, 32);
        }
    }

    public Map<String, Object> diagnostics()
    {
        List<Map<String, Object>> labels = new ArrayList<>(), configSlots = new ArrayList<>(),
                visibleActions = new ArrayList<>();
        if (this.layout != null)
        {
            for (int slot = 0; slot < visibleSlots(); slot++)
            {
                int selected = index(slot);
                WheelSelection.Point point = pointAt(slot, 65);
                int x = Math.round(point.x()), y = Math.round(point.y());
                visibleActions.add(Map.of("slot", slot, "id", this.actions.get(selected).id(), "label",
                        this.actions.get(selected).label(), "x", x, "y", y, "shortcut", Integer.toString(slot + 1),
                        "configGroup", hasConfig(slot) ? this.localEntries.get(selected).configGroup() : "", "category",
                        this.localMode && this.menu != null && this.localEntries.get(selected).category(), "back",
                        this.localMode && this.menu != null && this.localEntries.get(selected).back()));
                List<OrderedText> lines = wrappedLabel(selected);
                int ly = y - 4 - lines.size() * super.textRenderer.fontHeight + 2 + (showShortcutHints() ? 0 : 9);
                for (OrderedText line : lines)
                {
                    int lw = super.textRenderer.getWidth(line);
                    labels.add(Map.of("slot", slot, "text", this.actions.get(selected).label(), "x", x - lw / 2, "y",
                            ly, "width", lw, "height", super.textRenderer.fontHeight));
                    ly += super.textRenderer.fontHeight;
                }
                if (showShortcutHints())
                {
                    labels.add(textRectangle("[ " + (slot + 1) + " ]", x, y, slot));
                }

                if (hasConfig(slot))
                {
                    WheelSelection.Point gear = pointAt(slot, 35);
                    int gx = Math.round(gear.x()), gy = Math.round(gear.y());
                    labels.add(textRectangle("⚙", gx, gy - 4, slot));
                    configSlots.add(Map.of("slot", slot, "group", this.localEntries.get(selected).configGroup(), "x",
                            gx, "y", gy));
                }
            }
        }

        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("centerX", this.centerX);
        result.put("centerY", this.centerY);
        result.put("innerRadius", this.inner);
        result.put("outerRadius", this.outer);
        result.put("scrollHint", scrollHint());
        result.put("settingsIcon", SETTINGS_ICON.toString());
        result.put("page", this.page);
        result.put("localMode", this.localMode);
        result.put("source", source().name());
        result.put("locked", this.locked);
        result.put("keepOpen", this.locked);
        result.put("hovered", this.hovered);
        result.put("hoveredCenter", this.hoveredCenter);
        result.put("labels", labels);
        result.put("visibleActionIds", this.actions.stream().skip((long) this.page * SLOTS).limit(SLOTS)
                .map(ClientRuntime.Action::id).toList());
        result.put("configSlots", configSlots);
        result.put("actionLocked", this.runtime.localActionLocked());
        result.put("category", category());
        result.put("categoryId", category());
        result.put("depth", this.localMode && this.menu != null ? this.menu.depth() : 0);
        result.put("pageCount", WheelSelection.pageCount(this.actions.size(), SLOTS));
        result.put("paginationVisible", WheelSelection.pageCount(this.actions.size(), SLOTS) > 1);
        result.put("paginationControlsVisible", true);
        result.put("ownServerDisguise", this.runtime.hasOwnServerDisguise());
        result.put("serverAvailable", this.serverAvailable);
        result.put("serverModelInstance", this.runtime.serverOwnModelInstance());
        result.put("serverAppearance", this.runtime.ownServerAppearanceDiagnostics());
        result.put("effectiveLocalMode", this.runtime.interactionLocalMode());
        result.put("controls",
                this.navigationControls.stream().map(control -> widgetRectangle(control.id, control.widget)).toList());
        result.put("scopeButtonsVisible", false);
        result.put("localModelDetailsVisible", false);
        result.put("releaseKey", this.releaseKey);
        result.put("releaseType", this.releaseType.name());
        result.put("releaseConsumed", this.releaseConsumed);
        result.put("rememberedSource", this.runtime.wheelPreferences().source().name());
        result.put("rememberedClientPage", this.runtime.wheelPreferences().clientPage());
        result.put("rememberedServerPage", this.runtime.wheelPreferences().serverPage());
        result.put("entries",
                this.localMode && this.menu != null
                        ? this.localEntries.stream()
                                .map(entry -> Map.of("id", entry.id(), "configGroup", entry.configGroup(), "category",
                                        entry.category(), "back", entry.back()))
                                .toList()
                        : List.of());
        result.put("upstreamRoulette", true);
        result.put("upstreamSource", "OpenYSM-Updated/AnimationRouletteScreen@0306e1f");
        result.put("polygon", true);
        result.put("polygons", this.renderedPolygons);
        result.put("emittedVertices", this.emittedVertices.get());
        result.put("actions", visibleActions);
        result.put("path", pathText());
        result.put("categoryPath", List.copyOf(this.categoryPath));
        result.put("formPanel", formDiagnostics());
        return result;
    }

    private Map<String, Object> formDiagnostics()
    {
        List<Map<String, Object>> forms = new ArrayList<>();
        for (AnimationWheelScreen.FormRow row : this.formRows)
        {
            ArrayList<Map<String, Object>> controls = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < row.controls.size(); i++)
            {
                ClickableWidget widget = row.controls.get(i);
                String id = row.form.kind() == ModelConfigSchema.Kind.RADIO ? "radio:" + i
                        : (row.form.kind() == ModelConfigSchema.Kind.RANGE ? "range" : "checkbox");
                LinkedHashMap<String, Object> data = new LinkedHashMap<>(widgetRectangle(id, widget));
                data.put("visible", widget.visible);
                if (widget instanceof AuthorFormWidgets.Check checkbox)
                {
                    data.put("selected", checkbox.selected());
                }

                if (widget instanceof AuthorFormWidgets.Range range)
                {
                    data.put("value", range.currentValue());
                }

                if (row.form.kind() == ModelConfigSchema.Kind.RADIO)
                {
                    data.put("choice", i);
                }

                controls.add(data);
            }
            LinkedHashMap<String, Object> form = new LinkedHashMap<String, Object>();
            form.put("key", row.form.key());
            form.put("kind", row.form.kind().name());
            form.put("title", row.form.title());
            form.put("expression", row.form.expression());
            form.put("value", readForm(row.form));
            form.put("controls", controls);
            form.put("min", row.form.minimum());
            form.put("max", row.form.maximum());
            form.put("step", row.form.step());
            forms.add(form);
        }
        return Map.of("visible", this.configGroup != null, "group", this.configGroupId, "x",
                this.layout == null ? 0 : this.layout.panelX(), "y", this.layout == null ? 0 : this.layout.viewportY(),
                "width", this.layout == null ? 0 : this.layout.formWidth(), "height",
                this.layout == null ? 0 : this.layout.viewportHeight(), "scroll", this.configScroll, "contentHeight",
                this.configContentHeight, "maxScroll",
                this.layout == null ? 0 : Math.max(0, this.configContentHeight - this.layout.viewportHeight()), "forms",
                forms);
    }

    private static Map<String, Object> widgetRectangle(String id, ClickableWidget widget)
    {
        return Map.of("id", id, "label", widget.getMessage().getString(), "x", widget.getX(), "y", widget.getY(),
                "width", widget.getWidth(), "height", widget.getHeight(), "active", widget.active);
    }

    private Map<String, Object> textRectangle(String text, int center, int y, int slot)
    {
        int textWidth = super.textRenderer.getWidth(text);
        return Map.of("slot", slot, "text", text, "x", center - textWidth / 2, "y", y, "width", textWidth, "height",
                super.textRenderer.fontHeight);
    }

    public boolean navigateBack()
    {
        if (this.localMode && this.menu != null && this.menu.depth() > 0)
        {
            rememberPage();
            if (this.menu.back())
            {
                if (!this.categoryPath.isEmpty())
                {
                    this.categoryPath.removeLast();
                }
                changedCategory();
                return true;
            }
        }
        return false;
    }

    @Override
    public void close()
    {
        if (!navigateBack())
        {
            super.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean shouldPause()
    {
        return false;
    }
}
