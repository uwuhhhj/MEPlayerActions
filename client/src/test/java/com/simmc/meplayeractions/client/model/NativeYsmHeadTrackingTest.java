package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Angle conventions from OpenYSM 0306e1f AnimatableEntity and QueryBinding. */
class NativeYsmHeadTrackingTest {
    @ParameterizedTest
    @CsvSource({"-60, 0", "-30, 0", "30, 0", "60, -20"})
    void bundledHairCounteractsTheHeadUsingTheAuthorsExistingSpring(float rawPitch, float hairPitch) throws Exception {
        BbModel model = BbModel.parse(YsmFolderModel.bundledDefault());
        AnimationPlayer player = new AnimationPlayer(model);
        player.enableNativeYsm();

        // Exercise the real pre_parallel0 FLongHair track, including its second-order spring.
        // The author's -40 bound intentionally permits a residual angle when looking far down.
        for (int tick = 0; tick <= 120; tick++) player.sample(tick, List.of(), 0, rawPitch);

        Matrix4f basisHead = model.basisBoneTransforms().get("Head");
        Matrix4f basisHair = model.basisBoneTransforms().get("FLongHair");
        assertDirection(new Matrix4f(basisHead).rotateX(radians(-rawPitch)), player.boneTransform("Head").orElseThrow());
        assertDirection(new Matrix4f(basisHair).rotateX(radians(hairPitch)), player.boneTransform("FLongHair").orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({
            "30, -60, -30, 60",
            "-30, 45, 30, -45",
            "120, 0, -85, 0",
            "-120, 0, 85, 0",
            "370, -15, -10, 15",
            "-370, 15, 10, -15"
    })
    void nativeAuthorQueriesUseUpstreamSignsWrappingAndYawLimit(float rawYaw, float rawPitch,
                                                               double expectedYaw, double expectedPitch) throws Exception {
        AnimationPlayer player = new AnimationPlayer(queryProbe(true));
        player.enableNativeYsm();
        player.sample(0, List.of(), rawYaw, rawPitch);

        assertEquals(expectedYaw, player.expressionVariables().get("variable.ysm_yaw"), 1e-6);
        assertEquals(expectedPitch, player.expressionVariables().get("variable.ysm_pitch"), 1e-6);
        // Upstream QueryBinding preserves its original axis names: X is yaw, Y is pitch.
        assertEquals(expectedYaw, player.expressionVariables().get("variable.query_x"), 1e-6);
        assertEquals(expectedPitch, player.expressionVariables().get("variable.query_y"), 1e-6);

        // Correcting author queries must not negate the already-correct automatic Head a second time.
        assertDirection(new Matrix4f().rotateY(radians(expectedYaw)).rotateX(radians(expectedPitch)),
                player.boneTransform("Head").orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"30, -60, -30", "-30, 45, 30", "370, -15, -10", "120, 0, -85"})
    void ordinaryBbmodelKeepsRawAuthorQueriesAndItsExistingAutomaticHead(float rawYaw, float rawPitch,
                                                                       double automaticYaw) throws Exception {
        AnimationPlayer player = new AnimationPlayer(queryProbe(false));
        player.sample(0, List.of(), rawYaw, rawPitch);

        assertEquals((double) rawYaw, player.expressionVariables().get("variable.ysm_yaw"), 1e-6);
        assertEquals((double) rawPitch, player.expressionVariables().get("variable.ysm_pitch"), 1e-6);
        assertEquals((double) rawYaw, player.expressionVariables().get("variable.query_x"), 1e-6);
        assertEquals((double) rawPitch, player.expressionVariables().get("variable.query_y"), 1e-6);
        assertDirection(new Matrix4f().rotateY(radians(automaticYaw)).rotateX(radians(-rawPitch)),
                player.boneTransform("Head").orElseThrow());
    }

    private static BbModel queryProbe(boolean nativeYsm) throws Exception {
        JsonObject raw = JsonParser.parseString("""
                {"meta":{"format_version":"5.0"},"textures":[],"animations":[],
                 "elements":[{"uuid":"cube","from":[0,0,0],"to":[1,1,1],
                    "faces":{"north":{"uv":[0,0,1,1],"texture":0}}}],
                 "outliner":[{"uuid":"head","name":"Head","origin":[0,0,0],"children":["cube"]}],
                 "ysm_events":{"player_update":
                    "v.ysm_yaw=ysm.head_yaw;v.ysm_pitch=ysm.head_pitch;v.query_x=q.head_x_rotation;v.query_y=q.head_y_rotation;"}}
                """).getAsJsonObject();
        if (nativeYsm) {
            raw.addProperty("ysm_format_version", 65535);
            raw.addProperty("ysm_controller_family", "player");
        }
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffffffff);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        JsonObject texture = new JsonObject();
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        raw.getAsJsonArray("textures").add(texture);
        return BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static float radians(double degrees) { return (float) Math.toRadians(degrees); }

    private static void assertDirection(Matrix4f expected, Matrix4f actual) {
        // Translation and authored attachment pivots are irrelevant to whether the hair turns into the neck.
        for (Vector3f axis : List.of(new Vector3f(0, 1, 0), new Vector3f(0, 0, 1))) {
            Vector3f direction = expected.transformDirection(new Vector3f(axis)).normalize();
            Vector3f observed = actual.transformDirection(new Vector3f(axis)).normalize();
            assertEquals(direction.x, observed.x, 1e-4);
            assertEquals(direction.y, observed.y, 1e-4);
            assertEquals(direction.z, observed.z, 1e-4);
        }
    }
}
