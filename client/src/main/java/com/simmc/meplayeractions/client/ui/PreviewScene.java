package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.EntityAnimationController;
import com.simmc.meplayeractions.client.AnimationFormatValidator;
import com.simmc.meplayeractions.client.LocalMotionPolicy;
import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.expression.Molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Each owner/card has its own clock, author environment, controller state and springs. */
final class PreviewScene {
    static final double ENTRY_TICKS = 12;
    private static final Map<String, Double> CARD_QUERIES = Map.ofEntries(
            Map.entry("ysm.food_level", 20d), Map.entry("ysm.has_mainhand", 0d), Map.entry("ysm.has_offhand", 0d),
            Map.entry("query.ground_speed", 0d), Map.entry("query.vertical_speed", 0d), Map.entry("query.yaw_speed", 0d),
            Map.entry("query.is_sneaking", 0d));
    private final BbModel model;
    private final ModelPreview.Context context;
    private final AnimationPlayer player;
    private final List<BbModel.Layer> staticLayers;
    private final LocalMotionPolicy policy;
    private EntityAnimationController motion = new EntityAnimationController();
    private double startedAt = Double.NaN, previousClock = Double.NaN, age;
    private int startCount;
    private Map<String, Double> lastParameters = Map.of(), lastQueries = Map.of();
    private EntityAnimationController.Sample lastMotion;
    private Sample last;

    record NativeInputs(EntityAnimationController.Sample motion, Map<String, Double> queries,
                        Consumer<Molang.Context> configure) {
        static final NativeInputs EMPTY = new NativeInputs(null, Map.of(), ignored -> { });
        NativeInputs {
            queries = Map.copyOf(queries);
            Objects.requireNonNull(configure);
        }
    }

    record Sample(List<BbModel.Vertex> vertices, double age, double startedAt,
                  double entryProgress, int startCount, List<String> animations,
                  List<BbModel.Layer> layers, ModelPreview.Context context) {
        List<String> slots() { return layers.stream().map(BbModel.Layer::layer).toList(); }
    }

    PreviewScene(BbModel model, String previewAnimation) {
        this(model, previewAnimation, ModelPreview.Context.CARD);
    }

    PreviewScene(BbModel model, String previewAnimation, ModelPreview.Context context) {
        this.model = Objects.requireNonNull(model);
        this.context = Objects.requireNonNull(context);
        player = new AnimationPlayer(model);
        policy = LocalMotionPolicy.forModel(model);
        List<BbModel.Layer> selected = new ArrayList<>();
        if (!model.ysmControllers()) {
            // Ordinary BB previews retain their existing idle and fit/entrance behavior.
            if (model.animations().contains("idle")) selected.add(layer("posture", "idle", "LOOP"));
            if (previewAnimation != null && !previewAnimation.equals("idle")
                    && model.animations().contains(previewAnimation)) selected.add(layer("gui", previewAnimation, "LOOP"));
        } else if (context == ModelPreview.Context.CARD && previewAnimation != null
                && model.animations().contains(previewAnimation)) {
            // OpenYSM's dummy stops main/hand predicates and submits currentAnimation to cap.
            // An explicit cap slot also keeps ctrl.playing_extra_animation false in the dummy.
            String loop = AnimationFormatValidator.validate(model.animationFormatVersion(),
                    model.animationFromPrimaryAssembly(previewAnimation)) ? model.animationLoop(previewAnimation) : "LOOP";
            selected.add(layer(model.controllerFamily() + ".cap", previewAnimation, loop));
        }
        staticLayers = List.copyOf(selected);
    }

    private static BbModel.Layer layer(String slot, String animation, String loop) {
        return new BbModel.Layer(slot, animation, 0, 1, loop, 0, 0);
    }

    Sample sample(double clock, Map<String, Double> parameters) {
        return sample(clock, parameters, NativeInputs.EMPTY);
    }

    Sample sample(double clock, Map<String, Double> parameters, NativeInputs inputs) {
        if (!Double.isFinite(clock) || clock < 0) throw new IllegalArgumentException("Preview clock");
        Objects.requireNonNull(inputs);
        if (!Double.isNaN(previousClock) && clock + 1 < previousClock) restart();
        if (Double.isNaN(startedAt)) { startedAt = clock; startCount++; }
        previousClock = clock;
        boolean owner = model.ysmControllers() && context == ModelPreview.Context.OWNER;
        NativeInputs effective = owner ? inputs : NativeInputs.EMPTY;
        // Repeated draws and partial-tick jitter do not replay events or rewind physics.
        double nextAge = Math.max(age, clock - startedAt);
        if (last == null || nextAge != age || !lastParameters.equals(parameters)
                || !Objects.equals(lastMotion, effective.motion()) || !lastQueries.equals(effective.queries())) {
            age = nextAge;
            List<BbModel.Layer> layers = staticLayers;
            if (owner && effective.motion() != null) {
                motion.update((long) Math.floor(age), effective.motion(), policy, List.of(), model.animationCatalog());
                layers = motion.layers();
            }
            player.configureFrame(environment -> {
                if (owner) effective.configure().accept(environment);
                // Inventory mode is third person even if the world camera behind the menu is first person.
                environment.query("ysm.rendering_in_inventory", 1d);
                environment.query("ysm.rendering_in_paperdoll", 0d);
                environment.query("ysm.is_first_person", 0d);
                environment.query("query.is_first_person", 0d);
                environment.query("ysm.person_view", 2d);
                if (owner && effective.motion() != null)
                    for (String slot : motion.pausedControllers()) environment.query("ysm.pause." + slot, 1d);
            });
            Map<String, Double> queries = owner ? effective.queries() : CARD_QUERIES;
            float headYaw = queries.getOrDefault("ysm.head_yaw", 0d).floatValue();
            float headPitch = queries.getOrDefault("ysm.head_pitch", 0d).floatValue();
            last = new Sample(player.sample(age, layers, headYaw, headPitch, queries, Map.of(), parameters), age,
                    startedAt, Math.min(1, age / ENTRY_TICKS), startCount,
                    layers.stream().map(BbModel.Layer::animation).toList(), List.copyOf(layers), context);
            lastParameters = Map.copyOf(parameters);
            lastQueries = effective.queries();
            lastMotion = effective.motion();
        }
        return last;
    }

    /** A reopen/selection resets this context alone; camera drags never call this. */
    void restart() {
        player.reset(); motion = new EntityAnimationController();
        startedAt = Double.NaN; previousClock = Double.NaN; age = 0; last = null;
        lastParameters = Map.of(); lastQueries = Map.of(); lastMotion = null;
    }
}
