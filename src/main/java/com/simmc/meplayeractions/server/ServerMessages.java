package com.simmc.meplayeractions.server;

import com.simmc.meplayeractions.action.DisguiseOptions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Short, structured chat lines distinguish ME appearances from optional client rendering resources. */
public final class ServerMessages {
    private ServerMessages() {}
    public static List<String> disguise(DisguiseOptions options) {
        List<String> lines = new ArrayList<>();
        lines.add("§a已启用服务器伪装：§f" + options.modelId() + " §7（ModelEngine 蓝图）");
        lines.add("§7外观：缩放 §f" + options.scale() + "§7；隐藏原人物 §f" + yes(options.hideSelf())
                + "§7；本人可见 §f" + yes(options.showSelf()));
        lines.add("§7观众：距离小于 §f" + options.viewDistance() + " §7格；最多 §f" + options.maxViewers() + " §7名其他玩家");
        lines.add("§7跟随延迟：§f" + options.visualDelay() + " §7tick"
                + (options.effects().isEmpty() ? "" : "；药水：§f" + String.join("、", options.effects().stream().map(DisguiseOptions.Effect::display).toList())));
        lines.add("§e动作：J 轮盘或 /meplayeractions menu；解除：/meplayeractions undisguise");
        return List.copyOf(lines);
    }
    private static String yes(boolean value) { return value ? "是" : "否"; }

    public static List<String> modelResource(String id, String state, String source, String reason, boolean eligible) {
        List<String> lines = new ArrayList<>();
        lines.add("§7MPA 客户端资源：§f" + id + "§7；来源：§f" + resourceSource(source));
        if (!eligible) {
            lines.add("§e本次伪装不允许客户端接管；仍由 ModelEngine 渲染。");
            return List.copyOf(lines);
        }
        switch (state) {
            case "ready" -> {
                lines.add("§a客户端资源已准备；观看者取得并验证资源后，服务器确认接管。");
                lines.add("§7可复用本地有效缓存；资源就绪不代表已开始接管。");
                if (source.equals("modelengine")) lines.add("§eME 蓝图可能已烘焙；作者公式是否完整取决于原始蓝图。");
            }
            case "pending" -> lines.add("§e客户端资源正在准备或等待请求；ModelEngine 伪装照常显示。");
            case "missing" -> {
                lines.add("§e未找到客户端资源；ModelEngine 伪装照常显示。");
                lines.add("§7如需本地接管，可在 MPA models/ 中提供同名的完整模型资源。");
            }
            case "invalid" -> {
                lines.add("§c客户端资源无效；ModelEngine 伪装照常显示。");
                lines.addAll(wrapped("§7原因：", reason));
                lines.add("§7修正所选资源后重载 MPA；不会改用另一来源掩盖无效文件。");
            }
            default -> {
                lines.add("§e客户端资源暂不可用；ModelEngine 伪装照常显示。");
                lines.addAll(wrapped("§7原因：", reason));
            }
        }
        return List.copyOf(lines);
    }
    private static String resourceSource(String source) {
        return switch (source) {
            case "own" -> "MPA models/";
            case "jar" -> "插件内置资源";
            case "modelengine" -> "ModelEngine 蓝图（可能已烘焙）";
            case "none" -> "未找到";
            case "lookup" -> "尚未确定";
            default -> source;
        };
    }
    public static List<String> list(String title, Collection<String> entries) {
        List<String> lines = new ArrayList<>();
        lines.add(title + "§7（" + entries.size() + " 个）");
        if (entries.isEmpty()) { lines.add("§7暂无。"); return List.copyOf(lines); }
        StringBuilder line = new StringBuilder("§f");
        for (String entry : entries) {
            if (line.length() > 2 && line.length() + entry.length() + 2 > 76) {
                lines.add(line.toString()); line = new StringBuilder("§f");
            }
            if (line.length() > 2) line.append("、");
            line.append(entry);
        }
        if (line.length() > 2) lines.add(line.toString());
        return List.copyOf(lines);
    }
    public static List<String> wrapped(String prefix, String text) {
        List<String> lines = new ArrayList<>();
        String value = text == null || text.isBlank() ? "未提供" : text;
        for (String paragraph : value.split("\\R")) {
            for (int start = 0; start < paragraph.length();) {
                int end = paragraph.offsetByCodePoints(start, Math.min(64, paragraph.codePointCount(start, paragraph.length())));
                lines.add((start == 0 ? prefix : "§7  ") + paragraph.substring(start, end)); start = end;
            }
        }
        return List.copyOf(lines);
    }
}
