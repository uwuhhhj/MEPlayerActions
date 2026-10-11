package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBElement;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBModelFile;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBModelParser;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBToRawConverter;
import com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Independent Blockbench 4.10 cube preview expectations, before any player/world transform. */
class NativeBbModelCubeGeometryTest {
    // Official preview: https://github.com/JannisX11/blockbench/blob/v4.10.0/lib/three_custom.js#L1-L46,
    // cube.js updateFaces indices 0,2,1,2,3,1,
    // and updateUV at https://github.com/JannisX11/blockbench/blob/v4.10.0/js/outliner/cube.js#L1194-L1215.
    // Quad order below is texture top-left, bottom-left, bottom-right, top-right, outward winding.
    @ParameterizedTest
    @CsvSource({ "north,0", "south,1", "east,2", "west,3", "up,4", "down,5" })
    void eachEditorFaceBindsItsFourTextureCornersToTheCorrectOutwardGeometry(String direction, int index)
            throws Exception {
        RawYsmModel.RawCube cube = convert(fixture());
        RawYsmModel.RawFace face = cube.faces.get(index);
        float[][] expected = switch (direction) {
        case "north" -> new float[][] { { 3, 7, 11 }, { 3, 5, 11 }, { 1, 5, 11 }, { 1, 7, 11 } };
        case "south" -> new float[][] { { 1, 7, 13 }, { 1, 5, 13 }, { 3, 5, 13 }, { 3, 7, 13 } };
        case "east" -> new float[][] { { 3, 7, 13 }, { 3, 5, 13 }, { 3, 5, 11 }, { 3, 7, 11 } };
        case "west" -> new float[][] { { 1, 7, 11 }, { 1, 5, 11 }, { 1, 5, 13 }, { 1, 7, 13 } };
        case "up" -> new float[][] { { 1, 7, 11 }, { 1, 7, 13 }, { 3, 7, 13 }, { 3, 7, 11 } };
        case "down" -> new float[][] { { 1, 5, 13 }, { 1, 5, 11 }, { 3, 5, 11 }, { 3, 5, 13 } };
        default -> throw new AssertionError(direction);
        };
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(expected[i], face.positions[i], 1e-6f, direction + " corner " + i);
        }

        assertArrayEquals(new float[] { .125f, .125f, .625f, .625f }, face.u, 1e-6f);
        assertArrayEquals(new float[] { .1875f, .6875f, .6875f, .1875f }, face.v, 1e-6f);
        Vector3f cross = new Vector3f(face.positions[1]).sub(new Vector3f(face.positions[0]))
                .cross(new Vector3f(face.positions[2]).sub(new Vector3f(face.positions[0])));
        assertTrue(cross.dot(new Vector3f(face.normal)) > 0,
                "Front-face winding must agree with its outward light normal");
    }

    @ParameterizedTest
    @CsvSource({ "0,.125,.1875", "90,.125,.6875", "180,.625,.6875", "270,.625,.1875" })
    void editorQuarterTurnsRotateUvAroundTheFaceWithoutMirroringIt(int angle, float firstU, float firstV)
            throws Exception {
        JsonObject source = fixture();
        source.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north")
                .addProperty("rotation", angle);
        RawYsmModel.RawFace face = convert(source).faces.getFirst();
        assertEquals(firstU, face.u[0], 1e-6f);
        assertEquals(firstV, face.v[0], 1e-6f);
        assertArrayEquals(new float[] { 3, 7, 11 }, face.positions[0], 1e-6f);
        // The opposite vertex remains the opposite texture corner for all four author rotations.
        assertEquals(.75f - firstU, face.u[2], 1e-6f);
        assertEquals(.875f - firstV, face.v[2], 1e-6f);
    }

    @Test
    void reversedAuthorUvEndpointsRemainIntentionalMirrorsRatherThanBeingSorted() throws Exception {
        JsonObject source = fixture();
        source.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north")
                .add("uv", JsonParser.parseString("[20,22,4,6]"));
        RawYsmModel.RawFace face = convert(source).faces.getFirst();
        assertArrayEquals(new float[] { .625f, .625f, .125f, .125f }, face.u, 1e-6f);
        assertArrayEquals(new float[] { .6875f, .1875f, .1875f, .6875f }, face.v, 1e-6f);
    }

    @Test
    void missingNullAndExplicitlyDisabledCubeFacesDoNotGainInventedTextureGeometry() throws Exception {
        JsonObject source = fixture();
        JsonObject faces = source.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");
        faces.remove("south");
        faces.remove("up");
        faces.remove("down");
        faces.getAsJsonObject("east").add("texture", null);
        faces.getAsJsonObject("west").addProperty("enabled", false);
        RawYsmModel.RawCube cube = convert(source);
        assertEquals(1, cube.faces.size());
        assertArrayEquals(new float[] { 0, 0, -1 }, cube.faces.getFirst().normal, 1e-6f);
    }

    @ParameterizedTest
    @ValueSource(strings = { "24cbc799-0989-ceb6-c71c-1100386b086c", "cfa0e28b-f9f6-4e9f-4cad-f11ec9465992",
            "f0e062d0-b7ac-2b95-a564-bbbc794c6db7", "9ba5a9c7-57a8-a86b-6aca-f8b1d0a7a9b7" })
    void actualCachedJkEyeAndSkirtFacesRetainEditorTextureTopLeftAfterCubeRotation(String uuid) throws Exception {
        byte[] bytes;
        try (InputStream stream = getClass().getResourceAsStream("/numeric-blueprints/ysm_01_jk.bbmodel")) {
            assertNotNull(stream);
            bytes = stream.readAllBytes();
        }
        assertEquals("c6b55c6765713c6d843cad0da78350782f1e1d068cb4920b0dca083465fcc2f5",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        JsonObject original = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject element = original.getAsJsonArray("elements").asList().stream().map(value -> value.getAsJsonObject())
                .filter(value -> value.get("uuid").getAsString().equals(uuid)).findFirst().orElseThrow();
        // Isolate the authored cube, retaining its actual six face UVs, rotation, origin and source texture.
        BBModelFile model = BBModelParser.parse(original.toString());
        BBElement selected = model.elements.stream().filter(value -> value.uuid.equals(uuid)).findFirst().orElseThrow();
        model.elements = new java.util.ArrayList<>(List.of(selected));
        model.outliner.clear();
        model.groups.clear();
        model.animations.clear();
        model.animation_controllers.clear();
        RawYsmModel.RawFace face = BBToRawConverter.convertEditorAnimations(model, null).mainEntity.mainModel.bones
                .stream().filter(bone -> bone.name.equals("default")).findFirst().orElseThrow().cubes.getFirst().faces
                .getFirst();
        JsonArray north = element.getAsJsonObject("faces").getAsJsonObject("north").getAsJsonArray("uv");
        assertEquals(north.get(0).getAsFloat() / 256f, face.u[0], 1e-6f);
        assertEquals(north.get(1).getAsFloat() / 256f, face.v[0], 1e-6f);
        // North texture top-left is editor (+X,+Y,-Z), independently transformed by its author rotation.
        Vector3f expected = new Vector3f(selected.to[0], selected.to[1], selected.from[2]);
        Vector3f origin = new Vector3f(selected.origin);
        new Matrix4f().translation(origin).rotateZ((float) Math.toRadians(selected.rotation[2]))
                .rotateY((float) Math.toRadians(selected.rotation[1]))
                .rotateX((float) Math.toRadians(selected.rotation[0])).translate(new Vector3f(origin).negate())
                .transformPosition(expected).div(16);
        assertArrayEquals(new float[] { expected.x, expected.y, expected.z }, face.positions[0], 1e-5f, selected.name);
    }

    private static RawYsmModel.RawCube convert(JsonObject source) {
        return BBToRawConverter.convertEditorAnimations(BBModelParser.parse(source.toString()),
                null).mainEntity.mainModel.bones.stream().filter(bone -> bone.name.equals("Body")).findFirst()
                .orElseThrow().cubes.getFirst();
    }

    private static JsonObject fixture() throws Exception {
        JsonObject model = NativeBbModelTest.source();
        model.add("resolution", JsonParser.parseString("{\"width\":32,\"height\":32}"));
        model.add("elements", JsonParser.parseString("""
                [{"uuid":"cube","type":"cube","from":[16,80,176],"to":[48,112,208],"faces":{
                "north":{"uv":[4,6,20,22],"texture":0},"south":{"uv":[4,6,20,22],"texture":0},
                "east":{"uv":[4,6,20,22],"texture":0},"west":{"uv":[4,6,20,22],"texture":0},
                "up":{"uv":[4,6,20,22],"texture":0},"down":{"uv":[4,6,20,22],"texture":0}}}]
                """));
        model.remove("groups");
        model.remove("animations");
        model.remove("animation_controllers");
        model.add("outliner",
                JsonParser.parseString("[{\"uuid\":\"body\",\"name\":\"Body\",\"children\":[\"cube\"]}]"));
        return model;
    }
}
