package com.simmc.meplayeractions.command;

import java.util.*;

/** Public command grammar; internal posture dispatch is never exposed as a root command. */
public final class CommandLayout {
    public static final String NAME = "meplayeractions";
    public static final String PREFIX = "/" + NAME;
    public static final List<String> ROOTS = List.of("help", "disguise", "undisguise", "models", "attach",
            "menu", "animations", "play", "stop", "reset", "pose", "sync", "status", "reload");
    public static final List<String> POSES = List.of("sit", "crawl", "fly");
    private CommandLayout() {}

    public static String[] normalize(String[] input) {
        if (input.length == 0) return new String[]{"help"};
        String root = input[0].toLowerCase(Locale.ROOT);
        if (!ROOTS.contains(root)) throw new IllegalArgumentException("未知子命令；使用 " + PREFIX + " help，动作使用 play <动作名>");
        if (root.equals("pose")) {
            if (input.length < 2 || !POSES.contains(input[1].toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("用法：" + PREFIX + " pose <sit|crawl|fly> [on|off]");
            String pose = input[1].toLowerCase(Locale.ROOT);
            if (input.length > (pose.equals("fly") ? 3 : 2))
                throw new IllegalArgumentException("用法：" + PREFIX + " pose " + pose + (pose.equals("fly") ? " [on|off]" : ""));
            String[] normalized = Arrays.copyOfRange(input, 1, input.length);
            normalized[0] = pose;
            return normalized;
        }
        int min = root.equals("disguise") || root.equals("play") ? 2 : 1;
        int max = switch (root) { case "disguise" -> Integer.MAX_VALUE; case "play" -> 4; case "attach" -> 2; case "sync" -> 3; default -> 1; };
        if (input.length < min || input.length > max) throw new IllegalArgumentException("参数数量不正确；使用 " + PREFIX + " help");
        String[] normalized = input.clone(); normalized[0] = root;
        return normalized;
    }

    /** Keep the reserved protocol's sit/crawl requests working through the public pose group. */
    public static String[] clientAction(String action, String argument) {
        if (action.equals("sit") || action.equals("crawl")) return new String[]{"pose", action};
        return argument.isEmpty() ? new String[]{action} : new String[]{action, argument};
    }
}
