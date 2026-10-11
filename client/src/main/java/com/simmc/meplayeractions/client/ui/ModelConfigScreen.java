package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientOptions;
import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.expression.Molang;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.*;

/** Author-ordered model controls; edits are staged and atomically applied by the runtime engine. */
public final class ModelConfigScreen extends Screen {
    private final ClientRuntime runtime;
    private final String modelId;
    private final Screen parent;
    private final Map<String, String> scripts = new LinkedHashMap<>();
    private final Map<String, Integer> radios = new LinkedHashMap<>();
    private final Map<String, Double> draftValues = new LinkedHashMap<>();
    private final Map<String, Double> readValues = new HashMap<>();
    private final List<ClickableWidget> formWidgets = new ArrayList<>();
    private final List<FormRow> formRows = new ArrayList<>();
    private LocalModelLibrary.Loaded loaded;
    private ModelConfigSchema schema;
    private String groupId, message = "";
    private int groupPage, left, panelWidth, listX, listWidth, rowsTop, rowsBottom, formScroll, contentHeight;
    private boolean activeView, pending;
    private long request;
    private ButtonWidget up, down;

    private record FormRow(ModelConfigSchema.Form form, int top, int height) {
    }

    public ModelConfigScreen(ClientRuntime runtime, String modelId, String groupId, Screen parent) {
        super(Text.literal("模型配置 / 皮肤"));
        this.runtime = runtime;
        this.modelId = modelId;
        this.groupId = groupId == null ? "" : groupId;
        this.parent = parent;
    }

    public ModelConfigScreen(ClientRuntime runtime, String modelId, Screen parent) {
        this(runtime, modelId, "", parent);
    }

    private String locale() {
        return super.client == null ? "zh_cn" : super.client.getLanguageManager().getLanguage();
    }

    @Override
    protected void init() {
        this.activeView = true;
        this.panelWidth = Math.max(200, Math.min(720, super.width - 20));
        this.left = (super.width - this.panelWidth) / 2;
        int groupWidth = Math.max(65, Math.min(150, this.panelWidth / 4));
        this.listX = this.left + groupWidth + 8;
        this.listWidth = this.panelWidth - groupWidth - 8;
        this.rowsTop = 78;
        this.rowsBottom = Math.max(this.rowsTop + 22, super.height - 80);
        this.formWidgets.clear();
        this.formRows.clear();
        this.readValues.clear();
        this.contentHeight = 0;
        this.up = null;
        this.down = null;
        if (this.schema != null && !this.schema.groups().isEmpty()) {
            if (this.schema.group(this.groupId) == null) {
                this.groupId = this.schema.groups().getFirst().id();
            }

            int groupSize = Math.max(1, (this.rowsBottom - this.rowsTop) / 24);
            this.groupPage = Math.max(0, Math.min(this.groupPage, (this.schema.groups().size() - 1) / groupSize));
            for (int index = this.groupPage * groupSize; index < Math.min(this.schema.groups().size(),
                    (this.groupPage + 1) * groupSize); index++) {
                ModelConfigSchema.Group group = this.schema.groups().get(index);
                button((group.id().equals(this.groupId) ? "✓ " : "") + group.name(), this.left,
                        this.rowsTop + (index % groupSize) * 24, groupWidth, () -> {
                            this.groupId = group.id();
                            this.formScroll = 0;
                            clearAndInit();
                        }, group.description());
            }
            int navWidth = (groupWidth - 4) / 2;
            button("‹", this.left, super.height - 71, navWidth, () -> {
                this.groupPage--;
                clearAndInit();
            }, "上一组").active = this.groupPage > 0;
            button("›", this.left + navWidth + 4, super.height - 71, navWidth, () -> {
                this.groupPage++;
                clearAndInit();
            }, "下一组").active = (this.groupPage + 1) * groupSize < this.schema.groups().size();
            for (ModelConfigSchema.Form form : this.schema.group(this.groupId).forms()) {
                int start = this.contentHeight;
                this.contentHeight += addForm(form, this.rowsTop + start);
                this.formRows.add(new FormRow(form, start, this.contentHeight - start));
            }
            int nav = Math.max(40, (this.listWidth - 12) / 3);
            this.up = button("向上", this.listX, super.height - 71, nav,
                    () -> scroll(this.formScroll - Math.max(22, viewportHeight() - 22)), "向上浏览作者配置");
            this.down = button("向下", this.listX + nav + 6, super.height - 71, nav,
                    () -> scroll(this.formScroll + Math.max(22, viewportHeight() - 22)), "向下浏览作者配置");
            button("重置配置", this.listX + 2 * (nav + 6), super.height - 71, nav, this::reset,
                    "恢复此模型的作者默认变量和皮肤，不改变模型启用、缩放或位置");
            int saved = this.formScroll;
            this.formScroll = 0;
            scroll(saved);
        }
        ButtonWidget skin = button(this.loaded == null ? "加载皮肤…" : "皮肤：" + this.loaded.profile().selectedTexture(),
                this.left, 48, this.panelWidth, this::nextTexture, "切换作者提供的原始皮肤并立即保存");
        skin.active = this.loaded != null && this.loaded.profile().textures().size() > 1;
        button("返回", this.left, super.height - 27, 58, this::close, "未应用的模型参数不保存");
        button("应用并保存", this.left + this.panelWidth - 110, super.height - 27, 110, this::applyChanges,
                "执行完整作者配置脚本并保存").active = this.schema != null;
        load();
    }

    private ButtonWidget button(String label, int x, int y, int w, Runnable callback, String description) {
        ButtonWidget button = ButtonWidget
                .builder(Text.literal(super.textRenderer.trimToWidth(label, Math.max(1, w - 8))), b -> callback.run())
                .dimensions(x, y, Math.max(20, w), 20).build();
        if (!description.isEmpty()) {
            button.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, description));
        }

        return addDrawableChild(button);
    }

    private void load() {
        if (this.loaded != null || this.pending) {
            return;
        }

        this.pending = true;
        long token = ++this.request;
        this.runtime.loadLocalPreview(this.modelId).whenComplete((value, error) -> super.client.execute(() -> {
            if (!this.activeView || this.request != token) {
                return;
            }

            this.pending = false;
            if (error != null) {
                this.message = LocalAppearanceScreen.loadError(error);
                return;
            }
            try {
                this.loaded = value;
                this.schema = ModelConfigSchema.from(value.profile(), locale());
                if (this.scripts.isEmpty()) {
                    this.radios.clear();
                    this.radios.putAll(this.runtime.options.modelProfile(this.modelId).radioSelections());
                    this.draftValues.clear();
                    this.draftValues.putAll(this.runtime.localModelVariables(this.modelId));
                }
                clearAndInit();
            } catch (RuntimeException invalid) {
                this.loaded = null;
                this.message = "模型配置无法使用：" + invalid.getMessage();
            }
        }));
    }

    private double read(ModelConfigSchema.Form form) {
        if (this.readValues.containsKey(form.key())) {
            return this.readValues.get(form.key());
        }

        try {
            double value = this.runtime.readLocalModelExpression(this.modelId, form.expression(), this.draftValues);
            value = Double.isFinite(value) ? value : 0;
            this.readValues.put(form.key(), value);
            return value;
        } catch (RuntimeException invalid) {
            this.readValues.put(form.key(), 0d);
            return 0;
        }
    }

    private int selected(ModelConfigSchema.Form form) {
        return this.scripts.containsKey(form.key()) && this.radios.containsKey(form.key()) ? this.radios.get(form.key())
                : form.selectedIndex(read(form));
    }

    private <T extends ClickableWidget> T formWidget(T widget, String description) {
        if (!description.isEmpty()) {
            widget.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, description));
        }

        this.formWidgets.add(widget);
        addSelectableChild(widget);
        return widget;
    }

    private int addForm(ModelConfigSchema.Form form, int y) {
        switch (form.kind()) {
        case CHECKBOX -> {
            formWidget(new AuthorFormWidgets.Check(super.textRenderer, this.listX, y, this.listWidth, 20, form.title(),
                    () -> read(form) > 0, () -> {
                        stage(form, form.checkboxScript(read(form) <= 0));
                        clearAndInit();
                    }, this::insideViewport), form.description());
            return 24;
        }
        case RANGE -> {
            int entryWidth = Math.min(86, Math.max(52, this.listWidth / 4));
            TextFieldWidget[] input = new TextFieldWidget[1];
            AuthorFormWidgets.Range slider = formWidget(new AuthorFormWidgets.Range(super.textRenderer, this.listX, y,
                    this.listWidth - entryWidth - 4, 20, form, read(form), value -> {
                        stage(form, form.rangeScript(value));
                        if (input[0] != null) {
                            input[0].setText(AuthorFormWidgets.number(value));
                        }

                        return true;
                    }, this::insideViewport), form.description());
            TextFieldWidget field = new TextFieldWidget(super.textRenderer, this.listX + this.listWidth - entryWidth, y,
                    entryWidth, 20, Text.literal(form.title())) {
                @Override
                public boolean mouseClicked(Click click, boolean doubled) {
                    return insideViewport(click.x(), click.y()) && super.mouseClicked(click, doubled);
                }
            };
            field.setMaxLength(24);
            field.setText(AuthorFormWidgets.number(read(form)));
            input[0] = field;
            field.setChangedListener(text -> {
                try {
                    double value = form.snap(Double.parseDouble(text.strip()));
                    stage(form, form.rangeScript(value));
                    slider.syncValue(value);
                } catch (IllegalArgumentException invalid) {
                    this.scripts.put(form.key(), "");
                    this.message = "请输入有效数字";
                }
            });
            formWidget(field, form.description() + " [" + AuthorFormWidgets.number(form.minimum()) + " … "
                    + AuthorFormWidgets.number(form.maximum()) + "] 步长 " + AuthorFormWidgets.number(form.step()));
            return 24;
        }
        case RADIO -> {
            int widest = 0;
            for (ModelConfigSchema.Radio choice : form.choices()) {
                widest = Math.max(widest, super.textRenderer.getWidth(choice.label()));
            }

            int columns = AuthorFormLayout.radioColumns(this.listWidth, widest, form.choices().size()),
                    cellWidth = Math.max(20, this.listWidth / columns);
            for (int index = 0; index < form.choices().size(); index++) {
                int selectedIndex = index;
                formWidget(new AuthorFormWidgets.Check(super.textRenderer, this.listX + index % columns * cellWidth,
                        y + 14 + index / columns * 22, cellWidth - 2, 20, form.choices().get(index).label(),
                        () -> selected(form) == selectedIndex, () -> choose(form, selectedIndex), this::insideViewport),
                        form.description());
            }
            return 18 + AuthorFormLayout.radioRows(form.choices().size(), columns) * 22;
        }
        }
        throw new IllegalStateException("Unknown author form");
    }

    private void choose(ModelConfigSchema.Form form, int index) {
        if (!this.radios.containsKey(form.key()) && this.radios.size() >= ClientOptions.MAX_RADIO_SELECTIONS) {
            this.message = "单选配置保存数量已达上限，请先重置不再使用的配置";
            return;
        }
        this.radios.put(form.key(), index);
        stage(form, form.radioScript(index));
        clearAndInit();
    }

    private void stage(ModelConfigSchema.Form form, String script) {
        try {
            Molang.Context context = new Molang.Context();
            this.draftValues.forEach(context::set);
            Molang.Program program = Molang.compile(script);
            if (program.references().stream()
                    .anyMatch(name -> name.startsWith("query.") || name.startsWith("context."))) {
                throw new IllegalStateException("Actual player context required");
            }

            program.evaluate(context);
            for (String key : form.variables()) {
                if (context.has(key)) {
                    this.draftValues.put(key, context.get(key));
                }

            }

        } catch (RuntimeException liveContextRequired) {
            // The complete script is evaluated against the actual player only when explicitly applied.
        }
        this.scripts.remove(form.key());
        this.scripts.put(form.key(), script);
        this.message = "参数待应用";
        this.readValues.clear();
    }

    public boolean applyChanges() {
        if (this.scripts.values().stream().anyMatch(String::isEmpty)) {
            this.message = "请先修正无效的数字";
            return false;
        }
        if (this.scripts.isEmpty()) {
            this.message = "没有待应用的修改";
            return true;
        }
        if (this.scripts.values().stream().mapToLong(String::length).sum() > 32768) {
            this.message = "本次配置脚本过长，请分批应用";
            return false;
        }
        if (!this.runtime.runLocalScripts(this.modelId, List.copyOf(this.scripts.values()), Map.copyOf(this.radios))) {
            this.message = "配置未应用，原设置已保留；请检查作者脚本或保存空间";
            return false;
        }
        this.scripts.clear();
        this.draftValues.clear();
        this.draftValues.putAll(this.runtime.localModelVariables(this.modelId));
        this.message = "已保存并应用";
        clearAndInit();
        return true;
    }

    private void nextTexture() {
        if (this.loaded == null || this.loaded.profile().textures().isEmpty()) {
            return;
        }

        List<YsmModelProfile.TextureChoice> textures = this.loaded.profile().textures();
        int index = 0;
        for (int i = 0; i < textures.size(); i++) {
            if (textures.get(i).id().equals(this.loaded.profile().selectedTexture())) {
                index = i;
            }

        }

        if (this.runtime.selectLocalTexture(this.modelId, textures.get((index + 1) % textures.size()).id())) {
            this.loaded = null;
            this.pending = false;
            this.request++;
            this.message = "皮肤已保存";
            clearAndInit();
        } else {
            this.message = "皮肤未保存，请检查模型配置或保存空间";
        }

    }

    private void reset() {
        if (!this.runtime.canEditLocalAppearance()) {
            return;
        }

        if (!this.runtime.options.resetModelProfile(this.modelId)) {
            this.message = "重置失败，原设置已保留";
            return;
        }
        this.scripts.clear();
        this.radios.clear();
        this.draftValues.clear();
        this.formScroll = 0;
        if (this.modelId.equals("openysm_default")) {
            this.runtime.options.defaultHeaddress = true;
            this.runtime.options.defaultBlueTexture = false;
            this.runtime.options.save();
        }
        this.runtime.refreshLocalAppearance();
        this.loaded = null;
        this.pending = false;
        this.request++;
        this.message = "已恢复此模型默认配置";
        clearAndInit();
    }

    private int viewportHeight() {
        return Math.max(1, this.rowsBottom - this.rowsTop);
    }

    private boolean insideViewport(double x, double y) {
        return x >= this.listX && x < this.listX + this.listWidth && y >= this.rowsTop && y < this.rowsBottom;
    }

    private void scroll(int requested) {
        int next = AuthorFormLayout.clampScroll(requested, this.contentHeight, viewportHeight()),
                shift = this.formScroll - next;
        this.formScroll = next;
        for (ClickableWidget widget : this.formWidgets) {
            widget.setY(widget.getY() + shift);
            widget.visible = widget.getBottom() > this.rowsTop && widget.getY() < this.rowsBottom;
            widget.active = widget.visible && (!(widget instanceof AuthorFormWidgets.Range range)
                    || range.form.maximum() != range.form.minimum());
            if (!widget.visible && getFocused() == widget) {
                setFocused(null);
                setDragging(false);
            }
        }
        if (this.up != null) {
            this.up.active = this.formScroll > 0;
        }

        if (this.down != null) {
            this.down.active = this.formScroll < Math.max(0, this.contentHeight - viewportHeight());
        }

    }

    public Map<String, Object> diagnostics() {
        return Map.of("modelId", this.modelId, "group", this.groupId, "page", this.formScroll / viewportHeight(),
                "groupPage", this.groupPage, "forms",
                this.formRows.stream().filter(
                        row -> row.top + row.height > this.formScroll && row.top < this.formScroll + viewportHeight())
                        .map(row -> Map.of("key", row.form.key(), "kind", row.form.kind().name(), "value",
                                read(row.form)))
                        .toList(),
                "pendingScripts", this.scripts.size(), "texture",
                this.loaded == null ? "" : this.loaded.profile().selectedTexture(), "scroll", this.formScroll,
                "contentHeight", this.contentHeight);
    }

    @Override
    public void tick() {
        if (!this.runtime.canEditLocalAppearance()) {
            super.client.setScreen(new PlayerModelScreen(this.runtime));
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && x >= this.listX && this.schema != null) {
            scroll(this.formScroll + (vertical > 0 ? -22 : 22));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, super.width, super.height, 0xE0101823);
        context.drawCenteredTextWithShadow(super.textRenderer, super.title, super.width / 2, 9, 0xffffffff);
        context.drawCenteredTextWithShadow(super.textRenderer,
                super.textRenderer.trimToWidth("按模型保存 · " + this.modelId, Math.max(1, super.width - 20)),
                super.width / 2, 26, 0xffa9c8dc);
        context.fill(this.left, this.rowsTop - 4, this.left + this.panelWidth, this.rowsBottom + 2, 0xA025303C);
        super.render(context, mouseX, mouseY, delta);
        context.enableScissor(this.listX, this.rowsTop, this.listX + this.listWidth, this.rowsBottom);
        for (ModelConfigScreen.FormRow row : this.formRows) {
            if (row.form.kind() == ModelConfigSchema.Kind.RADIO) {
                context.drawTextWithShadow(super.textRenderer,
                        super.textRenderer.trimToWidth(row.form.title(), Math.max(1, this.listWidth)), this.listX,
                        this.rowsTop + row.top - this.formScroll + 2, 0xffe3edf8);
            }

        }

        for (ClickableWidget widget : this.formWidgets) {
            widget.render(context, mouseX, insideViewport(mouseX, mouseY) ? mouseY : -1000, delta);
        }

        context.disableScissor();
        if (this.schema != null && this.schema.groups().isEmpty()) {
            context.drawCenteredTextWithShadow(super.textRenderer, "此模型未定义额外配置", super.width / 2, this.rowsTop + 12,
                    0xffc6d5ec);
        }

        context.drawCenteredTextWithShadow(super.textRenderer,
                super.textRenderer.trimToWidth(
                        this.message.isEmpty() ? (this.pending ? "正在加载作者配置…" : "修改后点击应用并保存；滚轮浏览配置") : this.message,
                        Math.max(1, super.width - 20)),
                super.width / 2, super.height - 44, 0xffffd589);
    }

    @Override
    public void removed() {
        this.activeView = false;
        this.request++;
        this.pending = false;
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
