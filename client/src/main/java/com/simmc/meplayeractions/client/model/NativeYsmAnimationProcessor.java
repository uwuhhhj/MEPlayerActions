package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.expression.Molang;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** MIT source migration from OpenYSM 0306e1f BoneAnimationQueue, PredicateBasedController.TransformProviderRecord
 * and AnimationControllerRuntime.BoneBlendState. Adapted only to MPA's bounded degree-based channel arrays. */
final class NativeYsmAnimationProcessor {
    private final BbModel model;
    private final Map<YsmAnimationController.Playback, Slot> slots = new IdentityHashMap<>();
    private final Set<YsmAnimationController.Playback> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    // Predicate TransformProviderRecord owns a persistent minimum-only TransitionVector3f for each controller/bone.
    private final Map<String, double[]> predicateCompletion = new HashMap<>();
    static final class Contribution {
        final Slot slot;
        final BbModel.Pose raw;
        final boolean[][] evaluated;
        final double weight;
        final YsmAnimationController.Run run;
        final Molang.Context context;
        Contribution(Slot slot, double weight, YsmAnimationController.Run run, Molang.Context context) {
            this.slot = slot; this.weight = weight; this.run = run; this.context = context;
            this.raw = new BbModel.Pose(slot.origin.channels.length);
            this.evaluated = new boolean[slot.origin.channels.length][3];
        }
    }
    private static final class Slot {
        final long activation;
        final BbModel.Pose origin;
        final boolean[] scaleDeclared;
        BbModel.Pose cached;
        Slot(long activation, BbModel.Pose origin) { this.activation = activation; this.origin = origin.copy(); cached = new BbModel.Pose(origin.channels.length); scaleDeclared = new boolean[origin.channels.length]; }
    }
    NativeYsmAnimationProcessor(BbModel model) { this.model = model; }
    void reset() { slots.clear(); seen.clear(); predicateCompletion.clear(); }
    void beginFrame() { seen.clear(); }
    void finishFrame() { slots.keySet().removeIf(playback -> !seen.contains(playback)); }

    Contribution prepare(YsmAnimationController.Run run, Molang.Context context, BbModel.Pose finalSnapshot) {
        seen.add(run.playback());
        Slot slot = slots.get(run.playback());
        if (slot == null || slot.activation != run.activation()) {
            slot = new Slot(run.activation(), finalSnapshot); slots.put(run.playback(), slot);
        }
        for (int bone : model.nativeAnimationBones(run.animation()))
            slot.scaleDeclared[bone] = model.nativeChannelDeclared(run.animation(), bone, 2);
        // Instance.processBeginning/Running/Ending evaluates blend_weight before any raw keyframe expression.
        return new Contribution(slot, run.weight(), run, context);
    }

    private boolean hasChannel(Contribution source, int bone, int channel) {
        if (!source.run.contributes()) return false;
        return source.run.ending() ? source.slot.cached.channels[bone][channel] != null
                : model.nativeChannelDeclared(source.run.animation(), bone, channel);
    }

    private Vector3f value(Contribution source, int bone, int channel, boolean rawTransition,
                           boolean specialScale, YsmAnimationController controller) {
        if (source.run.ending()) return source.slot.cached.channels[bone][channel];
        if (!source.evaluated[bone][channel]) {
            controller.withPlayback(source.run.playback(), false, source.context,
                    () -> source.raw.channels[bone][channel] = model.evaluateNativeChannel(source.run.animation(),
                            source.run.elapsed(), source.run.loop(), source.run.beginning(), bone, channel, source.context, source.slot.origin));
            source.evaluated[bone][channel] = true;
        }
        Vector3f raw = source.raw.channels[bone][channel];
        if (source.run.beginning() && rawTransition) return raw;
        Vector3f point = new Vector3f(raw);
        if (source.run.beginning()) {
            Vector3f origin = source.slot.origin.channels[bone][channel]; if (origin == null) origin = BbModel.defaultValue(channel);
            point.set(origin).lerp(raw, specialScale && channel == 2 ? 1 : (float)source.run.progress());
        }
        // KeyFramePoint/getLerpPoint caches sampled values; TransitionPoint.evaluateRaw deliberately does not.
        source.slot.cached.channels[bone][channel] = new Vector3f(point);
        return point;
    }

    void apply(BbModel.Pose target, List<Contribution> sources, YsmAnimationController.Frame frame,
               YsmAnimationController controller) {
        if (sources.isEmpty()) return;
        // Runtime rebuilds the provider list in slot order, then each slot's animation bone order.
        Set<Integer> boneOrder = new LinkedHashSet<>();
        for (Contribution source : sources) boneOrder.addAll(model.nativeAnimationBones(source.run.animation()));
        for (int bone : boneOrder) for (int channel : new int[]{1, 0, 2}) {
            Contribution first = null;
            for (Contribution source : sources) if (hasChannel(source, bone, channel)) { first = source; break; }
            if (first == null) continue;
            Vector3f value;
            double completion = 1;
            if (frame.builtin()) {
                Vector3f raw = value(first, bone, channel, channel == 1, true, controller);
                double weight = first.weight;
                if (first.run.beginning()) {
                    float progress = channel == 2 ? 1 : (float)BbModel.clamp(first.run.progress());
                    Vector3f origin = first.slot.origin.channels[bone][channel]; if (origin == null) origin = BbModel.defaultValue(channel);
                    if (channel == 1) value = rotation(model, bone, origin, new Vector3f(raw).mul((float)weight), progress);
                    else {
                        value = new Vector3f(raw);
                        weight = 1 + (weight - 1) * progress;
                        if (channel == 2) value = new Vector3f(1).lerp(value, (float)weight);
                        else value.mul((float)weight);
                    }
                    completion = 0;
                } else {
                    value = channel == 2 ? new Vector3f(1).lerp(raw, (float)weight) : new Vector3f(raw).mul((float)weight);
                    completion = first.run.ending() ? progress(first, bone) : 0;
                }
                double[] retained = predicateCompletion.computeIfAbsent(frame.name(), name -> {
                    double[] initial = new double[target.channels.length]; Arrays.fill(initial, 1); return initial;
                });
                retained[bone] = Math.min(retained[bone], completion); completion = retained[bone];
            } else {
                value = BbModel.defaultValue(channel);
                boolean transition = first.run.beginning();
                float progress = (float)BbModel.clamp(first.run.progress());
                Vector3f origin = first.slot.origin.channels[bone][channel]; if (origin == null) origin = BbModel.defaultValue(channel);
                if (transition && Math.abs(progress) < 1e-5) { value.set(origin); completion = 0; }
                else {
                    for (Contribution source : sources) {
                        if (!hasChannel(source, bone, channel)) continue;
                        Vector3f raw = value(source, bone, channel, transition, false, controller);
                        double weight = source.weight;
                        if (!transition) {
                            double part = source.run.ending() ? progress(source, bone) : 0;
                            if (source.run.ending()) weight *= 1 - part;
                            completion = Math.min(completion, part);
                        }
                        if (channel == 2) value.mul(new Vector3f(1).lerp(raw, (float)weight));
                        else value.fma((float)weight, raw);
                    }
                    if (transition) {
                        value = channel == 1 ? rotation(model, bone, origin, value, progress) : new Vector3f(origin).lerp(value, progress);
                        completion = 0;
                    }
                }
            }
            Vector3f below = target.channels[bone][channel]; if (below == null) below = BbModel.defaultValue(channel);
            if (frame.deprecated() && channel == 1) target.channels[bone][channel] = new Vector3f(below).add(value);
            else target.channels[bone][channel] = completion == 0 ? value : channel == 1
                    ? rotation(model, bone, value, below, (float)BbModel.clamp(completion))
                    : new Vector3f(value).lerp(below, (float)BbModel.clamp(completion));
        }
    }

    /** BoneAnimationQueue.overrideMode uses ConstantPoint(totalTick=0) when that bone has a scale track. */
    private static double progress(Contribution source, int bone) {
        return source.slot.scaleDeclared[bone] ? source.run.progress() == 0 ? 0 : 1 : source.run.progress();
    }

    /** Exact MathUtil.nlerpEulerAngles arithmetic with the initial rotation included and source's ZYX extraction. */
    static Vector3f rotation(BbModel model, int bone, Vector3f from, Vector3f to, float progress) {
        Vector3f initial = model.initialBoneRotation(bone);
        Vector3f a = new Vector3f(from).add(initial).mul((float)(Math.PI / 180));
        Vector3f b = new Vector3f(to).add(initial).mul((float)(Math.PI / 180));
        Quaternionf qa = new Quaternionf().rotateZYX(a.z, a.y, a.x);
        Quaternionf qb = new Quaternionf().rotateZYX(b.z, b.y, b.x);
        qa.nlerp(qb, progress, qb);
        Vector3f result = new Vector3f(
                org.joml.Math.atan2(qb.y * qb.z + qb.w * qb.x, .5f - qb.x * qb.x - qb.y * qb.y),
                org.joml.Math.safeAsin(-2f * (qb.x * qb.z - qb.w * qb.y)),
                org.joml.Math.atan2(qb.x * qb.y + qb.w * qb.z, .5f - qb.y * qb.y - qb.z * qb.z));
        return result.mul((float)(180 / Math.PI)).sub(initial);
    }
}
