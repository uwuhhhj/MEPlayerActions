package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.MEPlayerActionsClient;
import com.simmc.meplayeractions.client.VanillaYsmQueries;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.model.YsmRenderScale;
import com.simmc.meplayeractions.client.render.YsmEquipmentRenderer;
import com.simmc.meplayeractions.client.render.YsmItemRenderer;
import com.simmc.meplayeractions.client.render.YsmPlayerSkin;
import com.simmc.meplayeractions.client.render.TexturePreparation;
import com.simmc.meplayeractions.client.render.ModelRenderer;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A screen-owned model camera, animation player and bounded GPU atlas cache.
 * Neither preparing nor drawing a preview changes gameplay bindings or client readiness.
 */
public final class ModelPreview implements AutoCloseable {
    public enum Context {
        OWNER, CARD, SELECTED
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("MEPlayerActions/Preview");
    private static final int MAX_MODELS = 12, MAX_DIMENSION = 4096;
    private static final long MAX_ASSET_PIXELS = 16L * 1024 * 1024;
    private static final long MAX_CACHE_PIXELS = 32L * 1024 * 1024;
    private static final AtomicLong IDS = new AtomicLong();
    private static final Set<ModelPreview> INSTANCES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final RenderPipeline PREVIEW_PIPELINE = RenderPipelines.register(RenderPipeline
            .builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("meplayeractions", "pipeline/gui_model_preview")).withCull(false).build());
    private final Map<String, Asset> assets = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, BbModel> failed = new LinkedHashMap<>();
    private final Map<String, PreviewRequest> pending = new LinkedHashMap<>();
    private final List<Asset> retired = new ArrayList<>();
    private final Set<Asset> usedThisFrame = new HashSet<>();
    private final AtomicLong emittedVertices = new AtomicLong();
    private final ClientRuntime runtime;
    private DrawContext frame;
    private int frameModels, frameQuads;
    private final List<DrawSnapshot> draws = new ArrayList<>();

    public ModelPreview() {
        this(MEPlayerActionsClient.runtime);
    }

    public ModelPreview(ClientRuntime runtime) {
        this.runtime = runtime;
        INSTANCES.add(this);
    }

    public static void resourcesReloaded() {
        List.copyOf(INSTANCES).forEach(ModelPreview::clear);
    }

    public boolean render(DrawContext context, BbModel model, String key, int x, int y, int width, int height,
            float yaw, float pitch, float tick) {
        return render(context, model, key, x, y, width, height, yaw, pitch, tick, Map.of());
    }

    /**
     * UI code supplies degrees (typically yaw=0, pitch=-10) and its animation clock.
     * Returns false for absent/invalid assets or a temporarily full cache; the caller
     * can display its loading/error placeholder. Dragging simply updates yaw/pitch.
     */
    public boolean render(DrawContext context, BbModel model, String key, int x, int y, int width, int height,
            float yaw, float pitch, float tick, Map<String, Double> parameters) {
        return render(context, model, key, x, y, width, height, yaw, pitch, tick, parameters, "");
    }

    public boolean render(DrawContext context, BbModel model, String key, int x, int y, int width, int height,
            float yaw, float pitch, float tick, Map<String, Double> parameters, String previewAnimation) {
        return render(context, model, key, x, y, width, height, yaw, pitch, tick, parameters, previewAnimation,
                YsmModelProfile.empty());
    }

    public boolean render(DrawContext context, BbModel model, String key, int x, int y, int width, int height,
            float yaw, float pitch, float tick, Map<String, Double> parameters, String previewAnimation,
            YsmModelProfile profile) {
        return render(context, model, key, x, y, width, height, yaw, pitch, tick, parameters, previewAnimation, profile,
                Context.OWNER);
    }

    public boolean render(DrawContext context, BbModel model, String key, int x, int y, int width, int height,
            float yaw, float pitch, float tick, Map<String, Double> parameters, String previewAnimation,
            YsmModelProfile profile, Context previewContext) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || context == null) {
            return false;
        }

        nextFrame(context);
        if (model == null || key == null || key.length() > 512 || width <= 0 || height <= 0 || !Float.isFinite(tick)
                || tick < 0 || tick > 1e14 || !Float.isFinite(yaw) || !Float.isFinite(pitch) || parameters == null
                || profile == null || previewContext == null) {
            return false;
        }

        Asset asset = this.assets.get(key);
        if (asset != null && (asset.model != model || asset.profile != profile)) {
            this.assets.remove(key);
            retire(asset);
            asset = null;
        }
        if (asset == null) {
            if (this.failed.get(key) == model) {
                return false;
            }
            PreviewRequest request = this.pending.get(key);
            if (request != null && (request.model != model || request.profile != profile)) {
                this.pending.remove(key);
                TexturePreparation.SHARED.cancel(request);
                request = null;
            }
            if (request == null && this.pending.size() < MAX_MODELS) {
                PreviewRequest fresh = new PreviewRequest(model, profile);
                if (TexturePreparation.SHARED.submit(fresh, () -> this.pending.get(key) == fresh,
                        memory -> prepare(key, model, previewAnimation, profile, memory), (success, reason) -> {
                            if (!this.pending.remove(key, fresh)) {
                                return;
                            }
                            if (success) {
                                this.failed.remove(key);
                            } else if (!reason.equals("cancelled")) {
                                recordFailure(key, model);
                                LOGGER.warn("Cannot prepare GUI model preview {}: {}", key, reason);
                            }
                        })) {
                    this.pending.put(key, fresh);
                }
            }
            return false;
        }
        try {
            boolean nativeCamera = NativeGuiPreviewCamera.appliesTo(profile);
            boolean entityPreview = previewContext != Context.CARD;
            ClientRuntime.GuiPreviewInput nativeInput = nativeCamera && entityPreview
                    && this.runtime != null ? this.runtime.guiPreviewInput() : null;
            PreviewScene.NativeInputs sceneInput = nativeCamera && entityPreview
                    && this.runtime != null
                            ? new PreviewScene.NativeInputs(nativeInput.sample(), Map.of(),
                                    ctx -> this.runtime.configureGuiPreviewExpressionContext(ctx,
                                            profile.selectedTexture()))
                            : PreviewScene.NativeInputs.EMPTY;
            if (nativeCamera && !entityPreview) {
                OtherClientPlayerEntity dummy = asset.previewDummy(client.world, previewContext);
                sceneInput = PreviewScene.NativeInputs.card(environment -> VanillaYsmQueries.populate(client, dummy, 20,
                        profile.selectedTexture(), environment));
            }
            PreviewScene.Sample sample = asset.sample(previewContext, tick, parameters, sceneInput);
            NativeGuiPreviewCamera.Camera camera = null;
            if (nativeCamera) {
                float nativeHeight = nativeInput == null ? 1.8f : nativeInput.height();
                float nativeScale = nativeInput == null ? 1f : nativeInput.scale();
                camera = NativeGuiPreviewCamera.select(previewContext == Context.CARD, nativeHeight, nativeScale, yaw,
                        pitch, asset.settings.disableRotation()).withModelScale(profile.properties());
            }
            PreviewMesh.Projection boundsProjection = camera == null
                    ? PreviewMesh.projectBounds(sample.vertices(), x, y, width, height, yaw, pitch, asset.settings)
                    : null;
            List<PreviewMesh.Quad> projected = boundsProjection == null
                    ? PreviewMesh.project(sample.vertices(), x, y, width, height, asset.settings, camera)
                    : boundsProjection.quads();
            float pixelsPerBlock = camera == null ? boundsProjection.pixelsPerBlock() : camera.pixelsPerBlock();
            // Native previews already have the author's/native player clock and fixed source camera.
            List<PreviewMesh.Quad> modelQuads = nativeCamera ? projected
                    : PreviewMesh.entrance(projected, x, y, width, height, sample.entryProgress());
            if (modelQuads.isEmpty()) {
                return false;
            }

            boolean nativeCard = nativeCamera && previewContext == Context.CARD;
            boolean decorate = !nativeCamera || previewContext == Context.CARD;
            // OpenYSM ModelButton paints its author's images over the full 52×90
            // widget, but renders the entity in a 76px viewport clipped to 70px.
            int decorationHeight = nativeCard ? 90 : height;
            int modelClipHeight = nativeCard ? 70 : height;
            List<PreviewMesh.Quad> quads = PreviewMesh.compose(modelQuads, x, y, width, decorationHeight,
                    decorate && asset.rects.containsKey(-1), decorate && asset.rects.containsKey(-2));
            Matrix3x2f pose = new Matrix3x2f(context.getMatrices());
            ScreenRect viewport = new ScreenRect(x, y, width, modelClipHeight).transformEachVertex(pose);
            ScreenRect decorationViewport = new ScreenRect(x, y, width, decorationHeight).transformEachVertex(pose);
            ScreenRect priorScissor = context.scissorStack.peekLast();
            ScreenRect scissor = priorScissor == null ? viewport : viewport.intersection(priorScissor);
            ScreenRect decorationScissor = priorScissor == null ? decorationViewport
                    : decorationViewport.intersection(priorScissor);
            if (scissor == null || scissor.width() <= 0 || scissor.height() <= 0) {
                return false;
            }

            TextureSetup textureSetup = TextureSetup.of(asset.texture.getGlTextureView(), asset.texture.getSampler());
            // OpenYSM submits GUI players to Minecraft's 3D entity renderer. Both YSM
            // and server bbmodel meshes need its per-pixel depth path; a 2D face sort
            // loses the thin eye planes even though their real depth is already available.
            List<PreviewMesh.Quad> background = quads.stream().filter(quad -> quad.texture() == -1).toList();
            List<PreviewMesh.Quad> foreground = quads.stream().filter(quad -> quad.texture() == -2).toList();
            if (!background.isEmpty() && decorationScissor != null) {
                context.state.addSimpleElement(new MeshState(pose, background, asset.rects, textureSetup,
                        decorationScissor, decorationScissor, this.emittedVertices));
                context.state.goUpLayer();
            }
            NativeGuiRenderBackend.Evidence nativeExecution = new NativeGuiRenderBackend.Evidence();
            NativeGuiRenderBackend.Attachments attachments = NativeGuiRenderBackend.Attachments.EMPTY;
            if (camera != null && entityPreview && client.player != null) {
                try {
                    PreviewScene scene = asset.scenes.get(previewContext);
                    List<YsmItemRenderer.Attachment> items = YsmItemRenderer.extract(client.player,
                            scene.animationPlayer(), profile.isImportedBbModel());
                    List<YsmEquipmentRenderer.Attachment> equipment = this.runtime != null
                            && (this.runtime.options.hideVanillaEquipment
                                    || this.runtime.isServerDisguised(client.player.getUuid()))
                                            ? List.<YsmEquipmentRenderer.Attachment>of()
                                            : YsmEquipmentRenderer.extract(client.player, scene.animationPlayer());
                    attachments = new NativeGuiRenderBackend.Attachments(camera.projectedBodyMatrix(), items,
                            equipment);
                } catch (RuntimeException failure) {
                    LOGGER.debug("Cannot extract optional GUI attachments for {}: {}", key, failure.toString());
                }
            }
            int hashSeparator = key.lastIndexOf(':');
            String sourceId = hashSeparator < 0 ? key : key.substring(0, hashSeparator);
            Identifier skin = profile.isYsm()
                    ? YsmPlayerSkin.resolve(sourceId, entityPreview ? client.player : null)
                    : null;
            context.state.addSpecialElement(new NativeGuiRenderBackend.State(x, y, x + width, y + height, pose, scissor,
                    scissor, modelQuads, asset.rects, asset.id, asset.texture.getSampler(), pixelsPerBlock,
                    this.emittedVertices, nativeExecution, attachments, skin == null ? Map.of() : Map.of(0, skin)));
            if (!foreground.isEmpty() && decorationScissor != null) {
                context.state.goUpLayer();
                context.state.addSimpleElement(new MeshState(pose, foreground, asset.rects, textureSetup,
                        decorationScissor, decorationScissor, this.emittedVertices));
            }
            // ModelButton draws its label/hover/favorite after the foreground image.
            context.state.goUpLayer();
            this.usedThisFrame.add(asset);
            this.frameModels++;
            this.frameQuads += quads.size();
            if (this.draws.size() < 32) {
                this.draws.add(new DrawSnapshot(key, x, y, width, height, sample, quads, asset.rects, asset.settings,
                        camera == null ? (asset.settings.disableRotation() ? 0 : yaw) : camera.yaw(),
                        camera == null ? (asset.settings.disableRotation() ? 0 : pitch) : camera.pitch(),
                        previewContext, camera, scissor, nativeExecution));
            }

            return true;
        } catch (Exception failure) {
            // Mark a broken asset once; its submitted texture remains alive through this frame.
            this.assets.remove(key);
            retire(asset);
            recordFailure(key, model);
            LOGGER.warn("Cannot draw GUI model preview {}: {}", key, failure.toString());
            return false;
        }
    }

    public static boolean rotationDisabled(YsmModelProfile profile) {
        // This is the interactive owner preview; the author's fixed-view flag belongs to dummy cards.
        return profile != null && !NativeGuiPreviewCamera.appliesTo(profile)
                && PreviewMesh.settings(profile.properties()).disableRotation();
    }

    /** Release one asset; already submitted geometry keeps its texture until the next frame. */
    public void release(String key) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(() -> release(key));
            return;
        }
        Asset asset = this.assets.remove(key);
        PreviewRequest request = this.pending.remove(key);
        if (request != null) {
            TexturePreparation.SHARED.cancel(request);
        }
        this.failed.remove(key);
        if (asset != null) {
            retire(asset);
        }

    }

    /** Restart an already cached model only when the user selects it, never on ordinary draws. */
    public void restart(String key) {
        restart(key, Context.OWNER);
    }

    public void restart(String key, Context previewContext) {
        Asset asset = this.assets.get(key);
        if (asset != null && asset.scenes.containsKey(previewContext)) {
            asset.scenes.get(previewContext).restart();
        }

    }

    /** Screen.removed() should call this. The same instance may be reused on return. */
    public void clear() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(this::clear);
            return;
        }
        this.assets.values().forEach(this::destroy);
        this.pending.values().forEach(TexturePreparation.SHARED::cancel);
        this.pending.clear();
        this.retired.forEach(this::destroy);
        this.assets.clear();
        this.retired.clear();
        this.failed.clear();
        this.usedThisFrame.clear();
        this.frame = null;
        this.frameModels = 0;
        this.frameQuads = 0;
        this.draws.clear();
    }

    @Override
    public void close() {
        clear();
    }

    /** Call once at the start of a screen's render, including loading/empty frames. */
    public void beginFrame(DrawContext context) {
        if (context != null && MinecraftClient.getInstance().isOnThread()) {
            nextFrame(context);
        }

    }

    /** Counts real sampled mesh submissions and actual VertexConsumer writes, for diagnostics. */
    public Map<String, Object> diagnostics() {
        return Map.of("assets", this.assets.size(), "gpuPixels", pixels(), "frameModels", this.frameModels,
                "frameQuads", this.frameQuads, "frameVertices", this.frameQuads * 4, "emittedVertices",
                this.emittedVertices.get(), "draws", describeDraws(null));
    }

    /** Bounded inspection of genuinely submitted UV/geometry, selected by source UV bounds. */
    public Map<String, Object> diagnostics(float minU, float minV, float maxU, float maxV) {
        Map<String, Object> result = new LinkedHashMap<>(diagnostics());
        if (Float.isFinite(minU) && Float.isFinite(minV) && Float.isFinite(maxU) && Float.isFinite(maxV) && minU <= maxU
                && minV <= maxV) {
            result.put("draws", describeDraws(new float[] { minU, minV, maxU, maxV }));
        }

        return Map.copyOf(result);
    }

    private List<Map<String, Object>> describeDraws(float[] region) {
        return this.draws.stream().map(draw -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("key", draw.key);
            info.put("x", draw.x);
            info.put("y", draw.y);
            info.put("context", draw.previewContext.name());
            Asset asset = this.assets.get(draw.key);
            PreviewScene scene = asset == null ? null : asset.scenes.get(draw.previewContext);
            info.put("queryFallbacks", scene == null ? List.of() : scene.queryDiagnostics());
            info.put("cameraSource", draw.camera == null ? "bbmodel-bounds-fit" : draw.camera.source());
            info.put("nativeCamera", draw.camera != null);
            if (draw.camera != null) {
                info.put("displaySize", draw.camera.displaySize());
                info.put("pixelsPerBlock", draw.camera.pixelsPerBlock());
                info.put("nativeEntityHeight", draw.camera.nativeHeight());
                info.put("nativeEntityScale", draw.camera.entityScale());
                info.put("anchorY", draw.camera.translationY());
                YsmRenderScale scale = draw.camera.modelScale();
                info.put("modelScale", Map.of("x", scale.x(), "y", scale.y(), "z", scale.z()));
            } else {
                info.put("backend", "bbmodel-native-gui-depth");
            }
            info.put("nativeBackend", NativeGuiRenderBackend.pipelineDiagnostics());
            info.put("nativeExecution", draw.nativeExecution.diagnostics());
            info.put("modelVertexDepthRange", List.of(
                    draw.quads.stream().filter(quad -> quad.texture() >= 0).flatMap(quad -> quad.points().stream())
                            .mapToDouble(PreviewMesh.Point::depth).min().orElse(0),
                    draw.quads.stream().filter(quad -> quad.texture() >= 0).flatMap(quad -> quad.points().stream())
                            .mapToDouble(PreviewMesh.Point::depth).max().orElse(0)));
            info.put("width", draw.width);
            info.put("height", draw.height);
            info.put("clip", Map.of("x", draw.scissor.getLeft(), "y", draw.scissor.getTop(), "width",
                    draw.scissor.width(), "height", draw.scissor.height()));
            info.put("sampleTick", draw.sample.age());
            info.put("startedAtTick", draw.sample.startedAt());
            info.put("entryProgress", draw.sample.entryProgress());
            info.put("startCount", draw.sample.startCount());
            info.put("layers", draw.sample.animations());
            info.put("quads", draw.quads.size());
            info.put("controllerSlots", draw.sample.slots());
            info.put("layerRequests",
                    draw.sample
                            .layers().stream().map(layer -> Map.<String, Object>of("layer", layer.layer(), "animation",
                                    layer.animation(), "loop", layer.loop(), "startedAtTick", layer.startedAtTick()))
                            .toList());
            info.put("vertices", draw.quads.size() * 4);
            info.put("noLighting", draw.settings.noLighting());
            info.put("rotationDisabled",
                    draw.camera == null ? draw.settings.disableRotation() : draw.camera.rotationDisabled());
            info.put("yaw", draw.yaw);
            info.put("pitch", draw.pitch);
            info.put("background",
                    draw.quads.stream().anyMatch(quad -> quad.texture() == -1) ? draw.settings.background() : "");
            info.put("foreground",
                    draw.quads.stream().anyMatch(quad -> quad.texture() == -2) ? draw.settings.foreground() : "");
            info.put("decorationQuads", draw.quads.stream().filter(quad -> quad.texture() < 0).count());
            info.put("decorations", draw.quads.stream().filter(quad -> quad.texture() < 0).limit(2).map(quad -> {
                PreviewAtlasUv rect = draw.rects.get(quad.texture());
                return Map.<String, Object>of("role", quad.texture() == -1 ? "background" : "foreground", "order",
                        draw.quads.indexOf(quad), "texture", quad.texture(), "color", quad.color(), "atlas",
                        List.of(rect.x(), rect.y(), rect.width(), rect.height(), rect.atlasWidth(), rect.atlasHeight()),
                        "atlasUv",
                        quad.points().stream().map(point -> List.of(rect.u(point.u()), rect.v(point.v()))).toList(),
                        "points", quad.points().stream().map(point -> List.of(point.x(), point.y())).toList());
            }).toList());
            if (region != null) {
                info.put("faces",
                        draw.quads.stream().filter(quad -> quad.points().stream()
                                .allMatch(point -> point.u() >= region[0] - 1e-6 && point.u() <= region[2] + 1e-6
                                        && point.v() >= region[1] - 1e-6 && point.v() <= region[3] + 1e-6))
                                .limit(12).map(quad -> {
                                    PreviewAtlasUv rect = draw.rects.get(quad.texture());
                                    return Map.<String, Object>of("sourceUv",
                                            quad.points().stream().map(point -> List.of(point.u(), point.v())).toList(),
                                            "atlasUv",
                                            quad.points().stream()
                                                    .map(point -> List.of(rect.u(point.u()), rect.v(point.v())))
                                                    .toList(),
                                            "points",
                                            quad.points().stream().map(point -> List.of(point.x(), point.y())).toList(),
                                            "depths", quad.points().stream().map(PreviewMesh.Point::depth).toList(),
                                            "atlas",
                                            List.of(rect.x(), rect.y(), rect.width(), rect.height(), rect.atlasWidth(),
                                                    rect.atlasHeight()),
                                            "texture", quad.texture(), "color", quad.color());
                                }).toList());
            }

            return Map.copyOf(info);
        }).toList();
    }

    private void nextFrame(DrawContext context) {
        if (context == this.frame) {
            return;
        }

        this.retired.forEach(this::destroy);
        this.retired.clear();
        this.usedThisFrame.clear();
        this.frame = context;
        this.frameModels = 0;
        this.frameQuads = 0;
        this.draws.clear();
    }

    private void recordFailure(String key, BbModel model) {
        this.failed.put(key, model);
        while (this.failed.size() > MAX_MODELS) {
            this.failed.remove(this.failed.keySet().iterator().next());
        }

    }

    private void retire(Asset asset) {
        if (this.usedThisFrame.contains(asset)) {
            this.retired.add(asset);
        }

        else {
            destroy(asset);
        }

    }

    private long pixels() {
        return this.assets.values().stream().mapToLong(a -> a.pixels).sum()
                + this.retired.stream().mapToLong(a -> a.pixels).sum();
    }

    private boolean makeRoom(long neededPixels, boolean needsSlot) {
        while ((needsSlot && this.assets.size() >= MAX_MODELS) || pixels() + neededPixels > MAX_CACHE_PIXELS) {
            Iterator<Map.Entry<String, ModelPreview.Asset>> iterator = this.assets.entrySet().iterator();
            Asset victim = null;
            while (iterator.hasNext()) {
                Asset candidate = iterator.next().getValue();
                if (this.usedThisFrame.contains(candidate)) {
                    continue;
                }

                victim = candidate;
                iterator.remove();
                break;
            }
            if (victim == null) {
                return false;
            }

            destroy(victim);
        }
        return true;
    }

    private void destroy(Asset asset) {
        MinecraftClient.getInstance().getTextureManager().destroyTexture(asset.id);
        asset.scenes.values().forEach(PreviewScene::dispose);
    }

    private TexturePreparation.Prepared prepare(String key, BbModel model, String previewAnimation, YsmModelProfile profile,
            TexturePreparation.Memory memory) throws Exception {
        List<Source> sources = new ArrayList<>();
        NativeImage atlas = null;
        boolean handedOff = false;
        try {
            Map<Integer, byte[]> pngs = new LinkedHashMap<>();
            long sourcePixels = 0;
            for (BbModel.Texture source : model.textures()) {
                byte[] png = source.png();
                if (pngs.put(source.index(), png) != null || pngs.size() > 16) {
                    throw new IOException("Too many or duplicate preview textures");
                }
                sourcePixels += ModelRenderer.pngPixels(png);
            }
            PreviewMesh.Settings settings = PreviewMesh.settings(profile.properties());
            for (Map.Entry<Integer, String> decoration : Map.of(-1, settings.background(), -2, settings.foreground())
                    .entrySet()) {
                if (!decoration.getValue().isEmpty()) {
                    Optional<byte[]> png = profile.resourcePng(decoration.getValue());
                    if (png.isPresent()) {
                        sourcePixels += ModelRenderer.pngPixels(png.get());
                        pngs.put(decoration.getKey(), png.get());
                    }
                }
            }
            if (sourcePixels > MAX_ASSET_PIXELS || pngs.isEmpty()) {
                throw new IOException("Preview texture pixel budget or empty model");
            }
            List<BbModel.Vertex> basis = model.basisVertices();
            if (basis.isEmpty() || basis.size() % 4 != 0 || basis.stream().anyMatch(vertex ->
                    !pngs.containsKey(vertex.texture()) || !Float.isFinite(vertex.x()) || !Float.isFinite(vertex.y())
                            || !Float.isFinite(vertex.z()) || !Float.isFinite(vertex.u()) || !Float.isFinite(vertex.v())
                            || !Float.isFinite(vertex.nx()) || !Float.isFinite(vertex.ny())
                            || !Float.isFinite(vertex.nz()))) {
                throw new IOException("Invalid preview geometry or texture reference");
            }
            // Reserve sources plus the largest permitted atlas before native allocation.
            memory.reserve((sourcePixels + MAX_ASSET_PIXELS) * 4);
            long pixels = 0;
            Set<Integer> indices = new HashSet<>();
            for (Map.Entry<Integer, byte[]> source : pngs.entrySet()) {
                if (memory.cancelled()) {
                    throw new InterruptedException("Preview preparation cancelled");
                }
                if (!indices.add(source.getKey()) || sources.size() >= 18) {
                    throw new IOException("Too many or duplicate preview textures");
                }

                NativeImage image = decodePng(source.getValue());
                sources.add(new Source(source.getKey(), image, true));
                if (image.getWidth() <= 0 || image.getHeight() <= 0 || image.getWidth() > MAX_DIMENSION
                        || image.getHeight() > MAX_DIMENSION) {
                    throw new IOException("Preview texture dimensions");
                }

                pixels += (long) image.getWidth() * image.getHeight();
                if (pixels > MAX_ASSET_PIXELS) {
                    throw new IOException("Preview texture pixel budget");
                }

            }
            if (sources.isEmpty()) {
                throw new IOException("Model has no preview texture");
            }

            Layout layout = pack(sources);

            atlas = new NativeImage(layout.width, layout.height, true);
            // Explicitly clear gaps: native allocation must not expose uninitialized pixels.
            atlas.fillRect(0, 0, layout.width, layout.height, 0);
            for (Placement placement : layout.placements) {
                NativeImage image = placement.source.image;
                // Duplicate boundary pixels into a gutter; source UVs keep their exact pixel boundaries.
                for (int row = -placement.padY(); row < image.getHeight() + placement.padY(); row++) {
                    for (int column = -placement.padX(); column < image.getWidth() + placement.padX(); column++) {
                        atlas.setColorArgb(placement.x + column, placement.y + row,
                                image.getColorArgb(Math.clamp(column, 0, image.getWidth() - 1),
                                        Math.clamp(row, 0, image.getHeight() - 1)));
                    }

                }

            }
            Map<Integer, PreviewAtlasUv> rects = new LinkedHashMap<>();
            for (Placement p : layout.placements) {
                rects.put(p.source.index, new PreviewAtlasUv(p.x, p.y, p.source.image.getWidth(),
                        p.source.image.getHeight(), layout.width, layout.height));
            }

            PreviewTexture result = new PreviewTexture(key, model, atlas, Map.copyOf(rects), layout.pixels(),
                    previewAnimation, profile, settings);
            handedOff = true;
            return result;
        } catch (Exception failure) {
            if (failure instanceof IOException io) {
                throw io;
            }

            throw new IOException("Preview preparation failed", failure);
        } finally {
            for (Source source : sources) {
                try {
                    source.image.close();
                } catch (Throwable failure) {
                    LOGGER.warn("Cannot close preview source pixels", failure);
                }
            }
            if (!handedOff && atlas != null) {
                atlas.close();
            }
        }
    }

    private static final class PreviewRequest {
        private final BbModel model;
        private final YsmModelProfile profile;

        private PreviewRequest(BbModel model, YsmModelProfile profile) {
            this.model = model;
            this.profile = profile;
        }
    }

    private final class PreviewTexture implements TexturePreparation.Prepared {
        private final String key;
        private final BbModel model;
        private NativeImage atlas;
        private final Map<Integer, PreviewAtlasUv> rects;
        private final long pixels;
        private final String animation;
        private final YsmModelProfile profile;
        private final PreviewMesh.Settings settings;

        private PreviewTexture(String key, BbModel model, NativeImage atlas, Map<Integer, PreviewAtlasUv> rects, long pixels,
                String animation, YsmModelProfile profile, PreviewMesh.Settings settings) {
            this.key = key;
            this.model = model;
            this.atlas = atlas;
            this.rects = rects;
            this.pixels = pixels;
            this.animation = animation;
            this.profile = profile;
            this.settings = settings;
        }

        @Override
        public TexturePreparation.Step upload() {
            if (!makeRoom(this.pixels, true)) {
                return TexturePreparation.Step.WAIT;
            }
            Identifier id = Identifier.of("meplayeractions", "gui_preview/atlas_" + IDS.incrementAndGet());
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "MEPlayerActions GUI preview " + id,
                    this.atlas);
            this.atlas = null;
            boolean registered = false;
            try {
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
                registered = true;
                ModelPreview.this.assets.put(this.key, new Asset(this.model, id, texture, this.rects, this.pixels,
                        this.animation, this.profile, this.settings));
            } catch (Throwable failure) {
                try {
                    if (registered) {
                        MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
                    } else {
                        texture.close();
                    }
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                    texture.close();
                }
                throw failure;
            }
            return new TexturePreparation.Step(true, true);
        }

        @Override
        public void close() {
            if (this.atlas != null) {
                try {
                    this.atlas.close();
                } finally {
                    this.atlas = null;
                }
            }
        }
    }

    /** Validate dimensions before native allocation; all callers supply previously validated model/profile PNGs. */
    private static NativeImage decodePng(byte[] png) throws IOException {
        if (png.length < 24 || png.length > 8 * 1024 * 1024 || ByteBuffer.wrap(png).getLong() != 0x89504e470d0a1a0aL) {
            throw new IOException("Preview resource must be a bounded PNG");
        }

        int width = ByteBuffer.wrap(png, 16, 8).getInt(), height = ByteBuffer.wrap(png, 20, 4).getInt();
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("Preview resource dimensions");
        }

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
                if (x + source.paddedWidth() > atlasWidth) {
                    x = 0;
                    y += rowHeight;
                    rowHeight = 0;
                }
                placements.add(new Placement(source, x + source.padX(), y + source.padY()));
                x += source.paddedWidth();
                rowHeight = Math.max(rowHeight, source.paddedHeight());
            }
            int atlasHeight = nextPowerOfTwo(y + rowHeight);
            Layout candidate = new Layout(atlasWidth, atlasHeight, List.copyOf(placements));
            if (atlasHeight <= MAX_DIMENSION && candidate.pixels() <= MAX_ASSET_PIXELS
                    && (best == null || candidate.pixels() < best.pixels())) {
                best = candidate;
            }

        }
        // Preserve the previous texture budget boundary for tightly packed large models.
        if (best == null && sources.stream().anyMatch(Source::gutter)) {
            return pack(sources.stream().map(source -> new Source(source.index, source.image, false)).toList());
        }

        if (best == null) {
            throw new IOException("Preview textures cannot fit a bounded atlas");
        }

        return best;
    }

    private static int nextPowerOfTwo(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }

    private record Source(int index, NativeImage image, boolean gutter) {
        int padX() {
            return this.gutter && this.image.getWidth() <= MAX_DIMENSION - 2 ? 1 : 0;
        }

        int padY() {
            return this.gutter && this.image.getHeight() <= MAX_DIMENSION - 2 ? 1 : 0;
        }

        int paddedWidth() {
            return this.image.getWidth() + 2 * padX();
        }

        int paddedHeight() {
            return this.image.getHeight() + 2 * padY();
        }
    }

    private record Placement(Source source, int x, int y) {
        int padX() {
            return this.source.padX();
        }

        int padY() {
            return this.source.padY();
        }
    }

    private record Layout(int width, int height, List<Placement> placements) {
        long pixels() {
            return (long) this.width * this.height;
        }
    }

    private static final class Asset {
        final BbModel model;
        final Identifier id;
        final NativeImageBackedTexture texture;
        final Map<Integer, PreviewAtlasUv> rects;
        final long pixels;
        final EnumMap<Context, PreviewScene> scenes = new EnumMap<>(Context.class);
        final String previewAnimation;
        final YsmModelProfile profile;
        final PreviewMesh.Settings settings;
        private final Map<Context, OtherClientPlayerEntity> dummies = new EnumMap<>(Context.class);

        Asset(BbModel model, Identifier id, NativeImageBackedTexture texture, Map<Integer, PreviewAtlasUv> rects,
                long pixels, String previewAnimation, YsmModelProfile profile, PreviewMesh.Settings settings) {
            this.model = model;
            this.id = id;
            this.texture = texture;
            this.rects = rects;
            this.pixels = pixels;
            this.profile = profile;
            this.settings = settings;
            this.previewAnimation = previewAnimation;
        }

        /** The source gallery has a fresh standing player with empty inventory, never the live owner's gear. */
        OtherClientPlayerEntity previewDummy(ClientWorld world, Context previewContext) {
            if (world == null) {
                return null;
            }

            OtherClientPlayerEntity dummy = this.dummies.get(previewContext);
            if (dummy == null || dummy.getEntityWorld() != world) {
                dummy = new OtherClientPlayerEntity(world, new GameProfile(UUID.randomUUID(), "mpa_preview"));
            }

            this.dummies.put(previewContext, dummy);
            return dummy;
        }

        PreviewScene.Sample sample(Context context, float tick, Map<String, Double> parameters,
                PreviewScene.NativeInputs inputs) {
            PreviewScene scene = this.scenes.computeIfAbsent(context,
                    slot -> new PreviewScene(this.model, this.previewAnimation, slot));
            if (this.profile.isYsm()) {
                scene.enableNativeYsm();
                scene.enableQueryDiagnostics();
            }
            PreviewScene.Sample sample = scene.sample(tick, parameters, inputs);
            for (BbModel.Vertex vertex : sample.vertices()) {
                if (!this.rects.containsKey(vertex.texture())) {
                    throw new IllegalArgumentException("Missing preview texture reference");
                }

            }

            return sample;
        }
    }

    private record DrawSnapshot(String key, int x, int y, int width, int height, PreviewScene.Sample sample,
            List<PreviewMesh.Quad> quads, Map<Integer, PreviewAtlasUv> rects, PreviewMesh.Settings settings, float yaw,
            float pitch, Context previewContext, NativeGuiPreviewCamera.Camera camera, ScreenRect scissor,
            NativeGuiRenderBackend.Evidence nativeExecution) {
    }

    private record MeshState(Matrix3x2f pose, List<PreviewMesh.Quad> quads, Map<Integer, PreviewAtlasUv> textures,
            TextureSetup textureSetup, ScreenRect scissorArea, ScreenRect bounds, AtomicLong emittedVertices)
            implements SimpleGuiElementRenderState {
        @Override
        public RenderPipeline pipeline() {
            return PREVIEW_PIPELINE;
        }

        @Override
        public void setupVertices(VertexConsumer consumer) {
            for (PreviewMesh.Quad quad : this.quads) {
                PreviewAtlasUv rect = this.textures.get(quad.texture());
                for (PreviewMesh.Point point : quad.points()) {
                    consumer.vertex(this.pose, point.x(), point.y()).texture(rect.u(point.u()), rect.v(point.v()))
                            .color(quad.color());
                }

            }
            this.emittedVertices.addAndGet((long) this.quads.size() * 4);
        }
    }
}
