package com.simmc.meplayeractions.client.ui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A screen-owned model camera, animation player and bounded GPU atlas cache.
 * Neither preparing nor drawing a preview changes gameplay bindings or client readiness.
 */
public final class ModelPreview implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("MEPlayerActions/Preview");
    private static final int MAX_MODELS = 12, MAX_DIMENSION = 4096;
    private static final long MAX_ASSET_PIXELS = 16L * 1024 * 1024;
    private static final long MAX_CACHE_PIXELS = 32L * 1024 * 1024;
    private static final AtomicLong IDS = new AtomicLong();
    private static final RenderPipeline PREVIEW_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
                    .withLocation(Identifier.of("meplayeractions", "pipeline/gui_model_preview"))
                    .withCull(false).build());
    private final Map<String, Asset> assets = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, BbModel> failed = new LinkedHashMap<>();
    private final List<Asset> retired = new ArrayList<>();
    private final Set<Asset> usedThisFrame = new HashSet<>();
    private final AtomicLong emittedVertices = new AtomicLong();
    private DrawContext frame;
    private int frameModels, frameQuads;
    private final List<DrawSnapshot> draws = new ArrayList<>();

    public boolean render(DrawContext context, BbModel model, String key, int x, int y,
                          int width, int height, float yaw, float pitch, float tick) {
        return render(context, model, key, x, y, width, height, yaw, pitch, tick, Map.of());
    }

    /**
     * UI code supplies degrees (typically yaw=0, pitch=-10) and its animation clock.
     * Returns false for absent/invalid assets or a temporarily full cache; the caller
     * can display its loading/error placeholder. Dragging simply updates yaw/pitch.
     */
    public boolean render(DrawContext context, BbModel model, String key, int x, int y,
                          int width, int height, float yaw, float pitch, float tick,
                          Map<String, Double> parameters) {
        return render(context, model, key, x, y, width, height, yaw, pitch, tick, parameters, "");
    }

    public boolean render(DrawContext context, BbModel model, String key, int x, int y,
                          int width, int height, float yaw, float pitch, float tick,
                          Map<String, Double> parameters, String previewAnimation) {
        return render(context, model, key, x, y, width, height, yaw, pitch, tick, parameters, previewAnimation,
                YsmModelProfile.empty());
    }

    public boolean render(DrawContext context, BbModel model, String key, int x, int y,
                          int width, int height, float yaw, float pitch, float tick,
                          Map<String, Double> parameters, String previewAnimation, YsmModelProfile profile) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || context == null) return false;
        nextFrame(context);
        if (model == null || key == null || key.length() > 512
                || width <= 0 || height <= 0 || !Float.isFinite(tick) || tick < 0 || tick > 1e14
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || parameters == null || profile == null) return false;
        Asset asset = assets.get(key);
        if (asset != null && (asset.model != model || asset.profile != profile)) {
            assets.remove(key);
            retire(asset);
            asset = null;
        }
        if (asset == null) {
            if (failed.get(key) == model || !makeRoom(0, true)) return false;
            try {
                asset = prepare(model, previewAnimation, profile);
                assets.put(key, asset);
                failed.remove(key);
            } catch (CacheFullException full) {
                return false;
            } catch (Exception failure) {
                recordFailure(key, model);
                LOGGER.warn("Cannot prepare GUI model preview {}: {}", key, failure.toString());
                return false;
            }
        }
        try {
            PreviewScene.Sample sample = asset.sample(tick, parameters);
            List<PreviewMesh.Quad> modelQuads = PreviewMesh.entrance(PreviewMesh.project(sample.vertices(),
                    x, y, width, height, yaw, pitch, asset.settings), x, y, width, height, sample.entryProgress());
            if (modelQuads.isEmpty()) return false;
            List<PreviewMesh.Quad> quads = PreviewMesh.compose(modelQuads, x, y, width, height,
                    asset.rects.containsKey(-1), asset.rects.containsKey(-2));
            Matrix3x2f pose = new Matrix3x2f(context.getMatrices());
            ScreenRect viewport = new ScreenRect(x, y, width, height).transformEachVertex(pose);
            ScreenRect priorScissor = context.scissorStack.peekLast();
            ScreenRect scissor = priorScissor == null ? viewport : viewport.intersection(priorScissor);
            if (scissor == null || scissor.width() <= 0 || scissor.height() <= 0) return false;
            context.state.addSimpleElement(new MeshState(pose, quads, asset.rects,
                    TextureSetup.of(asset.texture.getGlTextureView(), asset.texture.getSampler()),
                    scissor, scissor, emittedVertices));
            usedThisFrame.add(asset);
            frameModels++; frameQuads += quads.size();
            if (draws.size() < 32) draws.add(new DrawSnapshot(key, x, y, width, height, sample, quads, asset.rects,
                    asset.settings, asset.settings.disableRotation() ? 0 : yaw, asset.settings.disableRotation() ? 0 : pitch));
            return true;
        } catch (Exception failure) {
            // Mark a broken asset once; its submitted texture remains alive through this frame.
            assets.remove(key);
            retire(asset);
            recordFailure(key, model);
            LOGGER.warn("Cannot draw GUI model preview {}: {}", key, failure.toString());
            return false;
        }
    }

    public static boolean rotationDisabled(YsmModelProfile profile) {
        return profile != null && PreviewMesh.settings(profile.properties()).disableRotation();
    }

    /** Release one asset; already submitted geometry keeps its texture until the next frame. */
    public void release(String key) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { client.execute(() -> release(key)); return; }
        Asset asset = assets.remove(key);
        failed.remove(key);
        if (asset != null) retire(asset);
    }

    /** Restart an already cached model only when the user selects it, never on ordinary draws. */
    public void restart(String key) {
        Asset asset = assets.get(key);
        if (asset != null) asset.scene.restart();
    }

    /** Screen.removed() should call this. The same instance may be reused on return. */
    public void clear() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { client.execute(this::clear); return; }
        assets.values().forEach(this::destroy);
        retired.forEach(this::destroy);
        assets.clear(); retired.clear(); failed.clear(); usedThisFrame.clear(); frame = null;
        frameModels = 0; frameQuads = 0;
        draws.clear();
    }

    @Override public void close() { clear(); }

    /** Call once at the start of a screen's render, including loading/empty frames. */
    public void beginFrame(DrawContext context) {
        if (context != null && MinecraftClient.getInstance().isOnThread()) nextFrame(context);
    }

    /** Counts real sampled mesh submissions and actual VertexConsumer writes, for diagnostics. */
    public Map<String, Object> diagnostics() {
        return Map.of("assets", assets.size(), "gpuPixels", pixels(), "frameModels", frameModels,
                "frameQuads", frameQuads, "frameVertices", frameQuads * 4,
                "emittedVertices", emittedVertices.get(), "draws", describeDraws(null));
    }

    /** Bounded inspection of genuinely submitted UV/geometry, selected by source UV bounds. */
    public Map<String, Object> diagnostics(float minU, float minV, float maxU, float maxV) {
        Map<String, Object> result = new LinkedHashMap<>(diagnostics());
        if (Float.isFinite(minU) && Float.isFinite(minV) && Float.isFinite(maxU) && Float.isFinite(maxV)
                && minU <= maxU && minV <= maxV)
            result.put("draws", describeDraws(new float[]{minU, minV, maxU, maxV}));
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> describeDraws(float[] region) {
        return draws.stream().map(draw -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("key", draw.key); info.put("x", draw.x); info.put("y", draw.y);
            info.put("width", draw.width); info.put("height", draw.height);
            info.put("sampleTick", draw.sample.age()); info.put("startedAtTick", draw.sample.startedAt());
            info.put("entryProgress", draw.sample.entryProgress()); info.put("startCount", draw.sample.startCount());
            info.put("layers", draw.sample.animations()); info.put("quads", draw.quads.size());
            info.put("vertices", draw.quads.size() * 4);
            info.put("noLighting", draw.settings.noLighting()); info.put("rotationDisabled", draw.settings.disableRotation());
            info.put("yaw", draw.yaw); info.put("pitch", draw.pitch);
            info.put("background", draw.rects.containsKey(-1) ? draw.settings.background() : "");
            info.put("foreground", draw.rects.containsKey(-2) ? draw.settings.foreground() : "");
            info.put("decorationQuads", draw.quads.stream().filter(quad -> quad.texture() < 0).count());
            info.put("decorations", draw.quads.stream().filter(quad -> quad.texture() < 0).limit(2).map(quad -> {
                PreviewAtlasUv rect = draw.rects.get(quad.texture());
                return Map.<String, Object>of("role", quad.texture() == -1 ? "background" : "foreground",
                        "order", draw.quads.indexOf(quad), "texture", quad.texture(), "color", quad.color(),
                        "atlas", List.of(rect.x(), rect.y(), rect.width(), rect.height(), rect.atlasWidth(), rect.atlasHeight()),
                        "atlasUv", quad.points().stream().map(point -> List.of(rect.u(point.u()), rect.v(point.v()))).toList(),
                        "points", quad.points().stream().map(point -> List.of(point.x(), point.y())).toList());
            }).toList());
            if (region != null) info.put("faces", draw.quads.stream().filter(quad -> quad.points().stream().allMatch(point ->
                    point.u() >= region[0] - 1e-6 && point.u() <= region[2] + 1e-6
                            && point.v() >= region[1] - 1e-6 && point.v() <= region[3] + 1e-6)).limit(12).map(quad -> {
                PreviewAtlasUv rect = draw.rects.get(quad.texture());
                return Map.<String, Object>of("sourceUv", quad.points().stream().map(point -> List.of(point.u(), point.v())).toList(),
                        "atlasUv", quad.points().stream().map(point -> List.of(rect.u(point.u()), rect.v(point.v()))).toList(),
                        "points", quad.points().stream().map(point -> List.of(point.x(), point.y())).toList(),
                        "atlas", List.of(rect.x(), rect.y(), rect.width(), rect.height(), rect.atlasWidth(), rect.atlasHeight()),
                        "texture", quad.texture(), "color", quad.color());
            }).toList());
            return Map.copyOf(info);
        }).toList();
    }

    private void nextFrame(DrawContext context) {
        if (context == frame) return;
        retired.forEach(this::destroy);
        retired.clear(); usedThisFrame.clear(); frame = context; frameModels = 0; frameQuads = 0;
        draws.clear();
    }

    private void recordFailure(String key, BbModel model) {
        failed.put(key, model);
        while (failed.size() > MAX_MODELS) failed.remove(failed.keySet().iterator().next());
    }

    private void retire(Asset asset) {
        if (usedThisFrame.contains(asset)) retired.add(asset);
        else destroy(asset);
    }

    private long pixels() {
        return assets.values().stream().mapToLong(a -> a.pixels).sum()
                + retired.stream().mapToLong(a -> a.pixels).sum();
    }

    private boolean makeRoom(long neededPixels, boolean needsSlot) {
        while ((needsSlot && assets.size() >= MAX_MODELS) || pixels() + neededPixels > MAX_CACHE_PIXELS) {
            var iterator = assets.entrySet().iterator();
            Asset victim = null;
            while (iterator.hasNext()) {
                Asset candidate = iterator.next().getValue();
                if (usedThisFrame.contains(candidate)) continue;
                victim = candidate; iterator.remove(); break;
            }
            if (victim == null) return false;
            destroy(victim);
        }
        return true;
    }

    private void destroy(Asset asset) {
        MinecraftClient.getInstance().getTextureManager().destroyTexture(asset.id);
        asset.scene.restart();
    }

    private Asset prepare(BbModel model, String previewAnimation, YsmModelProfile profile) throws IOException {
        List<Source> sources = new ArrayList<>();
        NativeImage atlas = null;
        NativeImageBackedTexture texture = null;
        boolean registered = false;
        Identifier id = Identifier.of("meplayeractions", "gui_preview/atlas_" + IDS.incrementAndGet());
        try {
            long pixels = 0;
            Set<Integer> indices = new HashSet<>();
            for (BbModel.Texture source : model.textures()) {
                if (!indices.add(source.index()) || sources.size() >= 16)
                    throw new IOException("Too many or duplicate preview textures");
                NativeImage image = decodePng(source.png());
                sources.add(new Source(source.index(), image, true));
                if (image.getWidth() <= 0 || image.getHeight() <= 0 || image.getWidth() > MAX_DIMENSION
                        || image.getHeight() > MAX_DIMENSION) throw new IOException("Preview texture dimensions");
                pixels += (long) image.getWidth() * image.getHeight();
                if (pixels > MAX_ASSET_PIXELS) throw new IOException("Preview texture pixel budget");
            }
            if (sources.isEmpty()) throw new IOException("Model has no preview texture");
            PreviewMesh.Settings settings = PreviewMesh.settings(profile.properties());
            for (var decoration : Map.of(-1, settings.background(), -2, settings.foreground()).entrySet()) {
                if (decoration.getValue().isEmpty()) continue;
                var png = profile.resourcePng(decoration.getValue());
                if (png.isEmpty()) continue;
                NativeImage image = decodePng(png.get()); sources.add(new Source(decoration.getKey(), image, true));
                pixels += (long) image.getWidth() * image.getHeight();
                if (pixels > MAX_ASSET_PIXELS) throw new IOException("Preview texture pixel budget");
            }
            Layout layout = pack(sources);
            if (!makeRoom(layout.pixels(), false)) throw new CacheFullException();
            atlas = new NativeImage(layout.width, layout.height, true);
            // Explicitly clear gaps: native allocation must not expose uninitialized pixels.
            atlas.fillRect(0, 0, layout.width, layout.height, 0);
            for (Placement placement : layout.placements) {
                NativeImage image = placement.source.image;
                // Duplicate boundary pixels into a gutter; source UVs keep their exact pixel boundaries.
                for (int row = -placement.padY(); row < image.getHeight() + placement.padY(); row++)
                    for (int column = -placement.padX(); column < image.getWidth() + placement.padX(); column++)
                        atlas.setColorArgb(placement.x + column, placement.y + row,
                                image.getColorArgb(Math.clamp(column, 0, image.getWidth() - 1),
                                        Math.clamp(row, 0, image.getHeight() - 1)));
            }
            texture = new NativeImageBackedTexture(() -> "MEPlayerActions GUI preview " + id, atlas);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            registered = true;
            Map<Integer, PreviewAtlasUv> rects = new LinkedHashMap<>();
            for (Placement p : layout.placements) rects.put(p.source.index, new PreviewAtlasUv(
                    p.x, p.y, p.source.image.getWidth(), p.source.image.getHeight(), layout.width, layout.height));
            return new Asset(model, id, texture, Map.copyOf(rects), layout.pixels(), previewAnimation, profile, settings);
        } catch (Exception failure) {
            if (registered) MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
            else if (texture != null) texture.close();
            else if (atlas != null) atlas.close();
            if (failure instanceof IOException io) throw io;
            throw new IOException("Preview preparation failed", failure);
        } finally {
            sources.forEach(source -> source.image.close());
        }
    }

    /** Validate dimensions before native allocation; all callers supply previously validated model/profile PNGs. */
    private static NativeImage decodePng(byte[] png) throws IOException {
        if (png.length < 24 || png.length > 8 * 1024 * 1024 || ByteBuffer.wrap(png).getLong() != 0x89504e470d0a1a0aL)
            throw new IOException("Preview resource must be a bounded PNG");
        int width = ByteBuffer.wrap(png, 16, 8).getInt(), height = ByteBuffer.wrap(png, 20, 4).getInt();
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION)
            throw new IOException("Preview resource dimensions");
        return NativeImage.read(png);
    }

    /** Shelf-pack up to 16 model textures plus two author GUI images into the smallest bounded atlas. */
    private static Layout pack(List<Source> sources) throws IOException {
        List<Source> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparingInt((Source source) -> source.image.getHeight()).reversed());
        int minimumWidth = sorted.stream().mapToInt(Source::paddedWidth).max().orElseThrow();
        Layout best = null;
        for (int atlasWidth = nextPowerOfTwo(minimumWidth); atlasWidth <= MAX_DIMENSION; atlasWidth *= 2) {
            List<Placement> placements = new ArrayList<>();
            int x = 0, y = 0, rowHeight = 0;
            for (Source source : sorted) {
                if (x + source.paddedWidth() > atlasWidth) { x = 0; y += rowHeight; rowHeight = 0; }
                placements.add(new Placement(source, x + source.padX(), y + source.padY()));
                x += source.paddedWidth(); rowHeight = Math.max(rowHeight, source.paddedHeight());
            }
            int atlasHeight = nextPowerOfTwo(y + rowHeight);
            Layout candidate = new Layout(atlasWidth, atlasHeight, List.copyOf(placements));
            if (atlasHeight <= MAX_DIMENSION && candidate.pixels() <= MAX_ASSET_PIXELS
                    && (best == null || candidate.pixels() < best.pixels())) best = candidate;
        }
        // Preserve the previous texture budget boundary for tightly packed large models.
        if (best == null && sources.stream().anyMatch(Source::gutter))
            return pack(sources.stream().map(source -> new Source(source.index, source.image, false)).toList());
        if (best == null) throw new IOException("Preview textures cannot fit a bounded atlas");
        return best;
    }

    private static int nextPowerOfTwo(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }
    private record Source(int index, NativeImage image, boolean gutter) {
        int padX() { return gutter && image.getWidth() <= MAX_DIMENSION - 2 ? 1 : 0; }
        int padY() { return gutter && image.getHeight() <= MAX_DIMENSION - 2 ? 1 : 0; }
        int paddedWidth() { return image.getWidth() + 2 * padX(); }
        int paddedHeight() { return image.getHeight() + 2 * padY(); }
    }
    private record Placement(Source source, int x, int y) {
        int padX() { return source.padX(); }
        int padY() { return source.padY(); }
    }
    private record Layout(int width, int height, List<Placement> placements) {
        long pixels() { return (long) width * height; }
    }
    private static final class CacheFullException extends IOException { }

    private static final class Asset {
        final BbModel model;
        final Identifier id;
        final NativeImageBackedTexture texture;
        final Map<Integer, PreviewAtlasUv> rects;
        final long pixels;
        final PreviewScene scene;
        final YsmModelProfile profile;
        final PreviewMesh.Settings settings;

        Asset(BbModel model, Identifier id, NativeImageBackedTexture texture,
              Map<Integer, PreviewAtlasUv> rects, long pixels, String previewAnimation,
              YsmModelProfile profile, PreviewMesh.Settings settings) {
            this.model = model; this.id = id; this.texture = texture; this.rects = rects; this.pixels = pixels;
            this.profile = profile; this.settings = settings;
            scene = new PreviewScene(model, previewAnimation);
        }

        PreviewScene.Sample sample(float tick, Map<String, Double> parameters) {
            PreviewScene.Sample sample = scene.sample(tick, parameters);
            for (BbModel.Vertex vertex : sample.vertices()) if (!rects.containsKey(vertex.texture()))
                throw new IllegalArgumentException("Missing preview texture reference");
            return sample;
        }
    }

    private record DrawSnapshot(String key, int x, int y, int width, int height, PreviewScene.Sample sample,
                                List<PreviewMesh.Quad> quads, Map<Integer, PreviewAtlasUv> rects, PreviewMesh.Settings settings,
                                float yaw, float pitch) { }

    private record MeshState(Matrix3x2f pose, List<PreviewMesh.Quad> quads, Map<Integer, PreviewAtlasUv> textures,
                             TextureSetup textureSetup, ScreenRect scissorArea, ScreenRect bounds,
                             AtomicLong emittedVertices)
            implements SimpleGuiElementRenderState {
        @Override public RenderPipeline pipeline() { return PREVIEW_PIPELINE; }

        @Override public void setupVertices(VertexConsumer consumer) {
            for (PreviewMesh.Quad quad : quads) {
                PreviewAtlasUv rect = textures.get(quad.texture());
                for (PreviewMesh.Point point : quad.points())
                    consumer.vertex(pose, point.x(), point.y()).texture(rect.u(point.u()), rect.v(point.v()))
                            .color(quad.color());
            }
            emittedVertices.addAndGet((long) quads.size() * 4);
        }
    }
}
