package com.simmc.meplayeractions.action;

import com.simmc.meplayeractions.config.Settings;
import com.simmc.meplayeractions.command.CommandLayout;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Per-command overrides; parsing and validation finish before an existing disguise is touched. */
public record DisguiseOptions(String modelId, double scale, boolean hideSelf, int visualDelay,
                              boolean showSelf, double viewDistance, int maxViewers, List<Effect> effects) {
    private static final List<String> KEYS = List.of("scale", "hide-self", "delay", "show-self", "view-distance", "max-viewers", "effect");
    private static final Pattern EFFECT_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    public record Effect(String id, int level, int seconds) {
        public Effect {
            if (!EFFECT_ID.matcher(id).matches()) throw new IllegalArgumentException("药水名须使用原版英文 ID");
            if (level < 1 || level > 256) throw new IllegalArgumentException("药水等级范围：1–256；1 表示 I 级");
            if (seconds < 0 || seconds > 86400) throw new IllegalArgumentException("药水持续秒数范围：1–86400；省略表示随伪装持续");
        }
        public String display() { return id + ":" + level + (seconds == 0 ? "" : ":" + seconds); }
    }
    public DisguiseOptions {
        modelId = Settings.id(modelId);
        if (!Double.isFinite(scale) || scale < 0.05 || scale > 8)
            throw new IllegalArgumentException("缩放范围：0.05–8");
        if (visualDelay < 0 || visualDelay > 20) throw new IllegalArgumentException("视觉延迟范围：0–20 tick");
        if (!Double.isFinite(viewDistance) || viewDistance < 0.1 || viewDistance > 256)
            throw new IllegalArgumentException("可视距离范围：0.1–256 格");
        if (maxViewers < 0 || maxViewers > 1000) throw new IllegalArgumentException("最大观看人数范围：0–1000；0 表示其他玩家均不可见");
        effects = List.copyOf(effects);
        var ids = new HashSet<String>();
        for (Effect effect : effects) {
            if (!effect.id().equals("slowness")) throw new IllegalArgumentException("伪装药水仅支持缓慢：effect=slowness[:等级[:秒数]]");
            if (!ids.add(effect.id())) throw new IllegalArgumentException("药水重复设置：" + effect.id());
        }
    }
    public static DisguiseOptions defaults(String model, double scale, boolean hideSelf, int delay) {
        return new DisguiseOptions(model, scale, hideSelf, delay, true, 8, 10, List.of());
    }
    public static DisguiseOptions defaults(String model, Settings settings) {
        return new DisguiseOptions(model, settings.scale, settings.hideSelf, settings.visualDelayTicks,
                settings.showSelf, settings.modelViewDistance, settings.maxViewers, List.of());
    }
    /** Requires the model first, then key=value, --key=value or --key value overrides. */
    public static DisguiseOptions parse(String[] args, DisguiseOptions defaults) {
        if (args.length < 2 || args[1].isBlank() || args[1].contains("=") || args[1].startsWith("--"))
            throw new IllegalArgumentException("用法：" + CommandLayout.PREFIX + " disguise <模型名> [参数...]；模型名必须放在参数前面");
        String model = Settings.id(args[1]);
        double scale = defaults.scale();
        boolean hide = defaults.hideSelf(), showSelf = defaults.showSelf();
        int delay = defaults.visualDelay(), maxViewers = defaults.maxViewers();
        double viewDistance = defaults.viewDistance();
        List<Effect> effects = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 2;
        while (index < args.length) {
            String token = args[index++];
            String key, value;
            int equals = token.indexOf('=');
            if (equals > 0) {
                key = token.substring(0, equals); value = token.substring(equals + 1);
                if (key.startsWith("--")) key = key.substring(2);
            } else if (token.startsWith("--")) {
                key = token.substring(2);
                if (index >= args.length || args[index].startsWith("--") || args[index].contains("="))
                    throw new IllegalArgumentException("参数缺少值：" + token);
                value = args[index++];
            } else throw new IllegalArgumentException("伪装参数使用 key=value，例如 scale=0.8 effect=slowness:1");
            key = key.toLowerCase(Locale.ROOT);
            if (!KEYS.contains(key))
                throw new IllegalArgumentException("未知伪装参数：" + key + "；支持 " + String.join("、", KEYS));
            if (value.isBlank()) throw new IllegalArgumentException("参数缺少值：" + key);
            if (!seen.add(key)) throw new IllegalArgumentException("参数重复设置：" + key);
            switch (key) {
                case "scale" -> {
                    try { scale = Double.parseDouble(value); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("scale 请输入数字，例如 0.8"); }
                }
                case "hide-self" -> hide = bool(value, key);
                case "show-self" -> showSelf = bool(value, key);
                case "view-distance" -> {
                    try { viewDistance = Double.parseDouble(value); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("view-distance 请输入格数，例如 8"); }
                }
                case "max-viewers" -> maxViewers = integer(value, key);
                case "delay" -> delay = integer(value, "delay");
                case "effect" -> {
                    String[] parts = value.toLowerCase(Locale.ROOT).split(":", -1);
                    if (parts.length < 1 || parts.length > 3 || Arrays.stream(parts).anyMatch(String::isBlank))
                        throw new IllegalArgumentException("药水格式：effect=药水名[:等级[:秒数]]，例如 effect=slowness:1:60");
                    int seconds = parts.length > 2 ? integer(parts[2], "药水秒数") : 0;
                    if (parts.length > 2 && seconds == 0) throw new IllegalArgumentException("显式药水秒数至少为 1");
                    effects.add(new Effect(parts[0], parts.length > 1 ? integer(parts[1], "药水等级") : 1, seconds));
                }
            }
        }
        return new DisguiseOptions(model, scale, hide, delay, showSelf, viewDistance, maxViewers, effects);
    }
    private static boolean bool(String value, String key) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "on" -> true;
            case "false", "off" -> false;
            default -> throw new IllegalArgumentException(key + " 使用 true 或 false");
        };
    }
    private static int integer(String value, String name) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " 请输入整数"); }
    }
    public void requireEffects(Predicate<String> supported) {
        for (Effect effect : effects) if (!supported.test(effect.id()))
            throw new IllegalArgumentException("服务器未注册缓慢药水：" + effect.id());
    }
    public String description() {
        return "缩放 " + scale + "；隐藏原人物 " + hideSelf + "；视觉延迟 " + visualDelay + " tick"
                + "；自己可见 " + showSelf + "；可视距离 < " + viewDistance + " 格；最多其他观众 " + maxViewers
                + (effects.isEmpty() ? "" : "；药水 " + String.join(", ", effects.stream().map(Effect::display).toList()));
    }
    public static List<String> suggestions(String[] args, List<String> models, List<String> effects) {
        if (args.length < 2) return List.of();
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2) return models.stream().filter(s -> s.startsWith(current)).sorted().toList();
        if (args[1].contains("=") || args[1].startsWith("--")) return List.of();
        List<String> result = new ArrayList<>();
        String valueKey = args.length > 2 && args[args.length - 2].startsWith("--")
                && !args[args.length - 2].contains("=") ? args[args.length - 2].substring(2) : "";
        if (!valueKey.isEmpty()) result.addAll(values(valueKey, "", effects));
        else {
            String withoutDashes = current.startsWith("--") ? current.substring(2) : current;
            int equals = withoutDashes.indexOf('=');
            if (equals >= 0) result.addAll(values(withoutDashes.substring(0, equals),
                    (current.startsWith("--") ? "--" : "") + withoutDashes.substring(0, equals + 1), effects));
            else {
                Set<String> used = new HashSet<>();
                for (int i = 2; i < args.length - 1; i++) {
                    String previous = args[i].replaceFirst("^--", "");
                    if (previous.contains("=")) used.add(previous.substring(0, previous.indexOf('=')));
                    else if (args[i].startsWith("--")) used.add(previous);
                }
                for (String key : KEYS)
                    if (!used.contains(key) && (!key.equals("effect") || effects.contains("slowness")))
                        result.add(current.startsWith("--") ? "--" + key : key + "=");
            }
        }
        return result.stream().distinct().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(current)).sorted().toList();
    }
    private static List<String> values(String key, String prefix, List<String> effects) {
        List<String> values = switch (key) {
            case "scale" -> List.of("0.5", "0.8", "1.0", "1.2", "1.5", "2.0");
            case "hide-self", "show-self" -> List.of("true", "false");
            case "view-distance" -> List.of("4", "8", "16", "32");
            case "max-viewers" -> List.of("0", "5", "10", "20", "50");
            case "delay" -> List.of("0", "1", "2", "3", "4");
            case "effect" -> effects.contains("slowness") ? List.of("slowness:1") : List.of();
            default -> List.of();
        };
        return values.stream().map(v -> prefix + v).toList();
    }
}
