package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.network.PrivateUploadCatalogSnapshot;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ConfirmScreen;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Gallery layout/filtering/card navigation adapted from OpenYSM-Updated PlayerModelScreen,
 * revision 0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85 (MIT; see THIRD_PARTY_NOTICES).
 * Browsing is a GUI-only draft; only the explicit use/settings buttons apply an appearance.
 */
public class LocalAppearanceScreen extends Screen {
    private static final int MAX_LOADS = 3;
    // OpenYSM ModelButton(52, 90), PlayerModelScreen's 55/93 slot stride,
    // and ModelButton's 76-pixel entity viewport (ModelPreview crops only the entity to 70).
    private static final int CARD_WIDTH = GalleryPanelLayout.CARD_WIDTH,
            CARD_HEIGHT = GalleryPanelLayout.CARD_HEIGHT, CARD_GAP = GalleryPanelLayout.CARD_GAP,
            CARD_RENDER_HEIGHT = 76;
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
    private String groupDirectory="";
    private final GalleryPreviewSelection selection;
    private String query = "", message = "";
    private boolean favoritesOnly, uploadedOnly, activeView, rotating;
    private Set<String> confirmedUploads=Set.of();
    private Set<String> localSourceIds=Set.of();
    private List<PrivateUploadCatalogSnapshot.Model> savedUploads=List.of();
    private String observedDeleteId="",observedDeleteStatus="";
    private int sourceFilter, page, columns, rows, pageSize, inFlight;
    protected int drawnPreviewCount;
    protected int left, top, bottom, panelWidth, previewWidth, right, rightWidth, gridTop, gridBottom;
    protected int frameTop, frameBottom, titleY, footerY;
    protected boolean compactLayout;
    protected int previewX, previewY, previewW, previewH;
    protected boolean leftPreviewDrawn;
    protected String leftPreviewId="",leftPreviewKey="";
    protected String leftPreviewSource="native-player",leftPreviewInstance="",leftPreviewHash="";
    private final Set<String> drawnCards=new HashSet<>();
    private long generation;
    protected float yaw = 0, pitch = -8, ticks;
    private TextFieldWidget search;
    protected ButtonWidget use, settings, favorite;
    private ButtonWidget upload;

    public LocalAppearanceScreen(ClientRuntime runtime) { this(runtime, null); }
    public LocalAppearanceScreen(ClientRuntime runtime, Screen parent) {
        this(runtime,parent,Text.literal("本地模型图库"));
    }
    protected LocalAppearanceScreen(ClientRuntime runtime,Screen parent,Text title) {
        super(title);
        this.runtime = runtime;
        this.preview = new ModelPreview(runtime);
        this.parent = parent;
        selection = new GalleryPreviewSelection(runtime.localAppearance().modelId());
    }

    @Override protected void init() {
        activeView = true;
        var layout = GalleryPanelLayout.create(width, height, galleryHeaderHeight(false), galleryHeaderHeight(true));
        panelWidth = layout.panelWidth(); left = layout.left(); top = layout.top(); bottom = layout.bottom();
        previewWidth = layout.previewWidth(); right = layout.right(); rightWidth = layout.rightWidth();
        frameTop = layout.frameTop(); frameBottom = layout.frameBottom();
        titleY = layout.titleY(); footerY = layout.footerY(); compactLayout = layout.compact();
        previewX = left + 4; previewY = top + 25;
        previewW = previewWidth - 8; previewH = Math.max(30, bottom - previewY - (showRotationHint()?63:46));
        gridTop = top + 29; gridBottom = bottom - 26;
        columns = layout.columns(); rows = layout.rows();
        pageSize = columns * rows;
        use=null;settings=null;favorite=null;upload=null;
        buildHeaderControls();
        if(clientGalleryVisible())buildGalleryControls();
        else {search=null;visible=List.of();filtered=List.of();visibleGroups=List.of();filteredGroups=List.of();gridWidgets.clear();buildAlternateControls();}
        buildFooterControls();
    }

    protected int galleryHeaderHeight(boolean compact) {return compact ? 29 : 38;}
    protected boolean clientGalleryVisible() {return true;}
    protected boolean requiresLocalSource() {return true;}
    protected boolean showRotationHint() {return true;}
    protected String settingsButtonLabel(int buttonWidth) {return buttonWidth<65?"设置":"详情 / 设置";}
    protected boolean localGallery() {return true;}
    protected List<ClientRuntime.Action> galleryModels() {return runtime.localModels();}
    protected CompletableFuture<LocalModelLibrary.Loaded> loadGalleryPreview(String id) {
        return uploadedOnly&&!runtime.privateModelUploadState(id).uploaded()?CompletableFuture.completedFuture(null):runtime.loadLocalPreview(id);
    }
    protected YsmModelProfile galleryProfile(String id) {return runtime.localModelProfile(id);}
    protected String gallerySourceLabel(String id) {return uploadedOnly?"本人服务器存档":sourceLabel(id);}
    protected String galleryStatus() {return uploadedOnly?runtime.privateUploadCatalogStatus():runtime.localAppearanceStatus();}
    protected String galleryPreviewUnavailableText(String id) {return cloudModelHint(id).isEmpty()?"正在加载所选模型…":cloudModelHint(id);}
    /** Both sources use the same corner affordance; server cards expose cache status instead of uploading. */
    protected boolean galleryCardCloudVisible() {return localGallery();}
    protected String galleryCardCloudTooltip(String id) {return uploadTooltip(id);}
    protected void galleryCardCloudClicked(String id) {
        if(canUseCloudAction(id)){browseModel(id);uploadSelectedModel();}else setGalleryMessage(uploadTooltip(id));
    }
    protected void drawGalleryCardCloud(DrawContext context,String id,int x,int y) {
        var state=runtime.privateModelUploadState(id);boolean saved=storedPrivateModel(id)!=null;
        int tint=runtime.deletingUploadedPrivateModel(id)?0xffffd589:state.uploaded()?0xff8df2b5
                :saved||state.inProgress()||state.published()?0xffffd589:canUploadModel(id)?0xfff3f0e0:0xff8a929c;
        CloudUploadIcon.draw(context,x,y,tint,saved||state.published());
    }
    protected boolean canUseGalleryModel(String id,LocalModelLibrary.Loaded loaded) {
        return loaded!=null&&(!uploadedOnly||runtime.privateModelUploadState(id).uploaded());
    }
    protected String galleryUseTooltip() {return runtime.canActivateLocalAppearance()
            ? "使用所选模型，仅自己可见\n云朵按钮可上传分享" : "请先解除服务器伪装\n客户端与服务端外观不能同时使用";}
    protected GalleryModelUseState.State galleryUseState(String id,LocalModelLibrary.Loaded loaded) {
        if(uploadedOnly&&!runtime.privateModelUploadState(id).uploaded())
            return new GalleryModelUseState.State(GalleryModelUseState.Phase.BLOCKED,"使用模型",false);
        boolean current=runtime.currentAppearanceSource()==ClientRuntime.AppearanceSource.CLIENT;
        return GalleryModelUseState.local(id,loaded==null?"":loaded.hash(),current?runtime.currentAppearanceModelId():"",
                current?runtime.currentAppearanceAssetHash():"",runtime.pendingLocalAppearanceModelId(),
                runtime.canActivateLocalAppearance(),canUseGalleryModel(id,loaded));
    }
    protected String galleryUseTooltip(GalleryModelUseState.State state) {
        if(uploadedOnly&&!cloudModelHint(selection.modelId()).isEmpty())return "云端已保存\n"+cloudModelHint(selection.modelId()).replace("云端已保存 · ","")+"\n本机内容一致时才能使用\n云朵按钮可删除服务器资源";
        if(state.current())return "当前正在使用此模型\n选择其他模型可切换";
        if(state.waiting())return localGallery()?"正在加载所选模型\n请等待加载完成":"请求已发送\n等待服务器确认伪装";
        return galleryUseTooltip();
    }
    protected String galleryBrowsingMessage() {return "预览中，点击使用模型才应用";}
    protected boolean isFavoriteModel(String id) {return runtime.options.isFavorite(id);}
    protected void toggleModelFavorite(String id) {runtime.options.toggleFavorite(id);}
    protected void setGalleryMessage(String value) {message=value;}
    protected void refreshGalleryModels() {refreshModels(false);}
    protected void resetGallery() {
        clearGalleryPreviews();models=List.of();filtered=List.of();visible=List.of();
        groupSource=sourceFilter=page=0;groupDirectory=query=message="";
        favoritesOnly=uploadedOnly=false;groupPages.clear();selection.restore("");
    }
    protected void buildHeaderControls() { }
    protected void buildAlternateControls() { }

    private void buildGalleryControls() {
        if (models.isEmpty()) refreshModels(false);

        boolean searchFocused=search!=null && search.isFocused();
        int toolsWidth=localGallery()?167:101;
        search = new TextFieldWidget(textRenderer, right + 5, top + 5, Math.max(35, rightWidth - toolsWidth), 20, Text.literal("搜索模型"));
        search.setMaxLength(80); search.setPlaceholder(Text.literal("搜索名称 / ID")); search.setText(query);
        search.setChangedListener(value -> { query = value; page = 0; rebuildGrid(); });
        addDrawableChild(search);
        if(searchFocused)setInitialFocus(search);
        int favoritesX=right+rightWidth-(localGallery()?157:91);
        iconButton(favoritesOnly ? "★ 收藏" : "☆ 收藏", favoritesX, top + 5, 20, 0,0,() -> {
            favoritesOnly = !favoritesOnly; page = 0; clearAndInit();
        }, "只显示收藏的模型\n卡片右上角可收藏");
        button(runtime.options.showModelIds ? "ID ✓" : "ID", favoritesX+22, top + 5, 20, () -> {
            runtime.options.showModelIds = !runtime.options.showModelIds; runtime.options.save(); clearAndInit();
        }, "优先在卡片上显示模型 ID");

        if(localGallery()) {
        cloudButton("已上传",right+rightWidth-113,top+5,20,()->{
            uploadedOnly=!uploadedOnly;groupSource=sourceFilter=page=0;groupDirectory="";clearGalleryPreviews();refreshModels(false);clearAndInit();
        },()->uploadedOnly,runtime::privateUploadCatalogReady,()->runtime.privateUploadCatalogReady()
                ?"只显示服务器已保存的本人模型\n本地文件须与保存内容一致":runtime.privateUploadCatalogStatus());
        iconButton(groupSource>0?"↑ 全部模型":new String[]{"全部来源", "内置", "本地 YSM", "本地 BB"}[sourceFilter], right + rightWidth - 91, top + 5, 20,groupSource>0?0:32,groupSource>0?32:0,
                () -> {if(groupSource>0)navigateUp();else {sourceFilter=(sourceFilter+1)%4;page=0;clearAndInit();}},
                groupSource>0?"返回上一级模型分组":"切换模型来源筛选\n点击目录卡片进入分组");
        iconButton("文件夹", right + rightWidth - 69, top + 5, 20,80,0,() -> {
            Util.getOperatingSystem().open(runtime.localModelDirectory());
            message = "放入模型后点击刷新";
        }, "打开本地模型目录");
        button("刷新", right + rightWidth - 47, top + 5, 20, () -> {
            runtime.refreshLocalModelSources();refreshModels(true); page = 0; rebuildGrid(); message = "已刷新本地模型";
        }, "重新扫描模型与预览\n保留当前使用的外观");
        } else iconButton("缓存目录",right+rightWidth-47,top+5,20,80,0,this::openServerCacheDirectory,
                "打开本机服务器模型缓存\nconfig/meplayeractions/cache");
        iconButton("使用教程",right+rightWidth-25,top+5,20,80,16,
                ()->client.setScreen(new ModelHelpScreen(runtime,this,!localGallery())),localGallery()
                        ?"客户端模型教程\n导入、使用、上传分享与存档管理":"服务端模型教程\n伪装、外观设置与客户端接管");
        buildSelectionControls();
        rebuildGrid();
    }
    protected void buildSelectionControls() {
        use = button("使用模型", left + 5, bottom - 44, previewWidth - 10, this::useSelectedModel,
                galleryUseTooltip());
        int half = (previewWidth - 13) / 2;
        settings = button(settingsButtonLabel(half), left + 5, bottom - 21, half,
                () -> client.setScreen(new ModelSettingsScreen(runtime, selection.modelId(), this)), "查看模型详情\n设置缩放、位置与作者配置");
        upload = cloudButton("上传分享",left+8+half,bottom-21,half,this::uploadSelectedModel,
                ()->storedPrivateModel(selection.modelId())!=null||runtime.privateModelUploadState(selection.modelId()).published(),
                ()->canUseCloudAction(selection.modelId()),()->uploadTooltip(selection.modelId()),
                ()->runtime.deletingUploadedPrivateModel(selection.modelId())?"删除中":storedPrivateModel(selection.modelId())!=null
                        ?"已上传":runtime.privateModelUploadState(selection.modelId()).published()?"已分享":"上传分享");
    }
    protected void buildFooterControls() {
        button("返回", left, footerY, 52, this::close, "返回上一页");
        button("恢复默认", left + 56, footerY, 65, () -> {
            runtime.options.defaultHeaddress = true; runtime.options.defaultBlueTexture = false;
            var defaults = LocalAppearanceSettings.defaults(); runtime.updateLocalAppearance(defaults);
            refreshModels(true); selection.restore(defaults.modelId()); message = "已恢复默认，本地外观关闭"; rebuildGrid();
        }, "恢复默认模型、缩放与位置\n关闭私人外观");
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
        button.setTooltip(ModelUiTooltip.of(textRenderer,LocalAppearanceScreen.this.width,tooltip));
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
        button.setTooltip(ModelUiTooltip.of(textRenderer,LocalAppearanceScreen.this.width,tooltip));return addDrawableChild(button);
    }
    private ButtonWidget cloudButton(String label,int x,int y,int w,Runnable action,BooleanSupplier selected,
                                     BooleanSupplier enabled,Supplier<String> tooltip) {
        return cloudButton(label,x,y,w,action,selected,enabled,tooltip,()->selected.getAsBoolean()?"已上传":label);
    }
    private ButtonWidget cloudButton(String label,int x,int y,int w,Runnable action,BooleanSupplier selected,
                                     BooleanSupplier enabled,Supplier<String> tooltip,Supplier<String> caption) {
        var button=new ButtonWidget(x,y,Math.max(20,w),20,Text.literal(label),b->action.run(),narration->narration.get()) {
            @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
                active=enabled.getAsBoolean();setTooltip(ModelUiTooltip.of(textRenderer,LocalAppearanceScreen.this.width,tooltip.get()));
                setMessage(net.minecraft.text.Text.literal(caption.get()));
                context.fill(getX(),getY(),getRight(),getBottom(),selected.getAsBoolean()?-14774017:-12369342);
                if(hovered || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-790560);
                CloudUploadIcon.draw(context,getX()+2,getY()+2,active?0xfff3f0e0:0xff8a929c,selected.getAsBoolean());
                if(getWidth()>24)clipped(context,getMessage().getString(),getX()+20,getY()+6,getWidth()-22,active?0xfff3f0e0:0xff8a929c);
            }
        };
        button.active=enabled.getAsBoolean();button.setTooltip(ModelUiTooltip.of(textRenderer,LocalAppearanceScreen.this.width,tooltip.get()));
        return addDrawableChild(button);
    }
    private boolean canUploadModel(String id) {
        var entry=previews.get(id);
        return localGallery()&&!id.isEmpty()&&runtime.canShareLocalModel()&&entry!=null&&entry.loaded!=null
                &&!runtime.privateModelUploadState(id).inProgress();
    }
    private boolean canUseCloudAction(String id) {
        if(id.isEmpty()||runtime.deletingUploadedPrivateModel(id))return false;
        if(storedPrivateModel(id)!=null)return runtime.privateUploadCatalogReady();
        return runtime.privateModelUploadState(id).published()||canUploadModel(id);
    }
    private PrivateUploadCatalogSnapshot.Model storedPrivateModel(String id) {
        return runtime.uploadedPrivateModels().stream().filter(model->model.modelId().equals(id)).findFirst().orElse(null);
    }
    private String cloudModelHint(String id) {
        if(!uploadedOnly||storedPrivateModel(id)==null||runtime.privateModelUploadState(id).uploaded())return "";
        if(!runtime.privateUploadCatalogReady())return "云端已保存 · 正在核对本机文件";
        return localSourceIds.contains(id)?"云端已保存 · 本机文件内容不同":"云端已保存 · 本机未找到模型";
    }
    private String uploadTooltip(String id) {
        if(runtime.deletingUploadedPrivateModel(id))return "删除中\n等待服务器确认\n本地文件将保留";
        if(storedPrivateModel(id)!=null)return "服务器已保存\n点击删除这个模型的服务器存档\n包含该模型的已保存旧版本\n会结束相关私人分享\n本地文件将保留\n"
                +(runtime.privateModelUploadState(id).uploaded()?"本机内容一致":localSourceIds.contains(id)?"本机文件内容不同":"本机未找到模型")
                +(runtime.privateUploadDeleteStatus(id).isEmpty()?"":"\n"+runtime.privateUploadDeleteStatus(id));
        if(runtime.privateModelUploadState(id).published())return "已分享，尚未确认服务器保存\n点击结束私人分享\n保留本地文件与服务器缓存";
        if(!runtime.canActivateLocalAppearance())return "请先解除服务器伪装\n私人模型上传与服务器伪装不能同时使用";
        if(!runtime.canShareLocalModel())return "当前无法上传私人模型\n"+runtime.privateSyncStatus();
        if(id.isEmpty())return "先选择私人模型";
        var state=runtime.privateModelUploadState(id);
        return "使用此模型并上传分享\n获准的模组玩家可见\n原版玩家看见原版人物\n"
                +state.message()+(state.uploaded()?"\n服务器已保存；点击可再次分享":"");
    }
    private void uploadSelectedModel() {
        String id=selection.modelId();
        if(!canUseCloudAction(id)){message=uploadTooltip(id);return;}
        var expected=storedPrivateModel(id);
        if(expected!=null) {
            client.setScreen(new ConfirmScreen(confirmed->{
                if(confirmed) {
                    if(runtime.requestDeleteUploadedPrivateModel(expected)) {
                        observedDeleteId=id;observedDeleteStatus=runtime.privateUploadDeleteStatus(id);message="删除请求已提交，等待服务器确认";
                    } else {
                        message=runtime.privateUploadDeleteStatus(id);
                        if(message.isEmpty())message="当前无法删除服务器资源，请稍后重试";
                    }
                }
                client.setScreen(this);
            },Text.literal("删除服务器模型？"),Text.literal("删除 "+label(id)+" 的服务器存档及已保存旧版本。\n相关私人分享会结束，本地模型文件保留。"),
                    Text.literal("删除服务器资源"),Text.literal("取消")));
            return;
        }
        var shared=runtime.privateModelUploadState(id);
        if(shared.published()) {
            client.setScreen(new ConfirmScreen(confirmed->{
                if(confirmed) {
                    var current=runtime.privateModelUploadState(id);
                    if(!current.published() || !current.hash().equals(shared.hash()))message="私人分享已变化，请返回后重新确认";
                    else if(runtime.setPrivateSyncEnabled(false))message="已结束私人分享，本地模型与服务器缓存保留";
                    else message="未能结束私人分享，请重试";
                }
                client.setScreen(this);
            },Text.literal("结束私人分享？"),Text.literal("其他玩家将不再显示你的私人模型。\n本地模型文件和已保存的服务器缓存保留。"),Text.literal("结束分享"),Text.literal("取消")));
            return;
        }
        if(!canUploadModel(id)){message=uploadTooltip(id);return;}
        message=runtime.uploadAndShareLocalModel(id)?"":"当前无法上传私人模型";
        updateSelectionButtons();
    }

    private void openServerCacheDirectory() {
        try {
            java.nio.file.Files.createDirectories(runtime.localServerCacheDirectory());
            Util.getOperatingSystem().open(runtime.localServerCacheDirectory());
            message="已打开本机服务器模型缓存";
        } catch(java.io.IOException | SecurityException unavailable) {message="无法打开本机服务器模型缓存目录";}
    }

    private void refreshModels(boolean clear) {
        if (clear) { generation++; previews.clear(); inFlight = 0; preview.clear(); }
        models = List.copyOf(galleryModels());
        if(localGallery()) {
            localSourceIds=Set.copyOf(models.stream().map(ClientRuntime.Action::id).toList());
            if(uploadedOnly) {
                var byId=new LinkedHashMap<String,ClientRuntime.Action>();models.forEach(model->byId.put(model.id(),model));
                runtime.uploadedPrivateModels().forEach(model->byId.putIfAbsent(model.modelId(),new ClientRuntime.Action(model.modelId(),model.modelId())));
                models=List.copyOf(byId.values());
            }
        }
        selection.retain(models.stream().map(ClientRuntime.Action::id).toList());
    }

    private void rebuildGrid() {
        if(!clientGalleryVisible())return;
        for (var widget : gridWidgets) remove(widget);
        gridWidgets.clear();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        filtered = models.stream().filter(model -> (!favoritesOnly || isFavoriteModel(model.id()))
                && (!uploadedOnly || storedPrivateModel(model.id())!=null)
                && (!localGallery() || sourceFilter == 0 || source(model.id()) == sourceFilter)
                && (!localGallery() || groupSource == 0 || source(model.id()) == groupSource)
                && (!localGallery() || groupSource == 0 || ModelGalleryIndex.contains(groupPath(),model.id()))
                && (model.id().toLowerCase(Locale.ROOT).contains(needle) || label(model.id()).toLowerCase(Locale.ROOT).contains(needle)
                || model.label().toLowerCase(Locale.ROOT).contains(needle))).toList();
        var groups=new ArrayList<ModelGroup>();
        if(localGallery()&&groupSource==0 && sourceFilter==0 && !favoritesOnly && !uploadedOnly && needle.isEmpty())for(int kind=1;kind<=3;kind++) {
            int type=kind,count=(int)models.stream().filter(model->source(model.id())==type).count();
            if(count>0)groups.add(new ModelGroup(type,new String[]{"","builtin/","ysm/","bbmodel/"}[type],
                    new String[]{"","内置模型","YSM 模型","BBModel 文件"}[type],count));
        }
        if(localGallery()&&groupSource>0 && !favoritesOnly && !uploadedOnly && needle.isEmpty()) {
            for(var folder:ModelGalleryIndex.folders(filtered.stream().map(ClientRuntime.Action::id).toList(),groupPath()))
                groups.add(new ModelGroup(groupSource,folder.path(),folder.label(),folder.count()));
            filtered=filtered.stream().filter(model->ModelGalleryIndex.direct(groupPath(),model.id())).toList();
        }
        filteredGroups=List.copyOf(groups);
        page = Math.max(0, Math.min(page, pageCount() - 1));
        int start = page * pageSize;
        int end=start+pageSize,modelStart=Math.max(0,start-filteredGroups.size()),modelEnd=Math.max(0,end-filteredGroups.size());
        visibleGroups=filteredGroups.subList(Math.min(start,filteredGroups.size()),Math.min(end,filteredGroups.size()));
        visible = filtered.subList(Math.min(modelStart, filtered.size()), Math.min(modelEnd, filtered.size()));
        int cellW = CARD_WIDTH;
        int cellH = CARD_HEIGHT;
        for(int i=0;i<visibleGroups.size();i++) {
            var card=new GroupCard(visibleGroups.get(i),right+5+i%columns*(cellW+CARD_GAP),gridTop+i/columns*(cellH+CARD_GAP),cellW,cellH);
            gridWidgets.add(addDrawableChild(card));
        }
        for (int i = 0; i < visible.size(); i++) {
            int slot=i+visibleGroups.size();
            var card = new ModelCard(visible.get(i), right + 5 + slot % columns * (cellW + CARD_GAP), gridTop + slot / columns * (cellH + CARD_GAP), cellW, cellH);
            gridWidgets.add(addDrawableChild(card));
        }
        int navWidth = Math.min(62, (rightWidth - 58) / 2);
        var previous = button("上一页", right + 5, bottom - 21, navWidth, () -> {goToPage(page-1);}, "浏览上一页模型");
        previous.active = page > 0; gridWidgets.add(previous);
        var next = button("下一页", right + rightWidth - navWidth - 5, bottom - 21, navWidth, () -> {goToPage(page+1);}, "浏览下一页模型");
        next.active = page + 1 < pageCount(); gridWidgets.add(next);

        Set<String> retained = new HashSet<>();
        if (localGallery()&&runtime.localAppearance().enabled()) retained.add(runtime.localAppearance().modelId());
        if (!selection.modelId().isEmpty()) retained.add(selection.modelId());
        visible.forEach(model -> retained.add(model.id()));
        previews.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) return false;
            if (entry.getValue().loaded != null) preview.release(key(entry.getKey(), entry.getValue().loaded));
            return true;
        });
        if (!selection.modelId().isEmpty()) previews.computeIfAbsent(selection.modelId(), ignored -> new PreviewEntry());
        if (localGallery()&&runtime.localAppearance().enabled()) previews.computeIfAbsent(runtime.localAppearance().modelId(), ignored -> new PreviewEntry());
        visible.forEach(model -> previews.computeIfAbsent(model.id(), ignored -> new PreviewEntry()));
        updateSelectionButtons();
        pumpLoads();
    }

    /** These accessors also let the GUI harness assert selection/filter/page behavior without applying models. */
    public String selectedModelId() { return selection.modelId(); }
    public List<String> visibleModelIds() { return visible.stream().map(ClientRuntime.Action::id).toList(); }
    public int pageIndex() { return page; }
    public int pageCount() { return Math.max(1, (filtered.size()+filteredGroups.size() + Math.max(1, pageSize) - 1) / Math.max(1, pageSize)); }
    public void setSearchQuery(String value) { if (search != null) search.setText(value); else query = value; }
    public void setFavoritesOnly(boolean value) { favoritesOnly = value; page = 0; clearAndInit(); }
    public void goToPage(int index) { page = index; rebuildGrid();groupPages.put(groupPath(),page); }
    public String groupPath() {return groupSource==0?"":ModelGalleryIndex.root(groupSource)+groupDirectory;}
    public void browseGroup(int source) {
        if(source<1 || source>3 || models.stream().noneMatch(model->source(model.id())==source))return;
        groupPages.put(groupPath(),page);groupSource=source;groupDirectory="";sourceFilter=0;query="";
        page=groupPages.getOrDefault(groupPath(),0);clearAndInit();
    }
    private void browseGroup(ModelGroup group) {
        groupPages.put(groupPath(),page);groupSource=group.source();
        groupDirectory=group.path().substring(ModelGalleryIndex.root(groupSource).length());sourceFilter=0;query="";
        page=groupPages.getOrDefault(groupPath(),0);clearAndInit();
    }
    public void navigateUp() {
        if(groupSource==0)return;
        groupPages.put(groupPath(),page);
        if(groupDirectory.isEmpty())groupSource=0;
        else groupDirectory=ModelGalleryIndex.parent(groupPath()).substring(ModelGalleryIndex.root(groupSource).length());
        page=groupPages.getOrDefault(groupPath(),0);clearAndInit();
    }
    public void toggleSelectedFavorite() {
        if (selection.modelId().isEmpty()) return;
        toggleModelFavorite(selection.modelId()); rebuildGrid();
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
        result.put("panel",Map.of("x",left,"y",frameTop,"width",panelWidth,"height",frameBottom-frameTop,
                "columns",columns,"rows",rows,"compact",compactLayout));
        result.put("search",query);result.put("favoritesOnly",favoritesOnly);result.put("uploadedOnly",uploadedOnly);result.put("sourceFilter",sourceFilter);
        result.put("page",page);result.put("pageCount",pageCount());result.put("selectedModelId",selection.modelId());
        result.put("leftPreview",Map.of("x",previewX,"y",previewY,"width",previewW,"height",previewH,
                "modelId",leftPreviewId,"key",leftPreviewKey,"drawn",leftPreviewDrawn,
                "appearanceSource",leftPreviewSource,"instance",leftPreviewInstance,"hash",leftPreviewHash));
        result.put("cards",gridWidgets.stream().filter(widget->widget instanceof ModelCard).map(widget->{
            var card=(ModelCard)widget;var entry=previews.get(card.model.id());var info=new LinkedHashMap<String,Object>();
            info.put("modelId",card.model.id());info.put("label",card.model.label());info.put("x",card.getX());info.put("y",card.getY());
            info.put("width",card.getWidth());info.put("height",card.getHeight());info.put("selected",selection.modelId().equals(card.model.id()));
            info.put("favorite",isFavoriteModel(card.model.id()));info.put("loaded",entry!=null&&entry.loaded!=null);
            if(localGallery())info.put("upload",runtime.privateModelUploadState(card.model.id()).phase().name());
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
        selection.browse(id); message = galleryBrowsingMessage(); yaw = 0; pitch = -8;
        var entry = previews.get(id);
        if (entry != null && entry.loaded != null) preview.restart(key(id, entry.loaded), ModelPreview.Context.SELECTED);
        rebuildGrid(); return true;
    }
    public void useSelectedModel() {
        if (selection.modelId().isEmpty()) return;
        if(!runtime.canActivateLocalAppearance()){message="请先解除服务器伪装，再使用私人模型";return;}
        var entry = previews.get(selection.modelId());
        if (entry == null || entry.loaded == null) { message = "请等待模型预览加载完成"; return; }
        if(!galleryUseState(selection.modelId(),entry.loaded).canUse())return;
        runtime.setPrivateSyncEnabled(false);
        runtime.selectLocalModel(selection.modelId());
        runtime.options.showSelf = true; runtime.options.save();
        message = "";
        updateSelectionButtons();
    }

    protected void updateSelectionButtons() {
        if (use == null) return;
        var entry = previews.get(selection.modelId());
        var state=galleryUseState(selection.modelId(),entry==null?null:entry.loaded);
        use.active=state.canUse();use.setMessage(Text.literal(state.buttonLabel()));
        use.setTooltip(ModelUiTooltip.of(textRenderer,LocalAppearanceScreen.this.width,galleryUseTooltip(state)));
        if(settings!=null){settings.active=!selection.modelId().isEmpty()&&(!uploadedOnly||runtime.privateModelUploadState(selection.modelId()).uploaded());settings.setTooltip(ModelUiTooltip.of(textRenderer,LocalAppearanceScreen.this.width,
                settings.active?"查看模型详情\n设置缩放、位置与作者配置":"无法配置此服务器存档\n需要本机存在相同内容的模型"));}
        if(favorite!=null) {
            favorite.active=!selection.modelId().isEmpty();
            String favoriteText=isFavoriteModel(selection.modelId())?"★ 已收藏":"☆ 收藏";
            if(favorite.getWidth()<50)favoriteText=isFavoriteModel(selection.modelId())?"★":"☆";
            favorite.setMessage(Text.literal(textRenderer.trimToWidth(favoriteText,favorite.getWidth()-8)));
        }
    }

    private void pumpLoads() {
        if (!activeView || (localGallery()&&!runtime.canEditLocalAppearance()) || inFlight >= MAX_LOADS) return;
        List<String> order = new ArrayList<>();
        if (clientGalleryVisible() && !selection.modelId().isEmpty()) {
            previews.computeIfAbsent(selection.modelId(), ignored -> new PreviewEntry());
            order.add(selection.modelId());
        }
        if (localGallery()&&runtime.localAppearance().enabled()) {
            String currentId = runtime.localAppearance().modelId();
            previews.computeIfAbsent(currentId, ignored -> new PreviewEntry());
            if (!order.contains(currentId)) order.add(currentId);
        }
        if (!clientGalleryVisible() && order.isEmpty()) return;
        if (clientGalleryVisible()) for (var model : visible) if (!order.contains(model.id())) order.add(model.id());
        for (String id : order) {
            if (inFlight >= MAX_LOADS) break;
            PreviewEntry entry = previews.get(id);
            if (entry == null || entry.requested) continue;
            entry.requested = true; inFlight++; long requestGeneration = generation;
            var selectionRequest = selection.request(id);
            loadGalleryPreview(id).whenComplete((loaded, error) -> client.execute(() -> {
                if (!activeView || generation != requestGeneration) return;
                inFlight--;
                if (previews.get(id) == entry) {
                    entry.loaded = loaded; entry.error = error == null ? "" : loadError(error);
                    if (loaded != null && selection.isSelectedRequest(selectionRequest))
                        preview.restart(key(id, loaded), ModelPreview.Context.SELECTED);
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
    static int source(String id) { return ModelGalleryIndex.source(id); }
    static String sourceLabel(String id) { return switch (source(id)) { case 2 -> "本地 YSM"; case 3 -> "本地 BBModel"; default -> "内置"; }; }
    private static String key(String id, LocalModelLibrary.Loaded loaded) { return id + ":" + loaded.hash(); }
    private YsmModelProfile profile(String id) {
        var entry=previews.get(id);
        return entry!=null && entry.loaded!=null?entry.loaded.profile():galleryProfile(id);
    }
    private String label(String id) {
        String fallback=models.stream().filter(model -> model.id().equals(id)).map(ClientRuntime.Action::label).findFirst().orElse(id);
        return profile(id).localized(client==null?"zh_cn":client.getLanguageManager().getLanguage(),"metadata.name",fallback);
    }
    protected void clipped(DrawContext context, String text, int x, int y, int w, int color) {
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(text, Math.max(0, w)), x, y, color);
    }

    @Override public void tick() {
        if (requiresLocalSource() && !runtime.canEditLocalAppearance()) { client.setScreen(new PlayerModelScreen(runtime)); return; }
        if(localGallery()) {
            Set<String> uploads=runtime.uploadedPrivateModelIds();
            List<PrivateUploadCatalogSnapshot.Model> saved=runtime.uploadedPrivateModels();
            if(!uploads.equals(confirmedUploads)||!saved.equals(savedUploads)) {
                confirmedUploads=Set.copyOf(uploads);savedUploads=List.copyOf(saved);
                if(uploadedOnly){clearGalleryPreviews();refreshModels(false);rebuildGrid();}
            }
            if(!observedDeleteId.isEmpty()) {
                String status=runtime.privateUploadDeleteStatus(observedDeleteId);
                if(!status.equals(observedDeleteStatus)){observedDeleteStatus=status;message=status;}
                if(!runtime.deletingUploadedPrivateModel(observedDeleteId)) {
                    if(status.isEmpty())message="未收到服务器删除确认，请重新连接后查看存档";
                    observedDeleteId=observedDeleteStatus="";
                }
            }
        }
        updateSelectionButtons();
        ticks++; pumpLoads();
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        preview.beginFrame(context);
        drawnPreviewCount = 0;leftPreviewDrawn=false;leftPreviewId="";leftPreviewKey="";drawnCards.clear();
        leftPreviewSource="native-player";leftPreviewInstance="";leftPreviewHash="";
        context.fill(left - 4, frameTop - 2, left + panelWidth + 4, frameBottom + 2, 0xCF10171F);
        renderHeader(context);
        context.fill(left, top, left + previewWidth, bottom, 0xE6222730);
        context.fill(right, top, right + rightWidth, bottom, 0xE6222730);
        if(clientGalleryVisible())renderGallery(context,delta);
        else renderAlternateContent(context,delta);
        super.render(context, mouseX, mouseY, delta);
    }
    protected void renderHeader(DrawContext context) {
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xfff3f6ff);
        if (!compactLayout) context.drawCenteredTextWithShadow(textRenderer, "本地模型 · 分享在玩家模型主页主动开启", width / 2, titleY + 16, 0xffbccce0);
    }
    protected void renderAlternateContent(DrawContext context,float delta) { }
    private void renderGallery(DrawContext context,float delta) {
        renderSelectedPreview(context,delta);
        if (visible.isEmpty() && visibleGroups.isEmpty()) clipped(context, uploadedOnly
                ?runtime.privateUploadCatalogReady()?"暂无匹配的已上传模型":runtime.privateUploadCatalogStatus()
                :favoritesOnly?"暂无符合条件的收藏":"没有符合条件的模型",right+8,gridTop+20,rightWidth-16,0xffbccce0);
        context.drawCenteredTextWithShadow(textRenderer, (page + 1) + " / " + pageCount(), right + rightWidth / 2, bottom - 15, 0xffe6ecf4);
        String status = message.isEmpty() ? galleryStatus() : message;
        clipped(context, status, left + footerStatusInset(), footerY + 6, panelWidth - footerStatusInset(), 0xffffd589);
    }
    protected void renderSelectedPreview(DrawContext context,float delta) {
        boolean currentLocal=runtime.currentAppearanceSource()==ClientRuntime.AppearanceSource.CLIENT;
        if(localGallery() && !currentLocal && !selection.modelId().isEmpty()) {
            renderBrowsingPreview(context,delta,selection.modelId(),previews.get(selection.modelId()));
            return;
        }
        ClientRuntime.GuiPreviewAppearance appearance = runtime.guiPreviewAppearance();
        if(currentLocal && appearance==null) {
            String id=runtime.currentAppearanceModelId(),hash=runtime.currentAppearanceAssetHash();
            var entry=previews.get(id);
            if(entry!=null && entry.loaded!=null && entry.loaded.hash().equals(hash))
                appearance=new ClientRuntime.GuiPreviewAppearance(id,"gallery-current:"+id,hash,entry.loaded.model(),entry.loaded.profile());
        }
        var selectedEntry = previews.get(selection.modelId());
        var target = selection.target(appearance == null ? "" : appearance.modelId(),
                appearance == null ? "" : appearance.assetHash(),
                selectedEntry == null || selectedEntry.loaded == null ? null : selectedEntry.loaded.hash());
        if (target.source() == GalleryPreviewSelection.Source.SELECTED) {
            renderBrowsingPreview(context, delta, target.modelId(), selectedEntry);
            return;
        }
        if (target.source() == GalleryPreviewSelection.Source.VANILLA || appearance == null) {
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

    private void renderBrowsingPreview(DrawContext context, float delta, String id, PreviewEntry entry) {
        leftPreviewId = id; leftPreviewSource = "selected-preview";
        clipped(context, label(id), left + 6, top + 6, previewWidth - 12, 0xfff3f6ff);
        var state=galleryUseState(id,entry==null?null:entry.loaded);
        String status=!cloudModelHint(id).isEmpty()?"云端存档 · 本机内容不匹配":state.current()?"当前使用":state.waiting()?"正在加载所选模型":runtime.canActivateLocalAppearance()
                ?"预览 · 点击使用模型应用":"仅预览 · 请先解除服务器伪装";
        clipped(context,status,left+6,top+17,previewWidth-12,0xff92b9df);
        if (entry == null || entry.loaded == null) {
            String unavailable = entry != null && !entry.error.isEmpty() ? "预览加载失败：" + entry.error : galleryPreviewUnavailableText(id);
            clipped(context, unavailable, previewX + 3, previewY + previewH / 2, previewW - 6, 0xffffc685);
            return;
        }
        leftPreviewKey = key(id, entry.loaded); leftPreviewHash = entry.loaded.hash();
        var profile = entry.loaded.profile();
        // The upstream PlayerTextureScreen owns a separate PlayerPreviewEntity. A browsing draft
        // uses its own dummy/cap clock, never the live owner's model, gear, manual action or network hook.
        leftPreviewDrawn = preview.render(context, entry.loaded.model(), leftPreviewKey, previewX, previewY, previewW, previewH,
                yaw, pitch, ticks + delta, profile.isYsm() ? parameters(id) : Map.of(), entry.loaded.previewAnimation(),
                profile, ModelPreview.Context.SELECTED);
        if (leftPreviewDrawn) drawnPreviewCount++;
        else clipped(context, "所选模型预览暂不可用", previewX + 3, previewY + previewH / 2, previewW - 6, 0xffffc685);
        if (showRotationHint()) clipped(context, rotationDisabled() ? "作者固定正面视角" : "拖动旋转 · 双击回正",
                left + 6, bottom - 57, previewWidth - 12, 0xffa7b5c8);
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
            // OpenYSM PlayerTextureScreen.mouseDragged/adjustPitch: screen Y is inverted.
            yaw = (yaw + (float) dx * 1.5f) % 360; pitch = Math.max(-90, Math.min(90, pitch - (float) dy)); return true;
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
            super(x,y,w,h,net.minecraft.text.Text.literal(group.label()),button->browseGroup(group),DEFAULT_NARRATION_SUPPLIER);this.group=group;
            setTooltip(ModelUiTooltip.of(textRenderer,LocalAppearanceScreen.this.width,group.label()+" · "+group.count()+" 个模型\n"+group.path()+"\n点击进入模型目录"));
        }
        @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
            context.fill(getX(),getY(),getRight(),getBottom(),hovered?0xff394b62:0xff303d4e);
            context.drawTexture(RenderPipelines.GUI_TEXTURED,PACK_ICON,getX(),getY(),0f,0f,getWidth(),getHeight(),52,90,52,90);
            if(hovered || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-1982745);
            drawCardLabel(context, getMessage(), getX(), getBottom(), 0xff555555, false);
        }
    }
    private final class ModelCard extends ButtonWidget {
        private final ClientRuntime.Action model;
        private String tooltipText="";
        ModelCard(ClientRuntime.Action model, int x, int y, int w, int h) {
            super(x, y, w, h, net.minecraft.text.Text.literal(model.label()), button -> browseModel(model.id()), DEFAULT_NARRATION_SUPPLIER);
            this.model = model;
            refreshLabel(-1,-1);
        }
        private void refreshLabel(int mouseX,int mouseY) {
            String label=label(model.id());
            setMessage(net.minecraft.text.Text.literal(label));
            String description=profile(model.id()).localized(client==null?"zh_cn":client.getLanguageManager().getLanguage(),"metadata.description","");
            var target=isMouseOver(mouseX,mouseY)?ModelCardActionRegions.target(mouseX-getX(),mouseY-getY(),getWidth(),galleryCardCloudVisible()):ModelCardActionRegions.Target.BROWSE;
            String tooltip=target==ModelCardActionRegions.Target.UPLOAD?galleryCardCloudTooltip(model.id())
                    :target==ModelCardActionRegions.Target.FAVORITE?(isFavoriteModel(model.id())?"取消收藏":"收藏模型")+"\n"+label
                    :label+"\n"+gallerySourceLabel(model.id())+"\n"+model.id()+(description.isBlank()?"":"\n"+description)
                    +"\n点击预览\n右上角收藏"+(localGallery()?"\n左上角上传／管理服务器存档":galleryCardCloudVisible()?"\n左上角查看资源缓存与接管状态":"");
            if(!tooltip.equals(tooltipText)){tooltipText=tooltip;setTooltip(ModelUiTooltip.of(textRenderer,LocalAppearanceScreen.this.width,tooltip));}
        }
        @Override public void onClick(Click click, boolean doubled) {
            switch(ModelCardActionRegions.target(click.x()-getX(),click.y()-getY(),getWidth(),galleryCardCloudVisible())) {
                case FAVORITE -> {toggleModelFavorite(model.id());rebuildGrid();}
                case UPLOAD -> galleryCardCloudClicked(model.id());
                case BROWSE -> super.onClick(click,doubled);
            }
        }
        // PressableWidget.renderWidget is final: drawIcon owns the entire card, without drawButton's background.
        @Override protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
            refreshLabel(mouseX,mouseY);
            context.fill(getX(), getY(), getRight(), getBottom(), -12369342);
            var entry = previews.get(model.id());
            int imageHeight = Math.max(8, getHeight() - 20);
            if (entry != null && entry.loaded != null) {
                boolean nativeCard = NativeGuiPreviewCamera.appliesTo(entry.loaded.profile());
                boolean rendered;
                if (nativeCard) {
                    // ModelPreview applies the 70px crop to model geometry only; the author's
                    // background/foreground cover the full 52×90 card like ModelButton.
                    rendered = preview.render(context, entry.loaded.model(), key(model.id(), entry.loaded), getX(), getY(),
                            getWidth(), CARD_RENDER_HEIGHT, 0, 0, ticks + delta, parameters(model.id()),
                            entry.loaded.previewAnimation(), entry.loaded.profile(), ModelPreview.Context.CARD);
                } else rendered = preview.render(context, entry.loaded.model(), key(model.id(), entry.loaded), getX() + 2, getY() + 2,
                        getWidth() - 4, imageHeight - 2, 0, -8, ticks + delta, parameters(model.id()), entry.loaded.previewAnimation(), entry.loaded.profile(), ModelPreview.Context.CARD);
                if (rendered) {drawnPreviewCount++;drawnCards.add(model.id());}
                else clipped(context, "预览不可用", getX() + 5, getY() + imageHeight / 2, getWidth() - 10, 0xffffc685);
            } else clipped(context,!cloudModelHint(model.id()).isEmpty()?localSourceIds.contains(model.id())?"文件已改":"本地未找到"
                    :entry!=null&&!entry.error.isEmpty()?"加载失败":localGallery()?"加载中…":"服务端模型",getX()+5,getY()+imageHeight/2,getWidth()-10,0xffc1cedc);
            drawCardLabel(context, net.minecraft.text.Text.literal(runtime.options.showModelIds ? model.id() : getMessage().getString()),
                    getX(), getBottom(), 0xfff3f0e0, true);
            if(selection.modelId().equals(model.id()) || hovered || isFocused())context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), -790560);
            context.drawTexture(RenderPipelines.GUI_TEXTURED,GUI_ICONS,getRight()-16,getY(),isFavoriteModel(model.id())?16f:0f,0f,16,16,256,256);
            if(galleryCardCloudVisible())drawGalleryCardCloud(context,model.id(),getX()+1,getY()+1);
        }
    }

    /** ModelButton / PackIconButton use 45px wrapping within the same 52px author frame. */
    private void drawCardLabel(DrawContext context, Text label, int x, int bottom, int color, boolean shadow) {
        var lines = textRenderer.wrapLines(label, 45);
        int lineCount = Math.min(2, lines.size());
        for (int i = 0; i < lineCount; i++) {
            var line = lines.get(i);
            int y = lineCount > 1 ? bottom - 19 + i * 9 : bottom - 15;
            context.drawText(textRenderer, line, x + CARD_WIDTH / 2 - textRenderer.getWidth(line) / 2, y, color, shadow);
        }
    }

}
