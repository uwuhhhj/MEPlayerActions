package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.model.BbModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** GUI-only orthographic camera. Authored model coordinates never enter a world renderer. */
final class PreviewMesh {
    /** Per-corner camera depth is retained for a depth-tested GUI model pass. */
    record Point(float x, float y, float u, float v, float depth) {
        Point(float x, float y, float u, float v) { this(x, y, u, v, 0); }
    }
    record Quad(List<Point> points, int texture, int color, float depth) { }
    record Projection(List<Quad> quads, float pixelsPerBlock) {
        static final Projection EMPTY = new Projection(List.of(), 1);
    }
    record Settings(boolean noLighting, boolean disableRotation, String background, String foreground) { }

    private PreviewMesh() { }

    static Settings settings(JsonObject properties) {
        return new Settings(flag(properties, "gui_no_lighting"), flag(properties, "disable_preview_rotation"),
                resource(properties, "gui_background"), resource(properties, "gui_foreground"));
    }

    private static boolean flag(JsonObject properties, String key) {
        JsonElement value = properties.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
    private static String resource(JsonObject properties, String key) {
        JsonElement value = properties.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && value.getAsString().length() <= 256 ? value.getAsString() : "";
    }

    /** One ordered atlas batch prevents a GUI texture sorter from placing the foreground behind the model. */
    static List<Quad> compose(List<Quad> model, int x, int y, int width, int height, boolean background, boolean foreground) {
        if (width <= 0 || height <= 0) return List.of();
        if (!background && !foreground) return model;
        List<Quad> result = new ArrayList<>(model.size() + 2);
        if (background) result.add(image(x, y, width, height, -1));
        result.addAll(model);
        if (foreground) result.add(image(x, y, width, height, -2));
        return List.copyOf(result);
    }

    private static Quad image(int x, int y, int width, int height, int texture) {
        return new Quad(List.of(new Point(x, y, 0, 0), new Point(x, y + height, 0, 1),
                new Point(x + width, y + height, 1, 1), new Point(x + width, y, 1, 0)), texture, 0xffffffff, 0);
    }

    /** A short UI reveal, separate from any authored model animation. */
    static List<Quad> entrance(List<Quad> quads, int x, int y, int width, int height, double progress) {
        if (progress >= 1) return quads;
        double t = Math.clamp(progress, 0, 1), ease = t * t * (3 - 2 * t);
        float scale = (float) (.9 + .1 * ease), centerX = x + width * .5f, centerY = y + height * .5f;
        int alpha = (int) Math.round(255 * ease);
        return quads.stream().map(quad -> new Quad(quad.points().stream().map(point ->
                new Point(centerX + (point.x() - centerX) * scale,
                        centerY + (point.y() - centerY) * scale, point.u(), point.v(), point.depth())).toList(),
                quad.texture(), alpha << 24 | quad.color() & 0xffffff, quad.depth())).toList();
    }

    /** Angles are degrees; zero yaw faces the model's authored negative-Z front. */
    static List<Quad> project(List<BbModel.Vertex> vertices, int x, int y, int width, int height,
                              float yaw, float pitch) {
        return project(vertices, x, y, width, height, yaw, pitch, new Settings(false, false, "", ""));
    }

    static List<Quad> project(List<BbModel.Vertex> vertices, int x, int y, int width, int height,
                              float yaw, float pitch, Settings settings) {
        return projectBounds(vertices, x, y, width, height, yaw, pitch, settings).quads();
    }

    /** Ordinary bbmodels retain their bounds-fit camera; depth uses the same scale as X/Y. */
    static Projection projectBounds(List<BbModel.Vertex> vertices, int x, int y, int width, int height,
                                    float yaw, float pitch, Settings settings) {
        if (width <= 0 || height <= 0 || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || vertices.isEmpty() || vertices.size() % 4 != 0) return Projection.EMPTY;
        if (settings.disableRotation()) { yaw = 0; pitch = 0; }
        double a = Math.toRadians(yaw % 360), b = Math.toRadians(pitch % 360);
        double cy = Math.cos(a), sy = Math.sin(a), cp = Math.cos(b), sp = Math.sin(b);
        float[] positions = new float[vertices.size() * 3];
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vertices.size(); i++) {
            BbModel.Vertex v = vertices.get(i);
            if (!finite(v)) return Projection.EMPTY;
            double vx = cy * v.x() + sy * v.z(), vz = -sy * v.x() + cy * v.z();
            float px = (float) vx, py = (float) (cp * v.y() - sp * vz);
            float depth = (float) (sp * v.y() + cp * vz);
            if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(depth)) return Projection.EMPTY;
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
                if (v.texture() != face.texture()) return Projection.EMPTY;
                // From negative Z, the camera's right vector is negative X; using
                // positive X would mirror the authored front texture and asymmetry.
                points.add(new Point(x + width * .5f - (positions[(i + corner) * 3] - centerX) * scale,
                        y + height * .5f - (positions[(i + corner) * 3 + 1] - centerY) * scale,
                        v.u(), v.v(), positions[(i + corner) * 3 + 2]));
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
            int shade = settings.noLighting() || face.emissive() ? 255 : (int) Math.round(255 * (.58 + .42 * light));
            int color = 0xff000000 | shade << 16 | shade << 8 | shade;
            quads.add(new Quad(List.copyOf(points), face.texture(), color, depth));
        }
        // Keep transparent surfaces far-to-near while the GPU also tests each pixel's
        // actual depth. A whole-face average cannot resolve intersecting thin eye/hair planes.
        quads.sort(Comparator.comparingDouble(Quad::depth).reversed());
        return new Projection(List.copyOf(quads), scale);
    }

    /** Native YSM previews use the entity-height anchor and fixed source size, never geometry bounds. */
    static List<Quad> project(List<BbModel.Vertex> vertices, int x, int y, int width, int height,
                              Settings settings, NativeGuiPreviewCamera.Camera camera) {
        if (camera == null || width <= 0 || height <= 0 || vertices.isEmpty() || vertices.size() % 4 != 0)
            return List.of();
        List<Quad> quads = new ArrayList<>(vertices.size() / 4);
        for (int i = 0; i < vertices.size(); i += 4) {
            BbModel.Vertex face = vertices.get(i);
            List<Point> points = new ArrayList<>(4);
            float depth = 0;
            for (int corner = 0; corner < 4; corner++) {
                BbModel.Vertex vertex = vertices.get(i + corner);
                if (!finite(vertex) || vertex.texture() != face.texture()) return List.of();
                var projected = camera.project(vertex.x(), vertex.y(), vertex.z(), x, y, width, height);
                if (!Float.isFinite(projected.x()) || !Float.isFinite(projected.y())
                        || !Float.isFinite(projected.depth())) return List.of();
                points.add(new Point(projected.x(), projected.y(), vertex.u(), vertex.v(), projected.depth()));
                depth += projected.depth() * .25f;
            }
            var normal = camera.rotateNormal(face.nx(), face.ny(), face.nz());
            double length = Math.sqrt(normal.x() * normal.x() + normal.y() * normal.y()
                    + normal.depth() * normal.depth());
            double light = length < 1e-8 ? 0 : Math.max(0,
                    (-.45 * normal.x() + .7 * normal.y() - .8 * normal.depth())
                            / (Math.sqrt(.45 * .45 + .7 * .7 + .8 * .8) * length));
            int shade = settings.noLighting() || face.emissive() ? 255 : (int) Math.round(255 * (.58 + .42 * light));
            quads.add(new Quad(List.copyOf(points), face.texture(),
                    0xff000000 | shade << 16 | shade << 8 | shade, depth));
        }
        quads.sort(Comparator.comparingDouble(Quad::depth).reversed());
        return List.copyOf(quads);
    }

    private static boolean finite(BbModel.Vertex v) {
        return Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z())
                && Float.isFinite(v.u()) && Float.isFinite(v.v())
                && Float.isFinite(v.nx()) && Float.isFinite(v.ny()) && Float.isFinite(v.nz());
    }
}
