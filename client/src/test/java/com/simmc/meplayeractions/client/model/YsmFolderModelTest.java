package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import com.simmc.meplayeractions.expression.Molang;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class YsmFolderModelTest {
    @TempDir Path temporary;
    private static final String RESOURCE="/assets/meplayeractions/builtin/openysm_default/";

    @Test void cc0DefaultRetainsGeometryRealActionTracksAndSecondOrderDynamics() throws Exception {
        byte[] converted=YsmFolderModel.bundledDefault();BbModel model=BbModel.parse(converted);
        assertEquals(172,model.cubeCount());assertEquals(115,model.animations().size());assertTrue(model.ysmPhysics());
        assertTrue(model.animations().containsAll(List.of("idle","walk","run","jump","swim","elytra_fly","extra1","extra7")));
        assertEquals(60,model.animationLengthTicks("idle"));assertEquals(20,model.animationLengthTicks("walk"));
        assertNotEquals(model.sample(7,List.of(layer("idle"))),model.sample(7,List.of(layer("walk"))),"Imported movement must use authored tracks");
        AnimationPlayer left=new AnimationPlayer(model),right=new AnimationPlayer(model);
        for(int tick=0;tick<80;tick++) {
            var vertices=left.sample(tick,List.of(layer("walk")),30,-15,Map.of("query.ground_speed",5d,"query.vertical_speed",1d));
            right.sample(tick,List.of(layer("walk")),-30,15,Map.of("query.ground_speed",0d,"query.vertical_speed",0d));
            assertEquals(4128,vertices.size());assertTrue(vertices.stream().allMatch(vertex->Float.isFinite(vertex.x())&&Float.isFinite(vertex.y())&&Float.isFinite(vertex.z())));
        }
        assertNotEquals(left.sample(79,List.of(layer("walk")),30,-15,Map.of("query.ground_speed",5d,"query.vertical_speed",1d)),
                right.sample(79,List.of(layer("walk")),-30,15,Map.of("query.ground_speed",0d,"query.vertical_speed",0d)),
                "Imported look and dynamics must use each model instance's input");
        assertTrue(new String(converted, StandardCharsets.UTF_8).contains("ysm.second_order"),
                "The native per-instance resolver receives authored spring calls");
        assertFalse(new String(converted, StandardCharsets.UTF_8).contains("variable.ysm_import_s"));
        assertTrue(model.animations().containsAll(List.of("use_mainhand:bow", "use_offhand:shield", "swing:sword")));
        try(var manifest=YsmFolderModel.class.getResourceAsStream(RESOURCE+"ysm.json")) {
            assertNotNull(manifest);assertEquals("CC 0",JsonParser.parseString(new String(manifest.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("metadata").getAsJsonObject("license").get("type").getAsString());
        }
        assertNotNull(YsmFolderModel.class.getResourceAsStream(RESOURCE+"LICENSE.OpenYSM.txt"));
    }

    @Test void localFolderConvertsToTheSameCanonicalPlayerModel() throws Exception {
        Path folder=copyDefault();assertArrayEquals(YsmFolderModel.bundledDefault(),YsmFolderModel.read(folder));
    }

    @Test void previewMetadataTravelsBesideTheUnchangedModelBytesAndHash() throws Exception {
        Path folder = copyDefault(); var imported = YsmFolderModel.readWithPreview(folder);
        assertEquals("gui", imported.previewAnimation());
        assertArrayEquals(YsmFolderModel.bundledDefault(), imported.raw());
        JsonObject manifest = read(folder.resolve("ysm.json"));
        manifest.getAsJsonObject("properties").addProperty("preview_animation", "idle");
        Files.writeString(folder.resolve("ysm.json"), manifest.toString());
        var idle = YsmFolderModel.readWithPreview(folder);
        assertEquals("idle", idle.previewAnimation()); assertArrayEquals(imported.raw(), idle.raw());
        assertEquals(AssetTransfer.hash(imported.raw()), AssetTransfer.hash(idle.raw()));
        manifest.getAsJsonObject("properties").addProperty("preview_animation", "missing");
        Files.writeString(folder.resolve("ysm.json"), manifest.toString());
        var missing = YsmFolderModel.readWithPreview(folder);
        assertEquals("", missing.previewAnimation()); assertArrayEquals(imported.raw(), missing.raw());
    }

    @Test void resourceReferencesCannotReadOutsideTheSelectedFolder() throws Exception {
        Path folder=copyDefault();JsonObject manifest=read(folder.resolve("ysm.json"));
        for(String reference:List.of("../outside.json","models/../../outside.json","/outside.json","C:/outside.json","models\\main.json","https://example.invalid/main.json")) {
            manifest.getAsJsonObject("files").getAsJsonObject("player").getAsJsonObject("model").addProperty("main",reference);
            Files.writeString(folder.resolve("ysm.json"),manifest.toString());assertThrows(IOException.class,()->YsmFolderModel.read(folder),reference);
        }
    }

    @Test void mainTextureMustBePngAndDecodedWithinExistingRendererLimits() throws Exception {
        Path folder=copyDefault();Files.writeString(folder.resolve("textures/default.png"),"not a png");
        assertThrows(IOException.class,()->YsmFolderModel.read(folder));
        Files.write(folder.resolve("textures/default.png"),new byte[AssetTransfer.MAX_RAW+1]);
        assertThrows(IOException.class,()->YsmFolderModel.read(folder));
    }

    @Test void jsonDepthAndCyclicBoneHierarchyAreRejectedBeforeRendererConstruction() throws Exception {
        Path folder=copyDefault();Path manifestPath=folder.resolve("ysm.json");String manifest=Files.readString(manifestPath);
        Files.writeString(manifestPath,"[".repeat(100)+"0"+"]".repeat(100));assertThrows(IOException.class,()->YsmFolderModel.read(folder));
        Files.writeString(manifestPath,manifest);
        Path geometryPath=folder.resolve("models/main.json");JsonObject geometry=read(geometryPath);JsonArray bones=geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
        bones.get(0).getAsJsonObject().addProperty("parent",bones.get(bones.size()-1).getAsJsonObject().get("name").getAsString());
        Files.writeString(geometryPath,geometry.toString());assertThrows(IOException.class,()->YsmFolderModel.read(folder));
    }

    @Test void referencePathsRejectLinksWhenTheFileSystemCanCreateThem() throws Exception {
        Path folder=copyDefault(),outside=temporary.resolve("outside-main.json");Files.copy(folder.resolve("models/main.json"),outside);
        Path target=folder.resolve("models/main.json"),backup=folder.resolve("models/original.json");Files.move(target,backup);
        try { Files.createSymbolicLink(target,outside); }
        catch(IOException|UnsupportedOperationException error) { Files.move(backup,target);assertArrayEquals(YsmFolderModel.bundledDefault(),YsmFolderModel.read(folder));return; }
        assertThrows(IOException.class,()->YsmFolderModel.read(folder));
    }

    @Test void specAndSupportedPlayerAssetFieldsAreRequired() throws Exception {
        Path folder=copyDefault();JsonObject manifest=read(folder.resolve("ysm.json"));manifest.addProperty("spec",999);
        Files.writeString(folder.resolve("ysm.json"),manifest.toString());assertThrows(IOException.class,()->YsmFolderModel.read(folder));
        manifest.addProperty("spec",2);manifest.getAsJsonObject("files").getAsJsonObject("player").getAsJsonObject("animation").remove("main");
        Files.writeString(folder.resolve("ysm.json"),manifest.toString());assertThrows(IOException.class,()->YsmFolderModel.read(folder));
    }

    @Test void officialTimelineArraysAndExplicitDurationsRemainUnexpanded() throws Exception {
        for (String id : List.of(BuiltinYsmModels.TAISHO_MAID_ID, BuiltinYsmModels.NEW_YEAR_ID)) {
            var imported = YsmFolderModel.bundledWithProfile(id, null);
            JsonArray clips = JsonParser.parseString(new String(imported.raw(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("animations");
            JsonObject parallel = clip(clips, "parallel4");
            assertEquals(.01, parallel.get("length").getAsDouble());
            assertEquals(6.8333, clip(clips, "pre_parallel3").get("length").getAsDouble());
            JsonArray timeline = parallel.getAsJsonObject("animators").getAsJsonObject("ysm_import_timeline").getAsJsonArray("keyframes");
            assertEquals(2, timeline.size());
            assertEquals(64, timeline.get(0).getAsJsonObject().getAsJsonArray("data_points").size());
            assertEquals(50, timeline.get(1).getAsJsonObject().getAsJsonArray("data_points").size());
            var builtin = BuiltinYsmModels.find(id).orElseThrow();
            try (var source = YsmFolderModel.class.getResourceAsStream(builtin.resourceRoot() + "animations/main.animation.json")) {
                assertNotNull(source);
                JsonObject original = JsonParser.parseString(new String(source.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("animations").getAsJsonObject("parallel4").getAsJsonObject("timeline");
                for (int event = 0; event < timeline.size(); event++) {
                    JsonObject frame = timeline.get(event).getAsJsonObject();
                    JsonArray expected = original.getAsJsonArray(event == 0 ? "0.0" : "0.0101");
                    JsonArray actual = frame.getAsJsonArray("data_points");
                    assertEquals(event == 0 ? 0 : .0101, frame.get("time").getAsDouble());
                    for (int program = 0; program < actual.size(); program++)
                        assertEquals(expected.get(program).getAsString(), actual.get(program).getAsJsonObject().get("script").getAsString());
                }
            }
        }
        var astronaut = YsmFolderModel.bundledWithProfile(BuiltinYsmModels.ASTRONAUT_ID, null);
        BbModel model = BbModel.parse(astronaut.raw());
        assertEquals(200_000, model.animationLengthTicks("hold_mainhand:spear"));
        assertEquals("HOLD", model.animationLoop("hold_mainhand:spear"));
        assertEquals(Double.POSITIVE_INFINITY, model.animationLengthTicks("idle"));
        JsonArray clips = JsonParser.parseString(new String(astronaut.raw(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("animations");
        assertEquals(6.8333, clip(clips, "pre_parallel1").get("length").getAsDouble());
        assertEquals(0, clip(clips, "idle").get("length").getAsDouble());
        assertTrue(clip(clips, "idle").get("ysm_infinite").getAsBoolean());
    }

    @Test void sixtyFourTimelineProgramsKeepOrderAndIndependentReturns() throws Exception {
        Path folder = copyDefault(), path = folder.resolve("animations/extra.animation.json");
        JsonObject source = read(path), probe = new JsonObject(), timeline = new JsonObject();
        JsonArray programs = new JsonArray(); programs.add("v.order=0;return 5;");
        for (int i = 1; i < 64; i++) programs.add("v.order=v.order+1;");
        probe.addProperty("animation_length", 1); timeline.add("0.0", programs); probe.add("timeline", timeline);
        source.getAsJsonObject("animations").add("timeline_64", probe);
        JsonObject absent = new JsonObject(), later = new JsonObject(); later.addProperty("2", "v.later=1;"); absent.add("timeline", later);
        source.getAsJsonObject("animations").add("absent_length", absent);
        Files.writeString(path, source.toString());
        BbModel model = BbModel.parse(YsmFolderModel.read(folder));
        Molang.Context context = new Molang.Context();
        model.clipEvents("timeline_64", "ONCE", -.001, 0, context);
        assertEquals(63, context.get("v.order"), "Each program runs in order; one return cannot skip the remaining 63 programs");
        assertEquals(Double.POSITIVE_INFINITY, model.animationLengthTicks("absent_length"));
        model.clipEvents("absent_length", "ONCE", 0, 40, context);
        assertEquals(1, context.get("v.later"), "Missing duration remains infinite even when a later event supplies the final key time");
    }

    @Test void folderRejectsTimelineAndDurationBeyondObservedBoundedCompatibility() throws Exception {
        Path folder = copyDefault(), path = folder.resolve("animations/extra.animation.json");
        JsonObject source = read(path), probe = new JsonObject(), timeline = new JsonObject();
        JsonArray programs = new JsonArray(); for (int i = 0; i < 65; i++) programs.add("1;");
        probe.addProperty("animation_length", 1); timeline.add("0.0", programs); probe.add("timeline", timeline);
        source.getAsJsonObject("animations").add("bounded_probe", probe); Files.writeString(path, source.toString());
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder), "65 programs exceeds the evidenced 64-program limit");
        programs = new JsonArray(); for (int i = 0; i < 5; i++) programs.add("/*" + "酒".repeat(2200) + "*/1;");
        timeline.add("0.0", programs); Files.writeString(path, source.toString());
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder), "UTF-8 aggregate bytes exceed 32 KiB despite individually short valid programs");
        probe.remove("timeline");
        for (double length : List.of(-1d, 10_001d, Double.NaN, Double.POSITIVE_INFINITY)) {
            probe.addProperty("animation_length", length); Files.writeString(path, source.toString());
            assertThrows(IOException.class, () -> YsmFolderModel.read(folder), "Invalid/beyond-limit finite duration: " + length);
        }
        probe.addProperty("animation_length", 1); JsonObject invalidTime = new JsonObject(); invalidTime.addProperty("10001", "1;");
        probe.add("timeline", invalidTime); Files.writeString(path, source.toString());
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder), "Key times remain bounded independently of author duration");
    }

    @Test void officialFoxcarKeepsItsOriginalSignedCubeAndEveryAuthoredFace() throws Exception {
        var imported = YsmFolderModel.bundledWithProfile(BuiltinYsmModels.TAISHO_MAID_ID, null);
        JsonObject original;
        try (var resource = YsmFolderModel.class.getResourceAsStream(BuiltinYsmModels.find(BuiltinYsmModels.TAISHO_MAID_ID)
                .orElseThrow().resourceRoot() + "models/foxcar.json")) {
            assertNotNull(resource);
            original = JsonParser.parseString(new String(resource.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int cubeIndex = 0, signedIndex = -1; JsonObject signedSource = null;
        for (JsonElement boneValue : original.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones")) {
            JsonObject bone = boneValue.getAsJsonObject(); if (!bone.has("cubes")) continue;
            for (JsonElement cube : bone.getAsJsonArray("cubes")) {
                if (bone.get("name").getAsString().equals("eyes2") && signedSource == null) {
                    signedIndex = cubeIndex; signedSource = cube.getAsJsonObject();
                }
                cubeIndex++;
            }
        }
        assertEquals(551, cubeIndex); assertNotNull(signedSource);
        assertEquals(-.252, signedSource.getAsJsonArray("size").get(2).getAsDouble());
        for (String id : List.of("minecraft:horse", "minecraft:mule")) {
            var component = imported.profile().components().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
            assertEquals(551, component.model().cubeCount(), "Every authored vehicle cube is retained");
            JsonObject raw = JsonParser.parseString(new String(component.raw(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(65535, raw.get("ysm_format_version").getAsInt());
            JsonObject cube = raw.getAsJsonArray("elements").get(signedIndex).getAsJsonObject();
            assertTrue(cube.get("ysm_signed_cube").getAsBoolean());
            double[] expectedFrom = {7.60139, 23.806, 16.8984}, expectedTo = {12.38939, 26.194, 16.6464};
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(expectedFrom[axis], cube.getAsJsonArray("from").get(axis).getAsDouble(), 1e-8);
                assertEquals(expectedTo[axis], cube.getAsJsonArray("to").get(axis).getAsDouble(), 1e-8);
            }
            assertTrue(cube.getAsJsonArray("from").get(2).getAsDouble() > cube.getAsJsonArray("to").get(2).getAsDouble(),
                    "Signed Z endpoints must not be sorted or converted to absolute dimensions");
            assertEquals(java.util.Set.of("north", "east", "west"), cube.getAsJsonObject("faces").keySet());
            assertEquals(JsonParser.parseString("[16,425,24,429]"), cube.getAsJsonObject("faces").getAsJsonObject("north").getAsJsonArray("uv"));
            var vertices = component.model().vertices(component.model().emptyPose(), java.util.Set.of("eyes2"));
            assertEquals(20, vertices.size(), "Three signed faces plus the other two authored one-face cubes remain visible");
            assertTrue(vertices.stream().allMatch(vertex -> Float.isFinite(vertex.x()) && Float.isFinite(vertex.y()) && Float.isFinite(vertex.z())));
        }
    }

    private static JsonObject clip(JsonArray clips, String name) {
        for (JsonElement clip : clips) if (clip.getAsJsonObject().get("name").getAsString().equals(name)) return clip.getAsJsonObject();
        throw new AssertionError("Missing clip: " + name);
    }

    private Path copyDefault() throws Exception {
        return YsmFolderFixtures.copyDefault(temporary.resolve("model"));
    }
    private static JsonObject read(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    private static BbModel.Layer layer(String name) { return new BbModel.Layer("locomotion",name,0,1,"LOOP",0,0); }
}
