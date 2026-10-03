package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.Perspective;
import net.minecraft.text.Text;
import java.math.BigDecimal;
import java.util.Map;

/** Actual common appearance settings and supported model parameters, with no invented controls. */
public final class ModelSettingsScreen extends Screen {
    private final ClientRuntime runtime;
    private final Screen parent;
    private final ModelPreview preview = new ModelPreview();
    private String modelId, modelLabel, scaleText, xText, yText, zText, message = "", previewError = "";
    private boolean draftEnabled, draftShowSelf, activeView, pending, rotating;
    private LocalModelLibrary.Loaded loaded;
    private long previewRequest;
    private float yaw = 0, pitch = -8, ticks;
    private int left, top, bottom, panelWidth, previewWidth, right, rightWidth;
    private int previewX, previewY, previewW, previewH, drawnPreviewCount, controlHeight = 20, labelGap = 11;
    private TextFieldWidget scale, offsetX, offsetY, offsetZ;
    private ButtonWidget enabled, showSelf, headdress, skin;

    public ModelSettingsScreen(ClientRuntime runtime, String id, Screen parent) {
        super(Text.literal("模型详情 / 设置"));
        this.runtime = runtime;
        this.parent = parent;
        loadDraft(runtime.localAppearance());
        modelId = id;
    }
    private void loadDraft(LocalAppearanceSettings value) {
        modelId = value.modelId(); draftEnabled = value.enabled(); draftShowSelf = runtime.options.showSelf;
        scaleText = number(value.scale()); xText = number(value.offsetX());
        yText = number(value.offsetY()); zText = number(value.offsetZ());
    }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String number(float value) { return new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString(); }

    @Override protected void init() {
        activeView = true;
        modelLabel = runtime.localModels().stream().filter(model -> model.id().equals(modelId)).map(ClientRuntime.Action::label).findFirst().orElse(modelId);
        panelWidth = Math.max(220, Math.min(780, width - 16));
        left = (width - panelWidth) / 2; top = height < 230 ? 29 : 38; bottom = height - 34;
        previewWidth = Math.max(88, Math.min(260, panelWidth * 34 / 100));
        right = left + previewWidth + 6; rightWidth = panelWidth - previewWidth - 6;
        previewX = left + 4; previewY = top + 30; previewW = previewWidth - 8;
        previewH = Math.max(30, bottom - previewY - 30);
        int innerX = right + 6, innerW = rightWidth - 12, half = (innerW - 4) / 2;
        int available = bottom - top;
        controlHeight = height < 230 ? 18 : 20; labelGap = height < 230 ? 9 : 11;
        // Fixed footer plus proportionally spaced inputs keeps all controls inside small GUI windows.
        int controlY = top + 6, scaleY = top + Math.max(42, available / 3 - 8), offsetsY = top + Math.max(82, available * 2 / 3 - 11);
        enabled = button(draftEnabled ? "本地：开启" : "本地：关闭", innerX, controlY, half, () -> {
            draftEnabled = !draftEnabled; updateToggleLabels();
        }, "保存后开启或关闭本地外观；仅自己可见");
        showSelf = button(draftShowSelf ? "本人：显示" : "本人：隐藏", innerX + half + 4, controlY, half, () -> {
            draftShowSelf = !draftShowSelf; updateToggleLabels();
        }, "第三人称显示本人模型；第一人称不显示身体");
        if (modelId.equals("openysm_default")) {
            headdress = button(defaultHeaddress() ? "红色蝴蝶结 ✓" : "红色蝴蝶结 ×", innerX, controlY + controlHeight + 4, half,
                    this::toggleDefaultHeaddress, "参考默认模型的红色蝴蝶结头饰；立即保存，预览同步更新");
            skin = button(defaultBlueTexture() ? "皮肤：蓝色" : "皮肤：默认", innerX + half + 4, controlY + controlHeight + 4, half,
                    this::toggleDefaultTexture, "参考默认模型的两张原始皮肤；立即保存并更新当前本地外观");
            // Keep the numeric fields below the real default-model control.
            scaleY = Math.max(scaleY, top + (height < 230 ? 54 : 63)); offsetsY = Math.max(offsetsY, top + (height < 230 ? 83 : 101));
        } else { headdress = null; skin = null; }
        scale = field(innerX, scaleY, innerW, "缩放", scaleText, "原始尺寸的倍数，范围 0.05 到 8");
        scale.setChangedListener(value -> scaleText = value);
        int col = (innerW - 8) / 3;
        offsetX = field(innerX, offsetsY, col, "位置 X", xText, "世界 X 轴偏移，单位为方块，范围 -32 到 32");
        offsetY = field(innerX + col + 4, offsetsY, col, "位置 Y", yText, "世界 Y 轴偏移；正值向上，单位为方块，范围 -32 到 32");
        offsetZ = field(innerX + (col + 4) * 2, offsetsY, col, "位置 Z", zText, "世界 Z 轴偏移，单位为方块，范围 -32 到 32");
        offsetX.setChangedListener(value -> xText = value); offsetY.setChangedListener(value -> yText = value); offsetZ.setChangedListener(value -> zText = value);
        button("保存", innerX, bottom - 44, half, () -> saveSettings(false), "保存并立即应用当前设置，保留本页");
        button("保存并预览", innerX + half + 4, bottom - 44, half, () -> saveSettings(true), "开启本地外观与本人显示；在世界中切换到第三人称查看");
        button("恢复默认", innerX, bottom - 21, half, this::restoreDefaults, "恢复默认模型、缩放和位置，并关闭本地外观");
        button("关闭本地", innerX + half + 4, bottom - 21, half, () -> {
            runtime.disableLocalAppearance(); draftEnabled = false; updateToggleLabels(); message = "已恢复服务器显示或原版人物";
        }, "立即关闭本地外观，恢复服务器显示或原版人物");
        button("返回", left, height - 27, 52, this::close, "返回上一页；未保存的缩放与位置不应用");
        loadPreview();
    }

    private ButtonWidget button(String label, int x, int y, int w, Runnable action, String tooltip) {
        var button = ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label, Math.max(1, w - 8))), b -> action.run()).dimensions(x, y, Math.max(20, w), controlHeight).build();
        button.setTooltip(Tooltip.of(Text.literal(tooltip)));
        return addDrawableChild(button);
    }
    private TextFieldWidget field(int x, int y, int w, String label, String value, String tooltip) {
        var field = new TextFieldWidget(textRenderer, x, y, Math.max(20, w), controlHeight, Text.literal(label));
        field.setMaxLength(16); field.setText(value); field.setTooltip(Tooltip.of(Text.literal(tooltip)));
        return addDrawableChild(field);
    }
    private void updateToggleLabels() {
        setButtonText(enabled, draftEnabled ? "本地：开启" : "本地：关闭");
        setButtonText(showSelf, draftShowSelf ? "本人：显示" : "本人：隐藏");
        setButtonText(headdress, defaultHeaddress() ? "红色蝴蝶结 ✓" : "红色蝴蝶结 ×");
        setButtonText(skin, defaultBlueTexture() ? "皮肤：蓝色" : "皮肤：默认");
    }
    private void setButtonText(ButtonWidget button, String text) {
        if (button != null) button.setMessage(Text.literal(textRenderer.trimToWidth(text, button.getWidth() - 8)));
    }
    private void loadPreview() {
        if (loaded != null || pending) return;
        pending = true; long request = ++previewRequest; String id = modelId;
        runtime.loadLocalPreview(id).whenComplete((model, error) -> client.execute(() -> {
            if (!activeView || request != previewRequest || !modelId.equals(id)) return;
            pending = false; loaded = model; previewError = error == null ? "" : LocalAppearanceScreen.loadError(error);
        }));
    }

    public String selectedModelId() { return modelId; }
    public void setDraftEnabled(boolean value) { draftEnabled = value; updateToggleLabels(); }
    public void setDraftShowSelf(boolean value) { draftShowSelf = value; updateToggleLabels(); }
    public int drawnPreviewCount() { return drawnPreviewCount; }
    public Map<String, Object> previewDiagnostics() { return preview.diagnostics(); }
    public boolean defaultHeaddress() { return runtime.options.defaultHeaddress; }
    public void toggleDefaultHeaddress() {
        if (!modelId.equals("openysm_default")) return;
        runtime.options.defaultHeaddress = !runtime.options.defaultHeaddress;
        runtime.options.save(); updateToggleLabels(); message = "头饰设置已保存，仅自己可见";
    }
    public boolean defaultBlueTexture() { return runtime.options.defaultBlueTexture; }
    public void toggleDefaultTexture() {
        if (!modelId.equals("openysm_default")) return;
        runtime.options.defaultBlueTexture = !runtime.options.defaultBlueTexture; runtime.options.save();
        runtime.refreshLocalAppearance(); loaded = null; pending = false; previewError = ""; previewRequest++; preview.clear();
        updateToggleLabels(); loadPreview(); message = "皮肤设置已保存，仅自己可见";
    }
    public void setDraftNumbers(String scaleValue, String x, String y, String z) {
        scaleText = scaleValue; xText = x; yText = y; zText = z;
        if (scale != null) { scale.setText(scaleValue); offsetX.setText(x); offsetY.setText(y); offsetZ.setText(z); }
    }
    public LocalAppearanceSettings draftSettings() {
        return new LocalAppearanceSettings(draftEnabled, modelId,
                (float) readNumber(scaleText, .05, 8, "缩放"), readNumber(xText, -32, 32, "X"),
                readNumber(yText, -32, 32, "Y"), readNumber(zText, -32, 32, "Z"));
    }
    private static double readNumber(String text, double minimum, double maximum, String label) {
        double value;
        try { value = Double.parseDouble(text.trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(label + "请输入有效数字"); }
        if (!Double.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(label + "范围为 " + number(minimum) + " 到 " + number(maximum));
        return value;
    }
    public boolean saveSettings(boolean worldPreview) {
        try {
            LocalAppearanceSettings draft = draftSettings();
            if ((draft.enabled() || worldPreview) && loaded == null)
                throw new IllegalArgumentException(previewError.isEmpty() ? "请等待模型预览加载完成" : "模型无法使用：" + previewError);
            if (worldPreview) draft = new LocalAppearanceSettings(true, draft.modelId(), draft.scale(), draft.offsetX(), draft.offsetY(), draft.offsetZ());
            runtime.updateLocalAppearance(draft);
            runtime.options.showSelf = worldPreview || draftShowSelf; runtime.options.save();
            draftEnabled = draft.enabled(); draftShowSelf = runtime.options.showSelf; updateToggleLabels();
            message = draft.enabled() ? client.world == null ? "已保存，进入世界后显示" : "已保存，本地外观仅自己可见" : "已保存，本地外观关闭";
            if (worldPreview && client.world != null) {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK); client.setScreen(null);
            }
            return true;
        } catch (IllegalArgumentException exception) { message = exception.getMessage(); return false; }
    }
    private void restoreDefaults() {
        runtime.options.defaultHeaddress = true; runtime.options.defaultBlueTexture = false;
        var defaults = LocalAppearanceSettings.defaults(); runtime.updateLocalAppearance(defaults);
        loadDraft(defaults); loaded = null; pending = false; previewError = ""; previewRequest++; preview.clear();
        message = "已恢复默认，本地外观关闭"; clearAndInit();
    }
    private void clipped(DrawContext context, String text, int x, int y, int w, int color) {
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(text, Math.max(0, w)), x, y, color);
    }

    @Override public void tick() { ticks++; }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        preview.beginFrame(context);
        drawnPreviewCount = 0;
        context.fill(0, 0, width, height, 0xCF10171F);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 7, 0xfff3f6ff);
        if (height >= 230) context.drawCenteredTextWithShadow(textRenderer, "仅自己可见 · 参数保存后应用", width / 2, 23, 0xffbccce0);
        context.fill(left, top, left + previewWidth, bottom, 0xE6222730);
        context.fill(right, top, right + rightWidth, bottom, 0xE6222730);
        clipped(context, modelLabel, left + 6, top + 6, previewWidth - 12, 0xfff3f6ff);
        clipped(context, LocalAppearanceScreen.sourceLabel(modelId) + " · " + modelId, left + 6, top + 18, previewWidth - 12, 0xff92b9df);
        if (loaded != null) {
            Map<String, Double> parameters = modelId.equals("openysm_default")
                    ? Map.of("variable.roaming.red_bow_headdress", defaultHeaddress() ? 1d : 0d) : Map.of();
            if (preview.render(context, loaded.model(), modelId + ":" + loaded.hash(), previewX, previewY, previewW, previewH, yaw, pitch, ticks + delta, parameters)) drawnPreviewCount++;
            clipped(context, loaded.model().cubeCount() + " 方块 · " + loaded.model().animations().size() + " 动作", left + 6, bottom - 25, previewWidth - 12, 0xffc6d5e7);
        } else clipped(context, previewError.isEmpty() ? "正在加载模型…" : previewError, previewX + 3, previewY + previewH / 2, previewW - 6, 0xffffc685);
        clipped(context, "拖动旋转 · 自动居中", left + 6, bottom - 13, previewWidth - 12, 0xffa7b5c8);
        if (scale != null) {
            clipped(context, "缩放（0.05–8）", scale.getX(), scale.getY() - labelGap, scale.getWidth(), 0xffc6d5e7);
            clipped(context, "X 位置", offsetX.getX(), offsetX.getY() - labelGap, offsetX.getWidth(), 0xffc6d5e7);
            clipped(context, "Y（上为正）", offsetY.getX(), offsetY.getY() - labelGap, offsetY.getWidth(), 0xffc6d5e7);
            clipped(context, "Z 位置", offsetZ.getX(), offsetZ.getY() - labelGap, offsetZ.getWidth(), 0xffc6d5e7);
        }
        clipped(context, message.isEmpty() ? runtime.localAppearanceStatus() : message, left + 59, height - 21, panelWidth - 59, 0xffffd589);
        super.render(context, mouseX, mouseY, delta);
    }
    private boolean insidePreview(double x, double y) { return x >= previewX && x < previewX + previewW && y >= previewY && y < previewY + previewH; }
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && insidePreview(click.x(), click.y())) {
            rotating = true; if (doubled) { yaw = 0; pitch = -8; } return true;
        }
        return super.mouseClicked(click, doubled);
    }
    @Override public boolean mouseDragged(Click click, double dx, double dy) {
        if (rotating && click.button() == 0) { yaw = (yaw + (float) dx * 1.5f) % 360; pitch = Math.max(-65, Math.min(65, pitch + (float) dy)); return true; }
        return super.mouseDragged(click, dx, dy);
    }
    @Override public boolean mouseReleased(Click click) {
        if (rotating && click.button() == 0) { rotating = false; return true; }
        return super.mouseReleased(click);
    }
    @Override public void removed() { activeView = false; rotating = false; pending = false; previewRequest++; loaded = null; preview.clear(); }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
