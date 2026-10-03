package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import java.util.*;

public final class ActionsScreen extends Screen {
    private final ClientRuntime runtime;
    private int page,rows;
    private List<ClientRuntime.Action> actions=List.of();
    public ActionsScreen(ClientRuntime runtime){super(Text.literal("玩家模型动作"));this.runtime=runtime;}
    @Override protected void init() {
        actions=runtime.actions();rows=Math.max(1,Math.min(4,(height-234)/24));
        int pageSize=rows*3;page=Math.max(0,Math.min(page,Math.max(0,(actions.size()-1)/pageSize)));
        int panel=Math.min(450,width-24),left=(width-panel)/2,col=(panel-12)/3;
        for(int index=page*pageSize;index<Math.min(actions.size(),(page+1)*pageSize);index++) {
            var action=actions.get(index);int slot=index-page*pageSize;
            var button=ButtonWidget.builder(Text.literal(action.label()),b->runtime.request("play",action.id()))
                    .dimensions(left+(slot%3)*(col+6),98+(slot/3)*24,col,20).build();
            button.setTooltip(Tooltip.of(Text.literal(action.id()+" · 动画展示")));addDrawableChild(button);
        }
        int bottom=98+rows*24+6;
        addDrawableChild(ButtonWidget.builder(Text.literal("上一页"),b->{page--;clearAndInit();}).dimensions(left,bottom,col,20).build()).active=page>0;
        addDrawableChild(ButtonWidget.builder(Text.literal("停止动画"),b->runtime.request("stop","")).dimensions(left+col+6,bottom,col,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("下一页"),b->{page++;clearAndInit();}).dimensions(left+2*(col+6),bottom,col,20).build()).active=(page+1)*pageSize<actions.size();
        bottom+=24;
        addDrawableChild(ButtonWidget.builder(Text.literal("真实坐下"),b->runtime.request("sit","")).dimensions(left,bottom,col,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("真实爬行"),b->runtime.request("crawl","")).dimensions(left+col+6,bottom,col,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("重置姿态"),b->runtime.request("reset","")).dimensions(left+2*(col+6),bottom,col,20).build());
        bottom+=24;
        addDrawableChild(ButtonWidget.builder(enabledText(),b->{runtime.toggleEnabled();b.setMessage(enabledText());}).dimensions(left,bottom,col,20).build());
        addDrawableChild(ButtonWidget.builder(selfText(),b->{runtime.options.showSelf=!runtime.options.showSelf;runtime.options.save();b.setMessage(selfText());})
                .dimensions(left+col+6,bottom,col,20).build());
        addDrawableChild(ButtonWidget.builder(smoothingText(),b->{runtime.options.interpolationTicks=(runtime.options.interpolationTicks+1)%7;
                    runtime.options.save();b.setMessage(smoothingText());}).dimensions(left+2*(col+6),bottom,col,20).build())
                .setTooltip(Tooltip.of(Text.literal("仅在开启服务器拖后轨迹时生效；即时跟随使用原版实体的帧间平滑")));
        bottom+=24;
        addDrawableChild(ButtonWidget.builder(trailingText(),b->{runtime.options.followServerTimeline=!runtime.options.followServerTimeline;
                    runtime.options.save();b.setMessage(trailingText());}).dimensions(left,bottom,panel,20).build());
        bottom+=24;
        addDrawableChild(ButtonWidget.builder(Text.literal("本地预览示例"),b->runtime.preview("ysm_01_jk_npc")).dimensions(left,bottom,col,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("结束预览"),b->runtime.preview("off")).dimensions(left+col+6,bottom,col,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"),b->close()).dimensions(left+2*(col+6),bottom,col,20).build());
    }
    private Text enabledText(){return Text.literal("本地渲染："+(runtime.options.enabled?"开启":"关闭"));}
    private Text selfText(){return Text.literal("本人模型："+(runtime.options.showSelf?"显示":"隐藏"));}
    private Text smoothingText(){return Text.literal("拖后缓冲："+runtime.options.interpolationTicks+" tick");}
    private Text trailingText(){return Text.literal("坐标跟随："+(runtime.options.followServerTimeline?"服务器拖后轨迹（点击关闭）":"客户端实体即时跟随（默认）"));}
    @Override public void tick(){if(!runtime.actions().equals(actions))clearAndInit();}
    @Override public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        // Minecraft 1.21.11 applies screen blur before invoking render.
        context.fill(0,0,width,height,0xCC101823);
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,12,0xffffffff);
        List<String> status=runtime.status();
        for(int i=0;i<Math.min(6,status.size());i++)context.drawCenteredTextWithShadow(textRenderer,Text.literal(status.get(i)),width/2,29+i*11,0xffc6d5ec);
        if(actions.isEmpty())context.drawCenteredTextWithShadow(textRenderer,Text.literal("先在服务器伪装，或打开本地预览"),width/2,92,0xffffd589);
        super.render(context,mouseX,mouseY,delta);
    }
    @Override public boolean shouldPause(){return false;}
}
