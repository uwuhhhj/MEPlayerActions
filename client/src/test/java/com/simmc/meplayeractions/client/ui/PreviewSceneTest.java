package com.simmc.meplayeractions.client.ui;

import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreviewSceneTest {
    @Test void asynchronouslyLoadedModelStartsAtZeroAndMatchesItsAuthoredGuiAndIdle() throws Exception {
        var imported = YsmFolderModel.bundledDefaultWithPreview(false);
        BbModel model = BbModel.parse(imported.raw());
        assertEquals("gui", imported.previewAnimation());
        assertFalse(model.animations().contains("enter"), "The source does not provide an entrance clip");
        PreviewScene scene = new PreviewScene(model, imported.previewAnimation());
        var first = scene.sample(1374.5, Map.of());
        assertEquals(0, first.age()); assertEquals(0, first.entryProgress()); assertEquals(1, first.startCount());
        assertEquals(List.of("idle", "gui"), first.animations());
        AnimationPlayer authored = new AnimationPlayer(model);
        List<BbModel.Layer> layers = List.of(new BbModel.Layer("posture", "idle", 0, 1, "LOOP", 0, 0),
                new BbModel.Layer("gui", "gui", 0, 1, "LOOP", 0, 0));
        assertEquals(authored.sample(0, layers), first.vertices());
        assertEquals(authored.sample(8, layers), scene.sample(1382.5, Map.of()).vertices(),
                "Preview scripts/springs must advance the same elapsed age as authored playback");
    }

    @Test void largePreviewAndCardReuseOneClockWithoutRepeatingTheEntranceOrBlink() throws Exception {
        BbModel model = BbModel.parse(YsmFolderModel.bundledDefault());
        PreviewScene scene = new PreviewScene(model, "gui");
        var first = scene.sample(50, Map.of());
        assertSame(first, scene.sample(50, Map.of()));
        var mature = scene.sample(63, Map.of());
        assertEquals(13, mature.age()); assertEquals(1, mature.entryProgress());
        assertEquals(1, mature.startCount()); assertSame(mature, scene.sample(63, Map.of()));
        assertSame(mature, scene.sample(62.8, Map.of()), "Fractional GUI clock jitter cannot rewind springs/events");
        assertEquals(14, scene.sample(64, Map.of()).age());
        scene.restart();
        var selected = scene.sample(64, Map.of());
        assertEquals(0, selected.age()); assertEquals(0, selected.entryProgress()); assertEquals(2, selected.startCount());
        assertEquals(first.vertices(), selected.vertices(), "New selection resets the GUI instance alone");
    }

    @Test void originalWhiteEyeLayersOpenCloseAndReopenOnTheSourceBlinkClock() throws Exception {
        PreviewScene scene = new PreviewScene(BbModel.parse(YsmFolderModel.bundledDefault()), "gui");
        assertEquals(2, whiteEyeFaces(scene.sample(0, Map.of()).vertices()));
        // Source pre_parallel1 has an exact zero Y-scale key at 0.0833 seconds.
        assertEquals(0, whiteEyeFaces(scene.sample(.0833 * 20, Map.of()).vertices()));
        assertEquals(2, whiteEyeFaces(scene.sample(8, Map.of()).vertices()));
        assertEquals(1, scene.sample(80, Map.of()).startCount());
    }

    @Test void guiParameterEditReusesTheClockAndAnObsoletePreviewNameFallsBackToIdle() throws Exception {
        BbModel model = BbModel.parse(YsmFolderModel.bundledDefault());
        PreviewScene scene = new PreviewScene(model, "gui");
        var withBow = scene.sample(100, Map.of("variable.roaming.red_bow_headdress", 1d));
        var withoutBow = scene.sample(100, Map.of("variable.roaming.red_bow_headdress", 0d));
        assertEquals(0, withoutBow.age()); assertEquals(1, withoutBow.startCount());
        assertTrue(withoutBow.vertices().size() < withBow.vertices().size());
        assertEquals(List.of("idle"), new PreviewScene(model, "obsolete_preview").sample(3, Map.of()).animations());
        assertEquals(List.of("idle"), new PreviewScene(model, "idle").sample(3, Map.of()).animations());
        assertThrows(IllegalArgumentException.class, () -> scene.sample(Double.NaN, Map.of()));
    }

    @Test void authoredPreviewConditionTransformsRealVerticesWithoutChangingTheWorldBranch() throws Exception {
        // A minimal YSM-family model deliberately gives the GUI and the real entity different geometry.
        var raw = JsonParser.parseString("""
                {"meta":{"format_version":"5.0"},"ysm_controller_family":"player","textures":[],
                 "elements":[{"uuid":"cube","from":[0,0,0],"to":[16,16,16],
                    "faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
                 "outliner":[{"uuid":"body","name":"body","origin":[0,0,0],"children":["cube"]}],
                 "animations":[{"name":"gui","length":1,"loop":"LOOP","animators":{"body":{
                    "type":"bone","keyframes":[
                      {"channel":"position","time":0,"data_points":[{
                        "x":"ysm.rendering_in_inventory && !ysm.rendering_in_paperdoll && ysm.person_view == 2 && !ysm.is_first_person && !q.is_first_person ? 16 : -16","y":0,"z":0}]},
                      {"channel":"scale","time":0,"data_points":[{
                        "x":"ysm.rendering_in_inventory && !ysm.rendering_in_paperdoll && ysm.person_view == 2 && !ysm.is_first_person && !q.is_first_person ? 2 : 0.5","y":1,"z":1}]}
                    ]}}}]}
                """).getAsJsonObject();
        try (var texture = YsmFolderModel.class.getResourceAsStream(
                "/assets/meplayeractions/builtin/openysm_default/textures/default.png")) {
            assertNotNull(texture);
            var entry = new com.google.gson.JsonObject();
            entry.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(texture.readAllBytes()));
            raw.getAsJsonArray("textures").add(entry);
        }
        BbModel model = BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8));
        PreviewScene preview = new PreviewScene(model, "gui");
        var gui = preview.sample(300, Map.of()).vertices();
        assertEquals(4, gui.size());
        assertEquals(1, gui.stream().mapToDouble(BbModel.Vertex::x).min().orElseThrow(), 1e-5);
        assertEquals(3, gui.stream().mapToDouble(BbModel.Vertex::x).max().orElseThrow(), 1e-5,
                "The author's inventory/front-camera condition moves one block and doubles the actual face width");
        AnimationPlayer entity = new AnimationPlayer(model);
        var world = entity.sample(0, List.of(new BbModel.Layer("gui", "gui", 0, 1, "LOOP", 0, 0)), 0, 0,
                Map.of("ysm.rendering_in_inventory", 0d, "ysm.rendering_in_paperdoll", 0d,
                        "ysm.person_view", 0d, "ysm.is_first_person", 1d, "query.is_first_person", 1d));
        assertEquals(4, world.size());
        assertEquals(-1, world.stream().mapToDouble(BbModel.Vertex::x).min().orElseThrow(), 1e-5);
        assertEquals(-.5, world.stream().mapToDouble(BbModel.Vertex::x).max().orElseThrow(), 1e-5);
        assertEquals(gui, preview.sample(301, Map.of()).vertices(), "The GUI instance retains its own author environment");
    }

    private static int whiteEyeFaces(List<BbModel.Vertex> vertices) {
        int count = 0;
        for (int i = 0; i < vertices.size(); i += 4) {
            if (vertices.subList(i, i + 4).stream().allMatch(vertex -> vertex.u() >= 23f / 128
                    && vertex.u() <= 25f / 128 && vertex.v() >= 42f / 128 && vertex.v() <= 44f / 128)) count++;
        }
        return count;
    }
}
