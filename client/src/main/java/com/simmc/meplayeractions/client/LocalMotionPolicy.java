package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.client.model.BbModel.Layer;
import com.simmc.meplayeractions.client.network.WireJson;
import java.util.*;

/** Server-authorized animation mappings; no client-side assumptions about model clip names. */
public record LocalMotionPolicy(Set<String> features, Map<String, Layer> clips, int jumpMinTicks,
                                int landingGraceTicks, double movementThreshold, boolean interruptMove,
                                boolean interruptPosture, boolean flying, String interaction, String forcedPose, String specialPose, double anchorX, double anchorY,
                                double anchorZ, float anchorYaw) {
    private static final Set<String> FEATURES = Set.of("movement", "sprint", "jump", "sit", "sleep", "ride", "crawl",
            "sneak", "swim", "flight", "elytra", "swing", "mining");
    private static final Set<String> STATES = Set.of("idle", "walk", "run", "jump", "fall", "sit", "sleep", "bed-sleep",
            "boat", "minecart", "ride", "ride-pig", "ladder-move", "ladder-idle", "crawl-idle", "crawl-walk", "crouch-idle", "crouch-walk", "swim-idle",
            "swim-prone-idle", "swim-walk", "hover", "fly", "elytra", "swing-mainhand", "swing-offhand", "mining");
    public LocalMotionPolicy { features = Set.copyOf(features); clips = Map.copyOf(clips); }
    public boolean enabled(String feature) { return features.contains(feature); }
    public Layer layer(String state, String layer, long started) {
        Layer clip = clips.get(state);
        return clip == null ? null : new Layer(layer, clip.animation(), started, clip.speed(), clip.loop(), clip.inTicks(), clip.outTicks());
    }
    public static LocalMotionPolicy read(JsonObject json) {
        if (json == null) throw new IllegalArgumentException("Missing local motion policy");
        Set<String> features = new HashSet<>();
        JsonArray enabled = json.getAsJsonArray("features"), mappings = json.getAsJsonArray("clips");
        if (enabled == null || enabled.size() > FEATURES.size() || mappings == null || mappings.size() > STATES.size())
            throw new IllegalArgumentException("Motion policy size");
        for (JsonElement value : enabled) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Feature type");
            String name = value.getAsString();
            if (!FEATURES.contains(name) || !features.add(name)) throw new IllegalArgumentException("Motion feature");
        }
        Map<String, Layer> clips = new LinkedHashMap<>();
        for (JsonElement value : mappings) {
            JsonObject entry = value.getAsJsonObject();
            String state = WireJson.string(entry, "state", 32), animation = WireJson.string(entry, "animation", 128);
            Layer clip = new Layer("posture", animation, 0, WireJson.number(entry, "speed", .01, 20),
                    WireJson.string(entry, "loop", 16), (int) WireJson.integer(entry, "inTicks", 0, 200),
                    (int) WireJson.integer(entry, "outTicks", 0, 200));
            if (!STATES.contains(state) || clips.putIfAbsent(state, clip) != null) throw new IllegalArgumentException("Motion mapping");
        }
        String special = WireJson.string(json, "specialPose", 16);
        if (!Set.of("", "sit", "sleep", "crawl").contains(special)) throw new IllegalArgumentException("Special posture");
        String interaction = WireJson.string(json, "interaction", 32);
        if (!Set.of("", "mining", "swing-mainhand", "swing-offhand").contains(interaction)) throw new IllegalArgumentException("Interaction state");
        String forced = WireJson.string(json, "forcedPose", 16);
        if (!Set.of("", "crawl", "sneak").contains(forced)) throw new IllegalArgumentException("Forced posture");
        return new LocalMotionPolicy(features, clips, (int) WireJson.integer(json, "jumpMinTicks", 1, 200),
                (int) WireJson.integer(json, "landingGraceTicks", 0, 200), WireJson.number(json, "movementThreshold", 0, 1),
                WireJson.bool(json, "interruptMove"), WireJson.bool(json, "interruptPosture"), WireJson.bool(json, "flying"), interaction, forced, special,
                WireJson.number(json, "anchorX", -32, 32), WireJson.number(json, "anchorY", -32, 32),
                WireJson.number(json, "anchorZ", -32, 32), (float) WireJson.number(json, "anchorYaw", -360_000, 360_000));
    }
}
