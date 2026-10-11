package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.ServerDisguisePreferences;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local command preferences for one server model; saving never changes a server disguise. */
public final class ServerAppearanceSettingsScreen extends Screen {
    private final ClientRuntime runtime;
    private final Screen parent;
    private final String modelId;
    private final ModelPreview preview;
    private final Map<String, String> draft = new LinkedHashMap<>();
    private final Map<String, TextFieldWidget> fields = new LinkedHashMap<>();
    private final Map<String, ButtonWidget> resets = new LinkedHashMap<>();
    private final List<ClickableWidget> formWidgets = new ArrayList<>();
    private final Map<ClickableWidget, Integer> widgetRows = new IdentityHashMap<>();
    private GalleryPanelLayout.Bounds panel;
    private ButtonWidget resetAll;
    private Element previousFocus;
    private int rowsTop, rowsBottom, scroll;
    private static final int ROW_HEIGHT = 38;
    private String message = "";
    private float ticks;

    public ServerAppearanceSettingsScreen(ClientRuntime runtime, String modelId, Screen parent) {
        super(Text.literal("服务器模型外观设置"));
        this.runtime = runtime;
        this.modelId = modelId;
        this.parent = parent;
        this.preview = new ModelPreview(runtime);
        readDraft(runtime.options.serverDisguisePreferences(modelId));
    }

    private void readDraft(ServerDisguisePreferences value) {
        this.draft.put("scale", number(value.scale()));
        this.draft.put("hideSelf", value.hideSelf() == null ? "" : value.hideSelf().toString());
        this.draft.put("showSelf", value.showSelf() == null ? "" : value.showSelf().toString());
        this.draft.put("viewDistance", number(value.viewDistance()));
        this.draft.put("maxViewers", value.maxViewers() == null ? "" : value.maxViewers().toString());
        this.draft.put("delay", value.delay() == null ? "" : value.delay().toString());
        this.draft.put("effect", value.effect() == null ? "" : value.effect());
    }

    private static String number(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    @Override
    protected void init() {
        this.panel = GalleryPanelLayout.create(super.width, super.height, 38, 29);
        this.fields.clear();
        this.resets.clear();
        this.formWidgets.clear();
        this.widgetRows.clear();
        this.previousFocus = null;
        this.rowsTop = this.panel.top() + 28;
        this.rowsBottom = Math.max(this.rowsTop + 20, this.panel.bottom() - 52);
        int x = this.panel.right() + 9, w = this.panel.rightWidth() - 23, gap = 6,
                half = (this.panel.rightWidth() - 14 - gap) / 2;
        field("scale", "缩放", x, w, "缩放\n0.05–8；留空继承服务器默认");
        field("hideSelf", "隐藏原人物", x, w, "隐藏原人物\ntrue 隐藏／false 显示\n留空继承服务器默认");
        field("showSelf", "本人可见", x, w, "本人可见\ntrue 可见／false 不可见\n服务器仍校验观看许可");
        field("viewDistance", "观看距离（格）", x, w, "观看距离\n0.1–256 格\n留空继承服务器默认");
        field("maxViewers", "最多观看人数", x, w, "最多观看人数\n0–1000 名其他玩家\n0 表示其他玩家均不可见");
        field("delay", "视觉延迟（tick）", x, w, "视觉延迟\n0–20 tick\n留空继承服务器默认");
        field("effect", "缓慢效果", x, w, "缓慢效果\nslowness:等级[:秒数]\n需要服务器效果权限\n留空不请求药水效果");
        scroll(this.scroll);
        x = this.panel.right() + 7;
        button("保存设置", x, this.panel.bottom() - 44, half, this::save, "仅保存本机参数草稿\n返回后点击使用模型或应用设置");
        this.resetAll = button("全部重置", x + half + gap, this.panel.bottom() - 44, half, this::reset,
                "清空本页参数草稿\n保存后返回，再应用服务器默认");
        button("返回", this.panel.left(), this.panel.footerY(), 52, this::close, "返回模型图库\n未保存的输入不应用");
        updateResets();
    }

    private void field(String key, String label, int x, int width, String hint) {
        int row = this.fields.size(), y = this.rowsTop + row * ROW_HEIGHT - this.scroll + 11, resetWidth = 38,
                fieldWidth = Math.max(20, width - resetWidth - 5);
        TextFieldWidget field = new TextFieldWidget(super.textRenderer, x, y, fieldWidth, 20, Text.literal(label)) {
            @Override
            public boolean mouseClicked(Click click, boolean doubled) {
                return insideRows(click.x(), click.y()) && super.mouseClicked(click, doubled);
            }

            @Override
            public boolean isMouseOver(double mouseX, double mouseY) {
                return insideRows(mouseX, mouseY) && super.isMouseOver(mouseX, mouseY);
            }
        };
        field.setMaxLength(key.equals("effect") ? 40 : 24);
        field.setPlaceholder(Text.literal("服务器默认"));
        field.setText(this.draft.getOrDefault(key, ""));
        field.setChangedListener(value -> {
            this.draft.put(key, value);
            this.message = "参数待保存；尚未改变服务器伪装";
            updateResets();
        });
        field.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, hint));
        this.fields.put(key, field);
        formWidget(field, row);
        ButtonWidget reset = new ButtonWidget(x + fieldWidth + 5, y, resetWidth, 20, Text.literal("重置"),
                b -> field.setText(""), supplier -> supplier.get()) {
            @Override
            protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
                drawButton(context);
                context.drawCenteredTextWithShadow(ServerAppearanceSettingsScreen.super.textRenderer, getMessage(),
                        getX() + getWidth() / 2, getY() + 6, super.active ? 0xfff3f0e0 : 0xff8a929c);
            }

            @Override
            public boolean mouseClicked(Click click, boolean doubled) {
                return insideRows(click.x(), click.y()) && super.mouseClicked(click, doubled);
            }

            @Override
            public boolean isMouseOver(double mouseX, double mouseY) {
                return insideRows(mouseX, mouseY) && super.isMouseOver(mouseX, mouseY);
            }
        };
        reset.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, "重置" + label + "\n仅清空此项草稿\n保存并应用后继承服务器默认"));
        this.resets.put(key, reset);
        formWidget(reset, row);
    }

    private void formWidget(ClickableWidget widget, int row) {
        this.formWidgets.add(widget);
        this.widgetRows.put(widget, row);
        addSelectableChild(widget);
    }

    private ButtonWidget button(String label, int x, int y, int w, Runnable action, String hint) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), b -> action.run())
                .dimensions(x, y, Math.max(20, w), 20).build();
        button.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, hint));
        return addDrawableChild(button);
    }

    public boolean save() {
        try {
            ServerDisguisePreferences value = ServerDisguisePreferences.fromFields(this.draft.get("scale"),
                    this.draft.get("hideSelf"), this.draft.get("showSelf"), this.draft.get("viewDistance"),
                    this.draft.get("maxViewers"), this.draft.get("delay"), this.draft.get("effect"));
            if (!this.runtime.options.updateServerDisguisePreferences(this.modelId, value)) {
                this.message = "参数未保存，请检查配置空间与文件权限";
                return false;
            }
            this.runtime.serverPreferencesSaved(this.modelId);
            this.message = "已保存草稿；返回后点击使用模型或应用设置";
            return true;
        } catch (IllegalArgumentException invalid) {
            this.message = invalid.getMessage();
            return false;
        }
    }

    private void reset() {
        readDraft(ServerDisguisePreferences.defaults());
        this.fields.forEach((key, field) -> field.setText(this.draft.get(key)));
        updateResets();
        this.message = "草稿已重置；保存并应用后继承服务器默认";
    }

    private void updateResets() {
        this.resets.forEach((key, button) -> button.active = !this.draft.getOrDefault(key, "").isBlank());
        if (this.resetAll != null) {
            this.resetAll.active = this.draft.values().stream().anyMatch(value -> !value.isBlank());
        }

    }

    private boolean insideRows(double x, double y) {
        return this.panel != null && x >= this.panel.right() && x < this.panel.right() + this.panel.rightWidth()
                && y >= this.rowsTop && y < this.rowsBottom;
    }

    private void scroll(int requested) {
        this.scroll = Math.max(0,
                Math.min(requested, Math.max(0, this.fields.size() * ROW_HEIGHT - (this.rowsBottom - this.rowsTop))));
        this.formWidgets.forEach(
                widget -> widget.setY(this.rowsTop + this.widgetRows.get(widget) * ROW_HEIGHT - this.scroll + 11));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical != 0 && insideRows(mouseX, mouseY)) {
            scroll(this.scroll + (vertical > 0 ? -ROW_HEIGHT : ROW_HEIGHT));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void tick() {
        this.ticks++;
        Element focused = getFocused();
        if (focused != this.previousFocus && focused instanceof ClickableWidget widget
                && this.widgetRows.containsKey(widget)) {
            int top = this.widgetRows.get(widget) * ROW_HEIGHT, bottom = top + ROW_HEIGHT;
            if (top < this.scroll) {
                scroll(top);
            } else if (bottom > this.scroll + this.rowsBottom - this.rowsTop) {
                scroll(bottom - (this.rowsBottom - this.rowsTop));
            }

        }
        this.previousFocus = focused;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.preview.beginFrame(context);
        context.fill(this.panel.left() - 4, this.panel.frameTop() - 2, this.panel.left() + this.panel.panelWidth() + 4,
                this.panel.frameBottom() + 2, 0xCF10171F);
        context.drawCenteredTextWithShadow(super.textRenderer, super.title, super.width / 2, this.panel.titleY(),
                0xfff3f0e0);
        context.fill(this.panel.left(), this.panel.top(), this.panel.left() + this.panel.previewWidth(),
                this.panel.bottom(), 0xE6222730);
        context.fill(this.panel.right(), this.panel.top(), this.panel.right() + this.panel.rightWidth(),
                this.panel.bottom(), 0xE6222730);
        clipped(context, this.modelId, this.panel.left() + 6, this.panel.top() + 6, this.panel.previewWidth() - 12,
                0xfff3f0e0);
        clipped(context, "参数仅保存于本机", this.panel.left() + 6, this.panel.top() + 17, this.panel.previewWidth() - 12,
                0xff92b9df);
        int previewY = this.panel.top() + 29, previewH = Math.max(25, this.panel.bottom() - previewY - 9);
        LocalModelLibrary.Loaded loaded = this.runtime.serverModelForPreview(this.modelId);
        if (loaded != null) {
            this.preview.render(context, loaded.model(), "server-settings:" + this.modelId + ":" + loaded.hash(),
                    this.panel.left() + 4, previewY, this.panel.previewWidth() - 8, previewH, 0, -8, this.ticks + delta,
                    Map.of(), loaded.previewAnimation(), loaded.profile(), ModelPreview.Context.SELECTED);
        }

        else {
            clipped(context, this.runtime.serverCatalogOfflineMode() ? "正在准备本机缓存预览…" : "使用后由服务器下发模型",
                    this.panel.left() + 6, previewY + previewH / 2, this.panel.previewWidth() - 12, 0xffffc685);
        }

        clipped(context, "留空继承服务器默认", this.panel.right() + 7, this.panel.top() + 5, this.panel.rightWidth() - 14,
                0xff92b9df);
        clipped(context, "滚轮浏览 · Tab 切换输入框", this.panel.right() + 7, this.panel.top() + 16,
                this.panel.rightWidth() - 14, 0xff92b9df);
        context.enableScissor(this.panel.right(), this.rowsTop, this.panel.right() + this.panel.rightWidth(),
                this.rowsBottom);
        for (Map.Entry<String, TextFieldWidget> entry : this.fields.entrySet()) {
            TextFieldWidget field = entry.getValue();
            context.drawTextWithShadow(super.textRenderer, field.getMessage(), field.getX(), field.getY() - 10,
                    0xffc6d5e7);
            if (!this.draft.getOrDefault(entry.getKey(), "").isBlank()) {
                context.fill(field.getX() - 4, field.getY() - 10, field.getX() - 2, field.getBottom(), 0xffffd052);
            }

        }
        boolean mouseInside = insideRows(mouseX, mouseY);
        for (ClickableWidget widget : this.formWidgets) {
            if (widget.getBottom() > this.rowsTop && widget.getY() - 11 < this.rowsBottom) {
                widget.render(context, mouseInside ? mouseX : Integer.MIN_VALUE,
                        mouseInside ? mouseY : Integer.MIN_VALUE, delta);
            }

        }

        context.disableScissor();
        int maxScroll = Math.max(0, this.fields.size() * ROW_HEIGHT - (this.rowsBottom - this.rowsTop));
        if (maxScroll > 0) {
            int trackX = this.panel.right() + this.panel.rightWidth() - 5, viewHeight = this.rowsBottom - this.rowsTop,
                    thumbHeight = Math.max(12, viewHeight * viewHeight / (this.fields.size() * ROW_HEIGHT));
            context.fill(trackX, this.rowsTop, trackX + 2, this.rowsBottom, 0xff353d48);
            int thumbTop = this.rowsTop + this.scroll * (viewHeight - thumbHeight) / maxScroll;
            context.fill(trackX, thumbTop, trackX + 2, thumbTop + thumbHeight, 0xff92b9df);
        }
        if (!this.message.isEmpty()) {
            clipped(context, this.message, this.panel.right() + 7, this.panel.bottom() - 15,
                    this.panel.rightWidth() - 14, 0xffffd589);
        }

        clipped(context, "使用时由服务器校验参数与权限", this.panel.left() + 60, this.panel.footerY() + 6,
                this.panel.panelWidth() - 60, 0xff92b9df);
        super.render(context, mouseX, mouseY, delta);
    }

    private void clipped(DrawContext context, String value, int x, int y, int width, int color) {
        context.drawTextWithShadow(super.textRenderer, super.textRenderer.trimToWidth(value, Math.max(0, width)), x, y,
                color);
    }

    @Override
    public void removed() {
        this.preview.clear();
    }

    @Override
    public void close() {
        super.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
