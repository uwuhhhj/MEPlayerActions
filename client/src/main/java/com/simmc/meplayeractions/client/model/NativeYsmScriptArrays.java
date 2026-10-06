package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OpenYSM/Sparkle AnimationMapper.parse(list, true): one newline-joined Molang program.
 * The join deliberately adds no semicolon, so author blocks and early returns span lines.
 * Resource count and UTF-8 budgets remain enforced before compiling the joined source. */
final class NativeYsmScriptArrays {
    private NativeYsmScriptArrays() { }

    static JsonArray merge(JsonArray source, int maximumPrograms) {
        if (source.size() > maximumPrograms) throw new IllegalArgumentException("YSM script count exceeded");
        List<String> lines = new ArrayList<>();
        int bytes = 0;
        for (JsonElement value : source) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("YSM scripts must be text");
            String line = value.getAsString();
            bytes += line.getBytes(StandardCharsets.UTF_8).length + (lines.isEmpty() ? 0 : 1);
            if (bytes > 32_768) throw new IllegalArgumentException("YSM joined script exceeds 32 KiB");
            lines.add(line);
        }
        JsonArray result = new JsonArray(); result.add(String.join("\n", lines)); return result;
    }

    static JsonObject controllerActions(JsonObject source, boolean mergeMultilineExpressions) {
        JsonObject result = source.deepCopy();
        if (!mergeMultilineExpressions) return result;
        for (var controller : result.entrySet()) {
            if (!controller.getValue().isJsonObject()) continue;
            JsonElement states = controller.getValue().getAsJsonObject().get("states");
            if (states == null || !states.isJsonObject()) continue;
            for (var state : states.getAsJsonObject().entrySet()) {
                if (!state.getValue().isJsonObject()) continue;
                JsonObject definition = state.getValue().getAsJsonObject();
                for (String action : List.of("on_entry", "on_exit")) {
                    JsonElement programs = definition.get(action);
                    if (programs != null && programs.isJsonArray())
                        definition.add(action, merge(programs.getAsJsonArray(), 32));
                }
            }
        }
        return result;
    }
}
