package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YsmAnimationMetadataBoundsTest {
    @Test void nativeTimelineRunsTwoHundredFiftySixIndependentProgramsInOrderAndRejectsTheNext() throws Exception {
        JsonObject raw = fixture(65535);
        JsonObject clip = clip(raw, "parallel0", .01, "LOOP");
        var scripts = new ArrayList<String>(); scripts.add("v.n+=1;return 99;");
        for (int i = 1; i < 256; i++) scripts.add("v.n+=1;v.last=" + i + ";");
        effect(clip, "timeline", 0, scripts);
        AnimationPlayer player = new AnimationPlayer(parse(raw)); player.sample(0, List.of());
        assertEquals(256d, player.expressionVariables().get("variable.n"));
        assertEquals(255d, player.expressionVariables().get("variable.last"));
        scripts.add("v.n+=1;"); effect(clip, "timeline", 0, scripts);
        assertThrows(IllegalArgumentException.class, () -> parse(raw));
    }

    @Test void nativeExplicitDurationIsFiniteAndIndependentOfLaterAuthoredKeysAndEvents() throws Exception {
        JsonObject raw = fixture(65535); JsonObject longHold = clip(raw, "hold_mainhand:spear", 10000, "HOLD");
        position(longHold, 0, 0, 1.5, 16);
        JsonObject shortHold = clip(raw, "later_key", 6.8333, "HOLD"); position(shortHold, 0, 0, 8, 128);
        JsonObject event = clip(raw, "parallel4", .01, "LOOP"); effect(event, "timeline", .0101, List.of("v.event=1;"));
        BbModel model = parse(raw);
        assertEquals(200000, model.animationLengthTicks("hold_mainhand:spear"));
        assertTrue(Double.isFinite(model.animationLengthTicks("hold_mainhand:spear")));
        assertEquals(6.8333 * 20, model.animationLengthTicks("later_key"));
        assertEquals(.2, model.animationLengthTicks("parallel4"));
        AnimationPlayer player = new AnimationPlayer(model);
        // main is a native controller; post_main exists only when authored definitions/events register it.
        player.sample(160, List.of(new BbModel.Layer("player.main", "later_key", 0, 1, "HOLD", 0, 0)));
        assertTrue(player.controllerStates().containsKey("player.main"));
        assertEquals(6.8333, player.boneTransform("bone").orElseThrow().m30(), 1e-4);
        longHold.addProperty("length", 10000.001);
        assertThrows(IllegalArgumentException.class, () -> parse(raw));
    }

    @Test void timelineAggregateUtf8BudgetAndExistingRawByteBudgetRemainBounded() throws Exception {
        JsonObject raw = fixture(65535); JsonObject clip = clip(raw, "parallel0", 0, "LOOP");
        effect(clip, "timeline", 0, Collections.nCopies(64, "/*" + "字".repeat(100) + "*/v.n+=1;"));
        assertDoesNotThrow(() -> parse(raw));
        effect(clip, "timeline", 0, Collections.nCopies(64, "/*" + "字".repeat(200) + "*/v.n+=1;"));
        assertThrows(IllegalArgumentException.class, () -> parse(raw));
        byte[] oversized = new byte[8 * 1024 * 1024 + 1]; Arrays.fill(oversized, (byte) ' ');
        assertThrows(IllegalArgumentException.class, () -> BbModel.parse(oversized));
    }

    @Test void ordinaryBbModelAndSoundParticleLimitsDoNotInheritNativeTimelineAllowance() throws Exception {
        JsonObject ordinary = fixture(0); JsonObject clip = clip(ordinary, "parallel0", 0, "LOOP");
        effect(clip, "timeline", 0, Collections.nCopies(33, "v.n+=1;"));
        assertThrows(IllegalArgumentException.class, () -> parse(ordinary));
        effect(clip, "timeline", 0, List.of("v.n+=1;")); clip.addProperty("length", 3600.001);
        assertThrows(IllegalArgumentException.class, () -> parse(ordinary));
        clip.addProperty("length", 1); position(clip, 0, 0, 2, 16);
        assertThrows(IllegalArgumentException.class, () -> parse(ordinary));
        for (String channel : List.of("sound", "particle")) {
            JsonObject nativeRaw = fixture(65535); JsonObject nativeClip = clip(nativeRaw, "parallel0", 0, "LOOP");
            effect(nativeClip, channel, 0, Collections.nCopies(32, "minecraft:test")); assertDoesNotThrow(() -> parse(nativeRaw));
            effect(nativeClip, channel, 0, Collections.nCopies(33, "minecraft:test")); assertThrows(IllegalArgumentException.class, () -> parse(nativeRaw));
        }
    }

    @Test void flaggedNativeFoxcarSignedCubeKeepsAllThreeFacesTheirCornerOrderUvsAndNormals() throws Exception {
        JsonObject raw = foxcarSignedFixture(); List<BbModel.Vertex> vertices = parse(raw).basisVertices();
        assertEquals(12, vertices.size());
        // Original 01_taisho_maid/models/foxcar.json eyes2.cubes[0]: size [4.788, 2.388, -.252].
        double x0 = 7.60139 / 16, x1 = 12.38939 / 16, y0 = 23.806 / 16, y1 = 26.194 / 16;
        double z0 = 16.8984 / 16, z1 = 16.6464 / 16;
        double[][] corners = {
                {x1,y1,z0}, {x1,y0,z0}, {x0,y0,z0}, {x0,y1,z0},
                {x1,y1,z1}, {x1,y0,z1}, {x1,y0,z0}, {x1,y1,z0},
                {x0,y1,z0}, {x0,y0,z0}, {x0,y0,z1}, {x0,y1,z1}
        };
        double[][] uv = {
                {16,425}, {16,429}, {24,429}, {24,425},
                {16,425}, {16,429}, {17,429}, {17,425},
                {23,425}, {23,429}, {24,429}, {24,425}
        };
        for (int i = 0; i < vertices.size(); i++) {
            BbModel.Vertex vertex = vertices.get(i);
            assertEquals(corners[i][0], vertex.x(), 1e-6); assertEquals(corners[i][1], vertex.y(), 1e-6); assertEquals(corners[i][2], vertex.z(), 1e-6);
            assertEquals(uv[i][0] / 512, vertex.u(), 1e-6); assertEquals(uv[i][1] / 512, vertex.v(), 1e-6);
            assertEquals(i < 4 ? 0 : i < 8 ? 1 : -1, vertex.nx(), 1e-6); assertEquals(0, vertex.ny(), 1e-6);
            assertEquals(i < 4 ? -1 : 0, vertex.nz(), 1e-6);
        }
        assertTrue(vertices.get(4).z() < vertices.get(6).z(), "Signed extent was sorted or made absolute");
    }

    @Test void signedCubeAllowanceRequiresNativeFormatAndExplicitFlagAndKeepsFiniteBounds() throws Exception {
        JsonObject raw = foxcarSignedFixture(); JsonObject cube = raw.getAsJsonArray("elements").get(0).getAsJsonObject();
        cube.remove("ysm_signed_cube"); assertThrows(IllegalArgumentException.class, () -> parse(raw));
        cube.addProperty("ysm_signed_cube", true); raw.addProperty("ysm_format_version", 0);
        assertThrows(IllegalArgumentException.class, () -> parse(raw));
        raw.addProperty("ysm_format_version", 65535); cube.addProperty("inflate", 1024.001);
        assertThrows(IllegalArgumentException.class, () -> parse(raw));
        cube.addProperty("inflate", 0); cube.add("from", JsonParser.parseString("[4096.001,23.806,16.8984]"));
        assertThrows(IllegalArgumentException.class, () -> parse(raw));
        cube.add("from", JsonParser.parseString("[7.60139,23.806,16.8984]")); cube.addProperty("ysm_signed_cube", "true");
        assertThrows(IllegalArgumentException.class, () -> parse(raw));
    }

    private static JsonObject foxcarSignedFixture() throws Exception {
        JsonObject raw = fixture(65535); raw.add("resolution", JsonParser.parseString("{\"width\":512,\"height\":512}"));
        JsonObject cube = raw.getAsJsonArray("elements").get(0).getAsJsonObject(); cube.addProperty("ysm_signed_cube", true);
        cube.add("from", JsonParser.parseString("[7.60139,23.806,16.8984]"));
        cube.add("to", JsonParser.parseString("[12.38939,26.194,16.6464]"));
        cube.add("faces", JsonParser.parseString("""
                {"north":{"uv":[16,425,24,429],"texture":0},"east":{"uv":[16,425,17,429],"texture":0},
                "west":{"uv":[23,425,24,429],"texture":0}}
                """)); return raw;
    }

    private static BbModel parse(JsonObject raw) { return BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8)); }
    private static JsonObject clip(JsonObject raw, String name, double length, String loop) {
        JsonObject clip = new JsonObject(); clip.addProperty("name", name); clip.addProperty("length", length); clip.addProperty("loop", loop);
        clip.add("animators", new JsonObject()); raw.getAsJsonArray("animations").add(clip); return clip;
    }
    private static void effect(JsonObject clip, String channel, double time, List<String> values) {
        JsonObject animator = new JsonObject(); animator.addProperty("type", "effect"); JsonArray frames = new JsonArray(); animator.add("keyframes", frames);
        JsonObject frame = new JsonObject(); frame.addProperty("channel", channel); frame.addProperty("time", time); JsonArray points = new JsonArray(); frame.add("data_points", points);
        for (String value : values) { JsonObject point = new JsonObject(); point.addProperty(channel.equals("timeline") ? "script" : "effect", value); points.add(point); }
        frames.add(frame); clip.getAsJsonObject("animators").add("effects", animator);
    }
    private static void position(JsonObject clip, double firstTime, double firstX, double lastTime, double lastX) {
        JsonObject animator = new JsonObject(); JsonArray frames = new JsonArray(); animator.add("keyframes", frames);
        for (double[] value : List.of(new double[] { firstTime, firstX }, new double[] { lastTime, lastX })) {
            JsonObject point = new JsonObject(); point.addProperty("x", value[1]); point.addProperty("y", 0); point.addProperty("z", 0);
            JsonArray points = new JsonArray(); points.add(point); JsonObject frame = new JsonObject(); frame.addProperty("channel", "position");
            frame.addProperty("time", value[0]); frame.add("data_points", points); frames.add(frame);
        }
        clip.getAsJsonObject("animators").add("bone", animator);
    }
    private static JsonObject fixture(int version) throws Exception {
        JsonObject raw = JsonParser.parseString("""
                {"meta":{"format_version":"5.0"},"ysm_controller_family":"player","textures":[],"animations":[],
                "elements":[{"uuid":"cube","from":[0,0,0],"to":[16,16,16],"faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
                "outliner":[{"uuid":"bone","name":"bone","origin":[0,0,0],"children":["cube"]}]}
                """).getAsJsonObject();
        raw.addProperty("ysm_format_version", version);
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB); image.setRGB(0, 0, 0xffffffff);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        JsonObject texture = new JsonObject(); texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        raw.getAsJsonArray("textures").add(texture); return raw;
    }
}
