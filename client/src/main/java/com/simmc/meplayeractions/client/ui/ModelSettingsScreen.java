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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Actual common appearance settings and supported model parameters, with no invented controls. */
public final class ModelSettingsScreen extends Screen {
    private final ClientRuntime runtime;
    private final Screen parent;
    private final ModelPreview preview = new ModelPreview();
    private String modelId, modelLabel, scaleText, xText, yText, zText, message = "", previewError = "";
    private boolean draftEnabled, draftHideVanillaPlayer, draftHideVanillaEquipment, draftShowSelf, activeView, pending,
            rotating, serverDisguisePresent;
    private LocalModelLibrary.Loaded loaded;
    private long previewRequest;
    private float yaw = 0, pitch = -8, ticks;
    private int left, top, bottom, panelWidth, previewWidth, right, rightWidth;
    private int previewX, previewY, previewW, previewH, drawnPreviewCount, controlHeight = 20, labelGap = 11;
    private TextFieldWidget scale, offsetX, offsetY, offsetZ;
    private ButtonWidget enabled, hideVanillaPlayer, hideVanillaEquipment, showSelf, headdress, skin, authorConfig,
            save, savePreview;
    private Set<String> formVariables = Set.of();

    public ModelSettingsScreen(ClientRuntime runtime, String id, Screen parent) {
        super(Text.literal("模型详情 / 设置"));
        this.runtime = runtime;
        this.parent = parent;
        loadDraft(runtime.localAppearance());
        this.modelId = id;
    }

    private void loadDraft(LocalAppearanceSettings value) {
        this.modelId = value.modelId();
        this.draftEnabled = value.enabled();
        this.draftHideVanillaPlayer = this.runtime.options.hideVanillaPlayer;
        this.draftHideVanillaEquipment = this.runtime.options.hideVanillaEquipment;
        this.draftShowSelf = this.runtime.options.showSelf;
        this.scaleText = number(value.scale());
        this.xText = number(value.offsetX());
        this.yText = number(value.offsetY());
        this.zText = number(value.offsetZ());
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String number(float value) {
        return new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
    }

    @Override
    protected void init() {
        this.activeView = true;
        this.modelLabel = this.runtime.localModels().stream().filter(model -> model.id().equals(this.modelId))
                .map(ClientRuntime.Action::label).findFirst().orElse(this.modelId);
        if (this.loaded != null) {
            this.modelLabel = this.loaded.profile().localized(super.client.getLanguageManager().getLanguage(),
                    "metadata.name", this.modelLabel);
        }

        this.panelWidth = Math.max(220, Math.min(780, super.width - 16));
        this.left = (super.width - this.panelWidth) / 2;
        this.top = super.height < 230 ? 29 : 38;
        this.bottom = super.height - 34;
        this.previewWidth = Math.max(88, Math.min(260, this.panelWidth * 34 / 100));
        this.right = this.left + this.previewWidth + 6;
        this.rightWidth = this.panelWidth - this.previewWidth - 6;
        this.previewX = this.left + 4;
        this.previewY = this.top + 30;
        this.previewW = this.previewWidth - 8;
        this.previewH = Math.max(30, this.bottom - this.previewY - 54);
        int innerX = this.right + 6, innerW = this.rightWidth - 12, half = (innerW - 4) / 2;
        int available = this.bottom - this.top;
        this.controlHeight = super.height < 230 ? 18 : 20;
        this.labelGap = super.height < 230 ? 9 : 11;
        // Fixed footer plus proportionally spaced inputs keeps all controls inside small GUI windows.
        int controlY = this.top + 6, scaleY = this.top + Math.max(42, available / 3 - 8),
                offsetsY = this.top + Math.max(82, available * 2 / 3 - 11);
        this.enabled = button(this.draftEnabled ? "本地：开启" : "本地：关闭", innerX, controlY, innerW, () -> {
            this.draftEnabled = !this.draftEnabled;
            updateToggleLabels();
        }, "保存私人外观启停设置\n分享由图库云朵按钮开启");
        if (this.modelId.equals("openysm_default")) {
            this.headdress = button(defaultHeaddress() ? "红色蝴蝶结 ✓" : "红色蝴蝶结 ×", innerX,
                    controlY + this.controlHeight + 4, half, this::toggleDefaultHeaddress, "切换默认模型蝴蝶结\n立即保存并更新预览");
            this.skin = button(defaultBlueTexture() ? "皮肤：蓝色" : "皮肤：默认", innerX + half + 4,
                    controlY + this.controlHeight + 4, half, this::toggleDefaultTexture, "切换默认模型皮肤\n立即保存并更新预览");
            // Keep the numeric fields below the real default-model control.
            scaleY = Math.max(scaleY, this.top + (super.height < 230 ? 54 : 63));
            offsetsY = Math.max(offsetsY, this.top + (super.height < 230 ? 83 : 101));
        } else {
            this.headdress = null;
            this.skin = null;
        }
        this.scale = field(innerX, scaleY, innerW, "缩放", this.scaleText, "原始尺寸的倍数，范围 0.05 到 8");
        this.scale.setChangedListener(value -> this.scaleText = value);
        int col = (innerW - 8) / 3;
        this.offsetX = field(innerX, offsetsY, col, "位置 X", this.xText, "世界 X 轴偏移\n范围 -32 到 32 格");
        this.offsetY = field(innerX + col + 4, offsetsY, col, "位置 Y", this.yText, "世界 Y 轴偏移，正值向上\n范围 -32 到 32 格");
        this.offsetZ = field(innerX + (col + 4) * 2, offsetsY, col, "位置 Z", this.zText, "世界 Z 轴偏移\n范围 -32 到 32 格");
        this.offsetX.setChangedListener(value -> this.xText = value);
        this.offsetY.setChangedListener(value -> this.yText = value);
        this.offsetZ.setChangedListener(value -> this.zText = value);
        this.save = button("保存", innerX, this.bottom - 44, half, () -> saveSettings(false), "保存私人模型设置\n服务器伪装期间仅保存草稿");
        this.savePreview = button("保存并预览", innerX + half + 4, this.bottom - 44, half, () -> saveSettings(true),
                "使用私人模型并切换第三人称\n请先解除服务器伪装");
        button("恢复默认", innerX, this.bottom - 21, half, this::restoreDefaults, "恢复默认模型、缩放与位置\n关闭私人外观");
        button("关闭本地", innerX + half + 4, this.bottom - 21, half, () -> {
            this.runtime.disableLocalAppearance();
            this.draftEnabled = false;
            updateToggleLabels();
            this.message = "已恢复服务器显示或原版人物";
        }, "关闭私人外观\n保留服务器伪装或原版显示");
        button("返回", this.left, super.height - 27, 52, this::close, "返回上一页\n未保存的输入不应用");
        this.hideVanillaPlayer = layerToggle("玩家隐藏", this.left + 56, super.height - 27,
                () -> this.runtime.serverOwnModelPresent() ? this.runtime.ownPlayerHideSetting()
                        : this.draftHideVanillaPlayer,
                () -> {
                    if (this.runtime.serverOwnModelPresent()) {
                        return;
                    }

                    this.draftHideVanillaPlayer = !this.draftHideVanillaPlayer;
                    updateToggleLabels();
                }, "保存原版玩家显隐\n装备与伪装分别控制");
        this.hideVanillaEquipment = layerToggle("装备隐藏", this.left + 104, super.height - 27,
                () -> this.runtime.serverOwnModelPresent() || this.draftHideVanillaEquipment, () -> {
                    if (this.runtime.serverOwnModelPresent()) {
                        return;
                    }

                    this.draftHideVanillaEquipment = !this.draftHideVanillaEquipment;
                    updateToggleLabels();
                }, "保存盔甲、披风与鞘翅显隐\n玩家与伪装分别控制");
        this.showSelf = layerToggle("伪装显示", this.left + 152, super.height - 27,
                () -> this.runtime.serverOwnModelShowAllowed() && this.draftShowSelf, () -> {
                    if (!this.runtime.serverOwnModelShowAllowed()) {
                        return;
                    }
                    this.draftShowSelf = !this.draftShowSelf;
                    updateToggleLabels();
                }, "保存本人伪装显隐\n原版玩家与装备分别控制");
        updateToggleLabels();
        this.authorConfig = button("作者配置 / 皮肤…", this.left + 4, this.bottom - 49, this.previewWidth - 8, () -> {
            super.client.setScreen(new ModelConfigScreen(this.runtime, this.modelId, this));
        }, "作者定义的配置与皮肤\n按模型保存");
        this.authorConfig.active = this.loaded != null;
        loadPreview();
    }

    private ButtonWidget button(String label, int x, int y, int w, Runnable action, String tooltip) {
        ButtonWidget button = ButtonWidget
                .builder(Text.literal(super.textRenderer.trimToWidth(label, Math.max(1, w - 8))), b -> action.run())
                .dimensions(x, y, Math.max(20, w), this.controlHeight).build();
        button.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, tooltip));
        return addDrawableChild(button);
    }

    private ButtonWidget layerToggle(String label, int x, int y, BooleanSupplier selected, Runnable action,
            String tooltip) {
        ButtonWidget button = new ButtonWidget(x, y, 44, this.controlHeight, Text.literal(label), b -> action.run(),
                narration -> narration.get()) {
            @Override
            protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
                context.fill(getX(), getY(), getRight(), getBottom(), selected.getAsBoolean() ? -14774017 : -12369342);
                if (super.hovered || isFocused()) {
                    context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
                }

                context.drawCenteredTextWithShadow(ModelSettingsScreen.super.textRenderer,
                        ModelSettingsScreen.super.textRenderer.trimToWidth(getMessage().getString(), getWidth() - 6),
                        getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2,
                        super.active ? 0xfff3f0e0 : 0xff8a929c);
            }
        };
        button.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, tooltip));
        return addDrawableChild(button);
    }

    private TextFieldWidget field(int x, int y, int w, String label, String value, String tooltip) {
        TextFieldWidget field = new TextFieldWidget(super.textRenderer, x, y, Math.max(20, w), this.controlHeight,
                Text.literal(label));
        field.setMaxLength(16);
        field.setText(value);
        field.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, tooltip));
        return addDrawableChild(field);
    }

    private void updateToggleLabels() {
        setButtonText(this.enabled, this.draftEnabled ? "本地：开启" : "本地：关闭");
        this.serverDisguisePresent = this.runtime.serverOwnModelPresent();
        setButtonText(this.hideVanillaPlayer, this.serverDisguisePresent && !this.runtime.serverOwnPlayerHideRuleKnown()
                ? "服务器控制"
                : ((this.serverDisguisePresent ? this.runtime.ownPlayerHideSetting() : this.draftHideVanillaPlayer)
                        ? "玩家隐藏"
                        : "玩家显示"));
        setButtonText(this.hideVanillaEquipment,
                this.serverDisguisePresent || this.draftHideVanillaEquipment ? "装备隐藏" : "装备显示");
        setButtonText(this.showSelf, this.runtime.serverOwnModelShowAllowed() && this.draftShowSelf ? "伪装显示" : "伪装隐藏");
        if (this.hideVanillaPlayer != null) {
            this.hideVanillaPlayer.active = !this.serverDisguisePresent;
            this.hideVanillaPlayer.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width,
                    this.serverDisguisePresent ? (this.runtime.serverOwnPlayerHideRuleKnown()
                            ? "玩家由服务器控制\n当前规则：" + (this.runtime.ownPlayerHideSetting() ? "隐藏" : "显示") + "\n调整服务端外观设置后应用"
                            : "玩家由服务器控制\n未收到人物隐藏规则\n沿用原版服务器显示") : "保存原版玩家显隐\n装备与伪装分别控制"));
        }
        if (this.hideVanillaEquipment != null) {
            this.hideVanillaEquipment.active = !this.serverDisguisePresent;
            this.hideVanillaEquipment.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width,
                    this.serverDisguisePresent ? "服务器伪装期间装备保持隐藏\n伪装模型由独立按钮控制" : "保存盔甲、披风与鞘翅显隐\n玩家与伪装分别控制"));
        }
        if (this.enabled != null) {
            this.enabled.active = this.runtime.canActivateLocalAppearance();
        }

        if (this.savePreview != null) {
            this.savePreview.active = this.runtime.canActivateLocalAppearance();
        }

        if (this.showSelf != null) {
            this.showSelf.active = this.runtime.serverOwnModelShowAllowed();
            this.showSelf.setTooltip(
                    ModelUiTooltip.of(super.textRenderer, super.width, this.showSelf.active ? "保存本人伪装显隐\n原版玩家与装备分别控制"
                            : "伪装显示由服务器控制\n当前未允许本人可见\n本机显示偏好保留\n可在服务端外观设置请求本人可见并应用"));
        }
        setButtonText(this.headdress, defaultHeaddress() ? "红色蝴蝶结 ✓" : "红色蝴蝶结 ×");
        setButtonText(this.skin, defaultBlueTexture() ? "皮肤：蓝色" : "皮肤：默认");
    }

    private void setButtonText(ButtonWidget button, String text) {
        if (button != null) {
            button.setMessage(Text.literal(super.textRenderer.trimToWidth(text, button.getWidth() - 8)));
        }

    }

    private void loadPreview() {
        if (this.loaded != null || this.pending) {
            return;
        }

        this.pending = true;
        long request = ++this.previewRequest;
        String id = this.modelId;
        this.runtime.loadLocalPreview(id).whenComplete((model, error) -> super.client.execute(() -> {
            if (!this.activeView || request != this.previewRequest || !this.modelId.equals(id)) {
                return;
            }

            this.pending = false;
            this.loaded = model;
            this.previewError = error == null ? "" : LocalAppearanceScreen.loadError(error);
            if (this.loaded != null) {
                this.modelLabel = this.loaded.profile().localized(super.client.getLanguageManager().getLanguage(),
                        "metadata.name", this.modelLabel);
                try {
                    this.formVariables = ModelConfigSchema
                            .from(this.loaded.profile(), super.client.getLanguageManager().getLanguage()).variables();
                } catch (RuntimeException invalid) {
                    this.formVariables = Set.of();
                }
            }
            if (this.authorConfig != null) {
                this.authorConfig.active = this.loaded != null;
            }

        }));
    }

    public String selectedModelId() {
        return this.modelId;
    }

    public void setDraftEnabled(boolean value) {
        this.draftEnabled = value;
        updateToggleLabels();
    }

    public void setDraftHideVanillaPlayer(boolean value) {
        if (!this.runtime.serverOwnModelPresent()) {
            this.draftHideVanillaPlayer = value;
        }

        updateToggleLabels();
    }

    public void setDraftHideVanillaEquipment(boolean value) {
        if (!this.runtime.serverOwnModelPresent()) {
            this.draftHideVanillaEquipment = value;
        }

        updateToggleLabels();
    }

    public void setDraftShowSelf(boolean value) {
        this.draftShowSelf = value;
        updateToggleLabels();
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

    public boolean defaultHeaddress() {
        return this.runtime.localModelVariables("openysm_default").getOrDefault("variable.roaming.red_bow_headdress",
                this.runtime.options.defaultHeaddress ? 1d : 0d) > 0;
    }

    public void toggleDefaultHeaddress() {
        if (!this.modelId.equals("openysm_default")) {
            return;
        }

        boolean next = !defaultHeaddress();
        if (!this.runtime.runLocalScript(this.modelId, "v.roaming.red_bow_headdress=" + (next ? "1" : "0") + ";")) {
            this.message = "头饰设置未保存，请等待模型加载完成";
            return;
        }
        this.runtime.options.defaultHeaddress = next;
        this.runtime.options.save();
        updateToggleLabels();
        this.message = "头饰设置已保存";
    }

    public boolean defaultBlueTexture() {
        String texture = this.runtime.options.modelProfile("openysm_default").textureId();
        return texture.isEmpty() ? this.runtime.options.defaultBlueTexture : texture.equals("blue");
    }

    public void toggleDefaultTexture() {
        if (!this.modelId.equals("openysm_default")) {
            return;
        }

        boolean next = !defaultBlueTexture();
        if (!this.runtime.selectLocalTexture(this.modelId, next ? "blue" : "default")) {
            this.message = "皮肤设置未保存";
            return;
        }
        this.runtime.options.defaultBlueTexture = next;
        this.runtime.options.save();
        this.runtime.refreshLocalAppearance();
        this.loaded = null;
        this.pending = false;
        this.previewError = "";
        this.previewRequest++;
        this.preview.clear();
        updateToggleLabels();
        loadPreview();
        this.message = "皮肤设置已保存";
    }

    public void setDraftNumbers(String scaleValue, String x, String y, String z) {
        this.scaleText = scaleValue;
        this.xText = x;
        this.yText = y;
        this.zText = z;
        if (this.scale != null) {
            this.scale.setText(scaleValue);
            this.offsetX.setText(x);
            this.offsetY.setText(y);
            this.offsetZ.setText(z);
        }
    }

    public LocalAppearanceSettings draftSettings() {
        return new LocalAppearanceSettings(this.draftEnabled, this.modelId,
                (float) readNumber(this.scaleText, .05, 8, "缩放"), readNumber(this.xText, -32, 32, "X"),
                readNumber(this.yText, -32, 32, "Y"), readNumber(this.zText, -32, 32, "Z"));
    }

    private static double readNumber(String text, double minimum, double maximum, String label) {
        double value;
        try {
            value = Double.parseDouble(text.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "请输入有效数字");
        }
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + "范围为 " + number(minimum) + " 到 " + number(maximum));
        }

        return value;
    }

    public boolean saveSettings(boolean worldPreview) {
        try {
            if (worldPreview && !this.runtime.canActivateLocalAppearance()) {
                throw new IllegalArgumentException("请先解除服务器伪装，再预览私人模型");
            }

            LocalAppearanceSettings draft = draftSettings();
            if ((draft.enabled() || worldPreview) && this.loaded == null) {
                throw new IllegalArgumentException(
                        this.previewError.isEmpty() ? "请等待模型预览加载完成" : "模型无法使用：" + this.previewError);
            }

            if (worldPreview) {
                draft = new LocalAppearanceSettings(true, draft.modelId(), draft.scale(), draft.offsetX(),
                        draft.offsetY(), draft.offsetZ());
            }

            this.runtime.updateLocalAppearance(draft);
            if (worldPreview) {
                this.runtime.selectLocalModel(draft.modelId());
            }

            this.runtime.options.hideVanillaPlayer = this.draftHideVanillaPlayer;
            this.runtime.options.hideVanillaEquipment = this.draftHideVanillaEquipment;
            this.runtime.options.showSelf = worldPreview || this.draftShowSelf;
            this.runtime.options.save();
            this.draftEnabled = draft.enabled();
            this.draftHideVanillaPlayer = this.runtime.options.hideVanillaPlayer;
            this.draftHideVanillaEquipment = this.runtime.options.hideVanillaEquipment;
            this.draftShowSelf = this.runtime.options.showSelf;
            updateToggleLabels();
            this.message = this.runtime.serverOwnModelPresent() ? "已保存私人设置；请先解除服务器伪装"
                    : (worldPreview ? "已保存；等待私人模型加载" : "已保存私人模型设置");
            if (worldPreview && super.client.world != null) {
                super.client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                super.client.setScreen(null);
            }
            return true;
        } catch (IllegalArgumentException exception) {
            this.message = exception.getMessage();
            return false;
        }
    }

    private void restoreDefaults() {
        if (!this.runtime.options.resetModelProfile(this.modelId)
                || !this.runtime.options.resetModelProfile("openysm_default")) {
            this.message = "重置未保存，请检查配置文件";
            return;
        }
        this.runtime.options.defaultHeaddress = true;
        this.runtime.options.defaultBlueTexture = false;
        LocalAppearanceSettings defaults = LocalAppearanceSettings.defaults();
        this.runtime.updateLocalAppearance(defaults);
        loadDraft(defaults);
        this.loaded = null;
        this.pending = false;
        this.previewError = "";
        this.previewRequest++;
        this.preview.clear();
        this.message = "已恢复默认，本地外观关闭";
        clearAndInit();
    }

    private void clipped(DrawContext context, String text, int x, int y, int w, int color) {
        context.drawTextWithShadow(super.textRenderer, super.textRenderer.trimToWidth(text, Math.max(0, w)), x, y,
                color);
    }

    @Override
    public void tick() {
        if (!this.runtime.canEditLocalAppearance()) {
            super.client.setScreen(new PlayerModelScreen(this.runtime));
            return;
        }
        updateToggleLabels();
        this.ticks++;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.preview.beginFrame(context);
        this.drawnPreviewCount = 0;
        context.fill(0, 0, super.width, super.height, 0xCF10171F);
        context.drawCenteredTextWithShadow(super.textRenderer, super.title, super.width / 2, 7, 0xfff3f6ff);
        if (super.height >= 230) {
            context.drawCenteredTextWithShadow(super.textRenderer, "私人模型 · 参数保存后应用", super.width / 2, 23, 0xffbccce0);
        }

        context.fill(this.left, this.top, this.left + this.previewWidth, this.bottom, 0xE6222730);
        context.fill(this.right, this.top, this.right + this.rightWidth, this.bottom, 0xE6222730);
        clipped(context, this.modelLabel, this.left + 6, this.top + 6, this.previewWidth - 12, 0xfff3f6ff);
        clipped(context, LocalAppearanceScreen.sourceLabel(this.modelId) + " · " + this.modelId, this.left + 6,
                this.top + 18, this.previewWidth - 12, 0xff92b9df);
        if (this.loaded != null) {
            Map<String, Double> parameters = new LinkedHashMap<>();
            Map<String, Double> values = this.runtime.localModelVariables(this.modelId);
            for (String variable : this.formVariables) {
                if (values.containsKey(variable)) {
                    parameters.put(variable, values.get(variable));
                }

            }

            if (this.preview.render(context, this.loaded.model(), this.modelId + ":" + this.loaded.hash(),
                    this.previewX, this.previewY, this.previewW, this.previewH, this.yaw, this.pitch,
                    this.ticks + delta, parameters, this.loaded.previewAnimation(), this.loaded.profile())) {
                this.drawnPreviewCount++;
            }

            clipped(context,
                    this.loaded.model().cubeCount() + " 方块 · " + this.loaded.model().animations().size() + " 动作",
                    this.left + 6, this.bottom - 25, this.previewWidth - 12, 0xffc6d5e7);
        } else {
            clipped(context, this.previewError.isEmpty() ? "正在加载模型…" : this.previewError, this.previewX + 3,
                    this.previewY + this.previewH / 2, this.previewW - 6, 0xffffc685);
        }

        clipped(context, rotationDisabled() ? "作者固定正面视角" : "拖动旋转", this.left + 6, this.bottom - 13,
                this.previewWidth - 12, 0xffa7b5c8);
        if (this.scale != null) {
            clipped(context, "缩放（0.05–8）", this.scale.getX(), this.scale.getY() - this.labelGap, this.scale.getWidth(),
                    0xffc6d5e7);
            clipped(context, "X 位置", this.offsetX.getX(), this.offsetX.getY() - this.labelGap, this.offsetX.getWidth(),
                    0xffc6d5e7);
            clipped(context, "Y（上为正）", this.offsetY.getX(), this.offsetY.getY() - this.labelGap,
                    this.offsetY.getWidth(), 0xffc6d5e7);
            clipped(context, "Z 位置", this.offsetZ.getX(), this.offsetZ.getY() - this.labelGap, this.offsetZ.getWidth(),
                    0xffc6d5e7);
        }
        clipped(context, this.message.isEmpty() ? this.runtime.localAppearanceStatus() : this.message, this.left + 205,
                super.height - 21, this.panelWidth - 205, 0xffffd589);
        super.render(context, mouseX, mouseY, delta);
    }

    private boolean insidePreview(double x, double y) {
        return x >= this.previewX && x < this.previewX + this.previewW && y >= this.previewY
                && y < this.previewY + this.previewH;
    }

    private boolean rotationDisabled() {
        return this.loaded != null && ModelPreview.rotationDisabled(this.loaded.profile());
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
    public void removed() {
        this.activeView = false;
        this.rotating = false;
        this.pending = false;
        this.previewRequest++;
        this.loaded = null;
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
