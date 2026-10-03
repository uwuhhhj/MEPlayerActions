package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.model.BbModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** GUI-only orthographic camera. Authored model coordinates never enter a world renderer. */
final class PreviewMesh {
    record Point(float x, float y, float u, float v) { }
    record Quad(List<Point> points, int texture, int color, float depth) { }

    private PreviewMesh() { }

    /** Angles are degrees; zero yaw faces the model's authored negative-Z front. */
    static List<Quad> project(List<BbModel.Vertex> vertices, int x, int y, int width, int height,
                              float yaw, float pitch) {
        if (width <= 0 || height <= 0 || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || vertices.isEmpty() || vertices.size() % 4 != 0) return List.of();
        double a = Math.toRadians(yaw % 360), b = Math.toRadians(pitch % 360);
        double cy = Math.cos(a), sy = Math.sin(a), cp = Math.cos(b), sp = Math.sin(b);
        float[] positions = new float[vertices.size() * 3];
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vertices.size(); i++) {
            BbModel.Vertex v = vertices.get(i);
            if (!finite(v)) return List.of();
            double vx = cy * v.x() + sy * v.z(), vz = -sy * v.x() + cy * v.z();
            float px = (float) vx, py = (float) (cp * v.y() - sp * vz);
            float depth = (float) (sp * v.y() + cp * vz);
            if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(depth)) return List.of();
            positions[i * 3] = px;
            positions[i * 3 + 1] = py;
            positions[i * 3 + 2] = depth;
            minX = Math.min(minX, px); maxX = Math.max(maxX, px);
            minY = Math.min(minY, py); maxY = Math.max(maxY, py);
        }
        // Reserve margin on every side, including tiny grid cards. Translate by the
        // geometry's center so asymmetric and off-origin user models fit as well.
        float scale = Math.min(width * .88f / Math.max(.001f, maxX - minX),
                height * .88f / Math.max(.001f, maxY - minY));
        float centerX = (minX + maxX) * .5f, centerY = (minY + maxY) * .5f;
        List<Quad> quads = new ArrayList<>(vertices.size() / 4);
        for (int i = 0; i < vertices.size(); i += 4) {
            BbModel.Vertex face = vertices.get(i);
            List<Point> points = new ArrayList<>(4);
            float depth = 0;
            for (int corner = 0; corner < 4; corner++) {
                BbModel.Vertex v = vertices.get(i + corner);
                if (v.texture() != face.texture()) return List.of();
                // From negative Z, the camera's right vector is negative X; using
                // positive X would mirror the authored front texture and asymmetry.
                points.add(new Point(x + width * .5f - (positions[(i + corner) * 3] - centerX) * scale,
                        y + height * .5f - (positions[(i + corner) * 3 + 1] - centerY) * scale,
                        v.u(), v.v()));
                depth += positions[(i + corner) * 3 + 2] * .25f;
            }
            double nx = cy * face.nx() + sy * face.nz(), nz = -sy * face.nx() + cy * face.nz();
            double ny = cp * face.ny() - sp * nz;
            nz = sp * face.ny() + cp * nz;
            double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
            // Ambient + a soft key light above the camera. Draw both sides, like the
            // gameplay renderer, so thin hair/clothing sheets remain visible on rotation.
            double light = normalLength < 1e-8 ? 0 : Math.max(0, (-.45 * nx + .7 * ny - .8 * nz)
                    / (Math.sqrt(.45 * .45 + .7 * .7 + .8 * .8) * normalLength));
            int shade = (int) Math.round(255 * (.58 + .42 * light));
            int color = 0xff000000 | shade << 16 | shade << 8 | shade;
            quads.add(new Quad(List.copyOf(points), face.texture(), color, depth));
        }
        // All quads stay in one GUI element/atlas. Minecraft may sort GUI elements
        // by texture, but cannot reorder these far-to-near faces within the batch.
        quads.sort(Comparator.comparingDouble(Quad::depth).reversed());
        return List.copyOf(quads);
    }

    private static boolean finite(BbModel.Vertex v) {
        return Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z())
                && Float.isFinite(v.u()) && Float.isFinite(v.v())
                && Float.isFinite(v.nx()) && Float.isFinite(v.ny()) && Float.isFinite(v.nz());
    }
}
