package com.simmc.meplayeractions.me;

import com.simmc.meplayeractions.config.Settings;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;

/** ModelEngine registry keys are the only source of executable server model names. */
final class RegisteredModels {
    private RegisteredModels() {}
    static List<String> ids(Collection<String> keys) {
        return keys.stream().filter(RegisteredModels::valid).distinct().sorted().toList();
    }
    private static boolean valid(String id) {
        try { Settings.id(id); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    static String uniqueAttached(Collection<String> attached) {
        List<String> ids = List.copyOf(new LinkedHashSet<>(attached));
        if (ids.isEmpty()) throw new IllegalStateException("当前没有可管理的 ModelEngine 伪装。请先使用 /meplayeractions disguise <模型名>。");
        if (ids.size() != 1) throw new IllegalStateException("玩家附着了多个 ModelEngine 模型，请指定 /meplayeractions attach <模型名>：" + String.join("、", ids));
        return Settings.id(ids.getFirst());
    }
}
