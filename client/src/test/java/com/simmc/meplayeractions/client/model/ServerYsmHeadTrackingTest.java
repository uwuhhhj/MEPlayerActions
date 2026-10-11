package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Server-pushed and cached exports must use author angles without opting into private YSM rendering. */
class ServerYsmHeadTrackingTest {
    @TempDir Path cache;
    private static final List<BbModel.Layer> IDLE = List.of(new BbModel.Layer("base", "idle", 0, 1, "LOOP", 0, 0));

    @ParameterizedTest
    @CsvSource({
            "ysm_01_jk, 0, -60, 0, 60", "ysm_02_jk, 0, -60, 0, 60",
            "ysm_01_jk, 0, 60, 0, -60", "ysm_02_jk, 0, 60, 0, -60",
            "ysm_01_jk, 40, 0, -40, 0", "ysm_02_jk, -40, 0, 40, 0",
            "ysm_01_jk, 120, 0, -85, 0", "ysm_02_jk, -120, 0, 85, 0",
            "ysm_01_jk, 370, 0, -10, 0", "ysm_02_jk, -370, 0, 10, 0"
    })
    void serverExportsUseSourceQueriesAndKeepAutomaticHeadDirection(String id, float yaw, float pitch,
                                                                  double queryYaw, double queryPitch) throws Exception {
        byte[] source = source(id), unchanged = source.clone();
        BbModel model = BbModel.parse(source);
        assertTrue(model.usesYsmHeadQueries());
        AnimationPlayer viewed = new AnimationPlayer(model), neutral = new AnimationPlayer(model);
        double[] captured = new double[4];
        viewed.configureFrame(context -> {
            captured[0] = context.get("ysm.head_yaw"); captured[1] = context.get("ysm.head_pitch");
            captured[2] = context.get("query.head_x_rotation"); captured[3] = context.get("query.head_y_rotation");
        });
        viewed.sample(0, IDLE, yaw, pitch); neutral.sample(0, IDLE, 0, 0);

        assertArrayEquals(new double[]{queryYaw, queryPitch, queryYaw, queryPitch}, captured, 1e-6);
        Matrix4f expected = new Matrix4f(neutral.boneTransform("Head").orElseThrow())
                .rotateY(radians(queryYaw)).rotateX(radians(queryPitch));
        assertDirection(expected, viewed.boneTransform("Head").orElseThrow());
        assertFalse(viewed.nativeYsm(), "Server exports retain their server layers and original render scale");
        assertArrayEquals(unchanged, source, "Parsing a pushed asset does not rewrite its hash or cached source");
    }

    @ParameterizedTest
    @CsvSource({
            "ysm_01_jk, -60", "ysm_02_jk, -60",
            "ysm_01_jk, -30", "ysm_02_jk, -30",
            "ysm_01_jk, 30", "ysm_02_jk, 30",
            "ysm_01_jk, 60", "ysm_02_jk, 60"
    })
    void originalHairSpringReceivesSourcePitchAndCounteractsTheHead(String id, float pitch) throws Exception {
        BbModel model = BbModel.parse(source(id));
        AnimationPlayer viewed = new AnimationPlayer(model), neutral = new AnimationPlayer(model);
        for (int tick = 0; tick <= 120; tick++) {
            viewed.sample(tick, IDLE, 0, pitch); neutral.sample(tick, IDLE, 0, 0);
        }

        // Preserve the old author's spring response, including its K3 multiplier and -40 degree clamp.
        Map<String, Double> variables = viewed.expressionVariables();
        double expectedSpring = variables.get("variable.l5_k3") * Math.max(-pitch, -40);
        double hairSpring = variables.get("variable.l5_p0");
        assertEquals(expectedSpring, hairSpring, .01);
        assertEquals(Math.signum(-pitch), Math.signum(hairSpring));
        Matrix4f expectedHead = new Matrix4f(neutral.boneTransform("Head").orElseThrow()).rotateX(radians(-pitch));
        assertDirection(expectedHead, viewed.boneTransform("Head").orElseThrow());
        // Both the Head look and legacy hair keyframe rotate around X; opposite signs must subtract.
        Matrix4f expectedHair = new Matrix4f(neutral.boneTransform("FLongHair").orElseThrow())
                .rotateX(radians(-pitch - hairSpring));
        assertDirection(expectedHair, viewed.boneTransform("FLongHair").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ysm_01_jk", "ysm_02_jk"})
    void observingTwoOwnersDoesNotShareTheirHairSpringState(String id) throws Exception {
        BbModel sharedAsset = BbModel.parse(source(id));
        AnimationPlayer lookingUp = new AnimationPlayer(sharedAsset), lookingDown = new AnimationPlayer(sharedAsset);
        for (int tick = 0; tick <= 120; tick++) {
            lookingUp.sample(tick, IDLE, 0, -60);
            lookingDown.sample(tick, IDLE, 0, 60);
        }
        assertTrue(lookingUp.expressionVariables().get("variable.l5_p0") > 0);
        assertTrue(lookingDown.expressionVariables().get("variable.l5_p0") < 0);
        double before = lookingUp.expressionVariables().get("variable.l5_p0");
        lookingDown.reset(); lookingDown.sample(0, IDLE, 0, 0);
        assertEquals(before, lookingUp.expressionVariables().get("variable.l5_p0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ysm_01_jk", "ysm_02_jk"})
    void existingCachedRawAssetUsesTheSameMigrationWithoutRedownloading(String id) throws Exception {
        byte[] downloaded = source(id);
        Path cached = cache.resolve(id + ".bbmodel"); Files.write(cached, downloaded);
        BbModel wire = BbModel.parse(downloaded), disk = BbModel.parse(Files.readAllBytes(cached));
        AnimationPlayer first = new AnimationPlayer(wire), second = new AnimationPlayer(disk);
        for (int tick = 0; tick <= 20; tick++) {
            first.sample(tick, IDLE, 30, -45); second.sample(tick, IDLE, 30, -45);
        }
        assertTrue(disk.usesYsmHeadQueries());
        assertDirection(first.boneTransform("Head").orElseThrow(), second.boneTransform("Head").orElseThrow());
        assertDirection(first.boneTransform("FLongHair").orElseThrow(), second.boneTransform("FLongHair").orElseThrow());
        assertEquals(first.expressionVariables(), second.expressionVariables());
        assertArrayEquals(downloaded, Files.readAllBytes(cached));
        assertEquals(wire.cubeCount(), disk.cubeCount()); assertEquals(wire.animations(), disk.animations());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ysm_01_jk", "ysm_02_jk"})
    void ordinaryBbmodelKeepsRawQueriesEvenWhenItsNameAndAnimationsContainYsm(String id) throws Exception {
        JsonObject raw = JsonParser.parseString(new String(source(id), StandardCharsets.UTF_8)).getAsJsonObject();
        raw.remove("mpa_runtime");
        BbModel model = BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8));
        assertFalse(model.usesYsmHeadQueries());
        AnimationPlayer player = new AnimationPlayer(model); double[] captured = new double[4];
        player.configureFrame(context -> {
            captured[0] = context.get("ysm.head_yaw"); captured[1] = context.get("ysm.head_pitch");
            captured[2] = context.get("query.head_x_rotation"); captured[3] = context.get("query.head_y_rotation");
        });
        player.sample(0, IDLE, 370, -60);
        assertArrayEquals(new double[]{370, -60, 370, -60}, captured, 1e-6);
    }

    @Test void authorHeadExpressionsAndMatchingExpressionsOnOtherBonesArePreserved() throws Exception {
        BbModel model = expressionProbe(); AnimationPlayer player = new AnimationPlayer(model);
        player.sample(0, IDLE, 0, 10);
        // This author's Head expression already used head_pitch before the final term, so it is not MPA's injection.
        assertDirection(new Matrix4f().rotateX(radians(30)), player.boneTransform("Head").orElseThrow());
        // Even the exact injection-looking shape is author data outside the special Head bone.
        assertDirection(new Matrix4f().rotateX(radians(10)), player.boneTransform("Accessory").orElseThrow());
    }

    private static byte[] source(String id) throws Exception {
        try (var resource = ServerYsmHeadTrackingTest.class.getResourceAsStream("/" + id + ".bbmodel")) {
            assertNotNull(resource, "The test uses the actual distributed server export"); return resource.readAllBytes();
        }
    }

    private static BbModel expressionProbe() throws Exception {
        JsonObject raw = JsonParser.parseString("""
                {"meta":{"format_version":"4.10"},"mpa_runtime":{"source":"ysm_07_jk"},"textures":[],
                 "elements":[{"uuid":"cube","from":[0,0,0],"to":[1,1,1],
                    "faces":{"north":{"uv":[0,0,1,1],"texture":0}}}],
                 "outliner":[{"uuid":"head","name":"Head","origin":[0,0,0],"children":["cube"]},
                    {"uuid":"accessory","name":"Accessory","origin":[0,0,0],"children":[]}],
                 "animations":[{"name":"idle","length":1,"loop":"loop","animators":{
                    "head":{"type":"bone","name":"Head","keyframes":[{"channel":"rotation","time":0,
                        "data_points":[{"x":"(2*ysm.head_pitch)+ysm.head_pitch","y":"0","z":"0"}]}]},
                    "accessory":{"type":"bone","name":"Accessory","keyframes":[{"channel":"rotation","time":0,
                        "data_points":[{"x":"(0)+ysm.head_pitch","y":"0","z":"0"}]}]}}}]}
                """).getAsJsonObject();
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB); image.setRGB(0, 0, 0xffffffff);
        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png);
        JsonObject texture = new JsonObject();
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()));
        raw.getAsJsonArray("textures").add(texture);
        return BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static float radians(double degrees) { return (float) Math.toRadians(degrees); }

    private static void assertDirection(Matrix4f expected, Matrix4f actual) {
        for (Vector3f axis : List.of(new Vector3f(0, 1, 0), new Vector3f(0, 0, 1))) {
            Vector3f wanted = expected.transformDirection(new Vector3f(axis)).normalize();
            Vector3f observed = actual.transformDirection(new Vector3f(axis)).normalize();
            assertEquals(wanted.x, observed.x, 1e-4); assertEquals(wanted.y, observed.y, 1e-4); assertEquals(wanted.z, observed.z, 1e-4);
        }
    }
}
