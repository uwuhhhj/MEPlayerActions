package com.simmc.meplayeractions.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Optional protocol-3 optimizations are used only after both peers advertise them. */
public final class IncrementalStateProtocol {
    public static final String INCREMENTAL = "incremental_state", TIMELINE = "server_timeline";
    public static final int MAX_HEARTBEAT_BINDINGS = 64;
    public record Animation(String id, String label) { }
    public record Identity(UUID owner, String instance, String hash) { }
    private Set<String> offered = Set.of();
    private boolean incremental, timeline, timelinePreference, legacyHello;

    public static List<String> helloCapabilities(boolean followServerTimeline) {
        var result = new ArrayList<>(List.of("local_render", "server_push_models", INCREMENTAL));
        if (followServerTimeline) result.add(TIMELINE);
        return List.copyOf(result);
    }

    public List<String> nextHelloCapabilities(boolean followServerTimeline) {
        return legacyHello ? List.of("local_render", "server_push_models") : helloCapabilities(followServerTimeline);
    }

    public void helloSent(Collection<String> capabilities) {
        helloSent(capabilities, capabilities.contains(TIMELINE));
    }

    public void helloSent(Collection<String> capabilities, boolean followServerTimeline) {
        offered = Set.copyOf(capabilities);
        timelinePreference = followServerTimeline;
        incremental = false; timeline = false;
    }

    /** Pre-extension servers reject unknown hello capabilities; retry their original hello once. */
    public boolean fallbackForError(String code) {
        if (legacyHello || !Set.of("invalid_payload", "unsupported_capability").contains(code)
                || !offered.contains(INCREMENTAL) && !offered.contains(TIMELINE)) return false;
        legacyHello = true; offered = Set.of(); incremental = false; timeline = false; return true;
    }

    public void acknowledge(Collection<String> capabilities) {
        incremental = offered.contains(INCREMENTAL) && capabilities.contains(INCREMENTAL);
        timeline = offered.contains(TIMELINE) && capabilities.contains(TIMELINE);
    }

    public boolean incremental() { return incremental; }
    public boolean serverTimeline() { return timeline; }
    public boolean requestedTimeline() { return timelinePreference; }
    public void reset() {
        offered = Set.of(); incremental = false; timeline = false; timelinePreference = false; legacyHello = false;
    }

    /** Absent catalogue means unchanged only in a negotiated incremental session. Explicit [] always clears it. */
    public <T> List<T> animations(JsonObject state, List<T> previous, Function<Animation, T> factory) {
        if (!state.has("animations")) return incremental ? List.copyOf(previous) : List.of();
        JsonElement value = state.get("animations");
        if (!value.isJsonArray() || value.getAsJsonArray().size() > 512)
            throw new IllegalArgumentException("Animation catalogue");
        var result = new ArrayList<T>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonObject()) throw new IllegalArgumentException("Animation catalogue entry");
            JsonObject animation = entry.getAsJsonObject();
            String id = WireJson.string(animation, "id", 128);
            if (!id.matches("[a-zA-Z0-9_.:/-]{1,128}")) throw new IllegalArgumentException("Animation ID");
            result.add(factory.apply(new Animation(id, WireJson.string(animation, "label", 256))));
        }
        return List.copyOf(result);
    }

    /** Fragments renew listed current identities and report gaps requiring an authoritative snapshot. */
    public <T> boolean renewBindings(JsonObject heartbeat, Map<UUID, T> current,
                                    Function<T, Identity> identity, BiConsumer<T, Long> renew, long now) {
        if (!incremental) return false; // Legacy heartbeat only advances the server clock.
        List<Identity> identities = readHeartbeatBindings(heartbeat);
        boolean gap = false;
        for (Identity item : identities) {
            T binding = current.get(item.owner());
            if (binding != null && item.equals(identity.apply(binding))) renew.accept(binding, now);
            else gap = true;
        }
        return gap; // Missing entries in a fragment never imply a missing or removed binding.
    }

    private static List<Identity> readHeartbeatBindings(JsonObject heartbeat) {
        JsonElement value = heartbeat.get("bindings");
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Heartbeat bindings");
        JsonArray bindings = value.getAsJsonArray();
        if (bindings.size() > MAX_HEARTBEAT_BINDINGS) throw new IllegalArgumentException("Heartbeat binding count");
        var result = new ArrayList<Identity>();
        for (JsonElement element : bindings) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Heartbeat binding");
            JsonObject entry = element.getAsJsonObject();
            UUID owner = UUID.fromString(WireJson.string(entry, "owner", 36));
            String instance = UUID.fromString(WireJson.string(entry, "instance", 36)).toString();
            String hash = WireJson.string(entry, "hash", 64);
            if (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Heartbeat hash");
            result.add(new Identity(owner, instance, hash));
        }
        return List.copyOf(result); // Validate the whole fragment before renewing any lease.
    }
}
