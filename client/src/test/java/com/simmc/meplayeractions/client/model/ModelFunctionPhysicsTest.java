package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Function physics belongs to the model instance, independently of its controller/source path. */
class ModelFunctionPhysicsTest {
    private enum Mode {
        ORDINARY(false, false), LEGACY_CONTROLLERS(true, false),
        NATIVE_CONTROLLERS(true, true), NATIVE_WITHOUT_CONTROLLERS(false, true);
        final boolean controllers, nativeYsm;
        Mode(boolean controllers, boolean nativeYsm) { this.controllers = controllers; this.nativeYsm = nativeYsm; }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void firstOrderRespondsToTheRealFrameIntervalOnceInEitherControllerPath(Mode mode) throws Exception {
        AnimationPlayer player = player(mode, false);
        assertEquals(8, sample(player, 20, 8), 1e-6);
        // FirstOrder starts its simulation at zero; .125 seconds / .5 seconds reaches one quarter.
        assertEquals(2, sample(player, 22.5, 8), 1e-6);
        assertEquals(3.5, sample(player, 25, 8), 1e-6);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void secondOrderAdvancesFromThePreviousFrameInsteadOfRemainingAtZero(Mode mode) throws Exception {
        AnimationPlayer player = player(mode, true);
        assertEquals(8, sample(player, 20, 8), 1e-6);
        assertEquals(0, sample(player, 21, 8), 1e-6);
        // Source SecondOrder uses y += dt * dy before updating dy. With response 0 and f=1,
        // the second .05-second step is input * (2*pi)^2 * .05^2.
        double secondStep = 8 * Math.pow(2 * Math.PI, 2) * .05 * .05;
        assertEquals(secondStep, sample(player, 22, 8), 1e-5);
        assertTrue(sample(player, 23, 8) > secondStep);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void repeatedOrSlightlyOlderFrameDoesNotAdvanceFunctionPhysicsAgain(Mode mode) throws Exception {
        AnimationPlayer player = player(mode, false);
        sample(player, 20, 8);
        double advanced = sample(player, 21, 8);
        assertEquals(.8, advanced, 1e-6);
        for (int i = 0; i < 5; i++) assertEquals(advanced, sample(player, 21, 8), 1e-6);
        assertEquals(advanced, sample(player, 20.5, 8), 1e-6);
        assertEquals(1.52, sample(player, 22, 8), 1e-6);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void inputChangesAffectFollowingFramesAndDecayWithoutSharingInstances(Mode mode) throws Exception {
        AnimationPlayer moving = player(mode, false), separate = player(mode, false);
        sample(moving, 20, 8); sample(moving, 21, 8);
        assertEquals(1.52, sample(moving, 22, 0), 1e-6);
        assertEquals(1.368, sample(moving, 23, 0), 1e-6);
        assertEquals(0, sample(separate, 23, 0), 1e-6);
        assertEquals(0, sample(separate, 24, 0), 1e-6);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void resetAndWorldClockRewindDiscardThePreviousFunctionSimulation(Mode mode) throws Exception {
        AnimationPlayer player = player(mode, false);
        sample(player, 20, 8); sample(player, 21, 8);
        player.reset(); assertTrue(player.expressionVariables().isEmpty());
        assertEquals(8, sample(player, 5, 8), 1e-6);
        assertEquals(.8, sample(player, 6, 8), 1e-6);
        assertEquals(8, sample(player, 2, 8), 1e-6);
        assertEquals(.8, sample(player, 3, 8), 1e-6);
    }

    @Test
    void nativePhysicsRetainsTheSourcesInitialPositiveTickGuard() throws Exception {
        AnimationPlayer player = player(Mode.NATIVE_WITHOUT_CONTROLLERS, false);
        assertEquals(8, sample(player, 0, 8), 1e-6);
        assertEquals(0, sample(player, 1, 8), 1e-6);
        assertEquals(.8, sample(player, 2, 8), 1e-6);
    }

    @Test
    void convertedServerParallelScriptsRetainTheirIndependentTenMillisecondIntegration() throws Exception {
        JsonObject raw = document("v.observed=v.steps;");
        raw.add("mpa_runtime", JsonParser.parseString("{\"physics_step_seconds\":0.01,\"original_size\":true}"));
        JsonArray animations = raw.getAsJsonArray("animations");
        animations.add(effectAnimation("parallel1", "v.steps=0;"));
        animations.add(effectAnimation("parallel2", "v.steps=v.steps+1;"));
        AnimationPlayer player = new AnimationPlayer(parse(raw));
        assertEquals(0, sample(player, 20, 0), 1e-6);
        assertEquals(5, sample(player, 21, 0), 1e-6);
        assertEquals(5, sample(player, 21, 0), 1e-6);
        assertEquals(6, sample(player, 21.2, 0), 1e-6);
    }

    private static AnimationPlayer player(Mode mode, boolean secondOrder) throws Exception {
        String call = secondOrder ? "ysm.second_order('probe',q.target,1,0.5,0)"
                : "ysm.first_order('probe',q.target,0.5)";
        JsonObject raw = document("v.observed=" + call + ";v.observed");
        if (mode.controllers) {
            raw.addProperty("ysm_controller_family", "player");
            raw.add("ysm_animation_controllers", JsonParser.parseString("""
                    {"player.main":{"initial_state":"default","states":{"default":{
                        "animations":["idle"],"blend_transition":0}}}}
                    """));
        }
        if (mode.nativeYsm) raw.addProperty("ysm_format_version", 65535);
        AnimationPlayer player = new AnimationPlayer(parse(raw));
        if (mode.nativeYsm) player.enableNativeYsm();
        return player;
    }

    private static double sample(AnimationPlayer player, double tick, double target) {
        player.sample(tick, List.of(new BbModel.Layer("locomotion", "idle", 0, 1, "LOOP", 0, 0)),
                0, 0, Map.of("query.target", target));
        return player.expressionVariables().getOrDefault("variable.observed", Double.NaN);
    }

    private static JsonObject document(String expression) {
        JsonObject raw = JsonParser.parseString("""
                {"meta":{"format_version":"5.0"},"textures":[],
                 "elements":[{"uuid":"cube","from":[0,0,0],"to":[1,1,1],
                    "faces":{"north":{"uv":[0,0,1,1],"texture":0}}}],
                 "outliner":[{"uuid":"root","name":"Root","origin":[0,0,0],"children":["cube"]}],
                 "animations":[{"name":"idle","length":1,"loop":"loop","animators":{
                    "root":{"name":"Root","type":"bone","keyframes":[
                        {"channel":"rotation","time":0,"interpolation":"linear","data_points":[{"x":0,"y":0,"z":0}]}]}}}]}
                """).getAsJsonObject();
        raw.getAsJsonArray("animations").get(0).getAsJsonObject().getAsJsonObject("animators")
                .getAsJsonObject("root").getAsJsonArray("keyframes").get(0).getAsJsonObject()
                .getAsJsonArray("data_points").get(0).getAsJsonObject().addProperty("x", expression);
        return raw;
    }

    private static JsonObject effectAnimation(String name, String script) {
        JsonObject animation = JsonParser.parseString("""
                {"length":1,"loop":"loop","animators":{"effect":{"type":"effect","keyframes":[
                    {"channel":"timeline","time":0,"data_points":[{}]}]}}}
                """).getAsJsonObject();
        animation.addProperty("name", name);
        animation.getAsJsonObject("animators").getAsJsonObject("effect").getAsJsonArray("keyframes")
                .get(0).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().addProperty("script", script);
        return animation;
    }

    private static BbModel parse(JsonObject raw) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffffffff);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        JsonObject texture = new JsonObject();
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        raw.getAsJsonArray("textures").add(texture);
        return BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8));
    }
}
