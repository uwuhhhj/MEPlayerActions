package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.BbModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One GUI instance's clock, scripts and springs; no world/player queries or server layers. */
final class PreviewScene {
    static final double ENTRY_TICKS = 12;
    private static final Map<String, Double> QUERIES = Map.ofEntries(
            Map.entry("ysm.food_level", 20d), Map.entry("ysm.has_mainhand", 0d), Map.entry("ysm.has_offhand", 0d),
            Map.entry("query.ground_speed", 0d), Map.entry("query.vertical_speed", 0d), Map.entry("query.yaw_speed", 0d),
            Map.entry("query.is_sneaking", 0d), Map.entry("query.is_first_person", 0d), Map.entry("ysm.is_first_person", 0d),
            Map.entry("ysm.rendering_in_inventory", 1d), Map.entry("ysm.rendering_in_paperdoll", 0d), Map.entry("ysm.person_view", 2d));
    private final AnimationPlayer player;
    private final List<BbModel.Layer> layers;
    private double startedAt = Double.NaN, previousClock = Double.NaN, age;
    private int startCount;
    private Map<String, Double> lastParameters = Map.of();
    private Sample last;

    record Sample(List<BbModel.Vertex> vertices, double age, double startedAt,
                  double entryProgress, int startCount, List<String> animations) { }

    PreviewScene(BbModel model, String previewAnimation) {
        player = new AnimationPlayer(model);
        List<BbModel.Layer> selected = new ArrayList<>();
        if (model.animations().contains("idle")) selected.add(layer("posture", "idle"));
        if (previewAnimation != null && !previewAnimation.equals("idle")
                && model.animations().contains(previewAnimation)) selected.add(layer("gui", previewAnimation));
        layers = List.copyOf(selected);
    }

    private static BbModel.Layer layer(String slot, String animation) {
        return new BbModel.Layer(slot, animation, 0, 1, "LOOP", 0, 0);
    }

    Sample sample(double clock, Map<String, Double> parameters) {
        if (!Double.isFinite(clock) || clock < 0) throw new IllegalArgumentException("Preview clock");
        if (!Double.isNaN(previousClock) && clock + 1 < previousClock) restart();
        if (Double.isNaN(startedAt)) { startedAt = clock; startCount++; }
        previousClock = clock;
        // A repeated GUI/card draw and partial-tick jitter must not replay events or rewind physics.
        double nextAge = Math.max(age, clock - startedAt);
        if (last == null || nextAge != age || !lastParameters.equals(parameters)) {
            age = nextAge;
            last = new Sample(player.sample(age, layers, 0, 0, QUERIES, Map.of(), parameters), age,
                    startedAt, Math.min(1, age / ENTRY_TICKS), startCount,
                    layers.stream().map(BbModel.Layer::animation).toList());
            lastParameters = Map.copyOf(parameters);
        }
        return last;
    }

    /** Only a new selection/reopen starts a new UI entrance; camera drags never call this. */
    void restart() {
        player.reset(); startedAt = Double.NaN; previousClock = Double.NaN; age = 0; last = null;
        lastParameters = Map.of();
    }
}
