package com.simmc.meplayeractions.client.ui;

import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.EntityAnimationController;
import com.simmc.meplayeractions.client.LocalMotionPolicy;
import com.simmc.meplayeractions.client.VanillaYsmAnimations;
import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.BuiltinYsmModels;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PreviewSceneTest {
    @Test void asynchronouslyLoadedCardStartsAtZeroWithOnlyItsAuthoredCap() throws Exception {
        var imported = YsmFolderModel.bundledDefaultWithPreview(false);
        BbModel model = BbModel.parse(imported.raw());
        assertEquals("gui", imported.previewAnimation());
        assertFalse(model.animations().contains("enter"), "The source does not provide an entrance clip");
        PreviewScene scene = new PreviewScene(model, imported.previewAnimation());
        var first = scene.sample(1374.5, Map.of());
        assertEquals(0, first.age()); assertEquals(0, first.entryProgress()); assertEquals(1, first.startCount());
        assertEquals(List.of("gui"), first.animations());
        assertEquals(List.of("player.cap"), first.slots());
        AnimationPlayer authored = new AnimationPlayer(model);
        authored.configureFrame(environment -> {
            environment.query("ysm.rendering_in_inventory", 1d);
            environment.query("ysm.rendering_in_paperdoll", 0d);
            environment.query("ysm.is_first_person", 0d);
            environment.query("query.is_first_person", 0d);
            environment.query("ysm.person_view", 2d);
        });
        List<BbModel.Layer> layers = List.of(new BbModel.Layer("player.cap", "gui", 0, 1,
                model.animationLoop("gui"), 0, 0));
        assertEquals(authored.sample(0, layers), first.vertices());
        assertEquals(authored.sample(8, layers), scene.sample(1382.5, Map.of()).vertices(),
                "Preview scripts/springs must advance the same elapsed age as authored playback");
    }

    @Test void repeatedCardDrawsReuseTheirClockWithoutRepeatingTheEntranceOrBlink() throws Exception {
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

    @Test void guiParameterEditReusesTheClockAndAnObsoleteCardPreviewStaysStopped() throws Exception {
        BbModel model = BbModel.parse(YsmFolderModel.bundledDefault());
        PreviewScene scene = new PreviewScene(model, "gui");
        var withBow = scene.sample(100, Map.of("variable.roaming.red_bow_headdress", 1d));
        var withoutBow = scene.sample(100, Map.of("variable.roaming.red_bow_headdress", 0d));
        assertEquals(0, withoutBow.age()); assertEquals(1, withoutBow.startCount());
        assertTrue(withoutBow.vertices().size() < withBow.vertices().size());
        assertEquals(List.of(), new PreviewScene(model, "obsolete_preview").sample(3, Map.of()).animations());
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

    @Test void ownerUsesActualVanillaHandsAndMovementWithoutTheCardGui() throws Exception {
        BbModel model = BbModel.parse(YsmFolderModel.bundledDefault());
        var sword = new VanillaYsmAnimations.ItemState("minecraft:diamond_sword", Set.of("minecraft:swords"),
                "sword", "none", false, false, 1);
        var shield = new VanillaYsmAnimations.ItemState("minecraft:shield", Set.of(), "shield", "block", false, false, 1);
        var vanilla = new VanillaYsmAnimations.VanillaState(false, 0, false, false, false, sword, shield,
                VanillaYsmAnimations.Hand.NONE, 0, VanillaYsmAnimations.Hand.NONE, 0, false, "", Set.of(), false, false);
        PreviewScene owner = new PreviewScene(model, "gui", ModelPreview.Context.OWNER);
        AtomicInteger bindings = new AtomicInteger();
        var firstInput = new PreviewScene.NativeInputs(nativeSample(0, vanilla),
                Map.of("ysm.has_mainhand", 1d, "ysm.has_offhand", 1d), context -> {
                    bindings.incrementAndGet(); context.stringQuery("ysm.entity_type", "player");
                    context.query("ysm.has_mainhand", 1d); context.query("ysm.has_offhand", 1d);
                });
        var first = owner.sample(900, Map.of(), firstInput);
        assertEquals(ModelPreview.Context.OWNER, first.context());
        assertTrue(first.animations().contains("hold_mainhand:sword"));
        assertTrue(first.animations().contains("hold_offhand:shield"));
        assertFalse(first.animations().contains("gui")); assertFalse(first.slots().contains("player.cap"));
        var movingInput = new PreviewScene.NativeInputs(nativeSample(.2, vanilla), firstInput.queries(), firstInput.configure());
        var moved = owner.sample(901, Map.of(), movingInput);
        assertTrue(moved.animations().contains("walk"));
        var expected = new EntityAnimationController();
        expected.update(0, firstInput.motion(), LocalMotionPolicy.forModel(model), List.of(), model.animationCatalog());
        expected.update(1, movingInput.motion(), LocalMotionPolicy.forModel(model), List.of(), model.animationCatalog());
        assertEquals(expected.layers(), moved.layers(), "The GUI uses the existing native selector with a private clock");
        assertEquals(2, bindings.get());
        var card = new PreviewScene(model, "gui", ModelPreview.Context.CARD).sample(901, Map.of(), movingInput);
        assertEquals(List.of("gui"), card.animations()); assertEquals(List.of("player.cap"), card.slots());
        assertEquals(2, bindings.get(), "A dummy card never invokes the actual owner's bindings");
        assertEquals(List.of(), new PreviewScene(model, "gui", ModelPreview.Context.OWNER)
                .sample(50, Map.of()).animations(), "An absent native entity does not invent an idle/held-item state");
    }

    @Test void cardKeepsMainStoppedAndExplicitCapWithAuthoredLoopAndFalseExtraFlag() throws Exception {
        for (String loop : List.of("ONCE", "HOLD", "LOOP")) {
            var events = JsonParser.parseString("{\"player_ctrl_gui_focus\":[\"return 3;\"]}").getAsJsonObject();
            BbModel model = tinyModel("gui_focus", "ctrl.playing_extra_animation ? 64 : 16", loop, 65535, events);
            PreviewScene card = new PreviewScene(model, "gui_focus", ModelPreview.Context.CARD);
            var first = card.sample(500, Map.of());
            assertEquals(List.of("gui_focus"), first.animations()); assertEquals(List.of("player.cap"), first.slots());
            assertEquals(loop, first.layers().getFirst().loop());
            assertEquals(1, minimumX(first.vertices()), 1e-5,
                    "Explicit cap remains active even when the gui_focus predicate stops; the dummy extra flag is false");
            var elapsed = card.sample(525, Map.of());
            assertEquals(loop, elapsed.layers().getFirst().loop());
            assertEquals(loop.equals("ONCE") ? 0 : 1, minimumX(elapsed.vertices()), 1e-5);
        }
        var legacy = new PreviewScene(tinyModel("gui", "16", "HOLD", 18, null), "gui");
        assertEquals("LOOP", legacy.sample(5, Map.of()).layers().getFirst().loop());
    }

    @Test void ownerAndCardClocksAuthorVariablesAndResetsStayIndependent() throws Exception {
        var events = JsonParser.parseString("""
                {"player_init":["v.updates=0;"],"player_update":["v.updates+=1;"]}
                """).getAsJsonObject();
        BbModel model = tinyModel("gui", "v.updates*16", "LOOP", 65535, events);
        PreviewScene card = new PreviewScene(model, "gui", ModelPreview.Context.CARD);
        PreviewScene owner = new PreviewScene(model, "gui", ModelPreview.Context.OWNER);
        var nativeInputs = new PreviewScene.NativeInputs(nativeSample(0, VanillaYsmAnimations.VanillaState.NONE),
                Map.of("ysm.has_mainhand", 0d), context -> context.query("ysm.has_mainhand", 0d));
        card.sample(100, Map.of()); var matureCard = card.sample(110, Map.of());
        assertEquals(2, minimumX(matureCard.vertices()), 1e-5);
        owner.sample(200, Map.of(), nativeInputs); var matureOwner = owner.sample(205, Map.of(), nativeInputs);
        assertEquals(5, matureOwner.age()); assertEquals(10, matureCard.age());
        card.sample(111, Map.of()); assertEquals(2, minimumX(matureOwner.vertices()), 1e-5);
        card.restart(); var restarted = card.sample(300, Map.of());
        assertEquals(0, restarted.age()); assertEquals(2, restarted.startCount());
        assertEquals(1, minimumX(restarted.vertices()), 1e-5, "A restart resets only the card author's variables");
        assertSame(matureOwner, owner.sample(205, Map.of(), nativeInputs));
        assertSame(matureOwner, owner.sample(204.8, Map.of(), nativeInputs));
        assertEquals(1, matureOwner.startCount());
        assertEquals(0, owner.sample(100, Map.of(), nativeInputs).age(), "Only that context resets after a clock rollback");
        assertEquals(2, owner.sample(101, Map.of(), nativeInputs).startCount());
        var held = tinyModel("gui", "ysm.has_mainhand && ysm.has_offhand && ysm.entity_type == 'player' ? 32 : 0",
                "LOOP", 65535, null);
        PreviewScene nativeOwner = new PreviewScene(held, "gui", ModelPreview.Context.OWNER);
        var heldInputs = new PreviewScene.NativeInputs(nativeSample(0, VanillaYsmAnimations.VanillaState.NONE), Map.of(), context -> {
            context.query("ysm.has_mainhand", 1d); context.query("ysm.has_offhand", 1d);
            context.stringQuery("ysm.entity_type", "player");
        });
        nativeOwner.sample(0, Map.of(), heldInputs);
        assertEquals(2, minimumX(nativeOwner.sample(3, Map.of(), heldInputs).vertices()), 1e-5,
                "Typed owner bindings are retained rather than replaced with the dummy's empty-hand inputs");
    }

    @Test void fourBuiltinsUseOnlyTheirDeclaredCardPreviewWithoutSyntheticHoverOrFocus() throws Exception {
        for (String id : BuiltinYsmModels.ids()) {
            var imported = YsmFolderModel.bundledWithProfile(id, null);
            BbModel model = BbModel.parse(imported.raw());
            assertEquals("gui", imported.previewAnimation());
            assertTrue(List.of("hover", "hover_fadeout", "focus").stream().noneMatch(model.animations()::contains), id);
            var card = new PreviewScene(model, imported.previewAnimation(), ModelPreview.Context.CARD).sample(800, Map.of());
            assertEquals(List.of("gui"), card.animations(), id);
            assertEquals(List.of("player.cap"), card.slots(), id);
            assertEquals(model.animationLoop("gui"), card.layers().getFirst().loop(), id);
            assertFalse(card.vertices().isEmpty(), id);
        }
    }

    private static EntityAnimationController.Sample nativeSample(double x, VanillaYsmAnimations.VanillaState vanilla) {
        return new EntityAnimationController.Sample(x, 64, 0, true, false, false, false,
                false, false, false, false, "", false, 0, false, false, true, false, vanilla);
    }

    private static double minimumX(List<BbModel.Vertex> vertices) {
        return vertices.stream().mapToDouble(BbModel.Vertex::x).min().orElseThrow();
    }

    private static BbModel tinyModel(String preview, String expression, String loop, int format,
                                     com.google.gson.JsonObject events) throws Exception {
        var raw = JsonParser.parseString("""
                {"meta":{"format_version":"5.0"},"ysm_controller_family":"player","textures":[],
                 "elements":[{"uuid":"cube","from":[0,0,0],"to":[16,16,16],
                    "faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
                 "outliner":[{"uuid":"body","name":"body","origin":[0,0,0],"children":["cube"]}],"animations":[]}
                """).getAsJsonObject();
        raw.addProperty("ysm_format_version", format);
        if (events != null) raw.add("ysm_events", events);
        for (String name : List.of("idle", preview)) {
            var clip = JsonParser.parseString("""
                    {"name":"","length":1,"loop":"LOOP","animators":{"body":{"type":"bone","keyframes":[
                      {"channel":"position","time":0,"data_points":[{"x":0,"y":0,"z":0}]}]}}}
                    """).getAsJsonObject();
            clip.addProperty("name", name); clip.addProperty("loop", name.equals(preview) ? loop : "LOOP");
            if (format < 19) clip.addProperty("ysm_primary", false);
            clip.getAsJsonObject("animators").getAsJsonObject("body").getAsJsonArray("keyframes")
                    .get(0).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().addProperty("x", expression);
            raw.getAsJsonArray("animations").add(clip);
        }
        try (var texture = YsmFolderModel.class.getResourceAsStream(
                "/assets/meplayeractions/builtin/openysm_default/textures/default.png")) {
            assertNotNull(texture);
            var entry = new com.google.gson.JsonObject();
            entry.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(texture.readAllBytes()));
            raw.getAsJsonArray("textures").add(entry);
        }
        return BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8));
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
