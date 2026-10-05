package com.simmc.meplayeractions.me;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simmc.meplayeractions.expression.YsmRuntime;

import java.util.*;
import java.util.function.Function;

/** Bounded, class-loader-local source cache. JSON is converted to immutable data and discarded. */
final class YsmModelTemplates {
    record Point(String x, String y, String z) {
        String axis(String axis) { return switch (axis) { case "x" -> x; case "y" -> y; case "z" -> z;
            default -> throw new IllegalArgumentException("Unknown axis"); }; }
    }
    record Frame(String channel, float time, Point pre, Point post, boolean discontinuous, String interpolation) { }
    record Bone(UUID id, List<Frame> frames) { Bone { frames = List.copyOf(frames); } }
    record Clip(String name, List<Bone> bones) { Clip { bones = List.copyOf(bones); } }
    record Model(YsmRuntime.Template runtime, List<Clip> clips) { Model { clips = List.copyOf(clips); } }

    private final int capacity;
    private final Function<String, JsonObject> loader;
    private final Map<String, Optional<Model>> models = new LinkedHashMap<>(16, .75f, true);

    YsmModelTemplates(int capacity, Function<String, JsonObject> loader) {
        if (capacity < 1) throw new IllegalArgumentException("Template cache capacity must be positive");
        this.capacity = capacity; this.loader = Objects.requireNonNull(loader);
    }

    synchronized Optional<Model> get(String name) {
        Optional<Model> existing = models.get(name);
        if (existing != null) return existing;
        JsonObject raw = loader.apply(name);
        Optional<Model> loaded = raw == null || !raw.has("mpa_runtime") ? Optional.empty() : Optional.of(read(raw));
        models.put(name, loaded);
        if (models.size() > capacity) models.remove(models.keySet().iterator().next());
        return loaded;
    }

    private static Model read(JsonObject raw) {
        YsmRuntime.Template runtime = YsmRuntime.Template.compile(raw);
        List<Clip> clips = new ArrayList<>();
        for (JsonElement element : raw.getAsJsonArray("animations")) {
            JsonObject animation = element.getAsJsonObject();
            List<Bone> bones = new ArrayList<>();
            for (var entry : animation.getAsJsonObject("animators").entrySet()) {
                JsonObject animator = entry.getValue().getAsJsonObject();
                if (!animator.get("type").getAsString().equals("bone")) continue;
                List<Frame> frames = new ArrayList<>();
                for (JsonElement key : animator.getAsJsonArray("keyframes")) {
                    JsonObject frame = key.getAsJsonObject();
                    var points = frame.getAsJsonArray("data_points");
                    if (points.isEmpty()) continue;
                    String channel = frame.get("channel").getAsString();
                    frames.add(new Frame(channel, frame.get("time").getAsFloat(),
                            point(points.get(0).getAsJsonObject(), channel),
                            point(points.get(points.size() - 1).getAsJsonObject(), channel), points.size() > 1,
                            frame.get("interpolation").getAsString()));
                }
                bones.add(new Bone(UUID.fromString(entry.getKey()), frames));
            }
            clips.add(new Clip(animation.get("name").getAsString(), bones));
        }
        return new Model(runtime, clips);
    }

    private static Point point(JsonObject point, String channel) {
        String fallback = channel.equals("scale") ? "1" : "0";
        return new Point(text(point, "x", fallback), text(point, "y", fallback), text(point, "z", fallback));
    }
    private static String text(JsonObject point, String axis, String fallback) {
        return point.has(axis) ? point.get(axis).getAsString() : fallback;
    }
}
