package com.simmc.meplayeractions.action;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExampleCrawlModelTest {
    private JsonObject model(String id) throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("examples/models/" + id + ".bbmodel"))).getAsJsonObject();
    }
    private JsonObject animation(JsonObject model, String name) {
        for (var a : model.getAsJsonArray("animations")) if (a.getAsJsonObject().get("name").getAsString().equals(name)) return a.getAsJsonObject();
        fail("Missing animation: " + name); return null;
    }
    private JsonObject track(JsonObject anim, String bone) {
        for (var v : anim.getAsJsonObject("animators").entrySet()) if (v.getValue().getAsJsonObject().get("name").getAsString().equals(bone)) return v.getValue().getAsJsonObject();
        fail("Missing bone: " + bone); return null;
    }
    private double rotationX(JsonObject anim) {
        for (var f : track(anim, "Root").getAsJsonArray("keyframes")) if (f.getAsJsonObject().get("channel").getAsString().equals("rotation"))
            return f.getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().get("x").getAsDouble();
        fail("Missing root rotation"); return 0;
    }
    @Test void bothModelsDistinguishLandCrawlFromUprightLadder() throws Exception {
        for (String id : List.of("ysm_01_jk", "ysm_02_jk")) {
            var m = model(id);
            assertEquals(90, rotationX(animation(m, "crawl_idle")));
            assertEquals(90, rotationX(animation(m, "crawl_walk")));
            assertNotEquals(animation(m, "sleep"), animation(m, "crawl_idle"));
        }
        assertEquals(0, rotationX(animation(model("ysm_02_jk"), "ladder_up")));
        assertEquals(0, rotationX(animation(model("ysm_02_jk"), "ladder_stillness")));
    }
    @Test void jumpCycleEndsInNeutralPoseAndDoesNotDoubleTheRootHeight() throws Exception {
        var a = animation(model("ysm_02_jk"), "player_jump");
        assertEquals("once", a.get("loop").getAsString());
        var frames = track(a, "LeftLeg").getAsJsonArray("keyframes");
        assertEquals(0, frames.get(0).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().get("x").getAsDouble());
        assertEquals(0, frames.get(frames.size()-1).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().get("x").getAsDouble());
        for (var f : track(a, "Root").getAsJsonArray("keyframes")) if (f.getAsJsonObject().get("channel").getAsString().equals("position"))
            assertEquals(0, f.getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().get("y").getAsDouble());
    }
    private Map<String,JsonElement> bonePivots(JsonArray nodes) {
        var result=new TreeMap<String,JsonElement>();
        for(var node:nodes)if(node.isJsonObject()) {
            var bone=node.getAsJsonObject();result.put(bone.get("name").getAsString(),bone.get("origin"));
            result.putAll(bonePivots(bone.getAsJsonArray("children")));
        }
        return result;
    }
    @Test void originalAppearanceGeometryAndCommonAnimationsArePreserved() throws Exception {
        var first=model("ysm_01_jk");var second=model("ysm_02_jk");
        assertEquals(232,first.getAsJsonArray("elements").size());
        assertEquals(335,second.getAsJsonArray("elements").size());
        var firstBones=bonePivots(first.getAsJsonArray("outliner"));var secondBones=bonePivots(second.getAsJsonArray("outliner"));
        assertEquals(firstBones.keySet(),secondBones.keySet());
        for(String name:List.of("Root","Head","LeftLeg","RightLeg"))assertEquals(firstBones.get(name),secondBones.get(name));
        assertNotEquals(first.get("textures"),second.get("textures"));
        for(var m:List.of(first,second)) {
            var names=new HashSet<String>();
            for(var action:m.getAsJsonArray("animations"))assertTrue(names.add(action.getAsJsonObject().get("name").getAsString()));
            assertEquals(60,names.size());
            for(String name:List.of("boat","elytra_fly","ride","extra0","extra1","extra7"))assertTrue(animation(m,name).getAsJsonObject("animators").size()>8,name+" must retain body tracks");
            assertTrue(names.containsAll(Set.of("parallel1","parallel2","pre_parallel1","extra0","boat","ride","elytra_fly")));
            assertTrue(m.getAsJsonObject("mpa_runtime").get("original_size").getAsBoolean());
        }
        assertEquals(animation(first,"jump"),animation(second,"jump"));
        assertEquals(animation(first,"climbing"),animation(second,"climbing"));
    }
}
