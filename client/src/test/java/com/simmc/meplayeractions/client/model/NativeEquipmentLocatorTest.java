package com.simmc.meplayeractions.client.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeEquipmentLocatorTest {
    @Test void importedEquipmentChainPreservesEveryPivotAndRotationWhileIgnoringParentAndLocatorScale() throws Exception {
        byte[] source = NativeBbModelTest.source().toString().getBytes(StandardCharsets.UTF_8);
        var imported = NativeBbModel.read(source, Map.of("model.bbmodel", source), null);
        assertTrue(imported.profile().isImportedBbModel());
        var model = BbModel.parseLocal(imported.raw());
        int body = model.boneIndex("Body"), hand = model.boneIndex("RightHandLocator");
        assertTrue(body >= 0 && hand >= 0);
        var baseline = model.emptyPose();
        baseline.channels[body][0] = new Vector3f(3, 5, 7);
        baseline.channels[body][1] = new Vector3f(10, 20, 90);
        baseline.channels[hand][0] = new Vector3f(11, 13, 17);
        baseline.channels[hand][1] = new Vector3f(15, 25, 35);
        Matrix4f expected = model.boneTransforms(baseline).get("RightHandLocator");
        var scaled = baseline.copy();
        scaled.channels[body][2] = new Vector3f(-2, 3, 4);
        scaled.channels[hand][2] = new Vector3f(0);
        assertFalse(model.boneTransforms(scaled).containsKey("RightHandLocator"),
                "Native authored YSM still honors the zero-scale hidden chain");
        Matrix4f actual = model.equipmentLocatorTransforms(scaled).get("RightHandLocator");
        assertNotNull(actual, "Sparkle's equipment caller intentionally ignores hidden-scale output");
        assertMatrix(expected, actual);
        assertEquals(1, actual.determinant3x3(), 1e-5, "Parent mirroring and scale are omitted at every ancestor");
        assertEquals(new Vector3f(-2, 3, 4), scaled.channels[body][2], "Attachment extraction must not mutate body animation");
        actual.translate(100, 100, 100);
        assertMatrix(expected, model.equipmentLocatorTransforms(scaled).get("RightHandLocator"));
    }

    private static void assertMatrix(Matrix4f expected, Matrix4f actual) {
        assertNotNull(expected);
        float[] first = expected.get(new float[16]), second = actual.get(new float[16]);
        for (int i = 0; i < first.length; i++) assertEquals(first[i], second[i], 1e-6, "Matrix element " + i);
    }
}
