package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.model.nativebbmodel.ImportedVanillaPoseController;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeBbmodelActionPresetTest {
    @Test void realNativeImporterRetainsAuthorClipAndRemapsSourcePresetsToAuthorSkeleton() throws Exception {
        JsonObject source = humanoid();
        source.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("name", "idle");
        byte[] input = source.toString().getBytes(StandardCharsets.UTF_8);
        var imported = NativeBbModel.read(input, Map.of("model.bbmodel", input), null);
        var model = BbModel.parseLocal(imported.raw());
        assertTrue(imported.profile().isImportedBbModel());
        assertEquals("HOLD", model.animationLoop("idle"), "source preset must not replace author's idle");
        assertTrue(model.animations().containsAll(List.of("walk", "run", "extra0", "swing_hand", "use_mainhand")));
        JsonObject walk = runtimeClip(imported.raw(), "walk");
        assertTrue(walk.getAsJsonObject("animators").asMap().values().stream()
                .map(JsonElement::getAsJsonObject).anyMatch(animator -> "Left Upper Arm".equals(animator.get("name").getAsString())));
        var fp = imported.profile().components().stream().filter(component -> component.kind().equals("fp_arm")).findFirst().orElseThrow();
        assertTrue(fp.model().animations().containsAll(List.of("hold_mainhand", "parallel0")));
        assertFalse(YsmFolderModel.bundledDefaultWithPreview(false).profile().isImportedBbModel(), "plain native YSM retains its source action profile");
    }

    @Test void sourceFallbackKeepsAuthoredRotationsAndHeadWhileCompletingUnusedHumanoidParts() throws Exception {
        byte[] input = humanoid().toString().getBytes(StandardCharsets.UTF_8);
        var imported = NativeBbModel.read(input, Map.of("model.bbmodel", input), null);
        var model = BbModel.parseLocal(imported.raw());
        var pose = model.emptyPose();
        int arm = model.boneIndex("Left Upper Arm"), forearm = model.boneIndex("Left ForeArm"), head = model.boneIndex("Head");
        pose.channels[arm][1] = new Vector3f(15, 5, 2);
        var nativePose = new ImportedVanillaPoseController.PoseValues();
        nativePose.headX = 1; nativePose.leftArmX = 2; nativePose.leftForeArmX = .25f;
        NativeBbmodelPoseAdapter.apply(model, pose, nativePose);
        assertEquals(new Vector3f(15, 5, 2), pose.channels[arm][1], "source processor ownership wins over vanilla fallback");
        assertEquals((float)Math.toDegrees(.25), pose.channels[forearm][1].x, 1e-5);
        assertNull(pose.channels[head][1], "fallbackOnly excludes source head provider");
        assertNull(pose.channels[forearm][0]); assertNull(pose.channels[forearm][2]);
    }

    private static JsonObject humanoid() throws Exception {
        JsonObject source = NativeBbModelTest.source();
        JsonArray groups = source.getAsJsonArray("groups");
        JsonObject root = source.getAsJsonArray("outliner").get(0).getAsJsonObject();
        for (String[] part : new String[][]{{"head", "Head"}, {"leftarm", "Left Upper Arm"}, {"forearm", "Left ForeArm"}, {"rightarm", "RightArm"}, {"leftleg", "LeftLeg"}, {"rightleg", "RightLeg"}}) {
            JsonObject group = new JsonObject(); group.addProperty("uuid", part[0]); group.addProperty("name", part[1]);
            JsonArray origin = new JsonArray(); origin.add(0); origin.add(0); origin.add(0); group.add("origin", origin); groups.add(group);
            JsonObject child = new JsonObject(); child.addProperty("uuid", part[0]); child.add("children", new JsonArray());
            root.getAsJsonArray("children").add(child);
        }
        return source;
    }
    private static JsonObject runtimeClip(byte[] runtime, String name) {
        for (var element : JsonParser.parseString(new String(runtime, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("animations"))
            if (element.getAsJsonObject().get("name").getAsString().equals(name)) return element.getAsJsonObject();
        throw new AssertionError("Missing runtime clip " + name);
    }
}
