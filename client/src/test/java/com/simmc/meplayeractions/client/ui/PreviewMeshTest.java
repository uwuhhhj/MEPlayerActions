package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreviewMeshTest {
    @Test void offOriginTallModelFitsAndCentersInBothLargePreviewAndSmallCard() {
        List<BbModel.Vertex> model = face(10, 30, -4, 2, 8, 0, -1);
        for (int[] viewport : List.of(new int[]{20, 40, 240, 300}, new int[]{400, 10, 48, 60})) {
            List<PreviewMesh.Quad> mesh = PreviewMesh.project(model, viewport[0], viewport[1],
                    viewport[2], viewport[3], 0, 0);
            assertEquals(1, mesh.size());
            var points = mesh.getFirst().points();
            double minX = points.stream().mapToDouble(PreviewMesh.Point::x).min().orElseThrow();
            double maxX = points.stream().mapToDouble(PreviewMesh.Point::x).max().orElseThrow();
            double minY = points.stream().mapToDouble(PreviewMesh.Point::y).min().orElseThrow();
            double maxY = points.stream().mapToDouble(PreviewMesh.Point::y).max().orElseThrow();
            assertEquals(viewport[0] + viewport[2] * .5, (minX + maxX) * .5, .001);
            assertEquals(viewport[1] + viewport[3] * .5, (minY + maxY) * .5, .001);
            assertEquals(viewport[3] * .88, maxY - minY, .001);
            assertTrue(minX > viewport[0] && maxX < viewport[0] + viewport[2]);
            assertTrue(minY > viewport[1] && maxY < viewport[1] + viewport[3]);
            // A model's positive Y remains toward the top of the GUI.
            assertTrue(points.get(0).y() < points.get(1).y());
            assertTrue(points.get(0).x() < points.get(2).x(), "Authored front UVs must not be mirrored");
        }
    }

    @Test void cameraRotationReversesDepthOrderWithoutLosingFacesOrTextureReferences() {
        List<BbModel.Vertex> model = new ArrayList<>();
        model.addAll(face(0, 0, -2, 1, 1, 3, -1));
        model.addAll(face(0, 0, 2, 1, 1, 7, 1));
        List<PreviewMesh.Quad> front = PreviewMesh.project(model, 0, 0, 100, 100, 0, 0);
        List<PreviewMesh.Quad> rear = PreviewMesh.project(model, 0, 0, 100, 100, 180, 0);
        assertEquals(List.of(7, 3), front.stream().map(PreviewMesh.Quad::texture).toList());
        assertEquals(List.of(3, 7), rear.stream().map(PreviewMesh.Quad::texture).toList());
        assertEquals(8, front.stream().mapToInt(quad -> quad.points().size()).sum());
        assertTrue(front.get(0).depth() > front.get(1).depth());
        assertTrue(rear.get(0).depth() > rear.get(1).depth());
        assertTrue(rear.get(1).points().get(0).x() > rear.get(1).points().get(2).x());
    }

    @Test void dragYawAndPitchChangeProjectionAndNormalsControlSoftLighting() {
        List<BbModel.Vertex> model = face(0, 0, -2, 2, 4, 0, -1);
        var front = PreviewMesh.project(model, 0, 0, 200, 200, 0, 0).getFirst();
        var turned = PreviewMesh.project(model, 0, 0, 200, 200, 50, -25).getFirst();
        assertNotEquals(front.points(), turned.points());
        var backNormal = PreviewMesh.project(face(0, 0, -2, 2, 4, 0, 1),
                0, 0, 200, 200, 0, 0).getFirst();
        assertTrue((front.color() & 255) > (backNormal.color() & 255));
        assertEquals(255, front.color() >>> 24);
        assertTrue((backNormal.color() & 255) >= 147, "Ambient light retains unlit thin sheets");
    }

    @Test void faceUvsStayAttachedToTheirCornersAfterRotationAndSorting() {
        List<BbModel.Vertex> model = face(0, 0, -1, 1, 1, 9, -1);
        var points = PreviewMesh.project(model, 0, 0, 80, 90, 125, 30).getFirst().points();
        for (int i = 0; i < 4; i++) {
            assertEquals(model.get(i).u(), points.get(i).u());
            assertEquals(model.get(i).v(), points.get(i).v());
        }
    }

    @Test void badGeometryOrCameraProducesNoSubmission() {
        List<BbModel.Vertex> good = face(0, 0, 0, 1, 1, 0, -1);
        assertTrue(PreviewMesh.project(good, 0, 0, 0, 10, 0, 0).isEmpty());
        assertTrue(PreviewMesh.project(good, 0, 0, 10, 10, Float.NaN, 0).isEmpty());
        assertTrue(PreviewMesh.project(good.subList(0, 3), 0, 0, 10, 10, 0, 0).isEmpty());
        List<BbModel.Vertex> broken = new ArrayList<>(good);
        broken.set(1, new BbModel.Vertex(Float.POSITIVE_INFINITY, 0, 0, 0, 0, 0, 0, -1, 0));
        assertTrue(PreviewMesh.project(broken, 0, 0, 10, 10, 0, 0).isEmpty());
        broken.set(1, new BbModel.Vertex(0, 0, 0, 0, 0, 0, 0, -1, 1));
        assertTrue(PreviewMesh.project(broken, 0, 0, 10, 10, 0, 0).isEmpty());
    }

    @Test void uiEntranceFadesAndScalesWithoutChangingUvsDepthOrTheFinalAuthoredView() {
        var mesh = PreviewMesh.project(face(0, 0, -1, 2, 4, 0, -1), 10, 20, 100, 120, 0, 0);
        var start = PreviewMesh.entrance(mesh, 10, 20, 100, 120, 0).getFirst();
        var half = PreviewMesh.entrance(mesh, 10, 20, 100, 120, .5).getFirst();
        assertEquals(0, start.color() >>> 24); assertEquals(128, half.color() >>> 24);
        assertEquals(mesh.getFirst().depth(), half.depth()); assertEquals(mesh.getFirst().texture(), half.texture());
        for (int i = 0; i < 4; i++) {
            assertEquals(mesh.getFirst().points().get(i).u(), half.points().get(i).u());
            assertEquals(mesh.getFirst().points().get(i).v(), half.points().get(i).v());
            assertEquals(60 + (mesh.getFirst().points().get(i).x() - 60) * .95, half.points().get(i).x(), .0001);
        }
        assertSame(mesh, PreviewMesh.entrance(mesh, 10, 20, 100, 120, 1));
        assertSame(mesh, PreviewMesh.entrance(mesh, 10, 20, 100, 120, 2));
    }

    @Test void sourceNoLightingPreservesTextureColorsAndFixedCameraIgnoresUserRotation() {
        var vertices = face(0, 0, -2, 2, 4, 0, 1);
        var flags = PreviewMesh.settings(JsonParser.parseString("{\"gui_no_lighting\":true,\"disable_preview_rotation\":true}").getAsJsonObject());
        var fixed = PreviewMesh.project(vertices, 10, 20, 100, 120, 53, -41, flags);
        var front = PreviewMesh.project(vertices, 10, 20, 100, 120, 0, 0, flags);
        assertEquals(front, fixed, "A disabled preview camera must not rotate after a drag or carry-over from another model");
        assertEquals(0xffffffff, fixed.getFirst().color(), "Unlit previews retain the PNG's authored RGB");
        var shaded = PreviewMesh.project(vertices, 10, 20, 100, 120, 0, 0);
        assertEquals(shaded.getFirst().points(), fixed.getFirst().points());
        assertTrue((shaded.getFirst().color() & 255) < 255);
        var revealed = PreviewMesh.entrance(fixed, 10, 20, 100, 120, .5).getFirst();
        assertEquals(128, revealed.color() >>> 24); assertEquals(0xffffff, revealed.color() & 0xffffff);
    }

    @Test void authorBackgroundModelAndForegroundShareOneOrderedBatchAndBoundedViewport() {
        var mesh = PreviewMesh.project(face(0, 0, -2, 2, 4, 3, -1), 10, 20, 100, 120, 0, 0);
        var composed = PreviewMesh.compose(mesh, 10, 20, 100, 120, true, true);
        assertEquals(List.of(-1, 3, -2), composed.stream().map(PreviewMesh.Quad::texture).toList());
        assertSame(mesh.getFirst(), composed.get(1), "Decorations do not change model geometry, colors or UVs");
        for (var decoration : List.of(composed.getFirst(), composed.getLast())) {
            assertEquals(0xffffffff, decoration.color());
            assertEquals(List.of(new PreviewMesh.Point(10, 20, 0, 0), new PreviewMesh.Point(10, 140, 0, 1),
                    new PreviewMesh.Point(110, 140, 1, 1), new PreviewMesh.Point(110, 20, 1, 0)), decoration.points());
        }
        assertSame(mesh, PreviewMesh.compose(mesh, 10, 20, 100, 120, false, false));
        assertTrue(PreviewMesh.compose(mesh, 10, 20, 0, 120, true, true).isEmpty());
    }

    @Test void actualBuiltinAuthorFlagsAndImagesAreUsedWithoutInventedDefaults() throws Exception {
        try (var input = YsmFolderModel.class.getResourceAsStream("/assets/meplayeractions/builtin/openysm_default/ysm.json")) {
            assertNotNull(input);
            JsonObject properties = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("properties");
            var flags = PreviewMesh.settings(properties);
            assertTrue(flags.disableRotation()); assertFalse(flags.noLighting());
            assertEquals("textures/gui/background.png", flags.background());
            assertEquals("textures/gui/foreground.png", flags.foreground());
        }
        var unset = PreviewMesh.settings(new JsonObject());
        assertFalse(unset.disableRotation()); assertFalse(unset.noLighting()); assertEquals("", unset.background());
        var invalid = PreviewMesh.settings(JsonParser.parseString("{\"gui_no_lighting\":\"true\",\"disable_preview_rotation\":2,\"gui_background\":[]}").getAsJsonObject());
        assertFalse(invalid.noLighting()); assertFalse(invalid.disableRotation()); assertEquals("", invalid.background());
    }

    private static List<BbModel.Vertex> face(float x, float y, float z, float width, float height,
                                              int texture, float normalZ) {
        return List.of(new BbModel.Vertex(x + width, y + height, z, 0, 0, 0, 0, normalZ, texture),
                new BbModel.Vertex(x + width, y, z, 0, 1, 0, 0, normalZ, texture),
                new BbModel.Vertex(x, y, z, 1, 1, 0, 0, normalZ, texture),
                new BbModel.Vertex(x, y + height, z, 1, 0, 0, 0, normalZ, texture));
    }
}
