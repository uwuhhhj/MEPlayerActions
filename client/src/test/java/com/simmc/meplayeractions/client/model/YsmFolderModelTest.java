package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.network.AssetTransfer;
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
        assertEquals(172,model.cubeCount());assertEquals(59,model.animations().size());assertTrue(model.ysmPhysics());
        assertTrue(model.animations().containsAll(List.of("idle","walk","run","jump","swim","elytra_fly","extra1","extra7")));
        assertEquals(60,model.animationLengthTicks("idle"));assertEquals(20,model.animationLengthTicks("walk"));
        assertNotEquals(model.sample(7,List.of(layer("idle"))),model.sample(7,List.of(layer("walk"))),"Imported movement must use authored tracks");
        AnimationPlayer left=new AnimationPlayer(model),right=new AnimationPlayer(model);
        for(int tick=0;tick<80;tick++) {
            var vertices=left.sample(tick,List.of(layer("walk")),30,-15,Map.of("query.ground_speed",5d,"query.vertical_speed",1d));
            right.sample(tick,List.of(layer("walk")),-30,15,Map.of("query.ground_speed",0d,"query.vertical_speed",0d));
            assertEquals(4128,vertices.size());assertTrue(vertices.stream().allMatch(vertex->Float.isFinite(vertex.x())&&Float.isFinite(vertex.y())&&Float.isFinite(vertex.z())));
        }
        assertNotEquals(left.expressionVariables(),right.expressionVariables(),"Springs must use each model instance's input");
        assertEquals(1d,left.expressionVariables().get("variable.roaming.red_bow_headdress"));
        assertTrue(left.expressionVariables().entrySet().stream().anyMatch(entry->entry.getKey().startsWith("variable.ysm_import_s")&&entry.getKey().endsWith(".y")&&Math.abs(entry.getValue())>.01));
        try(var manifest=YsmFolderModel.class.getResourceAsStream(RESOURCE+"ysm.json")) {
            assertNotNull(manifest);assertEquals("CC 0",JsonParser.parseString(new String(manifest.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("metadata").getAsJsonObject("license").get("type").getAsString());
        }
        assertNotNull(YsmFolderModel.class.getResourceAsStream(RESOURCE+"LICENSE.OpenYSM.txt"));
    }

    @Test void localFolderConvertsToTheSameCanonicalPlayerModel() throws Exception {
        Path folder=copyDefault();assertArrayEquals(YsmFolderModel.bundledDefault(),YsmFolderModel.read(folder));
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

    private Path copyDefault() throws Exception {
        Path folder=temporary.resolve("model");
        for(String asset:List.of("ysm.json","models/main.json","animations/main.animation.json","animations/extra.animation.json","textures/default.png")) {
            Path target=folder.resolve(asset);Files.createDirectories(target.getParent());
            try(var input=YsmFolderModel.class.getResourceAsStream(RESOURCE+asset)) { assertNotNull(input);Files.write(target,input.readAllBytes()); }
        }
        return folder;
    }
    private static JsonObject read(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    private static BbModel.Layer layer(String name) { return new BbModel.Layer("locomotion",name,0,1,"LOOP",0,0); }
}
