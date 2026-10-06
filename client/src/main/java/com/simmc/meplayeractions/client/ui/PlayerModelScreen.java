package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Direct gallery home adapted from OpenYSM-Updated PlayerModelScreen/ModelButton/IconButton,
 * revision 0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85 (MIT; see THIRD_PARTY_NOTICES).
 * Client and server tabs share the same source gallery; only an explicit use button applies an appearance.
 */
public final class PlayerModelScreen extends LocalAppearanceScreen {
    private static final Identifier SETTINGS_ICON=Identifier.of("meplayeractions","textures/gui/settings.png");
    private final Map<ButtonWidget,String> roles=new IdentityHashMap<>();
    private final Set<String> serverFavorites=new HashSet<>();
    private boolean clientTab,advanced,initializedLocalMode;
    private String initializedInstance="";
    private long initializedCatalogRevision=Long.MIN_VALUE;
    private Map<String,String> initializedServerPreviews=Map.of();
    private Map<String,String> serverLabels=Map.of();

    public PlayerModelScreen(ClientRuntime runtime) {this(runtime,null);}
    public PlayerModelScreen(ClientRuntime runtime,Screen parent) {
        super(runtime,parent,Text.literal("玩家模型"));clientTab=runtime.interactionLocalMode();
    }

    @Override protected void init() {
        // Returning from a child screen must compare the previous snapshot before recording the new one.
        boolean localMode=runtime.interactionLocalMode();String instance=runtime.serverOwnModelInstance();
        if(initializedLocalMode!=localMode || !initializedInstance.equals(instance)) {
            clientTab=localMode;advanced=false;resetGallery();
        }
        initializedLocalMode=localMode;initializedInstance=instance;roles.clear();
        super.init();
        initializedCatalogRevision=runtime.serverCatalogRevision();
        initializedServerPreviews=serverPreviewAssets();
    }
    @Override protected int galleryHeaderHeight(boolean compact) {
        return compact ? GalleryPanelLayout.HOME_COMPACT_HEADER_HEIGHT : GalleryPanelLayout.HOME_HEADER_HEIGHT;
    }
    @Override protected boolean clientGalleryVisible() {return !advanced;}
    @Override protected boolean localGallery() {return clientTab;}
    @Override protected List<ClientRuntime.Action> galleryModels() {
        if(clientTab)return super.galleryModels();
        var models=runtime.serverCatalog();var labels=new LinkedHashMap<String,String>();
        models.forEach(model->labels.put(model.id(),model.label()));serverLabels=Map.copyOf(labels);return models;
    }
    @Override protected CompletableFuture<LocalModelLibrary.Loaded> loadGalleryPreview(String id) {
        return clientTab?super.loadGalleryPreview(id):CompletableFuture.completedFuture(runtime.serverModelForPreview(id));
    }
    @Override protected YsmModelProfile galleryProfile(String id) {
        if(clientTab)return super.galleryProfile(id);
        var loaded=runtime.serverModelForPreview(id);return loaded==null?YsmModelProfile.empty():loaded.profile();
    }
    @Override protected String gallerySourceLabel(String id) {return clientTab?super.gallerySourceLabel(id):"服务器模型";}
    @Override protected String galleryStatus() {return clientTab?super.galleryStatus():runtime.serverCatalogStatus();}
    @Override protected String galleryPreviewUnavailableText(String id) {
        return clientTab?super.galleryPreviewUnavailableText(id):"使用模型后由服务器下发资源";
    }
    @Override protected boolean canUseGalleryModel(String id,LocalModelLibrary.Loaded loaded) {
        return clientTab?super.canUseGalleryModel(id,loaded):runtime.canRequestServerDisguise(id);
    }
    @Override protected String galleryUseTooltip() {
        return clientTab?super.galleryUseTooltip():"使用当前服务器模型；资源由服务器下发，其他玩家看到服务器伪装";
    }
    @Override protected String galleryBrowsingMessage() {
        return clientTab?super.galleryBrowsingMessage():"浏览服务器模型；点击使用模型才伪装";
    }
    @Override protected boolean isFavoriteModel(String id) {return clientTab?super.isFavoriteModel(id):serverFavorites.contains(id);}
    @Override protected void toggleModelFavorite(String id) {
        if(clientTab)super.toggleModelFavorite(id);else if(!serverFavorites.remove(id))serverFavorites.add(id);
    }
    @Override protected void buildSelectionControls() {
        if(clientTab){super.buildSelectionControls();return;}
        settings=favorite=null;
        use=button("使用模型",left+5,bottom-44,previewWidth-10,this::useSelectedModel,galleryUseTooltip());
        roles.put(use,"useServerModel");
    }
    @Override public void useSelectedModel() {
        if(clientTab){super.useSelectedModel();return;}
        String id=selectedModelId();
        if(!runtime.canRequestServerDisguise(id)){setGalleryMessage(runtime.serverCatalogStatus());return;}
        setGalleryMessage(runtime.requestServerDisguise(id)?"已发送伪装请求，等待服务器确认":"无法发送伪装请求，请稍后重试");
    }
    @Override protected boolean requiresLocalSource() {return false;}
    @Override protected boolean showRotationHint() {return false;}
    @Override protected String settingsButtonLabel(int buttonWidth) {return "外观设置…";}

    @Override protected void buildHeaderControls() {
        int sourceOffset=compactLayout?20:29;
        int half=(panelWidth-6)/2,y=top-sourceOffset;
        int sourceHeight=compactLayout?18:24;
        roles.put(flatButton(clientTab?"客户端 ✓":"客户端",left,y,half,sourceHeight,()->switchSource(true),
                "本地模型图库与私人设置；使用模型仅本机可见，云朵按钮可主动上传分享",clientTab),"clientSource");
        roles.put(flatButton(clientTab?"服务端":"服务端 ✓",left+half+6,y,panelWidth-half-6,sourceHeight,()->switchSource(false),
                "浏览服务器允许使用的模型；点击使用后由服务器确认伪装并下发资源",!clientTab),"serverSource");
    }

    @Override protected void buildFooterControls() {
        roles.put(button("返回",left,footerY,46,this::close,"返回上一页"),"back");
        // Like OpenYSM ConfigCheckBoxForge / BooleanOptionRow, visible selection
        // comes from the actual option; the label also identifies each separate switch.
        boolean serverDisguise = runtime.serverOwnModelPresent();
        int toggleX = left + 50, toggleY = footerY;
        boolean hidePlayer = serverDisguise || runtime.options.hideVanillaPlayer;
        var vanillaPlayer = flatButton(hidePlayer?"玩家隐藏":"玩家显示",toggleX,toggleY,44,20,()->{
            if (runtime.serverOwnModelPresent()) return;
            runtime.options.hideVanillaPlayer=!runtime.options.hideVanillaPlayer;runtime.options.save();clearAndInit();
        },serverDisguise?"服务器伪装期间，原版玩家本体保持隐藏；伪装模型单独控制"
                :"只切换原版玩家本体；装备和伪装模型分别控制",hidePlayer);
        vanillaPlayer.active = !serverDisguise;
        roles.put(vanillaPlayer,"vanillaPlayer");
        boolean hideEquipment = serverDisguise || runtime.options.hideVanillaEquipment;
        var vanillaEquipment = flatButton(hideEquipment?"装备隐藏":"装备显示",toggleX+48,toggleY,44,20,()->{
            if (runtime.serverOwnModelPresent()) return;
            runtime.options.hideVanillaEquipment=!runtime.options.hideVanillaEquipment;runtime.options.save();clearAndInit();
        },serverDisguise?"服务器伪装期间，原版盔甲、披风和鞘翅保持隐藏；伪装模型单独控制"
                :"只切换原版盔甲、披风和鞘翅；玩家本体和伪装模型分别控制",hideEquipment);
        vanillaEquipment.active = !serverDisguise;
        roles.put(vanillaEquipment,"vanillaEquipment");
        roles.put(flatButton(runtime.options.showSelf?"伪装显示":"伪装隐藏",toggleX+96,toggleY,44,20,()->{
            runtime.options.showSelf=!runtime.options.showSelf;runtime.options.save();clearAndInit();
        },"只切换本人的伪装模型；原版玩家和装备分别控制",runtime.options.showSelf),"selfVisibility");
        var gear=new ButtonWidget(left+194,footerY,20,20,Text.literal("⚙"),button->{advanced=!advanced;clearAndInit();},narration->narration.get()) {
            @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
                context.fill(getX(),getY(),getRight(),getBottom(),-12369342);
                if(hovered || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-790560);
                context.drawTexture(RenderPipelines.GUI_TEXTURED,SETTINGS_ICON,getX()+2,getY()+2,0f,0f,16,16,32,32,32,32);
            }
        };
        gear.setTooltip(Tooltip.of(Text.literal(advanced?"返回图库":"客户端设置：渲染与轮盘选项")));roles.put(addDrawableChild(gear),"clientSettings");
    }
    @Override protected int footerStatusInset() {return 223;}

    @Override protected void buildAlternateControls() {
        if(!advanced)return;
        int x=right+8,w=rightWidth-16,y=top+30;
        boolean compact = bottom - top < 185;
        int controlWidth = compact ? (w - 4) / 2 : w;
        roles.put(flatButton(runtime.options.enabled?compact?"渲染开启":"客户端渲染：开启":compact?"渲染关闭":"客户端渲染：关闭",x,y,controlWidth,20,()->{
            runtime.toggleEnabled();clearAndInit();
        },"暂停客户端模型渲染并恢复服务器显示；保留模型、显隐和轮盘设置",runtime.options.enabled),"clientRendering");
        if(!compact)y+=25;
        roles.put(button(runtime.wheelPreferences().keepOpen()?compact?"轮盘保留":"选择后保持轮盘：开启":compact?"轮盘收起":"选择后保持轮盘：关闭",
                compact?x+controlWidth+4:x,y,controlWidth,()->{
            runtime.updateWheelPreferences(runtime.wheelPreferences().withKeepOpen(!runtime.wheelPreferences().keepOpen()));clearAndInit();
        },"选择动作后保留 J 轮盘；再次按 J 或返回可关闭"),"keepOpen");
        y+=25;
        if(clientTab && runtime.canEditLocalAppearance()) {
            roles.put(button(runtime.localActionLocked()?compact?"动作保留":"移动时保留本地动作：开启":compact?"动作自动":"移动时保留本地动作：关闭",x,y,controlWidth,()->{
                runtime.setLocalActionLocked(!runtime.localActionLocked());clearAndInit();
            },"仅影响本地显式动作；服务器真实动作由服务器决定"),"localActionLock");
            if(!compact)y+=25;
            roles.put(button(runtime.localAppearance().enabled()?compact?"本地开启":"本地外观：开启":compact?"本地关闭":"本地外观：关闭",
                    compact?x+controlWidth+4:x,y,controlWidth,()->{
                LocalAppearanceSettings current=runtime.localAppearance();
                runtime.updateLocalAppearance(new LocalAppearanceSettings(!current.enabled(),current.modelId(),current.scale(),current.offsetX(),current.offsetY(),current.offsetZ()));clearAndInit();
            },"保留模型选择与设置；只改变私人外观是否启用"),"privateEnabled");
            y+=25;
        }
        roles.put(button("返回图库",x,Math.max(y+25,bottom-22),w,()->{advanced=false;clearAndInit();},"关闭轮盘选项并回到当前来源"),"gallery");
    }
    private void switchSource(boolean local) {
        if(local || runtime.serverOwnModelPresent()) {
            runtime.selectInteractionSource(local);clientTab=runtime.interactionLocalMode();
        } else clientTab=false; // Browsing an empty server tab does not change private or network state.
        advanced=false;resetGallery();clearAndInit();
    }

    @Override public void tick() {
        if(initializedLocalMode!=runtime.interactionLocalMode() || !initializedInstance.equals(runtime.serverOwnModelInstance())) {
            clientTab=runtime.interactionLocalMode();advanced=false;resetGallery();clearAndInit();
        }
        if(!clientTab && initializedCatalogRevision!=runtime.serverCatalogRevision()) {
            resetGallery();clearAndInit();
        }
        if(!clientTab && !advanced) {
            Map<String,String> currentPreviews=serverPreviewAssets();
            if(!initializedServerPreviews.equals(currentPreviews)) {
                clearGalleryPreviews();clearAndInit();
            }
            if(use!=null)use.active=!selectedModelId().isEmpty()&&runtime.canRequestServerDisguise(selectedModelId());
        }
        super.tick();
    }

    /** Poll only the visible page and selection for already authorized resources; never requests a model. */
    private Map<String,String> serverPreviewAssets() {
        if(clientTab || advanced)return Map.of();
        var ids=new HashSet<>(visibleModelIds());
        if(!selectedModelId().isEmpty())ids.add(selectedModelId());
        var result=new LinkedHashMap<String,String>();
        for(String id:ids) {
            var loaded=runtime.serverModelForPreview(id);
            if(loaded!=null)result.put(id,loaded.hash());
        }
        return Map.copyOf(result);
    }

    @Override protected void renderHeader(DrawContext context) {
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,titleY,0xfff3f0e0);
    }
    @Override protected void renderAlternateContent(DrawContext context,float delta) {
        if(clientTab)renderSelectedPreview(context,delta);else renderServerPreview(context,delta);
        clipped(context,"客户端设置",right+8,top+8,rightWidth-16,0xfff3f0e0);
        clipped(context,runtime.privateSyncStatus(),right+8,top+19,rightWidth-16,0xff92b9df);
    }

    @Override protected void renderSelectedPreview(DrawContext context,float delta) {
        if(clientTab){super.renderSelectedPreview(context,delta);return;}
        String id=selectedModelId();
        if(id.isEmpty()) {
            clipped(context,"服务器模型",left+6,top+6,previewWidth-12,0xfff3f0e0);
            clipped(context,"请选择服务器模型",previewX+3,previewY+previewH/2,previewW-6,0xffc6d5e7);
            return;
        }
        var loaded=runtime.serverModelForPreview(id);
        String name=serverLabels.getOrDefault(id,id);
        leftPreviewId=id;leftPreviewSource="server-catalog-preview";
        clipped(context,name,left+6,top+6,previewWidth-12,0xfff3f0e0);
        clipped(context,"预览 · 点击使用模型伪装",left+6,top+17,previewWidth-12,0xff92b9df);
        clipped(context,"由服务器确认并下发",left+6,bottom-15,previewWidth-12,0xff92b9df);
        if(loaded==null) {
            clipped(context,galleryPreviewUnavailableText(id),previewX+3,previewY+previewH/2,previewW-6,0xffffc685);
            return;
        }
        leftPreviewKey="server-catalog:"+id+":"+loaded.hash();leftPreviewHash=loaded.hash();
        leftPreviewDrawn=preview.render(context,loaded.model(),leftPreviewKey,previewX,previewY,previewW,previewH,
                yaw,pitch,ticks+delta,Map.of(),loaded.previewAnimation(),loaded.profile(),ModelPreview.Context.SELECTED);
        if(leftPreviewDrawn)drawnPreviewCount++;
        else clipped(context,"服务器模型预览暂不可用",previewX+3,previewY+previewH/2,previewW-6,0xffffc685);
    }

    private void renderServerPreview(DrawContext context,float delta) {
        String id=runtime.serverOwnModelId(),instance=runtime.serverOwnModelInstance();
        clipped(context,id.isEmpty()?"服务器模型":id,left+6,top+6,previewWidth-12,0xfff3f0e0);
        clipped(context,"当前绑定 · 只读",left+6,top+17,previewWidth-12,0xff92b9df);
        ClientRuntime.RenderBinding binding=client.player==null?null:runtime.appearanceBinding(client.player.getUuid());
        if(runtime.serverOwnModelPresent() && binding!=null && instance.equals(binding.instance()) && !binding.motionSource().equals("local-self")) {
            leftPreviewId=id;leftPreviewKey="server:"+instance+":"+binding.assetHash();
            leftPreviewSource="server-bound";leftPreviewInstance=instance;leftPreviewHash=binding.assetHash();
            leftPreviewDrawn=preview.render(context,binding.model(),leftPreviewKey,previewX,previewY,previewW,previewH,yaw,pitch,ticks+delta,Map.of(),"",YsmModelProfile.empty());
            if(leftPreviewDrawn)drawnPreviewCount++;
        }
        if(!leftPreviewDrawn)clipped(context,runtime.serverOwnModelPresent()?"等待服务器模型就绪":"尚无服务器模型",previewX+3,previewY+previewH/2,previewW-6,0xffffc685);
    }
    @Override protected boolean rotationDisabled() {return clientTab && super.rotationDisabled();}

    public Map<String,Object> diagnostics() {
        var result=new LinkedHashMap<String,Object>();Map<String,Object> gallery=galleryDiagnostics();
        result.put("mode",clientTab?"client":"server");result.put("ownServerDisguise",runtime.serverOwnModelPresent());
        result.put("localModelControlsVisible",clientTab && runtime.canEditLocalAppearance());result.put("actionGrid",false);
        result.put("effectiveLocalMode",runtime.interactionLocalMode());result.put("advanced",advanced);result.put("galleryHome",true);
        result.put("galleryLayout",clientGalleryVisible());result.put("upstreamGallery",true);
        result.put("contentTop",top);result.put("contentBottom",bottom);result.put("scroll",0);result.put("maximumScroll",0);
        result.put("leftPreview",gallery.get("leftPreview"));result.put("cards",gallery.get("cards"));result.put("gallery",gallery);
        result.put("serverAppearance",runtime.ownServerAppearanceDiagnostics());
        result.put("serverCatalogRevision",runtime.serverCatalogRevision());result.put("serverCatalogStatus",runtime.serverCatalogStatus());
        result.put("privateSyncEnabled",runtime.privateSyncEnabled());result.put("privateSyncAvailable",runtime.privateSyncAvailable());
        result.put("privateSyncStatus",runtime.privateSyncStatus());
        result.put("privateSharing",runtime.privateSharingDiagnostics());
        result.put("widgets",children().stream().filter(value->value instanceof ButtonWidget).map(value->{
            var button=(ButtonWidget)value;var widget=new LinkedHashMap<String,Object>();
            widget.put("label",button.getMessage().getString());widget.put("role",roles.getOrDefault(button,"galleryControl"));
            widget.put("x",button.getX());widget.put("y",button.getY());widget.put("width",button.getWidth());widget.put("height",button.getHeight());
            widget.put("visible",button.visible);widget.put("active",button.active);return Map.copyOf(widget);
        }).toList());
        result.put("wheelKeepOpen",runtime.wheelPreferences().keepOpen());result.put("localActionLocked",runtime.localActionLocked());
        return Map.copyOf(result);
    }
}
