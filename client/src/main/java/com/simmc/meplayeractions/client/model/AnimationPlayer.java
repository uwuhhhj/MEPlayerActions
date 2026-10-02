package com.simmc.meplayeractions.client.model;

import org.joml.Vector3f;

import java.util.*;

/** Per-model-instance playback state. Repeated snapshots preserve the original clock. */
public final class AnimationPlayer {
    private final BbModel model;
    private final Map<String, State> states = new LinkedHashMap<>();
    private double lastTick = Double.NaN;

    public AnimationPlayer(BbModel model) { this.model = Objects.requireNonNull(model); }

    public void reset() { states.clear(); lastTick = Double.NaN; }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers) {
        return sample(serverTick, layers, 0, 0);
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers, float relativeHeadYaw, float headPitch) {
        BbModel.checkedTick(serverTick);
        List<BbModel.Layer> incoming = BbModel.ordered(layers);
        for (BbModel.Layer layer : incoming) {
            if (!model.animations().contains(layer.animation()))
                throw new IllegalArgumentException("Unknown animation: " + layer.animation());
        }
        // A world/clock reset must not blend a pose from the previous connection.
        if (!Double.isNaN(lastTick) && serverTick + 1 < lastTick) reset();
        lastTick = serverTick;
        Set<String> present = new HashSet<>();
        for (BbModel.Layer layer : incoming) {
            present.add(layer.layer());
            State state = states.get(layer.layer());
            if (state == null) {
                states.put(layer.layer(), new State(layer, empty(), layer.startedAtTick(), layer.inTicks()));
            } else if (!layer.equals(state.layer)) {
                Contribution previous = state.at(serverTick);
                int duration = Math.max(layer.inTicks(), state.layer == null ? 0 : state.layer.outTicks());
                state.previous = previous;
                state.layer = layer;
                state.changedAt = Math.max(serverTick, layer.startedAtTick());
                state.duration = duration;
            }
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
        model.applyLook(combined, relativeHeadYaw, headPitch, headWeights);
        return model.vertices(combined);
    }

    private Contribution empty() { return new Contribution(model.emptyPose()); }

    private final class State {
        BbModel.Layer layer;
        Contribution previous;
        double changedAt;
        int duration;
        State(BbModel.Layer layer, Contribution previous, double changedAt, int duration) {
            this.layer = layer; this.previous = previous; this.changedAt = changedAt; this.duration = duration;
        }
        Contribution at(double tick) {
            Contribution current = empty();
            if (layer != null) {
                BbModel.Evaluated target = model.evaluate(tick, layer, false);
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
