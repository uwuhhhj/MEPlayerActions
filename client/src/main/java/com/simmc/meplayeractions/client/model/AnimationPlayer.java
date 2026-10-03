package com.simmc.meplayeractions.client.model;

import org.joml.Vector3f;
import org.joml.Matrix4f;
import java.util.function.Consumer;
import com.simmc.meplayeractions.expression.Molang;

import java.util.*;

/** Per-model-instance playback state. Repeated snapshots preserve the original clock. */
public final class AnimationPlayer {
    private final BbModel model;
    private final Map<String, State> states = new LinkedHashMap<>();
    private double lastTick = Double.NaN;
    private final Molang.Context expressions = new Molang.Context();
    private boolean initialized;
    private double physicsRemainder;
    private final YsmAnimationController ysm;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, Spring> springs = new HashMap<>();
    private Consumer<Molang.Context> configureFrame = context -> { };
    private BbModel.Pose sampledPose;
    private BbModel.Pose readingPose;
    private double instanceStart = Double.NaN;
    private Molang.FunctionResolver installedResolver;
    private int functionDepth;
    private int eventDepth;
    private boolean lifecycleInitialized;

    public AnimationPlayer(BbModel model) { this.model = Objects.requireNonNull(model); ysm = model.ysmControllers() ? new YsmAnimationController(model) : null; sampledPose = model.emptyPose(); readingPose = sampledPose; }

    public void reset() { states.clear(); lastTick = Double.NaN; expressions.clear(); initialized = false; lifecycleInitialized = false; physicsRemainder = 0; groups.clear(); springs.clear(); if (ysm != null) ysm.reset(); sampledPose = model.emptyPose(); readingPose = sampledPose; instanceStart = Double.NaN; }
    public Map<String,Double> expressionVariables() { return expressions.variables(); }
    public Map<String,Object> expressionValues() { return expressions.values(); }
    /** Native bindings are injected after frame inputs, before any author code executes. */
    public void configureFrame(Consumer<Molang.Context> configure) { configureFrame = Objects.requireNonNull(configure); }
    public Map<String,Matrix4f> boneTransforms() { return model.boneTransforms(sampledPose); }
    public Optional<Matrix4f> boneTransform(String name) { return Optional.ofNullable(boneTransforms().get(name)); }
    public List<BbModel.Vertex> filteredVertices(Set<String> selected) { return model.vertices(sampledPose, Set.copyOf(selected)); }
    public Map<String,String> controllerStates() { return ysm == null ? Map.of() : ysm.states(); }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers) {
        return sample(serverTick, layers, 0, 0);
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers, float relativeHeadYaw, float headPitch) {
        return sample(serverTick, layers, relativeHeadYaw, headPitch, Map.of());
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers, float relativeHeadYaw,
                                     float headPitch, Map<String, Double> queries) {
        return sample(serverTick, layers, relativeHeadYaw, headPitch, queries, Map.of());
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers, float relativeHeadYaw,
                                     float headPitch, Map<String, Double> queries, Map<String, Double> accessories) {
        return sample(serverTick, layers, relativeHeadYaw, headPitch, queries, accessories, Map.of());
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers, float relativeHeadYaw,
                                     float headPitch, Map<String, Double> queries, Map<String, Double> accessories,
                                     Map<String, Double> localParameters) {
        BbModel.checkedTick(serverTick);
        if (!Double.isNaN(lastTick) && serverTick < lastTick && lastTick - serverTick <= 1)
            return sample(lastTick, layers, relativeHeadYaw, headPitch, queries, accessories, localParameters);
        if (localParameters.size() > 128) throw new IllegalArgumentException("Local parameter count");
        localParameters.forEach((key, value) -> {
            if (!key.matches("variable\\.[\\p{L}\\p{N}_]+(?:\\.[\\p{L}\\p{N}_]+)*") || key.length() > 128 || value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000)
                throw new IllegalArgumentException("Local parameter value");
        });
        if (!accessories.isEmpty()) {
            if (!accessories.keySet().equals(Set.of("a", "b"))) throw new IllegalArgumentException("Accessory fields");
            for (Double value : accessories.values())
                if (value == null || !Double.isFinite(value) || value < 0 || value > 1)
                    throw new IllegalArgumentException("Accessory range");
        }
        List<BbModel.Layer> incoming = BbModel.ordered(model.withParallelLayers(layers));
        for (BbModel.Layer layer : incoming) {
            if (!model.animations().contains(layer.animation()))
                throw new IllegalArgumentException("Unknown animation: " + layer.animation());
        }
        // A world/clock reset must not blend a pose from the previous connection.
        if (!Double.isNaN(lastTick) && serverTick + 1 < lastTick) reset();
        Map<String, Double> inputs = new HashMap<>(queries);
        inputs.put("ysm.head_yaw", (double) relativeHeadYaw); inputs.put("ysm.head_pitch", (double) headPitch);
        inputs.put("query.head_x_rotation",(double)relativeHeadYaw);inputs.put("query.head_y_rotation",(double)headPitch);
        inputs.putIfAbsent("ysm.food_level", 20d); inputs.put("query.life_time", serverTick / 20);
        expressions.frame(inputs);
        if (Double.isNaN(instanceStart)) instanceStart = serverTick;
        if (ysm != null) expressions.query("query.life_time", (serverTick - instanceStart) / 20);
        if (expressions.functionResolver() == installedResolver) expressions.functions(null);
        configureFrame.accept(expressions);
        Molang.FunctionResolver nativeResolver = expressions.functionResolver();
        installedResolver = (name, arguments) -> resolve(name, arguments, nativeResolver);
        expressions.functions(installedResolver);
        if (!lifecycleInitialized) { lifecycleInitialized = true; model.authorEvent("player_init", List.of(), expressions); }
        boolean firstPerson = expressions.has("ysm.is_first_person") ? expressions.get("ysm.is_first_person") != 0
                : expressions.has("query.is_first_person") ? expressions.get("query.is_first_person") != 0
                : Set.of("fp.arm", "fp_arm").contains(model.controllerFamily());
        model.authorEvent("player_update", List.of(firstPerson), expressions);
        for (BbModel.Layer layer : incoming) if (!expressions.has("ctrl." + layer.animation())) expressions.query("ctrl." + layer.animation(), 1);
        expressions.query("ctrl.playing_extra_animation", incoming.stream().anyMatch(layer -> layer.layer().equals("manual")) ? 1 : 0);
        if (ysm != null) {
            localParameters.forEach(expressions::set);
            if (!accessories.isEmpty()) {
                expressions.set("variable.roaming.a", accessories.get("a")); expressions.set("variable.roaming.b", accessories.get("b"));
            }
            return sampleYsm(serverTick, incoming, relativeHeadYaw, headPitch, accessories, localParameters);
        }
        if (!initialized) { model.initializePhysics(expressions); initialized = true; }
        model.helperInputs(serverTick,incoming,expressions);
        // The source physics uses a 10 ms integration step. Bound catch-up after pauses.
        physicsRemainder += Double.isNaN(lastTick) ? 0 : Math.max(0, Math.min(.25, (serverTick - lastTick) / 20));
        while (physicsRemainder + 1e-9 >= .01) { model.stepPhysics(expressions); physicsRemainder -= .01; }
        lastTick = serverTick;
        for (BbModel.Layer layer : incoming) {
            State state = states.get(layer.layer());
            double before = state == null || !layer.equals(state.layer) ? layer.startedAtTick() - 1e-5 : state.eventTick;
            model.events(layer, before, serverTick, expressions);
        }
        // A late viewer can replay an old toggle here. The server's resulting values win before any pose sampling.
        if (!accessories.isEmpty()) {
            expressions.set("variable.roaming.a", accessories.get("a"));
            expressions.set("variable.roaming.b", accessories.get("b"));
        }
        localParameters.forEach(expressions::set);
        Set<String> present = new HashSet<>();
        for (BbModel.Layer layer : incoming) {
            present.add(layer.layer());
            State state = states.get(layer.layer());
            if (state == null) {
                state=new State(layer, empty(), layer.startedAtTick(), layer.inTicks());states.put(layer.layer(),state);
            } else if (!layer.equals(state.layer)) {
                Contribution previous = state.at(serverTick);
                int duration = Math.max(layer.inTicks(), state.layer == null ? 0 : state.layer.outTicks());
                state.previous = previous;
                state.layer = layer;
                state.changedAt = Math.max(serverTick, layer.startedAtTick());
                state.duration = duration;
            }
            state.eventTick=serverTick;
        }
        for (var entry : states.entrySet()) {
            State state = entry.getValue();
            if (!present.contains(entry.getKey()) && state.layer != null) {
                state.previous = state.at(serverTick);
                state.duration = state.layer.outTicks();
                state.layer = null;
                state.changedAt = serverTick;
            }
        }
        BbModel.Pose combined = model.emptyPose();
        double[] headWeights = model.defaultHeadWeights();
        List<Map.Entry<String, State>> ordered = new ArrayList<>(states.entrySet());
        ordered.sort(Comparator.comparingInt(entry -> BbModel.priority(entry.getKey())));
        for (var entry : ordered) {
            Contribution contribution = entry.getValue().at(serverTick);
            apply(combined, contribution, BbModel.additive(entry.getKey()));
            if (entry.getKey().equalsIgnoreCase("manual")) {
                for (int i = 0; i < headWeights.length; i++)
                    if (contribution.pose.channels[i][1] != null) headWeights[i] *= 1 - BbModel.clamp(contribution.weights[i][1]);
            }
        }
        states.entrySet().removeIf(entry -> entry.getValue().layer == null
                && serverTick >= entry.getValue().changedAt + entry.getValue().duration);
        if(incoming.stream().noneMatch(layer->model.ownsHeadLook(layer.animation())))model.applyLook(combined,relativeHeadYaw,headPitch,headWeights);
        sampledPose = combined.copy(); readingPose = sampledPose;
        return model.vertices(combined);
    }

    private List<BbModel.Vertex> sampleYsm(double tick, List<BbModel.Layer> incoming, float yaw, float pitch,
                                                 Map<String,Double> accessories, Map<String,Double> localParameters) {
        double delta = Double.isNaN(lastTick) ? 0 : Math.max(0, Math.min(.25, (tick - lastTick) / 20));
        springs.values().forEach(spring -> spring.update(delta)); lastTick = tick;
        BbModel.Pose combined = model.emptyPose(); readingPose = sampledPose;
        double[] headWeights = model.defaultHeadWeights(); boolean[] authoredLook = { false };
        ysm.sample(tick, incoming, expressions, (frame, context) -> {
            BbModel.Pose target = model.emptyPose();
            for (YsmAnimationController.Run run : frame.runs()) {
                if (run.events()) model.clipEvents(run.animation(), run.loop(), run.before(), run.elapsed(), context);
                // Saved user fields and authoritative server accessories win over first-frame author defaults.
                localParameters.forEach(context::set);
                if (!accessories.isEmpty()) {
                    context.set("variable.roaming.a", accessories.get("a")); context.set("variable.roaming.b", accessories.get("b"));
                }
                if (!run.active()) continue;
                BbModel.Evaluated evaluated = model.evaluateClip(run.animation(), run.elapsed(), run.loop(), context);
                // Multiple clips in the same state sum position/rotation and multiply weighted scale.
                addState(target, evaluated.pose(), evaluated.weight());
                authoredLook[0] |= model.ownsHeadLook(run.animation());
                readingPose = previewPose(combined, target, frame.deprecated());
            }
            Group group = groups.get(frame.name());
            if (group == null) {
                group = new Group(frame.revision(), model.emptyPose(), frame.changedAt(), frame.blend());
                groups.put(frame.name(), group);
            } else if (group.revision != frame.revision()) {
                group.previous = group.at(tick); group.revision = frame.revision();
                group.changedAt = frame.changedAt(); group.blend = frame.blend();
            }
            group.current = target;
            if (frame.suppressed()) return;
            BbModel.Pose pose = group.at(tick);
            applyGroup(combined, pose, frame.deprecated());
            if (frame.cap()) model.suppressManualLook(headWeights, pose, frame.blend().fraction(tick - frame.changedAt()));
            readingPose = combined;
        });
        if (!authoredLook[0]) model.applyLook(combined, yaw, pitch, headWeights);
        sampledPose = combined.copy(); readingPose = sampledPose;
        return model.vertices(combined);
    }
    private static void addState(BbModel.Pose target, BbModel.Pose clip, double weight) {
        for (int i = 0; i < target.channels.length; i++) for (int c = 0; c < 3; c++) {
            Vector3f value = clip.channels[i][c]; if (value == null) continue;
            Vector3f base = target.channels[i][c]; if (base == null) base = BbModel.defaultValue(c);
            target.channels[i][c] = c == 2 ? new Vector3f(base).mul(new Vector3f(1).lerp(value, (float)weight))
                    : new Vector3f(base).add(new Vector3f(value).mul((float)weight));
        }
    }
    private static BbModel.Pose previewPose(BbModel.Pose base, BbModel.Pose top, boolean deprecated) {
        BbModel.Pose result = base.copy(); applyGroup(result, top, deprecated); return result;
    }
    private static void applyGroup(BbModel.Pose target, BbModel.Pose top, boolean deprecated) {
        for (int i = 0; i < target.channels.length; i++) for (int c = 0; c < 3; c++) {
            Vector3f value = top.channels[i][c]; if (value == null) continue;
            Vector3f base = target.channels[i][c];
            target.channels[i][c] = deprecated && c == 1 && base != null ? new Vector3f(base).add(value) : new Vector3f(value);
        }
    }
    private final class Group {
        long revision; BbModel.Pose previous, current; double changedAt; YsmAnimationController.Blend blend;
        Group(long revision, BbModel.Pose previous, double changedAt, YsmAnimationController.Blend blend) {
            this.revision = revision; this.previous = previous; this.current = model.emptyPose(); this.changedAt = changedAt; this.blend = blend;
        }
        BbModel.Pose at(double tick) {
            double fraction = BbModel.clamp(blend.fraction(tick - changedAt)); if (fraction >= 1) return current.copy();
            BbModel.Pose result = model.emptyPose();
            for (int i = 0; i < result.channels.length; i++) for (int c = 0; c < 3; c++) {
                Vector3f a = previous.channels[i][c], b = current.channels[i][c]; if (a == null && b == null) continue;
                if (a == null) a = BbModel.defaultValue(c); if (b == null) b = BbModel.defaultValue(c);
                result.channels[i][c] = c == 1 ? rotationBlend(a, b, (float)fraction) : new Vector3f(a).lerp(b, (float)fraction);
            }
            return result;
        }
    }
    private static Vector3f rotationBlend(Vector3f a, Vector3f b, float t) {
        // Quaternion normalized interpolation avoids the +/-180-degree transition discontinuity.
        org.joml.Quaternionf qa = new org.joml.Quaternionf().rotationZYX((float)Math.toRadians(a.z), (float)Math.toRadians(a.y), (float)Math.toRadians(a.x));
        org.joml.Quaternionf qb = new org.joml.Quaternionf().rotationZYX((float)Math.toRadians(b.z), (float)Math.toRadians(b.y), (float)Math.toRadians(b.x));
        Vector3f euler = qa.nlerp(qb, t).getEulerAnglesZYX(new Vector3f());
        return euler.mul((float)(180 / Math.PI));
    }
    private Object resolve(String name, List<Object> args, Molang.FunctionResolver fallback) {
        if (name.equals("ysm.sync")) {
            List<Object> values = BbModel.syncArguments(args);
            if (values == null || eventDepth >= 16) return 0d;
            eventDepth++;
            try {
                Runnable dispatch = () -> model.authorEvent("sync", values, expressions);
                if (ysm == null) dispatch.run(); else ysm.withoutBuiltinEvent(dispatch);
            } finally { eventDepth--; }
            return 0d;
        }
        if (name.startsWith("fn.")) {
            Molang.Program program = model.authorFunctions().get(name.substring(3));
            if (program == null || args.size() > 32 || functionDepth >= 16) return 0d;
            functionDepth++;
            try { return BbModel.callAuthorFunction(expressions, program, args); }
            finally { functionDepth--; }
        }
        if (name.equals("ysm.first_order") || name.equals("ysm.second_order")) {
            if (args.size() < 2 || !(args.get(0) instanceof String key) || key.isEmpty() || key.length() > 128) return 0d;
            Spring spring = springs.get(key); double input = safeNumber(args.get(1), 36_000);
            if (spring == null) {
                if (springs.size() >= 256) return input;
                spring = new Spring(name.equals("ysm.second_order")); springs.put(key, spring); spring.args(args, input); return input;
            }
            spring.args(args, input); return spring.value;
        }
        if (Set.of("ysm.bone_rot", "ysm.bone_pos", "ysm.bone_scale", "ysm.bone_pivot_abs").contains(name)) {
            String bone = args.isEmpty() ? "" : String.valueOf(args.get(0));
            int channel = switch (name) { case "ysm.bone_rot" -> 1; case "ysm.bone_scale" -> 2; case "ysm.bone_pivot_abs" -> 3; default -> 0; };
            Vector3f value = model.boneValue(bone, channel, readingPose);
            if (channel == 1) { value.x = -value.x; value.y = -value.y; }

            return new Molang.VectorValue(value.x, value.y, value.z);
        }
        if (name.startsWith("ctrl.") && ysm != null) return ysm.function(name, args, fallback);
        if (Set.of("ctrl.set_animation", "ctrl.set_beginning_transition_length", "ctrl.reset", "ctrl.indicate_reload").contains(name)) return 0d;
        if (Set.of("ysm.play_sound", "ysm.stop_sound", "ysm.stop_all_sounds", "ysm.particle", "ysm.abs_particle").contains(name))
            return fallback == null ? 0d : fallback.call(name, args);
        return fallback == null ? 0d : fallback.call(name, args);
    }
    private static double safeNumber(Object value, double bound) {
        double number = value instanceof Number n ? n.doubleValue() : 0;
        return Double.isFinite(number) ? Math.max(-bound, Math.min(bound, number)) : 0;
    }
    private static final class Spring {
        final boolean secondOrder;
        double input, frequency = 1, damping = 1, response = 1, value, velocity, previousInput;
        Spring(boolean secondOrder) { this.secondOrder = secondOrder; }
        void args(List<Object> args, double input) {
            this.input = input;
            if (secondOrder) {
                frequency = args.size() > 2 ? Math.max(0, Math.min(5, safeNumber(args.get(2), 5))) : 1;
                damping = args.size() > 3 ? Math.max(0, Math.min(1, safeNumber(args.get(3), 1))) : 1;
                response = args.size() > 4 ? safeNumber(args.get(4), 100) : 1;
            } else response = args.size() > 2 ? Math.max(.001, safeNumber(args.get(2), 100)) : 1;
        }
        void update(double dt) {
            if (dt <= 0) return;
            if (!secondOrder) { value += Math.min(1, dt / response) * (input - value); return; }
            if (frequency <= 0) return;
            double k1 = damping / Math.PI / frequency, k2 = 1 / Math.pow(2 * Math.PI * frequency, 2);
            double k3 = response * damping / (2 * Math.PI * frequency);
            double derivative = (input - previousInput) / dt; previousInput = input;
            double maxStep = Math.sqrt(4 * k2 + k1 * k1) - k1;
            int cycles = Math.max(1, Math.min(256, (int)Math.ceil(dt / maxStep))); double step = dt / cycles;
            for (int i = 0; i < cycles; i++) {
                value += step * velocity;
                velocity += step * (k3 * derivative + input - value - k1 * velocity) / k2;
            }
            if (!Double.isFinite(value) || !Double.isFinite(velocity)) { value = input; velocity = 0; }
            value = Math.max(-36_000, Math.min(36_000, value)); velocity = Math.max(-1e6, Math.min(1e6, velocity));
        }
    }

    private Contribution empty() { return new Contribution(model.emptyPose()); }

    private final class State {
        BbModel.Layer layer;
        Contribution previous;
        double changedAt;
        int duration;
        double eventTick;
        State(BbModel.Layer layer, Contribution previous, double changedAt, int duration) {
            this.layer = layer; this.previous = previous; this.changedAt = changedAt; this.duration = duration;
            eventTick=layer.startedAtTick()-1e-5;
        }
        Contribution at(double tick) {
            Contribution current = empty();
            if (layer != null) {
                BbModel.Evaluated target = model.evaluate(tick, layer, false, expressions);
                current = new Contribution(target.pose());
                for (int i = 0; i < current.pose.channels.length; i++) for (int c = 0; c < 3; c++)
                    if (current.pose.channels[i][c] != null) current.weights[i][c] = target.weight();
            }
            double t = duration == 0 ? (tick >= changedAt ? 1 : 0) : BbModel.clamp((tick - changedAt) / duration);
            if (t >= 1) return current;
            return blend(previous, current, t);
        }
    }
    private static final class Contribution {
        final BbModel.Pose pose;
        final double[][] weights;
        Contribution(BbModel.Pose pose) {
            this.pose = pose; weights = new double[pose.channels.length][3];
        }
    }
    private static Contribution blend(Contribution a, Contribution b, double fraction) {
        Contribution result = new Contribution(new BbModel.Pose(a.pose.channels.length));
        for (int i = 0; i < a.pose.channels.length; i++) for (int c = 0; c < 3; c++) {
            double left = (1 - fraction) * a.weights[i][c], right = fraction * b.weights[i][c];
            double total = left + right;
            if (total <= 0) continue;
            Vector3f av = a.pose.channels[i][c], bv = b.pose.channels[i][c];
            if (av == null) av = BbModel.defaultValue(c);
            if (bv == null) bv = BbModel.defaultValue(c);
            result.pose.channels[i][c] = new Vector3f(av).mul((float) (left / total))
                    .add(new Vector3f(bv).mul((float) (right / total)));
            result.weights[i][c] = total;
        }
        return result;
    }
    private static void apply(BbModel.Pose below, Contribution top, boolean additive) {
        for (int i = 0; i < below.channels.length; i++) for (int c = 0; c < 3; c++) {
            Vector3f value = top.pose.channels[i][c];
            if (value == null || top.weights[i][c] <= 0) continue;
            Vector3f base = below.channels[i][c];
            if (base == null) base = BbModel.defaultValue(c);
            below.channels[i][c] = BbModel.combine(base, value, c, (float) BbModel.clamp(top.weights[i][c]), additive);
        }
    }
}
