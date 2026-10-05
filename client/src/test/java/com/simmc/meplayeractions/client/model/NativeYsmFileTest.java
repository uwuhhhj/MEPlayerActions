package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel;
import com.simmc.meplayeractions.client.model.nativeysm.PublicYsmCodec;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeYsmFileTest {
    @Test void bodyFamilyConflictsFollowAuthoredOrderAndExplicitFirstPersonFamilyRemainsSeparate() throws Exception {
        RawYsmModel source=model();source.mainEntity.armModel=source.mainEntity.mainModel;
        for(var entry:java.util.Map.of("main",1f,"extra",3f,"arm",2f,"fp_arm",4f).entrySet()) {
            var file=new RawYsmModel.RawAnimationFile();var animation=new RawYsmModel.RawAnimation();
            animation.name="shared";animation.loopMode=3;animation.length=entry.getValue();file.animations.put(animation.name,animation);
            // Insert in an explicit author order, independently of Map.of's iteration order below.
            source.mainEntity.animationFiles.put(entry.getKey(),file);
        }
        var ordered=new java.util.LinkedHashMap<String,RawYsmModel.RawAnimationFile>();
        for(String family:List.of("main","extra","arm","fp_arm"))ordered.put(family,source.mainEntity.animationFiles.get(family));
        source.mainEntity.animationFiles.clear();source.mainEntity.animationFiles.putAll(ordered);
        var imported=NativeYsmFile.importModel(source,null);
        assertEquals(40,BbModel.parseLocal(imported.raw()).animationLengthTicks("shared"));
        assertEquals(80,imported.profile().components().stream().filter(component->component.kind().equals("fp_arm")).findFirst().orElseThrow().model().animationLengthTicks("shared"));
        assertEquals(4,imported.profile().animationFiles().size());
        for(String family:ordered.keySet())assertTrue(imported.sourceFiles().containsKey("animations/"+family+".json"),"Full source families are preserved");
        source.mainEntity.animationFiles.clear();
        for(String family:List.of("arm","main","extra","fp_arm"))source.mainEntity.animationFiles.put(family,ordered.get(family));
        assertEquals(60,BbModel.parseLocal(NativeYsmFile.importModel(source,null).raw()).animationLengthTicks("shared"));
    }
    @Test void binaryTimelinePreservesOneHundredEightySeparateProgramsAndTheirOriginalOrder() throws Exception {
        RawYsmModel source=model();var file=new RawYsmModel.RawAnimationFile();var animation=new RawYsmModel.RawAnimation();
        animation.name="parallel0";animation.loopMode=1;animation.length=.01f;
        var event=new RawYsmModel.RawTimelineEvent();event.timestamp=0;
        event.events.add("v.n+=1;return 7;");
        for(int i=1;i<180;i++)event.events.add("v.n+=1;v.last="+i+";");
        animation.timelineEvents.add(event);file.animations.put(animation.name,animation);source.mainEntity.animationFiles.put("main",file);
        var imported=NativeYsmFile.importModel(source,null);var player=new AnimationPlayer(BbModel.parseLocal(imported.raw()));
        player.sample(0,List.of());
        assertEquals(180d,player.expressionVariables().get("variable.n"));
        assertEquals(179d,player.expressionVariables().get("variable.last"));
        event.events.clear();for(int i=0;i<256;i++)event.events.add("v.n+=1;");
        assertDoesNotThrow(()->NativeYsmFile.importModel(source,null));
        event.events.add("v.n+=1;");assertThrows(IOException.class,()->NativeYsmFile.importModel(source,null));
    }
    @Test void matureBinaryAdapterRetainsBakedFaceWindingUvsGlowAndNativeFormat() throws Exception {
        RawYsmModel source = model(); var imported = NativeYsmFile.importModel(source, null);
        BbModel model = BbModel.parse(imported.raw());
        assertEquals(26, model.animationFormatVersion()); assertTrue(model.hasBone("ysmGlowEyes"));
        var vertices = model.sample(0, List.of()); assertEquals(4, vertices.size());
        assertEquals(0, vertices.get(0).x(), 1e-6); assertEquals(1, vertices.get(1).x(), 1e-6);
        assertEquals(1, vertices.get(2).y(), 1e-6); assertEquals(1, vertices.get(2).u(), 1e-6);
        assertTrue(vertices.stream().allMatch(BbModel.Vertex::emissive));
        assertEquals("CC 0", imported.profile().metadata().getAsJsonObject("license").get("type").getAsString());
        assertEquals("v.face", imported.profile().extraAnimationButtons().get(0).getAsJsonObject().getAsJsonArray("config_forms").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("Binary model", imported.profile().localized("en_us", "metadata.name", "missing"));
        var restored = NativeModelBundle.decode(NativeModelBundle.encode("ysm", "ysm.json", imported.sourceFiles()), null);
        assertEquals(26, restored.model().animationFormatVersion());
        assertEquals(vertices, restored.model().sample(0, List.of()));
        assertEquals(imported.profile().extraAnimationButtons(), restored.profile().extraAnimationButtons());
    }

    @Test void unsupportedTruncatedAndCorruptedFilesFailBeforeNativeAllocation() {
        for (int version : new int[]{0, 1, 32, 33}) {
            byte[] header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(version).array();
            assertThrows(IOException.class, () -> NativeYsmFile.readDecompressed(header, null));
        }
        assertThrows(Exception.class, () -> PublicYsmCodec.decode(new byte[71]));
        assertThrows(Exception.class, () -> PublicYsmCodec.decode(new byte[128]));
        assertThrows(Exception.class, () -> PublicYsmCodec.decode(new byte[8 * 1024 * 1024 + 1]));
    }

    static RawYsmModel model() {
        RawYsmModel raw = new RawYsmModel(); raw.formatVersion = 26; raw.metadata.name = "Binary model"; raw.metadata.licenseType = "CC 0";
        RawYsmModel.RawGeometry geometry = new RawYsmModel.RawGeometry(); geometry.identifier = "geometry.binary";
        RawYsmModel.RawBone bone = new RawYsmModel.RawBone(); bone.name = "ysmGlowEyes"; bone.parentName = "";
        RawYsmModel.RawFace face = new RawYsmModel.RawFace(); face.normal = new float[]{0,0,1};
        face.positions = new float[][]{{0,0,0},{1,0,0},{1,1,0},{0,1,0}}; face.u = new float[]{0,1,1,0}; face.v = new float[]{0,0,1,1};
        RawYsmModel.RawCube cube = new RawYsmModel.RawCube(); cube.faces.add(face); bone.cubes.add(cube); geometry.bones.add(bone); raw.mainEntity.mainModel = geometry;
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture(); texture.name = "default"; texture.imageFormat = -1; texture.width = texture.height = 1; texture.data = new byte[]{(byte)255,0,0,(byte)255};
        raw.mainEntity.textures.put("default", texture); raw.properties.heightScale = raw.properties.widthScale = 1;
        RawYsmModel.ExtraAnimationButton button = new RawYsmModel.ExtraAnimationButton(); button.id = "face"; button.name = "Face"; button.description = "Face configuration";
        RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm(); form.type = "checkbox"; form.defaultValue = "v.face"; form.title = "Show"; form.description = "Show face"; button.forms.add(form); raw.properties.extraAnimationButtons.add(button);
        raw.languageFiles.put("en_us", new RawYsmModel.RawLanguageFile("", java.util.Map.of("metadata.name", "Binary model")));
        return raw;
    }
}
