package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.expression.Molang;
import java.util.List;
import java.util.Map;

/** Read-only descriptions of actual lookups, shared by world and GUI diagnostics. */
public final class YsmQueryDiagnostics {
    private YsmQueryDiagnostics() { }
    public static List<Map<String,Object>> describe(AnimationPlayer player) {
        return player.expressionDiagnostics().stream().map(YsmQueryDiagnostics::describe).toList();
    }
    private static Map<String,Object> describe(Molang.QueryDiagnostic value) {
        return Map.of("name",value.name(),"reason",value.reason(),"count",value.count(),
                "actualFallback",value.actualFallback());
    }
}
