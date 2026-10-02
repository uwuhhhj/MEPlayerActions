package com.simmc.meplayeractions.action;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExampleCrawlModelTest {
    private JsonObject model(String id) throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("examples/blueprints/npc/" + id + ".bbmodel"))).getAsJsonObject();
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
    @Test void bothAllowedModelsContainProneCrawlAndNpcKeepsStandingClimb() throws Exception {
        for (String id : List.of("ysm_01_jk_player", "ysm_01_jk_npc")) {
            var m = model(id);
            assertEquals(90, rotationX(animation(m, "crawl_idle")));
            assertEquals(90, rotationX(animation(m, "crawl_walk")));
            assertNotEquals(animation(m, "sleep"), animation(m, "crawl_idle"));
        }
        assertEquals(0, rotationX(animation(model("ysm_01_jk_npc"), "climb")));
        assertEquals(0, rotationX(animation(model("ysm_01_jk_npc"), "climb_idle")));
    }
    @Test void jumpCycleEndsInNeutralPoseAndDoesNotDoubleTheRootHeight() throws Exception {
        var a = animation(model("ysm_01_jk_npc"), "player_jump");
        assertEquals("once", a.get("loop").getAsString());
        var frames = track(a, "LeftLeg").getAsJsonArray("keyframes");
        assertEquals(0, frames.get(0).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().get("x").getAsDouble());
        assertEquals(0, frames.get(frames.size()-1).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().get("x").getAsDouble());
        for (var f : track(a, "Root").getAsJsonArray("keyframes")) if (f.getAsJsonObject().get("channel").getAsString().equals("position"))
            assertEquals(0, f.getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().get("y").getAsDouble());
    }
    @Test void geometryAndExistingNpcTracksArePreserved() throws Exception {
        var npc = model("ysm_01_jk_npc"); var player = model("ysm_01_jk_player");
        for (String key : List.of("elements", "outliner", "textures", "resolution")) assertEquals(npc.get(key), player.get(key));
        var names = new HashSet<String>();
        for (var a : npc.getAsJsonArray("animations")) assertTrue(names.add(a.getAsJsonObject().get("name").getAsString()));
        assertEquals(30, names.size());
        assertEquals(animation(npc, "jump"), animation(player, "jump"));
    }
}
