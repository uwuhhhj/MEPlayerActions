package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeBbModelBasisTest {
    @ParameterizedTest
    @CsvSource({"4.10,32.5,0,0", "4.10,0,-37,0", "4.10,0,0,24", "4.10,23,-41,57",
            "5.0,32.5,0,0", "5.0,0,-37,0", "5.0,0,0,24", "5.0,23,-41,57"})
    void authorRestRotationUsesTheSamePositiveZyxEditorBasisAcrossFormatVersions(String version, float x, float y, float z)
            throws Exception {
        JsonObject source = staticSource(version);
        source.getAsJsonArray("outliner").get(0).getAsJsonObject().add("rotation", array(x, y, z));
        byte[] bytes = source.toString().getBytes(StandardCharsets.UTF_8);
        var imported = NativeBbModel.read(bytes, Map.of("model.bbmodel", bytes), null);
        var model = BbModel.parseLocal(imported.raw());

        Matrix4f editor = rotation(x, y, z);
        assertArrayEquals(editor.get(new float[16]), model.basisBoneTransforms().get("Body").get(new float[16]), 1e-5f);
        assertArrayEquals(BbModel.parse(bytes).basisBoneTransforms().get("Body").get(new float[16]),
                model.basisBoneTransforms().get("Body").get(new float[16]), 1e-5f);
    }

    @Test
    void authoredLocatorRetainsItsLocalRestRotationAndParentBasis() throws Exception {
        JsonObject source = staticSource("5.0");
        JsonObject body = source.getAsJsonArray("outliner").get(0).getAsJsonObject();
        body.add("rotation", array(15, 20, -25));
        JsonObject locator = JsonParser.parseString("""
                {"uuid":"hand","name":"RightHandLocator","type":"locator","position":[4,2,1],"rotation":[-17,31,43]}
                """).getAsJsonObject();
        source.getAsJsonArray("elements").add(locator);
        body.getAsJsonArray("children").add("hand");
        byte[] bytes = source.toString().getBytes(StandardCharsets.UTF_8);
        var imported = NativeBbModel.read(bytes, Map.of("model.bbmodel", bytes), null);
        var model = BbModel.parseLocal(imported.raw());

        Matrix4f expected = rotation(15, 20, -25).translate(4 / 16f, 2 / 16f, 1 / 16f).mul(rotation(-17, 31, 43));
        assertArrayEquals(expected.get(new float[16]),
                model.basisBoneTransforms().get("RightHandLocator").get(new float[16]), 1e-5f);
    }

    private static JsonObject staticSource(String version) throws Exception {
        JsonObject source = NativeBbModelTest.source();
        source.add("meta", JsonParser.parseString("{\"format_version\":\"" + version + "\",\"model_format\":\"bedrock\"}"));
        JsonArray elements = new JsonArray();
        elements.add(source.getAsJsonArray("elements").get(0).deepCopy());
        source.add("elements", elements);
        source.remove("groups");
        source.remove("animations");
        source.remove("animation_controllers");
        source.add("outliner", JsonParser.parseString("[{\"uuid\":\"body\",\"name\":\"Body\",\"origin\":[0,0,0],\"children\":[\"cube\"]}]"));
        return source;
    }

    private static JsonArray array(float x, float y, float z) {
        JsonArray values = new JsonArray();
        values.add(x); values.add(y); values.add(z);
        return values;
    }

    private static Matrix4f rotation(float x, float y, float z) {
        return new Matrix4f().rotateZ((float) Math.toRadians(z)).rotateY((float) Math.toRadians(y)).rotateX((float) Math.toRadians(x));
    }
}
