package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.Perspective;
import net.minecraft.text.Text;
import java.util.List;

/** Basic local model settings, usable in singleplayer and on ordinary servers. */
public final class LocalAppearanceScreen extends Screen {
    private final ClientRuntime runtime;
    private final Screen parent;
    private String modelId, scaleText, xText, yText, zText;
    private String message = "";
    private int left, panel, top;
    private TextFieldWidget scale, offsetX, offsetY, offsetZ;

    public LocalAppearanceScreen(ClientRuntime runtime) { this(runtime, null); }
    public LocalAppearanceScreen(ClientRuntime runtime, Screen parent) {
        super(Text.literal("本地外观设置"));
        this.runtime = runtime;
        this.parent = parent;
        loadDraft(runtime.localAppearance());
    }
    private void loadDraft(LocalAppearanceSettings value) {
        modelId = value.modelId();
        scaleText = number(value.scale()); xText = number(value.offsetX());
        yText = number(value.offsetY()); zText = number(value.offsetZ());
    }
    private static String number(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String number(float value) { return new java.math.BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString(); }
    @Override protected void init() {
        panel = Math.min(450, width - 24); left = (width - panel) / 2;
        top = Math.max(0, (height - 280) / 2);
        int half = (panel - 6) / 2, col = (panel - 12) / 3;
        modelButton("01 · 银灰蓝眼", "ysm_01_jk", left, y(47), half);
        modelButton("02 · 基准酒狐", "ysm_02_jk", left + half + 6, y(47), half);
        List<ClientRuntime.Action> imported = runtime.localModels().stream().filter(action -> action.id().startsWith("local:")).toList();
        String selectedLabel = imported.stream().filter(action -> action.id().equals(modelId)).map(ClientRuntime.Action::label).findFirst().orElse("点击选择");
        var importedButton = ButtonWidget.builder(Text.literal(imported.isEmpty() ? "其他本地模型：暂无" : "本地文件：" + textRenderer.trimToWidth(selectedLabel, panel - 90)), b -> {
            int current = -1;
            for (int i = 0; i < imported.size(); i++) if (imported.get(i).id().equals(modelId)) current = i;
            modelId = imported.get((current + 1) % imported.size()).id(); message = ""; clearAndInit();
        }).dimensions(left, y(71), panel, 20).build();
        importedButton.active = !imported.isEmpty();
        importedButton.setTooltip(Tooltip.of(Text.literal("读取 config/meplayeractions/models 中的 .bbmodel；点击依次选择")));
        addDrawableChild(importedButton);
        scale = field(left, y(109), half, "缩放", scaleText);
        scale.setChangedListener(value -> scaleText = value);
        addDrawableChild(ButtonWidget.builder(Text.literal(runtime.localAppearance().enabled() ? "关闭本地外观" : "开启本地外观"), b -> {
            if (runtime.localAppearance().enabled()) { runtime.disableLocalAppearance(); message = "已恢复服务器显示或原版人物"; clearAndInit(); }
            else if (save(false)) clearAndInit();
        }).dimensions(left + half + 6, y(109), half, 20).build());
        offsetX = field(left, y(149), col, "X 位置", xText);
        offsetY = field(left + col + 6, y(149), col, "Y 位置", yText);
        offsetZ = field(left + 2 * (col + 6), y(149), col, "Z 位置", zText);
        offsetX.setChangedListener(value -> xText = value);
        offsetY.setChangedListener(value -> yText = value);
        offsetZ.setChangedListener(value -> zText = value);
        addDrawableChild(ButtonWidget.builder(Text.literal("保存并预览"), b -> save(true))
                .dimensions(left, y(193), panel, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("恢复默认"), b -> {
            var defaults = LocalAppearanceSettings.defaults(); runtime.updateLocalAppearance(defaults);
            loadDraft(defaults); message = "已恢复默认，关闭本地外观"; clearAndInit();
        }).dimensions(left, y(217), col, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("本地动作预览"), b -> {
            if (client != null) client.setScreen(new ActionsScreen(runtime, true, this));
        }).dimensions(left + col + 6, y(217), col, 20).build()).active = runtime.localAppearance().enabled();
        addDrawableChild(ButtonWidget.builder(Text.literal("停止本地动作"), b -> runtime.stopLocal())
                .dimensions(left + 2 * (col + 6), y(217), col, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> close()).dimensions(left, y(241), panel, 20).build());
    }
    private int y(int offset) { return top + Math.round(offset * Math.min(1f, Math.max(.85f, (height - 12f) / 268f))); }
    private void modelButton(String label, String id, int x, int y, int buttonWidth) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label + (id.equals(modelId) ? " ✓" : "")), b -> {
            modelId = id; message = ""; clearAndInit();
        }).dimensions(x, y, buttonWidth, 20).build());
    }
    private TextFieldWidget field(int x, int y, int fieldWidth, String label, String value) {
        var field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20, Text.literal(label));
        field.setMaxLength(16); field.setText(value);
        field.setTooltip(Tooltip.of(Text.literal(label.equals("缩放") ? "原始尺寸的倍数，范围 0.05 到 8" : "世界坐标偏移，单位为方块，范围 -32 到 32；Y 正值向上")));
        return addDrawableChild(field);
    }
    private double readNumber(TextFieldWidget field, double minimum, double maximum, String label) {
        double value;
        try { value = Double.parseDouble(field.getText().trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(label + "请输入有效数字"); }
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException(label + "范围为 " + number(minimum) + " 到 " + number(maximum));
        return value;
    }
    private boolean save(boolean preview) {
        try {
            var settings = new LocalAppearanceSettings(true, modelId,
                    (float) readNumber(scale, .05, 8, "缩放"), readNumber(offsetX, -32, 32, "X"),
                    readNumber(offsetY, -32, 32, "Y"), readNumber(offsetZ, -32, 32, "Z"));
            runtime.updateLocalAppearance(settings);
            message = client != null && client.world != null ? "已保存，本地外观仅自己可见" : "已保存，进入世界后自动应用";
            if (preview) { runtime.options.showSelf = true; runtime.options.save(); }
            if (preview && client != null && client.world != null) {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                client.setScreen(null);
            }
            return true;
        } catch (IllegalArgumentException exception) { message = exception.getMessage(); return false; }
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xC8101823);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, y(10), 0xffffffff);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("仅自己可见 · 无需服务器插件"), width / 2, y(29), 0xffc6d5ec);
        context.drawTextWithShadow(textRenderer, Text.literal("缩放（0.05–8）"), left, y(97), 0xffc6d5ec);
        int col = (panel - 12) / 3;
        context.drawTextWithShadow(textRenderer, Text.literal("位置 X"), left, y(137), 0xffc6d5ec);
        context.drawTextWithShadow(textRenderer, Text.literal("位置 Y（上为正）"), left + col + 6, y(137), 0xffc6d5ec);
        context.drawTextWithShadow(textRenderer, Text.literal("位置 Z"), left + 2 * (col + 6), y(137), 0xffc6d5ec);
        String status = message.isEmpty() ? runtime.localAppearanceStatus() : message;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(status, panel)), width / 2, y(177), 0xffffd589);
        super.render(context, mouseX, mouseY, delta);
    }
    @Override public void close() { if (client != null) client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
