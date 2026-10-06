package com.simmc.meplayeractions.client.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.Optional;

/** Use Minecraft's glyph widths and word wrapping, with an explicit compact line budget. */
final class ModelUiTooltip {
    static Tooltip of(TextRenderer renderer, int screenWidth, String content) {
        var styled=Text.empty();String[] paragraphs=content.split("\n",-1);
        for(int index=0;index<paragraphs.length;index++) {
            if(index>0)styled.append("\n");
            String line=paragraphs[index];Formatting color=index==0?Formatting.AQUA:Formatting.GRAY;
            if(line.startsWith("请先")||line.startsWith("无法")||line.startsWith("当前无法")||line.startsWith("失败")
                    ||line.startsWith("服务器未")||line.startsWith("服务器拒绝")||line.startsWith("尚未")||line.startsWith("本机未找到")||line.startsWith("本机文件内容不同")
                    ||line.startsWith("没有私人模型上传权限")||line.startsWith("参数未保存")||line.startsWith("当前未允许")||line.startsWith("此模型不在"))color=Formatting.RED;
            else if(line.startsWith("等待")||line.startsWith("正在")||line.startsWith("删除中")||line.startsWith("请求已发送")||line.startsWith("需要连接")||line.startsWith("未收到"))color=Formatting.GOLD;
            else if(line.startsWith("当前正在使用")||line.startsWith("服务器已保存")||line.startsWith("已分享"))color=Formatting.GREEN;
            styled.append(Text.literal(line).formatted(color));
        }
        return of(renderer,screenWidth,styled);
    }
    static Tooltip of(TextRenderer renderer,int screenWidth,Text content) {
        int width = Math.max(40, Math.min(160, screenWidth - 24));
        var lines=renderer.getTextHandler().wrapLines(content,width,Style.EMPTY);var wrapped=Text.empty();
        for(int index=0;index<Math.min(12,lines.size());index++) {
            if(index>0)wrapped.append("\n");
            lines.get(index).visit((style,value)->{wrapped.append(Text.literal(value).setStyle(style));return Optional.empty();},Style.EMPTY);
        }
        if(lines.size()>12)wrapped.append(Text.literal("\n…").formatted(Formatting.GRAY));
        return Tooltip.of(wrapped,content);
    }
    private ModelUiTooltip() { }
}
