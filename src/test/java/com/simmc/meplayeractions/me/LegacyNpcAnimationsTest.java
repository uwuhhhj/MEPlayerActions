package com.simmc.meplayeractions.me;

import com.google.gson.*;
import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode;
import com.ticxo.modelengine.api.animation.Timeline;
import com.ticxo.modelengine.api.animation.keyframe.KeyframeType;
import com.ticxo.modelengine.api.animation.keyframe.KeyframeTypes;
import com.ticxo.modelengine.api.animation.keyframe.data.DoubleData;
import com.ticxo.modelengine.api.animation.keyframe.type.VectorKeyframe;
import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import com.ticxo.modelengine.api.error.ErrorCollector;
import com.ticxo.modelengine.core.model.render.DisplayRendererImpl;
import org.bukkit.Location;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Loads the shipped stock 26 clips into real ME timelines, without starting a Minecraft server. */
class LegacyNpcAnimationsTest {
    private static final UUID ROOT = UUID.fromString("be6912e5-c593-0d8c-345b-9a4c9001afe1");
    private ModelBlueprint model(boolean legacy) throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of("examples/blueprints/npc/ysm_01_jk_npc.bbmodel"))).getAsJsonObject();
        var blueprint = new ModelBlueprint(); blueprint.setName("ysm_01_jk_npc");
        var root = new BlueprintBone(); root.setUuid(ROOT); root.setName("root");
        root.setGlobalPosition(new Vector3f(0, -0.06379681f / 16, 0)); blueprint.getBones().put("root", root);
        blueprint.constructFlatBoneMap(new ErrorCollector("test.bbmodel"));
        for (var item : json.getAsJsonArray("animations")) {
            var a = item.getAsJsonObject(); String name = a.get("name").getAsString();
            if (legacy && Set.of("crawl_idle", "crawl_walk", "player_jump", "bed_sleep").contains(name)) continue;
            var clip = new BlueprintAnimation(blueprint, name);
            clip.setLength(a.get("length").getAsDouble()); clip.setLoopMode(LoopMode.get(a.get("loop").getAsString()));
            for (var entry : a.getAsJsonObject("animators").entrySet()) {
                var track = entry.getValue().getAsJsonObject(); var timeline = new Timeline(clip, false);
                for (var value : track.getAsJsonArray("keyframes")) {
                    var frame = value.getAsJsonObject(); String channel = frame.get("channel").getAsString();
                    KeyframeType<VectorKeyframe, Vector3f> type = switch (channel) {
                        case "position" -> KeyframeTypes.POSITION;
                        case "rotation" -> KeyframeTypes.ROTATION;
                        case "scale" -> KeyframeTypes.SCALE;
                        default -> throw new IllegalArgumentException(channel);
                    };
                    VectorKeyframe key = timeline.getKeyframe(frame.get("time").getAsFloat(), type);
                    var points = frame.getAsJsonArray("data_points"); var point = points.get(0).getAsJsonObject();
                    key.setX(new DoubleData(point.get("x").getAsDouble()));
                    key.setY(new DoubleData(point.get("y").getAsDouble()));
                    key.setZ(new DoubleData(point.get("z").getAsDouble()));
                    if (channel.equals("position")) { key.setXFactor(1f/16); key.setYFactor(1f/16); key.setZFactor(-1f/16); }
                    if (channel.equals("rotation")) { key.setXFactor((float)Math.PI/180); key.setYFactor(-(float)Math.PI/180); key.setZFactor(-(float)Math.PI/180); }
                    if (channel.equals("scale")) { key.setXFactor(1); key.setYFactor(1); key.setZFactor(1); }
                    key.setInterpolation(frame.get("interpolation").getAsString());
                }
                clip.getTimelines().put(UUID.fromString(entry.getKey()), timeline);
            }
            blueprint.getAnimations().put(name, clip);
        }
        return blueprint;
    }
    private Vector3f root(BlueprintAnimation clip, KeyframeType<VectorKeyframe, Vector3f> channel) {
        return clip.getTimelines().get(ROOT).getInterpolator(channel).firstEntry().getValue().getValue(0, null);
    }
    @Test void legacy26ClipsGainThreePrivateOverlaysWithoutChangingSharedBlueprint() throws Exception {
        var blueprint = model(true); assertEquals(26, blueprint.getAnimations().size());
        var result = LegacyNpcAnimations.create(blueprint);
        assertEquals(Set.of("crawl_idle", "crawl_walk", "player_jump"), result.clips().keySet());
        assertEquals(26, blueprint.getAnimations().size()); assertFalse(blueprint.getAnimations().containsKey("crawl_idle"));
        for (String name : List.of("climb", "climb_idle")) {
            assertEquals(0, root(blueprint.getAnimations().get(name), KeyframeTypes.ROTATION).lengthSquared(), 0.000001);
            assertEquals(0, root(blueprint.getAnimations().get(name), KeyframeTypes.POSITION).lengthSquared(), 0.000001);
        }
        for (var clip : result.clips().values()) {
            assertSame(blueprint, clip.getModelBlueprint());
            for (var timeline : clip.getTimelines().values()) assertSame(clip, timeline.getAnimation());
        }
    }
    @Test void proneRootMatchesNewModelAndRemainsHorizontalAtScaleOnePointFive() throws Exception {
        var legacy = model(true); var modern = model(false); var result = LegacyNpcAnimations.create(legacy);
        for (String name : List.of("crawl_idle", "crawl_walk")) {
            var clip = result.clips().get(name);
            assertTrue(root(clip, KeyframeTypes.POSITION).distance(root(modern.getAnimations().get(name), KeyframeTypes.POSITION)) < 0.000001f);
            var rotation = root(clip, KeyframeTypes.ROTATION);
            assertEquals(Math.PI/2, rotation.x, 0.000001);
            var up = new Vector3f(0, 1.5f, 0).rotateX(rotation.x);
            assertEquals(0, up.y, 0.000001); assertEquals(1.5, up.z, 0.000001);
            var position = root(clip, KeyframeTypes.POSITION).mul(1.5f);
            assertEquals(2.55187252 * 1.5 / 16, position.y, 0.000001);
            assertEquals(-12.12139447 * 1.5 / 16, position.z, 0.000001);
        }
    }
    @Test void independentMePivotPreservesAuthoredCrawlHeightAndOffsetAtEveryYawAndScale() throws Exception {
        var legacy = model(true);
        var crawl = LegacyNpcAnimations.create(legacy).clips().get("crawl_idle");
        Vector3f authored = root(crawl, KeyframeTypes.POSITION)
                .add(legacy.getFlatMap().get("root").getGlobalPosition());
        var feet = new Location(null, 7.5, 64.25, -2.5);
        // Use the actual ME display pivot and its mount cancellation, rather than only
        // checking the animation's numbers. No player passenger attachment participates.
        var pivot = new DisplayRendererImpl.PivotImpl(1);
        assertFalse(pivot.isOverridden());
        for (float scale : new float[]{0.5f, 1f, 1.5f, 2f}) {
            for (float yaw : new float[]{0, 90, 180, 270}) {
                float modelEye = 1.62f * scale;
                pivot.updatePosition(feet, modelEye);
                Vector3f bodyOffset = new Vector3f(authored).mul(scale).rotateY((float)Math.toRadians(-yaw));
                Vector3f displayTranslation = new Vector3f(bodyOffset).sub(pivot.getMountOffset(modelEye));
                Vector3f rendered = new Vector3f(pivot.getPosition().get()).add(displayTranslation);
                Vector3f expected = new Vector3f((float)feet.getX(), (float)feet.getY(), (float)feet.getZ()).add(bodyOffset);
                assertTrue(rendered.distance(expected) < 0.00001f);
                assertEquals(feet.getY() + authored.y * scale, rendered.y, 0.00001);
            }
        }
    }
    @Test void copiesKeepLimbMotionButDoNotShareMutableFramesWithNpc() throws Exception {
        var blueprint = model(true); var result = LegacyNpcAnimations.create(blueprint);
        assertEquals(LoopMode.HOLD, result.clips().get("crawl_idle").getLoopMode());
        assertEquals(LoopMode.LOOP, result.clips().get("crawl_walk").getLoopMode());
        var source = blueprint.getAnimations().get("climb"); var copy = result.clips().get("crawl_walk");
        assertEquals(source.getLength(), copy.getLength()); assertEquals(source.getTimelines().keySet(), copy.getTimelines().keySet());
        for (var entry : source.getTimelines().entrySet()) if (!entry.getKey().equals(ROOT)) {
            var sourceTimeline = entry.getValue(); var copyTimeline = copy.getTimelines().get(entry.getKey());
            assertNotSame(sourceTimeline, copyTimeline);
            for (var type : List.of(KeyframeTypes.POSITION, KeyframeTypes.ROTATION, KeyframeTypes.SCALE)) {
                if (!sourceTimeline.hasInterpolator(type)) continue;
                var oldFrames = sourceTimeline.getInterpolator(type); var newFrames = copyTimeline.getInterpolator(type);
                assertEquals(oldFrames.keySet(), newFrames.keySet());
                for (var frame : oldFrames.entrySet()) {
                    var cloned = newFrames.get(frame.getKey()); assertNotSame(frame.getValue(), cloned);
                    assertEquals(frame.getValue().getValue(0, null), cloned.getValue(0, null));
                    assertEquals(frame.getValue().getInterpolation(), cloned.getInterpolation());
                }
            }
        }
        var boneId = source.getTimelines().keySet().stream().filter(id -> !id.equals(ROOT)
                && source.getTimelines().get(id).hasInterpolator(KeyframeTypes.ROTATION)).findFirst().orElseThrow();
        var oldFrame = source.getTimelines().get(boneId).getInterpolator(KeyframeTypes.ROTATION).firstEntry().getValue();
        var cloned = copy.getTimelines().get(boneId).getInterpolator(KeyframeTypes.ROTATION).firstEntry().getValue();
        var before = oldFrame.getValue(0, null); cloned.setX(new DoubleData(123));
        assertEquals(before, oldFrame.getValue(0, null));
    }
    @Test void installedNewClipsAndDifferentSkeletonsArePreserved() throws Exception {
        var modern = model(false); assertTrue(LegacyNpcAnimations.create(modern).clips().isEmpty());
        var legacy = model(true); legacy.setName("custom_npc");
        assertTrue(LegacyNpcAnimations.create(legacy).clips().isEmpty());
        legacy.setName("ysm_01_jk_npc"); legacy.getFlatMap().get("root").setUuid(UUID.randomUUID());
        assertTrue(LegacyNpcAnimations.create(legacy).clips().isEmpty());
        legacy.getFlatMap().get("root").setUuid(ROOT); legacy.getFlatMap().get("root").setGlobalPosition(new Vector3f(0, 1, 0));
        assertTrue(LegacyNpcAnimations.create(legacy).clips().isEmpty());
    }
    @Test void customClimbRootIsNotOverwrittenAndExistingProneClipWins() throws Exception {
        var legacy = model(true);
        legacy.getAnimations().get("climb_idle").getTimelines().get(ROOT).getInterpolator(KeyframeTypes.ROTATION)
                .firstEntry().getValue().setX(new DoubleData(20));
        var result = LegacyNpcAnimations.create(legacy); assertFalse(result.clips().containsKey("crawl_idle"));
        assertTrue(result.clips().containsKey("crawl_walk"));
        var custom = new BlueprintAnimation(legacy, "crawl_walk"); legacy.getAnimations().put("crawl_walk", custom);
        assertFalse(LegacyNpcAnimations.create(legacy).clips().containsKey("crawl_walk"));
        assertSame(custom, legacy.getAnimations().get("crawl_walk"));
    }
    @Test void jumpOverlayHasNeutralEndsAndNoExtraRootLift() throws Exception {
        var legacy = model(true); var jump = LegacyNpcAnimations.create(legacy).clips().get("player_jump");
        assertEquals(LoopMode.ONCE, jump.getLoopMode()); assertEquals(0.75, jump.getLength());
        assertEquals(new Vector3f(), root(jump, KeyframeTypes.POSITION));
        for (Timeline timeline : jump.getTimelines().values()) if (timeline.hasInterpolator(KeyframeTypes.ROTATION)) {
            var frames = timeline.getInterpolator(KeyframeTypes.ROTATION);
            if (frames.isEmpty()) continue;
            assertEquals(0, frames.firstEntry().getValue().getValue(0, null).lengthSquared(), 0.000001);
            assertEquals(0, frames.lastEntry().getValue().getValue(0, null).lengthSquared(), 0.000001);
        }
        assertFalse(legacy.getAnimations().containsKey("player_jump"));
    }
}
