package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.model.BbModel;
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

    private static List<BbModel.Vertex> face(float x, float y, float z, float width, float height,
                                              int texture, float normalZ) {
        return List.of(new BbModel.Vertex(x + width, y + height, z, 0, 0, 0, 0, normalZ, texture),
                new BbModel.Vertex(x + width, y, z, 0, 1, 0, 0, normalZ, texture),
                new BbModel.Vertex(x, y, z, 1, 1, 0, 0, normalZ, texture),
                new BbModel.Vertex(x, y + height, z, 1, 0, 0, 0, normalZ, texture));
    }
}
