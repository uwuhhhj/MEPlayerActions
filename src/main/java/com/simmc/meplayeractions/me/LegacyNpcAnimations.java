package com.simmc.meplayeractions.me;

import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode;
import com.ticxo.modelengine.api.animation.Timeline;
import com.ticxo.modelengine.api.animation.keyframe.KeyframeType;
import com.ticxo.modelengine.api.animation.keyframe.KeyframeTypes;
import com.ticxo.modelengine.api.animation.keyframe.data.DoubleData;
import com.ticxo.modelengine.api.animation.keyframe.type.VectorKeyframe;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import org.joml.Vector3f;

import java.util.*;

/** Private overlay clips for the shipped NPC skeleton; the shared blueprint is never changed. */
public final class LegacyNpcAnimations {
    private LegacyNpcAnimations() {}
    private static final UUID ROOT = UUID.fromString("be6912e5-c593-0d8c-345b-9a4c9001afe1");
    // Blockbench -> ME: position * (1/16, 1/16, -1/16), rotation * (PI/180, -PI/180, -PI/180).
    private static final Vector3f PRONE_POSITION = new Vector3f(0, 2.55187252f / 16, -12.12139447f / 16);
    private static final List<KeyframeType<VectorKeyframe, Vector3f>> CHANNELS =
            List.of(KeyframeTypes.POSITION, KeyframeTypes.ROTATION, KeyframeTypes.SCALE);
    public record Result(Map<String, BlueprintAnimation> clips, Map<String, String> sources, String diagnosis) {
        public Result { clips = Map.copyOf(clips); sources = Map.copyOf(sources); }
    }
    public static Result create(ModelBlueprint blueprint) {
        if (!"ysm_01_jk_npc".equals(blueprint.getName())) return new Result(Map.of(), Map.of(), "当前模型无需旧 NPC 兼容");
        Set<String> wanted = Set.of("crawl_idle", "crawl_walk", "player_jump");
        if (blueprint.getAnimations().keySet().containsAll(wanted))
            return new Result(Map.of(), Map.of(), "模型已自带趴下/爬行/跳跃轨道");
        var root = blueprint.getFlatMap() == null ? null : blueprint.getFlatMap().get("root");
        if (root == null || !ROOT.equals(root.getUuid()) || root.getParent() != null
                || root.getGlobalPosition() == null
                || root.getGlobalPosition().distance(new Vector3f(0, -0.06379681f / 16, 0)) > 0.0001f)
            return new Result(Map.of(), Map.of(), "骨架与随包 NPC 不匹配；请安装对应新版 bbmodel");
        Map<String, BlueprintAnimation> clips = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String[] pair : List.of(new String[]{"climb_idle", "crawl_idle"}, new String[]{"climb", "crawl_walk"})) {
            String source = pair[0], target = pair[1];
            if (blueprint.getAnimations().containsKey(target)) continue;
            BlueprintAnimation original = blueprint.getAnimations().get(source);
            if (original == null || !standingRoot(original) || hasScripts(original)) continue;
            BlueprintAnimation copy = copy(original, target);
            Timeline timeline = copy.getTimelines().get(ROOT);
            constant(timeline, KeyframeTypes.POSITION, PRONE_POSITION);
            constant(timeline, KeyframeTypes.ROTATION, new Vector3f((float) Math.PI / 2, 0, 0));
            copy.setLoopMode(target.equals("crawl_idle") ? LoopMode.HOLD : LoopMode.LOOP);
            clips.put(target, copy); sources.put(target, source + " + 卧倒 Root（会话内兼容）");
        }
        BlueprintAnimation jump = blueprint.getAnimations().get("jump");
        if (!blueprint.getAnimations().containsKey("player_jump") && jump != null && !hasScripts(jump)
                && Math.abs(jump.getLength() - 0.75) < 0.0001 && numericRotations(jump)) {
            BlueprintAnimation copy = copy(jump, "player_jump");
            for (Timeline timeline : copy.getTimelines().values()) {
                if (!timeline.hasInterpolator(KeyframeTypes.ROTATION)) continue;
                var rotation = timeline.getInterpolator(KeyframeTypes.ROTATION);
                if (rotation.isEmpty()) continue;
                Vector3f pose = rotation.firstEntry().getValue().getValue(0, null);
                rotation.clear();
                float[][] phases = {{0, 0}, {0.10f, 0.35f}, {0.30f, 1}, {0.50f, 0.75f}, {0.65f, 0.3f}, {0.75f, 0}};
                for (float[] phase : phases) rotation.put(phase[0], vector(new Vector3f(pose).mul(phase[1])));
            }
            Timeline neutral = new Timeline(copy, false);
            constant(neutral, KeyframeTypes.POSITION, new Vector3f());
            constant(neutral, KeyframeTypes.ROTATION, new Vector3f());
            constant(neutral, KeyframeTypes.SCALE, new Vector3f(1));
            copy.getTimelines().put(ROOT, neutral); copy.setLoopMode(LoopMode.ONCE);
            clips.put("player_jump", copy); sources.put("player_jump", "jump + 起落过渡（会话内兼容）");
        }
        String diagnosis = clips.isEmpty() ? "旧 NPC 原动画不满足兼容条件；请安装新版 bbmodel"
                : "已从旧 NPC 为此会话补齐 " + String.join(", ", clips.keySet());
        return new Result(clips, sources, diagnosis);
    }
    private static boolean hasScripts(BlueprintAnimation original) {
        return original.getGlobalTimeline().hasInterpolator(KeyframeTypes.SCRIPT)
                && !original.getGlobalTimeline().getInterpolator(KeyframeTypes.SCRIPT).isEmpty();
    }
    private static boolean standingRoot(BlueprintAnimation original) {
        Timeline root = original.getTimelines().get(ROOT);
        return root != null && !root.isGlobalRotation() && zero(root, KeyframeTypes.POSITION)
                && zero(root, KeyframeTypes.ROTATION);
    }
    private static boolean zero(Timeline timeline, KeyframeType<VectorKeyframe, Vector3f> channel) {
        if (!timeline.hasInterpolator(channel)) return false;
        var frames = timeline.getInterpolator(channel);
        if (frames.size() != 1 || frames.firstKey() != 0) return false;
        var frame = frames.firstEntry().getValue();
        return numeric(frame) && frame.getValue(0, null).lengthSquared() < 0.00000001f;
    }
    private static boolean numericRotations(BlueprintAnimation original) {
        for (Timeline timeline : original.getTimelines().values()) {
            if (!timeline.hasInterpolator(KeyframeTypes.ROTATION)) continue;
            for (VectorKeyframe frame : timeline.getInterpolator(KeyframeTypes.ROTATION).values())
                if (!numeric(frame)) return false;
        }
        return true;
    }
    private static boolean numeric(VectorKeyframe frame) {
        for (var value : frame.getPreVector()) if (!(value instanceof DoubleData)) return false;
        return true;
    }
    private static BlueprintAnimation copy(BlueprintAnimation original, String name) {
        BlueprintAnimation copy = new BlueprintAnimation(original.getModelBlueprint(), name);
        copy.setLength(original.getLength()); copy.setLoopMode(original.getLoopMode()); copy.setOverride(original.isOverride());
        for (var entry : original.getTimelines().entrySet()) {
            Timeline timeline = new Timeline(copy, entry.getValue().isGlobalRotation());
            for (var channel : CHANNELS) if (entry.getValue().hasInterpolator(channel)) {
                for (var key : entry.getValue().getInterpolator(channel).entrySet())
                    timeline.getInterpolator(channel).put(key.getKey(), copy(key.getValue()));
            }
            copy.getTimelines().put(entry.getKey(), timeline);
        }
        return copy;
    }
    private static VectorKeyframe copy(VectorKeyframe source) {
        VectorKeyframe frame = new VectorKeyframe();
        frame.setX(source.getPreVector()[0]); frame.setY(source.getPreVector()[1]); frame.setZ(source.getPreVector()[2]);
        frame.setPostX(source.getPostVector()[0]); frame.setPostY(source.getPostVector()[1]); frame.setPostZ(source.getPostVector()[2]);
        frame.setXFactor(source.getXFactor()); frame.setYFactor(source.getYFactor()); frame.setZFactor(source.getZFactor());
        frame.setDiscontinuous(source.isDiscontinuous()); frame.setInterpolation(source.getInterpolation());
        float[] lt = source.getLeftTime(), lv = source.getLeftValue(), rt = source.getRightTime(), rv = source.getRightValue();
        frame.setBezierLeftTime(lt[0], lt[1], lt[2]); frame.setBezierLeftValue(lv[0], lv[1], lv[2]);
        frame.setBezierRightTime(rt[0], rt[1], rt[2]); frame.setBezierRightValue(rv[0], rv[1], rv[2]);
        return frame;
    }
    private static void constant(Timeline timeline, KeyframeType<VectorKeyframe, Vector3f> channel, Vector3f value) {
        var frames = timeline.getInterpolator(channel); frames.clear(); frames.put(0f, vector(value));
    }
    private static VectorKeyframe vector(Vector3f value) {
        VectorKeyframe frame = new VectorKeyframe();
        frame.setX(new DoubleData(value.x)); frame.setY(new DoubleData(value.y)); frame.setZ(new DoubleData(value.z));
        frame.setXFactor(1); frame.setYFactor(1); frame.setZFactor(1); frame.setInterpolation("linear");
        return frame;
    }
}
