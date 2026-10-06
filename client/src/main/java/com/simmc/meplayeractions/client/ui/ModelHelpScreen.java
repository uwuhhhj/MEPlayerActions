package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import java.io.IOException;
import java.nio.file.Files;

/** One player-facing guide shared by the client and server gallery information buttons. */
public final class ModelHelpScreen extends Screen {
    private final ClientRuntime runtime;
    private final Screen parent;
    private final boolean server;
    private int scroll,maxScroll;
    private String message="";

    public ModelHelpScreen(ClientRuntime runtime,Screen parent,boolean server) {
        super(Text.literal(server?"服务端模型教程":"客户端模型教程"));
        this.runtime=runtime;this.parent=parent;this.server=server;
    }
    @Override protected void init() {
        var folder=ButtonWidget.builder(Text.literal(server?"打开本机缓存目录":"打开模型文件夹"),b->openDirectory())
                .dimensions(width/2-107,height-34,134,20).build();
        folder.setTooltip(ModelUiTooltip.of(textRenderer,width,server?"打开本机已下载模型的缓存\n不是服务器的模型目录":"打开本机模型目录\n放入模型后返回图库刷新"));
        addDrawableChild(folder);
        var back=ButtonWidget.builder(Text.literal("返回图库"),b->close()).dimensions(width/2+32,height-34,75,20).build();
        back.setTooltip(ModelUiTooltip.of(textRenderer,width,"返回模型图库"));addDrawableChild(back);
    }
    private void openDirectory() {
        try {
            var path=server?runtime.localServerCacheDirectory():runtime.localModelDirectory();
            Files.createDirectories(path);Util.getOperatingSystem().open(path);message=server?"已打开本机缓存目录":"放入模型后，返回图库点击刷新";
        }catch(IOException | SecurityException unavailable){message="无法打开本机目录，请检查文件权限";}
    }
    private MutableText guide() {
        var text=Text.empty();
        section(text,"三种使用方式",
                "本地私人：点击客户端“使用模型”，仅自己看到私人外观。\n"
                +"私人分享：主动上传给服务器，获准的模组玩家下载后可见；原版玩家仍看到原版人物。\n"
                +"服务端伪装：服务器管理模型与伪装，模组客户端接管绘制，原版客户端看到服务器模型。");
        if(server)serverGuide(text);else clientGuide(text);
        section(text,"切换外观",
                "客户端／服务端按钮只切换浏览页面。服务器伪装期间可浏览、配置私人模型，但不能同时使用或上传。\n"
                +"先解除服务器伪装，再主动使用私人模型；解除后不会自动恢复私人外观。");
        section(text,"J 动作轮盘",
                "按 J 打开当前来源的动作轮盘，滚轮翻页。左侧按钮解除对应伪装，右上角打开设置。\n"
                +"图库底部的玩家、装备、伪装按钮分别控制本人显隐；服务器伪装期间原版人物按服务器隐藏规则，装备保持隐藏。");
        return text;
    }
    private void clientGuide(MutableText text) {
        section(text,"1 · 放入模型",
                "点击“打开模型文件夹”，只放入你有权使用、分享的资源。YSM 支持 .ysm、ZIP 模型包和原始模型文件夹；文件夹需保留 ysm.json、models、animations、textures 等完整资源。\n"
                +"YSM 可用子文件夹分类。独立 .bbmodel 文件放在模型目录根部；支持嵌入纹理，也可保留模型声明的目录内 PNG 伴随资源。全部伴随文件须在模型目录内；放好后返回图库点击“刷新”。");
        section(text,"2 · 预览与使用",
                "点击卡片只预览，正在使用的外观不会改变。点击左侧“使用模型”才应用，仅自己可见。\n"
                +"“当前使用”表示已应用；“外观设置”可调整私人模型与作者配置。卡片右上角收藏方便下次寻找。");
        section(text,"3 · 主动上传分享",
                "点击卡片左上角或左侧的云朵“上传分享”，会使用所选模型并上传；服务器授权后，获准的模组观看者才会收到模型。\n"
                +"需要服务器支持和上传权限。云朵不可用时查看悬停提示；上传等待或失败不表示已保存。");
        section(text,"4 · 查看与管理已上传",
                "工具栏云朵显示当前服务器已保存的本人模型，进入服务器后等待列表同步。点击已上传模型的云朵，可确认删除该模型及旧版本的服务器存档；相关分享结束，本地文件保留，收到服务器确认后才移除。\n"
                +"本机缺失或内容不同的存档只能管理，不能直接使用，也不会自动取回文件。只有“已分享”而未确认保存时，云朵提供结束分享。");
    }
    private void serverGuide(MutableText text) {
        section(text,"1 · 浏览与伪装",
                "图库展示服务器提供的模型。点击卡片只浏览，点击“使用模型”才请求伪装，相当于 /meplayeractions disguise 模型ID。\n"
                +"权限由服务器检查。“等待服务器确认”表示请求中；收到服务器绑定后才显示“当前使用”，模型资源由服务器下发。");
        section(text,"离线缓存不等于使用授权",
                "未连接服务器时显示离线模式，可预览本机已下载的缓存模型、保存外观参数或打开缓存目录。\n"
                +"尚无服务器模型 ID 的旧缓存只可预览，不能保存伪装参数。\n"
                +"离线不能请求服务器伪装。连接后仍须取得当前服务器授权；只在缓存、未列入当前服务器图库的模型也不能直接使用。");
        section(text,"2 · 外观设置",
                "选择模型后打开“外观设置”，调整缩放、本人可见、观看距离等参数；留空继承服务器默认。黄色标记表示自定义项，右侧“重置”仅清空该项草稿。\n"
                +"“保存设置”只保存于本机。返回图库后点击“使用模型”或“应用设置”，再等待服务器确认；部分参数需要额外权限。");
        section(text,"3 · 客户端接管",
                "安装模组并开启客户端渲染时，已下发模型在本机绘制；未安装模组的观看者继续看到服务器伪装。\n"
                +"客户端设置里暂停渲染不会解除服务器伪装，服务器 J 轮盘仍可发起动作。文件夹按钮打开本机缓存，不能修改服务器图库。");
    }
    private static void section(MutableText target,String title,String body) {
        if(!target.getString().isEmpty())target.append("\n\n");
        target.append(Text.literal(title+"\n").formatted(Formatting.AQUA));
        target.append(Text.literal(body).formatted(Formatting.GRAY));
    }
    @Override public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        context.fill(0,0,width,height,0xED171F2A);
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,12,0xfff3f6ff);
        context.drawCenteredTextWithShadow(textRenderer,"滚轮浏览教程",width/2,25,0xff92b9df);
        int textWidth=Math.max(40,Math.min(520,width-36)),x=(width-textWidth)/2,top=42,bottom=Math.max(top+20,height-60);
        var lines=textRenderer.wrapLines(guide(),textWidth);int viewport=bottom-top;
        maxScroll=Math.max(0,lines.size()*11-viewport);scroll=Math.min(scroll,maxScroll);
        context.enableScissor(x,top,x+textWidth,bottom);
        for(int index=0;index<lines.size();index++)context.drawTextWithShadow(textRenderer,lines.get(index),x,top+index*11-scroll,0xffc6d5e7);
        context.disableScissor();
        if(maxScroll>0) {
            int thumbHeight=Math.max(12,viewport*viewport/(lines.size()*11)),thumbTop=top+scroll*(viewport-thumbHeight)/maxScroll;
            context.fill(x+textWidth+5,top,x+textWidth+7,bottom,0xff353d48);
            context.fill(x+textWidth+5,thumbTop,x+textWidth+7,thumbTop+thumbHeight,0xff92b9df);
        }
        if(!message.isEmpty())context.drawCenteredTextWithShadow(textRenderer,textRenderer.trimToWidth(message,textWidth),width/2,height-48,0xffffd589);
        super.render(context,mouseX,mouseY,delta);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(vertical==0)return super.mouseScrolled(x,y,horizontal,vertical);
        scroll=Math.max(0,Math.min(maxScroll,scroll-(int)Math.round(vertical*22)));return true;
    }
    @Override public void close() {client.setScreen(parent);}
    @Override public boolean shouldPause() {return false;}
}
