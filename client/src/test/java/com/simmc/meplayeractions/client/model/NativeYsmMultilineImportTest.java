package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel;
import com.simmc.meplayeractions.expression.Molang;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeYsmMultilineImportTest {
    @Test void binaryPropertyControlsTimelineEarlyReturnAndPartialSourceLines() throws Exception {
        RawYsmModel source = sourceWithTimeline("v.value=1;return 7;", "v.value=2;");
        source.properties.mergeMultilineExpr = true;
        var merged = NativeYsmFile.importModel(source, null);
        Molang.Context context = new Molang.Context();
        BbModel.parseLocal(merged.raw()).clipEvents("probe", "ONCE", -.001, 0, context);
        assertEquals(1, context.get("v.value"), "Merged return exits the complete program, as in AnimationMapper.parse");
        assertTrue(merged.profile().mergeMultilineExpressions());
        source.properties.mergeMultilineExpr = false;
        context = new Molang.Context();
        BbModel.parseLocal(NativeYsmFile.importModel(source, null).raw()).clipEvents("probe", "ONCE", -.001, 0, context);
        assertEquals(2, context.get("v.value"), "Unmerged array entries have independent returns");

        source = sourceWithTimeline("v.value =", "1+2;"); source.properties.mergeMultilineExpr = true;
        context = new Molang.Context();
        BbModel.parseLocal(NativeYsmFile.importModel(source, null).raw()).clipEvents("probe", "ONCE", -.001, 0, context);
        assertEquals(3, context.get("v.value"), "Individual source fragments are compiled only after the source join");
        String joined = new String(NativeYsmFile.importModel(source, null).raw(), StandardCharsets.UTF_8);
        assertTrue(joined.contains("v.value =\\n1+2;"));
    }

    @Test void controllerEntryAndExitUseSameJoinWhileProfileRetainsAuthorArrays() throws Exception {
        RawYsmModel source = NativeYsmFileTest.model(); source.properties.mergeMultilineExpr = true;
        var file = new RawYsmModel.RawAnimationControllerFile();
        var controller = new RawYsmModel.RawAnimationController();
        controller.animationName = "player.post_main"; controller.initialState = "default";
        var initial = new RawYsmModel.RawControllerState(); initial.name = "default";
        initial.onEntry.addAll(List.of("v.entry=1;return 7;", "v.entry=9;"));
        initial.onExit.addAll(List.of("v.exit=1;return 7;", "v.exit=9;"));
        initial.transitions.put("next", "q.switch");
        var next = new RawYsmModel.RawControllerState(); next.name = "next"; next.onEntry.add("v.next=2;");
        controller.states.addAll(List.of(initial, next)); file.controllers.put(controller.animationName, controller);
        source.mainEntity.animationControllerFiles.add(file);
        var imported = NativeYsmFile.importModel(source, null);
        AnimationPlayer player = new AnimationPlayer(BbModel.parseLocal(imported.raw()));
        player.sample(0, List.of(), 0, 0, Map.of("query.switch", 0d));
        assertEquals(1d, player.expressionVariables().get("variable.entry"));
        player.sample(1, List.of(), 0, 0, Map.of("query.switch", 1d));
        assertEquals(1d, player.expressionVariables().get("variable.exit"));
        assertEquals(2d, player.expressionVariables().get("variable.next"));
        var original = imported.profile().animationControllers().getAsJsonObject("player.post_main")
                .getAsJsonObject("states").getAsJsonObject("default");
        assertEquals(2, original.getAsJsonArray("on_entry").size(), "Author metadata is preserved independently of compiled runtime actions");
    }

    @Test void sparkleFolderProfileAndAnimationFamilyReachNativeRuntimeAndCullRemainsMetadata() throws Exception {
        RawYsmModel source = sourceWithTimeline("v.value=3;"); source.formatVersion = 65535;
        var animation = source.mainEntity.animationFiles.remove("main");
        source.mainEntity.animationFiles.put("animation-main", animation);
        source.properties.renderLayersFirst = true; source.properties.allCutout = true;
        var imported = NativeYsmFile.importModel(source, null);
        BbModel model = BbModel.parseLocal(imported.raw());
        assertTrue(model.animations().contains("probe"));
        assertEquals(65535, model.animationFormatVersion());
        assertTrue(imported.profile().renderLayersFirst()); assertTrue(imported.profile().allCutout());
        assertEquals(4, model.sample(0, List.of()).size(), "Source CPU rendering does not prune faces using its GPU cullable flag");
        var converted = JsonParser.parseString(new String(imported.raw(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(converted.get("ysm_all_cutout").getAsBoolean());
        var restored = NativeModelBundle.decode(NativeModelBundle.encode("ysm", "ysm.json", imported.sourceFiles()), null);
        assertTrue(restored.profile().allCutout()); assertTrue(restored.model().animations().contains("probe"));
    }

    private static RawYsmModel sourceWithTimeline(String... programs) {
        RawYsmModel source = NativeYsmFileTest.model();
        var file = new RawYsmModel.RawAnimationFile(); var animation = new RawYsmModel.RawAnimation();
        animation.name = "probe"; animation.length = 1; animation.loopMode = 0;
        var event = new RawYsmModel.RawTimelineEvent(); event.timestamp = 0; event.events.addAll(List.of(programs));
        animation.timelineEvents.add(event); file.animations.put(animation.name, animation);
        source.mainEntity.animationFiles.put("main", file); return source;
    }
}
