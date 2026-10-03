package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientOptions;
import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import java.math.BigDecimal;
import java.util.*;
import net.minecraft.text.Text;

/** Private, author-defined controls and skins. This page never sends server requests. */
public final class ModelConfigScreen extends Screen {
    private final ClientRuntime runtime;
    private final String modelId;
    private final Screen parent;
    private final Map<String, String> scripts = new LinkedHashMap<>();
    private final Map<String, Integer> radios = new LinkedHashMap<>();
    private final Map<String, Double> draftValues = new LinkedHashMap<>();
    private LocalModelLibrary.Loaded loaded;
    private ModelConfigSchema schema;
    private String groupId, message = "";
    private int page, groupPage, pageSize, left, panelWidth, listX, listWidth, rowsTop;
    private boolean activeView, pending;
    private long request;
    private List<ModelConfigSchema.Form> visible = List.of();

    public ModelConfigScreen(ClientRuntime runtime, String modelId, String groupId, Screen parent) {
        super(Text.literal("模型配置 / 皮肤")); this.runtime = runtime; this.modelId = modelId;
        this.groupId = groupId == null ? "" : groupId; this.parent = parent;
    }
    public ModelConfigScreen(ClientRuntime runtime, String modelId, Screen parent) { this(runtime, modelId, "", parent); }
    private String locale() { return client == null ? "zh_cn" : client.getLanguageManager().getLanguage(); }
    @Override protected void init() {
        activeView = true; panelWidth = Math.min(720, width - 20); left = (width - panelWidth) / 2;
        int groupWidth = Math.max(65, Math.min(150, panelWidth / 4));
        listX = left + groupWidth + 8; listWidth = panelWidth - groupWidth - 8; rowsTop = 78;
        pageSize = Math.max(1, (height - 150) / 48);
        if (schema != null && !schema.groups().isEmpty()) {
            if (schema.group(groupId).isEmpty()) groupId = schema.groups().getFirst().id();
            int groupSize = Math.max(1, (height - 154) / 24);
            groupPage = Math.max(0, Math.min(groupPage, (schema.groups().size() - 1) / groupSize));
            for (int index = groupPage * groupSize; index < Math.min(schema.groups().size(), (groupPage + 1) * groupSize); index++) {
                var group = schema.groups().get(index);
                button((group.id().equals(groupId) ? "✓ " : "") + group.name(), left, rowsTop + (index % groupSize) * 24, groupWidth, () -> {
                    groupId = group.id(); page = 0; clearAndInit();
                }, group.description());
            }
            int navWidth = (groupWidth - 4) / 2;
            button("‹", left, height - 71, navWidth, () -> { groupPage--; clearAndInit(); }, "上一组").active = groupPage > 0;
            button("›", left + navWidth + 4, height - 71, navWidth, () -> { groupPage++; clearAndInit(); }, "下一组").active = (groupPage + 1) * groupSize < schema.groups().size();
            var forms = schema.group(groupId).orElseThrow().forms();
            page = Math.max(0, Math.min(page, Math.max(0, (forms.size() - 1) / pageSize)));
            visible = forms.stream().skip((long) page * pageSize).limit(pageSize).toList();
            for (int slot = 0; slot < visible.size(); slot++) addForm(visible.get(slot), rowsTop + slot * 48);
            int nav = Math.max(40, (listWidth - 12) / 3);
            button("上一页", listX, height - 71, nav, () -> { page--; clearAndInit(); }, "配置上一页").active = page > 0;
            button("下一页", listX + nav + 6, height - 71, nav, () -> { page++; clearAndInit(); }, "配置下一页").active = (page + 1) * pageSize < forms.size();
            button("重置模型配置", listX + 2 * (nav + 6), height - 71, nav, this::reset, "恢复此模型的作者默认变量和皮肤，不改变模型启用、缩放或位置");
        } else visible = List.of();
        String texture = loaded == null ? "加载皮肤…" : "皮肤：" + loaded.profile().selectedTexture();
        var skin = button(texture, left, 48, panelWidth, this::nextTexture, "切换此模型的原始皮肤并立即保存；仅自己可见");
        skin.active = loaded != null && loaded.profile().textures().size() > 1;
        button("返回", left, height - 27, 58, this::close, "未应用的模型参数不保存");
        button("应用并保存", left + panelWidth - 110, height - 27, 110, this::applyChanges, "执行作者配置脚本并保存；本地外观仅自己可见").active = schema != null;
        load();
    }
    private ButtonWidget button(String label, int x, int y, int w, Runnable callback, String description) {
        var button = ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label, Math.max(1, w - 8))), b -> callback.run())
                .dimensions(x, y, Math.max(20, w), 20).build();
        if (!description.isEmpty()) button.setTooltip(Tooltip.of(Text.literal(description)));
        return addDrawableChild(button);
    }
    private void load() {
        if (loaded != null || pending) return;
        pending = true; long token = ++request;
        runtime.loadLocalPreview(modelId).whenComplete((value, error) -> client.execute(() -> {
            if (!activeView || request != token) return;
            pending = false;
            if (error != null) { message = LocalAppearanceScreen.loadError(error); return; }
            try {
                loaded = value; schema = ModelConfigSchema.from(value.profile(), locale());
                if (scripts.isEmpty()) {
                    radios.clear(); radios.putAll(runtime.options.modelProfile(modelId).radioSelections());
                    draftValues.clear(); draftValues.putAll(runtime.localModelVariables(modelId));
                }
                clearAndInit();
            } catch (RuntimeException invalid) { loaded = null; message = "模型配置无法使用：" + invalid.getMessage(); }
        }));
    }
    private double read(ModelConfigSchema.Form form) {
        try { return form.read(draftValues); } catch (RuntimeException invalid) { return 0; }
    }
    private void addForm(ModelConfigSchema.Form form, int y) {
        int controlY = y + 13;
        switch (form.kind()) {
            case CHECKBOX -> button(read(form) > 0 ? "✓ 开启" : "× 关闭", listX, controlY, listWidth, () -> {
                boolean selected = read(form) <= 0; stage(form, form.checkboxScript(selected)); clearAndInit();
            }, form.title() + " · " + form.description());
            case RANGE -> {
                int small = Math.max(22, Math.min(34, listWidth / 7));
                button("−", listX, controlY, small, () -> adjust(form, -1), form.description());
                var field = new TextFieldWidget(textRenderer, listX + small + 4, controlY, Math.max(20, listWidth - (small + 4) * 2), 20, Text.literal(form.title()));
                field.setMaxLength(24); field.setText(number(read(form))); field.setTooltip(Tooltip.of(Text.literal(form.description()
                        + " [" + number(form.minimum()) + " … " + number(form.maximum()) + "] 步长 " + number(form.step()))));
                field.setChangedListener(text -> {
                    try { stage(form, form.rangeScript(Double.parseDouble(text.trim()))); message = "参数待应用；仅自己可见"; }
                    catch (IllegalArgumentException invalid) { scripts.put(form.key(), ""); message = "请输入范围内的有效数字"; }
                }); addDrawableChild(field);
                button("+", listX + listWidth - small, controlY, small, () -> adjust(form, 1), form.description());
            }
            case RADIO -> {
                int selected = Math.max(0, Math.min(form.choices().size() - 1, radios.getOrDefault(form.key(), (int) Math.round(read(form)))));
                button(form.choices().get(selected).label() + "  ›", listX, controlY, listWidth, () -> choose(form, (selected + 1) % form.choices().size()),
                        form.title() + " · " + form.description() + " · 点击依次切换 " + form.choices().size() + " 个选项");
            }
        }
    }
    private void adjust(ModelConfigSchema.Form form, int direction) {
        double step = form.step() > 0 ? form.step() : Math.max(.01, (form.maximum() - form.minimum()) / 100);
        stage(form, form.rangeScript(read(form) + direction * step)); clearAndInit();
    }
    private void choose(ModelConfigSchema.Form form, int index) {
        if (!radios.containsKey(form.key()) && radios.size() >= ClientOptions.MAX_RADIO_SELECTIONS) {
            message = "此模型已达到单选配置保存上限，请先重置不再使用的配置"; return;
        }
        radios.put(form.key(), index); stage(form, form.radioScript(index)); clearAndInit();
    }
    private void stage(ModelConfigSchema.Form form, String script) {
        try {
            var context = new com.simmc.meplayeractions.expression.Molang.Context(); draftValues.forEach(context::set);
            com.simmc.meplayeractions.expression.Molang.compile(script).evaluate(context);
            for (String key : form.variables()) if (context.has(key)) draftValues.put(key, context.get(key));
        } catch (RuntimeException unavailableContext) {
            // A script using live player queries is executed by Runtime on apply, never by an invented GUI binding.
        }
        scripts.remove(form.key()); scripts.put(form.key(), script); message = "参数待应用；仅自己可见";
    }
    public boolean applyChanges() {
        if (scripts.values().stream().anyMatch(String::isEmpty)) { message = "请先修正无效的数字"; return false; }
        if (scripts.isEmpty()) { message = "没有待应用的修改"; return true; }
        long characters = scripts.values().stream().mapToLong(String::length).sum();
        if (characters > 32_768) { message = "本次配置脚本过长，请分批应用"; return false; }
        if (!runtime.runLocalScripts(modelId, List.copyOf(scripts.values()), Map.copyOf(radios))) {
            message = "配置未应用，原设置已保留；请检查模型配置或保存空间"; return false;
        }
        scripts.clear(); draftValues.clear(); draftValues.putAll(runtime.localModelVariables(modelId));
        message = "已保存并应用；仅自己可见"; clearAndInit(); return true;
    }
    private void nextTexture() {
        if (loaded == null || loaded.profile().textures().isEmpty()) return;
        var textures = loaded.profile().textures(); int index = 0;
        for (int i = 0; i < textures.size(); i++) if (textures.get(i).id().equals(loaded.profile().selectedTexture())) index = i;
        if (runtime.selectLocalTexture(modelId, textures.get((index + 1) % textures.size()).id())) {
            loaded = null; pending = false; request++; message = "皮肤已保存；仅自己可见"; clearAndInit();
        } else message = "皮肤未保存，请检查模型配置或保存空间";
    }
    private void reset() {
        if (!runtime.canEditLocalAppearance()) return;
        if (!runtime.options.resetModelProfile(modelId)) { message = "重置失败，原设置已保留"; return; }
        scripts.clear(); radios.clear(); draftValues.clear();
        if (modelId.equals("openysm_default")) {
            runtime.options.defaultHeaddress = true; runtime.options.defaultBlueTexture = false; runtime.options.save();
        }
        runtime.refreshLocalAppearance();
        loaded = null; pending = false; request++; message = "已恢复此模型默认配置"; clearAndInit();
    }
    public Map<String, Object> diagnostics() {
        return Map.of("modelId", modelId, "group", groupId, "page", page, "groupPage", groupPage,
                "forms", visible.stream().map(form -> Map.of("key", form.key(), "kind", form.kind().name(), "value", read(form))).toList(),
                "pendingScripts", scripts.size(), "texture", loaded == null ? "" : loaded.profile().selectedTexture());
    }
    @Override public void tick() {
        if (!runtime.canEditLocalAppearance()) client.setScreen(new PlayerModelScreen(runtime));
    }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && schema != null) { page += vertical > 0 ? -1 : 1; clearAndInit(); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xE0101823);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 9, 0xffffffff);
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth("仅自己可见 · " + modelId, Math.max(1, width - 20)), width / 2, 26, 0xffa9c8dc);
        context.fill(left, rowsTop - 4, left + panelWidth, height - 78, 0xA025303C);
        for (int slot = 0; slot < visible.size(); slot++) {
            var form = visible.get(slot);
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(form.title(), Math.max(1, listWidth)), listX, rowsTop + slot * 48, 0xffe3edf8);
        }
        if (schema != null && schema.groups().isEmpty()) context.drawCenteredTextWithShadow(textRenderer, "此模型未定义额外配置", width / 2, rowsTop + 12, 0xffc6d5ec);
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(message.isEmpty() ? pending ? "正在加载作者配置…" : "修改后点击应用并保存" : message, Math.max(1, width - 20)), width / 2, height - 44, 0xffffd589);
        super.render(context, mouseX, mouseY, delta);
    }
    @Override public void removed() { activeView = false; request++; pending = false; }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
