package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.Perspective;
import net.minecraft.text.Text;
import java.math.BigDecimal;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Actual common appearance settings and supported model parameters, with no invented controls. */
public final class ModelSettingsScreen extends Screen {
    private final ClientRuntime runtime;
    private final Screen parent;
    private final ModelPreview preview = new ModelPreview();
    private String modelId, modelLabel, scaleText, xText, yText, zText, message = "", previewError = "";
    private boolean draftEnabled, draftHideVanillaPlayer, draftHideVanillaEquipment, draftShowSelf,
            activeView, pending, rotating, serverDisguisePresent;
    private LocalModelLibrary.Loaded loaded;
    private long previewRequest;
    private float yaw = 0, pitch = -8, ticks;
    private int left, top, bottom, panelWidth, previewWidth, right, rightWidth;
    private int previewX, previewY, previewW, previewH, drawnPreviewCount, controlHeight = 20, labelGap = 11;
    private TextFieldWidget scale, offsetX, offsetY, offsetZ;
    private ButtonWidget enabled, hideVanillaPlayer, hideVanillaEquipment, showSelf, headdress, skin, authorConfig, save, savePreview;
    private Set<String> formVariables = Set.of();

    public ModelSettingsScreen(ClientRuntime runtime, String id, Screen parent) {
        super(Text.literal("模型详情 / 设置"));
        this.runtime = runtime;
        this.parent = parent;
        loadDraft(runtime.localAppearance());
        modelId = id;
    }
    private void loadDraft(LocalAppearanceSettings value) {
        modelId = value.modelId(); draftEnabled = value.enabled();
        draftHideVanillaPlayer = runtime.options.hideVanillaPlayer;
        draftHideVanillaEquipment = runtime.options.hideVanillaEquipment;
        draftShowSelf = runtime.options.showSelf;
        scaleText = number(value.scale()); xText = number(value.offsetX());
        yText = number(value.offsetY()); zText = number(value.offsetZ());
    }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String number(float value) { return new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString(); }

    @Override protected void init() {
        activeView = true;
        modelLabel = runtime.localModels().stream().filter(model -> model.id().equals(modelId)).map(ClientRuntime.Action::label).findFirst().orElse(modelId);
        if(loaded!=null)modelLabel=loaded.profile().localized(client.getLanguageManager().getLanguage(),"metadata.name",modelLabel);
        panelWidth = Math.max(220, Math.min(780, width - 16));
        left = (width - panelWidth) / 2; top = height < 230 ? 29 : 38; bottom = height - 34;
        previewWidth = Math.max(88, Math.min(260, panelWidth * 34 / 100));
        right = left + previewWidth + 6; rightWidth = panelWidth - previewWidth - 6;
        previewX = left + 4; previewY = top + 30; previewW = previewWidth - 8;
        previewH = Math.max(30, bottom - previewY - 54);
        int innerX = right + 6, innerW = rightWidth - 12, half = (innerW - 4) / 2;
        int available = bottom - top;
        controlHeight = height < 230 ? 18 : 20; labelGap = height < 230 ? 9 : 11;
        // Fixed footer plus proportionally spaced inputs keeps all controls inside small GUI windows.
        int controlY = top + 6, scaleY = top + Math.max(42, available / 3 - 8), offsetsY = top + Math.max(82, available * 2 / 3 - 11);
        enabled = button(draftEnabled ? "本地：开启" : "本地：关闭", innerX, controlY, innerW, () -> {
            draftEnabled = !draftEnabled; updateToggleLabels();
        }, "保存私人外观启停设置\n分享由图库云朵按钮开启");
        if (modelId.equals("openysm_default")) {
            headdress = button(defaultHeaddress() ? "红色蝴蝶结 ✓" : "红色蝴蝶结 ×", innerX, controlY + controlHeight + 4, half,
                    this::toggleDefaultHeaddress, "切换默认模型蝴蝶结\n立即保存并更新预览");
            skin = button(defaultBlueTexture() ? "皮肤：蓝色" : "皮肤：默认", innerX + half + 4, controlY + controlHeight + 4, half,
                    this::toggleDefaultTexture, "切换默认模型皮肤\n立即保存并更新预览");
            // Keep the numeric fields below the real default-model control.
            scaleY = Math.max(scaleY, top + (height < 230 ? 54 : 63)); offsetsY = Math.max(offsetsY, top + (height < 230 ? 83 : 101));
        } else { headdress = null; skin = null; }
        scale = field(innerX, scaleY, innerW, "缩放", scaleText, "原始尺寸的倍数，范围 0.05 到 8");
        scale.setChangedListener(value -> scaleText = value);
        int col = (innerW - 8) / 3;
        offsetX = field(innerX, offsetsY, col, "位置 X", xText, "世界 X 轴偏移\n范围 -32 到 32 格");
        offsetY = field(innerX + col + 4, offsetsY, col, "位置 Y", yText, "世界 Y 轴偏移，正值向上\n范围 -32 到 32 格");
        offsetZ = field(innerX + (col + 4) * 2, offsetsY, col, "位置 Z", zText, "世界 Z 轴偏移\n范围 -32 到 32 格");
        offsetX.setChangedListener(value -> xText = value); offsetY.setChangedListener(value -> yText = value); offsetZ.setChangedListener(value -> zText = value);
        save=button("保存", innerX, bottom - 44, half, () -> saveSettings(false), "保存私人模型设置\n服务器伪装期间仅保存草稿");
        savePreview=button("保存并预览", innerX + half + 4, bottom - 44, half, () -> saveSettings(true), "使用私人模型并切换第三人称\n请先解除服务器伪装");
        button("恢复默认", innerX, bottom - 21, half, this::restoreDefaults, "恢复默认模型、缩放与位置\n关闭私人外观");
        button("关闭本地", innerX + half + 4, bottom - 21, half, () -> {
            runtime.disableLocalAppearance(); draftEnabled = false; updateToggleLabels(); message = "已恢复服务器显示或原版人物";
        }, "关闭私人外观\n保留服务器伪装或原版显示");
        button("返回", left, height - 27, 52, this::close, "返回上一页\n未保存的输入不应用");
        hideVanillaPlayer = layerToggle("玩家隐藏", left + 56, height - 27,
                () -> runtime.serverOwnModelPresent()?runtime.ownPlayerHideSetting():draftHideVanillaPlayer, () -> {
                    if (runtime.serverOwnModelPresent()) return;
                    draftHideVanillaPlayer = !draftHideVanillaPlayer; updateToggleLabels();
                }, "保存原版玩家显隐\n装备与伪装分别控制");
        hideVanillaEquipment = layerToggle("装备隐藏", left + 104, height - 27,
                () -> runtime.serverOwnModelPresent() || draftHideVanillaEquipment, () -> {
                    if (runtime.serverOwnModelPresent()) return;
                    draftHideVanillaEquipment = !draftHideVanillaEquipment; updateToggleLabels();
                }, "保存盔甲、披风与鞘翅显隐\n玩家与伪装分别控制");
        showSelf = layerToggle("伪装显示", left + 152, height - 27, () -> runtime.serverOwnModelShowAllowed()&&draftShowSelf,
                () -> {if(!runtime.serverOwnModelShowAllowed())return;draftShowSelf = !draftShowSelf; updateToggleLabels(); },
                "保存本人伪装显隐\n原版玩家与装备分别控制");
        updateToggleLabels();
        authorConfig = button("作者配置 / 皮肤…", left + 4, bottom - 49, previewWidth - 8, () -> {
            client.setScreen(new ModelConfigScreen(runtime, modelId, this));
        }, "作者定义的配置与皮肤\n按模型保存");
        authorConfig.active = loaded != null;
        loadPreview();
    }

    private ButtonWidget button(String label, int x, int y, int w, Runnable action, String tooltip) {
        var button = ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label, Math.max(1, w - 8))), b -> action.run()).dimensions(x, y, Math.max(20, w), controlHeight).build();
        button.setTooltip(ModelUiTooltip.of(textRenderer,width,tooltip));
        return addDrawableChild(button);
    }
    private ButtonWidget layerToggle(String label, int x, int y, BooleanSupplier selected, Runnable action, String tooltip) {
        var button = new ButtonWidget(x, y, 44, controlHeight, Text.literal(label), b -> action.run(), narration -> narration.get()) {
            @Override protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
                context.fill(getX(), getY(), getRight(), getBottom(), selected.getAsBoolean() ? -14774017 : -12369342);
                if (hovered || isFocused()) context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
                context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(getMessage().getString(), getWidth() - 6),
                        getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, active ? 0xfff3f0e0 : 0xff8a929c);
            }
        };
        button.setTooltip(ModelUiTooltip.of(textRenderer,width,tooltip));
        return addDrawableChild(button);
    }
    private TextFieldWidget field(int x, int y, int w, String label, String value, String tooltip) {
        var field = new TextFieldWidget(textRenderer, x, y, Math.max(20, w), controlHeight, Text.literal(label));
        field.setMaxLength(16); field.setText(value); field.setTooltip(ModelUiTooltip.of(textRenderer,width,tooltip));
        return addDrawableChild(field);
    }
    private void updateToggleLabels() {
        setButtonText(enabled, draftEnabled ? "本地：开启" : "本地：关闭");
        serverDisguisePresent = runtime.serverOwnModelPresent();
        setButtonText(hideVanillaPlayer, serverDisguisePresent&&!runtime.serverOwnPlayerHideRuleKnown()?"服务器控制"
                :(serverDisguisePresent?runtime.ownPlayerHideSetting():draftHideVanillaPlayer)?"玩家隐藏":"玩家显示");
        setButtonText(hideVanillaEquipment, serverDisguisePresent || draftHideVanillaEquipment ? "装备隐藏" : "装备显示");
        setButtonText(showSelf, runtime.serverOwnModelShowAllowed()&&draftShowSelf ? "伪装显示" : "伪装隐藏");
        if (hideVanillaPlayer != null) {
            hideVanillaPlayer.active = !serverDisguisePresent;
            hideVanillaPlayer.setTooltip(ModelUiTooltip.of(textRenderer,width,serverDisguisePresent
                    ? runtime.serverOwnPlayerHideRuleKnown()?"玩家由服务器控制\n当前规则："+(runtime.ownPlayerHideSetting()?"隐藏":"显示")+"\n调整服务端外观设置后应用"
                    :"玩家由服务器控制\n未收到人物隐藏规则\n沿用原版服务器显示"
                    : "保存原版玩家显隐\n装备与伪装分别控制"));
        }
        if (hideVanillaEquipment != null) {
            hideVanillaEquipment.active = !serverDisguisePresent;
            hideVanillaEquipment.setTooltip(ModelUiTooltip.of(textRenderer,width,serverDisguisePresent
                    ? "服务器伪装期间装备保持隐藏\n伪装模型由独立按钮控制"
                    : "保存盔甲、披风与鞘翅显隐\n玩家与伪装分别控制"));
        }
        if(enabled!=null)enabled.active=runtime.canActivateLocalAppearance();
        if(savePreview!=null)savePreview.active=runtime.canActivateLocalAppearance();
        if(showSelf!=null) {
            showSelf.active=runtime.serverOwnModelShowAllowed();
            showSelf.setTooltip(ModelUiTooltip.of(textRenderer,width,showSelf.active?"保存本人伪装显隐\n原版玩家与装备分别控制"
                    :"伪装显示由服务器控制\n当前未允许本人可见\n本机显示偏好保留\n可在服务端外观设置请求本人可见并应用"));
        }
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
            if (loaded != null) {
                modelLabel=loaded.profile().localized(client.getLanguageManager().getLanguage(),"metadata.name",modelLabel);
                try { formVariables = ModelConfigSchema.from(loaded.profile(), client.getLanguageManager().getLanguage()).variables(); }
                catch (RuntimeException invalid) { formVariables = Set.of(); }
            }
            if (authorConfig != null) authorConfig.active = loaded != null;
        }));
    }

    public String selectedModelId() { return modelId; }
    public void setDraftEnabled(boolean value) { draftEnabled = value; updateToggleLabels(); }
    public void setDraftHideVanillaPlayer(boolean value) {
        if (!runtime.serverOwnModelPresent()) draftHideVanillaPlayer = value;
        updateToggleLabels();
    }
    public void setDraftHideVanillaEquipment(boolean value) {
        if (!runtime.serverOwnModelPresent()) draftHideVanillaEquipment = value;
        updateToggleLabels();
    }
    public void setDraftShowSelf(boolean value) { draftShowSelf = value; updateToggleLabels(); }
    public int drawnPreviewCount() { return drawnPreviewCount; }
    public Map<String, Object> previewDiagnostics() { return preview.diagnostics(); }
    public Map<String, Object> previewDiagnostics(float minU, float minV, float maxU, float maxV) {
        return preview.diagnostics(minU, minV, maxU, maxV);
    }
    public boolean defaultHeaddress() {
        return runtime.localModelVariables("openysm_default").getOrDefault("variable.roaming.red_bow_headdress", runtime.options.defaultHeaddress ? 1d : 0d) > 0;
    }
    public void toggleDefaultHeaddress() {
        if (!modelId.equals("openysm_default")) return;
        boolean next = !defaultHeaddress();
        if (!runtime.runLocalScript(modelId, "v.roaming.red_bow_headdress=" + (next ? "1" : "0") + ";")) {
            message = "头饰设置未保存，请等待模型加载完成"; return;
        }
        runtime.options.defaultHeaddress = next;
        runtime.options.save(); updateToggleLabels(); message = "头饰设置已保存";
    }
    public boolean defaultBlueTexture() {
        String texture = runtime.options.modelProfile("openysm_default").textureId();
        return texture.isEmpty() ? runtime.options.defaultBlueTexture : texture.equals("blue");
    }
    public void toggleDefaultTexture() {
        if (!modelId.equals("openysm_default")) return;
        boolean next = !defaultBlueTexture();
        if (!runtime.selectLocalTexture(modelId, next ? "blue" : "default")) { message = "皮肤设置未保存"; return; }
        runtime.options.defaultBlueTexture = next; runtime.options.save();
        runtime.refreshLocalAppearance(); loaded = null; pending = false; previewError = ""; previewRequest++; preview.clear();
        updateToggleLabels(); loadPreview(); message = "皮肤设置已保存";
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
            if(worldPreview && !runtime.canActivateLocalAppearance())throw new IllegalArgumentException("请先解除服务器伪装，再预览私人模型");
            LocalAppearanceSettings draft = draftSettings();
            if ((draft.enabled() || worldPreview) && loaded == null)
                throw new IllegalArgumentException(previewError.isEmpty() ? "请等待模型预览加载完成" : "模型无法使用：" + previewError);
            if (worldPreview) draft = new LocalAppearanceSettings(true, draft.modelId(), draft.scale(), draft.offsetX(), draft.offsetY(), draft.offsetZ());
            runtime.updateLocalAppearance(draft);
            if(worldPreview)runtime.selectLocalModel(draft.modelId());
            runtime.options.hideVanillaPlayer = draftHideVanillaPlayer;
            runtime.options.hideVanillaEquipment = draftHideVanillaEquipment;
            runtime.options.showSelf = worldPreview || draftShowSelf;
            runtime.options.save();
            draftEnabled = draft.enabled();
            draftHideVanillaPlayer = runtime.options.hideVanillaPlayer;
            draftHideVanillaEquipment = runtime.options.hideVanillaEquipment;
            draftShowSelf = runtime.options.showSelf; updateToggleLabels();
            message=runtime.serverOwnModelPresent()?"已保存私人设置；请先解除服务器伪装":worldPreview
                    ?"已保存；等待私人模型加载":"已保存私人模型设置";
            if (worldPreview && client.world != null) {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK); client.setScreen(null);
            }
            return true;
        } catch (IllegalArgumentException exception) { message = exception.getMessage(); return false; }
    }
    private void restoreDefaults() {
        if (!runtime.options.resetModelProfile(modelId) || !runtime.options.resetModelProfile("openysm_default")) {
            message = "重置未保存，请检查配置文件"; return;
        }
        runtime.options.defaultHeaddress = true; runtime.options.defaultBlueTexture = false;
        var defaults = LocalAppearanceSettings.defaults(); runtime.updateLocalAppearance(defaults);
        loadDraft(defaults); loaded = null; pending = false; previewError = ""; previewRequest++; preview.clear();
        message = "已恢复默认，本地外观关闭"; clearAndInit();
    }
    private void clipped(DrawContext context, String text, int x, int y, int w, int color) {
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(text, Math.max(0, w)), x, y, color);
    }

    @Override public void tick() {
        if (!runtime.canEditLocalAppearance()) { client.setScreen(new PlayerModelScreen(runtime)); return; }
        updateToggleLabels();
        ticks++;
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        preview.beginFrame(context);
        drawnPreviewCount = 0;
        context.fill(0, 0, width, height, 0xCF10171F);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 7, 0xfff3f6ff);
        if (height >= 230) context.drawCenteredTextWithShadow(textRenderer, "私人模型 · 参数保存后应用", width / 2, 23, 0xffbccce0);
        context.fill(left, top, left + previewWidth, bottom, 0xE6222730);
        context.fill(right, top, right + rightWidth, bottom, 0xE6222730);
        clipped(context, modelLabel, left + 6, top + 6, previewWidth - 12, 0xfff3f6ff);
        clipped(context, LocalAppearanceScreen.sourceLabel(modelId) + " · " + modelId, left + 6, top + 18, previewWidth - 12, 0xff92b9df);
        if (loaded != null) {
            Map<String, Double> parameters = new LinkedHashMap<>();
            Map<String, Double> values = runtime.localModelVariables(modelId);
            for (String variable : formVariables) if (values.containsKey(variable)) parameters.put(variable, values.get(variable));
            if (preview.render(context, loaded.model(), modelId + ":" + loaded.hash(), previewX, previewY, previewW, previewH, yaw, pitch, ticks + delta, parameters, loaded.previewAnimation(), loaded.profile())) drawnPreviewCount++;
            clipped(context, loaded.model().cubeCount() + " 方块 · " + loaded.model().animations().size() + " 动作", left + 6, bottom - 25, previewWidth - 12, 0xffc6d5e7);
        } else clipped(context, previewError.isEmpty() ? "正在加载模型…" : previewError, previewX + 3, previewY + previewH / 2, previewW - 6, 0xffffc685);
        clipped(context, rotationDisabled() ? "作者固定正面视角" : "拖动旋转", left + 6, bottom - 13, previewWidth - 12, 0xffa7b5c8);
        if (scale != null) {
            clipped(context, "缩放（0.05–8）", scale.getX(), scale.getY() - labelGap, scale.getWidth(), 0xffc6d5e7);
            clipped(context, "X 位置", offsetX.getX(), offsetX.getY() - labelGap, offsetX.getWidth(), 0xffc6d5e7);
            clipped(context, "Y（上为正）", offsetY.getX(), offsetY.getY() - labelGap, offsetY.getWidth(), 0xffc6d5e7);
            clipped(context, "Z 位置", offsetZ.getX(), offsetZ.getY() - labelGap, offsetZ.getWidth(), 0xffc6d5e7);
        }
        clipped(context, message.isEmpty() ? runtime.localAppearanceStatus() : message, left + 205, height - 21, panelWidth - 205, 0xffffd589);
        super.render(context, mouseX, mouseY, delta);
    }
    private boolean insidePreview(double x, double y) { return x >= previewX && x < previewX + previewW && y >= previewY && y < previewY + previewH; }
    private boolean rotationDisabled() { return loaded != null && ModelPreview.rotationDisabled(loaded.profile()); }
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && insidePreview(click.x(), click.y())) {
            if (rotationDisabled()) { rotating = false; return true; }
            rotating = true; if (doubled) { yaw = 0; pitch = -8; } return true;
        }
        return super.mouseClicked(click, doubled);
    }
    @Override public boolean mouseDragged(Click click, double dx, double dy) {
        if (rotating && click.button() == 0) {
            if (rotationDisabled()) { rotating = false; return true; }
            // OpenYSM PlayerTextureScreen.mouseDragged/adjustPitch: screen Y is inverted.
            yaw = (yaw + (float) dx * 1.5f) % 360; pitch = Math.max(-90, Math.min(90, pitch - (float) dy)); return true;
        }
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
