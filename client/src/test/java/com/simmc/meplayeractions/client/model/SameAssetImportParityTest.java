package com.simmc.meplayeractions.client.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Identical source assets preserve author semantics regardless of local selection or server delivery. */
class SameAssetImportParityTest {
    private static final List<String> BONES = List.of("Root", "Head", "FLongHair", "MTail", "Tail", "Tail2", "Tail7");
    private static final List<BbModel.Layer> IDLE = List.of(new BbModel.Layer("base", "idle", 0, 1, "LOOP", 0, 0));

    @ParameterizedTest
    @CsvSource({"full,ysm_01_jk", "full,ysm_02_jk", "numeric-blueprints,ysm_01_jk", "numeric-blueprints,ysm_02_jk"})
    void publicWineFoxAssetsKeepLocalAndServerGeometryProfilesAndIdlePoses(String family, String id) throws Exception {
        byte[] source = source(family, id);
        var local = NativeBbModel.read(source, Map.of("model.bbmodel", source), null);
        var server = BbModelAsset.read(source);
        BbModel localModel = BbModel.parseLocal(local.raw());

        assertModelParity(localModel, local.profile(), server);
        assertEquals(local.previewAnimation(), server.previewAnimation());
        assertPoseParity(localModel, local.profile(), server, BONES, IDLE);

        BbModel editor = BbModel.parse(source);
        assertEquals(32.5f, localModel.initialBoneRotation(localModel.boneIndex("Tail")).x, 1e-4,
                "Blockbench's positive Tail rest angle must survive import");
        for (String bone : BONES)
            assertMatrixEquals(editor.basisBoneTransforms().get(bone), localModel.basisBoneTransforms().get(bone),
                    bone + " editor rest chain");
        Vector3f tailTip = localModel.basisBoneTransforms().get("Tail7").getTranslation(new Vector3f());
        assertEquals(1.9572757f, tailTip.y, 1e-4, "Tail7 must follow the unreflected ancestor rest rotations");
        assertEquals(1.1073203f, tailTip.z, 1e-4, "Tail7 must remain behind the model in its editor basis");

        if (family.equals("full")) {
            assertArrayEquals(source, local.raw(), "MPA runtime exports must not be converted twice");
            assertFalse(local.profile().isYsm(), "Prepared assets retain their existing runtime profile");
            assertEquals(editor.animations(), localModel.animations());
            assertEquals(editor.usesYsmHeadQueries(), localModel.usesYsmHeadQueries());
        } else {
            assertTrue(local.profile().isImportedBbModel(), "Ordinary author BBModels retain the mature conversion path");
        }
    }

    @Test
    void ordinaryAuthorMeshControllerAndLocatorUseTheSameLocalAndServerImport() throws Exception {
        byte[] source = NativeBbModelTest.source().toString().getBytes(StandardCharsets.UTF_8);
        var local = NativeBbModel.read(source, Map.of("model.bbmodel", source), null);
        var server = BbModelAsset.read(source);
        BbModel localModel = BbModel.parseLocal(local.raw());

        assertTrue(local.profile().isImportedBbModel());
        assertModelParity(localModel, local.profile(), server);
        assertEquals(local.previewAnimation(), server.previewAnimation());
        assertTrue(server.model().basisBoneTransforms().keySet().containsAll(List.of("Body", "RightHandLocator", "Accessory")));
        assertFalse(server.model().basisVertices().isEmpty());
        assertPoseParity(localModel, local.profile(), server, List.of("Body", "RightHandLocator", "Accessory"),
                List.of(new BbModel.Layer("base", "authored", 0, 1, "HOLD", 0, 0)));
    }

    private static void assertModelParity(BbModel local, YsmModelProfile localProfile, BbModelAsset.Decoded server) {
        assertEquals(local.animations(), server.model().animations());
        assertEquals(local.animationFormatVersion(), server.model().animationFormatVersion());
        assertEquals(local.usesYsmHeadQueries(), server.model().usesYsmHeadQueries());
        assertEquals(local.basisVertices(), server.model().basisVertices());
        assertEquals(local.basisBoneTransforms().keySet(), server.model().basisBoneTransforms().keySet());
        for (String bone : local.basisBoneTransforms().keySet())
            assertMatrixEquals(local.basisBoneTransforms().get(bone), server.model().basisBoneTransforms().get(bone), bone + " basis");
        assertEquals(localProfile.isYsm(), server.profile().isYsm());
        assertEquals(localProfile.sourceManifest(), server.profile().sourceManifest());
        assertEquals(localProfile.animationFiles(), server.profile().animationFiles());
        assertEquals(localProfile.animationControllers(), server.profile().animationControllers());
        assertEquals(localProfile.extraAnimations(), server.profile().extraAnimations());
        assertEquals(localProfile.functions(), server.profile().functions());
        assertEquals(localProfile.events(), server.profile().events());
    }

    private static void assertPoseParity(BbModel localModel, YsmModelProfile localProfile, BbModelAsset.Decoded server,
                                         List<String> bones, List<BbModel.Layer> layers) {
        AnimationPlayer localPlayer = new AnimationPlayer(localModel), serverPlayer = new AnimationPlayer(server.model());
        if (localProfile.isYsm()) localPlayer.enableNativeYsm();
        if (server.profile().isYsm()) serverPlayer.enableNativeYsm();
        for (int tick = 0; tick <= 120; tick++) {
            localPlayer.sample(tick, layers, 0, 0);
            serverPlayer.sample(tick, layers, 0, 0);
            if (tick == 0 || tick == 1 || tick == 20 || tick == 120)
                for (String bone : bones)
                    assertMatrixEquals(localPlayer.boneTransform(bone).orElseThrow(), serverPlayer.boneTransform(bone).orElseThrow(),
                            "tick " + tick + "/" + bone);
        }
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual, String message) {
        assertNotNull(expected, message + " expected bone");
        assertNotNull(actual, message + " actual bone");
        assertArrayEquals(expected.get(new float[16]), actual.get(new float[16]), 1e-4f, message);
    }

    private static byte[] source(String family, String id) throws Exception {
        String resource = (family.equals("full") ? "/" : "/" + family + "/") + id + ".bbmodel";
        try (var stream = SameAssetImportParityTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return stream.readAllBytes();
        }
    }
}
