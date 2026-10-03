package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Gallery layout/filtering/card navigation adapted from OpenYSM-Updated PlayerModelScreen,
 * revision 0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85 (MIT; see THIRD_PARTY_NOTICES).
 * Browsing is a GUI-only draft; only the explicit use/settings buttons apply an appearance.
 */
public class LocalAppearanceScreen extends Screen {
    private static final int MAX_LOADS = 3;
    private static final int CARD_HEIGHT = 90, CARD_RENDER_HEIGHT = 76, CARD_CROP_HEIGHT = 70;
    protected static final Identifier GUI_ICONS=Identifier.of("meplayeractions","textures/gui/icon.png");
    private static final Identifier PACK_ICON=Identifier.of("meplayeractions","textures/gui/default_pack_icon.png");
    protected final ClientRuntime runtime;
    protected final Screen parent;
    protected final ModelPreview preview;
    private final Map<String, PreviewEntry> previews = new HashMap<>();
    private final List<ButtonWidget> gridWidgets = new ArrayList<>();
    private List<ClientRuntime.Action> models = List.of(), filtered = List.of(), visible = List.of();
    private record ModelGroup(int source,String path,String label,int count) { }
    private List<ModelGroup> filteredGroups=List.of(),visibleGroups=List.of();
    private final Map<String,Integer> groupPages=new HashMap<>();
    private int groupSource;
    private String selectedId, query = "", message = "";
    private boolean favoritesOnly, activeView, rotating;
    private int sourceFilter, page, columns, rows, pageSize, inFlight;
    protected int drawnPreviewCount;
    protected int left, top, bottom, panelWidth, previewWidth, right, rightWidth, gridTop, gridBottom;
    protected int previewX, previewY, previewW, previewH;
    protected boolean leftPreviewDrawn;
    protected String leftPreviewId="",leftPreviewKey="";
    private String leftPreviewSource="native-player",leftPreviewInstance="",leftPreviewHash="";
    private final Set<String> drawnCards=new HashSet<>();
    private long generation;
    protected float yaw = 0, pitch = -8, ticks;
    private TextFieldWidget search;
    private ButtonWidget use, settings, favorite;

    public LocalAppearanceScreen(ClientRuntime runtime) { this(runtime, null); }
    public LocalAppearanceScreen(ClientRuntime runtime, Screen parent) {
        this(runtime,parent,Text.literal("本地模型图库"));
    }
    protected LocalAppearanceScreen(ClientRuntime runtime,Screen parent,Text title) {
        super(title);
        this.runtime = runtime;
        this.preview = new ModelPreview(runtime);
        this.parent = parent;
        selectedId = runtime.localAppearance().modelId();
    }

    @Override protected void init() {
        activeView = true;
        panelWidth = Math.max(220, Math.min(1040, width - 16));
        left = (width - panelWidth) / 2;
        top = galleryTop();
        bottom = height - 34;
        previewWidth = Math.max(88, Math.min(290, panelWidth * 31 / 100));
        right = left + previewWidth + 6;
        rightWidth = panelWidth - previewWidth - 6;
        previewX = left + 4; previewY = top + 25;
        previewW = previewWidth - 8; previewH = Math.max(30, bottom - previewY - (showRotationHint()?63:46));
        gridTop = top + 29; gridBottom = bottom - 26;
        columns = Math.max(1, Math.min(5, (rightWidth - 10) / 54));
        rows = Math.max(1, Math.min(2, (gridBottom - gridTop + 4) / (CARD_HEIGHT + 4)));
        pageSize = columns * rows;
        use=null;settings=null;favorite=null;
        buildHeaderControls();
        if(clientGalleryVisible())buildGalleryControls();
        else {search=null;visible=List.of();filtered=List.of();visibleGroups=List.of();filteredGroups=List.of();gridWidgets.clear();buildAlternateControls();}
        buildFooterControls();
    }

    protected int galleryTop() {return height < 230 ? 29 : 38;}
    protected boolean clientGalleryVisible() {return true;}
    protected boolean requiresLocalSource() {return true;}
    protected boolean showRotationHint() {return true;}
    protected String settingsButtonLabel(int buttonWidth) {return buttonWidth<65?"设置":"详情 / 设置";}
    protected void buildHeaderControls() { }
    protected void buildAlternateControls() { }

    private void buildGalleryControls() {
        if (models.isEmpty()) refreshModels(false);

        boolean searchFocused=search!=null && search.isFocused();
        search = new TextFieldWidget(textRenderer, right + 5, top + 5, Math.max(35, rightWidth - 145), 20, Text.literal("搜索模型"));
        search.setMaxLength(80); search.setPlaceholder(Text.literal("搜索名称 / ID")); search.setText(query);
        search.setChangedListener(value -> { query = value; page = 0; rebuildGrid(); });
        addDrawableChild(search);
        if(searchFocused)setInitialFocus(search);
        iconButton(favoritesOnly ? "★ 收藏" : "☆ 收藏", right + rightWidth - 135, top + 5, 20, 0,0,() -> {
            favoritesOnly = !favoritesOnly; page = 0; clearAndInit();
        }, "仅显示已收藏的模型；每张卡片右上角可收藏");
        button(runtime.options.showModelIds ? "ID ✓" : "ID", right + rightWidth - 113, top + 5, 20, () -> {
            runtime.options.showModelIds = !runtime.options.showModelIds; runtime.options.save(); clearAndInit();
        }, "优先在卡片上显示模型 ID");

        iconButton(groupSource>0?"↑ 全部模型":new String[]{"全部来源", "内置", "本地 YSM", "本地 BB"}[sourceFilter], right + rightWidth - 91, top + 5, 20,groupSource>0?0:32,groupSource>0?32:0,
                () -> {if(groupSource>0)navigateUp();else {sourceFilter=(sourceFilter+1)%4;page=0;clearAndInit();}},
                groupSource>0?"返回上一级模型分组":"点击切换模型来源筛选；分组卡片可进入对应模型目录");
        iconButton("文件夹", right + rightWidth - 69, top + 5, 20,80,0,() -> {
            Util.getOperatingSystem().open(runtime.localModelDirectory());
            message = "放入模型后点击刷新";
        }, "打开本地模型目录");
        button("刷新", right + rightWidth - 47, top + 5, 20, () -> {
            refreshModels(true); page = 0; rebuildGrid(); message = "已刷新本地模型";
        }, "重新扫描文件和模型预览；不会改变已使用的外观");
        iconButton("导入说明", right + rightWidth - 25, top + 5, 20,80,16,
                () -> client.setScreen(new ImportHelpScreen(this)), "支持安全 YSM 文件夹和独立 BBModel 文件");

        use = button("使用模型", left + 5, bottom - 44, previewWidth - 10, this::useSelectedModel,
                "将当前浏览的模型用于自己；其他玩家看不到本地选择");
        int half = (previewWidth - 13) / 2;
        settings = button(settingsButtonLabel(half), left + 5, bottom - 21, half,
                () -> client.setScreen(new ModelSettingsScreen(runtime, selectedId, this)), "查看模型信息，设置缩放、XYZ 位置与有效模型参数");
        favorite = button("☆ 收藏", left + 8 + half, bottom - 21, half, this::toggleSelectedFavorite, "收藏或取消收藏当前浏览模型");
        rebuildGrid();
    }
    protected void buildFooterControls() {
        button("返回", left, height - 27, 52, this::close, "返回上一页");
        button("恢复默认", left + 56, height - 27, 65, () -> {
            runtime.options.defaultHeaddress = true; runtime.options.defaultBlueTexture = false;
            var defaults = LocalAppearanceSettings.defaults(); runtime.updateLocalAppearance(defaults);
            refreshModels(true); selectedId = defaults.modelId(); message = "已恢复默认，本地外观关闭"; rebuildGrid();
        }, "恢复默认模型、缩放和位置，并关闭本地外观");
    }

    protected ButtonWidget button(String label, int x, int y, int w, Runnable action, String tooltip) {
        return flatButton(label,x,y,w,20,action,tooltip,false);
    }
    protected ButtonWidget flatButton(String label,int x,int y,int w,int h,Runnable action,String tooltip,boolean selected) {
        var button=new ButtonWidget(x,y,Math.max(20,w),h,Text.literal(label),b->action.run(),narration->narration.get()) {
            @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
                context.fill(getX(),getY(),getRight(),getBottom(),selected?-14774017:-12369342);
                if(hovered || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-790560);
                String text=textRenderer.trimToWidth(getMessage().getString(),Math.max(1,getWidth()-6));
                context.drawCenteredTextWithShadow(textRenderer,text,getX()+getWidth()/2,getY()+(getHeight()-8)/2,active?0xfff3f0e0:0xff8a929c);
            }
        };
        button.setTooltip(Tooltip.of(Text.literal(tooltip)));
        return addDrawableChild(button);
    }
    protected ButtonWidget iconButton(String label,int x,int y,int w,int u,int v,Runnable action,String tooltip) {
        var button=new ButtonWidget(x,y,Math.max(20,w),20,Text.literal(label),b->action.run(),narration->narration.get()) {
            @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
                context.fill(getX(),getY(),getRight(),getBottom(),-12369342);
                if(hovered || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-790560);
                context.drawTexture(RenderPipelines.GUI_TEXTURED,GUI_ICONS,getX()+(getWidth()-16)/2,getY()+2,(float)u,(float)v,16,16,256,256);
            }
        };
        button.setTooltip(Tooltip.of(Text.literal(tooltip)));return addDrawableChild(button);
    }

    private void refreshModels(boolean clear) {
        if (clear) { generation++; previews.clear(); inFlight = 0; preview.clear(); }
        models = List.copyOf(runtime.localModels());
        if (models.stream().noneMatch(model -> model.id().equals(selectedId)))
            selectedId = models.isEmpty() ? "" : models.getFirst().id();
    }

    private void rebuildGrid() {
        if(!clientGalleryVisible())return;
        for (var widget : gridWidgets) remove(widget);
        gridWidgets.clear();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        filtered = models.stream().filter(model -> (!favoritesOnly || runtime.options.isFavorite(model.id()))
                && (sourceFilter == 0 || source(model.id()) == sourceFilter)
                && (groupSource == 0 || source(model.id()) == groupSource)
                && (model.id().toLowerCase(Locale.ROOT).contains(needle) || model.label().toLowerCase(Locale.ROOT).contains(needle))).toList();
        var groups=new ArrayList<ModelGroup>();
        if(groupSource==0 && sourceFilter==0 && !favoritesOnly && needle.isEmpty())for(int kind=1;kind<=3;kind++) {
            int type=kind,count=(int)models.stream().filter(model->source(model.id())==type).count();
            if(count>0)groups.add(new ModelGroup(type,new String[]{"","builtin/","ysm/","bbmodel/"}[type],
                    new String[]{"","内置模型","YSM 文件夹","BBModel 文件"}[type],count));
        }
        filteredGroups=List.copyOf(groups);
        page = Math.max(0, Math.min(page, pageCount() - 1));
        int start = page * pageSize;
        int end=start+pageSize,modelStart=Math.max(0,start-filteredGroups.size()),modelEnd=Math.max(0,end-filteredGroups.size());
        visibleGroups=filteredGroups.subList(Math.min(start,filteredGroups.size()),Math.min(end,filteredGroups.size()));
        visible = filtered.subList(Math.min(modelStart, filtered.size()), Math.min(modelEnd, filtered.size()));
        int cellW = Math.max(20, (rightWidth - 10 - (columns - 1) * 4) / columns);
        int cellH = CARD_HEIGHT;
        for(int i=0;i<visibleGroups.size();i++) {
            var card=new GroupCard(visibleGroups.get(i),right+5+i%columns*(cellW+4),gridTop+i/columns*(cellH+4),cellW,cellH);
            gridWidgets.add(addDrawableChild(card));
        }
        for (int i = 0; i < visible.size(); i++) {
            int slot=i+visibleGroups.size();
            var card = new ModelCard(visible.get(i), right + 5 + slot % columns * (cellW + 4), gridTop + slot / columns * (cellH + 4), cellW, cellH);
            gridWidgets.add(addDrawableChild(card));
        }
        int navWidth = Math.min(62, (rightWidth - 58) / 2);
        var previous = button("上一页", right + 5, bottom - 21, navWidth, () -> {goToPage(page-1);}, "浏览上一页模型");
        previous.active = page > 0; gridWidgets.add(previous);
        var next = button("下一页", right + rightWidth - navWidth - 5, bottom - 21, navWidth, () -> {goToPage(page+1);}, "浏览下一页模型");
        next.active = page + 1 < pageCount(); gridWidgets.add(next);

        Set<String> retained = new HashSet<>();
        if (runtime.localAppearance().enabled()) retained.add(runtime.localAppearance().modelId());
        if (!selectedId.isEmpty()) retained.add(selectedId);
        visible.forEach(model -> retained.add(model.id()));
        previews.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) return false;
            if (entry.getValue().loaded != null) preview.release(key(entry.getKey(), entry.getValue().loaded));
            return true;
        });
        if (!selectedId.isEmpty()) previews.computeIfAbsent(selectedId, ignored -> new PreviewEntry());
        if (runtime.localAppearance().enabled()) previews.computeIfAbsent(runtime.localAppearance().modelId(), ignored -> new PreviewEntry());
        visible.forEach(model -> previews.computeIfAbsent(model.id(), ignored -> new PreviewEntry()));
        updateSelectionButtons();
        pumpLoads();
    }

    /** These accessors also let the GUI harness assert selection/filter/page behavior without applying models. */
    public String selectedModelId() { return selectedId; }
    public List<String> visibleModelIds() { return visible.stream().map(ClientRuntime.Action::id).toList(); }
    public int pageIndex() { return page; }
    public int pageCount() { return Math.max(1, (filtered.size()+filteredGroups.size() + Math.max(1, pageSize) - 1) / Math.max(1, pageSize)); }
    public void setSearchQuery(String value) { if (search != null) search.setText(value); else query = value; }
    public void setFavoritesOnly(boolean value) { favoritesOnly = value; page = 0; clearAndInit(); }
    public void goToPage(int index) { page = index; rebuildGrid();groupPages.put(groupPath(),page); }
    public String groupPath() {return new String[]{"","builtin/","ysm/","bbmodel/"}[groupSource];}
    public void browseGroup(int source) {
        if(source<1 || source>3 || models.stream().noneMatch(model->source(model.id())==source))return;
        groupPages.put(groupPath(),page);groupSource=source;sourceFilter=0;query="";
        page=groupPages.getOrDefault(groupPath(),0);clearAndInit();
    }
    public void navigateUp() {
        if(groupSource==0)return;
        groupPages.put(groupPath(),page);groupSource=0;page=groupPages.getOrDefault("",0);clearAndInit();
    }
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
    /** Actual initialized card bounds and this frame's mesh submissions, rather than a synthetic UI plan. */
    public Map<String,Object> galleryDiagnostics() {
        var result=new LinkedHashMap<String,Object>();
        result.put("referenceRevision","0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85");
        result.put("layout","left-preview/right-search-model-cards");result.put("groupPath",groupPath());
        result.put("search",query);result.put("favoritesOnly",favoritesOnly);result.put("sourceFilter",sourceFilter);
        result.put("page",page);result.put("pageCount",pageCount());result.put("selectedModelId",selectedId);
        result.put("leftPreview",Map.of("x",previewX,"y",previewY,"width",previewW,"height",previewH,
                "modelId",leftPreviewId,"key",leftPreviewKey,"drawn",leftPreviewDrawn,
                "appearanceSource",leftPreviewSource,"instance",leftPreviewInstance,"hash",leftPreviewHash));
        result.put("cards",gridWidgets.stream().filter(widget->widget instanceof ModelCard).map(widget->{
            var card=(ModelCard)widget;var entry=previews.get(card.model.id());var info=new LinkedHashMap<String,Object>();
            info.put("modelId",card.model.id());info.put("label",card.model.label());info.put("x",card.getX());info.put("y",card.getY());
            info.put("width",card.getWidth());info.put("height",card.getHeight());info.put("selected",selectedId.equals(card.model.id()));
            info.put("favorite",runtime.options.isFavorite(card.model.id()));info.put("loaded",entry!=null&&entry.loaded!=null);
            info.put("error",entry==null?"":entry.error);info.put("drawn",drawnCards.contains(card.model.id()));return Map.copyOf(info);
        }).toList());
        result.put("groups",gridWidgets.stream().filter(widget->widget instanceof GroupCard).map(widget->{
            var card=(GroupCard)widget;return Map.<String,Object>of("path",card.group.path(),"label",card.group.label(),"count",card.group.count(),
                    "x",card.getX(),"y",card.getY(),"width",card.getWidth(),"height",card.getHeight());
        }).toList());
        result.put("preview",preview.diagnostics());return Map.copyOf(result);
    }
    public boolean browseModel(String id) {
        if (models.stream().noneMatch(model -> model.id().equals(id))) return false;
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
        if (!activeView || !runtime.canEditLocalAppearance() || inFlight >= MAX_LOADS) return;
        List<String> order = new ArrayList<>();
        if (runtime.localAppearance().enabled()) {
            String currentId = runtime.localAppearance().modelId();
            previews.computeIfAbsent(currentId, ignored -> new PreviewEntry());
            order.add(currentId);
        }
        if (!clientGalleryVisible() && order.isEmpty()) return;
        if (clientGalleryVisible() && !selectedId.isEmpty() && !order.contains(selectedId)) order.add(selectedId);
        if (clientGalleryVisible()) for (var model : visible) if (!order.contains(model.id())) order.add(model.id());
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
    protected void clipped(DrawContext context, String text, int x, int y, int w, int color) {
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(text, Math.max(0, w)), x, y, color);
    }

    @Override public void tick() {
        if (requiresLocalSource() && !runtime.canEditLocalAppearance()) { client.setScreen(new PlayerModelScreen(runtime)); return; }
        ticks++; pumpLoads();
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        preview.beginFrame(context);
        drawnPreviewCount = 0;leftPreviewDrawn=false;leftPreviewId="";leftPreviewKey="";drawnCards.clear();
        leftPreviewSource="native-player";leftPreviewInstance="";leftPreviewHash="";
        context.fill(0, 0, width, height, 0xCF10171F);
        renderHeader(context);
        context.fill(left, top, left + previewWidth, bottom, 0xE6222730);
        context.fill(right, top, right + rightWidth, bottom, 0xE6222730);
        if(clientGalleryVisible())renderGallery(context,delta);
        else renderAlternateContent(context,delta);
        super.render(context, mouseX, mouseY, delta);
    }
    protected void renderHeader(DrawContext context) {
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 7, 0xfff3f6ff);
        if (height >= 230) context.drawCenteredTextWithShadow(textRenderer, "仅自己可见 · 无需服务器插件", width / 2, 23, 0xffbccce0);
    }
    protected void renderAlternateContent(DrawContext context,float delta) { }
    private void renderGallery(DrawContext context,float delta) {
        renderSelectedPreview(context,delta);
        if (visible.isEmpty() && visibleGroups.isEmpty()) clipped(context, favoritesOnly ? "暂无符合条件的收藏" : "没有符合条件的模型", right + 8, gridTop + 20, rightWidth - 16, 0xffbccce0);
        context.drawCenteredTextWithShadow(textRenderer, (page + 1) + " / " + pageCount(), right + rightWidth / 2, bottom - 15, 0xffe6ecf4);
        String status = message.isEmpty() ? runtime.localAppearanceStatus() : message;
        clipped(context, status, left + footerStatusInset(), height - 21, panelWidth - footerStatusInset(), 0xffffd589);
    }
    protected void renderSelectedPreview(DrawContext context,float delta) {
        ClientRuntime.GuiPreviewAppearance appearance = runtime.guiPreviewAppearance();
        if (appearance == null) {
            clipped(context, "原版玩家", left + 6, top + 6, previewWidth - 12, 0xfff3f6ff);
            clipped(context, "当前无替换模型", left + 6, top + 17, previewWidth - 12, 0xff92b9df);
            clipped(context, "原版外观 · 使用后显示模型", previewX + 3, previewY + previewH / 2, previewW - 6, 0xffc6d5e7);
            return;
        }
        String id = appearance.modelId();
        leftPreviewId=id;leftPreviewKey=id+":"+appearance.assetHash();leftPreviewHash=appearance.assetHash();
        leftPreviewInstance=appearance.instance();leftPreviewSource="runtime-current";
        clipped(context, label(id), left + 6, top + 6, previewWidth - 12, 0xfff3f6ff);
        clipped(context, "当前使用", left + 6, top + 17, previewWidth - 12, 0xff92b9df);
        var entry = previews.get(id);
        boolean sameAsset = entry != null && entry.loaded != null
                && entry.loaded.hash().equals(appearance.assetHash())
                && entry.loaded.profile().isYsm() == appearance.profile().isYsm();
        // The live binding is authoritative; a separately decoded GUI copy may share its exact validated content hash.
        var model = sameAsset ? entry.loaded.model() : appearance.model();
        YsmModelProfile profile = sameAsset ? entry.loaded.profile() : appearance.profile();
        leftPreviewDrawn=preview.render(context, model, leftPreviewKey, previewX, previewY, previewW, previewH,
                yaw, pitch, ticks + delta, profile.isYsm() ? parameters(id) : Map.of(), profile.previewAnimation(), profile, ModelPreview.Context.OWNER);
        if (leftPreviewDrawn) drawnPreviewCount++;
        else clipped(context, "当前模型预览暂不可用", previewX + 3, previewY + previewH / 2, previewW - 6, 0xffffc685);
        if(showRotationHint())clipped(context, rotationDisabled() ? "作者固定正面视角" : "拖动旋转 · 双击回正", left + 6, bottom - 57, previewWidth - 12, 0xffa7b5c8);
    }
    protected int footerStatusInset() {return 127;}

    private boolean insidePreview(double x, double y) { return x >= previewX && x < previewX + previewW && y >= previewY && y < previewY + previewH; }
    protected boolean rotationDisabled() {
        return false;
    }
    private Map<String, Double> parameters(String id) {
        var values=new LinkedHashMap<String,Double>();
        if(id.equals("openysm_default"))values.put("variable.roaming.red_bow_headdress",runtime.options.defaultHeaddress?1d:0d);
        values.putAll(runtime.options.modelProfile(id).variables());return Map.copyOf(values);
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
    @Override public boolean mouseScrolled(double mouseX,double mouseY,double horizontal,double vertical) {
        if(clientGalleryVisible() && mouseX>=right && mouseX<right+rightWidth && mouseY>=gridTop && mouseY<bottom && vertical!=0) {
            goToPage(page+(vertical>0?-1:1));return true;
        }
        return super.mouseScrolled(mouseX,mouseY,horizontal,vertical);
    }
    @Override public void removed() {
        activeView = false; rotating = false;clearGalleryPreviews();
    }
    protected void clearGalleryPreviews() {generation++;inFlight=0;previews.clear();preview.clear();drawnCards.clear();leftPreviewDrawn=false;}
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }

    private static final class PreviewEntry { boolean requested; LocalModelLibrary.Loaded loaded; String error = ""; }
    private final class GroupCard extends ButtonWidget {
        private final ModelGroup group;
        GroupCard(ModelGroup group,int x,int y,int w,int h) {
            super(x,y,w,h,net.minecraft.text.Text.literal(group.label()),button->browseGroup(group.source()),DEFAULT_NARRATION_SUPPLIER);this.group=group;
            setTooltip(Tooltip.of(net.minecraft.text.Text.literal(group.label()+" · "+group.count()+" 个模型\n点击进入分组；来源于已校验的本地模型 ID")));
        }
        @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
            context.fill(getX(),getY(),getRight(),getBottom(),hovered?0xff394b62:0xff303d4e);
            context.drawTexture(RenderPipelines.GUI_TEXTURED,PACK_ICON,getX(),getY(),0f,0f,getWidth(),getHeight(),52,90,52,90);
            if(hovered || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-1982745);
            clipped(context,group.label(),getX()+4,getBottom()-24,getWidth()-8,0xff555555);
            clipped(context,group.count()+" 个模型",getX()+4,getBottom()-12,getWidth()-8,0xff555555);
        }
    }
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
            context.fill(getX(), getY(), getRight(), getBottom(), -12369342);
            if(selectedId.equals(model.id()) || hovered || isFocused())context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
            var entry = previews.get(model.id());
            int imageHeight = Math.max(8, getHeight() - 20);
            if (entry != null && entry.loaded != null) {
                boolean nativeCard = NativeGuiPreviewCamera.appliesTo(entry.loaded.profile());
                boolean rendered;
                if (nativeCard) {
                    context.enableScissor(getX(), getY(), getRight(), getY() + CARD_CROP_HEIGHT);
                    try {
                        rendered = preview.render(context, entry.loaded.model(), key(model.id(), entry.loaded), getX(), getY(),
                                getWidth(), CARD_RENDER_HEIGHT, 0, 0, ticks + delta, parameters(model.id()),
                                entry.loaded.previewAnimation(), entry.loaded.profile(), ModelPreview.Context.CARD);
                    } finally { context.disableScissor(); }
                } else rendered = preview.render(context, entry.loaded.model(), key(model.id(), entry.loaded), getX() + 2, getY() + 2,
                        getWidth() - 4, imageHeight - 2, 0, -8, ticks + delta, parameters(model.id()), entry.loaded.previewAnimation(), entry.loaded.profile(), ModelPreview.Context.CARD);
                if (rendered) {drawnPreviewCount++;drawnCards.add(model.id());}
                else clipped(context, "预览不可用", getX() + 5, getY() + imageHeight / 2, getWidth() - 10, 0xffffc685);
            } else clipped(context, entry != null && !entry.error.isEmpty() ? "加载失败" : "加载中…", getX() + 5, getY() + imageHeight / 2, getWidth() - 10, 0xffc1cedc);
            clipped(context, model.label(), getX() + 4, getBottom() - 24, getWidth() - 8, 0xfff3f6ff);
            clipped(context, runtime.options.showModelIds ? model.id() : sourceLabel(model.id()), getX() + 4, getBottom() - 12, getWidth() - 8, 0xffa5bfd5);
            context.drawTexture(RenderPipelines.GUI_TEXTURED,GUI_ICONS,getRight()-16,getY(),runtime.options.isFavorite(model.id())?16f:0f,0f,16,16,256,256);
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
            String help = "仅导入你有权使用的模型。\n\nYSM：放入已解压的模型文件夹，内含 ysm.json、models、animations 与 textures；可使用模型提供的骨架、动画、皮肤和作者配置。\n\nBBModel：放入独立 .bbmodel 文件，纹理需嵌入。\n\n文件需位于此目录内；不跟随链接，不访问目录外资源。作者 Molang 在受限表达式环境中运行。暂不导入加密 .ysm。\n\n返回图库后点击“刷新”。本地选择仅自己可见。滚动可查看全部说明。";
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
