package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Direct gallery home adapted from OpenYSM-Updated PlayerModelScreen/ModelButton/IconButton,
 * revision 0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85 (MIT; see THIRD_PARTY_NOTICES).
 * The two source tabs choose the viewer's private or current server-bound appearance.
 */
public final class PlayerModelScreen extends LocalAppearanceScreen {
    private static final Identifier SETTINGS_ICON=Identifier.of("meplayeractions","textures/gui/settings.png");
    private final Map<ButtonWidget,String> roles=new IdentityHashMap<>();
    private boolean clientTab,advanced,initializedLocalMode;
    private String initializedInstance="";
    private ButtonWidget selfOnly,sharedModel;

    public PlayerModelScreen(ClientRuntime runtime) {this(runtime,null);}
    public PlayerModelScreen(ClientRuntime runtime,Screen parent) {
        super(runtime,parent,Text.literal("玩家模型"));clientTab=runtime.interactionLocalMode();
    }

    @Override protected void init() {
        // Returning from a child screen must compare the previous snapshot before recording the new one.
        boolean localMode=runtime.interactionLocalMode();String instance=runtime.serverOwnModelInstance();
        if(initializedLocalMode!=localMode || !initializedInstance.equals(instance)) {
            clientTab=localMode;advanced=false;clearGalleryPreviews();
        }
        initializedLocalMode=localMode;initializedInstance=instance;roles.clear();
        super.init();
    }
    @Override protected int galleryTop() {return (height<210?52:58)+(clientTab?24:0);}
    @Override protected boolean clientGalleryVisible() {return clientTab && !advanced;}
    @Override protected boolean requiresLocalSource() {return false;}
    @Override protected boolean showRotationHint() {return false;}
    @Override protected String settingsButtonLabel(int buttonWidth) {return "外观设置…";}

    @Override protected void buildHeaderControls() {
        int half=(panelWidth-6)/2,y=top-(clientTab?53:29);
        roles.put(flatButton(clientTab?"客户端 ✓":"客户端",left,y,half,24,()->switchSource(true),
                "本地图库与私人设置；选择仅自己可见或授权分享。手动覆盖服务器伪装仍只在本机显示",clientTab),"clientSource");
        roles.put(flatButton(clientTab?"服务器下发":"服务器下发 ✓",left+half+6,y,panelWidth-half-6,24,()->switchSource(false),
                "仅展示当前服务器绑定的模型；模型选择与分发由服务器决定",!clientTab),"serverSource");
        selfOnly=sharedModel=null;
        if(clientTab) {
            boolean sharing=runtime.privateSyncEnabled()&&!runtime.serverOwnModelPresent();
            selfOnly=flatButton(sharing?"仅自己可见":"仅自己可见 ✓",left,top-26,half,20,()->{
                runtime.setPrivateSyncEnabled(false);clearAndInit();
            },"只在自己的客户端显示，不上传模型，也不改变服务器伪装",!sharing);
            sharedModel=flatButton(sharing?"分享给模组玩家 ✓":"分享给模组玩家",left+half+6,top-26,panelWidth-half-6,20,()->{
                runtime.setPrivateSyncEnabled(true);clearAndInit();
            },sharingTooltip(),sharing);
            sharedModel.active=runtime.canShareLocalModel();
            roles.put(selfOnly,"selfOnly");roles.put(sharedModel,"sharePrivateModel");
        }
    }

    @Override protected void buildFooterControls() {
        roles.put(button("返回",left,height-27,46,this::close,"返回上一页"),"back");
        // Like OpenYSM ConfigCheckBoxForge / BooleanOptionRow, visible selection
        // comes from the actual option; the label also identifies each separate switch.
        boolean serverDisguise = runtime.serverOwnModelPresent();
        int toggleX = left + 50, toggleY = height - 27;
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
        var gear=new ButtonWidget(left+194,height-27,20,20,Text.literal("⚙"),button->{advanced=!advanced;clearAndInit();},narration->narration.get()) {
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
    private String sharingTooltip() {
        return "主动将已使用的 .ysm 或 .bbmodel 上传给服务器，供获准观看的模组玩家下载并本地渲染；原版玩家仍看见原版人物。\n"
                +runtime.privateSyncStatus();
    }

    private void switchSource(boolean local) {
        if(local || runtime.serverOwnModelPresent()) {
            runtime.selectInteractionSource(local);clientTab=runtime.interactionLocalMode();
        } else clientTab=false; // Browsing an empty server tab does not change private or network state.
        advanced=false;clearGalleryPreviews();clearAndInit();
    }

    @Override public void tick() {
        if(initializedLocalMode!=runtime.interactionLocalMode() || !initializedInstance.equals(runtime.serverOwnModelInstance())) {
            clientTab=runtime.interactionLocalMode();advanced=false;clearGalleryPreviews();clearAndInit();
        }
        if(sharedModel!=null){sharedModel.active=runtime.canShareLocalModel();sharedModel.setTooltip(Tooltip.of(Text.literal(sharingTooltip())));}
        super.tick();
    }

    @Override protected void renderHeader(DrawContext context) {
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,7,0xfff3f0e0);
    }
    @Override protected void renderAlternateContent(DrawContext context,float delta) {
        if(clientTab)renderSelectedPreview(context,delta);else renderServerPreview(context,delta);
        if(advanced) {
            clipped(context,"客户端设置",right+8,top+8,rightWidth-16,0xfff3f0e0);
            clipped(context,runtime.privateSyncStatus(),right+8,top+19,rightWidth-16,0xff92b9df);
            return;
        }
        String model=runtime.serverOwnModelPresent()?runtime.serverOwnModelId():"暂无服务器模型";
        clipped(context,model,right+8,top+9,rightWidth-16,0xfff3f0e0);
        String status=!runtime.serverBridgeReady()?"当前服务器未连接模型功能":!runtime.serverOwnModelPresent()
                ?"使用服务器伪装后，模型会显示在这里":runtime.serverOwnModelReady()?"已接管服务器模型":"模型尚未就绪，暂由服务器显示";
        Object error=runtime.ownServerAppearanceDiagnostics().get("assetError");
        String assetError=error instanceof String detail && !detail.isEmpty()?"\n"+detail:"";
        var lines=textRenderer.wrapLines(Text.literal(status+assetError+"\n\n服务器决定模型、动画与同步；此页只显示当前绑定，不申请其它模型。\n\n动作使用 J 轮盘。"),Math.max(1,rightWidth-16));
        context.enableScissor(right+6,top+28,right+rightWidth-6,bottom-6);
        int y=top+30;for(var line:lines) {context.drawTextWithShadow(textRenderer,line,right+8,y,0xffb9c9da);y+=11;}
        context.disableScissor();
    }

    private void renderServerPreview(DrawContext context,float delta) {
        String id=runtime.serverOwnModelId(),instance=runtime.serverOwnModelInstance();
        clipped(context,id.isEmpty()?"服务器模型":id,left+6,top+6,previewWidth-12,0xfff3f0e0);
        clipped(context,"当前绑定 · 只读",left+6,top+17,previewWidth-12,0xff92b9df);
        ClientRuntime.RenderBinding binding=client.player==null?null:runtime.appearanceBinding(client.player.getUuid());
        if(runtime.serverOwnModelPresent() && binding!=null && instance.equals(binding.instance()) && !binding.motionSource().equals("local-self")) {
            leftPreviewId=id;leftPreviewKey="server:"+instance+":"+binding.assetHash();
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
