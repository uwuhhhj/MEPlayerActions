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
import net.minecraft.util.Util;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

/** Browsing is a GUI-only draft; only the explicit use/settings buttons apply an appearance. */
public final class LocalAppearanceScreen extends Screen {
    private static final int MAX_LOADS = 3;
    private final ClientRuntime runtime;
    private final Screen parent;
    private final ModelPreview preview = new ModelPreview();
    private final Map<String, PreviewEntry> previews = new HashMap<>();
    private final List<ButtonWidget> gridWidgets = new ArrayList<>();
    private List<ClientRuntime.Action> models = List.of(), filtered = List.of(), visible = List.of();
    private String selectedId, query = "", message = "";
    private boolean favoritesOnly, activeView, rotating;
    private int sourceFilter, page, columns, rows, pageSize, inFlight, drawnPreviewCount;
    private int left, top, bottom, panelWidth, previewWidth, right, rightWidth, gridTop, gridBottom;
    private int previewX, previewY, previewW, previewH;
    private long generation;
    private float yaw = 0, pitch = -8, ticks;
    private TextFieldWidget search;
    private ButtonWidget use, settings, favorite;

    public LocalAppearanceScreen(ClientRuntime runtime) { this(runtime, null); }
    public LocalAppearanceScreen(ClientRuntime runtime, Screen parent) {
        super(Text.literal("本地模型图库"));
        this.runtime = runtime;
        this.parent = parent;
        selectedId = runtime.localAppearance().modelId();
    }

    @Override protected void init() {
        activeView = true;
        panelWidth = Math.max(220, Math.min(1040, width - 16));
        left = (width - panelWidth) / 2;
        top = height < 230 ? 29 : 38;
        bottom = height - 34;
        previewWidth = Math.max(88, Math.min(290, panelWidth * 31 / 100));
        right = left + previewWidth + 6;
        rightWidth = panelWidth - previewWidth - 6;
        previewX = left + 4; previewY = top + 25;
        previewW = previewWidth - 8; previewH = Math.max(30, bottom - previewY - 63);
        gridTop = top + 54; gridBottom = bottom - 26;
        columns = Math.max(1, Math.min(5, (rightWidth - 10) / 68));
        rows = Math.max(1, Math.min(2, (gridBottom - gridTop) / 78));
        pageSize = columns * rows;
        if (models.isEmpty()) refreshModels(false);

        search = new TextFieldWidget(textRenderer, right + 5, top + 5, Math.max(35, rightWidth - 81), 20, Text.literal("搜索模型"));
        search.setMaxLength(80); search.setPlaceholder(Text.literal("搜索名称 / ID")); search.setText(query);
        search.setChangedListener(value -> { query = value; page = 0; rebuildGrid(); });
        addDrawableChild(search);
        button(favoritesOnly ? "★ 收藏" : "☆ 收藏", right + rightWidth - 72, top + 5, 42, () -> {
            favoritesOnly = !favoritesOnly; page = 0; clearAndInit();
        }, "仅显示已收藏的模型；每张卡片右上角可收藏");
        button(runtime.options.showModelIds ? "ID ✓" : "ID", right + rightWidth - 27, top + 5, 22, () -> {
            runtime.options.showModelIds = !runtime.options.showModelIds; runtime.options.save(); clearAndInit();
        }, "优先在卡片上显示模型 ID");

        int toolWidth = (rightWidth - 19) / 4;
        button(new String[]{"全部来源", "内置", "本地 YSM", "本地 BB"}[sourceFilter], right + 5, top + 29, toolWidth,
                () -> { sourceFilter = (sourceFilter + 1) % 4; page = 0; clearAndInit(); }, "点击切换模型来源筛选");
        button("文件夹", right + 8 + toolWidth, top + 29, toolWidth, () -> {
            Util.getOperatingSystem().open(runtime.localModelDirectory());
            message = "放入模型后点击刷新";
        }, "打开本地模型目录");
        button("刷新", right + 11 + toolWidth * 2, top + 29, toolWidth, () -> {
            refreshModels(true); page = 0; rebuildGrid(); message = "已刷新本地模型";
        }, "重新扫描文件和模型预览；不会改变已使用的外观");
        button("导入说明", right + 14 + toolWidth * 3, top + 29, toolWidth,
                () -> client.setScreen(new ImportHelpScreen(this)), "支持安全 YSM 文件夹和独立 BBModel 文件");

        use = button("使用模型", left + 5, bottom - 44, previewWidth - 10, this::useSelectedModel,
                "将当前浏览的模型用于自己；其他玩家看不到本地选择");
        int half = (previewWidth - 13) / 2;
        settings = button(half < 65 ? "设置" : "详情 / 设置", left + 5, bottom - 21, half,
                () -> client.setScreen(new ModelSettingsScreen(runtime, selectedId, this)), "查看模型信息，设置缩放、XYZ 位置与有效模型参数");
        favorite = button("☆ 收藏", left + 8 + half, bottom - 21, half, this::toggleSelectedFavorite, "收藏或取消收藏当前浏览模型");
        button("返回", left, height - 27, 52, this::close, "返回上一页");
        button("恢复默认", left + 56, height - 27, 65, () -> {
            runtime.options.defaultHeaddress = true; runtime.options.defaultBlueTexture = false;
            var defaults = LocalAppearanceSettings.defaults(); runtime.updateLocalAppearance(defaults);
            refreshModels(true); selectedId = defaults.modelId(); message = "已恢复默认，本地外观关闭"; rebuildGrid();
        }, "恢复默认模型、缩放和位置，并关闭本地外观");
        rebuildGrid();
    }

    private ButtonWidget button(String label, int x, int y, int w, Runnable action, String tooltip) {
        var button = ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label, Math.max(1, w - 8))), b -> action.run()).dimensions(x, y, Math.max(20, w), 20).build();
        button.setTooltip(Tooltip.of(Text.literal(tooltip)));
        return addDrawableChild(button);
    }

    private void refreshModels(boolean clear) {
        if (clear) { generation++; previews.clear(); inFlight = 0; preview.clear(); }
        models = List.copyOf(runtime.localModels());
        if (models.stream().noneMatch(model -> model.id().equals(selectedId)))
            selectedId = models.isEmpty() ? "" : models.getFirst().id();
    }

    private void rebuildGrid() {
        for (var widget : gridWidgets) remove(widget);
        gridWidgets.clear();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        filtered = models.stream().filter(model -> (!favoritesOnly || runtime.options.isFavorite(model.id()))
                && (sourceFilter == 0 || source(model.id()) == sourceFilter)
                && (model.id().toLowerCase(Locale.ROOT).contains(needle) || model.label().toLowerCase(Locale.ROOT).contains(needle))).toList();
        page = Math.max(0, Math.min(page, pageCount() - 1));
        int start = page * pageSize;
        visible = filtered.subList(Math.min(start, filtered.size()), Math.min(start + pageSize, filtered.size()));
        int cellW = Math.max(20, (rightWidth - 10 - (columns - 1) * 4) / columns);
        int cellH = Math.max(30, (gridBottom - gridTop - (rows - 1) * 4) / rows);
        for (int i = 0; i < visible.size(); i++) {
            var card = new ModelCard(visible.get(i), right + 5 + i % columns * (cellW + 4), gridTop + i / columns * (cellH + 4), cellW, cellH);
            gridWidgets.add(addDrawableChild(card));
        }
        int navWidth = Math.min(62, (rightWidth - 58) / 2);
        var previous = button("上一页", right + 5, bottom - 21, navWidth, () -> { page--; rebuildGrid(); }, "浏览上一页模型");
        previous.active = page > 0; gridWidgets.add(previous);
        var next = button("下一页", right + rightWidth - navWidth - 5, bottom - 21, navWidth, () -> { page++; rebuildGrid(); }, "浏览下一页模型");
        next.active = page + 1 < pageCount(); gridWidgets.add(next);

        Set<String> retained = new HashSet<>();
        if (!selectedId.isEmpty()) retained.add(selectedId);
        visible.forEach(model -> retained.add(model.id()));
        previews.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) return false;
            if (entry.getValue().loaded != null) preview.release(key(entry.getKey(), entry.getValue().loaded));
            return true;
        });
        if (!selectedId.isEmpty()) previews.computeIfAbsent(selectedId, ignored -> new PreviewEntry());
        visible.forEach(model -> previews.computeIfAbsent(model.id(), ignored -> new PreviewEntry()));
        updateSelectionButtons();
        pumpLoads();
    }

    /** These accessors also let the GUI harness assert selection/filter/page behavior without applying models. */
    public String selectedModelId() { return selectedId; }
    public List<String> visibleModelIds() { return visible.stream().map(ClientRuntime.Action::id).toList(); }
    public int pageIndex() { return page; }
    public int pageCount() { return Math.max(1, (filtered.size() + Math.max(1, pageSize) - 1) / Math.max(1, pageSize)); }
    public void setSearchQuery(String value) { if (search != null) search.setText(value); else query = value; }
    public void setFavoritesOnly(boolean value) { favoritesOnly = value; page = 0; clearAndInit(); }
    public void goToPage(int index) { page = index; rebuildGrid(); }
    public void toggleSelectedFavorite() {
        if (selectedId.isEmpty()) return;
        runtime.options.toggleFavorite(selectedId); rebuildGrid();
    }
    public int loadedPreviewCount() { return (int) previews.values().stream().filter(entry -> entry.loaded != null).count(); }
    public int drawnPreviewCount() { return drawnPreviewCount; }
    public Map<String, Object> previewDiagnostics() { return preview.diagnostics(); }
    public Map<String, Object> previewDiagnostics(float minU, float minV, float maxU, float maxV) {
        return preview.diagnostics(minU, minV, maxU, maxV);
    }
    public boolean browseModel(String id) {
        if (models.stream().noneMatch(model -> model.id().equals(id))) return false;
        var entry = previews.get(id);
        if (!selectedId.equals(id) && entry != null && entry.loaded != null) preview.restart(key(id, entry.loaded));
        selectedId = id; message = "仅浏览，点击使用才应用"; yaw = 0; pitch = -8;
        rebuildGrid(); return true;
    }
    public void useSelectedModel() {
        if (selectedId.isEmpty()) return;
        var entry = previews.get(selectedId);
        if (entry == null || entry.loaded == null) { message = "请等待模型预览加载完成"; return; }
        runtime.selectLocalModel(selectedId);
        runtime.options.showSelf = true; runtime.options.save();
        message = client.world == null ? "已保存，进入世界后显示" : "已使用，仅自己可见（第三人称查看）";
        updateSelectionButtons();
    }

    private void updateSelectionButtons() {
        if (use == null) return;
        var entry = previews.get(selectedId);
        use.active = entry != null && entry.loaded != null;
        settings.active = !selectedId.isEmpty(); favorite.active = !selectedId.isEmpty();
        String favoriteText = runtime.options.isFavorite(selectedId) ? "★ 已收藏" : "☆ 收藏";
        if (favorite.getWidth() < 50) favoriteText = runtime.options.isFavorite(selectedId) ? "★" : "☆";
        favorite.setMessage(Text.literal(textRenderer.trimToWidth(favoriteText, favorite.getWidth() - 8)));
    }

    private void pumpLoads() {
        if (!activeView || inFlight >= MAX_LOADS) return;
        List<String> order = new ArrayList<>();
        if (!selectedId.isEmpty()) order.add(selectedId);
        for (var model : visible) if (!order.contains(model.id())) order.add(model.id());
        for (String id : order) {
            if (inFlight >= MAX_LOADS) break;
            PreviewEntry entry = previews.get(id);
            if (entry == null || entry.requested) continue;
            entry.requested = true; inFlight++; long requestGeneration = generation;
            runtime.loadLocalPreview(id).whenComplete((loaded, error) -> client.execute(() -> {
                if (!activeView || generation != requestGeneration) return;
                inFlight--;
                if (previews.get(id) == entry) {
                    entry.loaded = loaded; entry.error = error == null ? "" : loadError(error);
                    updateSelectionButtons();
                }
                pumpLoads();
            }));
        }
    }
    static String loadError(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? "模型预览加载失败" : detail.replace('\n', ' ').replace('\r', ' ');
    }
    static int source(String id) { return id.startsWith("ysm:") ? 2 : id.startsWith("local:") ? 3 : 1; }
    static String sourceLabel(String id) { return switch (source(id)) { case 2 -> "本地 YSM"; case 3 -> "本地 BBModel"; default -> "内置"; }; }
    private static String key(String id, LocalModelLibrary.Loaded loaded) { return id + ":" + loaded.hash(); }
    private String label(String id) { return models.stream().filter(model -> model.id().equals(id)).map(ClientRuntime.Action::label).findFirst().orElse(id); }
    private void clipped(DrawContext context, String text, int x, int y, int w, int color) {
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(text, Math.max(0, w)), x, y, color);
    }

    @Override public void tick() { ticks++; pumpLoads(); }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        preview.beginFrame(context);
        drawnPreviewCount = 0;
        context.fill(0, 0, width, height, 0xCF10171F);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 7, 0xfff3f6ff);
        if (height >= 230) context.drawCenteredTextWithShadow(textRenderer, "仅自己可见 · 无需服务器插件", width / 2, 23, 0xffbccce0);
        context.fill(left, top, left + previewWidth, bottom, 0xE6222730);
        context.fill(right, top, right + rightWidth, bottom, 0xE6222730);
        clipped(context, label(selectedId), left + 6, top + 6, previewWidth - 12, 0xfff3f6ff);
        clipped(context, sourceLabel(selectedId) + (runtime.localAppearance().enabled() && runtime.localAppearance().modelId().equals(selectedId) ? " · 使用中" : " · 浏览"), left + 6, top + 17, previewWidth - 12, 0xff92b9df);
        var entry = previews.get(selectedId);
        if (entry != null && entry.loaded != null) {
            boolean rendered = preview.render(context, entry.loaded.model(), key(selectedId, entry.loaded), previewX, previewY, previewW, previewH, yaw, pitch, ticks + delta, parameters(selectedId), entry.loaded.previewAnimation(), entry.loaded.profile());
            if (rendered) drawnPreviewCount++;
            if (!rendered) clipped(context, "预览暂不可用", previewX + 3, previewY + previewH / 2, previewW - 6, 0xffffc685);
        } else {
            String text = entry != null && !entry.error.isEmpty() ? entry.error : selectedId.isEmpty() ? "没有可用模型" : "正在加载模型…";
            clipped(context, text, previewX + 3, previewY + previewH / 2, previewW - 6, 0xffffc685);
        }
        clipped(context, rotationDisabled() ? "作者固定正面视角" : "拖动旋转 · 双击回正", left + 6, bottom - 57, previewWidth - 12, 0xffa7b5c8);
        if (visible.isEmpty()) clipped(context, favoritesOnly ? "暂无符合条件的收藏" : "没有符合条件的模型", right + 8, gridTop + 20, rightWidth - 16, 0xffbccce0);
        context.drawCenteredTextWithShadow(textRenderer, (page + 1) + " / " + pageCount(), right + rightWidth / 2, bottom - 15, 0xffe6ecf4);
        String status = message.isEmpty() ? runtime.localAppearanceStatus() : message;
        clipped(context, status, left + 127, height - 21, panelWidth - 127, 0xffffd589);
        super.render(context, mouseX, mouseY, delta);
    }

    private boolean insidePreview(double x, double y) { return x >= previewX && x < previewX + previewW && y >= previewY && y < previewY + previewH; }
    private boolean rotationDisabled() {
        var entry = previews.get(selectedId);
        return entry != null && entry.loaded != null && ModelPreview.rotationDisabled(entry.loaded.profile());
    }
    private Map<String, Double> parameters(String id) {
        return id.equals("openysm_default") ? Map.of("variable.roaming.red_bow_headdress", runtime.options.defaultHeaddress ? 1d : 0d) : Map.of();
    }
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
            yaw = (yaw + (float) dx * 1.5f) % 360; pitch = Math.max(-65, Math.min(65, pitch + (float) dy)); return true;
        }
        return super.mouseDragged(click, dx, dy);
    }
    @Override public boolean mouseReleased(Click click) {
        if (rotating && click.button() == 0) { rotating = false; return true; }
        return super.mouseReleased(click);
    }
    @Override public void removed() {
        activeView = false; rotating = false; generation++; inFlight = 0; previews.clear(); preview.clear();
    }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }

    private static final class PreviewEntry { boolean requested; LocalModelLibrary.Loaded loaded; String error = ""; }
    private final class ModelCard extends ButtonWidget {
        private final ClientRuntime.Action model;
        ModelCard(ClientRuntime.Action model, int x, int y, int w, int h) {
            super(x, y, w, h, net.minecraft.text.Text.literal(model.label()), button -> browseModel(model.id()), DEFAULT_NARRATION_SUPPLIER);
            this.model = model;
            setTooltip(Tooltip.of(net.minecraft.text.Text.literal(model.label() + "\n" + sourceLabel(model.id()) + " · " + model.id() + "\n点击浏览；右上角 ☆ 收藏")));
        }
        @Override public void onClick(Click click, boolean doubled) {
            if (click.x() >= getRight() - 17 && click.y() < getY() + 17) {
                runtime.options.toggleFavorite(model.id()); rebuildGrid();
            } else super.onClick(click, doubled);
        }
        // PressableWidget.renderWidget is final: drawIcon owns the entire card, without drawButton's background.
        @Override protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(getX(), getY(), getRight(), getBottom(), 0xff353c47);
            context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), selectedId.equals(model.id()) ? 0xff76b9ee : hovered || isFocused() ? 0xffb5cee2 : 0xff586372);
            var entry = previews.get(model.id());
            int imageHeight = Math.max(8, getHeight() - 28);
            if (entry != null && entry.loaded != null) {
                if (preview.render(context, entry.loaded.model(), key(model.id(), entry.loaded), getX() + 2, getY() + 2, getWidth() - 4, imageHeight - 2, 0, -8, ticks + delta, parameters(model.id()), entry.loaded.previewAnimation(), entry.loaded.profile())) drawnPreviewCount++;
                else clipped(context, "预览不可用", getX() + 5, getY() + imageHeight / 2, getWidth() - 10, 0xffffc685);
            } else clipped(context, entry != null && !entry.error.isEmpty() ? "加载失败" : "加载中…", getX() + 5, getY() + imageHeight / 2, getWidth() - 10, 0xffc1cedc);
            clipped(context, model.label(), getX() + 4, getBottom() - 24, getWidth() - 8, 0xfff3f6ff);
            clipped(context, runtime.options.showModelIds ? model.id() : sourceLabel(model.id()), getX() + 4, getBottom() - 12, getWidth() - 8, 0xffa5bfd5);
            context.drawTextWithShadow(textRenderer, runtime.options.isFavorite(model.id()) ? "★" : "☆", getRight() - 14, getY() + 4, 0xffffd878);
        }
    }

    private final class ImportHelpScreen extends Screen {
        private final Screen gallery;
        private int scroll, maxScroll;
        ImportHelpScreen(Screen gallery) { super(Text.literal("本地模型导入")); this.gallery = gallery; }
        @Override protected void init() {
            addDrawableChild(ButtonWidget.builder(Text.literal("打开模型文件夹"), b -> Util.getOperatingSystem().open(runtime.localModelDirectory()))
                    .dimensions(width / 2 - 105, height - 50, 130, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("返回图库"), b -> close()).dimensions(width / 2 + 30, height - 50, 75, 20).build());
        }
        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, width, height, 0xED171F2A);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xfff3f6ff);
            int textWidth = Math.min(520, width - 30), x = (width - textWidth) / 2;
            String help = "仅导入你有权使用的模型。\n\nYSM：放入已解压的模型文件夹，内含 ysm.json、models、animations 与 textures；支持基础几何和动画。完整动态配置后续支持。\n\nBBModel：放入独立 .bbmodel 文件，纹理需嵌入。\n\n文件需位于此目录内；不跟随链接，不加载外部路径或执行外部脚本。暂不导入加密 .ysm。\n\n返回图库后点击“刷新”。本地选择仅自己可见。滚动可查看全部说明。";
            var lines = textRenderer.wrapLines(Text.literal(help), textWidth);
            maxScroll = Math.max(0, lines.size() * 11 - (height - 100));
            scroll = Math.min(scroll, maxScroll);
            context.enableScissor(x, 40, x + textWidth, height - 59);
            for (int i = 0; i < lines.size(); i++) context.drawTextWithShadow(textRenderer, lines.get(i), x, 43 + i * 11 - scroll, 0xffc6d5e7);
            context.disableScissor();
            super.render(context, mouseX, mouseY, delta);
        }
        @Override public void close() { client.setScreen(gallery); }
        @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(vertical * 22))); return true;
        }
        @Override public boolean shouldPause() { return false; }
    }
}
