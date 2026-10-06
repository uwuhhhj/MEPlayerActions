package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.ServerDisguisePreferences;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local command preferences for one server model; saving never changes a server disguise. */
public final class ServerAppearanceSettingsScreen extends Screen {
    private final ClientRuntime runtime;
    private final Screen parent;
    private final String modelId;
    private final ModelPreview preview;
    private final Map<String,String> draft=new LinkedHashMap<>();
    private final Map<String,TextFieldWidget> fields=new LinkedHashMap<>();
    private final Map<String,ButtonWidget> resets=new LinkedHashMap<>();
    private final List<ClickableWidget> formWidgets=new ArrayList<>();
    private final Map<ClickableWidget,Integer> widgetRows=new IdentityHashMap<>();
    private GalleryPanelLayout.Bounds panel;
    private ButtonWidget resetAll;
    private Element previousFocus;
    private int rowsTop,rowsBottom,scroll;
    private static final int ROW_HEIGHT=38;
    private String message="";
    private float ticks;

    public ServerAppearanceSettingsScreen(ClientRuntime runtime,String modelId,Screen parent) {
        super(Text.literal("服务器模型外观设置"));
        this.runtime=runtime;this.modelId=modelId;this.parent=parent;preview=new ModelPreview(runtime);
        readDraft(runtime.options.serverDisguisePreferences(modelId));
    }

    private void readDraft(ServerDisguisePreferences value) {
        draft.put("scale",number(value.scale()));draft.put("hideSelf",value.hideSelf()==null?"":value.hideSelf().toString());
        draft.put("showSelf",value.showSelf()==null?"":value.showSelf().toString());draft.put("viewDistance",number(value.viewDistance()));
        draft.put("maxViewers",value.maxViewers()==null?"":value.maxViewers().toString());draft.put("delay",value.delay()==null?"":value.delay().toString());
        draft.put("effect",value.effect()==null?"":value.effect());
    }
    private static String number(Double value) {return value==null?"":BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}

    @Override protected void init() {
        panel=GalleryPanelLayout.create(width,height,38,29);fields.clear();resets.clear();formWidgets.clear();widgetRows.clear();
        previousFocus=null;rowsTop=panel.top()+28;rowsBottom=Math.max(rowsTop+20,panel.bottom()-52);
        int x=panel.right()+9,w=panel.rightWidth()-23,gap=6,half=(panel.rightWidth()-14-gap)/2;
        field("scale","缩放",x,w,"缩放\n0.05–8；留空继承服务器默认");
        field("hideSelf","隐藏原人物",x,w,"隐藏原人物\ntrue 隐藏／false 显示\n留空继承服务器默认");
        field("showSelf","本人可见",x,w,"本人可见\ntrue 可见／false 不可见\n服务器仍校验观看许可");
        field("viewDistance","观看距离（格）",x,w,"观看距离\n0.1–256 格\n留空继承服务器默认");
        field("maxViewers","最多观看人数",x,w,"最多观看人数\n0–1000 名其他玩家\n0 表示其他玩家均不可见");
        field("delay","视觉延迟（tick）",x,w,"视觉延迟\n0–20 tick\n留空继承服务器默认");
        field("effect","缓慢效果",x,w,"缓慢效果\nslowness:等级[:秒数]\n需要服务器效果权限\n留空不请求药水效果");
        scroll(scroll);
        x=panel.right()+7;
        button("保存设置",x,panel.bottom()-44,half,this::save,"仅保存本机参数草稿\n返回后点击使用模型或应用设置");
        resetAll=button("全部重置",x+half+gap,panel.bottom()-44,half,this::reset,"清空本页参数草稿\n保存后返回，再应用服务器默认");
        button("返回",panel.left(),panel.footerY(),52,this::close,"返回模型图库\n未保存的输入不应用");
        updateResets();
    }

    private void field(String key,String label,int x,int width,String hint) {
        int row=fields.size(),y=rowsTop+row*ROW_HEIGHT-scroll+11,resetWidth=38,fieldWidth=Math.max(20,width-resetWidth-5);
        var field=new TextFieldWidget(textRenderer,x,y,fieldWidth,20,Text.literal(label)) {
            @Override public boolean mouseClicked(Click click,boolean doubled) {return insideRows(click.x(),click.y()) && super.mouseClicked(click,doubled);}
            @Override public boolean isMouseOver(double mouseX,double mouseY) {return insideRows(mouseX,mouseY) && super.isMouseOver(mouseX,mouseY);}
        };
        field.setMaxLength(key.equals("effect")?40:24);field.setPlaceholder(Text.literal("服务器默认"));
        field.setText(draft.getOrDefault(key,""));field.setChangedListener(value->{draft.put(key,value);message="参数待保存；尚未改变服务器伪装";updateResets();});
        field.setTooltip(ModelUiTooltip.of(textRenderer,this.width,hint));fields.put(key,field);formWidget(field,row);
        var reset=new ButtonWidget(x+fieldWidth+5,y,resetWidth,20,Text.literal("重置"),b->field.setText(""),supplier->supplier.get()) {
            @Override protected void drawIcon(DrawContext context,int mouseX,int mouseY,float delta) {
                drawButton(context);
                context.drawCenteredTextWithShadow(textRenderer,getMessage(),getX()+getWidth()/2,getY()+6,active?0xfff3f0e0:0xff8a929c);
            }
            @Override public boolean mouseClicked(Click click,boolean doubled) {return insideRows(click.x(),click.y()) && super.mouseClicked(click,doubled);}
            @Override public boolean isMouseOver(double mouseX,double mouseY) {return insideRows(mouseX,mouseY) && super.isMouseOver(mouseX,mouseY);}
        };
        reset.setTooltip(ModelUiTooltip.of(textRenderer,this.width,"重置"+label+"\n仅清空此项草稿\n保存并应用后继承服务器默认"));
        resets.put(key,reset);formWidget(reset,row);
    }
    private void formWidget(ClickableWidget widget,int row) {formWidgets.add(widget);widgetRows.put(widget,row);addSelectableChild(widget);}
    private ButtonWidget button(String label,int x,int y,int w,Runnable action,String hint) {
        var button=ButtonWidget.builder(Text.literal(label),b->action.run()).dimensions(x,y,Math.max(20,w),20).build();
        button.setTooltip(ModelUiTooltip.of(textRenderer,width,hint));return addDrawableChild(button);
    }
    public boolean save() {
        try {
            var value=ServerDisguisePreferences.fromFields(draft.get("scale"),draft.get("hideSelf"),draft.get("showSelf"),
                    draft.get("viewDistance"),draft.get("maxViewers"),draft.get("delay"),draft.get("effect"));
            if(!runtime.options.updateServerDisguisePreferences(modelId,value)){message="参数未保存，请检查配置空间与文件权限";return false;}
            runtime.serverPreferencesSaved(modelId);
            message="已保存草稿；返回后点击使用模型或应用设置";return true;
        } catch(IllegalArgumentException invalid){message=invalid.getMessage();return false;}
    }
    private void reset() {
        readDraft(ServerDisguisePreferences.defaults());fields.forEach((key,field)->field.setText(draft.get(key)));
        updateResets();message="草稿已重置；保存并应用后继承服务器默认";
    }
    private void updateResets() {
        resets.forEach((key,button)->button.active=!draft.getOrDefault(key,"").isBlank());
        if(resetAll!=null)resetAll.active=draft.values().stream().anyMatch(value->!value.isBlank());
    }
    private boolean insideRows(double x,double y) {return panel!=null && x>=panel.right() && x<panel.right()+panel.rightWidth() && y>=rowsTop && y<rowsBottom;}
    private void scroll(int requested) {
        scroll=Math.max(0,Math.min(requested,Math.max(0,fields.size()*ROW_HEIGHT-(rowsBottom-rowsTop))));
        formWidgets.forEach(widget->widget.setY(rowsTop+widgetRows.get(widget)*ROW_HEIGHT-scroll+11));
    }
    @Override public boolean mouseScrolled(double mouseX,double mouseY,double horizontal,double vertical) {
        if(vertical!=0 && insideRows(mouseX,mouseY)){scroll(scroll+(vertical>0?-ROW_HEIGHT:ROW_HEIGHT));return true;}
        return super.mouseScrolled(mouseX,mouseY,horizontal,vertical);
    }
    @Override public void tick() {
        ticks++;var focused=getFocused();
        if(focused!=previousFocus && focused instanceof ClickableWidget widget && widgetRows.containsKey(widget)) {
            int top=widgetRows.get(widget)*ROW_HEIGHT,bottom=top+ROW_HEIGHT;
            if(top<scroll)scroll(top);else if(bottom>scroll+rowsBottom-rowsTop)scroll(bottom-(rowsBottom-rowsTop));
        }
        previousFocus=focused;
    }
    @Override public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        preview.beginFrame(context);
        context.fill(panel.left()-4,panel.frameTop()-2,panel.left()+panel.panelWidth()+4,panel.frameBottom()+2,0xCF10171F);
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,panel.titleY(),0xfff3f0e0);
        context.fill(panel.left(),panel.top(),panel.left()+panel.previewWidth(),panel.bottom(),0xE6222730);
        context.fill(panel.right(),panel.top(),panel.right()+panel.rightWidth(),panel.bottom(),0xE6222730);
        clipped(context,modelId,panel.left()+6,panel.top()+6,panel.previewWidth()-12,0xfff3f0e0);
        clipped(context,"参数仅保存于本机",panel.left()+6,panel.top()+17,panel.previewWidth()-12,0xff92b9df);
        int previewY=panel.top()+29,previewH=Math.max(25,panel.bottom()-previewY-9);
        var loaded=runtime.serverModelForPreview(modelId);
        if(loaded!=null)preview.render(context,loaded.model(),"server-settings:"+modelId+":"+loaded.hash(),panel.left()+4,
                previewY,panel.previewWidth()-8,previewH,0,-8,ticks+delta,Map.of(),loaded.previewAnimation(),loaded.profile(),ModelPreview.Context.SELECTED);
        else clipped(context,runtime.serverCatalogOfflineMode()?"正在准备本机缓存预览…":"使用后由服务器下发模型",panel.left()+6,previewY+previewH/2,panel.previewWidth()-12,0xffffc685);
        clipped(context,"留空继承服务器默认",panel.right()+7,panel.top()+5,panel.rightWidth()-14,0xff92b9df);
        clipped(context,"滚轮浏览 · Tab 切换输入框",panel.right()+7,panel.top()+16,panel.rightWidth()-14,0xff92b9df);
        context.enableScissor(panel.right(),rowsTop,panel.right()+panel.rightWidth(),rowsBottom);
        for(var entry:fields.entrySet()) {
            var field=entry.getValue();
            context.drawTextWithShadow(textRenderer,field.getMessage(),field.getX(),field.getY()-10,0xffc6d5e7);
            if(!draft.getOrDefault(entry.getKey(),"").isBlank())context.fill(field.getX()-4,field.getY()-10,field.getX()-2,field.getBottom(),0xffffd052);
        }
        boolean mouseInside=insideRows(mouseX,mouseY);
        for(var widget:formWidgets)if(widget.getBottom()>rowsTop && widget.getY()-11<rowsBottom)
            widget.render(context,mouseInside?mouseX:Integer.MIN_VALUE,mouseInside?mouseY:Integer.MIN_VALUE,delta);
        context.disableScissor();
        int maxScroll=Math.max(0,fields.size()*ROW_HEIGHT-(rowsBottom-rowsTop));
        if(maxScroll>0) {
            int trackX=panel.right()+panel.rightWidth()-5,viewHeight=rowsBottom-rowsTop,thumbHeight=Math.max(12,viewHeight*viewHeight/(fields.size()*ROW_HEIGHT));
            context.fill(trackX,rowsTop,trackX+2,rowsBottom,0xff353d48);
            int thumbTop=rowsTop+scroll*(viewHeight-thumbHeight)/maxScroll;
            context.fill(trackX,thumbTop,trackX+2,thumbTop+thumbHeight,0xff92b9df);
        }
        if(!message.isEmpty())clipped(context,message,panel.right()+7,panel.bottom()-15,panel.rightWidth()-14,0xffffd589);
        clipped(context,"使用时由服务器校验参数与权限",panel.left()+60,panel.footerY()+6,panel.panelWidth()-60,0xff92b9df);
        super.render(context,mouseX,mouseY,delta);
    }
    private void clipped(DrawContext context,String value,int x,int y,int width,int color) {
        context.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(value,Math.max(0,width)),x,y,color);
    }
    @Override public void removed() {preview.clear();}
    @Override public void close() {client.setScreen(parent);}
    @Override public boolean shouldPause() {return false;}
}
