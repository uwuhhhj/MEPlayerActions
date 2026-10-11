package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBModelParser;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBToRawConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeBbModelTest {
    @TempDir Path directory;

    @Test void matureConverterImportsSeparateGroupsMeshAndDeclaredLocatorsIntoNativeRuntime() throws Exception {
        JsonObject source = source();
        var raw = BBToRawConverter.convert(BBModelParser.parse(source.toString()));
        assertTrue(raw.mainEntity.mainModel.bones.stream().anyMatch(bone -> bone.name.equals("Body")));
        assertTrue(raw.mainEntity.mainModel.bones.stream().anyMatch(bone -> bone.name.equals("RightHandLocator") && bone.parentName.equals("Body")));
        assertTrue(raw.mainEntity.mainModel.bones.stream().anyMatch(bone -> bone.name.equals("Accessory") && bone.parentName.equals("Body")));
        assertTrue(raw.mainEntity.mainModel.bones.stream().flatMap(bone -> bone.cubes.stream()).flatMap(cube -> cube.faces.stream()).count() >= 2);
        var imported = NativeBbModel.read(bytes(source), Map.of("model.bbmodel", bytes(source)), null);
        var model = BbModel.parseLocal(imported.raw());
        assertEquals(65535, model.animationFormatVersion());
        assertTrue(model.basisBoneTransforms().keySet().containsAll(List.of("Body", "RightHandLocator", "Accessory")));
        assertFalse(model.basisVertices().isEmpty());
        assertTrue(model.animations().contains("authored"));
        assertEquals("HOLD", model.animationLoop("authored"));
        assertThrows(IllegalArgumentException.class, () -> BbModel.parse(bytes(source)), "The low-level runtime reader does not convert author group tables");
    }

    @Test void numericBezierBakingPreservesAuthorCurveInsteadOfDroppingOrUsingAnEndpointLine() throws Exception {
        var raw = BBToRawConverter.convert(BBModelParser.parse(source().toString()));
        var authored = raw.mainEntity.animationFiles.get("animation-main").animations.get("authored");
        var positions = authored.boneAnimations.getFirst().position;
        assertEquals(25, positions.size());
        assertEquals(.5f, positions.get(12).timestamp, 1e-6);
        assertEquals(7.5f, ((Number)positions.get(12).postData[0]).floatValue(), .02f);
        assertEquals(0f, ((Number)positions.getLast().postData[0]).floatValue(), 1e-6);
        assertTrue(positions.stream().allMatch(frame -> frame.interpolationMode == 0));
    }

    @Test void standardControllerAndTimelineSourcesSurviveTheExistingNativeProfileAdapter() throws Exception {
        JsonObject source = source();
        var raw = BBToRawConverter.convert(BBModelParser.parse(source.toString()));
        var controller = raw.mainEntity.animationControllerFiles.getFirst().controllers.get("player.main");
        assertEquals("waiting", controller.initialState);
        assertEquals("v.started=1;", controller.states.getFirst().onEntry.getFirst());
        assertEquals("v.changed", controller.states.getFirst().transitions.get("active"));
        assertEquals("v.mark=1", raw.mainEntity.animationFiles.get("animation-main").animations.get("authored").timelineEvents.getFirst().events.getFirst());
        var imported = NativeBbModel.read(bytes(source), Map.of("model.bbmodel", bytes(source)), null);
        assertTrue(imported.profile().isYsm());
        assertTrue(BbModel.parse(imported.raw()).controllerDefinitions().names().contains("player.main"));
        assertTrue(imported.profile().animationFiles().containsKey("main"));
    }

    @Test void localSelectionAndPassivePrivateDecodeUseIdenticalMatureConversion() throws Exception {
        Path file = directory.resolve("author.bbmodel"); Files.write(file, bytes(source()));
        LocalModelLibrary library = new LocalModelLibrary(directory);
        var local = library.load("local:author.bbmodel");
        var receiver = NativeModelBundle.decode(library.sourceBundle("local:author.bbmodel"), null);
        assertEquals(local.hash(), receiver.hash());
        assertEquals(local.model().animations(), receiver.model().animations());
        assertEquals(local.model().sample(7, List.of()), receiver.model().sample(7, List.of()));
        assertEquals(local.profile().extraAnimations(), receiver.profile().extraAnimations());
    }

    @Test void declaredCompanionPngCanBeSharedWithoutServerPathsOrASecondFilesystemLookup() throws Exception {
        JsonObject source = source(); JsonObject texture = source.getAsJsonArray("textures").get(0).getAsJsonObject();
        texture.addProperty("source", ""); texture.addProperty("relative_path", "textures/author.png");
        Files.createDirectory(directory.resolve("textures")); Files.write(directory.resolve("textures/author.png"), png());
        Files.write(directory.resolve("author.bbmodel"), bytes(source));
        var library = new LocalModelLibrary(directory); var local = library.load("local:author.bbmodel");
        byte[] bundle = library.sourceBundle("local:author.bbmodel");
        assertTrue(NativeModelBundle.validate(bundle).files().containsKey("textures/author.png"));
        assertEquals(local.hash(), NativeModelBundle.decode(bundle, null).hash());
    }

    @Test void escapedCompanionPathsAndDuplicateJsonAreRejectedBeforeSourceConversion() throws Exception {
        JsonObject source = source(); JsonObject texture = source.getAsJsonArray("textures").get(0).getAsJsonObject();
        texture.addProperty("source", ""); texture.addProperty("relative_path", "../outside.png");
        Path file = directory.resolve("model.bbmodel"); Files.write(file, bytes(source));
        assertThrows(IOException.class, () -> NativeBbModel.localSourceFiles(file, bytes(source)));
        byte[] repeated = "{\"textures\":[],\"textures\":[]}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> NativeBbModel.read(repeated, Map.of("model.bbmodel", repeated), null));
    }

    @Test void internalRuntimeExportsRemainIdenticalRatherThanBeingConvertedTwice() throws Exception {
        byte[] bytes = YsmFolderModel.bundledDefault();
        var imported = NativeBbModel.read(bytes, Map.of("model.bbmodel", bytes), null);
        assertArrayEquals(bytes, imported.raw());
    }

    @Test void unnamedEmbeddedTextureRemainsUsableAndHasStableDerivedIdentity() throws Exception {
        JsonObject source = source();
        JsonObject texture = source.getAsJsonArray("textures").get(0).getAsJsonObject();
        texture.remove("name"); texture.remove("uuid");
        byte[] original = bytes(source);
        var first = NativeBbModel.read(original, Map.of("model.bbmodel", original), null);
        var second = NativeBbModel.read(original, Map.of("model.bbmodel", original), null);
        assertArrayEquals(first.raw(), second.raw());
        assertFalse(BbModelAsset.read(original).model().basisVertices().isEmpty());
        assertEquals(1, first.profile().textures().size());
        assertArrayEquals(bytes(source), original, "Import must not rewrite the original transfer bytes");
    }

    @Test void unnamedTextureMetadataDoesNotCollapseOrReplaceAnotherDeclaredTexture() throws Exception {
        JsonObject source = source();
        JsonObject first = source.getAsJsonArray("textures").get(0).getAsJsonObject();
        JsonObject second = first.deepCopy();
        first.remove("name"); first.remove("uuid"); second.remove("uuid");
        second.addProperty("name", "mpa_embedded_0.png");
        source.getAsJsonArray("textures").add(second);
        byte[] original = bytes(source);
        var imported = NativeBbModel.read(original, Map.of("model.bbmodel", original), null);
        assertEquals(2, imported.profile().textures().size());
        assertEquals(2, imported.profile().textures().stream().map(YsmModelProfile.TextureChoice::id).distinct().count());
        assertTrue(imported.profile().textures().stream().anyMatch(texture -> texture.path().endsWith("mpa_embedded_0.png")));
    }

    static JsonObject source() throws IOException {
        JsonObject model = JsonParser.parseString("""
                {"name":"author","resolution":{"width":1,"height":1},
                 "elements":[
                  {"uuid":"cube","type":"cube","from":[0,0,0],"to":[1,1,1],"faces":{"north":{"uv":[0,0,1,1],"texture":0}}},
                  {"uuid":"mesh","type":"mesh","vertices":{"a":[0,0,0],"b":[1,0,0],"c":[0,1,0]},
                   "faces":{"triangle":{"vertices":["a","b","c"],"uv":{"a":[0,0],"b":[1,0],"c":[0,1]},"texture":0}}},
                  {"uuid":"hand","name":"RightHandLocator","type":"locator","position":[2,1,0]},
                  {"uuid":"accessory","name":"Accessory","type":"null_object","position":[0,2,0]}],
                 "groups":[{"uuid":"body","name":"Body","origin":[0,2,0]}],
                 "outliner":[{"uuid":"body","children":["cube","mesh","hand","accessory"]}],
                 "textures":[{"uuid":"texture","name":"author.png","width":1,"height":1}],
                 "animations":[{"uuid":"animation","name":"authored","length":1,"loop":"hold",
                   "timeline":{"0.2":"v.mark=1;"},
                   "animators":{"body":{"type":"bone","name":"Body","keyframes":[
                    {"channel":"position","time":0,"interpolation":"bezier","bezier_right_time":[0.33333333,0.33333333,0.33333333],"bezier_right_value":[10,0,0],"data_points":[{"x":0,"y":0,"z":0}]},
                    {"channel":"position","time":1,"interpolation":"linear","bezier_left_time":[0.66666667,0.66666667,0.66666667],"bezier_left_value":[10,0,0],"data_points":[{"x":0,"y":0,"z":0}]}]}}}],
                 "animation_controllers":[{"name":"player.main","initial_state":"waiting","states":[
                  {"name":"waiting","animations":[],"on_entry":["v.started=1;"],"transitions":[{"active":"v.changed"}]},
                  {"name":"active","animations":[]}]}]}
                """).getAsJsonObject();
        model.getAsJsonArray("textures").get(0).getAsJsonObject().addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png()));
        return model;
    }
    private static byte[] bytes(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
    private static byte[] png() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB), "png", bytes); return bytes.toByteArray();
    }
}
