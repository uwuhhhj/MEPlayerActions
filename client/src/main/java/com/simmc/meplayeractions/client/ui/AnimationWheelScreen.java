package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/** Radial local previews and server actions, with no changes to native game posture. */
public final class AnimationWheelScreen extends Screen {
    private static final int SLOTS = 8;
    private final ClientRuntime runtime;
    private final Screen parent;
    private boolean localMode, locked, serverAvailable, releaseConsumed, releaseKeyDown, keyboardSelection;
    private int page, hovered=-1, centerX, centerY, inner, outer, sideX, sideWidth, releaseKey=GLFW.GLFW_KEY_G;
    private List<ClientRuntime.Action> actions=List.of();
    private List<WheelSelection.Run> ring=List.of();
    private String message="";
    private com.simmc.meplayeractions.client.model.YsmModelProfile menuProfile;
    private ModelActionMenu menu;
    private List<ModelActionMenu.Entry> localEntries=List.of();
    private boolean hoveredConfig;

    public AnimationWheelScreen(ClientRuntime runtime) {
        this(runtime, runtime.localAppearance().enabled() || !runtime.serverOwnModelReady(), null);
        beginHoldSelection();
    }
    public AnimationWheelScreen(ClientRuntime runtime, boolean localMode, Screen parent) {
        super(Text.literal("动作轮盘")); this.runtime=runtime; this.localMode=localMode; this.parent=parent;
        releaseConsumed=true;
    }
    /** The entry key can be rebound independently of this menu. */
    public void setReleaseKey(int keyCode) { releaseKey=keyCode; beginHoldSelection(); }
    public void beginHoldSelection() { releaseConsumed=false; releaseKeyDown=true; }
    /** Called by the entry binding on release. Repeated tick callbacks never replay an action. */
    public void releaseSelection() {
        releaseKeyDown=false;
        if (releaseConsumed || client==null || client.currentScreen!=this) return;
        releaseConsumed=true;
        if (index(hovered)>=0) { if(hoveredConfig) openConfig(hovered); else select(hovered); } else if (!locked) close();
    }
    @Override protected void init() {
        serverAvailable=runtime.serverBridgeReady() && runtime.serverOwnModelReady();
        actions=currentActions(); page=Math.max(0,Math.min(page,WheelSelection.pageCount(actions.size(),SLOTS)-1));
        sideWidth=Math.max(104,Math.min(156,width/3)); sideX=width-sideWidth-12;
        centerX=(sideX-12)/2; centerY=height/2+10;
        outer=Math.max(28,Math.min((height-108)/2,(sideX-28)/2)); inner=Math.max(16,outer/3);
        ring=WheelSelection.ring(inner,outer,SLOTS);
        int scopeWidth=Math.max(60,(width-30)/2);
        addDrawableChild(ButtonWidget.builder(Text.literal(localMode ? "本地动作 ✓" : "本地动作"), b -> switchMode(true))
                .dimensions(12,32,scopeWidth,20).build());
        var server=ButtonWidget.builder(Text.literal(localMode ? "服务器动作" : "服务器动作 ✓"), b -> switchMode(false))
                .dimensions(18+scopeWidth,32,scopeWidth,20).build();
        server.setTooltip(Tooltip.of(Text.literal("需要服务器支持和服务器模型；选择动作会同步给其他观看者")));
        addDrawableChild(server);
        int controlsY=Math.max(78,height-(height>=244 ? 151 : 127));
        var lock=ButtonWidget.builder(Text.literal(locked ? "保持打开：开" : "保持打开：关"), b -> {locked=!locked;clearAndInit();})
                .dimensions(sideX,controlsY,sideWidth,20).build();
        lock.setTooltip(Tooltip.of(Text.literal("选择后保留菜单；动作锁定是另一项独立设置"))); addDrawableChild(lock);
        var actionLock=ButtonWidget.builder(Text.literal(runtime.localActionLocked() ? "动作锁定：开" : "动作锁定：关"), b -> {
            runtime.setLocalActionLocked(!runtime.localActionLocked()); clearAndInit();
        }).dimensions(sideX,controlsY+24,sideWidth,20).build();
        actionLock.setTooltip(Tooltip.of(Text.literal("开启后移动不会取消本地额外动作；不会锁定轮盘或改变真实姿态")));
        addDrawableChild(actionLock).active=localMode;
        addDrawableChild(ButtonWidget.builder(Text.literal("停止当前动作"),b -> stop())
                .dimensions(sideX,controlsY+48,sideWidth,20).build()).active=localMode || serverAvailable;
        var settings=ButtonWidget.builder(Text.literal("模型参数…"),b -> {
            if(client!=null)client.setScreen(new ModelSettingsScreen(runtime,runtime.localAppearance().modelId(),this));
        }).dimensions(sideX,controlsY+72,sideWidth,20).build();
        settings.setTooltip(Tooltip.of(Text.literal("编辑本地模型、缩放和 XYZ；这些参数仅自己可见")));addDrawableChild(settings);
        if(height>=244)addDrawableChild(ButtonWidget.builder(Text.literal("模型图库…"),b -> {
            if(client!=null)client.setScreen(new LocalAppearanceScreen(runtime,this));
        }).dimensions(sideX,controlsY+96,sideWidth,20).build());
        int navWidth=Math.max(42,Math.min(76,(sideX-30)/3)), navY=height-25;
        addDrawableChild(ButtonWidget.builder(Text.literal("上一页"),b -> changePage(-1))
                .dimensions(12,navY,navWidth,20).build()).active=page>0;
        addDrawableChild(ButtonWidget.builder(Text.literal("下一页"),b -> changePage(1))
                .dimensions(18+navWidth,navY,navWidth,20).build()).active=page+1<WheelSelection.pageCount(actions.size(),SLOTS);
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"),b -> close())
                .dimensions(sideX,navY,sideWidth,20).build());
    }
    private List<ClientRuntime.Action> currentActions() {
        if(!localMode) return runtime.serverBridgeReady() && runtime.serverOwnModelReady() ? runtime.actions() : List.of();
        var profile=runtime.localModelProfile();
        if(profile!=menuProfile) {
            menuProfile=profile; menu=null; page=0;
            try { if(profile!=null && profile.isYsm()) menu=new ModelActionMenu(profile, client==null ? "zh_cn" : client.getLanguageManager().getLanguage()); }
            catch(RuntimeException invalid) { message="模型动作配置无法使用"; }
        }
        if(menu==null) { localEntries=List.of(); return runtime.localActions(); }
        localEntries=menu.entries();
        return localEntries.stream().map(entry -> new ClientRuntime.Action(entry.id(),entry.label())).toList();
    }
    private int index(int slot) { return WheelSelection.actionIndex(page,slot,actions.size(),SLOTS); }
    private void switchMode(boolean local) {localMode=local;menu=null;menuProfile=null;page=0;hovered=-1;keyboardSelection=false;message="";clearAndInit();}
    private void changePage(int direction) {
        page=Math.max(0,Math.min(page+direction,WheelSelection.pageCount(actions.size(),SLOTS)-1));
        hovered=-1;keyboardSelection=false;clearAndInit();
    }
    private void select(int slot) {
        int index=index(slot); if(index<0)return;
        String id=actions.get(index).id(); boolean played;
        if(localMode && menu!=null) {
            var entry=localEntries.get(index);
            if(entry.category() || entry.back()) {
                if(menu.enter(entry)) { page=0; hovered=-1; releaseConsumed=true; clearAndInit(); }
                else message="此动作分类不可进入";
                return;
            }
        }
        if(localMode) played=runtime.playLocal(id);
        else {
            played=runtime.serverBridgeReady() && runtime.serverOwnModelReady();
            if(played)runtime.request("play",id);
        }
        if(!played){message=localMode ? "先选择并启用本地模型，等待加载完成" : "先连接支持的服务器并使用服务器模型";return;}
        releaseConsumed=true;
        message="已选择："+actions.get(index).label();
        if(!locked && client!=null)client.setScreen(parent);
    }
    private boolean hasConfig(int slot) {
        int index=index(slot); return localMode && menu!=null && index>=0 && !localEntries.get(index).configGroup().isEmpty();
    }
    private boolean inConfigRing(double x,double y) {
        double distance=x*x+y*y, edge=inner+(outer-inner)*.42;
        return distance>=inner*inner && distance<edge*edge;
    }
    private void openConfig(int slot) {
        if(!hasConfig(slot))return;
        releaseConsumed=true;
        client.setScreen(new ModelConfigScreen(runtime,runtime.localAppearance().modelId(),localEntries.get(index(slot)).configGroup(),this));
    }
    private void stop() {
        if(localMode)runtime.stopLocal();
        else if(runtime.serverBridgeReady() && runtime.serverOwnModelReady())runtime.request("stop","");
        message="已停止";
    }
    @Override public void tick() {
        boolean available=runtime.serverBridgeReady() && runtime.serverOwnModelReady();
        if(available!=serverAvailable || !currentActions().equals(actions)){hovered=-1;keyboardSelection=false;clearAndInit();}
    }
    @Override public void mouseMoved(double mouseX,double mouseY) {
        keyboardSelection=false;hovered=WheelSelection.slotAt(mouseX-centerX,mouseY-centerY,inner,outer,SLOTS);
        hoveredConfig=hasConfig(hovered) && inConfigRing(mouseX-centerX,mouseY-centerY);
        super.mouseMoved(mouseX,mouseY);
    }
    @Override public boolean mouseClicked(Click click,boolean doubled) {
        if(super.mouseClicked(click,doubled))return true;
        if(click.button()!=GLFW.GLFW_MOUSE_BUTTON_LEFT)return false;
        double x=click.x()-centerX,y=click.y()-centerY;
        if(x*x+y*y<inner*inner){stop();return true;}
        int slot=WheelSelection.slotAt(x,y,inner,outer,SLOTS);
        if(index(slot)>=0){if(hasConfig(slot) && inConfigRing(x,y))openConfig(slot);else select(slot);return true;}return false;
    }
    @Override public boolean mouseScrolled(double mouseX,double mouseY,double horizontal,double vertical) {
        if(vertical!=0){changePage(vertical>0 ? -1 : 1);return true;}return super.mouseScrolled(mouseX,mouseY,horizontal,vertical);
    }
    @Override public boolean keyPressed(KeyInput input) {
        int key=input.key();
        if(key==releaseKey){if(!releaseKeyDown)beginHoldSelection();return true;}
        if(key>=GLFW.GLFW_KEY_1 && key<=GLFW.GLFW_KEY_8){hovered=key-GLFW.GLFW_KEY_1;keyboardSelection=true;hoveredConfig=false;select(hovered);return true;}
        if(key==GLFW.GLFW_KEY_ENTER || key==GLFW.GLFW_KEY_KP_ENTER){select(hovered);return true;}
        if(key==GLFW.GLFW_KEY_LEFT || key==GLFW.GLFW_KEY_PAGE_UP){changePage(-1);return true;}
        if(key==GLFW.GLFW_KEY_RIGHT || key==GLFW.GLFW_KEY_PAGE_DOWN){changePage(1);return true;}
        return super.keyPressed(input);
    }
    @Override public boolean keyReleased(KeyInput input) {
        if(input.key()==releaseKey){releaseSelection();return true;}return super.keyReleased(input);
    }
    @Override public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        context.fill(0,0,width,height,0x95101823);
        if(!keyboardSelection) {
            hovered=WheelSelection.slotAt(mouseX-centerX,mouseY-centerY,inner,outer,SLOTS);
            hoveredConfig=hasConfig(hovered) && inConfigRing(mouseX-centerX,mouseY-centerY);
        }
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,12,0xffffffff);
        for(var run:ring) {
            int color=index(run.slot())<0 ? 0xAA1B2631 : run.slot()==hovered ? 0xF05290B8
                    : run.slot()%2==0 ? 0xE02E4053 : 0xE0273749;
            context.fill(centerX+run.x1(),centerY+run.y(),centerX+run.x2(),centerY+run.y()+1,color);
        }
        for(int y=-inner;y<inner;y++) {
            int half=(int)Math.sqrt(inner*inner-y*y);
            context.fill(centerX-half,centerY+y,centerX+half,centerY+y+1,0xDF172330);
        }
        context.drawCenteredTextWithShadow(textRenderer,localMode ? "本地" : "服务器",centerX,centerY-10,0xffbcd7ea);
        context.drawCenteredTextWithShadow(textRenderer,"停止",centerX,centerY+3,0xffffffff);
        for(int slot=0;slot<SLOTS;slot++) {
            int index=index(slot); if(index<0)continue;
            double angle=-Math.PI/2+slot*Math.PI*2/SLOTS, radius=(inner+outer)*.56;
            int x=centerX+(int)(Math.cos(angle)*radius),y=centerY+(int)(Math.sin(angle)*radius);
            String label=slotLabel(index);
            context.drawCenteredTextWithShadow(textRenderer,Integer.toString(slot+1),x,y-9,0xffa9c8dc);
            if(outer>=60)context.drawCenteredTextWithShadow(textRenderer,label,x,y+2,0xffffffff);
            if(hasConfig(slot)) {
                double gearRadius=inner+(outer-inner)*.24;
                context.drawCenteredTextWithShadow(textRenderer,"⚙",centerX+(int)(Math.cos(angle)*gearRadius),centerY+(int)(Math.sin(angle)*gearRadius)-4,hoveredConfig && slot==hovered ? 0xffffd589 : 0xffa9c8dc);
            }
        }
        var profile=runtime.localAppearance();
        int textY=66;
        context.drawTextWithShadow(textRenderer,Text.literal(localMode ? "基础模型参数" : "本地模型参数"),sideX,textY,0xffffffff);
        if(height>=300) {
            context.drawTextWithShadow(textRenderer,Text.literal(textRenderer.trimToWidth(profile.modelId(),sideWidth)),sideX,textY+14,0xffa4bbd6);
            context.drawTextWithShadow(textRenderer,Text.literal("缩放 "+number(profile.scale())),sideX,textY+28,0xffc6d5ec);
            context.drawTextWithShadow(textRenderer,Text.literal(textRenderer.trimToWidth("XYZ "+number(profile.offsetX())+" / "+number(profile.offsetY())+" / "+number(profile.offsetZ()),sideWidth)),sideX,textY+42,0xffc6d5ec);
        }
        String detail=!message.isEmpty() ? message : actions.isEmpty() ? (localMode ? "模型参数中选择并启用本地模型" : "当前服务器没有可用动作")
                : localMode ? "仅自己可见 · 悬停后松开按键选择" : "服务器同步 · 悬停后松开按键选择";
        context.drawCenteredTextWithShadow(textRenderer,Text.literal(textRenderer.trimToWidth(detail,width-24)),width/2,height-40,0xffc6d5ec);
        String pageLabel=(page+1)+" / "+WheelSelection.pageCount(actions.size(),SLOTS)
                +(localMode && menu!=null && menu.depth()>0 ? " · "+menu.categoryId() : "");
        context.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth(pageLabel,Math.max(1,sideX-24)),centerX,height-57,0xffa9c8dc);
        super.render(context,mouseX,mouseY,delta);
        int index=index(hovered);
        if(index>=0)context.drawTooltip(textRenderer,Text.literal((hoveredConfig ? "点击配置 · " : "")+actions.get(index).label()+" · "+actions.get(index).id()
                +(localMode ? " · 仅自己可见" : " · 服务器动作")),mouseX,mouseY);
    }
    private static String number(double value) {return java.math.BigDecimal.valueOf(value).setScale(2,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    private String slotLabel(int index) {return textRenderer.trimToWidth(actions.get(index).label(),Math.max(22,(int)(outer*.65)));}
    /** Real visible text rectangles support layout/accessibility inspection at the active GUI scale. */
    public Map<String,Object> diagnostics() {
        List<Map<String,Object>> labels=new ArrayList<>(),configSlots=new ArrayList<>();
        if(textRenderer!=null)for(int slot=0;slot<SLOTS;slot++) {
            int index=index(slot);if(index<0)continue;
            double angle=-Math.PI/2+slot*Math.PI*2/SLOTS,radius=(inner+outer)*.56;
            int x=centerX+(int)(Math.cos(angle)*radius),y=centerY+(int)(Math.sin(angle)*radius);
            labels.add(textRectangle(Integer.toString(slot+1),x,y-9,slot));
            if(outer>=60)labels.add(textRectangle(slotLabel(index),x,y+2,slot));
            if(hasConfig(slot)) {
                double radiusConfig=inner+(outer-inner)*.24;
                int configX=centerX+(int)(Math.cos(angle)*radiusConfig),configY=centerY+(int)(Math.sin(angle)*radiusConfig);
                labels.add(textRectangle("⚙",configX,configY-4,slot));
                configSlots.add(Map.of("slot",slot,"group",localEntries.get(index).configGroup(),"x",configX,"y",configY));
            }
        }
        var result=new java.util.LinkedHashMap<String,Object>(Map.of("centerX",centerX,"centerY",centerY,"innerRadius",inner,"outerRadius",outer,
                "page",page,"localMode",localMode,"locked",locked,"hovered",hovered,"labels",labels,
                "visibleActionIds",actions.stream().skip((long)page*SLOTS).limit(SLOTS).map(ClientRuntime.Action::id).toList()));
        result.put("configSlots",configSlots);result.put("actionLocked",runtime.localActionLocked());
        result.put("category",localMode && menu!=null ? menu.categoryId() : "");
        result.put("categoryId",localMode && menu!=null ? menu.categoryId() : "");result.put("depth",localMode && menu!=null ? menu.depth() : 0);
        result.put("pageCount",WheelSelection.pageCount(actions.size(),SLOTS));
        result.put("entries",localMode && menu!=null ? localEntries.stream().map(entry->Map.of("id",entry.id(),"configGroup",entry.configGroup(),"category",entry.category(),"back",entry.back())).toList() : List.of());return result;
    }
    private Map<String,Object> textRectangle(String text,int center,int y,int slot) {
        int textWidth=textRenderer.getWidth(text),x=center-textWidth/2;
        return Map.of("slot",slot,"text",text,"x",x,"y",y,"width",textWidth,"height",textRenderer.fontHeight);
    }
    public boolean navigateBack() {
        if(localMode && menu!=null && menu.back()) {page=0;hovered=-1;clearAndInit();return true;} return false;
    }
    @Override public void close() {if(client!=null) {if(!navigateBack())client.setScreen(parent);}}
    @Override public boolean shouldPause() {return false;}
}
