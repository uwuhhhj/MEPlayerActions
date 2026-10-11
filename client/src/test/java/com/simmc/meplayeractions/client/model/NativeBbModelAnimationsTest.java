package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.expression.Molang;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Authored channels follow official editor axes after evaluation; native and preset channels keep their source semantics. */
class NativeBbModelAnimationsTest {
    @ParameterizedTest
    @ValueSource(strings = {"4.10", "5.0"})
    void numericMultiAxisRotationPositionAndScaleUseTheOfficialZyxEditorPose(String version) throws Exception {
        JsonObject source = source(version);
        source.getAsJsonArray("outliner").get(0).getAsJsonObject().add("rotation", numbers(13, 27, -9));
        keys(source).add(frame("rotation", 0, "linear", "23", "-41", "57"));
        keys(source).add(frame("position", 0, "linear", "5", "-3", "7"));
        keys(source).add(frame("scale", 0, "linear", "1.2", ".8", "1.5"));
        BbModel model = imported(source);
        int sign = version.startsWith("4") ? -1 : 1;
        Matrix4f expected = new Matrix4f().translate(sign * 5 / 16f, -3 / 16f, 7 / 16f)
                .rotateZ(radians(-9 + 57)).rotateY(radians(27 + sign * -41)).rotateX(radians(13 + sign * 23))
                .scale(1.2f, .8f, 1.5f);
        assertArrayEquals(expected.get(new float[16]), model.boneTransforms(sample(model, 0, context())).get("Body").get(new float[16]), 1e-5f);
    }

    @ParameterizedTest
    @MethodSource("completePrograms")
    void wholeProgramsIncludingAssignmentsNestedReturnsAndCommentsKeepTheirResultAndSideEffects(String version, String expression)
            throws Exception {
        JsonObject source = source(version);
        keys(source).add(frame("rotation", 0, "linear", expression, "-41", "math.sin(90)*57"));
        byte[] bytes = source.toString().getBytes(StandardCharsets.UTF_8);
        var imported = NativeBbModel.read(bytes, Map.of("model.bbmodel", bytes), null);
        BbModel model = BbModel.parseLocal(imported.raw());
        Molang.Context context = context(); context.query("q.flag", 1);
        int sign = version.startsWith("4") ? -1 : 1;
        Vector3f rotation = sample(model, 0, context).channels[model.boneIndex("Body")][1];
        assertEquals(sign * 23, rotation.x, 1e-5);
        assertEquals(sign * -41, rotation.y, 1e-5);
        assertEquals(57, rotation.z, 1e-5);
        if (expression.contains("v.turn")) assertEquals(23d, context.variables().get("variable.turn"));
        JsonObject authored = authoredAnimations(imported).getAsJsonObject("authored");
        assertEquals(expression, authored.getAsJsonObject("bones").getAsJsonObject("Body")
                .getAsJsonObject("rotation").getAsJsonObject("0.0").getAsJsonArray("post").get(0).getAsString(),
                "Host adaptation must not rewrite an author program");
    }

    static Stream<Arguments> completePrograms() {
        List<String> programs = List.of("q.flag ? 23 : -7", "v.turn=23; v.turn;", "v.turn=23; RETURN v.turn;",
                "return (q.flag ? { return 23; } : -7);", "q.flag ? { return 23; } : { return -7; };",
                "/* return -100; */ q.flag ? 23 : -7", "// return -100;\n{v.turn=23; v.turn;}");
        return Stream.of("4.10", "5.0").flatMap(version -> programs.stream().map(program -> Arguments.of(version, program)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.10", "5.0"})
    void previousPositionThisUsesTheSameAxesInClipAndNativeQueueEvaluation(String version) throws Exception {
        JsonObject source = source(version);
        keys(source).add(frame("position", 0, "linear", "this+1", "this+2", "this+3"));
        BbModel model = imported(source);
        int bone = model.boneIndex("Body");
        BbModel.Pose previous = model.emptyPose(); previous.channels[bone][0] = new Vector3f(8, -4, 2);
        Vector3f expected = new Vector3f(version.startsWith("4") ? 7 : 9, -2, 5);
        assertEquals(expected, model.evaluateClip("authored", 5, "HOLD", context(), previous).pose().channels[bone][0]);
        assertEquals(expected, model.evaluateNativeChannel("authored", 5, "HOLD", false, bone, 0, context(), previous));
        assertEquals(expected, model.evaluateNativeChannel("authored", 5, "HOLD", true, bone, 0, context(), previous));
        assertEquals(new Vector3f(8, -4, 2), previous.channels[bone][0]);
    }

    @Test
    void officialFourToFiveKeyframeMigrationProducesTheSameFinalPose() throws Exception {
        JsonObject old = source("4.10"), modern = source("5.0");
        keys(old).add(frame("rotation", 0, "linear", "23", "-41", "57"));
        keys(old).add(frame("position", 0, "linear", "math.sin(30)*10", "-3", "7"));
        keys(modern).add(frame("rotation", 0, "linear", "-23", "41", "57"));
        keys(modern).add(frame("position", 0, "linear", "-(math.sin(30)*10)", "-3", "7"));
        BbModel first = imported(old), second = imported(modern);
        assertArrayEquals(first.boneTransforms(sample(first, 0, context())).get("Body").get(new float[16]),
                second.boneTransforms(sample(second, 0, context())).get("Body").get(new float[16]), 1e-5f);
    }

    @ParameterizedTest
    @CsvSource({"4.10,bezier,linear,0,0,-15", "5.0,bezier,linear,0,0,15",
            "4.10,bezier,linear,6,-4,-15.75", "5.0,bezier,linear,6,-4,15.75",
            "4.10,linear,bezier,6,-4,-15.75", "5.0,linear,bezier,6,-4,15.75",
            "4.10,step,bezier,6,-4,-10", "5.0,step,bezier,6,-4,10",
            "4.10,bezier,catmullrom,6,-4,-15", "5.0,bezier,catmullrom,6,-4,15",
            "4.10,catmullrom,bezier,6,-4,-15", "5.0,catmullrom,bezier,6,-4,15"})
    void bezierUsesEndpointOffsetsSignedSecondsAndOfficialInterpolationPriority(String version, String outgoing, String incoming,
                                                                                float rightValue, float leftValue, float expected)
            throws Exception {
        JsonObject source = source(version);
        JsonObject start = frame("position", 0, outgoing, "10", "0", "0");
        JsonObject end = frame("position", 2, incoming, "20", "0", "0");
        start.add("bezier_right_time", numbers(1, 1, 1)); start.add("bezier_right_value", numbers(rightValue, 0, 0));
        end.add("bezier_left_time", numbers(-1, -1, -1)); end.add("bezier_left_value", numbers(leftValue, 0, 0));
        keys(source).add(start); keys(source).add(end);
        BbModel model = imported(source);
        assertEquals(expected, sample(model, 20, context()).channels[model.boneIndex("Body")][0].x, 1e-4);
    }

    @Test
    void migratedBezierEndpointsAndValueOffsetsAreEquivalentInFourAndFive() throws Exception {
        JsonObject old = bezierSource("4.10", 10, 20, 6, -4), modern = bezierSource("5.0", -10, -20, -6, 4);
        BbModel first = imported(old), second = imported(modern);
        for (double tick : new double[]{0, 5, 10, 20, 30, 35, 40})
            assertEquals(sample(first, tick, context()).channels[first.boneIndex("Body")][0].x,
                    sample(second, tick, context()).channels[second.boneIndex("Body")][0].x, 1e-4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.10", "5.0"})
    void firstDualDataPointIsBeforeTheKeyAndTheLastIsAfterIt(String version) throws Exception {
        JsonObject source = source(version);
        keys(source).add(frame("position", 0, "linear", "0", "0", "0"));
        JsonObject jump = frame("position", 1, "linear", "10", "0", "0");
        jump.getAsJsonArray("data_points").add(point("20", "0", "0"));
        keys(source).add(jump); keys(source).add(frame("position", 2, "linear", "30", "0", "0"));
        BbModel model = imported(source);
        int sign = version.startsWith("4") ? -1 : 1;
        assertEquals(sign * 9.99, sample(model, 19.98, context()).channels[model.boneIndex("Body")][0].x, 1e-4);
        assertEquals(sign * 10, sample(model, 20, context()).channels[model.boneIndex("Body")][0].x, 1e-4);
        assertEquals(sign * 20.01, sample(model, 20.02, context()).channels[model.boneIndex("Body")][0].x, 1e-4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ysm_01_jk", "ysm_02_jk"})
    void publishedNumericBlueprintWalkUsesTheSameAuthorMotionAsItsEditorReader(String id) throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/numeric-blueprints/" + id + ".bbmodel")) {
            assertNotNull(stream); bytes = stream.readAllBytes();
        }
        BbModel editor = BbModel.parse(bytes), decoded = BbModelAsset.read(bytes).model();
        assertTrue(editor.animations().contains("walk"));
        for (double tick : new double[]{0, 1, 4, 8, 12, 19}) {
            var expected = editor.boneTransforms(editor.evaluateClip("walk", tick, "LOOP", new Molang.Context()).pose());
            var actual = decoded.boneTransforms(decoded.evaluateClip("walk", tick, "LOOP", context()).pose());
            for (String bone : expected.keySet())
                assertArrayEquals(expected.get(bone).get(new float[16]), actual.get(bone).get(new float[16]), 1e-4f,
                        id + "/walk tick " + tick + "/" + bone);
        }
    }

    @Test
    void authoredAliasesInheritTheirAxesWhileBuiltinPresetsAndTrueNativeAnimationsRemainUnmarked() throws Exception {
        JsonObject source = source("5.0");
        animation(source).addProperty("name", "hold_mainhand:spear");
        keys(source).add(frame("rotation", 0, "linear", "23", "0", "0"));
        var decoded = NativeBbModel.read(source.toString().getBytes(StandardCharsets.UTF_8),
                Map.of("model.bbmodel", source.toString().getBytes(StandardCharsets.UTF_8)), null);
        JsonObject animations = authoredAnimations(decoded);
        assertEquals(5, animations.getAsJsonObject("hold_mainhand:spear").get("mpa_bb_editor_axes").getAsInt());
        assertEquals(5, animations.getAsJsonObject("hold_mainhand:lance").get("mpa_bb_editor_axes").getAsInt());
        assertFalse(animations.getAsJsonObject("idle").has("mpa_bb_editor_axes"));

        var nativeModel = NativeYsmFileTest.model();
        var file = new com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel.RawAnimationFile();
        var clip = new com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel.RawAnimation(); clip.name = "authored"; clip.length = 2;
        var track = new com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel.RawBoneAnimation(); track.boneName = "ysmGlowEyes";
        var key = new com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel.RawKeyframe(); key.postData = new Object[]{23, 0, 0};
        track.rotation.add(key); clip.boneAnimations.add(track); file.animations.put(clip.name, clip); nativeModel.mainEntity.animationFiles.put("main", file);
        BbModel preserved = BbModel.parseLocal(NativeYsmFile.importModel(nativeModel, null).raw());
        assertEquals(-23, sample(preserved, 0, context()).channels[preserved.boneIndex("ysmGlowEyes")][1].x, 1e-5);
    }

    private static JsonObject bezierSource(String version, float from, float to, float right, float left) throws Exception {
        JsonObject source = source(version);
        JsonObject start = frame("position", 0, "bezier", Float.toString(from), "0", "0");
        JsonObject end = frame("position", 2, "linear", Float.toString(to), "0", "0");
        start.add("bezier_right_time", numbers(1, 1, 1)); start.add("bezier_right_value", numbers(right, 0, 0));
        end.add("bezier_left_time", numbers(-1, -1, -1)); end.add("bezier_left_value", numbers(left, 0, 0));
        keys(source).add(start); keys(source).add(end);
        return source;
    }

    private static JsonObject source(String version) throws Exception {
        JsonObject source = NativeBbModelTest.source();
        source.add("meta", JsonParser.parseString("{\"format_version\":\"" + version + "\",\"model_format\":\"bedrock\"}"));
        JsonArray elements = new JsonArray(); elements.add(source.getAsJsonArray("elements").get(0).deepCopy()); source.add("elements", elements);
        source.remove("groups"); source.remove("animation_controllers");
        source.add("outliner", JsonParser.parseString("[{\"uuid\":\"body\",\"name\":\"Body\",\"origin\":[0,0,0],\"children\":[\"cube\"]}]"));
        source.add("animations", JsonParser.parseString("[{\"name\":\"authored\",\"length\":2,\"loop\":\"hold\",\"animators\":{\"body\":{\"name\":\"Body\",\"type\":\"bone\",\"keyframes\":[]}}}]"));
        return source;
    }

    private static JsonObject animation(JsonObject source) { return source.getAsJsonArray("animations").get(0).getAsJsonObject(); }
    private static JsonObject authoredAnimations(YsmFolderModel.Imported imported) {
        String path = imported.profile().animationFiles().get("main");
        byte[] resource = imported.sourceFiles().get(path);
        assertNotNull(resource, path);
        return JsonParser.parseString(new String(resource, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations");
    }
    private static JsonArray keys(JsonObject source) { return animation(source).getAsJsonObject("animators").getAsJsonObject("body").getAsJsonArray("keyframes"); }
    private static JsonObject point(String x, String y, String z) {
        JsonObject point = new JsonObject(); point.addProperty("x", x); point.addProperty("y", y); point.addProperty("z", z); return point;
    }
    private static JsonObject frame(String channel, float time, String interpolation, String x, String y, String z) {
        JsonObject frame = new JsonObject(); frame.addProperty("channel", channel); frame.addProperty("time", time); frame.addProperty("interpolation", interpolation);
        JsonArray points = new JsonArray(); points.add(point(x, y, z)); frame.add("data_points", points); return frame;
    }
    private static JsonArray numbers(float... values) { JsonArray result = new JsonArray(); for (float value : values) result.add(value); return result; }
    private static float radians(float degrees) { return (float) Math.toRadians(degrees); }
    private static Molang.Context context() { var result = new Molang.Context(); result.enableNativeYsm(); return result; }
    private static BbModel imported(JsonObject source) throws Exception { return BbModelAsset.read(source.toString().getBytes(StandardCharsets.UTF_8)).model(); }
    private static BbModel.Pose sample(BbModel model, double ticks, Molang.Context context) { return model.evaluateClip("authored", ticks, "HOLD", context).pose(); }
}
