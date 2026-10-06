package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Fixtures pin OpenYSM 0306e1f processor, queue, predicate and audio-manager lifecycle semantics. */
class NativeControllerMigrationTest {
    @Test void unownedFinalChannelResetsFromLastOwnedTickOverThreeTicks() {
        JsonObject raw = fixture(); clip(raw, "owned", "position", 12, 0, 0);
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[{\"owned\":\"!q.release\"}]}}}}");
        AnimationPlayer player = player(raw);
        player.sample(0, List.of()); assertEquals(.75, translationX(player), 1e-6);
        player.sample(1, List.of(), 0, 0, Map.of("query.release", 1d)); assertEquals(.5, translationX(player), 1e-6);
        player.sample(1, List.of(), 0, 0, Map.of("query.release", 1d)); assertEquals(.5, translationX(player), 1e-6);
        player.sample(2, List.of(), 0, 0, Map.of("query.release", 1d)); assertEquals(.25, translationX(player), 1e-6);
        player.sample(3, List.of(), 0, 0, Map.of("query.release", 1d)); assertEquals(0, translationX(player), 1e-6);
        player.sample(4, List.of()); assertEquals(.75, translationX(player), 1e-6);
        player.reset(); player.sample(5, List.of(), 0, 0, Map.of("query.release", 1d)); assertEquals(0, translationX(player), 1e-6);
    }

    @Test void newAnimationBeginsFromActualFinalBoneSnapshotIncludingLowerPriorityController() {
        JsonObject raw = fixture(); clip(raw, "base", "position", 16, 0, 0); clip(raw, "hold", "position", 32, 0, 0);
        AnimationPlayer player = player(raw);
        BbModel.Layer base = layer("posture", "base", 0, 0);
        player.sample(0, List.of(base)); assertEquals(1, translationX(player), 1e-6);
        BbModel.Layer hold = layer("player.hold_mainhand", "hold", 1, 4);
        player.sample(1, List.of(base, hold)); assertEquals(1, translationX(player), 1e-6);
        player.sample(3, List.of(base, hold)); assertEquals(1.5, translationX(player), 1e-6);
        player.sample(5, List.of(base, hold)); assertEquals(2, translationX(player), 1e-6);
    }

    @Test void predicateBeginningScaleUsesFullProgressAndAuthoredWeight() {
        JsonObject raw = fixture(); clip(raw, "hidden", "scale", 0, 1, 1);
        AnimationPlayer player = player(raw); player.sample(0, List.of(layer("player.hold_mainhand", "hidden", 0, 4)));
        assertTrue(player.filteredVertices(Set.of("bone")).isEmpty(), "Immediate zero scale hides the bone during beginning transition");
        JsonObject weighted = fixture(); clip(weighted, "grow", "scale", 2, 1, 1);
        weighted.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("blend_weight", ".25");
        AnimationPlayer second = player(weighted); second.sample(0, List.of(layer("player.hold_mainhand", "grow", 0, 4)));
        assertEquals(1.25, second.boneTransform("bone").orElseThrow().m00(), 1e-6);
    }

    @Test void nativePredicateRetainsSourceCachedCompletionUntilEndingAndThenStopsAudio() {
        JsonObject raw = fixture(); clip(raw, "base", "position", 16, 0, 0); clip(raw, "hold", "position", 32, 0, 0);
        timeline(raw, "hold", "ysm.play_sound(1,'safe:hold',4);");
        AnimationPlayer player = player(raw); List<Effect> calls = effects(player);
        BbModel.Layer base = layer("posture", "base", 0, 0), hold = layer("player.hold_mainhand", "hold", 0, 0);
        player.sample(0, List.of(base, hold)); calls.clear();
        player.sample(1, List.of(base)); assertEquals(2, translationX(player), 1e-6); assertTrue(calls.isEmpty());
        // All three pinned sources keep TransformProviderRecord.percentCompleted at its previous minimum zero.
        player.sample(2, List.of(base)); assertEquals(2, translationX(player), 1e-6); assertTrue(calls.isEmpty());
        player.sample(3, List.of(base)); assertEquals(2, translationX(player), 1e-6); assertTrue(calls.isEmpty());
        player.sample(4, List.of(base)); assertEquals(1, translationX(player), 1e-6);
        assertEquals("ysm.stop_all_sounds", calls.getFirst().name);
    }

    @Test void authorStateScaleKeepsOrdinaryBeginningInterpolation() {
        JsonObject raw = fixture(); clip(raw, "grow", "scale", 2, 1, 1);
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"grow\"],\"blend_transition\":.2}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of());
        assertEquals(1, player.boneTransform("bone").orElseThrow().m00(), 1e-6);
        player.sample(2, List.of()); assertEquals(1.5, player.boneTransform("bone").orElseThrow().m00(), 1e-6);
    }

    @Test void declaredScaleEndingAlsoRespectsPredicatePersistentCompletionMinimum() {
        JsonObject raw = fixture(); clip(raw, "base", "scale", 1, 1, 1); clip(raw, "grow", "scale", 2, 1, 1);
        AnimationPlayer player = player(raw);
        BbModel.Layer base = layer("posture", "base", 0, 0), grow = layer("player.hold_mainhand", "grow", 0, 0);
        player.sample(0, List.of(base, grow)); assertEquals(2, player.boneTransform("bone").orElseThrow().m00(), 1e-6);
        player.sample(1, List.of(base)); assertEquals(2, player.boneTransform("bone").orElseThrow().m00(), 1e-6);
        player.sample(2, List.of(base)); assertEquals(2, player.boneTransform("bone").orElseThrow().m00(), 1e-6);
        player.sample(4, List.of(base)); assertEquals(1, player.boneTransform("bone").orElseThrow().m00(), 1e-6);
    }

    @Test void rotationNlerpIncludesInitialBoneRotationAndMatchesAbsoluteQuaternionPath() {
        JsonObject raw = fixture(); Vector3f initial = new Vector3f(65, 30, 20);
        raw.getAsJsonArray("outliner").get(0).getAsJsonObject().add("rotation", JsonParser.parseString("[65,30,20]"));
        clip(raw, "a", "rotation", 80, 10, -20); clip(raw, "b", "rotation", -40, 55, 70);
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"],\"transitions\":[{\"b\":\"q.change\"}]},\"b\":{\"animations\":[\"b\"],\"blend_transition\":.2}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of());
        player.sample(1, List.of(), 0, 0, Map.of("query.change", 1d)); player.sample(3, List.of());
        Quaternionf from = quaternion(new Vector3f(initial).add(80, 10, -20));
        Quaternionf to = quaternion(new Vector3f(initial).add(-40, 55, 70));
        Matrix4f expected = new Matrix4f().rotation(from.nlerp(to, .5f));
        Matrix4f actual = player.boneTransform("bone").orElseThrow();
        assertTrue(expected.equals(actual, 1e-5f), "Blend must follow the absolute initial + animated rotation path");
    }

    @Test void endingOneSlotDoesNotRestartOrDampUnrelatedRunningChannelInSameState() {
        JsonObject raw = fixture(); clip(raw, "once", "position", 16, 0, 0); clip(raw, "ongoing", "rotation", 0, 0, 0);
        JsonObject once = raw.getAsJsonArray("animations").get(0).getAsJsonObject(); once.addProperty("length", 1); once.addProperty("loop", "ONCE");
        raw.getAsJsonArray("animations").get(1).getAsJsonObject().getAsJsonObject("animators").getAsJsonObject("bone")
                .getAsJsonArray("keyframes").get(0).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().addProperty("x", "q.anim_time*100");
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"once\",\"ongoing\"]}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of()); player.sample(19, List.of());
        player.sample(20, List.of());
        Matrix4f actual = player.boneTransform("bone").orElseThrow();
        assertEquals(new Matrix4f().rotationX((float)Math.toRadians(100)).m11(), actual.m11(), 1e-5);
        player.sample(21, List.of()); actual = player.boneTransform("bone").orElseThrow();
        assertEquals(new Matrix4f().rotationX((float)Math.toRadians(105)).m11(), actual.m11(), 1e-5);
        // Source BoneBlendState applies ConstantPoint influence, then its per-channel progress into the lower owner.
        assertEquals(4d / 9, actual.m30(), 1e-5);
    }

    @Test void invalidNativeControllerAnimationConditionUsesSourceZeroWithoutWeakeningOrdinaryParser() {
        JsonObject raw = fixture(); clip(raw, "a", "position", 16, 0, 0);
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[{\"a\":\"bad(\"}]}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of()); assertEquals(0, translationX(player), 1e-6);
        raw.remove("ysm_format_version");
        assertThrows(IllegalArgumentException.class, () -> BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test void absentAndEmptyNativeControllerAnimationConditionsKeepSourceDefaultActive() {
        for (String condition : List.of("\"a\"", "{\"a\":\"\"}")) {
            JsonObject raw = fixture(); clip(raw, "a", "position", 16, 0, 0);
            controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[" + condition + "]}}}}");
            AnimationPlayer player = player(raw); player.sample(0, List.of()); assertEquals(1, translationX(player), 1e-6);
        }
    }

    @Test void nativeRawExpressionsEvaluateRotationBeforePosition() {
        JsonObject raw = fixture(); clip(raw, "a", "position", 0, 0, 0);
        channel(raw, "a", "position", "v.marker", "0", "0");
        channel(raw, "a", "rotation", "v.marker=2;return 0;", "0", "0");
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of()); assertEquals(2d / 16, translationX(player), 1e-6);
    }

    @Test void nativeSlotExpressionsInterleaveByChannelInsteadOfSamplingWholeClips() {
        JsonObject raw = fixture(); clip(raw, "a", "position", 0, 0, 0); clip(raw, "b", "position", 0, 0, 0);
        channel(raw, "a", "position", "v.marker", "0", "0");
        channel(raw, "b", "position", "v.marker", "0", "0");
        channel(raw, "a", "rotation", "v.marker=16;return 0;", "0", "0");
        channel(raw, "b", "rotation", "v.marker=32;return 0;", "0", "0");
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\",\"b\"]}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of()); assertEquals(4, translationX(player), 1e-6);
    }

    @Test void nativeBlendWeightIsFrozenBeforeRawChannelSideEffects() {
        JsonObject raw = fixture(); clip(raw, "a", "position", 16, 0, 0);
        channel(raw, "a", "position", "v.weight=0;return 16;", "0", "0");
        raw.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("blend_weight", "v.weight");
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}}");
        AnimationPlayer player = player(raw); player.configureFrame(context -> context.set("variable.weight", .5));
        player.sample(0, List.of()); assertEquals(.5, translationX(player), 1e-6);
        assertEquals(0d, player.expressionVariables().get("variable.weight"), 1e-6);
    }

    @Test void nativeEachSlotRunsEventsAndWeightBeforeTheNextSlotCondition() {
        JsonObject raw = fixture(); clip(raw, "a", "position", 16, 0, 0); clip(raw, "b", "position", 0, 0, 0);
        timeline(raw, "a", "v.weight=.5;");
        raw.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("blend_weight", "v.weight");
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\",{\"b\":\"v.weight=0;return 1;\"}]}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of()); assertEquals(.5, translationX(player), 1e-6);
        assertEquals(0d, player.expressionVariables().get("variable.weight"), 1e-6);
    }

    @Test void nativeInactiveSlotStillEvaluatesWeightBeforeActiveRawChannels() {
        JsonObject raw = fixture(); clip(raw, "a", "position", 0, 0, 0); clip(raw, "b", "position", 0, 0, 0);
        raw.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("blend_weight", "v.marker=16;return 1;");
        channel(raw, "b", "position", "v.marker", "0", "0");
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[{\"a\":\"0\"},\"b\"]}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of()); assertEquals(1, translationX(player), 1e-6);
    }

    @Test void nativeLaterSlotConditionReadsThePreviousInstanceAnimationContext() {
        JsonObject raw = fixture(); clip(raw, "a", "rotation", 0, 0, 0); clip(raw, "b", "position", 16, 0, 0);
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\",{\"b\":\"q.anim_time>.5\"}]}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of()); assertEquals(0, translationX(player), 1e-6);
        player.sample(20, List.of()); assertEquals(1, translationX(player), 1e-6);
    }

    @Test void inactiveSlotStillEstablishesSourceProviderBoneOrder() {
        JsonObject raw = fixture();
        raw.getAsJsonArray("outliner").add(JsonParser.parseString("{\"uuid\":\"other\",\"name\":\"other\",\"origin\":[0,0,0],\"children\":[]}"));
        clip(raw, "inactive", "position", 0, 0, 0); clip(raw, "active", "position", 0, 0, 0);
        JsonObject inactive = raw.getAsJsonArray("animations").get(0).getAsJsonObject();
        JsonObject original = inactive.getAsJsonObject("animators").getAsJsonObject("bone"), order = new JsonObject();
        order.add("other", original.deepCopy()); order.add("bone", original); inactive.add("animators", order);
        JsonObject active = raw.getAsJsonArray("animations").get(1).getAsJsonObject();
        active.getAsJsonObject("animators").add("other", active.getAsJsonObject("animators").get("bone").deepCopy());
        channel(raw, "active", "bone", "position", "v.marker", "0", "0");
        channel(raw, "active", "other", "position", "v.marker=16;return 0;", "0", "0");
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[{\"inactive\":\"0\"},\"active\"]}}}}");
        AnimationPlayer player = player(raw); player.sample(0, List.of()); assertEquals(1, translationX(player), 1e-6);
    }

    @Test void controllerAndPlaybackSoundsStopWithinTheirOwnScopesAndGlobalSurvivesStateTransition() {
        JsonObject raw = fixture(); clip(raw, "a", "position", 0, 0, 0); timeline(raw, "a", "ysm.play_sound(3,'safe:slot',4);ysm.play_sound(4,'safe:global',6);");
        controllers(raw, "{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"],\"on_entry\":[\"ysm.play_sound(1,'safe:controller',4);\"],\"transitions\":[{\"next\":\"q.change\"}]},\"next\":{\"on_entry\":[\"ysm.play_sound(2,'safe:next',4);\"]}}}}");
        AnimationPlayer player = player(raw); List<Effect> calls = effects(player);
        player.sample(0, List.of());
        String controller = scopeFor(calls, "safe:controller"), slot = scopeFor(calls, "safe:slot");
        calls.clear(); player.sample(1, List.of(), 0, 0, Map.of("query.change", 1d));
        assertTrue(calls.stream().anyMatch(e -> e.name.equals("ysm.stop_all_sounds") && e.scope.equals(controller) && e.args.isEmpty()));
        assertTrue(calls.stream().anyMatch(e -> e.name.equals("ysm.stop_all_sounds") && e.scope.equals(slot) && e.args.isEmpty()));
        assertFalse(calls.stream().anyMatch(e -> e.name.equals("ysm.stop_all_sounds") && !e.args.isEmpty()));
        int stopController = index(calls, "ysm.stop_all_sounds", controller), startNew = index(calls, "ysm.play_sound", controller);
        assertTrue(stopController < startNew, "Old controller manager stops before on_entry of the next state");
        calls.clear(); player.reset();
        assertTrue(calls.stream().anyMatch(e -> e.name.equals("ysm.stop_all_sounds") && e.args.equals(List.of(1d))));
    }

    @Test void loopStopsPreviousPlaybackManagerBeforeZeroTimelineStartsAgain() {
        JsonObject raw = fixture(); clip(raw, "a", "position", 0, 0, 0);
        raw.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("length", .5);
        timeline(raw, "a", "ysm.play_sound(1,'safe:cycle',4);");
        AnimationPlayer player = player(raw); List<Effect> calls = effects(player);
        List<BbModel.Layer> layers = List.of(layer("player.hold_mainhand", "a", 0, 0));
        player.sample(0, layers); String scope = scopeFor(calls, "safe:cycle"); calls.clear();
        player.sample(10, layers); assertTrue(calls.isEmpty());
        player.sample(11, layers);
        assertEquals("ysm.stop_all_sounds", calls.getFirst().name); assertEquals(scope, calls.getFirst().scope);
        assertEquals("ysm.play_sound", calls.getLast().name); assertEquals(scope, calls.getLast().scope);
    }

    @Test void authorResetStopsPlaybackAudioWithoutExecutingDeferredAuthorEvents() {
        JsonObject raw = fixture(); clip(raw, "a", "position", 0, 0, 0);
        timeline(raw, "a", "ysm.play_sound(1,'safe:active',4);ysm.defer('later',1);");
        events(raw, "player_ctrl_hold_mainhand", "q.reset ? ctrl.reset() : 0;return q.reset ? 3 : 5;");
        events(raw, "defer", "v.deferred+=1;");
        AnimationPlayer player = player(raw); List<Effect> calls = effects(player);
        List<BbModel.Layer> layers = List.of(layer("player.hold_mainhand", "a", 0, 0));
        player.sample(0, layers); calls.clear(); player.sample(1, layers, 0, 0, Map.of("query.reset", 1d));
        assertEquals(1, calls.size()); assertEquals("ysm.stop_all_sounds", calls.getFirst().name);
        assertFalse(player.expressionVariables().containsKey("variable.deferred"));
        player.dispose(); assertFalse(player.expressionVariables().containsKey("variable.deferred"));
    }

    private record Effect(String name, String scope, List<Object> args) { }
    private static List<Effect> effects(AnimationPlayer player) {
        List<Effect> calls = new ArrayList<>();
        player.configureFrame(context -> context.functions((name, args) -> { calls.add(new Effect(name, context.effectScope(), List.copyOf(args))); return true; }));
        return calls;
    }
    private static String scopeFor(List<Effect> calls, String resource) { return calls.stream().filter(e -> e.name.equals("ysm.play_sound") && e.args.contains(resource)).findFirst().orElseThrow().scope; }
    private static int index(List<Effect> calls, String name, String scope) { for (int i = 0; i < calls.size(); i++) if (calls.get(i).name.equals(name) && calls.get(i).scope.equals(scope)) return i; return -1; }
    private static Quaternionf quaternion(Vector3f degrees) { degrees.mul((float)(Math.PI / 180)); return new Quaternionf().rotateZYX(degrees.z, degrees.y, degrees.x); }
    private static double translationX(AnimationPlayer player) { return player.boneTransform("bone").orElseThrow().m30(); }
    private static AnimationPlayer player(JsonObject raw) { AnimationPlayer player = new AnimationPlayer(BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8))); player.enableNativeYsm(); return player; }
    private static BbModel.Layer layer(String slot, String clip, long started, int in) { return new BbModel.Layer(slot, clip, started, 1, "LOOP", in, 4); }
    private static void controllers(JsonObject raw, String text) { raw.add("ysm_animation_controllers", JsonParser.parseString(text)); }
    private static void events(JsonObject raw, String name, String script) { JsonObject events = raw.has("ysm_events") ? raw.getAsJsonObject("ysm_events") : new JsonObject(); JsonArray array = new JsonArray(); array.add(script); events.add(name, array); raw.add("ysm_events", events); }
    private static void timeline(JsonObject raw, String name, String script) {
        for (JsonElement element : raw.getAsJsonArray("animations")) if (element.getAsJsonObject().get("name").getAsString().equals(name)) {
            JsonObject point = new JsonObject(); point.addProperty("script", script); JsonArray points = new JsonArray(); points.add(point);
            JsonObject frame = new JsonObject(); frame.addProperty("channel", "timeline"); frame.addProperty("time", 0); frame.add("data_points", points);
            JsonArray frames = new JsonArray(); frames.add(frame); JsonObject animator = new JsonObject(); animator.addProperty("type", "effect"); animator.add("keyframes", frames);
            element.getAsJsonObject().getAsJsonObject("animators").add("effects", animator);
        }
    }
    private static void clip(JsonObject raw, String name, String channel, double x, double y, double z) {
        JsonObject point = new JsonObject(); point.addProperty("x", x); point.addProperty("y", y); point.addProperty("z", z);
        JsonArray points = new JsonArray(); points.add(point); JsonObject frame = new JsonObject(); frame.addProperty("channel", channel); frame.addProperty("time", 0); frame.add("data_points", points);
        JsonArray frames = new JsonArray(); frames.add(frame); JsonObject animator = new JsonObject(); animator.add("keyframes", frames); JsonObject animators = new JsonObject(); animators.add("bone", animator);
        JsonObject clip = new JsonObject(); clip.addProperty("name", name); clip.addProperty("length", 10); clip.addProperty("loop", "LOOP"); clip.add("animators", animators); raw.getAsJsonArray("animations").add(clip);
    }
    private static void channel(JsonObject raw, String animation, String channel, String x, String y, String z) {
        channel(raw, animation, "bone", channel, x, y, z);
    }
    private static void channel(JsonObject raw, String animation, String bone, String channel, String x, String y, String z) {
        for (JsonElement element : raw.getAsJsonArray("animations")) if (element.getAsJsonObject().get("name").getAsString().equals(animation)) {
            JsonArray frames = element.getAsJsonObject().getAsJsonObject("animators").getAsJsonObject(bone).getAsJsonArray("keyframes");
            JsonObject target = null;
            for (JsonElement value : frames) if (value.getAsJsonObject().get("channel").getAsString().equals(channel)) target = value.getAsJsonObject();
            if (target == null) { target = new JsonObject(); target.addProperty("channel", channel); target.addProperty("time", 0); frames.add(target); }
            JsonObject point = new JsonObject(); point.addProperty("x", x); point.addProperty("y", y); point.addProperty("z", z);
            JsonArray points = new JsonArray(); points.add(point); target.add("data_points", points);
        }
    }
    private static JsonObject fixture() {
        JsonObject raw = JsonParser.parseString("""
                {"meta":{"format_version":"5.0"},"ysm_format_version":65535,"ysm_controller_family":"player","textures":[],"animations":[],
                 "elements":[{"uuid":"cube","from":[0,0,0],"to":[16,16,16],"faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
                 "outliner":[{"uuid":"bone","name":"bone","origin":[0,0,0],"children":["cube"]}]}
                """).getAsJsonObject();
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB); image.setRGB(0, 0, 0xffffffff);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
            JsonObject texture = new JsonObject(); texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray())); raw.getAsJsonArray("textures").add(texture);
        } catch (Exception failed) { throw new AssertionError(failed); }
        return raw;
    }
}
