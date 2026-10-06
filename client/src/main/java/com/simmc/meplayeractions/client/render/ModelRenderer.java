package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.YsmQueryDiagnostics;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.model.YsmRenderScale;
import com.simmc.meplayeractions.client.model.NativeBbmodelPoseAdapter;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Arm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Uses the 1.21.11 extraction/command pipeline; no immediate GPU writes during drawing. */
public final class ModelRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("MEPlayerActions/Render");
    private static final long MAX_GPU_PIXELS = 32L * 1024 * 1024;
    private static final long MAX_ASSET_PIXELS = 16L * 1024 * 1024;
    private static final Map<String, PreparedAsset> ASSETS = new HashMap<>();
    private static final Map<InstanceKey, AnimationPlayer> PLAYERS = new HashMap<>();
    private static volatile List<FrozenModel> frame = List.of();
    private static volatile List<FrameModel> frameModels = List.of();
    private static volatile int firstPersonSkipped;
    private static volatile long drawnBatches;
    private static volatile long extractedFrames;
    private static volatile Map<String, Object> cameraInfo = Map.of();
    private static volatile List<Map<String, Object>> itemInfo = List.of();
    private static volatile List<Map<String, Object>> submittedItemDraws = List.of();
    private static volatile List<Map<String, Object>> submittedEquipmentDraws = List.of();
    private static ClientRuntime runtime;
    private static boolean registered;

    private ModelRenderer() { }

    /** Register once; all gameplay reads and mesh animation happen during extraction. */
    public static void register(ClientRuntime clientRuntime) {
        runtime = clientRuntime;
        if (registered) return;
        registered = true;
        WorldRenderEvents.END_EXTRACTION.register(ModelRenderer::extract);
        WorldRenderEvents.BEFORE_ENTITIES.register(ModelRenderer::submit);
        Identifier reloadId = Identifier.of("meplayeractions", "runtime_models");
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        resources.registerReloader(reloadId, (SynchronousResourceReloader) manager -> reloadTextures());
        resources.addReloaderOrdering(ResourceReloaderKeys.AFTER_VANILLA, reloadId);
        if (Boolean.getBoolean("meplayeractions.e2e")) E2EHarness.register(clientRuntime);
    }

    /** Complete texture uploads before acknowledging client rendering to the server. */
    public static boolean prepare(String hash, BbModel model) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || hash == null || !hash.matches("[0-9a-f]{64}") || model == null) return false;
        if (ASSETS.containsKey(hash)) return true;
        Map<Integer, PreparedTexture> textures = new LinkedHashMap<>();
        try {
            long existingPixels = pixelCount();
            long assetPixels = 0;
            List<BbModel.Vertex> basis = model.basisVertices();
            if (basis.isEmpty() || basis.size() % 4 != 0) {
                throw new IllegalArgumentException("Model has no complete visible quad geometry");
            }
            Set<Integer> sourceIndices = new HashSet<>();
            model.textures().forEach(texture -> sourceIndices.add(texture.index()));
            for (BbModel.Vertex vertex : basis) {
                if (!sourceIndices.contains(vertex.texture()) || !finite(vertex)) {
                    throw new IllegalArgumentException("Invalid model geometry or texture reference");
                }
            }
            for (BbModel.Texture source : model.textures()) {
                if (textures.containsKey(source.index())) throw new IllegalArgumentException("Duplicate texture index");
                Identifier id = Identifier.of("meplayeractions", "runtime/" + hash + "/" + source.index());
                NativeImage image = NativeImage.read(source.png());
                NativeImageBackedTexture texture = null;
                try {
                    if (image.getWidth() <= 0 || image.getHeight() <= 0
                            || image.getWidth() > 4096 || image.getHeight() > 4096) {
                        throw new IllegalArgumentException("Texture dimensions exceed 4096 pixels");
                    }
                    long pixels = (long) image.getWidth() * image.getHeight();
                    assetPixels += pixels;
                    if (assetPixels > MAX_ASSET_PIXELS || existingPixels + assetPixels > MAX_GPU_PIXELS) {
                        throw new IllegalArgumentException("GPU texture pixel budget exceeded");
                    }
                    texture = new NativeImageBackedTexture(() -> "MEPlayerActions " + hash, image);
                    client.getTextureManager().registerTexture(id, texture);
                    boolean translucent = partialAlpha(image);
                    textures.put(source.index(), new PreparedTexture(id, RenderLayers.entityCutoutNoCull(id),
                            translucent ? RenderLayers.entityTranslucent(id) : RenderLayers.entityCutoutNoCull(id), pixels));
                } catch (Exception failure) {
                    if (texture != null) texture.close();
                    else image.close();
                    throw failure;
                }
            }
            if (textures.isEmpty()) throw new IllegalArgumentException("Model has no textures");
            ASSETS.put(hash, new PreparedAsset(model, Map.copyOf(textures)));
            return true;
        } catch (Exception failure) {
            textures.values().forEach(texture -> client.getTextureManager().destroyTexture(texture.id()));
            LOGGER.warn("Cannot prepare model {} for rendering: {}", hash, failure.toString());
            return false;
        }
    }

    public static boolean has(String hash) {
        return ASSETS.containsKey(hash);
    }

    /** OpenYSM's texture translucency classification preserves fractional alpha instead of cutting it away. */
    private static boolean partialAlpha(NativeImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int alpha = image.getColorArgb(x, y) >>> 24;
            if (alpha > 0 && alpha < 255) return true;
        }
        return false;
    }

    public static long pixelCount() {
        return ASSETS.values().stream().flatMap(asset -> asset.textures().values().stream())
                .mapToLong(PreparedTexture::pixels).sum();
    }

    public static Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("textures", ASSETS.size()); result.put("gpuPixels", pixelCount()); result.put("drawnBatches", drawnBatches);
        result.put("firstPersonSelfSkipped", firstPersonSkipped); result.put("models", frameModels); result.put("camera", cameraInfo);
        result.put("extractedFrames", extractedFrames); result.put("items", itemInfo); result.put("submittedItems", YsmItemRenderer.submittedItems());
        result.put("submittedItemDraws", submittedItemDraws); result.put("submittedEquipment", YsmEquipmentRenderer.submittedEquipment());
        result.put("submittedEquipmentDraws", submittedEquipmentDraws);
        result.put("components", YsmComponentRenderer.diagnostics());
        result.put("queryFallbacks",PLAYERS.entrySet().stream().filter(entry -> !entry.getValue().expressionDiagnostics().isEmpty())
                .map(entry -> Map.of("owner",entry.getKey().owner().toString(),"instance",entry.getKey().instance(),
                        "queries",YsmQueryDiagnostics.describe(entry.getValue()))).toList());
        return Map.copyOf(result);
    }

    /** Query fallbacks belong to the currently sampled private body/components, never a remote asset request. */
    public static List<Map<String,Object>> queryDiagnostics(UUID owner) {
        List<Map<String,Object>> values = new ArrayList<>();
        PLAYERS.entrySet().stream().filter(entry -> entry.getKey().owner().equals(owner))
                .forEach(entry -> values.addAll(YsmQueryDiagnostics.describe(entry.getValue())));
        values.addAll(YsmComponentRenderer.queryDiagnostics(owner));
        return List.copyOf(values);
    }

    /** Read-only instance state for isolated in-game expression checks. */
    public static Map<String, Double> expressionVariables(UUID owner) {
        return PLAYERS.entrySet().stream().filter(entry -> entry.getKey().owner().equals(owner))
                .map(entry -> entry.getValue().expressionVariables()).findFirst().orElse(Map.of());
    }
    /** Server-authorized echo applies once to the matching WORLD BODY instance, never GUI/FP clocks. */
    public static boolean applyAuthorSync(UUID owner, String instance, List<Double> arguments) {
        for (var entry : PLAYERS.entrySet()) {
            if (entry.getKey().owner().equals(owner) && entry.getKey().instance().equals(instance)) {
                return entry.getValue().applySync(arguments);
            }
        }
        return false;
    }

    public record FrameModel(String owner, String hash, int vertices, double minY, double maxY,
                             float bodyYaw, float appliedYaw, double minX, double maxX, double minZ, double maxZ,
                             double pivotX, double pivotY, double pivotZ, String motionSource,
                             String instance, boolean nativeYsm, float modelScaleX, float modelScaleY,
                             float modelScaleZ, float bindingScale, float initialYOffset) { }

    public static void release(String hash) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(() -> release(hash));
            return;
        }
        PreparedAsset asset = ASSETS.remove(hash);
        if (asset == null) return;
        frame = List.of();
        frameModels = List.of();
        itemInfo = List.of();
        submittedItemDraws = List.of();
        submittedEquipmentDraws = List.of();
        YsmComponentRenderer.clear();
        PLAYERS.entrySet().removeIf(entry -> {
            if (!entry.getKey().hash().equals(hash)) return false;
            entry.getValue().dispose();
            return true;
        });
        asset.textures().values().forEach(texture -> client.getTextureManager().destroyTexture(texture.id()));
    }

    private static boolean finite(BbModel.Vertex vertex) {
        return Float.isFinite(vertex.x()) && Float.isFinite(vertex.y()) && Float.isFinite(vertex.z())
                && Float.isFinite(vertex.u()) && Float.isFinite(vertex.v())
                && Float.isFinite(vertex.nx()) && Float.isFinite(vertex.ny()) && Float.isFinite(vertex.nz());
    }

    private static void reloadTextures() {
        Map<String, BbModel> retained = new LinkedHashMap<>();
        ASSETS.forEach((hash, asset) -> retained.put(hash, asset.model()));
        if (runtime != null) { runtime.releaseAll(); retained.keySet().removeAll(runtime.resourcesReloaded()); }
        clear();
        retained.forEach((hash, model) -> {
            if (!prepare(hash, model) && runtime != null) {
                runtime.renderFailed(null, "", hash, "Texture upload failed after resource reload");
            }
        });
    }

    /** Called on disconnect/reload on the client thread, including native image/GPU release. */
    public static void clear() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(ModelRenderer::clear);
            return;
        }
        NativePlayerPresentation.beginFrame(List.of());
        NativeRenderPhases.beginFrame();
        frame = List.of();
        frameModels = List.of();
        itemInfo = List.of();
        submittedItemDraws = List.of();
        submittedEquipmentDraws = List.of();
        YsmComponentRenderer.clear();
        PLAYERS.values().forEach(AnimationPlayer::dispose);
        PLAYERS.clear();
        ASSETS.values().forEach(asset -> asset.textures().values().forEach(
                texture -> client.getTextureManager().destroyTexture(texture.id())));
        ASSETS.clear();
    }

    public static boolean shouldHidePlayer(UUID owner) {
        return runtime != null && runtime.shouldHidePlayer(owner);
    }

    public static boolean shouldHideVanillaLayers(UUID owner) {
        return runtime != null && runtime.shouldHideVanillaLayers(owner);
    }

    public static boolean shouldHideFirstPersonArm() {
        return runtime != null && runtime.shouldHideFirstPersonArm();
    }

    private static void extract(WorldExtractionContext context) {
        ClientRuntime activeRuntime = runtime;
        if (activeRuntime == null || context.world() == null) {
            frame = List.of();
            frameModels = List.of();
            itemInfo = List.of();
            submittedItemDraws = List.of();
            submittedEquipmentDraws = List.of();
            YsmComponentRenderer.clear();
            NativePlayerPresentation.beginFrame(List.of());
            NativeRenderPhases.beginFrame();
            firstPersonSkipped = 0;
            extractedFrames++;
            return;
        }
        Vec3d camera = context.worldState().cameraRenderState.pos;
        NativePlayerPresentation.beginFrame(context.worldState().entityRenderStates, camera);
        NativeRenderPhases.beginFrame();
        UUID cameraOwner = context.camera().getFocusedEntity() == null
                ? null : context.camera().getFocusedEntity().getUuid();
        boolean firstPerson = !context.camera().isThirdPerson();
        UUID localOwner = MinecraftClient.getInstance().player == null ? null : MinecraftClient.getInstance().player.getUuid();
        cameraInfo = Map.of("firstPerson", firstPerson, "focused", cameraOwner == null ? "" : cameraOwner.toString(),
                "localPlayer", localOwner == null ? "" : localOwner.toString(), "x", camera.x, "y", camera.y, "z", camera.z);
        List<FrozenModel> nextFrame = new ArrayList<>();
        List<FrameModel> modelInfo = new ArrayList<>();
        List<Map<String, Object>> nextItemInfo = new ArrayList<>();
        YsmComponentRenderer.beginExtraction(activeRuntime, context);
        int skipped = 0;
        Set<InstanceKey> live = new HashSet<>();
        for (ClientRuntime.RenderBinding binding : activeRuntime.animationBindings()) {
            PreparedAsset asset = ASSETS.get(binding.assetHash());
            if (asset == null || !validTransform(binding)) {
                activeRuntime.renderFailed(binding.owner(), binding.instance(), binding.assetHash(), "Invalid render transform or missing texture");
                continue;
            }
            InstanceKey key = new InstanceKey(binding.owner(), binding.instance(), binding.assetHash());
            live.add(key);
            try {
                YsmModelProfile profile = activeRuntime.modelProfile(binding.owner());
                boolean nativeYsm = profile.isYsm();
                var nativePlayer = activeRuntime.nativePlayer(binding.owner());
                YsmRenderScale modelScale = YsmRenderScale.forProfile(profile);
                AnimationPlayer player = PLAYERS.computeIfAbsent(key, ignored -> new AnimationPlayer(binding.model()));
                if(nativeYsm){player.enableNativeYsm();player.enableQueryDiagnostics();}
                player.syncListener(nativeYsm ? activeRuntime.nativeSyncListener(binding.owner()) : null);
                player.configureFrame(expressions -> activeRuntime.configureExpressionContext(binding.owner(), expressions));
                if (nativeYsm && profile.isImportedBbModel())
                    NativeBbmodelPoseAdapter.install(player, binding.model(), nativePlayer, NativeHumanoidPoseSampler.frame(nativePlayer));
                List<BbModel.Vertex> vertices = player.sample(binding.serverTick(), binding.layers(),
                        binding.headYaw() - binding.bodyYaw(), binding.headPitch(), activeRuntime.expressionQueries(binding.owner()),
                        activeRuntime.accessoryState(binding.owner()), activeRuntime.localParameters(binding.owner()));
                if (nativeYsm) activeRuntime.recordLocalRoaming(binding.owner(), binding.instance(), player.consumeRoamingChanges());
                try { YsmComponentRenderer.extractArms(activeRuntime, binding); }
                catch (RuntimeException failure) { LOGGER.warn("Cannot prepare first-person arms for {}: {}", binding.owner(), failure.toString()); }
                // Hidden self rendering still advances timeline scripts and spring state.
                if (firstPerson && (binding.owner().equals(cameraOwner) || binding.owner().equals(localOwner))) {
                    skipped++;
                    continue;
                }
                // Off-screen remote players have no body in this vanilla frame. Never reuse a previous frame's anchor.
                if (nativePlayer != null && !binding.owner().equals(localOwner)
                        && !NativePlayerPresentation.captured(binding.owner())) continue;
                var presentation = NativePlayerPresentation.get(binding.owner());
                var vanillaFrame = NativePlayerPresentation.entityFrame(binding.owner());
                // OpenYSM's native body branch: hidden/invisible geometry is omitted, but glowing
                // invisible players retain the source outline render type and native team color.
                boolean requestedModel = activeRuntime.shouldShowModel(binding.owner());
                boolean outlineOnly = nativeYsm && vanillaFrame != null && vanillaFrame.invisible()
                        && vanillaFrame.outlineColor() != 0;
                boolean showModel = requestedModel && (!nativeYsm || vanillaFrame == null || !vanillaFrame.invisible() || outlineOnly);
                int outlineColor = outlineOnly ? vanillaFrame.outlineColor() : 0;
                if (vertices.isEmpty()) continue;
                Matrix4f passenger = nativeYsm ? YsmComponentRenderer.passengerTransform(nativePlayer) : new Matrix4f();
                Matrix4f body = nativeYsm && nativePlayer != null ? NativePlayerPresentation.frame(nativePlayer).parent()
                        : new Matrix4f().rotateY((float) Math.toRadians(180 - binding.bodyYaw()));
                if (nativeYsm && nativePlayer != null && activeRuntime.hasServerPoseAnchor(binding.owner()))
                    body.rotateLocalY((float) Math.toRadians(NativePlayerPresentation.frame(nativePlayer).bodyYaw() - binding.bodyYaw()));
                Matrix4f parent = new Matrix4f(passenger).mul(body);
                Box geometry = geometryBounds(binding, vertices, modelScale, nativeYsm, parent);
                if (!context.frustum().isVisible(geometry.expand(.05))) continue;
                Map<Integer, List<BbModel.Vertex>> byTexture = new LinkedHashMap<>();
                for (int offset = 0; showModel && offset + 3 < vertices.size(); offset += 4) {
                    int index = vertices.get(offset).texture();
                    if (!asset.textures().containsKey(index)) continue;
                    List<BbModel.Vertex> face = vertices.subList(offset, offset + 4);
                    if (face.stream().anyMatch(vertex -> vertex.texture() != index)) continue;
                    byTexture.computeIfAbsent(index, ignored -> new ArrayList<>()).addAll(face);
                }
                List<FrozenTexture> meshes = new ArrayList<>(byTexture.size());
                Identifier playerSkin = nativeYsm ? YsmPlayerSkin.resolve(activeRuntime.appearanceModelId(binding.owner()),
                        activeRuntime.nativePlayer(binding.owner())).orElse(null) : null;
                byTexture.forEach((index, mesh) -> {
                    Identifier textureId = playerSkin != null && index == 0 ? playerSkin : asset.textures().get(index).id();
                    RenderLayer layer = outlineOnly ? RenderLayers.outlineNoCull(textureId)
                            : playerSkin != null && index == 0 ? RenderLayers.entityCutoutNoCull(playerSkin)
                            : nativeYsm ? asset.textures().get(index).nativeLayer() : asset.textures().get(index).layer();
                    meshes.add(new FrozenTexture(layer, List.copyOf(mesh)));
                });
                int light = vanillaFrame != null ? vanillaFrame.light() : WorldRenderer.getLightmapCoordinates(context.world(),
                        BlockPos.ofFloored(binding.x(), binding.y() + Math.max(0.25, binding.scale()), binding.z()));
                List<YsmItemRenderer.Attachment> items = List.of();
                List<YsmEquipmentRenderer.Attachment> equipment = List.of();
                boolean hideNativeEquipment = activeRuntime.shouldHideVanillaLayers(binding.owner());
                boolean hideNativePlayer = activeRuntime.shouldHidePlayer(binding.owner());
                try {
                    // Visible vanilla players already render hand items at their native pose.
                    // Hiding author geometry alone must not remove the player's real held items.
                    if (hideNativePlayer && (!nativeYsm || nativePlayer == null || !nativePlayer.isSpectator()))
                        items = YsmItemRenderer.extract(nativePlayer, player, profile.isImportedBbModel());
                    // Native armor/cape/wings follow the original-player visibility policy. Visible
                    // vanilla players already submit their own equipment, so never attach it twice.
                    // Authored model geometry and independently resolved main/off-hand items stay visible.
                    if (!hideNativeEquipment && hideNativePlayer && (!nativeYsm || nativePlayer == null || !nativePlayer.isSpectator()))
                        equipment = YsmEquipmentRenderer.extract(nativePlayer, player);
                } catch (RuntimeException failure) { LOGGER.warn("Cannot extract equipment for {}: {}", binding.owner(), failure.toString()); }
                Map<String, Object> itemSource = new LinkedHashMap<>();
                itemSource.put("owner", binding.owner().toString()); itemSource.put("instance", binding.instance()); itemSource.put("hash", binding.assetHash());
                itemSource.put("nativeYsm", nativeYsm);
                itemSource.put("modelScaleX", modelScale.x()); itemSource.put("modelScaleY", modelScale.y()); itemSource.put("modelScaleZ", modelScale.z());
                itemSource.put("bindingScale", binding.scale());
                itemSource.put("initialYOffset", nativeYsm ? YsmRenderScale.PLAYER_BODY_Y_OFFSET : 0f);
                itemSource.put("currentBindingValid", true); itemSource.put("motionSource", binding.motionSource());
                itemSource.put("nativePresent", nativePlayer != null);
                itemSource.put("hideNativeEquipment", hideNativeEquipment);
                itemSource.put("hideNativePlayer", hideNativePlayer);
                itemSource.put("showDisguiseModel", showModel);
                itemSource.put("outlineOnly", outlineOnly);
                itemSource.put("renderLayersFirst", nativeYsm && profile.renderLayersFirst());
                if (nativePlayer != null) {
                    itemSource.put("nativeEntityUuid", nativePlayer.getUuid().toString()); itemSource.put("nativeEntityId", nativePlayer.getId());
                    itemSource.put("swinging", nativePlayer.handSwinging); itemSource.put("swingTicks", nativePlayer.handSwingTicks);
                    itemSource.put("usingItem", nativePlayer.isUsingItem()); itemSource.put("useTicks", nativePlayer.getItemUseTime());
                    itemSource.put("activeHand", nativePlayer.isUsingItem() ? nativePlayer.getActiveHand().name() : "");
                    Map<String,String> armor=new LinkedHashMap<>();
                    for(var slot:List.of(net.minecraft.entity.EquipmentSlot.HEAD,net.minecraft.entity.EquipmentSlot.CHEST,
                            net.minecraft.entity.EquipmentSlot.LEGS,net.minecraft.entity.EquipmentSlot.FEET))
                        armor.put(slot.name(),net.minecraft.registry.Registries.ITEM.getId(nativePlayer.getEquippedStack(slot).getItem()).toString());
                    itemSource.put("nativeArmorStacks",Map.copyOf(armor));
                    itemSource.put("nativeCapeAvailable",nativePlayer instanceof net.minecraft.client.network.AbstractClientPlayerEntity clientPlayer
                            && clientPlayer.getSkin().cape()!=null);
                }
                if (!items.isEmpty() || !equipment.isEmpty()) {
                    Map<String, Object> info = new LinkedHashMap<>(itemSource);
                    info.put("attachments", YsmItemRenderer.diagnostics(items)); info.put("equipment", YsmEquipmentRenderer.diagnostics(equipment));
                    nextItemInfo.add(Map.copyOf(info));
                }
                if (meshes.isEmpty() && items.isEmpty() && equipment.isEmpty()) continue;
                nextFrame.add(new FrozenModel(binding.x() - camera.x, binding.y() - camera.y,
                        binding.z() - camera.z, binding.bodyYaw(), binding.scale(), nativeYsm, modelScale, parent,
                        light, nativeYsm && presentation != null ? OverlayTexture.getUv(0,
                                presentation.renderState().hurt || presentation.renderState().deathTime > 0)
                                : OverlayTexture.DEFAULT_UV, nativeYsm && profile.renderLayersFirst(),
                        outlineColor, List.copyOf(meshes), items, equipment, Map.copyOf(itemSource)));
                if (showModel) modelInfo.add(new FrameModel(binding.owner().toString(), binding.assetHash(), vertices.size(),
                        geometry.minY, geometry.maxY, binding.bodyYaw(), 180 - binding.bodyYaw(),
                        geometry.minX, geometry.maxX, geometry.minZ, geometry.maxZ,
                        binding.x(), binding.y(), binding.z(), binding.motionSource(), binding.instance(), nativeYsm,
                        modelScale.x(), modelScale.y(), modelScale.z(), binding.scale(),
                        nativeYsm ? YsmRenderScale.PLAYER_BODY_Y_OFFSET : 0f));
            } catch (RuntimeException failure) {
                activeRuntime.renderFailed(binding.owner(), binding.instance(), binding.assetHash(), failure.toString());
                LOGGER.warn("Cannot extract model {}: {}", binding.assetHash(), failure.toString());
            }
        }
        PLAYERS.entrySet().removeIf(entry -> {
            if (live.contains(entry.getKey())) return false;
            entry.getValue().dispose();
            return true;
        });
        frame = List.copyOf(nextFrame);
        frameModels = List.copyOf(modelInfo);
        itemInfo = List.copyOf(nextItemInfo);
        YsmComponentRenderer.finishExtraction();
        firstPersonSkipped = skipped;
        // Keep this monotonic through reload/clear so lifecycle checks can require a fresh frame.
        extractedFrames++;
    }

    private static boolean validTransform(ClientRuntime.RenderBinding binding) {
        return Double.isFinite(binding.x()) && Double.isFinite(binding.y()) && Double.isFinite(binding.z())
                && Float.isFinite(binding.bodyYaw()) && Float.isFinite(binding.scale())
                && binding.scale() > 0 && binding.scale() <= 8;
    }

    private static Box geometryBounds(ClientRuntime.RenderBinding binding, List<BbModel.Vertex> vertices,
                                      YsmRenderScale modelScale, boolean nativeYsm, Matrix4f parent) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        Matrix4f initial = new Matrix4f(parent).scale(binding.scale());
        modelScale.applyInitialPlayerBody(initial, nativeYsm);
        Vector3f point = new Vector3f();
        for (BbModel.Vertex vertex : vertices) {
            initial.transformPosition(vertex.x(), vertex.y(), vertex.z(), point);
            // Apply the existing sampled-position budget after the newly supported author transform too.
            if (!point.isFinite() || point.lengthSquared() > 1e12f)
                throw new IllegalArgumentException("Sampled model bounds exceeded after YSM INITIAL scale");
            double x = binding.x() + point.x;
            double y = binding.y() + point.y;
            double z = binding.z() + point.z;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void submit(WorldRenderContext context) {
        List<Map<String, Object>> itemDraws = new ArrayList<>();
        List<Map<String, Object>> equipmentDraws = new ArrayList<>();
        for (FrozenModel model : frame) {
            MatrixStack matrices = context.matrices();
            matrices.push();
            try {
                matrices.translate(model.x(), model.y(), model.z());
                matrices.multiplyPositionMatrix(model.parent());
                matrices.scale(model.scale(), model.scale(), model.scale());
                if (model.nativeYsm()) {
                    matrices.translate(0, YsmRenderScale.PLAYER_BODY_Y_OFFSET, 0);
                    matrices.scale(model.modelScale().x(), model.modelScale().y(), model.modelScale().z());
                }
                var geometryQueue = model.nativeYsm() ? NativeRenderPhases.geometry(context.commandQueue(), model.layersFirst())
                        : context.commandQueue();
                var attachmentsQueue = model.nativeYsm() ? NativeRenderPhases.attachments(context.commandQueue(), model.layersFirst())
                        : context.commandQueue();
                for (FrozenTexture texture : model.textures()) {
                    geometryQueue.submitCustom(matrices, texture.layer(),
                            (entry, consumer) -> emit(entry, consumer, texture.vertices(), model.light(), model.overlay(),
                                    model.outlineColor() == 0 ? 0xFFFFFFFF : model.outlineColor()));
                }
                for (Map<String, Object> draw : YsmItemRenderer.submit(model.items(), matrices, attachmentsQueue, model.light())) {
                    Map<String, Object> proof = new LinkedHashMap<>(model.itemSource());
                    proof.putAll(draw); proof.put("extractedFrame", extractedFrames);
                    itemDraws.add(Map.copyOf(proof));
                }
                long equipmentBefore=YsmEquipmentRenderer.submittedEquipment();
                YsmEquipmentRenderer.submit(model.equipment(), matrices, attachmentsQueue, model.light());
                Map<String,Object> equipmentProof=new LinkedHashMap<>(model.itemSource());
                equipmentProof.put("extractedFrame",extractedFrames);
                equipmentProof.put("equipment",YsmEquipmentRenderer.diagnostics(model.equipment()));
                equipmentProof.put("submitted",YsmEquipmentRenderer.submittedEquipment()-equipmentBefore);
                equipmentDraws.add(Map.copyOf(equipmentProof));
            } finally {
                matrices.pop();
            }
        }
        submittedItemDraws = List.copyOf(itemDraws);
        submittedEquipmentDraws = List.copyOf(equipmentDraws);
        YsmComponentRenderer.submit(context);
    }

    /** Component meshes use the same prepared texture atlas and budgets as the primary model. */
    static boolean submitMesh(String hash, List<BbModel.Vertex> vertices, MatrixStack matrices,
                              net.minecraft.client.render.command.OrderedRenderCommandQueue queue, int light) {
        return submitMesh(hash, vertices, matrices, queue, light, ignored -> { });
    }
    static boolean submitMesh(String hash, List<BbModel.Vertex> vertices, MatrixStack matrices,
                              net.minecraft.client.render.command.OrderedRenderCommandQueue queue, int light,
                              java.util.function.IntConsumer emitted) {
        return submitMesh(hash, vertices, matrices, queue, light, null, emitted);
    }
    static boolean submitMesh(String hash, List<BbModel.Vertex> vertices, MatrixStack matrices,
                              net.minecraft.client.render.command.OrderedRenderCommandQueue queue, int light,
                              Identifier playerSkin, java.util.function.IntConsumer emitted) {
        PreparedAsset asset = ASSETS.get(hash);
        if (asset == null || vertices.isEmpty()) return false;
        Map<Integer, List<BbModel.Vertex>> meshes = new LinkedHashMap<>();
        for (int i = 0; i + 3 < vertices.size(); i += 4) {
            List<BbModel.Vertex> face = vertices.subList(i, i + 4);
            int texture = face.get(0).texture();
            if (!asset.textures().containsKey(texture) || face.stream().anyMatch(v -> v.texture() != texture)) continue;
            meshes.computeIfAbsent(texture, ignored -> new ArrayList<>()).addAll(face);
        }
        meshes.forEach((index, mesh) -> {
            List<BbModel.Vertex> frozen = List.copyOf(mesh);
            queue.submitCustom(matrices, playerSkin != null && index == 0 ? RenderLayers.entityTranslucent(playerSkin)
                    : asset.model().ysmControllers() ? asset.textures().get(index).nativeLayer()
                    : asset.textures().get(index).layer(), (entry, consumer) -> {
                emit(entry, consumer, frozen, light);
                emitted.accept(frozen.size());
            });
        });
        return !meshes.isEmpty();
    }

    public static boolean renderFirstPersonArm(Arm arm, MatrixStack matrices,
            net.minecraft.client.render.command.OrderedRenderCommandQueue queue, int light) {
        ClientRuntime activeRuntime = runtime;
        if (activeRuntime == null || !activeRuntime.options.enabled) return false;
        var nativePlayer = MinecraftClient.getInstance().player;
        if (nativePlayer != null && !activeRuntime.shouldShowModel(nativePlayer.getUuid())) {
            // Returning true suppresses the native arm without drawing a hidden author arm.
            // With the native body enabled, return false so Minecraft keeps its own hand path.
            return activeRuntime.shouldHidePlayer(nativePlayer.getUuid());
        }
        return YsmComponentRenderer.renderArm(arm, matrices, queue, light);
    }

    private static void emit(MatrixStack.Entry entry, VertexConsumer consumer, List<BbModel.Vertex> vertices, int light) {
        emit(entry, consumer, vertices, light, OverlayTexture.DEFAULT_UV);
    }
    private static void emit(MatrixStack.Entry entry, VertexConsumer consumer, List<BbModel.Vertex> vertices, int light, int overlay) {
        emit(entry, consumer, vertices, light, overlay, 0xFFFFFFFF);
    }
    private static void emit(MatrixStack.Entry entry, VertexConsumer consumer, List<BbModel.Vertex> vertices, int light, int overlay, int color) {
        drawnBatches++;
        for (BbModel.Vertex vertex : vertices) {
            consumer.vertex(entry, vertex.x(), vertex.y(), vertex.z())
                    .color(color).texture(vertex.u(), vertex.v())
                    .overlay(overlay).light(vertex.emissive() ? 0xF000F0 : light)
                    .normal(entry, vertex.nx(), vertex.ny(), vertex.nz());
        }
    }

    private record PreparedTexture(Identifier id, RenderLayer layer, RenderLayer nativeLayer, long pixels) { }
    private record PreparedAsset(BbModel model, Map<Integer, PreparedTexture> textures) { }
    private record InstanceKey(UUID owner, String instance, String hash) { }
    private record FrozenTexture(RenderLayer layer, List<BbModel.Vertex> vertices) { }
    private record FrozenModel(double x, double y, double z, float yaw, float scale,
                               boolean nativeYsm, YsmRenderScale modelScale, Matrix4f parent, int light, int overlay, boolean layersFirst, int outlineColor,
                               List<FrozenTexture> textures, List<YsmItemRenderer.Attachment> items,
                               List<YsmEquipmentRenderer.Attachment> equipment, Map<String, Object> itemSource) { }
}
