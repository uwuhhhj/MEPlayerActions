package com.simmc.meplayeractions.client.ui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.BbModel;
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || context == null) return false;
        nextFrame(context);
        if (model == null || key == null || key.length() > 512
                || width <= 0 || height <= 0 || !Float.isFinite(tick) || tick < 0 || tick > 1e14
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || parameters == null) return false;
        Asset asset = assets.get(key);
        if (asset != null && asset.model != model) {
            assets.remove(key);
            retire(asset);
            asset = null;
        }
        if (asset == null) {
            if (failed.get(key) == model || !makeRoom(0, true)) return false;
            try {
                asset = prepare(model);
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
            List<PreviewMesh.Quad> quads = PreviewMesh.project(asset.sample(tick, parameters),
                    x, y, width, height, yaw, pitch);
            if (quads.isEmpty()) return false;
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

    /** Release one asset; already submitted geometry keeps its texture until the next frame. */
    public void release(String key) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { client.execute(() -> release(key)); return; }
        Asset asset = assets.remove(key);
        failed.remove(key);
        if (asset != null) retire(asset);
    }

    /** Screen.removed() should call this. The same instance may be reused on return. */
    public void clear() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { client.execute(this::clear); return; }
        assets.values().forEach(this::destroy);
        retired.forEach(this::destroy);
        assets.clear(); retired.clear(); failed.clear(); usedThisFrame.clear(); frame = null;
        frameModels = 0; frameQuads = 0;
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
                "emittedVertices", emittedVertices.get());
    }

    private void nextFrame(DrawContext context) {
        if (context == frame) return;
        retired.forEach(this::destroy);
        retired.clear(); usedThisFrame.clear(); frame = context; frameModels = 0; frameQuads = 0;
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
        asset.player.reset();
    }

    private Asset prepare(BbModel model) throws IOException {
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
                NativeImage image = NativeImage.read(source.png());
                sources.add(new Source(source.index(), image));
                if (image.getWidth() <= 0 || image.getHeight() <= 0 || image.getWidth() > MAX_DIMENSION
                        || image.getHeight() > MAX_DIMENSION) throw new IOException("Preview texture dimensions");
                pixels += (long) image.getWidth() * image.getHeight();
                if (pixels > MAX_ASSET_PIXELS) throw new IOException("Preview texture pixel budget");
            }
            if (sources.isEmpty()) throw new IOException("Model has no preview texture");
            Layout layout = pack(sources);
            if (!makeRoom(layout.pixels(), false)) throw new CacheFullException();
            atlas = new NativeImage(layout.width, layout.height, true);
            // Explicitly clear gaps: native allocation must not expose uninitialized pixels.
            atlas.fillRect(0, 0, layout.width, layout.height, 0);
            for (Placement placement : layout.placements) {
                NativeImage image = placement.source.image;
                for (int row = 0; row < image.getHeight(); row++)
                    for (int column = 0; column < image.getWidth(); column++)
                        atlas.setColorArgb(placement.x + column, placement.y + row, image.getColorArgb(column, row));
            }
            texture = new NativeImageBackedTexture(() -> "MEPlayerActions GUI preview " + id, atlas);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            registered = true;
            Map<Integer, TextureRect> rects = new LinkedHashMap<>();
            for (Placement p : layout.placements) rects.put(p.source.index, new TextureRect(
                    p.x, p.y, p.source.image.getWidth(), p.source.image.getHeight(), layout.width, layout.height));
            return new Asset(model, id, texture, Map.copyOf(rects), layout.pixels());
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

    /** Shelf-pack up to 16 validated textures, choosing the smallest bounded atlas. */
    private static Layout pack(List<Source> sources) throws IOException {
        List<Source> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparingInt((Source source) -> source.image.getHeight()).reversed());
        int minimumWidth = sorted.stream().mapToInt(source -> source.image.getWidth()).max().orElseThrow();
        Layout best = null;
        for (int atlasWidth = nextPowerOfTwo(minimumWidth); atlasWidth <= MAX_DIMENSION; atlasWidth *= 2) {
            List<Placement> placements = new ArrayList<>();
            int x = 0, y = 0, rowHeight = 0;
            for (Source source : sorted) {
                if (x + source.image.getWidth() > atlasWidth) { x = 0; y += rowHeight; rowHeight = 0; }
                placements.add(new Placement(source, x, y));
                x += source.image.getWidth(); rowHeight = Math.max(rowHeight, source.image.getHeight());
            }
            int atlasHeight = nextPowerOfTwo(y + rowHeight);
            Layout candidate = new Layout(atlasWidth, atlasHeight, List.copyOf(placements));
            if (atlasHeight <= MAX_DIMENSION && candidate.pixels() <= MAX_ASSET_PIXELS
                    && (best == null || candidate.pixels() < best.pixels())) best = candidate;
        }
        if (best == null) throw new IOException("Preview textures cannot fit a bounded atlas");
        return best;
    }

    private static int nextPowerOfTwo(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }
    private record Source(int index, NativeImage image) { }
    private record Placement(Source source, int x, int y) { }
    private record Layout(int width, int height, List<Placement> placements) {
        long pixels() { return (long) width * height; }
    }
    private record TextureRect(int x, int y, int width, int height, int atlasWidth, int atlasHeight) {
        float u(float value) { return (x + .5f + Math.clamp(value, 0f, 1f) * (width - 1)) / atlasWidth; }
        float v(float value) { return (y + .5f + Math.clamp(value, 0f, 1f) * (height - 1)) / atlasHeight; }
    }
    private static final class CacheFullException extends IOException { }

    private static final class Asset {
        final BbModel model;
        final Identifier id;
        final NativeImageBackedTexture texture;
        final Map<Integer, TextureRect> rects;
        final long pixels;
        final AnimationPlayer player;
        final List<BbModel.Layer> idle;
        float lastTick = Float.NaN;
        Map<String, Double> lastParameters = Map.of();
        List<BbModel.Vertex> sampled = List.of();

        Asset(BbModel model, Identifier id, NativeImageBackedTexture texture,
              Map<Integer, TextureRect> rects, long pixels) {
            this.model = model; this.id = id; this.texture = texture; this.rects = rects; this.pixels = pixels;
            player = new AnimationPlayer(model);
            idle = model.animations().contains("idle")
                    ? List.of(new BbModel.Layer("posture", "idle", 0, 1, "LOOP", 0, 0)) : List.of();
        }

        List<BbModel.Vertex> sample(float tick, Map<String, Double> parameters) {
            if (tick != lastTick || !lastParameters.equals(parameters)) {
                sampled = player.sample(tick, idle, 0, 0, Map.of(), Map.of(), parameters);
                for (BbModel.Vertex vertex : sampled) if (!rects.containsKey(vertex.texture()))
                    throw new IllegalArgumentException("Missing preview texture reference");
                lastTick = tick; lastParameters = Map.copyOf(parameters);
            }
            return sampled;
        }
    }

    private record MeshState(Matrix3x2f pose, List<PreviewMesh.Quad> quads, Map<Integer, TextureRect> textures,
                             TextureSetup textureSetup, ScreenRect scissorArea, ScreenRect bounds,
                             AtomicLong emittedVertices)
            implements SimpleGuiElementRenderState {
        @Override public RenderPipeline pipeline() { return PREVIEW_PIPELINE; }

        @Override public void setupVertices(VertexConsumer consumer) {
            for (PreviewMesh.Quad quad : quads) {
                TextureRect rect = textures.get(quad.texture());
                for (PreviewMesh.Point point : quad.points())
                    consumer.vertex(pose, point.x(), point.y()).texture(rect.u(point.u()), rect.v(point.v()))
                            .color(quad.color());
            }
            emittedVertices.addAndGet((long) quads.size() * 4);
        }
    }
}
