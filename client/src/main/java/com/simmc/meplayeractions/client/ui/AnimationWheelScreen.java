package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.MEPlayerActionsClient;
import com.simmc.meplayeractions.client.WheelPreferences;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Yarn 1.21.11 port of OpenYSM's classic AnimationRouletteScreen at 0306e1f (MIT).
 * Polygon slices, configuration slices, author forms, scrolling and flat/check/range widgets
 * follow AnimationRouletteScreen, FlatColorButton, ConfigCheckBox and AnimationSlider.
 * Runtime supplies MPA action authorization and atomic private-profile writes.
 * The mirrored left-side disguise controls are MPA additions, not upstream roulette features.
 * See THIRD_PARTY_NOTICES.md for the original license and source attribution.
 */
public final class AnimationWheelScreen extends Screen {
    private static final int SLOTS=WheelSelection.PAGE_SIZE;
    private static final Identifier SETTINGS_ICON=Identifier.of("meplayeractions","textures/gui/settings.png");
    private final ClientRuntime runtime;
    private final Screen parent;
    private final AtomicLong emittedVertices=new AtomicLong();
    private boolean localMode,locked,serverAvailable,ownServerDisguise,sourceInitialized,restorePageOnCatalog;
    private boolean releaseConsumed,releaseKeyDown,keyboardSelection,hoveredConfig,hoveredCenter;
    private int page,hovered=-1,centerX,centerY,inner,outer,releaseKey=GLFW.GLFW_KEY_J;
    private InputUtil.Type releaseType=InputUtil.Type.KEYSYM;
    private WheelSelection.RouletteLayout layout;
    private List<ClientRuntime.Action> actions=List.of();
    private List<Map<String,Object>> renderedPolygons=List.of();
    private final List<NavigationControl> navigationControls=new ArrayList<>();
    private final List<FormRow> formRows=new ArrayList<>();
    private final List<ClickableWidget> formWidgets=new ArrayList<>();
    private final Map<String,Double> formValues=new HashMap<>();
    private final List<String> categoryPath=new ArrayList<>();
    private final Map<String,Integer> categoryPages=new HashMap<>();
    private String message="",configGroupId="",serverInstance="";
    private int configScroll,configContentHeight;
    private YsmModelProfile menuProfile;
    private ModelActionMenu menu;
    private ModelConfigSchema configSchema;
    private ModelConfigSchema.Group configGroup;
    private List<ModelActionMenu.Entry> localEntries=List.of();
    private FlatButton centerButton,scrollUpButton,scrollDownButton,undisguiseButton;

    public AnimationWheelScreen(ClientRuntime runtime) {this(runtime,true,null);beginHoldSelection();}
    /** The compatibility source argument never changes Runtime's per-instance action source. */
    public AnimationWheelScreen(ClientRuntime runtime,boolean localMode,Screen parent) {
        super(Text.literal("动作轮盘"));this.runtime=runtime;this.parent=parent;this.localMode=localMode;releaseConsumed=true;
    }
    public void setReleaseKey(int keyCode) {
        releaseKey=keyCode;releaseType=InputUtil.Type.KEYSYM;
        if(MEPlayerActionsClient.actionWheelKey!=null) {
            var bound=KeyBindingHelper.getBoundKeyOf(MEPlayerActionsClient.actionWheelKey);
            if(bound.getCode()==keyCode)releaseType=bound.getCategory();
        }
        beginHoldSelection();
    }
    public void setReleaseKey(InputUtil.Key key) {releaseKey=key.getCode();releaseType=key.getCategory();beginHoldSelection();}
    public void beginHoldSelection() {releaseConsumed=false;releaseKeyDown=true;}
    private boolean releaseMatches(KeyInput input) {
        return releaseType!=InputUtil.Type.MOUSE &&
                (releaseType==InputUtil.Type.SCANCODE?input.scancode():input.key())==releaseKey;
    }
    /** Release dispatches once and cannot submit a previous appearance's selection. */
    public void releaseSelection() {
        releaseKeyDown=false;
        if(releaseConsumed || client.currentScreen!=this)return;
        releaseConsumed=true;
        if(!sameSource())return;
        if(index(hovered)>=0){if(hoveredConfig)openConfig(hovered);else select(hovered);}
        else if(hoveredCenter){activateCenter();if(!localMode && !locked)close();}
        else if(!locked)close();
    }
    private WheelPreferences.Source source() {return localMode?WheelPreferences.Source.CLIENT:WheelPreferences.Source.SERVER;}
    private void clearHover() {hovered=-1;hoveredConfig=false;hoveredCenter=false;keyboardSelection=false;}
    private void clearConfig() {configGroup=null;configGroupId="";configScroll=0;configContentHeight=0;}
    private void syncSource() {
        var preferences=runtime.wheelPreferences();
        boolean local=runtime.interactionLocalMode();
        if(!sourceInitialized || local!=localMode) {
            localMode=local;page=preferences.page(source());sourceInitialized=true;restorePageOnCatalog=true;
            menu=null;menuProfile=null;configSchema=null;localEntries=List.of();categoryPages.clear();categoryPath.clear();
            clearConfig();clearHover();message="";
        }
        ownServerDisguise=runtime.hasOwnServerDisguise();serverInstance=runtime.serverOwnModelInstance();locked=preferences.keepOpen();
    }
    private boolean sameSource() {
        if(localMode==runtime.interactionLocalMode() && serverInstance.equals(runtime.serverOwnModelInstance()))return true;
        clearHover();releaseConsumed=true;clearAndInit();return false;
    }
    @Override protected void init() {
        syncSource();navigationControls.clear();formRows.clear();formWidgets.clear();formValues.clear();scrollUpButton=null;scrollDownButton=null;
        serverAvailable=runtime.canUseServerActions();actions=currentActions();
        if(restorePageOnCatalog && !actions.isEmpty()) {page=runtime.wheelPreferences().page(source());restorePageOnCatalog=false;}
        page=WheelSelection.clampPage(page,actions.size(),SLOTS);
        layout=WheelSelection.layout(width,height);centerX=layout.centerX();centerY=layout.centerY();
        inner=layout.scaled(25);outer=layout.scaled(105);
        int centerWidth=Math.max(20,layout.scaled(40)),centerHeight=Math.max(12,layout.scaled(20));
        centerButton=addControl("center",new FlatButton(centerX-centerWidth/2,centerY-centerHeight/2,
                centerWidth,centerHeight,centerLabel(),this::activateCenter));
        centerButton.active=localMode?runtime.canUseLocalActions():serverAvailable;
        centerButton.setTooltip(Tooltip.of(Text.literal(localMode?"移动时保持本地专用动作":"停止服务器专用动作")));
        var settings=addControl("settings",new SettingsIconButton(width-28,8,()->{
            releaseConsumed=true;client.setScreen(new PlayerModelScreen(runtime,this));
        }));
        settings.setTooltip(Tooltip.of(Text.literal("玩家模型设置")));
        // Match the existing flat navigation style; scope follows this exact screen source/instance.
        undisguiseButton=addControl(localMode?"undisguise-private":"undisguise-server",
                new FlatButton(layout.sourcePanelX(),layout.navigationY(),layout.panelWidth(),layout.backHeight(),
                        localMode?"解除私人伪装":"解除服务器伪装",this::undisguiseCurrentSource));
        undisguiseButton.active=canUndisguiseCurrentSource();
        undisguiseButton.setTooltip(Tooltip.of(Text.literal(localMode
                ?"关闭私人伪装；保留模型、参数和皮肤；不会解除服务器伪装"
                :"执行 /meplayeractions undisguise 请求解除本人服务器伪装；服务器校验权限；外部原生伪装仅停止 MPA 接管；私人设置保留")));
        int aw=layout.navigationWidth(),ah=layout.navigationHeight();
        var previous=addControl("previous",new FlatButton(layout.panelX(),layout.navigationY(),aw,ah,"<",()->changePage(-1)));
        var next=addControl("next",new FlatButton(layout.panelRight()-aw,layout.navigationY(),aw,ah,">",()->changePage(1)));
        previous.active=page>0;next.active=page+1<WheelSelection.pageCount(actions.size(),SLOTS);
        addControl("back",new FlatButton(layout.panelX(),layout.backY(),layout.panelWidth(),layout.backHeight(),
                "返回",()->{releaseConsumed=true;close();}));
        if(configGroup!=null && localMode && runtime.canEditLocalAppearance())buildConfigForms();
    }
    private String centerLabel() {
        if(!localMode)return "停止";
        String prefix=layout!=null && layout.scaled(40)<28?"锁:":"锁定:";
        return prefix+(runtime.localActionLocked()?"开":"关");
    }
    private <T extends FlatButton> T addControl(String id,T widget) {
        navigationControls.add(new NavigationControl(id,widget));return addDrawableChild(widget);
    }
    private List<ClientRuntime.Action> currentActions() {
        if(!localMode)return runtime.serverActions();
        if(!runtime.canUseLocalActions())return List.of();
        var profile=runtime.localModelProfile();
        if(profile!=menuProfile) {
            menuProfile=profile;menu=null;configSchema=null;categoryPages.clear();categoryPath.clear();clearConfig();restorePageOnCatalog=true;
            try {
                if(profile!=null && profile.isYsm()) {
                    String language=client.getLanguageManager().getLanguage();
                    menu=new ModelActionMenu(profile,language);configSchema=ModelConfigSchema.from(profile,language);
                }
            } catch(RuntimeException invalid){message="模型动作配置无法使用";}
        }
        if(menu==null){localEntries=List.of();return runtime.localActions();}
        localEntries=menu.entries();
        return localEntries.stream().map(entry->new ClientRuntime.Action(entry.id(),entry.label())).toList();
    }
    private int visibleSlots() {return WheelSelection.visibleSlots(page,actions.size());}
    private int index(int slot) {return WheelSelection.actionIndex(page,slot,actions.size(),SLOTS);}
    private String category() {return localMode && menu!=null?menu.categoryId():"";}
    private void rememberPage() {
        if(localMode && menu!=null)categoryPages.put(category(),page);
        if(!localMode || menu==null || menu.depth()==0) {
            var preferences=runtime.wheelPreferences();
            if(preferences.page(source())!=page)runtime.updateWheelPreferences(preferences.withPage(source(),page));
        }
    }
    private void changePage(int direction) {
        int next=WheelSelection.clampPage(page+direction,actions.size(),SLOTS);
        if(next==page)return;
        page=next;restorePageOnCatalog=false;rememberPage();clearHover();releaseConsumed=true;clearAndInit();
    }
    private void changedCategory() {
        page=categoryPages.getOrDefault(category(),menu.depth()==0?runtime.wheelPreferences().page(source()):0);
        restorePageOnCatalog=false;clearHover();clearConfig();releaseConsumed=true;clearAndInit();
    }
    private void select(int slot) {
        if(!sameSource())return;
        int selected=index(slot);if(selected<0)return;
        if(localMode && menu!=null) {
            var entry=localEntries.get(selected);
            if(entry.category() || entry.back()) {
                rememberPage();
                if(menu.enter(entry)) {
                    if(entry.back()){if(!categoryPath.isEmpty())categoryPath.removeLast();}
                    else categoryPath.add(entry.label());
                    changedCategory();
                } else message="此动作分类不可进入";
                return;
            }
        }
        String id=actions.get(selected).id();boolean played;
        if(localMode)played=runtime.canUseLocalActions() && runtime.playLocal(id);
        else {played=runtime.canUseServerActions();if(played)runtime.request("play",id);}
        if(!played){message=localMode?"选择并启用本地模型后可播放动作":"等待服务器动作频道";return;}
        rememberPage();releaseConsumed=true;message="已选择："+actions.get(selected).label();
        if(!locked)client.setScreen(parent);
    }
    private boolean hasConfig(int slot) {
        int selected=index(slot);
        return localMode && runtime.canEditLocalAppearance() && menu!=null && configSchema!=null && selected>=0 &&
                !localEntries.get(selected).configGroup().isEmpty() &&
                configSchema.group(localEntries.get(selected).configGroup()).isPresent();
    }
    private void openConfig(int slot) {
        if(!sameSource() || !hasConfig(slot))return;
        configGroupId=localEntries.get(index(slot)).configGroup();
        configGroup=configSchema.group(configGroupId).orElse(null);configScroll=0;releaseConsumed=true;
        clearAndInit(); // This same Screen continues to render both roulette and author form.
    }
    private void activateCenter() {
        if(!sameSource())return;
        releaseConsumed=true;
        if(localMode) {
            if(runtime.canUseLocalActions())runtime.setLocalActionLocked(!runtime.localActionLocked());
            centerButton.setMessage(Text.literal(centerLabel()));
        } else stop();
    }
    private void stop() {
        if(!sameSource())return;
        if(localMode)runtime.stopLocal();
        else if(runtime.canUseServerActions())runtime.request("stop","");
        else {message="等待服务器动作频道";return;}
        releaseConsumed=true;message="已停止";
    }
    private boolean canUndisguiseCurrentSource() {
        return localMode?runtime.canEditLocalAppearance() && runtime.localAppearance().enabled():runtime.canRequestServerUndisguise();
    }
    private void undisguiseCurrentSource() {
        if(!sameSource())return;
        releaseConsumed=true;clearHover();
        if(!canUndisguiseCurrentSource()) {
            message=localMode?"私人伪装未启用":"当前没有可请求解除的服务器伪装";return;
        }
        if(localMode) {
            runtime.disableLocalAppearance();
            if(runtime.localAppearance().enabled()){message="私人伪装未关闭";return;}
            if(runtime.serverOwnModelPresent())runtime.selectInteractionSource(false);
            client.setScreen(parent);
        } else if(!runtime.requestServerUndisguise()) {
            message="解除服务器伪装的请求未发送";return;
        } else {
            // A server request is complete only after the normal server state/unbind confirmation.
            message="已请求解除服务器伪装，等待服务器确认";undisguiseButton.active=false;
        }
    }
    @Override public void tick() {
        boolean present=runtime.hasOwnServerDisguise(),available=runtime.canUseServerActions();
        YsmModelProfile previousProfile=menuProfile;List<ClientRuntime.Action> current=currentActions();
        if(localMode!=runtime.interactionLocalMode() || !serverInstance.equals(runtime.serverOwnModelInstance()) ||
                present!=ownServerDisguise || available!=serverAvailable || previousProfile!=menuProfile || !current.equals(actions)) {
            clearHover();releaseConsumed=true;clearAndInit();
        }
        locked=runtime.wheelPreferences().keepOpen();
        if(centerButton!=null)centerButton.setMessage(Text.literal(centerLabel()));
        if(undisguiseButton!=null)undisguiseButton.active=canUndisguiseCurrentSource();
    }
    private void hover(double mouseX,double mouseY) {
        double x=(mouseX-centerX)/layout.scale(),y=(mouseY-centerY)/layout.scale();
        hovered=WheelSelection.classicSlotAt(x,y,50,100,visibleSlots());hoveredConfig=false;
        int configSlot=WheelSelection.classicSlotAt(x,y,20,50,visibleSlots());
        if(hasConfig(configSlot)){hovered=configSlot;hoveredConfig=true;}
        hoveredCenter=centerButton!=null && centerButton.isMouseOver(mouseX,mouseY);
    }
    @Override public void mouseMoved(double mouseX,double mouseY) {
        keyboardSelection=false;hover(mouseX,mouseY);super.mouseMoved(mouseX,mouseY);
    }
    @Override public boolean mouseClicked(Click click,boolean doubled) {
        if(releaseType==InputUtil.Type.MOUSE && click.button()==releaseKey) {
            if(!releaseKeyDown)beginHoldSelection();return true;
        }
        hover(click.x(),click.y());
        if(super.mouseClicked(click,doubled)){releaseConsumed=true;return true;}
        if(click.button()!=GLFW.GLFW_MOUSE_BUTTON_LEFT)return false;
        if(index(hovered)>=0){if(hoveredConfig)openConfig(hovered);else select(hovered);return true;}
        return false;
    }
    @Override public boolean mouseReleased(Click click) {
        if(releaseType==InputUtil.Type.MOUSE && click.button()==releaseKey){releaseSelection();return true;}
        return super.mouseReleased(click);
    }
    @Override public boolean mouseScrolled(double mouseX,double mouseY,double horizontal,double vertical) {
        if(vertical==0)return super.mouseScrolled(mouseX,mouseY,horizontal,vertical);
        if(mouseX>=layout.panelX() && configGroup!=null){scrollConfig(vertical>0?-20:20);return true;}
        changePage(vertical>0?-1:1);return true;
    }
    @Override public boolean keyPressed(KeyInput input) {
        int key=input.key();
        if(releaseMatches(input)){if(!releaseKeyDown)beginHoldSelection();return true;}
        if(getFocused() instanceof SliderWidget && key!=GLFW.GLFW_KEY_ESCAPE && super.keyPressed(input))return true;
        if(key>=GLFW.GLFW_KEY_1 && key<=GLFW.GLFW_KEY_8){
            hovered=key-GLFW.GLFW_KEY_1;keyboardSelection=true;hoveredConfig=false;hoveredCenter=false;select(hovered);return true;
        }
        if(key==GLFW.GLFW_KEY_ENTER || key==GLFW.GLFW_KEY_KP_ENTER) {
            if(getFocused()!=null && super.keyPressed(input))return true;
            if(hoveredCenter)activateCenter();else select(hovered);return true;
        }
        if(key==GLFW.GLFW_KEY_LEFT || key==GLFW.GLFW_KEY_PAGE_UP){changePage(-1);return true;}
        if(key==GLFW.GLFW_KEY_RIGHT || key==GLFW.GLFW_KEY_PAGE_DOWN){changePage(1);return true;}
        return super.keyPressed(input);
    }
    @Override public boolean keyReleased(KeyInput input) {
        if(releaseMatches(input)){releaseSelection();return true;}return super.keyReleased(input);
    }

    @Override public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        context.fill(0,0,width,height,0x35000000);
        if(!keyboardSelection)hover(mouseX,mouseY);
        context.drawCenteredTextWithShadow(textRenderer,localMode?"本地动作":"服务器动作",width/2,8,0xfff3f0e0);
        var polygons=new ArrayList<Map<String,Object>>();
        for(int slot=0;slot<visibleSlots();slot++) {
            boolean selected=hovered==slot && !hoveredConfig,config=hasConfig(slot);
            if(selected && config) {
                drawRadialSegment(context,polygons,slot,50,115,0xf0ffb100,"action");
                drawRadialSegment(context,polygons,slot,25,50,0x90000000,"action");
            } else drawRadialSegment(context,polygons,slot,25,selected?115:105,selected?0xf0ffb100:0x90000000,"action");
            if(config)drawRadialSegment(context,polygons,slot,hoveredConfig && hovered==slot?15:25,50,
                    hoveredConfig && hovered==slot?0xf000ceff:0x7000ceff,"config");
        }
        renderedPolygons=List.copyOf(polygons);
        renderRadialLabels(context);renderPathAndPage(context);
        super.render(context,mouseX,mouseY,delta);renderConfigForms(context,mouseX,mouseY,delta);
        if(configGroup==null) {
            String detail=actions.isEmpty()?(localMode?"暂无本地动作":"等待服务器动作"):
                    localMode?"点击动作内侧 ⚙ 配置组件":"服务器下发动作";
            context.drawWrappedTextWithShadow(textRenderer,Text.literal(detail),layout.panelX()+2,
                    layout.viewportY()+3,Math.max(1,layout.panelWidth()-4),0xffb8bec8);
        }
        context.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth(scrollHint(),Math.max(1,width-24)),
                width/2,height-28,0xffb8bec8);
        if(!message.isEmpty())context.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth(message,Math.max(1,width-24)),
                width/2,height-15,0xfff3f0e0);
        int selected=index(hovered);
        if(selected>=0) {
            String description=localMode && menu!=null?localEntries.get(selected).description():"";
            context.drawTooltip(textRenderer,Text.literal((hoveredConfig?"配置 · ":"")+actions.get(selected).label()
                    +(description.isEmpty()?"":"\n"+description)),mouseX,mouseY);
        }
    }
    private void drawRadialSegment(DrawContext context,List<Map<String,Object>> polygons,int slot,double innerRadius,
                                   double outerRadius,int color,String kind) {
        var vertices=WheelSelection.polygon(slot,innerRadius*layout.scale(),outerRadius*layout.scale()).stream()
                .map(point->new WheelSelection.Point(centerX+point.x(),centerY+point.y())).toList();
        context.state.addSimpleElement(RadialSliceRenderState.of(context.getMatrices(),vertices,color,null,emittedVertices));
        polygons.add(Map.of("slot",slot,"kind",kind,"color",color,
                "vertices",vertices.stream().map(point->List.of(point.x(),point.y())).toList()));
    }
    private WheelSelection.Point pointAt(int slot,double radius) {
        var point=WheelSelection.labelPoint(slot,radius*layout.scale());
        return new WheelSelection.Point(centerX+point.x(),centerY+point.y());
    }
    private boolean showShortcutHints() {return page==0 && (menu==null || menu.depth()==0);}
    private List<OrderedText> wrappedLabel(int selected) {
        var lines=textRenderer.wrapLines(Text.literal(actions.get(selected).label()),Math.max(32,layout.scaled(50)));
        return lines.size()<=4?lines:lines.subList(0,4);
    }
    private void renderRadialLabels(DrawContext context) {
        for(int slot=0;slot<visibleSlots();slot++) {
            int selected=index(slot);var point=pointAt(slot,65);int x=Math.round(point.x()),y=Math.round(point.y())-4;
            var lines=wrappedLabel(selected);int firstY=y-lines.size()*textRenderer.fontHeight+2+(showShortcutHints()?0:9);
            boolean category=localMode && menu!=null && (localEntries.get(selected).category() || localEntries.get(selected).back());
            for(var line:lines) {
                context.drawCenteredTextWithShadow(textRenderer,line,x,firstY,category?0xffff6262:0xfff3f0e0);
                firstY+=textRenderer.fontHeight;
            }
            // Actual Screen numeric bindings, without inventing unregistered author global hotkeys.
            if(showShortcutHints())context.drawCenteredTextWithShadow(textRenderer,"[ "+(slot+1)+" ]",x,y+4,0xffffff55);
            if(hasConfig(slot)) {
                var gear=pointAt(slot,35);
                context.drawCenteredTextWithShadow(textRenderer,"⚙",Math.round(gear.x()),Math.round(gear.y())-4,0xffffc44f);
            }
        }
    }
    private String pathText() {
        String root=localMode?"本地":"服务器";
        return categoryPath.isEmpty()?root:root+" > "+String.join(" > ",categoryPath);
    }
    private String scrollHint() {
        boolean paged=WheelSelection.pageCount(actions.size(),SLOTS)>1;
        if(configGroup!=null)return paged?"滚轮快捷调整轮盘：切页；右侧滚动配置":"当前仅一页；右侧滚轮滚动配置";
        return paged?"滚轮快捷调整轮盘：切换动作页":"当前仅一页；悬停选择或点击动作";
    }
    private void renderPathAndPage(DrawContext context) {
        int left=layout.panelX()+layout.navigationWidth()+2,right=layout.panelRight()-layout.navigationWidth()-2;
        context.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth("路径："+pathText(),Math.max(1,right-left)),
                (left+right)/2,layout.navigationY()+2,0xfff3f0e0);
        int pageTop=layout.navigationY()+Math.max(12,layout.scaled(15)),pageBottom=layout.navigationY()+layout.navigationHeight();
        context.fill(left,pageTop,right,pageBottom,0xcf000000);
        context.drawCenteredTextWithShadow(textRenderer,(page+1)+"/"+WheelSelection.pageCount(actions.size(),SLOTS),
                (left+right)/2,pageTop+(pageBottom-pageTop-textRenderer.fontHeight)/2,0xff55ffff);
    }

    private void buildConfigForms() {
        String modelId=runtime.localAppearance().modelId();
        int contentWidth=layout.formWidth(),offset=0,savedScroll=configScroll;
        configScroll=0;
        Map<String,Double> variables=runtime.localModelVariables(modelId);
        for(var form:configGroup.forms()) {
            int top=offset;List<ClickableWidget> widgets=new ArrayList<>();
            if(form.kind()==ModelConfigSchema.Kind.CHECKBOX) {
                var widget=new AuthorFormWidgets.Check(textRenderer,layout.panelX(),layout.viewportY()+offset,contentWidth,12,form.title(),
                        ()->readForm(form)>0,()->applyForm(modelId,form.checkboxScript(readForm(form)<=0),null,-1),this::insideFormViewport);
                widget.setTooltip(Tooltip.of(Text.literal(form.description())));
                widgets.add(widget);registerFormWidget(widget);offset+=14;
            } else if(form.kind()==ModelConfigSchema.Kind.RANGE) {
                var widget=new AuthorFormWidgets.Range(textRenderer,layout.panelX(),layout.viewportY()+offset,contentWidth,15,form,
                        readForm(form,variables),requested->applyForm(modelId,form.rangeScript(requested),null,-1),this::insideFormViewport);
                widget.setTooltip(Tooltip.of(Text.literal(form.description())));
                widgets.add(widget);registerFormWidget(widget);offset+=17;
            } else {
                int maxLabel=0;
                for(var choice:form.choices())maxLabel=Math.max(maxLabel,textRenderer.getWidth(choice.label()));
                int columns=AuthorFormLayout.radioColumns(contentWidth,maxLabel,form.choices().size()),cellWidth=Math.max(1,contentWidth/columns);offset+=14;
                for(int choice=0;choice<form.choices().size();choice++) {
                    int choiceIndex=choice;
                    var widget=new AuthorFormWidgets.Check(textRenderer,layout.panelX()+choice%columns*cellWidth,
                            layout.viewportY()+offset+choice/columns*14,cellWidth,12,form.choices().get(choice).label(),
                            ()->form.selectedIndex(readForm(form))==choiceIndex,
                            ()->applyForm(modelId,form.radioScript(choiceIndex),form.key(),choiceIndex),this::insideFormViewport);
                    widget.setTooltip(Tooltip.of(Text.literal(form.description())));
                    widgets.add(widget);registerFormWidget(widget);
                }
                offset+=AuthorFormLayout.radioRows(form.choices().size(),columns)*14+3;
            }
            formRows.add(new FormRow(form,top,List.copyOf(widgets)));
        }
        configContentHeight=offset;
        int sw=layout.scrollbarWidth(),sh=Math.max(22,layout.scaled(60));
        scrollUpButton=addControl("config-up",new FlatButton(layout.panelRight()-sw,layout.viewportY(),sw,sh,"↑",()->scrollConfig(-50)));
        scrollDownButton=addControl("config-down",new FlatButton(layout.panelRight()-sw,
                layout.viewportY()+layout.viewportHeight()-sh,sw,sh,"↓",()->scrollConfig(50)));
        scrollConfig(savedScroll);
    }
    private void registerFormWidget(ClickableWidget widget) {formWidgets.add(widget);addSelectableChild(widget);}
    private double readForm(ModelConfigSchema.Form form) {return readForm(form,runtime.localModelVariables(runtime.localAppearance().modelId()));}
    private double readForm(ModelConfigSchema.Form form,Map<String,Double> values) {
        if(formValues.containsKey(form.key()))return formValues.get(form.key());
        try {double value=runtime.readLocalModelExpression(runtime.localAppearance().modelId(),form.expression(),values);
            if(Double.isFinite(value)){formValues.put(form.key(),value);return value;}}catch(RuntimeException invalid){ }
        formValues.put(form.key(),0d);
        message="模型配置值无法读取："+form.title();return 0;
    }
    private boolean applyForm(String modelId,String script,String radioKey,int radioIndex) {
        releaseConsumed=true;
        if(!sameSource() || !localMode || !runtime.canEditLocalAppearance() ||
                !runtime.localAppearance().modelId().equals(modelId))return false;
        Map<String,Integer> radios=new LinkedHashMap<>(runtime.options.modelProfile(modelId).radioSelections());
        if(radioKey!=null)radios.put(radioKey,radioIndex);
        boolean accepted=runtime.runLocalScripts(modelId,List.of(script),radios);
        if(accepted)formValues.clear();
        message=accepted?"已更新模型组件":"模型组件设置未保存";return accepted;
    }
    private boolean insideFormViewport(double x,double y) {
        return configGroup!=null && x>=layout.panelX() && x<layout.formRight() &&
                y>=layout.viewportY() && y<layout.viewportY()+layout.viewportHeight();
    }
    private void scrollConfig(int amount) {
        int next=WheelSelection.clampScroll(configScroll+amount,configContentHeight,layout.viewportHeight());
        int shift=configScroll-next;configScroll=next;
        for(var widget:formWidgets) {
            widget.setY(widget.getY()+shift);
            widget.visible=widget.getBottom()>layout.viewportY() && widget.getY()<layout.viewportY()+layout.viewportHeight();
            widget.active=widget.visible && runtime.canEditLocalAppearance() &&
                    (!(widget instanceof AuthorFormWidgets.Range range) || range.form.maximum()!=range.form.minimum());
            if(!widget.visible && getFocused()==widget){setFocused(null);setDragging(false);}
        }
        if(scrollUpButton!=null)scrollUpButton.active=configScroll>0;
        if(scrollDownButton!=null)scrollDownButton.active=configScroll<Math.max(0,configContentHeight-layout.viewportHeight());
    }
    private void renderConfigForms(DrawContext context,int mouseX,int mouseY,float delta) {
        if(configGroup==null)return;
        context.enableScissor(layout.panelX(),layout.viewportY(),layout.formRight(),layout.viewportY()+layout.viewportHeight());
        int clippedMouseY=insideFormViewport(mouseX,mouseY)?mouseY:-1000;
        for(var row:formRows)if(row.form.kind()==ModelConfigSchema.Kind.RADIO)
            context.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(row.form.title(),layout.formWidth()),
                    layout.panelX(),layout.viewportY()+row.top-configScroll+2,0xfff3f0e0);
        for(var widget:formWidgets)widget.render(context,mouseX,clippedMouseY,delta);
        context.disableScissor();
    }

    private record NavigationControl(String id,FlatButton widget) { }
    private record FormRow(ModelConfigSchema.Form form,int top,List<ClickableWidget> controls) { }
    /** OpenYSM FlatColorButton's flat fill and cream hover outline, with native focus/narration. */
    private class FlatButton extends ButtonWidget {
        FlatButton(int x,int y,int width,int height,String label,Runnable action) {
            super(x,y,width,height,net.minecraft.text.Text.literal(label),button->action.run(),DEFAULT_NARRATION_SUPPLIER);
        }
        @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
            context.fill(getX(),getY(),getRight(),getBottom(),active?-12369342:0x80525252);
            if(isHovered() || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-790560);
            context.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth(getMessage().getString(),Math.max(1,getWidth()-4)),
                    getX()+getWidth()/2,getY()+(getHeight()-textRenderer.fontHeight)/2,active?0xfff3f0e0:0xffa0a0a0);
        }
    }
    /** Same source settings texture and flat icon treatment as the existing gallery settings entry. */
    private final class SettingsIconButton extends FlatButton {
        SettingsIconButton(int x,int y,Runnable action) {super(x,y,20,20,"玩家模型设置",action);}
        @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
            context.fill(getX(),getY(),getRight(),getBottom(),-12369342);
            if(isHovered() || isFocused())context.drawStrokedRectangle(getX(),getY(),getWidth(),getHeight(),-790560);
            context.drawTexture(RenderPipelines.GUI_TEXTURED,SETTINGS_ICON,getX()+2,getY()+2,0f,0f,16,16,32,32,32,32);
        }
    }
    public Map<String,Object> diagnostics() {
        List<Map<String,Object>> labels=new ArrayList<>(),configSlots=new ArrayList<>(),visibleActions=new ArrayList<>();
        if(layout!=null)for(int slot=0;slot<visibleSlots();slot++) {
            int selected=index(slot);var point=pointAt(slot,65);int x=Math.round(point.x()),y=Math.round(point.y());
            visibleActions.add(Map.of("slot",slot,"id",actions.get(selected).id(),"label",actions.get(selected).label(),
                    "x",x,"y",y,"shortcut",Integer.toString(slot+1),"configGroup",hasConfig(slot)?localEntries.get(selected).configGroup():"",
                    "category",localMode && menu!=null && localEntries.get(selected).category(),"back",localMode && menu!=null && localEntries.get(selected).back()));
            var lines=wrappedLabel(selected);int ly=y-4-lines.size()*textRenderer.fontHeight+2+(showShortcutHints()?0:9);
            for(var line:lines) {
                int lw=textRenderer.getWidth(line);
                labels.add(Map.of("slot",slot,"text",actions.get(selected).label(),"x",x-lw/2,"y",ly,"width",lw,"height",textRenderer.fontHeight));
                ly+=textRenderer.fontHeight;
            }
            if(showShortcutHints())labels.add(textRectangle("[ "+(slot+1)+" ]",x,y,slot));
            if(hasConfig(slot)) {
                var gear=pointAt(slot,35);int gx=Math.round(gear.x()),gy=Math.round(gear.y());
                labels.add(textRectangle("⚙",gx,gy-4,slot));
                configSlots.add(Map.of("slot",slot,"group",localEntries.get(selected).configGroup(),"x",gx,"y",gy));
            }
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("centerX",centerX);result.put("centerY",centerY);result.put("innerRadius",inner);result.put("outerRadius",outer);
        result.put("scrollHint",scrollHint());result.put("settingsIcon",SETTINGS_ICON.toString());
        result.put("page",page);result.put("localMode",localMode);result.put("source",source().name());
        result.put("locked",locked);result.put("keepOpen",locked);result.put("hovered",hovered);result.put("hoveredCenter",hoveredCenter);
        result.put("labels",labels);result.put("visibleActionIds",actions.stream().skip((long)page*SLOTS).limit(SLOTS).map(ClientRuntime.Action::id).toList());
        result.put("configSlots",configSlots);result.put("actionLocked",runtime.localActionLocked());
        result.put("category",category());result.put("categoryId",category());result.put("depth",localMode && menu!=null?menu.depth():0);
        result.put("pageCount",WheelSelection.pageCount(actions.size(),SLOTS));result.put("paginationVisible",WheelSelection.pageCount(actions.size(),SLOTS)>1);
        result.put("paginationControlsVisible",true);result.put("ownServerDisguise",runtime.hasOwnServerDisguise());result.put("serverAvailable",serverAvailable);
        result.put("serverModelInstance",runtime.serverOwnModelInstance());result.put("serverAppearance",runtime.ownServerAppearanceDiagnostics());
        result.put("effectiveLocalMode",runtime.interactionLocalMode());
        result.put("controls",navigationControls.stream().map(control->widgetRectangle(control.id,control.widget)).toList());
        result.put("scopeButtonsVisible",false);result.put("localModelDetailsVisible",false);
        result.put("releaseKey",releaseKey);result.put("releaseType",releaseType.name());result.put("releaseConsumed",releaseConsumed);
        result.put("rememberedSource",runtime.wheelPreferences().source().name());
        result.put("rememberedClientPage",runtime.wheelPreferences().clientPage());result.put("rememberedServerPage",runtime.wheelPreferences().serverPage());
        result.put("entries",localMode && menu!=null?localEntries.stream().map(entry->Map.of("id",entry.id(),"configGroup",entry.configGroup(),
                "category",entry.category(),"back",entry.back())).toList():List.of());
        result.put("upstreamRoulette",true);result.put("upstreamSource","OpenYSM-Updated/AnimationRouletteScreen@0306e1f");
        result.put("polygon",true);result.put("polygons",renderedPolygons);result.put("emittedVertices",emittedVertices.get());
        result.put("actions",visibleActions);result.put("path",pathText());result.put("categoryPath",List.copyOf(categoryPath));
        result.put("formPanel",formDiagnostics());return result;
    }
    private Map<String,Object> formDiagnostics() {
        List<Map<String,Object>> forms=new ArrayList<>();
        for(var row:formRows) {
            var controls=new ArrayList<Map<String,Object>>();
            for(int i=0;i<row.controls.size();i++) {
                var widget=row.controls.get(i);
                String id=row.form.kind()==ModelConfigSchema.Kind.RADIO?"radio:"+i:row.form.kind()==ModelConfigSchema.Kind.RANGE?"range":"checkbox";
                var data=new LinkedHashMap<>(widgetRectangle(id,widget));data.put("visible",widget.visible);
                if(widget instanceof AuthorFormWidgets.Check checkbox)data.put("selected",checkbox.selected());
                if(widget instanceof AuthorFormWidgets.Range range)data.put("value",range.currentValue());
                if(row.form.kind()==ModelConfigSchema.Kind.RADIO)data.put("choice",i);
                controls.add(data);
            }
            var form=new LinkedHashMap<String,Object>();
            form.put("key",row.form.key());form.put("kind",row.form.kind().name());form.put("title",row.form.title());
            form.put("expression",row.form.expression());form.put("value",readForm(row.form));form.put("controls",controls);
            form.put("min",row.form.minimum());form.put("max",row.form.maximum());form.put("step",row.form.step());forms.add(form);
        }
        return Map.of("visible",configGroup!=null,"group",configGroupId,"x",layout==null?0:layout.panelX(),
                "y",layout==null?0:layout.viewportY(),"width",layout==null?0:layout.formWidth(),
                "height",layout==null?0:layout.viewportHeight(),"scroll",configScroll,"contentHeight",configContentHeight,
                "maxScroll",layout==null?0:Math.max(0,configContentHeight-layout.viewportHeight()),"forms",forms);
    }
    private static Map<String,Object> widgetRectangle(String id,ClickableWidget widget) {
        return Map.of("id",id,"label",widget.getMessage().getString(),"x",widget.getX(),"y",widget.getY(),
                "width",widget.getWidth(),"height",widget.getHeight(),"active",widget.active);
    }
    private Map<String,Object> textRectangle(String text,int center,int y,int slot) {
        int textWidth=textRenderer.getWidth(text);
        return Map.of("slot",slot,"text",text,"x",center-textWidth/2,"y",y,"width",textWidth,"height",textRenderer.fontHeight);
    }
    public boolean navigateBack() {
        if(localMode && menu!=null && menu.depth()>0) {
            rememberPage();
            if(menu.back()){if(!categoryPath.isEmpty())categoryPath.removeLast();changedCategory();return true;}
        }
        return false;
    }
    @Override public void close() {if(!navigateBack())client.setScreen(parent);}
    @Override public boolean shouldPause() {return false;}
}
