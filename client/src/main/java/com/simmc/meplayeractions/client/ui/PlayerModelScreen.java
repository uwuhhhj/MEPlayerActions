package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.network.ServerModelPresentation;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
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
    private boolean clientTab,advanced,initializedServerPresent,initializedServerOffline;
    private String initializedInstance="";
    private long initializedCatalogRevision=Long.MIN_VALUE;
    private Map<String,String> initializedServerPreviews=Map.of();
    private Map<String,String> serverLabels=Map.of();
    private UsageSnapshot initializedUsage;
    private record UsageSnapshot(ClientRuntime.AppearanceSource source,String modelId,String hash,
                                 String pendingServer,String pendingLocal,String requestStatus,boolean canActivate,
                                 boolean playerHidden,boolean playerRuleKnown,boolean modelShown,boolean modelShowAllowed) { }

    public PlayerModelScreen(ClientRuntime runtime) {this(runtime,null);}
    public PlayerModelScreen(ClientRuntime runtime,Screen parent) {
        super(runtime,parent,Text.literal("玩家模型"));clientTab=!runtime.serverOwnModelPresent();
    }

    @Override protected void init() {
        boolean present=runtime.serverOwnModelPresent();String instance=runtime.serverOwnModelInstance();
        if(!initializedServerPresent && present && clientTab) {
            clientTab=false;advanced=false;resetGallery();
        }
        initializedServerPresent=present;initializedInstance=instance;roles.clear();
        initializedServerOffline=runtime.serverCatalogOfflineMode();
        super.init();
        initializedCatalogRevision=runtime.serverCatalogRevision();
        initializedServerPreviews=serverPreviewAssets();
        initializedUsage=usageSnapshot();
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
    @Override protected String gallerySourceLabel(String id) {return clientTab?super.gallerySourceLabel(id):runtime.serverCachedPreviewOnly(id)?"本机缓存 · 仅预览":"服务器模型";}
    @Override protected String galleryStatus() {
        if(clientTab)return super.galleryStatus();
        if(runtime.serverCatalogOfflineMode())return runtime.serverCatalogStatus()+" · 连接服务器后取得使用授权";
        String request=runtime.serverDisguiseRequestStatus();
        return request.isEmpty()?runtime.serverCatalogStatus():request;
    }
    @Override protected String galleryPreviewUnavailableText(String id) {
        return clientTab?super.galleryPreviewUnavailableText(id):runtime.serverModelPresentation(id).placeholder();
    }
    @Override protected boolean galleryCardCloudVisible() {return true;}
    @Override protected String galleryCardCloudTooltip(String id) {
        return clientTab?super.galleryCardCloudTooltip(id):runtime.serverModelPresentation(id).tooltip();
    }
    @Override protected void galleryCardCloudClicked(String id) {
        if(clientTab){super.galleryCardCloudClicked(id);return;}
        // The cloud selects a status/preview only. Only the explicit use button requests a disguise.
        browseModel(id);setGalleryMessage(runtime.serverModelPresentation(id).placeholder());
    }
    @Override protected void drawGalleryCardCloud(DrawContext context,String id,int x,int y) {
        if(clientTab){super.drawGalleryCardCloud(context,id,x,y);return;}
        var state=runtime.serverModelPresentation(id);CloudUploadIcon.drawServer(context,x,y,state.cloudColor(),state.cloud());
    }
    @Override protected boolean canUseGalleryModel(String id,LocalModelLibrary.Loaded loaded) {
        return clientTab?super.canUseGalleryModel(id,loaded):runtime.canRequestServerDisguise(id);
    }
    @Override protected GalleryModelUseState.State galleryUseState(String id,LocalModelLibrary.Loaded loaded) {
        if(clientTab)return super.galleryUseState(id,loaded);
        boolean current=runtime.currentAppearanceSource()==ClientRuntime.AppearanceSource.SERVER;
        return GalleryModelUseState.server(id,current?runtime.currentAppearanceModelId():"",runtime.pendingServerDisguiseModelId(),
                runtime.canRequestServerDisguise(id),!id.isEmpty()&&runtime.serverModelHasPersistentId(id)&&runtime.serverPreferencesNeedApply(id));
    }
    @Override protected String galleryUseTooltip() {
        return clientTab?super.galleryUseTooltip():"请求使用所选服务器模型\n服务器先确认伪装，再检查可接管资源\n有可用资源才会授权客户端下载\n其他玩家可见服务器伪装";
    }
    @Override protected String galleryUseTooltip(GalleryModelUseState.State state) {
        if(!clientTab && runtime.serverCatalogOfflineMode())return "离线模式，仅可预览缓存\n需要连接服务器取得当前使用授权\n外观设置仍可保存于本机";
        if(!clientTab && !state.current() && !state.waiting() && !state.canUse()) {
            if(runtime.serverCachedPreviewOnly(selectedModelId()))return "本机缓存，仅供预览\n此模型不在当前服务器授权图库\n缓存不能代替使用授权";
            if(!runtime.pendingServerDisguiseModelId().isEmpty())return "等待另一个伪装请求完成\n收到服务器确认后再切换";
            if(!runtime.serverCatalogReady())return "等待服务器模型图库\n"+runtime.serverCatalogStatus();
        }
        if(!clientTab)return (state.current()?state.canUse()?"保存的外观设置尚未应用\n点击应用后等待服务器确认":"当前正在使用此服务器伪装\n当前使用不等于已开启本地接管"
                :state.waiting()?"等待服务器确认伪装请求":"点击请求使用所选服务器伪装")+"\n"+runtime.serverModelPresentation(selectedModelId()).tooltip();
        return super.galleryUseTooltip(state);
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
        favorite=null;
        use=button("使用模型",left+5,bottom-44,previewWidth-10,this::useSelectedModel,galleryUseTooltip());
        roles.put(use,"useServerModel");
        settings=button("外观设置…",left+5,bottom-21,previewWidth-10,
                ()->{if(runtime.serverModelHasPersistentId(selectedModelId()))client.setScreen(new ServerAppearanceSettingsScreen(runtime,selectedModelId(),this));},
                "保存此服务器模型的伪装参数\n返回后点击使用模型才提交");
        roles.put(settings,"serverAppearanceSettings");
    }
    @Override public void useSelectedModel() {
        if(clientTab){super.useSelectedModel();return;}
        String id=selectedModelId();
        if(!galleryUseState(id,runtime.serverModelForPreview(id)).canUse())return;
        setGalleryMessage(runtime.requestServerDisguise(id)?"":"无法发送伪装请求，请稍后重试");
        updateSelectionButtons();
    }
    @Override protected boolean requiresLocalSource() {return false;}
    @Override protected boolean showRotationHint() {return false;}
    @Override protected String settingsButtonLabel(int buttonWidth) {return "外观设置…";}

    @Override protected void buildHeaderControls() {
        int sourceOffset=compactLayout?20:29;
        int half=(panelWidth-6)/2,y=top-sourceOffset;
        int sourceHeight=compactLayout?18:24;
        roles.put(flatButton(clientTab?"客户端 ✓":"客户端",left,y,half,sourceHeight,()->switchSource(true),
                "浏览客户端模型\n使用模型仅自己可见\n云朵按钮可上传分享",clientTab),"clientSource");
        String serverLabel=runtime.serverCatalogOfflineMode()?clientTab?"服务端 · 离线":"服务端 · 离线 ✓":clientTab?"服务端":"服务端 ✓";
        roles.put(flatButton(serverLabel,left+half+6,y,panelWidth-half-6,sourceHeight,()->switchSource(false),
                runtime.serverCatalogOfflineMode()?"离线模式\n浏览本机缓存并保存设置\n连接服务器后才能请求伪装":"浏览服务器可用模型\n点击使用后等待服务器确认",!clientTab),"serverSource");
    }

    @Override protected void buildFooterControls() {
        roles.put(button("返回",left,footerY,46,this::close,"返回上一页"),"back");
        // Like OpenYSM ConfigCheckBoxForge / BooleanOptionRow, visible selection
        // comes from the actual option; the label also identifies each separate switch.
        boolean serverDisguise = runtime.serverOwnModelPresent();
        int toggleX = left + 50, toggleY = footerY;
        boolean hidePlayer=runtime.ownPlayerHideSetting(),playerRuleUnknown=serverDisguise&&!runtime.serverOwnPlayerHideRuleKnown();
        String playerLabel=playerRuleUnknown?"玩家·服务器控制":hidePlayer?"玩家隐藏":"玩家显示";
        int playerWidth=Math.max(44,textRenderer.getWidth(playerLabel)+6),playerExtra=playerWidth-44;
        var vanillaPlayer = flatButton(playerLabel,toggleX,toggleY,playerWidth,20,()->{
            if (runtime.serverOwnModelPresent()) return;
            runtime.options.hideVanillaPlayer=!runtime.options.hideVanillaPlayer;runtime.options.save();clearAndInit();
        },serverDisguise?playerRuleUnknown?"玩家由服务器控制\n未收到人物隐藏规则\n沿用原版服务器显示"
                :"玩家由服务器控制\n当前规则："+(hidePlayer?"隐藏":"显示")+"\n调整服务端外观设置后应用"
                :"只切换原版玩家\n装备与伪装分别控制",hidePlayer);
        vanillaPlayer.active = !serverDisguise;
        roles.put(vanillaPlayer,"vanillaPlayer");
        boolean hideEquipment = serverDisguise || runtime.options.hideVanillaEquipment;
        var vanillaEquipment = flatButton(hideEquipment?"装备隐藏":"装备显示",toggleX+48+playerExtra,toggleY,44,20,()->{
            if (runtime.serverOwnModelPresent()) return;
            runtime.options.hideVanillaEquipment=!runtime.options.hideVanillaEquipment;runtime.options.save();clearAndInit();
        },serverDisguise?"服务器伪装期间装备保持隐藏\n伪装模型由独立按钮控制"
                :"只切换盔甲、披风与鞘翅\n玩家与伪装分别控制",hideEquipment);
        vanillaEquipment.active = !serverDisguise;
        roles.put(vanillaEquipment,"vanillaEquipment");
        boolean modelShown=runtime.ownModelShowSetting(),modelShowAllowed=runtime.serverOwnModelShowAllowed();
        var ownModel=flatButton(modelShown?"伪装显示":"伪装隐藏",toggleX+96+playerExtra,toggleY,44,20,()->{
            if(!runtime.serverOwnModelShowAllowed())return;
            runtime.options.showSelf=!runtime.options.showSelf;runtime.options.save();clearAndInit();
        },modelShowAllowed?"只切换本人的伪装模型\n原版玩家与装备分别控制":"伪装显示由服务器控制\n当前未允许本人可见\n本机显示偏好保留\n可在服务端外观设置请求本人可见并应用",modelShown);
        ownModel.active=modelShowAllowed;roles.put(ownModel,"selfVisibility");
        var gear=new ButtonWidget(left+194+playerExtra,footerY,20,20,Text.literal("⚙"),button->{advanced=!advanced;clearAndInit();},narration->narration.get()) {
            @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
                context.fill(getX(),getY(),getRight(),getBottom(),-12369342);
                if(hovered || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-790560);
                context.drawTexture(RenderPipelines.GUI_TEXTURED,SETTINGS_ICON,getX()+2,getY()+2,0f,0f,16,16,32,32,32,32);
            }
        };
        gear.setTooltip(ModelUiTooltip.of(textRenderer,width,advanced?"返回图库":"客户端设置\n渲染与轮盘选项"));roles.put(addDrawableChild(gear),"clientSettings");
    }
    @Override protected int footerStatusInset() {
        String label=runtime.serverOwnModelPresent()&&!runtime.serverOwnPlayerHideRuleKnown()?"玩家·服务器控制":runtime.ownPlayerHideSetting()?"玩家隐藏":"玩家显示";
        return 223+Math.max(0,textRenderer.getWidth(label)+6-44);
    }

    @Override protected void buildAlternateControls() {
        if(!advanced)return;
        int x=right+8,w=rightWidth-16,y=top+30;
        boolean compact = bottom - top < 185;
        int controlWidth = compact ? (w - 4) / 2 : w;
        roles.put(flatButton(runtime.options.enabled?compact?"渲染开启":"客户端渲染：开启":compact?"渲染关闭":"客户端渲染：关闭",x,y,controlWidth,20,()->{
            runtime.toggleEnabled();clearAndInit();
        },"暂停客户端模型渲染\n恢复服务器或原版显示\n保留模型与轮盘设置",runtime.options.enabled),"clientRendering");
        if(!compact)y+=25;
        roles.put(button(runtime.wheelPreferences().keepOpen()?compact?"轮盘保留":"选择后保持轮盘：开启":compact?"轮盘收起":"选择后保持轮盘：关闭",
                compact?x+controlWidth+4:x,y,controlWidth,()->{
            runtime.updateWheelPreferences(runtime.wheelPreferences().withKeepOpen(!runtime.wheelPreferences().keepOpen()));clearAndInit();
        },"选择动作后保持轮盘\n再次按 J 或返回关闭"),"keepOpen");
        y+=25;
        if(clientTab && runtime.canEditLocalAppearance()) {
            roles.put(button(runtime.localActionLocked()?compact?"动作保留":"移动时保留本地动作：开启":compact?"动作自动":"移动时保留本地动作：关闭",x,y,controlWidth,()->{
                runtime.setLocalActionLocked(!runtime.localActionLocked());clearAndInit();
            },"移动时保留本地动作\n服务器动作由服务器决定"),"localActionLock");
            if(!compact)y+=25;
            boolean privateApplied=runtime.currentAppearanceSource()==ClientRuntime.AppearanceSource.CLIENT;
            roles.put(button(privateApplied?compact?"本地开启":"本地外观：开启":compact?"本地关闭":"本地外观：关闭",
                    compact?x+controlWidth+4:x,y,controlWidth,()->{
                if(!runtime.canActivateLocalAppearance())return;
                LocalAppearanceSettings current=runtime.localAppearance();
                if(runtime.currentAppearanceSource()==ClientRuntime.AppearanceSource.CLIENT)
                    runtime.updateLocalAppearance(new LocalAppearanceSettings(false,current.modelId(),current.scale(),current.offsetX(),current.offsetY(),current.offsetZ()));
                else runtime.selectLocalModel(current.modelId());
                clearAndInit();
            },"开启或关闭私人外观\n请先解除服务器伪装\n保留模型与设置"),"privateEnabled");
            roles.entrySet().stream().filter(entry->entry.getValue().equals("privateEnabled"))
                    .forEach(entry->entry.getKey().active=runtime.canActivateLocalAppearance());
            y+=25;
        }
        roles.put(button("返回图库",x,Math.max(y+25,bottom-22),w,()->{advanced=false;clearAndInit();},"关闭轮盘选项并回到当前来源"),"gallery");
    }
    private void switchSource(boolean local) {
        clientTab=local; // Tabs only browse; use buttons own appearance changes.
        advanced=false;resetGallery();clearAndInit();
    }

    @Override public void tick() {
        boolean offlineChanged=initializedServerOffline!=runtime.serverCatalogOfflineMode();
        if(offlineChanged || !clientTab && initializedCatalogRevision!=runtime.serverCatalogRevision()) {
            if(!clientTab)refreshGalleryModels();
            clearAndInit();
        }
        boolean present=runtime.serverOwnModelPresent();String instance=runtime.serverOwnModelInstance();
        if(!initializedServerPresent && present) {
            clientTab=false;advanced=false;resetGallery();clearAndInit();
        } else if(initializedServerPresent!=present || !initializedInstance.equals(instance)) {
            setGalleryMessage("");clearAndInit();
        }
        if(!clientTab && !advanced) {
            Map<String,String> currentPreviews=serverPreviewAssets();
            if(!initializedServerPreviews.equals(currentPreviews)) {
                clearGalleryPreviews();clearAndInit();
            }
        }
        UsageSnapshot current=usageSnapshot();
        if(!current.equals(initializedUsage)) {
            boolean visibilityChanged=initializedUsage!=null&&(current.playerHidden()!=initializedUsage.playerHidden()||current.playerRuleKnown()!=initializedUsage.playerRuleKnown()
                    ||current.modelShown()!=initializedUsage.modelShown()||current.modelShowAllowed()!=initializedUsage.modelShowAllowed());
            setGalleryMessage("");initializedUsage=current;
            if(visibilityChanged)clearAndInit();else updateSelectionButtons();
        }
        super.tick();
    }

    private UsageSnapshot usageSnapshot() {
        return new UsageSnapshot(runtime.currentAppearanceSource(),runtime.currentAppearanceModelId(),runtime.currentAppearanceAssetHash(),
                runtime.pendingServerDisguiseModelId(),runtime.pendingLocalAppearanceModelId(),runtime.serverDisguiseRequestStatus(),runtime.canActivateLocalAppearance(),
                runtime.ownPlayerHideSetting(),runtime.serverOwnPlayerHideRuleKnown(),runtime.ownModelShowSetting(),runtime.serverOwnModelShowAllowed());
    }

    @Override protected void updateSelectionButtons() {
        super.updateSelectionButtons();
        if(!clientTab && settings!=null) {
            settings.active=!selectedModelId().isEmpty()&&runtime.serverModelHasPersistentId(selectedModelId());
            settings.setTooltip(ModelUiTooltip.of(textRenderer,width,settings.active
                    ?"保存此服务器模型的伪装参数\n返回后点击使用模型才提交":"缓存尚无服务器模型 ID\n仅可预览，不能保存伪装参数"));
        }
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
        var state=galleryUseState(id,loaded);
        leftPreviewId=id;leftPreviewSource=state.current()?"server-catalog-current":"server-catalog-preview";
        clipped(context,name,left+6,top+6,previewWidth-12,0xfff3f0e0);
        clipped(context,state.waiting()?"等待服务器确认":state.current()?"当前使用":runtime.serverCatalogOfflineMode()?"离线缓存 · 仅预览"
                        :runtime.serverCachedPreviewOnly(id)?"本机缓存 · 尚未授权":"预览 · 点击使用模型伪装",
                left+6,top+17,previewWidth-12,0xff92b9df);
        if(loaded==null) {
            renderServerResourceStatus(context,id,previewY,previewH);
            return;
        }
        String actualHash=state.current()?runtime.currentAppearanceAssetHash():"";
        if(!actualHash.isEmpty()&&!actualHash.equals(loaded.hash())) {
            renderServerResourceStatus(context,id,previewY,previewH);
            return;
        }
        leftPreviewKey="server-catalog:"+id+":"+loaded.hash();leftPreviewHash=loaded.hash();
        int statusHeight=Math.min(72,Math.max(30,previewH/3)),modelHeight=Math.max(20,previewH-statusHeight-4);
        leftPreviewDrawn=preview.render(context,loaded.model(),leftPreviewKey,previewX,previewY,previewW,modelHeight,
                yaw,pitch,ticks+delta,Map.of(),loaded.previewAnimation(),loaded.profile(),ModelPreview.Context.SELECTED);
        if(leftPreviewDrawn)drawnPreviewCount++;
        else clipped(context,"服务器模型预览暂不可用",previewX+3,previewY+modelHeight/2,previewW-6,0xffffc685);
        renderServerResourceStatus(context,id,previewY+modelHeight+4,statusHeight);
    }

    /** Narrow panels wrap the key facts first; the cloud/use tooltip retains full source and error details. */
    private void renderServerResourceStatus(DrawContext context,String id,int y,int height) {
        var status=runtime.serverModelPresentation(id);
        var ordered=new java.util.ArrayList<ServerModelPresentation.Line>();
        for(String prefix:List.of("服务器资源：","客户端资源：","本地接管："))
            status.lines().stream().filter(line->line.text().startsWith(prefix)).forEach(ordered::add);
        status.lines().stream().filter(line->!ordered.contains(line)).forEach(ordered::add);
        int maxLines=Math.max(1,height/10),used=0;
        context.enableScissor(previewX,y,previewX+previewW,y+height);
        for(int factIndex=0;factIndex<ordered.size();factIndex++) {
            var fact=ordered.get(factIndex);
            var wrapped=textRenderer.wrapLines(Text.literal(fact.text()),Math.max(20,previewW-6));
            int budget=factIndex<3?Math.max(1,(maxLines-used)/Math.max(1,3-factIndex)):maxLines-used;
            int count=0;
            for(var line:wrapped) {
                if(used>=maxLines)break;
                context.drawTextWithShadow(textRenderer,line,previewX+3,y+used*10,fact.color());used++;
                if(++count>=budget)break;
            }
            if(used>=maxLines)break;
        }
        context.disableScissor();
    }

    private void renderServerPreview(DrawContext context,float delta) {
        String id=runtime.serverOwnModelId(),instance=runtime.serverOwnModelInstance();
        clipped(context,id.isEmpty()?"服务器模型":id,left+6,top+6,previewWidth-12,0xfff3f0e0);
        clipped(context,"服务器当前绑定 · 接管状态独立显示",left+6,top+17,previewWidth-12,0xff92b9df);
        var loaded=runtime.serverModelForPreview(id);
        int statusHeight=Math.min(72,Math.max(30,previewH/3)),modelHeight=Math.max(20,previewH-statusHeight-4);
        if(runtime.serverOwnModelPresent() && loaded!=null) {
            leftPreviewId=id;leftPreviewKey="server:"+instance+":"+loaded.hash();
            leftPreviewSource="server-bound";leftPreviewInstance=instance;leftPreviewHash=loaded.hash();
            leftPreviewDrawn=preview.render(context,loaded.model(),leftPreviewKey,previewX,previewY,previewW,modelHeight,yaw,pitch,ticks+delta,Map.of(),loaded.previewAnimation(),loaded.profile());
            if(leftPreviewDrawn)drawnPreviewCount++;
        }
        if(!leftPreviewDrawn)renderServerResourceStatus(context,id,previewY,previewH);
        else renderServerResourceStatus(context,id,previewY+modelHeight+4,statusHeight);
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
