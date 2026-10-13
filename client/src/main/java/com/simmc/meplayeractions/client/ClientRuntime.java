package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.effects.ModelEffectInstances;
import com.simmc.meplayeractions.client.effects.YsmModelEffects;
import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.BbModel.Layer;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.BbModelAsset;
import com.simmc.meplayeractions.client.model.BuiltinYsmModels;
import com.simmc.meplayeractions.client.model.NativeModelBundle;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.network.*;
import com.simmc.meplayeractions.client.render.ModelRenderer;
import com.simmc.meplayeractions.client.render.NativeBodyVisibility;
import com.simmc.meplayeractions.client.render.NativePlayerPresentation;
import com.simmc.meplayeractions.client.ui.ModelConfigSchema;
import com.simmc.meplayeractions.config.AnimationLabels;
import com.simmc.meplayeractions.expression.Molang;

import com.google.gson.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** All live session and GPU mutations run on the Minecraft thread. Asset decoding does not. */
public final class ClientRuntime {
    public record RenderBinding(UUID owner, String instance, String assetHash, BbModel model, List<Layer> layers,
            double serverTick, double x, double y, double z, float bodyYaw, float headYaw, float headPitch, float scale,
            boolean hidePlayer, String motionSource) {
    }

    public record Action(String id, String label) {
    }

    public enum AppearanceSource {
        NONE, CLIENT, SERVER
    }

    /** Read-only owner input for the native inventory preview, independent of disguise and asset leases. */
    public record GuiPreviewInput(float height, float scale, EntityAnimationController.Sample sample) {
    }

    /** Identity and assets of the currently prepared appearance; GUI reads never advance world controllers. */
    public record GuiPreviewAppearance(String modelId, String instance, String assetHash, BbModel model,
            YsmModelProfile profile) {
    }

    private static final long SECOND = 1_000_000_000L;
    private final MinecraftClient client;
    private final ServerClock clock = new ServerClock();
    private final Map<UUID, Binding> bindings = new LinkedHashMap<>();
    private final Map<UUID, Binding> privateBindings = new LinkedHashMap<>();
    private final Map<UUID, Long> privateExtraSequences = new HashMap<>();
    private final Map<String, String> privateLogicalSourceIds = new ConcurrentHashMap<>();

    private record PendingSync(UUID owner, String instance, List<Double> args, long expires) {
    }

    private final ArrayDeque<PendingSync> pendingPrivateEvents = new ArrayDeque<>();
    private PrivateModelSyncClient privateModels;
    private final Map<String, PendingInstall> textureInstalls = new LinkedHashMap<>();
    private final Set<String> textureInstallSlots = new HashSet<>();
    private long localExtraSequence;
    private long lastRoamingFlush;
    private final Map<String, BbModel> assets = new LinkedHashMap<>(16, 0.75f, true);
    // Decoding semantics belong to the installed asset, independently of its binding/transport source.
    // Entries share the assets map's 16-model lifetime and contain no original transfer bytes.
    private final Map<String, BbModelAsset.Decoded> assetMetadata = new HashMap<>();
    private final Set<String> packAssets = new HashSet<>();
    private final Set<String> serverAssets = new HashSet<>();
    private final Map<String, String> serverModelAssets = new HashMap<>();
    private final ServerPushAuthorization pushAuthorization = new ServerPushAuthorization();
    private final FailedAssetCache failedAssets = new FailedAssetCache(this::activeAssetHashes);
    private final Set<String> loading = new HashSet<>();
    private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(8), r -> {
                Thread t = new Thread(r, "MPA-model-loader");
                t.setDaemon(true);
                return t;
            });
    private final ThreadPoolExecutor previewDecoder = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(8), r -> {
                Thread t = new Thread(r, "MPA-preview-loader");
                t.setDaemon(true);
                return t;
            });
    private final ThreadPoolExecutor privateDecoder = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(8), r -> {
                Thread t = new Thread(r, "MPA-private-sync-loader");
                t.setDaemon(true);
                return t;
            });
    private final Path cache = FabricLoader.getInstance().getGameDir().resolve("config/meplayeractions/cache");
    private final ServerModelCache serverModelCache = new ServerModelCache(this.cache);
    private final ServerModelCache privateModelCache = new ServerModelCache(this.cache.resolve("private-models"));
    private final ServerCachedModelCatalog serverCachedModelCatalog = new ServerCachedModelCatalog(
            this.serverModelCache);
    private final LocalModelLibrary localModelLibrary = new LocalModelLibrary(
            FabricLoader.getInstance().getConfigDir().resolve("meplayeractions/models"));
    private final Map<String, LocalModelLibrary.Loaded> localProfiles = new LinkedHashMap<>(16, .75f, true);

    private record EffectState(YsmModelEffects effects, Entity entity, String instance, String hash,
            YsmModelProfile profile) {
    }

    private final ModelEffectInstances<EffectState> modelEffects = new ModelEffectInstances<>(256,
            state -> state.effects().close());
    public final ClientOptions options;
    private boolean connected, acknowledged;
    private boolean pushNegotiated, unsupportedHandshake;
    private String serverAssetMode = "";
    private List<String> serverCapabilities = List.of();
    private final IncrementalStateProtocol stateProtocol = new IncrementalStateProtocol();
    private final ServerModelCatalogSnapshot serverModelCatalog = new ServerModelCatalogSnapshot();
    private final ServerDisguiseRequest serverDisguiseRequest = new ServerDisguiseRequest();
    private List<ServerCachedModelCatalog.Model> cachedServerCatalog = List.of();
    private final Map<String, LocalModelLibrary.Loaded> cachedServerPreviews = new LinkedHashMap<>(16, .75f, true);
    private final Set<String> cachedServerPreviewLoading = new HashSet<>();
    private final Map<String, Long> cachedServerPreviewRetry = new HashMap<>();
    private final Map<String, String> cachedServerPreviewErrors = new HashMap<>();
    private boolean serverCacheIndexLoading, serverCacheIndexRefreshPending, serverCacheIndexReady;
    private long serverCacheCatalogRevision, serverCachePreviewEpoch;

    private long pushOffersReceived, pushCacheHits, pushCacheMisses, pushTransfersBegun, pushTransfersCompleted,
            pushGpuPrepared, pushReadySent, pushRenderAcks, pushAssetRequests, pushCancelled, pushRejected;
    private long generation, lastHello, lastHeartbeat, lastReceived;
    private long localTick;
    private int leaseTicks = 100, maxPayload = 16_000;
    private Object world;
    private String lastError = "", previewId = "", previewHash = "", previewManual = "", previewPose = "";
    private long previewStarted, previewManualStarted, previewJumpUntil;
    private long previewRequest;
    private boolean previewPending;
    private boolean previewWasGround = true;
    private Binding localSelf;
    private final LocalAppearanceVisibility localAppearanceVisibility = new LocalAppearanceVisibility();
    private final PlayerInteractionPolicy playerInteractionPolicy = new PlayerInteractionPolicy();
    private String knownOwnServerModelId = "";
    private boolean localAppearancePending;
    private long localAppearanceRequest, localAppearanceRetryAfter, requestPacketsSent;
    private long ownAppearanceMissingSince, lastAppearanceSnapshotRequest;
    private long lastPrivatePauseSaveAttempt;
    private String localAppearanceError = "";

    public ClientRuntime(MinecraftClient client) {
        this.client = client;
        this.options = new ClientOptions(
                FabricLoader.getInstance().getConfigDir().resolve("meplayeractions-client.json"));
        if (this.options.privateAppearancePaused) {
            this.playerInteractionPolicy.pauseLocalAppearance();
        }

        this.privateModels = new PrivateModelSyncClient(new PrivateModelSyncClient.Host() {
            @Override
            public boolean channelAvailable() {
                return ClientRuntime.this.connected && client.getNetworkHandler() != null
                        && ClientPlayNetworking.canSend(PrivateModelPayload.ID);
            }

            @Override
            public boolean viewEnabled() {
                return ClientRuntime.this.options.enabled;
            }

            @Override
            public boolean send(JsonObject packet) {
                return sendPrivate(packet);
            }

            @Override
            public PrivateModelSyncClient.Local local() {
                return privateLocalSnapshot();
            }

            @Override
            public CompletableFuture<byte[]> bundle(String id) {
                return decodeAsync(() -> ClientRuntime.this.localModelLibrary.sourceBundle(id));
            }

            @Override
            public CompletableFuture<PrivateModelSyncClient.SourceIdentity> sourceIdentity(String id) {
                return decodeAsync(() -> {
                    byte[] bytes = ClientRuntime.this.localModelLibrary.sourceBundle(id);
                    return new PrivateModelSyncClient.SourceIdentity(AssetTransfer.hash(bytes),
                            NativeModelBundle.validate(bytes).kind(), bytes.length);
                });
            }

            @Override
            public void savedModelDeleted(String id, String hash) {
                // The relay confirms deletion stopped this source publication; the saved catalog hash may be a newer version.
                // Keep the private model locally, but do not republish the removed server archive next tick.
                if (ClientRuntime.this.options.privateSyncEnabled && localAppearance().enabled()
                        && localAppearance().modelId().equals(id)) {
                    ClientRuntime.this.options.privateSyncEnabled = false;
                    ClientRuntime.this.options.save();
                }
            }

            @Override
            public CompletableFuture<byte[]> cached(String hash) {
                return decodeAsync(() -> ClientRuntime.this.privateModelCache.readValidated(hash).orElse(null));
            }

            @Override
            public CompletableFuture<LocalModelLibrary.Loaded> decode(byte[] bytes, String texture) {
                return decodeAsync(() -> {
                    LocalModelLibrary.Loaded loaded = NativeModelBundle.decode(bytes, texture);
                    String hash = AssetTransfer.hash(bytes);
                    for (String id : List.of(BuiltinYsmModels.ALEX_ID, BuiltinYsmModels.STEVE_ID)) {
                        if (hash.equals(AssetTransfer.hash(ClientRuntime.this.localModelLibrary.sourceBundle(id)))) {
                            ClientRuntime.this.privateLogicalSourceIds.put(hash, BuiltinYsmModels.logicalSourceId(id));
                        }

                    }

                    return loaded;
                });
            }

            @Override
            public void cache(String hash, byte[] bytes) {
                decodeAsync(() -> {
                    ClientRuntime.this.privateModelCache.writeValidated(hash, bytes);
                    return null;
                });
            }

            @Override
            public void dispatch(Runnable task) {
                client.execute(task);
            }

            @Override
            public boolean prepare(PrivateModelSyncClient.Remote remote, LocalModelLibrary.Loaded loaded) {
                return ClientRuntime.this.assets.containsKey(loaded.hash()) && ModelRenderer.has(loaded.hash());
            }

            @Override
            public void prepareAsync(PrivateModelSyncClient.Remote remote, LocalModelLibrary.Loaded loaded,
                    BooleanSupplier current, Consumer<Boolean> completion) {
                install(loaded.hash(), loaded.model(), () -> current.getAsBoolean() && client.world != null,
                        success -> {
                            if (success && current.getAsBoolean()) {
                                Binding binding = new Binding(remote.owner, remote.generation.toString(),
                                        "private:" + remote.hash, loaded.hash());
                                binding.profile = loaded.profile();
                                binding.motion = localMotionPolicy(loaded.model());
                                binding.hidePlayer = true;
                                ClientRuntime.this.privateBindings.put(remote.owner, binding);
                                ClientRuntime.this.privateExtraSequences.remove(remote.owner);
                            }
                            completion.accept(success && current.getAsBoolean());
                        });
            }

            @Override
            public void remove(UUID owner, UUID instance) {
                Binding binding = ClientRuntime.this.privateBindings.get(owner);
                if (binding != null && binding.instance.equals(instance.toString())) {
                    ClientRuntime.this.privateBindings.remove(owner);
                    ClientRuntime.this.privateExtraSequences.remove(owner);
                    if (client.player == null || !owner.equals(client.player.getUuid())) {
                        closeModelEffects(owner);
                    }

                }
                ClientRuntime.this.pendingPrivateEvents
                        .removeIf(event -> event.owner().equals(owner) && event.instance().equals(instance.toString()));
            }

            @Override
            public void state(PrivateModelSyncClient.Remote remote) {
                applyPrivateState(remote);
            }

            @Override
            public void event(UUID owner, UUID instance, List<Double> args) {
                String target = client.player != null && owner.equals(client.player.getUuid())
                        && ClientRuntime.this.localSelf != null ? ClientRuntime.this.localSelf.instance
                                : instance.toString();
                if (!ModelRenderer.applyAuthorSync(owner, target, args)) {
                    if (ClientRuntime.this.pendingPrivateEvents.size() >= 16) {
                        ClientRuntime.this.pendingPrivateEvents.removeFirst();
                    }

                    ClientRuntime.this.pendingPrivateEvents
                            .addLast(new PendingSync(owner, target, args, System.nanoTime() + 5 * SECOND));
                }
            }
        });
        refreshServerCacheIndex();
    }

    private <T> CompletableFuture<T> decodeAsync(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            this.privateDecoder.execute(() -> {
                try {
                    result.complete(task.call());
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException busy) {
            result.completeExceptionally(busy);
        }
        return result;
    }

    public void joined() {
        reset();
        this.connected = true;
        this.world = this.client.world;
    }

    public void reset() {
        this.textureInstalls.clear();
        this.textureInstallSlots.clear();
        this.options.flushRoamingVariables();
        this.lastRoamingFlush = 0;
        if (this.privateModels != null) {
            this.privateModels.reset();
        }
        this.privateBindings.clear();
        this.privateExtraSequences.clear();
        this.pendingPrivateEvents.clear();
        closeModelEffects();
        VanillaYsmQueries.reset();
        releaseBindings();
        abortTransfers();
        this.connected = false;
        this.acknowledged = false;
        this.loading.clear();
        this.failedAssets.clear();
        this.assets.clear();
        this.assetMetadata.clear();
        this.packAssets.clear();
        this.serverAssets.clear();
        this.serverModelAssets.clear();
        this.clock.reset();
        this.pushNegotiated = false;
        this.unsupportedHandshake = false;
        this.serverAssetMode = "";
        this.serverCapabilities = List.of();
        this.stateProtocol.reset();
        this.serverModelCatalog.reset();
        this.serverDisguiseRequest.reset();
        this.lastHello = 0;
        this.lastHeartbeat = 0;
        this.lastReceived = 0;
        this.previewId = "";
        this.previewHash = "";
        this.previewManual = "";
        this.previewPose = "";
        this.localSelf = null;
        this.localAppearanceError = "";
        this.localAppearanceRetryAfter = 0;
        this.ownAppearanceMissingSince = 0;
        this.lastAppearanceSnapshotRequest = 0;
        this.localAppearanceVisibility.reset();
        this.playerInteractionPolicy.reset();
        this.knownOwnServerModelId = "";
        this.serverCachePreviewEpoch++;
        this.cachedServerPreviews.clear();
        this.cachedServerPreviewLoading.clear();
        this.cachedServerPreviewRetry.clear();
        this.cachedServerPreviewErrors.clear();
        ModelRenderer.clear();
    }

    public void tick() {
        this.localTick++;
        long now = System.nanoTime();
        this.failedAssets.prune(now);
        this.serverDisguiseRequest.expire(now);
        if (now - this.lastRoamingFlush >= SECOND) {
            this.options.flushRoamingVariables();
            this.lastRoamingFlush = now;
        }
        if (this.client.world != this.world) {
            boolean wasConnected = this.connected;
            reset();
            this.connected = wasConnected;
            this.world = this.client.world;
        }
        pumpTextureInstalls();
        ModelRenderer.pumpTexturePreparation();
        // This private mode is independent of the server handshake and also runs in single player.
        ensureLocalAppearance();
        updateLocalAppearanceMotion();
        this.privateModels.tick(now);
        for (PendingSync event : List.copyOf(this.pendingPrivateEvents)) {
            if (now > event.expires() || !privateEventCurrent(event)
                    || ModelRenderer.applyAuthorSync(event.owner(), event.instance(), event.args())) {
                this.pendingPrivateEvents.remove(event);
            }

        }
        tickModelEffects();
        if (!this.connected || this.client.getNetworkHandler() == null) {
            return;
        }

        if (this.acknowledged && this.stateProtocol.requestedTimeline() != this.options.followServerTimeline) {
            // Keep the known server appearance while its new snapshot is pending; only its render leases end.
            releaseBindings();
            abortTransfers();
            this.acknowledged = false;
            this.pushNegotiated = false;
            this.stateProtocol.reset();
            this.serverModelCatalog.reset();
            this.serverModelAssets.clear();
            this.serverDisguiseRequest.reset();
            this.lastHello = 0;
        }
        if (this.acknowledged && now - this.lastReceived > this.leaseTicks * 50_000_000L) {
            releaseBindings();
            abortTransfers();
            this.acknowledged = false;
            this.pushNegotiated = false;
            this.stateProtocol.reset();
            this.serverModelCatalog.reset();
            this.serverModelAssets.clear();
            this.serverDisguiseRequest.failed("服务器同步中断；伪装请求未确认");
            this.lastHello = 0;
            this.lastError = "服务器同步已超时，恢复服务器显示";
        }
        // Rendering is optional; the server action session and its authoritative catalogue are not.
        if (!this.acknowledged && !this.unsupportedHandshake && now - this.lastHello > 3 * SECOND
                && ClientPlayNetworking.canSend(ActionPayload.ID)) {
            JsonObject hello = WireJson.envelope("hello");
            hello.addProperty("clientVersion", clientVersion());
            List<String> capabilities = this.stateProtocol.nextHelloCapabilities(this.options.followServerTimeline);
            JsonArray caps = new JsonArray();
            capabilities.forEach(caps::add);
            hello.add("capabilities", caps);
            if (send(hello)) {
                this.stateProtocol.helloSent(capabilities, this.options.followServerTimeline);
            }
            this.lastHello = now;
        }
        if (this.acknowledged) {
            for (Binding binding : List.copyOf(this.bindings.values())) {
                if (now - binding.lastPacket > this.leaseTicks * 50_000_000L) {
                    failed(binding, "模型状态超时");
                    this.bindings.remove(binding.owner);
                    continue;
                }
                if (this.options.enabled
                        && (this.client.player == null || !binding.owner.equals(this.client.player.getUuid())
                                || binding.showSelf)
                        && !binding.hash.isEmpty() && this.assets.containsKey(binding.hash)
                        && ModelRenderer.has(binding.hash) && !binding.active && !binding.unsupported
                        && now - binding.lastReady > SECOND
                        && !this.failedAssets.coolingDown(binding.hash, now)) {
                    JsonObject ready = identity("render_ready", binding);
                    if (send(ready)) {
                        binding.readySent = true;
                        binding.lastReady = now;
                        binding.assetState = "等待服务器渲染确认";
                        this.pushReadySent++;
                    }
                }
            }
            if (now - this.lastHeartbeat > SECOND) {
                sendHeartbeats();
                this.lastHeartbeat = now;
            }
            // A lost lease can remove the server binding before an undisguise notice is sent.
            // A bounded full snapshot proves when private rendering can safely resume.
            boolean missingOwn = this.localAppearanceVisibility.hasServerAppearance() && this.client.player != null
                    && !this.bindings.containsKey(this.client.player.getUuid());
            if (!missingOwn) {
                this.ownAppearanceMissingSince = 0;
            }

            else {
                if (this.ownAppearanceMissingSince == 0) {
                    this.ownAppearanceMissingSince = now;
                }

                if (now - this.ownAppearanceMissingSince > 200_000_000L) {
                    requestAppearanceSnapshot(now);
                }

            }
        }
        prunePushOffers(now);
        if (this.options.enabled && !this.options.followServerTimeline && this.client.world != null) {
            for (Binding binding : this.bindings.values()) {
                BbModel model = this.assets.get(binding.hash);
                PlayerEntity player = this.client.world.getPlayerByUuid(binding.owner);
                if (model != null && player != null && usable(binding, now)) {
                    entityBinding(binding, model, player);
                }

            }
        }

    }

    private void requestAppearanceSnapshot(long now) {
        // Share the bound across own-binding recovery and every fragmented heartbeat gap.
        if (now - this.lastAppearanceSnapshotRequest > 2 * SECOND && send(WireJson.envelope("snapshot_request"))) {
            this.lastAppearanceSnapshotRequest = now;
        }

    }

    public void receive(byte[] bytes) {
        try {
            JsonObject json = WireJson.decode(bytes);
            String type = WireJson.string(json, "type", 32);
            long now = System.nanoTime();
            if (type.equals("hello_ack")) {
                if (!WireJson.string(json, "mode", 32).equals("local-render")) {
                    throw new IllegalArgumentException("Server mode");
                }

                List<String> caps = readCapabilities(json);
                String mode = json.has("assetMode") ? WireJson.string(json, "assetMode", 32) : "";
                if (!ServerPushAuthorization.acceptsHandshake(mode, caps)) {
                    releaseBindings();
                    abortTransfers();
                    this.acknowledged = false;
                    this.pushNegotiated = false;
                    this.stateProtocol.reset();
                    this.serverModelCatalog.reset();
                    this.serverModelAssets.clear();
                    this.serverDisguiseRequest.reset();
                    this.unsupportedHandshake = true;
                    this.serverAssetMode = mode;
                    this.serverCapabilities = caps;
                    this.lastHello = now;
                    this.lastError = "服务器未协商模型主动推送，请升级 MEPlayerActions 服务端到 0.4.2；保持服务器显示";
                    return;
                }
                this.leaseTicks = (int) WireJson.integer(json, "leaseTicks", 20, 400);
                this.maxPayload = (int) WireJson.integer(json, "maxPayload", 384, 32_766);
                this.localAppearanceVisibility.serverSessionStarted();
                releaseBindings();
                abortTransfers();
                this.serverModelCatalog.reset();
                this.serverModelAssets.clear();
                this.serverDisguiseRequest.reset();
                this.clock.observe(WireJson.integer(json, "serverTick", 0, 0xffff_ffffL), now);
                this.serverAssetMode = mode;
                this.serverCapabilities = caps;
                this.pushNegotiated = true;
                this.unsupportedHandshake = false;
                this.stateProtocol.acknowledge(caps);
                this.acknowledged = true;
                this.lastReceived = now;
                this.lastError = "";
                return;
            }
            if (!this.acknowledged) {
                if (type.equals("error")) {
                    String code = WireJson.string(json, "code", 64);
                    if (this.lastHello != 0 && this.stateProtocol.fallbackForError(code)) {
                        this.unsupportedHandshake = false;
                        this.lastHello = 0;
                        this.lastError = "";
                        return;
                    }
                    if (this.lastHello != 0 && Set
                            .of("unsupported_protocol", "invalid_payload", "unsupported_capability").contains(code)) {
                        this.unsupportedHandshake = true;
                        this.lastError = "服务器不支持模型主动推送，请升级 MEPlayerActions 服务端到 0.4.2（" + code + "）；保持服务器显示";
                    } else {
                        this.lastError = "服务器：" + code;
                    }

                }
                return;
            }
            switch (type) {
            case "server_model_catalog" -> {
                if (!this.stateProtocol.serverCatalog()) {
                    throw new IllegalArgumentException("Unnegotiated server model catalogue");
                }

                if (this.serverModelCatalog.accept(json)) {
                    this.serverModelAssets.keySet().retainAll(this.serverModelCatalog.models().stream()
                            .map(ServerModelCatalogSnapshot.Model::id).toList());
                    if (!this.serverDisguiseRequest.modelId().isEmpty()
                            && !this.serverModelCatalog.contains(this.serverDisguiseRequest.modelId())) {
                        this.serverDisguiseRequest.failed("服务器已移除该模型或撤销伪装权限");
                    }

                }
            }
            case "state" -> state(json, now);
            case "disguise_result" -> acceptDisguiseResult(json, now);
            case "render_ack" -> {
                Binding binding = matching(json);
                if (this.options.enabled && binding != null && binding.readySent
                        && this.assets.containsKey(binding.hash) && ModelRenderer.has(binding.hash)) {
                    binding.active = true;
                    binding.assetState = "本地渲染";
                    binding.assetError = "";
                    this.pushRenderAcks++;
                }
            }
            case "unbind" -> {
                UUID owner = UUID.fromString(WireJson.string(json, "owner", 36));
                String instance = WireJson.string(json, "instance", 36);
                Binding binding = this.bindings.get(owner);
                if (binding != null && binding.instance.equals(instance)) {
                    this.bindings.remove(owner);
                    closeModelEffects(owner);
                }
                prunePushOffers(now);
                String reason = WireJson.string(json, "reason", 128);
                if (this.client.player != null && owner.equals(this.client.player.getUuid())) {
                    this.localAppearanceVisibility.serverUnbound(instance, reason);
                }

                if (Set.of("plugin-close", "plugin_stopping", "sync_disabled", "session_ended").contains(reason)) {
                    releaseBindings();
                    abortTransfers();
                    this.acknowledged = false;
                    this.pushNegotiated = false;
                    this.stateProtocol.reset();
                    this.serverModelCatalog.reset();
                    this.serverModelAssets.clear();
                    this.serverDisguiseRequest.reset();
                    this.lastHello = 0;
                }
            }
            case "heartbeat" -> {
                long serverTick = WireJson.integer(json, "serverTick", 0, 0xffff_ffffL);
                boolean gap = this.stateProtocol.renewBindings(json, this.bindings,
                        binding -> new IncrementalStateProtocol.Identity(binding.owner, binding.instance, binding.hash),
                        (binding, received) -> binding.lastPacket = received, now);
                if (gap) {
                    requestAppearanceSnapshot(now);
                }

                this.clock.observe(serverTick, now);
            }
            case "asset_offer" -> acceptOffer(json, now);
            case "asset_begin" -> begin(json, now);
            case "asset_chunk" -> pushChunk(json, now);
            case "asset_end" -> finish(json, now);
            case "asset_cancel" -> cancelOffer(json);
            case "error" -> {
                String code = WireJson.string(json, "code", 64);
                this.lastError = "服务器：" + code;
                if ((code.equals("render_unavailable") || code.equals("render_not_authorized")) && json.has("owner")) {
                    Binding binding = matching(json);
                    if (binding != null) {
                        binding.active = false;
                        binding.readySent = false;
                        binding.assetState = "保持服务器显示";
                        binding.assetError = code.equals("render_not_authorized") ? "服务器未授权此次客户端接管" : "服务器暂时无法确认客户端接管";
                        this.failedAssets.put(binding.hash, now);
                    }
                }
            }
            case "snapshot_begin" ->
                this.localAppearanceVisibility.beginSnapshot(WireJson.integer(json, "snapshotId", 0, Long.MAX_VALUE));
            case "snapshot_end" ->
                this.localAppearanceVisibility.endSnapshot(WireJson.integer(json, "snapshotId", 0, Long.MAX_VALUE));
            default -> throw new IllegalArgumentException("Unknown packet");
            }
            this.lastReceived = now;
        } catch (Exception exception) {
            this.lastError = "同步包校验失败，恢复服务器显示";
            MEPlayerActionsClient.LOGGER.warn("Rejected MPA packet: {}", exception.toString());
            releaseBindings();
            abortTransfers();
            this.acknowledged = false;
            this.pushNegotiated = false;
            this.stateProtocol.reset();
            this.serverModelCatalog.reset();
            this.serverModelAssets.clear();
            this.serverDisguiseRequest.failed("服务器同步校验失败；伪装请求未确认");
            this.lastHello = System.nanoTime();
        }
    }

    private void state(JsonObject json, long now) {
        UUID owner = UUID.fromString(WireJson.string(json, "owner", 36));
        if (this.client.player != null && owner.equals(this.client.player.getUuid())
                && (!this.previewId.isEmpty() || this.previewPending)) {
            this.previewRequest++;
            this.previewPending = false;
            this.previewId = "";
            this.previewHash = "";
            this.previewManual = "";
        }
        String instance = UUID.fromString(WireJson.string(json, "instance", 36)).toString();
        String modelId = WireJson.modelId(json), hash = WireJson.string(json, "assetHash", 64);
        if (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Hash");
        }

        long sequence = WireJson.integer(json, "sequence", 0, Long.MAX_VALUE),
                rawTick = WireJson.integer(json, "serverTick", 0, 0xffff_ffffL);
        long tick = this.clock.unwrap(rawTick);
        TransformTimeline.Transform transform = new TransformTimeline.Transform(tick,
                WireJson.number(json, "x", -30_000_000, 30_000_000), WireJson.number(json, "y", -4096, 4096),
                WireJson.number(json, "z", -30_000_000, 30_000_000),
                (float) WireJson.number(json, "bodyYaw", -360_000, 360_000),
                (float) WireJson.number(json, "headYaw", -360_000, 360_000),
                (float) WireJson.number(json, "headPitch", -360, 360));
        float scale = (float) WireJson.number(json, "scale", 0.05, 8);
        LocalMotionPolicy motion = LocalMotionPolicy.read(json.getAsJsonObject("motion"));
        Map<String, Double> accessories = readAccessoryState(json);
        boolean hide = WireJson.bool(json, "hidePlayer"), showSelf = WireJson.bool(json, "showSelf");
        Boolean sourceInvisible = NativeBodyVisibility.readSourceInvisible(json);
        List<Layer> layers = new ArrayList<>();
        JsonArray array = json.getAsJsonArray("layers");
        if (array == null || array.size() > 16) {
            throw new IllegalArgumentException("Layers");
        }

        Set<String> layerNames = new HashSet<>();
        for (JsonElement value : array) {
            JsonObject layer = value.getAsJsonObject();
            String name = WireJson.string(layer, "layer", 32), animation = WireJson.string(layer, "animation", 128);
            String loop = WireJson.string(layer, "loop", 16);
            if (!layerNames.add(name) || !Set.of("ONCE", "LOOP", "HOLD").contains(loop)) {
                throw new IllegalArgumentException("Layer mode");
            }

            long rawStarted = WireJson.integer(layer, "startedAtTick", 0, 0xffff_ffffL);
            long started = tick - ((rawTick - rawStarted) & 0xffff_ffffL);
            layers.add(new Layer(name, animation, started, WireJson.number(layer, "speed", 0.01, 20), loop,
                    (int) WireJson.integer(layer, "inTicks", 0, 200),
                    (int) WireJson.integer(layer, "outTicks", 0, 200)));
        }
        Binding binding = this.bindings.get(owner);
        boolean sameBinding = binding != null && binding.instance.equals(instance) && binding.hash.equals(hash)
                && binding.modelId.equals(modelId);
        List<Action> actions = this.stateProtocol.animations(json, sameBinding ? binding.actions : List.of(),
                animation -> new Action(animation.id(), animation.label()));
        if (binding == null || !binding.instance.equals(instance) || !binding.hash.equals(hash)
                || !binding.modelId.equals(modelId)) {
            if (binding != null) {
                failed(binding, "");
            }

            if (binding == null && this.bindings.size() >= 64) {
                return;
            }

            binding = new Binding(owner, instance, modelId, hash);
            this.bindings.put(owner, binding);
        }
        if (sequence < binding.sequence || !binding.timeline.add(transform)) {
            return;
        }

        if (this.client.player != null && owner.equals(this.client.player.getUuid())) {
            this.localAppearanceVisibility.serverOwnState(instance);
            this.serverDisguiseRequest.confirmed(modelId, instance);
            interactionPolicy();
        }
        binding.sequence = sequence;
        binding.layers = List.copyOf(layers);
        binding.scale = scale;
        binding.accessories = accessories;
        binding.foodLevel = json.has("foodLevel") ? (int) WireJson.integer(json, "foodLevel", 0, 20) : 20;
        binding.layerTimeline.add(tick, binding.layers);
        binding.motion = motion;
        binding.localServerLayers = binding.localClock.accept(tick, this.localTick, binding.layers);
        binding.hidePlayer = hide;
        binding.showSelf = showSelf;
        binding.sourceInvisible = sourceInvisible;
        binding.actions = List.copyOf(actions);
        binding.lastPacket = now;
        binding.serverAssetStatus = json.has("assetStatus") ? WireJson.string(json, "assetStatus", 64) : "";
        binding.serverAssetReason = json.has("assetReason") ? WireJson.string(json, "assetReason", 256) : "";
        binding.serverAssetSource = json.has("assetSource") ? WireJson.string(json, "assetSource", 128) : "";
        BbModel loaded = this.assets.get(hash);
        binding.unsupported = loaded != null
                && layers.stream().anyMatch(layer -> !loaded.animations().contains(layer.animation()));
        if (binding.unsupported) {
            failed(binding, "客户端模型缺少对应动画，请更新服务器 bbmodel");
        }

        this.clock.observe(rawTick, now);
        if (hash.isEmpty()) {
            binding.assetState = switch (binding.serverAssetStatus) {
            case "pending" -> "服务器正在准备模型";
            case "missing" -> "服务器缺少客户端原模型";
            case "invalid" -> "服务器原模型无效";
            case "server-only" -> "该模型仅服务器渲染";
            default -> "服务器模型资产不可用";
            };
            binding.assetError = binding.serverAssetReason.isEmpty() ? "服务端未提供客户端原模型，保持服务器显示"
                    : binding.serverAssetReason;
        } else if (!this.assets.containsKey(hash) && binding.assetState.equals("等待服务器模型")) {
            binding.assetError = "";
        }
        prunePushOffers(now);
    }

    private void acceptDisguiseResult(JsonObject json, long now) {
        if (!this.stateProtocol.disguiseResults()) {
            throw new IllegalArgumentException("Unnegotiated disguise result");
        }

        String raw = WireJson.string(json, "requestId", 36);
        UUID nonce = UUID.fromString(raw);
        if (!nonce.toString().equals(raw)) {
            throw new IllegalArgumentException("Disguise result UUID");
        }

        String id = WireJson.modelId(json);
        boolean success = WireJson.bool(json, "success");
        String instance = "", code = "";
        ServerDisguisePreferences actual = null;
        if (success) {
            instance = WireJson.string(json, "instance", 36);
            if (!UUID.fromString(instance).toString().equals(instance)) {
                throw new IllegalArgumentException("Disguise result instance");
            }

            JsonObject values = json.getAsJsonObject("options");
            if (values == null) {
                throw new IllegalArgumentException("Disguise result options");
            }

            actual = new ServerDisguisePreferences(WireJson.number(values, "scale", .05, 8),
                    WireJson.bool(values, "hideSelf"), WireJson.bool(values, "showSelf"),
                    WireJson.number(values, "viewDistance", .1, 256),
                    (int) WireJson.integer(values, "maxViewers", 0, 1000),
                    (int) WireJson.integer(values, "delay", 0, 20), WireJson.string(values, "effect", 64));
        } else {
            code = WireJson.string(json, "code", 64);
        }

        this.serverDisguiseRequest.expire(now);
        if (this.serverDisguiseRequest.result(nonce, id, success, instance, actual, code)) {
            Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
            if (own != null && this.localAppearanceVisibility.hasServerAppearance()) {
                this.serverDisguiseRequest.confirmed(own.modelId, own.instance);
            }

        }
    }

    private static Map<String, Double> readAccessoryState(JsonObject json) {
        JsonElement value = json.get("accessories");
        if (value == null) {
            return Map.of();
        }

        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("Accessories");
        }

        JsonObject accessories = value.getAsJsonObject();
        if (accessories.size() == 0) {
            return Map.of();
        }

        if (!accessories.keySet().equals(Set.of("a", "b"))) {
            throw new IllegalArgumentException("Accessory fields");
        }

        return Map.of("a", WireJson.number(accessories, "a", 0, 1), "b", WireJson.number(accessories, "b", 0, 1));
    }

    private LocalModelLibrary.Loaded loadPackModel(String id, String hash) throws IOException {
        ResourceManager manager = this.client.getResourceManager();
        return PackModelLibrary.load((resource, maximum) -> {
            Optional<Resource> found = manager.getResource(Identifier.of(resource));
            if (found.isEmpty()) {
                throw new IOException("缺少资源 " + resource);
            }

            try (InputStream input = found.get().getInputStream()) {
                byte[] data = input.readNBytes(maximum + 1);
                if (data.length == 0 || data.length > maximum) {
                    throw new IOException("资源大小超出限制");
                }

                return data;
            }
        }, id, hash);
    }

    private static String clientVersion() {
        return FabricLoader.getInstance().getModContainer("meplayeractions")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("0.4.2");
    }

    private static List<String> readCapabilities(JsonObject json) {
        JsonArray values = json.getAsJsonArray("capabilities");
        if (values == null || values.size() > 16) {
            throw new IllegalArgumentException("Server capabilities");
        }

        Set<String> result = new LinkedHashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Capability type");
            }

            String capability = value.getAsString();
            if (!capability.matches("[a-z_]{1,64}") || !result.add(capability)) {
                throw new IllegalArgumentException("Capability value");
            }

        }
        return List.copyOf(result);
    }

    private static UUID offerId(JsonObject json) {
        String value = WireJson.string(json, "offerId", 36);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) {
            throw new IllegalArgumentException("Offer UUID");
        }

        return id;
    }

    private List<ServerPushAuthorization.Identity> pushBindings() {
        return this.bindings.values().stream().filter(binding -> !binding.hash.isEmpty())
                .map(binding -> new ServerPushAuthorization.Identity(binding.owner, binding.instance, binding.modelId,
                        binding.hash))
                .toList();
    }

    private boolean currentPush(ServerPushAuthorization.Offer offer, long epoch) {
        return epoch == this.generation && this.pushNegotiated && this.acknowledged && this.client.world == this.world
                && this.pushAuthorization.current(offer, pushBindings(), System.nanoTime());
    }

    private void pushState(String hash, String state, String error) {
        for (Binding binding : this.bindings.values()) {
            if (binding.hash.equals(hash)) {
                binding.assetState = state;
                binding.assetError = error;
            }
        }

    }

    private boolean assetStatus(ServerPushAuthorization.Offer offer, String status) {
        JsonObject feedback = WireJson.envelope("asset_status");
        feedback.addProperty("offerId", offer.offerId().toString());
        feedback.addProperty("hash", offer.identity().hash());
        feedback.addProperty("status", status);
        return send(feedback);
    }

    private void acceptOffer(JsonObject json, long now) {
        if (!this.pushNegotiated) {
            throw new IllegalArgumentException("Unnegotiated server push");
        }

        ServerPushAuthorization.Identity identity = new ServerPushAuthorization.Identity(
                UUID.fromString(WireJson.string(json, "owner", 36)), WireJson.string(json, "instance", 36),
                WireJson.modelId(json), WireJson.hash(json, "hash"));
        ServerPushAuthorization.Offer offer = new ServerPushAuthorization.Offer(offerId(json), identity);
        this.pushOffersReceived++;
        try {
            if (!this.pushAuthorization.offer(offer, pushBindings(), now)) {
                return;
            }

        } catch (IllegalArgumentException rejected) {
            this.pushRejected++;
            assetStatus(offer, "rejected");
            return;
        }
        String hash = identity.hash();
        long epoch = this.generation;
        BbModel prepared = this.assets.get(hash);
        if (prepared != null && ModelRenderer.has(hash)) {
            completePush(offer, this.assetMetadata.getOrDefault(hash,
                    new BbModelAsset.Decoded(prepared, "", YsmModelProfile.empty())), true, epoch);
            return;
        }
        this.loading.add(hash);
        pushState(hash, "正在校验服务器模型缓存", "");
        try {
            this.decoder.execute(() -> {
                BbModelAsset.Decoded model = null;
                try {
                    Optional<byte[]> cached = this.serverModelCache.readValidated(hash);
                    if (cached.isPresent()) {
                        model = BbModelAsset.read(cached.get());
                    }

                } catch (IOException | RuntimeException corrupt) {
                    MEPlayerActionsClient.LOGGER.debug("Server model cache parse rejected {}", hash.substring(0, 12),
                            corrupt);
                }
                BbModelAsset.Decoded result = model;
                this.client.execute(() -> {
                    if (!currentPush(offer, epoch)) {
                        return;
                    }

                    if (result != null) {
                        this.cachedServerPreviewErrors.remove(hash);
                        this.cachedServerPreviewRetry.remove(hash);
                        completePush(offer, result, true, epoch);
                        return;
                    }
                    this.pushCacheMisses++;
                    pushState(hash, "正在下载服务器模型", "");
                    this.pushAuthorization.missing(offer, pushBindings(), System.nanoTime());
                    if (!assetStatus(offer, "missing")) {
                        rejectPush(offer, "无法反馈服务器模型缓存状态");
                    }

                });
            });
        } catch (RejectedExecutionException busy) {
            rejectPush(offer, "服务器模型缓存校验队列已满");
        }
    }

    private void completePush(ServerPushAuthorization.Offer offer, BbModelAsset.Decoded decoded, boolean cached,
            long epoch) {
        completePush(offer, decoded, cached, epoch, null);
    }

    private void completePush(ServerPushAuthorization.Offer offer, BbModelAsset.Decoded decoded, boolean cached,
            long epoch, byte[] validatedBytes) {
        if (!currentPush(offer, epoch)) {
            return;
        }

        String hash = offer.identity().hash();
        pushState(hash, "准备服务器模型渲染", "");
        BbModel model = decoded.model();
        if (cached && !assetStatus(offer, "cached")) {
            rejectPush(offer, "无法确认已校验的服务器模型缓存");
            return;
        }
        install(hash, model, () -> currentPush(offer, epoch), success -> {
            if (!currentPush(offer, epoch)) {
                return;
            }
            boolean supported = this.bindings.values().stream().filter(binding -> binding.hash.equals(hash))
                    .anyMatch(binding -> !binding.unsupported);
            if (!success || !supported) {
                rejectPush(offer, "服务器模型无法准备渲染或缺少对应动画");
                return;
            }
            this.failedAssets.remove(hash);
            this.loading.remove(hash);
            this.pushGpuPrepared++;
            if (cached) {
                this.pushCacheHits++;
            } else {
                this.pushTransfersCompleted++;
            }

            this.assetMetadata.put(hash, decoded);
            this.serverAssets.add(hash);
            this.serverModelAssets.put(offer.identity().modelId(), hash);
            if (cached) {
                rememberCachedServerModel(offer.identity().modelId(), hash);
            }

            pushState(hash, "等待服务器渲染确认", "");
            if (validatedBytes != null) {
                cachePreparedPush(offer.identity().modelId(), hash, validatedBytes, epoch);
            }
            this.pushAuthorization.remove(offer);
        });
    }

    private void cachePreparedPush(String modelId, String hash, byte[] raw, long epoch) {
        try {
            this.decoder.execute(() -> {
                try {
                    boolean changed = this.serverModelCache.writeValidated(modelId, hash, raw);
                    this.client.execute(() -> {
                        if (epoch != this.generation) {
                            return;
                        }
                        this.cachedServerPreviewErrors.remove(hash);
                        this.cachedServerPreviewRetry.remove(hash);
                        if (changed) {
                            refreshServerCacheIndex();
                        }
                    });
                } catch (IOException failure) {
                    MEPlayerActionsClient.LOGGER.debug("Server model cache write skipped {}: {}", hash.substring(0, 12),
                            failure.toString());
                }
            });
        } catch (RejectedExecutionException busy) {
            MEPlayerActionsClient.LOGGER.debug("Server model cache write queue full {}", hash.substring(0, 12));
        }
    }

    private void rejectPush(ServerPushAuthorization.Offer offer, String reason) {
        if (!this.pushAuthorization.remove(offer)) {
            return;
        }

        this.pushRejected++;
        this.loading.remove(offer.identity().hash());
        assetStatus(offer, "rejected");
        failAsset(offer.identity().hash(), reason);
        pushState(offer.identity().hash(), "服务器模型同步失败", reason);
    }

    private void prunePushOffers(long now) {
        for (ServerPushAuthorization.Offer offer : this.pushAuthorization.prune(pushBindings(), now)) {
            this.pushCancelled++;
            this.loading.remove(offer.identity().hash());
            assetStatus(offer, "rejected");
            pushState(offer.identity().hash(), "等待服务器模型", "模型推送已取消或超时，保持服务器显示");
        }
    }

    public Set<String> resourcesReloaded() {
        closeModelEffects();
        // Server-pushed models own embedded textures and remain valid across an unrelated RP reload.
        // Invalidate only resource/private preview callbacks; do not discard in-flight push grants.
        this.previewRequest++;
        this.previewPending = false;
        this.localAppearanceRequest++;
        this.localAppearancePending = false;
        this.failedAssets.clear();
        Set<String> refresh = new HashSet<>(this.packAssets);
        refresh.removeAll(this.serverAssets);
        refresh.forEach(hash -> {
            this.assets.remove(hash);
            this.assetMetadata.remove(hash);
        });
        this.packAssets.clear();
        if (refresh.contains(this.previewHash)) {
            this.previewHash = "";
            this.previewId = "";
        }
        return refresh;
    }

    /** Independent browser decoding; it never creates a world binding or acknowledges a server lease. */
    public CompletableFuture<LocalModelLibrary.Loaded> loadLocalPreview(String id) {
        if (!canEditLocalAppearance()) {
            return CompletableFuture.failedFuture(new IllegalStateException(localAppearanceSuspensionReason()));
        }

        String texture = localTextureSelection(id);
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return this.localModelLibrary.load(id, texture.isEmpty() ? null : texture);
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }, this.previewDecoder).thenApply(loaded -> {
                this.client.execute(() -> rememberLocalProfile(id, loaded));
                return loaded;
            });
        } catch (RejectedExecutionException busy) {
            return CompletableFuture.failedFuture(busy);
        }
    }

    private String localTextureSelection(String id) {
        String texture = this.options.modelProfile(id).textureId();
        if (texture.isEmpty() && id.equals("openysm_default") && this.options.defaultBlueTexture) {
            texture = "blue";
        }

        return texture;
    }

    private void rememberLocalProfile(String id, LocalModelLibrary.Loaded loaded) {
        this.localProfiles.put(id, loaded);
        while (this.localProfiles.size() > 12) {
            this.localProfiles.remove(this.localProfiles.keySet().iterator().next());
        }

    }

    public YsmModelProfile localModelProfile() {
        return localModelProfile(localAppearance().modelId());
    }

    public YsmModelProfile localModelProfile(String id) {
        if (this.localSelf != null && this.localSelf.modelId.equals(id)) {
            return this.localSelf.profile;
        }

        LocalModelLibrary.Loaded loaded = this.localProfiles.get(id);
        return loaded == null ? YsmModelProfile.empty() : loaded.profile();
    }

    public YsmModelProfile modelProfile(UUID owner) {
        if (localAppearanceActive() && owner.equals(this.client.player.getUuid())) {
            return this.localSelf.profile;
        }

        Binding remote = this.privateBindings.get(owner);
        if (privateUsable(owner)) {
            return remote.profile;
        }

        Binding server = this.bindings.get(owner);
        if (server != null) {
            return installedProfile(server.hash);
        }

        return this.client.player != null && owner.equals(this.client.player.getUuid())
                ? installedProfile(this.previewHash)
                : YsmModelProfile.empty();
    }

    private YsmModelProfile installedProfile(String hash) {
        BbModelAsset.Decoded metadata = this.assetMetadata.get(hash);
        return metadata == null ? YsmModelProfile.empty() : metadata.profile();
    }

    public String appearanceModelId(UUID owner) {
        if (localAppearanceActive() && owner.equals(this.client.player.getUuid())) {
            return this.localSelf.modelId;
        }

        Binding remote = this.privateBindings.get(owner);
        if (privateUsable(owner)) {
            return this.privateLogicalSourceIds.getOrDefault(this.privateModels.remote(owner).hash, remote.modelId);
        }

        Binding server = this.bindings.get(owner);
        return server == null ? "" : server.modelId;
    }

    public boolean privateSyncAvailable() {
        return this.privateModels.available();
    }

    /** Upload receipts belong to this live private-relay session and are read without touching source files. */
    public PrivateModelSyncClient.UploadState privateModelUploadState(String id) {
        return this.privateModels.uploadState(id);
    }

    public Set<String> uploadedPrivateModelIds() {
        return this.privateModels.uploadedModelIds();
    }

    public String uploadedPrivateModelDirectoryStatus() {
        return this.privateModels.uploadedDirectoryStatus();
    }

    public boolean privateUploadCatalogReady() {
        return this.privateModels.uploadCatalogReady();
    }

    public String privateUploadCatalogStatus() {
        return this.privateModels.uploadCatalogStatus();
    }

    public List<PrivateUploadCatalogSnapshot.Model> uploadedPrivateModels() {
        return this.privateModels.uploadedModels();
    }

    public boolean requestDeleteUploadedPrivateModel(String id) {
        return this.privateModels.requestDeleteUploadedModel(id);
    }

    public boolean requestDeleteUploadedPrivateModel(PrivateUploadCatalogSnapshot.Model expected) {
        return this.privateModels.requestDeleteUploadedModel(expected);
    }

    public boolean deletingUploadedPrivateModel(String id) {
        return this.privateModels.deletingUploadedModel(id);
    }

    public String privateUploadDeleteStatus(String id) {
        return this.privateModels.uploadDeleteStatus(id);
    }

    /** Explicit cloud action selects and shares the file through the existing permission-gated publisher. */
    public boolean uploadAndShareLocalModel(String id) {
        if (!canShareLocalModel() || !canActivateLocalAppearance() || !LocalAppearanceSettings.isValidModelId(id)
                || localModels().stream().noneMatch(model -> model.id().equals(id))) {
            return false;
        }

        selectLocalModel(id);
        if (!canUseLocalAppearance() || !localAppearance().enabled() || !localAppearance().modelId().equals(id)) {
            return false;
        }

        this.options.showSelf = true;
        this.options.save();
        if (!setPrivateSyncEnabled(true)) {
            return false;
        }

        this.privateModels.restartPublishing();
        return true;
    }

    public boolean privateSyncEnabled() {
        return this.options.privateSyncEnabled;
    }

    public PrivateModelSyncClient.PublicationIdentity privatePublicationIdentity() {
        return this.privateModels.publicationIdentity();
    }

    /** The confirmation must still describe this exact publication, even when the same file was shared again. */
    public boolean stopPrivatePublication(PrivateModelSyncClient.PublicationIdentity expected) {
        return expected != null && expected.equals(this.privateModels.publicationIdentity())
                && setPrivateSyncEnabled(false);
    }

    public List<PrivateModelSyncClient.ReceivedModel> receivedPrivateModels() {
        return this.privateModels.receivedModels();
    }

    public boolean canShareLocalModel() {
        return privateSyncAvailable() && canActivateLocalAppearance();
    }

    public String privateSyncStatus() {
        if (serverOwnModelPresent()) {
            return "服务端伪装期间，私人展示与分享已暂停；配置已保留";
        }

        if (interactionPolicy().localSelectionPaused()) {
            return "私人展示与分享已暂停；使用模型后恢复";
        }

        if (!this.options.privateSyncEnabled) {
            return privateSyncAvailable() ? "仅自己可见 · 可主动开启分享" : "仅自己可见 · " + this.privateModels.status();
        }

        if (!localAppearance().enabled() && privateSyncAvailable()) {
            return "分享已开启 · 使用本地模型后上传";
        }

        return this.privateModels.status();
    }

    public Map<String, Object> privateSharingDiagnostics() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("requested", this.options.privateSyncEnabled);
        result.put("canUpload", this.privateModels.available());
        result.put("canView", this.privateModels.canView());
        result.put("viewingEnabled", this.privateModels.viewingEnabled());
        result.put("viewerStateNegotiated", this.privateModels.viewerStateNegotiated());
        result.put("canShare", canShareLocalModel());
        result.put("committed", this.privateModels.committed());
        result.put("bytes", this.privateModels.publicationBytes());
        result.put("uploadedBytes", this.privateModels.uploadedBytes());
        result.put("status", privateSyncStatus());
        result.put("receiving", receivedPrivateModels().stream().map(model -> {
            LinkedHashMap<String, Object> receipt = new LinkedHashMap<String, Object>();
            receipt.put("owner", model.owner().toString());
            receipt.put("generation", model.generation().toString());
            receipt.put("modelId", model.modelId());
            receipt.put("hash", model.hash());
            receipt.put("kind", model.kind());
            receipt.put("bytes", model.bytes());
            receipt.put("receivedBytes", model.receivedBytes());
            receipt.put("cacheHit", model.cacheHit());
            receipt.put("phase", model.phase().name());
            receipt.put("ready", model.ready());
            receipt.put("active", model.active());
            receipt.put("localState", privateViewingStatus(model));
            receipt.put("failure", model.failure());
            return receipt;
        }).toList());
        return Collections.unmodifiableMap(result);
    }

    /** Readiness is a resource/lease acknowledgement; it cannot prove that an off-screen player was drawn. */
    private String privateViewingStatus(PrivateModelSyncClient.ReceivedModel model) {
        if (model.phase() == PrivateModelSyncClient.ReceivingPhase.FAILED) {
            return model.failure();
        }

        if (!this.options.enabled) {
            return "客户端渲染已关闭，暂停接收";
        }

        if (isServerDisguised(model.owner())) {
            return "发布者存在服务器伪装，私人外观暂停";
        }

        if (!model.active()) {
            return switch (model.phase()) {
            case CHECKING_CACHE -> "正在校验本地缓存";
            case WAITING_DOWNLOAD -> "等待服务器下发资源";
            case DOWNLOADING -> "正在接收资源";
            case DECODING -> "正在解析并准备纹理";
            case WAITING_ACK -> "本机资源已就绪，等待服务器观看确认";
            default -> "等待有效观看租约";
            };
        }

        if (this.client.world == null || this.client.world.getPlayerByUuid(model.owner()) == null) {
            return "已获观看授权，等待原版玩家实体";
        }

        Binding binding = this.privateBindings.get(model.owner());
        if (binding == null || !binding.instance.equals(model.generation().toString())
                || !ModelRenderer.has(binding.hash)) {
            return "已获观看授权，本机绘制资源尚未就绪";
        }

        return "资源与观看授权已就绪；实际绘制依当前视角与显隐";
    }

    public boolean setPrivateSyncEnabled(boolean enabled) {
        if (enabled && !canShareLocalModel()) {
            return false;
        }

        this.options.privateSyncEnabled = enabled;
        this.options.save();
        if (!enabled) {
            this.privateModels.stopPublishing();
        }
        return true;
    }

    public void receivePrivate(byte[] bytes) {
        this.privateModels.receive(bytes, System.nanoTime());
    }

    private boolean sendPrivate(JsonObject packet) {
        if (!this.connected || this.client.getNetworkHandler() == null
                || !ClientPlayNetworking.canSend(PrivateModelPayload.ID)) {
            return false;
        }

        byte[] bytes = packet.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ActionPayload.MAX_BYTES) {
            return false;
        }

        try {
            ClientPlayNetworking.send(new PrivateModelPayload(bytes));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public Consumer<List<Double>> nativeSyncListener(UUID owner) {
        if (this.client.player == null || !owner.equals(this.client.player.getUuid()) || isServerDisguised(owner)
                || !localAppearanceActive() || !this.options.privateSyncEnabled || !this.privateModels.committed()) {
            return null;
        }

        return values -> this.privateModels.event(values, System.nanoTime());
    }

    /** Only the current owner's world BODY may persist authored roaming changes. */
    public void recordLocalRoaming(UUID owner, String instance, Map<String, Double> changes) {
        if (changes.isEmpty() || this.client.player == null || !owner.equals(this.client.player.getUuid())
                || this.localSelf == null || !this.localSelf.instance.equals(instance) || !localAppearanceActive()) {
            return;
        }

        this.options.updateRoamingVariables(this.localSelf.modelId, changes);
    }

    private PrivateModelSyncClient.Local privateLocalSnapshot() {
        LocalAppearanceSettings settings = localAppearance();
        if (!this.options.privateSyncEnabled || !settings.enabled() || this.client.player == null
                || !canUseLocalAppearance() || isServerDisguised(this.client.player.getUuid()) || this.localSelf == null
                || !this.localSelf.modelId.equals(settings.modelId())) {
            return null;
        }

        JsonObject appearance = new JsonObject();
        appearance.addProperty("scale", settings.scale());
        appearance.addProperty("offsetX", settings.offsetX());
        appearance.addProperty("offsetY", settings.offsetY());
        appearance.addProperty("offsetZ", settings.offsetZ());
        appearance.addProperty("textureId", this.localSelf.profile.selectedTexture());
        JsonObject variables = new JsonObject(), radios = new JsonObject();
        ClientOptions.ModelProfile profile = this.options.modelProfile(settings.modelId());
        if (settings.modelId().equals("openysm_default")) {
            variables.addProperty("variable.roaming.red_bow_headdress", this.options.defaultHeaddress ? 1 : 0);
        }

        profile.variables().forEach((key, value) -> {
            if (variables.size() < 128 && Double.isFinite(value) && Math.abs(value) <= 1_000_000) {
                variables.addProperty(key, value);
            }
        });
        profile.radioSelections().forEach((key, value) -> {
            if (radios.size() < 128 && value >= 0 && value <= 255) {
                radios.addProperty(key.toLowerCase(Locale.ROOT), value);
            }
        });
        appearance.add("variables", variables);
        appearance.add("radioSelections", radios);
        JsonObject extra = new JsonObject();
        Layer layer = this.localSelf.localServerLayers.isEmpty() ? null : this.localSelf.localServerLayers.getFirst();
        extra.addProperty("id", layer == null ? "" : layer.animation());
        extra.addProperty("loop", layer == null ? "ONCE" : layer.loop());
        extra.addProperty("locked", this.options.localActionLocked);
        extra.addProperty("sequence", this.localExtraSequence);
        return new PrivateModelSyncClient.Local(this.client.player.getUuid(), settings.modelId(), appearance, extra);
    }

    private boolean privateUsable(UUID owner) {
        PrivateModelSyncClient.Remote remote = this.privateModels.remote(owner);
        Binding binding = this.privateBindings.get(owner);
        return this.options.enabled && remote != null && remote.active && binding != null && !isServerDisguised(owner)
                && this.client.world != null && this.client.world.getPlayerByUuid(owner) != null
                && ModelRenderer.has(binding.hash);
    }

    private boolean privateEventCurrent(PendingSync event) {
        if (this.client.player != null && event.owner().equals(this.client.player.getUuid())) {
            return this.localSelf != null && this.localSelf.instance.equals(event.instance())
                    && !isServerDisguised(event.owner());
        }

        Binding binding = this.privateBindings.get(event.owner());
        return binding != null && binding.instance.equals(event.instance()) && privateUsable(event.owner());
    }

    private void applyPrivateState(PrivateModelSyncClient.Remote remote) {
        Binding binding = this.privateBindings.get(remote.owner);
        if (binding == null || !binding.instance.equals(remote.generation.toString())) {
            return;
        }

        binding.active = remote.active;
        binding.scale = remote.appearance.get("scale").getAsFloat();
        long sequence = remote.extra.get("sequence").getAsLong();
        if (this.privateExtraSequences.getOrDefault(remote.owner, -1L) == sequence) {
            return;
        }
        this.privateExtraSequences.put(remote.owner, sequence);
        String animation = remote.extra.get("id").getAsString();
        BbModel model = this.assets.get(binding.hash);
        binding.localServerLayers = animation.isEmpty() || model == null || !model.animations().contains(animation)
                ? List.of()
                : List.of(new Layer("manual", animation, this.localTick, 1, remote.extra.get("loop").getAsString(), 2,
                        2));
    }

    public PlayerEntity nativePlayer(UUID owner) {
        return this.client.world == null ? null : this.client.world.getPlayerByUuid(owner);
    }

    public RenderBinding appearanceBinding(UUID owner) {
        return animationBindings().stream().filter(binding -> binding.owner().equals(owner)).findFirst().orElse(null);
    }

    public boolean localActionLocked() {
        return this.options.localActionLocked;
    }

    public void setLocalActionLocked(boolean locked) {
        if (!canUseLocalActions()) {
            return;
        }
        this.options.localActionLocked = locked;
        this.options.save();
    }

    public Map<String, Double> localModelVariables(String id) {
        LocalModelLibrary.Loaded loaded = this.localProfiles.get(id);
        BbModel model = loaded == null ? null : loaded.model();
        if (model == null && this.localSelf != null && this.localSelf.modelId.equals(id)) {
            model = this.assets.get(this.localSelf.hash);
        }

        Map<String, Double> values = new LinkedHashMap<>();
        if (model != null) {
            values.putAll(model.initialVariables());
        }

        if (this.client.player != null && localAppearanceActive() && this.localSelf.modelId.equals(id)) {
            values.putAll(ModelRenderer.expressionVariables(this.client.player.getUuid()));
        }

        if (id.equals("openysm_default")) {
            values.put("variable.roaming.red_bow_headdress", this.options.defaultHeaddress ? 1d : 0d);
        }

        values.putAll(this.options.modelProfile(id).variables());
        return Map.copyOf(values);
    }

    public boolean runLocalScript(String id, String script) {
        return script != null && runLocalScripts(id, List.of(script), this.options.modelProfile(id).radioSelections());
    }

    public boolean runLocalScripts(String id, List<String> scripts, Map<String, Integer> radios) {
        if (!canEditLocalAppearance()) {
            return false;
        }

        if (!LocalAppearanceSettings.isValidModelId(id) || scripts == null || radios == null || scripts.size() > 128) {
            return false;
        }

        if (scripts.stream().anyMatch(Objects::isNull) || scripts.stream().mapToLong(String::length).sum() > 32768) {
            return false;
        }

        try {
            ModelConfigSchema schema = ModelConfigSchema.from(localModelProfile(id), this.client.options.language);
            AnimationPlayer player = configurationPlayer(id);
            if (player == null) {
                return false;
            }

            Map<String, Double> evaluated = player.authorConfiguration(scripts, localModelVariables(id));
            Map<String, Double> values = new LinkedHashMap<>(this.options.modelProfile(id).variables());
            for (String key : schema.variables()) {
                if (evaluated.containsKey(key)) {
                    values.put(key, evaluated.get(key));
                }

            }

            int roaming = (int) values.keySet().stream().filter(ClientOptions::isRoamingVariable).count();
            for (Map.Entry<String, Double> entry : evaluated.entrySet()) {
                if (ClientOptions.isRoamingVariable(entry.getKey()) && Double.isFinite(entry.getValue())
                        && Math.abs(entry.getValue()) <= 1_000_000
                        && (values.containsKey(entry.getKey()) || roaming < ClientOptions.MAX_ROAMING_VARIABLES)) {
                    if (!values.containsKey(entry.getKey())) {
                        roaming++;
                    }

                    values.put(entry.getKey(), entry.getValue());
                }
            }

            ClientOptions.ModelProfile previous = this.options.modelProfile(id);
            return this.options.updateModelProfile(id,
                    new ClientOptions.ModelProfile(previous.textureId(), values, radios));
        } catch (RuntimeException invalid) {
            MEPlayerActionsClient.LOGGER.debug("Private model configuration rejected: {}", invalid.getMessage());
            return false;
        }
    }

    private AnimationPlayer configurationPlayer(String id) {
        LocalModelLibrary.Loaded loaded = this.localProfiles.get(id);
        BbModel model = loaded == null ? null : loaded.model();
        if (model == null && this.localSelf != null && this.localSelf.modelId.equals(id)) {
            model = this.assets.get(this.localSelf.hash);
        }

        if (model == null) {
            return null;
        }
        AnimationPlayer player = new AnimationPlayer(model);
        if (localModelProfile(id).isYsm()) {
            player.enableNativeYsm();
        }

        player.configureFrame(context -> {
            if (this.client.player != null) {
                configureExpressionContext(this.client.player.getUuid(), context);
            }

        });
        if (this.client.player != null && this.localSelf != null && this.localSelf.modelId.equals(id)) {
            player.syncListener(nativeSyncListener(this.client.player.getUuid()));
        }

        return player;
    }

    public double readLocalModelExpression(String id, String expression, Map<String, Double> draftValues) {
        AnimationPlayer player = configurationPlayer(id);
        if (player == null) {
            throw new IllegalArgumentException("模型尚未加载");
        }

        Map<String, Double> values = new LinkedHashMap<>(localModelVariables(id));
        values.putAll(draftValues);
        return player.readConfiguration(expression, values);
    }

    public boolean selectLocalTexture(String id, String texture) {
        if (!canEditLocalAppearance()) {
            return false;
        }

        YsmModelProfile profile = localModelProfile(id);
        if (texture == null || profile.textures().stream().noneMatch(choice -> choice.id().equals(texture))) {
            return false;
        }

        ClientOptions.ModelProfile previous = this.options.modelProfile(id);
        if (!this.options.updateModelProfile(id,
                new ClientOptions.ModelProfile(texture, previous.variables(), previous.radioSelections()))) {
            return false;
        }

        this.localProfiles.remove(id);
        if (this.localSelf != null && this.localSelf.modelId.equals(id)) {
            invalidateLocalAppearance();
            ensureLocalAppearance();
        }
        return true;
    }

    public Path localModelDirectory() {
        return this.localModelLibrary.directory();
    }

    public void refreshLocalAppearance() {
        if (!canEditLocalAppearance()) {
            suspendLocalAppearanceForServer();
            return;
        }
        invalidateLocalAppearance();
        ensureLocalAppearance();
    }

    public void refreshLocalModelSources() {
        this.localModelLibrary.clearSourceBundleCache();
        this.privateModels.invalidateSources();
        if (canEditLocalAppearance()) {
            invalidateLocalAppearance();
            ensureLocalAppearance();
        }
    }

    private void begin(JsonObject json, long now) {
        String hash = WireJson.hash(json, "hash");
        this.pushAuthorization.begin(offerId(json), hash, WireJson.modelId(json),
                (int) WireJson.integer(json, "rawBytes", 1, AssetTransfer.MAX_RAW),
                (int) WireJson.integer(json, "compressedBytes", 1, AssetTransfer.MAX_COMPRESSED),
                (int) WireJson.integer(json, "chunks", 1, AssetTransfer.MAX_CHUNKS), pushBindings(), now);
        this.pushTransfersBegun++;
        pushState(hash, "正在下载服务器模型", "");
    }

    private void pushChunk(JsonObject json, long now) {
        this.pushAuthorization.chunk(offerId(json), WireJson.hash(json, "hash"),
                (int) WireJson.integer(json, "index", 0, AssetTransfer.MAX_CHUNKS - 1),
                WireJson.string(json, "data", 12_000), pushBindings(), now);
    }

    private void cancelOffer(JsonObject json) {
        UUID id = offerId(json);
        String hash = WireJson.hash(json, "hash"), reason = WireJson.string(json, "reason", 128);
        ServerPushAuthorization.Offer offer = this.pushAuthorization.find(id).orElse(null);
        if (offer == null) {
            return;
        }

        if (!offer.identity().hash().equals(hash)) {
            throw new IllegalArgumentException("Cancel offer hash");
        }

        if (this.pushAuthorization.remove(offer)) {
            this.pushCancelled++;
            this.loading.remove(hash);
            pushState(hash, "等待服务器模型", "服务器取消模型推送：" + reason + "；保持服务器显示");
        }
    }

    private void finish(JsonObject json, long now) {
        UUID id = offerId(json);
        String hash = WireJson.hash(json, "hash");
        AssetTransfer transfer = this.pushAuthorization.end(id, hash, pushBindings(), now);
        ServerPushAuthorization.Offer offer = this.pushAuthorization.find(id).orElseThrow();
        long epoch = this.generation;
        this.loading.add(hash);
        pushState(hash, "正在校验下载模型", "");
        try {
            this.decoder.execute(() -> {
                try {
                    byte[] raw = transfer.finish();
                    BbModelAsset.Decoded model = BbModelAsset.read(raw);
                    this.client.execute(() -> {
                        if (!currentPush(offer, epoch)) {
                            return;
                        }

                        completePush(offer, model, false, epoch, raw);
                    });
                } catch (Exception exception) {
                    this.client.execute(() -> {
                        if (currentPush(offer, epoch)) {
                            rejectPush(offer, "服务器模型校验失败：" + exception.getMessage());
                        }
                    });
                }
            });
        } catch (RejectedExecutionException busy) {
            rejectPush(offer, "服务器模型解析队列已满");
        }
    }

    private void install(String hash, BbModel model, BooleanSupplier current, Consumer<Boolean> completion) {
        if (!current.getAsBoolean()) {
            return;
        }
        if (this.assets.containsKey(hash) && ModelRenderer.has(hash)) {
            finishTextureInstall(hash, model);
            completion.accept(true);
            return;
        }
        PendingInstall pending = this.textureInstalls.get(hash);
        if (pending == null) {
            if (this.textureInstalls.size() >= 16) {
                completion.accept(false);
                return;
            }
            pending = new PendingInstall(model);
            this.textureInstalls.put(hash, pending);
        }
        pending.listeners.add(new InstallListener(current, completion));
    }

    private void pumpTextureInstalls() {
        for (Map.Entry<String, PendingInstall> entry : List.copyOf(this.textureInstalls.entrySet())) {
            String hash = entry.getKey();
            PendingInstall pending = entry.getValue();
            pending.listeners.removeIf(listener -> !listener.current().getAsBoolean());
            if (pending.listeners.isEmpty()) {
                this.textureInstalls.remove(hash, pending);
                this.textureInstallSlots.remove(hash);
                continue;
            }
            if (pending.submitted) {
                continue;
            }
            while (!this.assets.containsKey(hash) && this.assets.size() + this.textureInstallSlots.stream()
                    .filter(value -> !this.assets.containsKey(value)).count() >= 16
                    && !this.textureInstallSlots.contains(hash)) {
                if (!evictInactive()) {
                    break;
                }
            }
            if (!this.assets.containsKey(hash) && !this.textureInstallSlots.contains(hash)
                    && this.assets.size() + this.textureInstallSlots.stream()
                            .filter(value -> !this.assets.containsKey(value)).count() >= 16) {
                continue;
            }
            this.textureInstallSlots.add(hash);
            pending.submitted = ModelRenderer.prepareAsync(hash, pending.model,
                    () -> this.textureInstalls.get(hash) == pending && pending.listeners.stream()
                            .anyMatch(listener -> listener.current().getAsBoolean()),
                    (success, reason) -> {
                        if (this.textureInstalls.get(hash) != pending) {
                            return;
                        }
                        if (!success && reason.equals("cancelled") && pending.listeners.stream()
                                .anyMatch(listener -> listener.current().getAsBoolean())) {
                            pending.submitted = false;
                            return;
                        }
                        this.textureInstalls.remove(hash, pending);
                        this.textureInstallSlots.remove(hash);
                        if (success) {
                            finishTextureInstall(hash, pending.model);
                        } else if (!reason.equals("cancelled")) {
                            failAsset(hash, "模型纹理加载失败");
                        }
                        for (InstallListener listener : pending.listeners) {
                            if (listener.current().getAsBoolean()) {
                                listener.completion().accept(success);
                            }
                        }
                    });
            if (!pending.submitted) {
                this.textureInstallSlots.remove(hash);
            }
        }
    }

    private void finishTextureInstall(String hash, BbModel model) {
        this.assets.put(hash, model);
        this.failedAssets.remove(hash);
        if (!this.unsupportedHandshake) {
            this.lastError = "";
        }

        for (Binding binding : this.bindings.values()) {
            if (binding.hash.equals(hash)) {
                binding.unsupported = binding.layers.stream()
                        .anyMatch(layer -> !model.animations().contains(layer.animation()));
                if (binding.unsupported) {
                    failed(binding, "客户端模型缺少对应动画，请更新服务器 bbmodel");
                }

            }
        }

        MEPlayerActionsClient.LOGGER.info("Prepared MPA model {}: {} cubes, {} animations", hash.substring(0, 12),
                model.cubeCount(), model.animations().size());
    }

    public boolean evictTextureAsset() {
        return evictInactive();
    }

    public boolean retainsTextureAsset(String hash) {
        return this.assets.containsKey(hash) || this.textureInstalls.containsKey(hash);
    }

    public void restoreTextureAsset(String hash, BbModel model) {
        long epoch = this.generation;
        install(hash, model, () -> epoch == this.generation && this.assets.get(hash) == model, success -> {
            if (!success) {
                renderFailed(null, "", hash, "Texture upload failed after resource reload");
            }
        });
    }

    private record InstallListener(BooleanSupplier current, Consumer<Boolean> completion) {
    }

    private static final class PendingInstall {
        private final BbModel model;
        private final List<InstallListener> listeners = new ArrayList<>();
        private boolean submitted;

        private PendingInstall(BbModel model) {
            this.model = model;
        }
    }

    private boolean evictInactive() {
        Set<String> active = new HashSet<>();
        for (Binding binding : this.bindings.values()) {
            active.add(binding.hash);
        }

        for (Binding binding : this.privateBindings.values()) {
            active.add(binding.hash);
        }

        if (!this.previewHash.isEmpty()) {
            active.add(this.previewHash);
        }

        if (this.localSelf != null) {
            active.add(this.localSelf.hash);
        }

        Iterator<Map.Entry<String, BbModel>> iterator = this.assets.entrySet().iterator();
        while (iterator.hasNext()) {
            String hash = iterator.next().getKey();
            if (active.contains(hash)) {
                continue;
            }

            iterator.remove();
            this.assetMetadata.remove(hash);
            this.packAssets.remove(hash);
            this.serverAssets.remove(hash);
            this.serverModelAssets.values().removeIf(hash::equals);
            ModelRenderer.release(hash);
            return true;
        }
        return false;
    }

    private void failAsset(String hash, String reason) {
        this.loading.remove(hash);
        this.failedAssets.put(hash, System.nanoTime());
        this.lastError = reason;
        for (Binding binding : this.bindings.values()) {
            if (binding.hash.equals(hash)) {
                failed(binding, "");
                binding.assetState = "服务器模型同步失败";
                binding.assetError = reason;
            }
        }

    }

    private Set<String> activeAssetHashes() {
        Set<String> active = new HashSet<>(this.loading);
        this.bindings.values().forEach(binding -> active.add(binding.hash));
        this.privateBindings.values().forEach(binding -> active.add(binding.hash));
        if (this.localSelf != null) {
            active.add(this.localSelf.hash);
        }
        if (!this.previewHash.isEmpty()) {
            active.add(this.previewHash);
        }
        return active;
    }

    private Binding matching(JsonObject json) {
        Binding binding = this.bindings.get(UUID.fromString(WireJson.string(json, "owner", 36)));
        return binding != null && binding.instance.equals(WireJson.string(json, "instance", 36))
                && binding.hash.equals(WireJson.hash(json, "hash")) ? binding : null;
    }

    private JsonObject identityFields(Binding binding) {
        JsonObject json = new JsonObject();
        json.addProperty("owner", binding.owner.toString());
        json.addProperty("instance", binding.instance);
        json.addProperty("hash", binding.hash);
        return json;
    }

    private JsonObject identity(String type, Binding binding) {
        JsonObject json = identityFields(binding);
        json.addProperty("protocol", 3);
        json.addProperty("type", type);
        return json;
    }

    private void failed(Binding binding, String reason) {
        closeModelEffects(binding.owner);
        if (binding.readySent) {
            send(identity("render_failed", binding));
        }

        binding.active = false;
        binding.readySent = false;
        if (!reason.isEmpty()) {
            this.lastError = reason;
            binding.assetState = "保持服务器显示";
            binding.assetError = reason;
        }
    }

    private void releaseBindings() {
        for (Binding binding : this.bindings.values()) {
            failed(binding, "");
        }
        this.bindings.clear();
    }

    private void abortTransfers() {
        this.generation++;
        this.previewRequest++;
        this.previewPending = false;
        this.decoder.getQueue().clear();
        this.localAppearanceRequest++;
        this.localAppearancePending = false;
        this.pushCancelled += this.pushAuthorization.diagnostics().size();
        this.pushAuthorization.clear();
        this.loading.clear();
    }

    private boolean send(JsonObject json) {
        if (!this.connected || this.client.getNetworkHandler() == null
                || !ClientPlayNetworking.canSend(ActionPayload.ID)) {
            return false;
        }

        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > this.maxPayload) {
            this.lastError = "动作数据超过频道负载限制";
            return false;
        }
        try {
            ClientPlayNetworking.send(new ActionPayload(bytes));
            if (json.has("type") && json.get("type").getAsString().equals("asset_request")) {
                this.pushAssetRequests++;
            }

            return true;
        } catch (RuntimeException exception) {
            this.lastError = "服务器动作频道不可用";
            return false;
        }
    }

    private void sendHeartbeats() {
        JsonObject packet = WireJson.envelope("render_heartbeat");
        JsonArray batch = new JsonArray();
        packet.add("bindings", batch);
        for (Binding binding : this.bindings.values()) {
            if (binding.readySent) {
                JsonObject identity = identityFields(binding);
                batch.add(identity);
                if (packet.toString().getBytes(StandardCharsets.UTF_8).length > this.maxPayload) {
                    batch.remove(batch.size() - 1);
                    send(packet);
                    batch = new JsonArray();
                    batch.add(identity);
                    packet = WireJson.envelope("render_heartbeat");
                    packet.add("bindings", batch);
                }
            }
        }

        send(packet);
    }

    public void request(String action, String argument) {
        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        if (canUseLocalActions() && !this.previewId.isEmpty() && own == null) {
            if (action.equals("play")) {
                this.previewManual = argument;
                this.previewManualStarted = this.client.world.getTime();
            } else if (action.equals("stop") || action.equals("reset")) {
                this.previewManual = "";
            }

            return;
        }
        if (!canUseServerActions() || own == null) {
            return;
        }

        if (action.equals("play") && own.actions.stream().noneMatch(item -> item.id().equals(argument))) {
            return;
        }

        JsonObject json = WireJson.envelope("request");
        json.addProperty("action", action);
        if (!argument.isEmpty()) {
            json.addProperty("argument", argument);
        }

        if (send(json)) {
            this.requestPacketsSent++;
        }

    }

    public void toggleEnabled() {
        // Keep the server-selected instance, action catalogue and authorized model offers.
        // render_failed releases only GPU leases, so ME resumes displaying the disguise.
        if (this.options.enabled) {
            releaseAll();
        }

        this.options.enabled = !this.options.enabled;
        this.options.save();
        this.privateModels.viewerRenderingChanged();
        if (!this.options.enabled) {
            closeModelEffects();
            this.previewRequest++;
            this.previewPending = false;
            this.localAppearanceRequest++;
            this.localAppearancePending = false;
        }
    }

    public Collection<RenderBinding> renderBindings() {
        return animationBindings().stream().filter(binding -> shouldShowModel(binding.owner())).toList();
    }

    /** Hidden self models keep instance scripts and physics alive without submitting geometry. */
    public boolean shouldShowModel(UUID owner) {
        if (this.client.player == null || !owner.equals(this.client.player.getUuid())) {
            return true;
        }

        Binding server = this.bindings.get(owner);
        return this.options.showSelf && (server == null || server.showSelf);
    }

    public Collection<RenderBinding> animationBindings() {
        if (!this.options.enabled || this.client.world == null) {
            return List.of();
        }

        long now = System.nanoTime();
        double tick = this.clock.estimate(now) - this.options.interpolationTicks;
        List<RenderBinding> result = new ArrayList<>();
        for (Binding binding : this.bindings.values()) {
            BbModel model = this.assets.get(binding.hash);
            if (!usable(binding, now) || model == null) {
                continue;
            }

            PlayerEntity player = this.client.world.getPlayerByUuid(binding.owner);
            if (player == null) {
                continue;
            }

            RenderBinding nativeBinding = entityBinding(binding, model, player);
            if (this.options.followServerTimeline) {
                List<Layer> presentationLayers = binding.layerTimeline.sample(tick);
                result.add(new RenderBinding(binding.owner, binding.instance, binding.hash, model, presentationLayers,
                        tick, nativeBinding.x(), nativeBinding.y(), nativeBinding.z(), nativeBinding.bodyYaw(),
                        nativeBinding.headYaw(), nativeBinding.headPitch(), binding.scale, binding.hidePlayer,
                        "native-position-server-animation"));
            } else {
                result.add(nativeBinding);
            }
        }
        for (Binding binding : this.privateBindings.values()) {
            if (privateUsable(binding.owner)) {
                PlayerEntity player = this.client.world.getPlayerByUuid(binding.owner);
                BbModel model = this.assets.get(binding.hash);
                if (player == null || model == null) {
                    continue;
                }
                RenderBinding nativeBinding = entityBinding(binding, model, player);
                PrivateModelSyncClient.Remote remote = this.privateModels.remote(binding.owner);
                JsonObject appearance = remote.appearance;
                result.removeIf(value -> value.owner().equals(binding.owner));
                result.add(new RenderBinding(nativeBinding.owner(), nativeBinding.instance(), nativeBinding.assetHash(),
                        model, nativeBinding.layers(), nativeBinding.serverTick(),
                        nativeBinding.x() + appearance.get("offsetX").getAsDouble(),
                        nativeBinding.y() + appearance.get("offsetY").getAsDouble(),
                        nativeBinding.z() + appearance.get("offsetZ").getAsDouble(), nativeBinding.bodyYaw(),
                        nativeBinding.headYaw(), nativeBinding.headPitch(), binding.scale, true, "private-tracked"));
            }
        }

        if (localAppearanceActive()) {
            result.removeIf(binding -> binding.owner().equals(this.client.player.getUuid()));
            result.add(localAppearanceBinding());
            return List.copyOf(result);
        }
        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        if (own == null && !this.previewId.isEmpty() && this.client.player != null
                && this.assets.containsKey(this.previewHash) && ModelRenderer.has(this.previewHash)) {
            result.add(previewBinding());
        }

        return List.copyOf(result);
    }

    /** Diagnostics only: compare the ME's delayed trajectory with the native entity presentation. */
    public TransformTimeline.Transform serverTransform(UUID owner) {
        Binding binding = this.bindings.get(owner);
        return binding == null ? null
                : binding.timeline.sample(this.clock.estimate(System.nanoTime()) - this.options.interpolationTicks);
    }

    public Map<String, Double> expressionQueries(UUID owner) {
        PlayerEntity player = this.client.world == null ? null : this.client.world.getPlayerByUuid(owner);
        if (player == null) {
            return Map.of();
        }

        Binding binding = this.bindings.get(owner);
        // Native entity deltas also work for remote players whose getVelocity is not populated.
        Vec3d delta = player.getEntityPos().subtract(player.lastX, player.lastY, player.lastZ);
        if (delta.lengthSquared() > 16) {
            delta = Vec3d.ZERO;
        }

        Map<String, Double> values = new HashMap<>();
        values.put("ysm.food_level", (double) (player == this.client.player ? player.getHungerManager().getFoodLevel()
                : (binding == null ? 20 : binding.foodLevel)));
        values.put("ysm.has_mainhand", player.getMainHandStack().isEmpty() ? 0d : 1d);
        values.put("ysm.has_offhand", player.getOffHandStack().isEmpty() ? 0d : 1d);
        values.put("query.ground_speed", Math.sqrt(delta.x * delta.x + delta.z * delta.z) * 20);
        values.put("query.vertical_speed", delta.y * 20);
        values.put("query.yaw_speed", (double) MathHelper.wrapDegrees(player.getYaw() - player.lastYaw) * 20);
        values.put("query.position_delta_0", delta.x);
        values.put("query.position_delta_1", delta.y);
        values.put("query.position_delta_2", delta.z);
        values.put("query.is_sneaking", player.isSneaking() ? 1d : 0d);
        values.put("query.time_stamp", (double) this.client.world.getTimeOfDay());
        return Map.copyOf(values);
    }

    /** Empty for local previews and models without server-owned accessory state. */
    public Map<String, Double> accessoryState(UUID owner) {
        if (localAppearanceActive() && owner.equals(this.client.player.getUuid())) {
            return Map.of();
        }

        Binding binding = this.bindings.get(owner);
        return binding == null ? Map.of() : binding.accessories;
    }

    public Map<String, Double> localParameters(UUID owner) {
        if (privateUsable(owner)) {
            LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
            this.privateModels.remote(owner).appearance.getAsJsonObject("variables").entrySet()
                    .forEach(entry -> values.put(entry.getKey(), entry.getValue().getAsDouble()));
            return Map.copyOf(values);
        }
        if (!localAppearanceActive() || !owner.equals(this.client.player.getUuid())) {
            return Map.of();
        }

        Map<String, Double> values = new LinkedHashMap<>();
        if (this.localSelf.modelId.equals("openysm_default")) {
            values.put("variable.roaming.red_bow_headdress", this.options.defaultHeaddress ? 1d : 0d);
        }

        values.putAll(this.options.modelProfile(this.localSelf.modelId).variables());
        return Map.copyOf(values);
    }

    public void configureExpressionContext(UUID owner, Molang.Context context) {
        PlayerEntity player = nativePlayer(owner);
        Binding binding = this.bindings.get(owner);
        int food = player == this.client.player && player != null ? player.getHungerManager().getFoodLevel()
                : (binding == null ? 20 : binding.foodLevel);
        VanillaYsmQueries.populate(this.client, player, food, modelProfile(owner).selectedTexture(),
                nativeFlying(player), context);
        context.query("ysm.is_first_person",
                player != null && player == this.client.player && this.client.options.getPerspective().isFirstPerson()
                        ? 1d
                        : 0d);
        Molang.FunctionResolver nativeFunctions = context.functionResolver();
        RenderBinding appearance = appearanceBinding(owner);
        YsmModelEffects frameEffects = appearance == null || player == null ? null
                : frameEffects(owner, player, "body", appearance.instance(), appearance.assetHash(),
                        modelProfile(owner));
        installFrameEffects(context, nativeFunctions, frameEffects);
        Binding motion = localAppearanceActive() && owner.equals(this.client.player.getUuid()) ? this.localSelf
                : (privateUsable(owner) ? this.privateBindings.get(owner) : binding);
        if (motion != null) {
            for (String controller : motion.localMotion.pausedControllers()) {
                context.query("ysm.pause." + controller, 1d);
            }

        }

    }

    /** Hash lookup remains for existing callers; render processors should pass their exact component identity. */
    public void configureComponentExpressionContext(UUID owner, Entity entity, String componentHash,
            Molang.Context context) {
        YsmModelProfile.Component component = modelProfile(owner).components().stream()
                .filter(value -> value.hash().equals(componentHash)).findFirst().orElse(null);
        configureComponentExpressionContext(owner, entity, component, context);
    }

    public void configureComponentExpressionContext(UUID owner, Entity entity, YsmModelProfile.Component component,
            Molang.Context context) {
        YsmModelProfile profile = modelProfile(owner);
        String texture = component == null ? "" : component.selectedTexture();
        if (entity instanceof PlayerEntity player) {
            Binding binding = this.bindings.get(player.getUuid());
            int food = player == this.client.player ? player.getHungerManager().getFoodLevel()
                    : (binding == null ? 20 : binding.foodLevel);
            VanillaYsmQueries.populate(this.client, player, food, texture, nativeFlying(player), context);
        } else {
            VanillaYsmQueries.populateEntity(this.client, entity, 20, texture, context);
        }

        context.query("ysm.is_first_person", component != null && component.kind().equals("fp_arm") ? 1d : 0d);
        Molang.FunctionResolver nativeFunctions = context.functionResolver();
        RenderBinding appearance = appearanceBinding(owner);
        YsmModelEffects effects = appearance == null || entity == null || component == null ? null
                : frameEffects(owner, entity, componentEffectProcessor(component), appearance.instance(),
                        component.hash(), profile);
        installFrameEffects(context, nativeFunctions, effects);
    }

    private static String componentEffectProcessor(YsmModelProfile.Component component) {
        return "component:" + component.kind() + ":" + component.id();
    }

    private YsmModelEffects frameEffects(UUID owner, Entity entity, String processor, String instance, String hash,
            YsmModelProfile profile) {
        ModelEffectInstances.Key key = new ModelEffectInstances.Key(owner, entity.getUuid(), processor);
        EffectState state = this.modelEffects.acquire(key,
                previous -> previous.entity() == entity && previous.instance().equals(instance)
                        && previous.hash().equals(hash),
                () -> new EffectState(new YsmModelEffects(this.client), entity, instance, hash, profile));
        if (state == null) {
            return null;
        }

        state.effects().update(entity, instance, hash, profile);
        return state.effects();
    }

    private static void installFrameEffects(Molang.Context context, Molang.FunctionResolver nativeFunctions,
            YsmModelEffects effects) {
        context.functions((function, arguments) -> switch (function) {
        case "ysm.play_sound", "ysm.stop_sound", "ysm.stop_all_sounds", "ysm.particle", "ysm.abs_particle" ->
            effects != null && Boolean.TRUE
                    .equals(effects.handle(function, arguments, context.nativeYsm(), context.effectScope()));
        default -> nativeFunctions == null ? 0d : nativeFunctions.call(function, arguments);
        });
    }

    private void tickModelEffects() {
        Map<UUID, RenderBinding> current = new HashMap<>();
        for (RenderBinding binding : animationBindings()) {
            current.put(binding.owner(), binding);
        }

        this.modelEffects.retain((key, state) -> {
            RenderBinding binding = current.get(key.owner());
            return binding != null && binding.instance().equals(state.instance())
                    && (key.body() ? binding.assetHash().equals(state.hash()) && key.owner().equals(key.entity())
                            : modelProfile(key.owner()).components().stream()
                                    .anyMatch(component -> componentEffectProcessor(component).equals(key.processor())
                                            && component.hash().equals(state.hash())))
                    && !state.entity().isRemoved() && state.entity().getEntityWorld() == this.client.world;
        });
        this.modelEffects.forEach((key, state) -> state.effects().update(state.entity(), state.instance(), state.hash(),
                state.profile()));
    }

    private void closeModelEffects(UUID owner) {
        this.modelEffects.closeOwner(owner);
    }

    private void closeModelEffects() {
        this.modelEffects.close();
    }

    public Map<String, Object> modelEffectsDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        this.modelEffects.forEach((key, state) -> result.put(
                key.body() ? key.owner().toString() : key.owner() + "/" + key.entity() + "/" + key.processor(),
                state.effects().diagnostics()));
        return Map.copyOf(result);
    }

    public VanillaYsmAnimations.VanillaState vanillaState(UUID owner) {
        return VanillaYsmQueries.vanillaState(this.client, nativePlayer(owner));
    }

    public GuiPreviewAppearance guiPreviewAppearance() {
        if (!this.options.enabled || this.client.world == null || this.client.player == null) {
            return null;
        }

        if (localAppearanceActive()) {
            BbModel model = this.assets.get(this.localSelf.hash);
            return model == null ? null
                    : new GuiPreviewAppearance(this.localSelf.modelId, this.localSelf.instance, this.localSelf.hash,
                            model, this.localSelf.profile);
        }
        Binding own = this.bindings.get(this.client.player.getUuid());
        if (usable(own, System.nanoTime())) {
            BbModel model = this.assets.get(own.hash);
            return model == null ? null
                    : new GuiPreviewAppearance(own.modelId, own.instance, own.hash, model, installedProfile(own.hash));
        }
        if (own == null && !this.previewId.isEmpty() && this.assets.containsKey(this.previewHash)
                && ModelRenderer.has(this.previewHash)) {
            return new GuiPreviewAppearance(this.previewId, "preview:" + this.previewId, this.previewHash,
                    this.assets.get(this.previewHash), installedProfile(this.previewHash));
        }

        return null;
    }

    public GuiPreviewInput guiPreviewInput() {
        ClientPlayerEntity player = this.client.player;
        if (player == null) {
            return new GuiPreviewInput(1.8f, 1f, null);
        }

        Vec3d position = player.getEntityPos();
        Entity vehicle = player.getVehicle();
        String riding = vehicle == null ? ""
                : (vehicle instanceof AbstractBoatEntity ? "boat"
                        : (vehicle instanceof AbstractMinecartEntity ? "minecart"
                                : (vehicle instanceof PigEntity ? "ride-pig" : "ride")));
        EntityAnimationController.Sample sample = new EntityAnimationController.Sample(position.x, position.y,
                position.z, player.isOnGround(), player.isSleeping(), player.getPose() == EntityPose.SWIMMING,
                player.isTouchingWater(), player.getAbilities().flying, player.isGliding(),
                player.isSneaking() || player.getPose() == EntityPose.CROUCHING, player.isSprinting(), riding,
                player.handSwinging, Math.max(0, player.handSwingTicks), player.preferredHand == Hand.OFF_HAND,
                this.client.interactionManager != null && this.client.interactionManager.isBreakingBlock(), true,
                player.isClimbing(), vanillaState(player.getUuid()),
                VanillaYsmQueries.movementFallback(this.client, player));
        return new GuiPreviewInput(player.getHeight(), player.getScale(), sample);
    }

    public void configureGuiPreviewExpressionContext(Molang.Context context, String texture) {
        ClientPlayerEntity player = this.client.player;
        VanillaYsmQueries.populate(this.client, player, player == null ? 20 : player.getHungerManager().getFoodLevel(),
                texture, context);
        // Inventory rendering is third person even when the camera behind the menu is first person.
        context.query("ysm.rendering_in_inventory", 1d);
        context.query("ysm.rendering_in_paperdoll", 0d);
        context.query("ysm.is_first_person", 0d);
        context.query("query.is_first_person", 0d);
        context.query("ysm.person_view", 2d);
    }

    private static VanillaYsmAnimations.ItemState vanillaItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return VanillaYsmAnimations.ItemState.EMPTY;
        }

        String id = Registries.ITEM.getId(stack.getItem()).toString();
        Set<String> tags = new LinkedHashSet<>();
        stack.streamTags().limit(128).forEach(tag -> tags.add(tag.id().toString()));
        String kind = "";
        for (String tool : List.of("sword", "axe", "pickaxe", "shovel", "hoe", "shield", "crossbow", "bow",
                "fishing_rod")) {
            if (tags.contains("minecraft:" + tool + "s") || tags.contains("c:" + tool + "s")
                    || id.equals("minecraft:" + tool)) {
                kind = tool;
                break;
            }
        }

        if (kind.isEmpty() && (id.equals("minecraft:trident") || tags.contains("c:tridents"))) {
            kind = "spear";
        }

        if (kind.isEmpty()
                && (id.endsWith("_spear") || tags.contains("minecraft:spears") || tags.contains("c:spears"))) {
            kind = "lance";
        }

        if (kind.isEmpty() && (id.equals("minecraft:splash_potion") || id.equals("minecraft:lingering_potion"))) {
            kind = "throwable_potion";
        }

        if (kind.isEmpty() && id.equals("minecraft:mace")) {
            kind = "mace";
        }

        // OpenYSM compares item/components/count; a damaged previous stack compares only item type.
        long revision = 31L * ItemStack.hashCode(stack) + stack.getCount();
        return new VanillaYsmAnimations.ItemState(id, tags, kind, stack.getUseAction().name().toLowerCase(Locale.ROOT),
                false, VanillaYsmQueries.charged(stack), revision, stack.isDamaged(),
                new ItemStackComparisonKey(stack));
    }

    /** Adapt OpenYSM's ItemStack.matches to Yarn's exact component/count comparison. */
    private static final class ItemStackComparisonKey implements VanillaYsmAnimations.TrackedItemComparison {
        // LivingEntityFrameState retains the actual stack; it does not copy the tracked hand item.
        private final ItemStack trackedStack;

        ItemStackComparisonKey(ItemStack stack) {
            this.trackedStack = stack;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ItemStackComparisonKey key
                    && ItemStack.areEqual(this.trackedStack, key.trackedStack);
        }

        @Override
        public int hashCode() {
            return 31 * ItemStack.hashCode(this.trackedStack) + this.trackedStack.getCount();
        }

        @Override
        public boolean isDamaged() {
            return this.trackedStack.isDamaged();
        }
    }

    private RenderBinding entityBinding(Binding binding, BbModel model, PlayerEntity player) {
        NativePlayerPresentation.Snapshot presentation = NativePlayerPresentation.frame(player);
        float delta = presentation.tickDelta();
        Vec3d pos = new Vec3d(presentation.x(), presentation.y(), presentation.z());
        float bodyYaw = presentation.bodyYaw();
        float headYaw = presentation.headYaw(), headPitch = presentation.headPitch();
        LocalMotionPolicy policy = binding.motion;
        if (binding == this.localSelf) {
            policy = policy.withActionLock(this.options.localActionLocked);
        }

        else if (binding == this.privateBindings.get(binding.owner)) {
            PrivateModelSyncClient.Remote remote = this.privateModels.remote(binding.owner);
            if (remote != null && binding.instance.equals(remote.generation.toString())) {
                policy = policy.withActionLock(remote.extra.get("locked").getAsBoolean());
            }

        }
        boolean own = player == this.client.player, bedSleeping = policy.specialPose().isEmpty() && player.isSleeping();
        if (!policy.specialPose().isEmpty()) {
            pos = pos.add(policy.anchorX(), policy.anchorY(), policy.anchorZ());
            bodyYaw = policy.anchorYaw();
            if (policy.specialPose().equals("sleep")) {
                headYaw = bodyYaw;
                headPitch = 0;
            }
        }
        Entity vehicle = player.getVehicle();
        String riding = vehicle == null ? ""
                : (vehicle instanceof AbstractBoatEntity ? "boat"
                        : (vehicle instanceof AbstractMinecartEntity ? "minecart"
                                : (vehicle instanceof PigEntity ? "ride-pig" : "ride")));
        Vec3d current = player.getEntityPos();
        binding.localMotion.update(this.localTick,
                new EntityAnimationController.Sample(current.x, current.y, current.z, player.isOnGround(), bedSleeping,
                        player.getPose() == EntityPose.SWIMMING || policy.forcedPose().equals("crawl"),
                        player.isTouchingWater(), nativeFlying(player), player.isGliding(),
                        player.isSneaking() || player.getPose() == EntityPose.CROUCHING
                                || policy.forcedPose().equals("sneak"),
                        player.isSprinting(), riding, player.handSwinging, player.handSwingTicks,
                        player.preferredHand == Hand.OFF_HAND,
                        own && this.client.interactionManager != null
                                && this.client.interactionManager.isBreakingBlock(),
                        own, player.isClimbing(), vanillaState(player.getUuid()),
                        VanillaYsmQueries.movementFallback(this.client, player)),
                policy, binding.localServerLayers, model.animationCatalog());
        return new RenderBinding(binding.owner, binding.instance, binding.hash, model, binding.localMotion.layers(),
                this.localTick + delta, pos.x, pos.y, pos.z, bodyYaw, headYaw, headPitch, binding.scale,
                binding.hidePlayer, own ? "local-player" : "tracked-player");
    }

    /** Match YSM's remote-state cache: vanilla sends player abilities only to their owner. */
    private boolean nativeFlying(PlayerEntity player) {
        if (player == null) {
            return false;
        }

        if (player == this.client.player) {
            return player.getAbilities().flying;
        }

        if (privateUsable(player.getUuid())) {
            return this.privateModels.isFlying(player.getUuid());
        }

        Binding binding = this.bindings.get(player.getUuid());
        return binding == null ? player.getAbilities().flying : binding.motion.flying();
    }

    /** GSit's explicit pose anchor is an authored offset, not a second player-position timeline. */
    public boolean hasServerPoseAnchor(UUID owner) {
        Binding binding = this.bindings.get(owner);
        return binding != null && !binding.motion.specialPose().isEmpty()
                && !(localAppearanceActive() && owner.equals(this.client.player.getUuid()));
    }

    public boolean shouldHidePlayer(UUID owner) {
        if (!this.options.enabled) {
            return false;
        }

        if (privateUsable(owner)) {
            return true;
        }

        if (localAppearanceActive() && owner.equals(this.client.player.getUuid())) {
            return this.options.hideVanillaPlayer;
        }

        Binding binding = this.bindings.get(owner);
        long now = System.nanoTime();
        if (usable(binding, now)) {
            return binding.hidePlayer;
        }

        return binding == null && !this.previewId.isEmpty() && this.client.player != null
                && owner.equals(this.client.player.getUuid()) && this.assets.containsKey(this.previewHash)
                && ModelRenderer.has(this.previewHash) && this.options.hideVanillaPlayer;
    }

    /** ME can hide the original copy by packet without changing the actual player's invisibility. */
    public Boolean modelSourceInvisible(UUID owner) {
        Binding binding = this.bindings.get(owner);
        return usable(binding, System.nanoTime()) ? binding.sourceInvisible : null;
    }

    /** UI reads the authoritative hide-player rule; draft server preferences cannot change this setting. */
    public boolean ownPlayerHideSetting() {
        if (!serverOwnModelPresent()) {
            return this.options.hideVanillaPlayer;
        }

        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        // Without metadata, vanilla keeps honoring the server's original invisibility/display rules.
        return own != null && own.hidePlayer;
    }

    public boolean serverOwnPlayerHideRuleKnown() {
        return serverOwnModelPresent() && this.client.player != null
                && this.bindings.containsKey(this.client.player.getUuid());
    }

    public boolean serverOwnModelShowAllowed() {
        if (!serverOwnModelPresent()) {
            return true;
        }

        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        return own == null || own.showSelf;
    }

    public boolean ownModelShowSetting() {
        return this.options.showSelf && serverOwnModelShowAllowed();
    }

    /** Native gear is separate from author geometry and held items; server disguises always suppress it. */
    public boolean shouldHideVanillaLayers(UUID owner) {
        if (!this.options.enabled) {
            return false;
        }

        return isServerDisguised(owner) || privateUsable(owner) || localAppearanceActive() && this.client.player != null
                && owner.equals(this.client.player.getUuid()) && this.options.hideVanillaEquipment;
    }

    private boolean usable(Binding binding, long now) {
        return this.options.enabled && binding != null && binding.active && !binding.unsupported
                && now - binding.lastPacket < this.leaseTicks * 50_000_000L
                && now - this.lastReceived < this.leaseTicks * 50_000_000L && ModelRenderer.has(binding.hash);
    }

    public boolean shouldHideFirstPersonArm() {
        return !localAppearanceActive() && this.client.player != null && shouldHidePlayer(this.client.player.getUuid());
    }

    public void renderFailed(UUID owner, String instance, String hash, String reason) {
        for (Binding failedPrivate : List.copyOf(this.privateBindings.values())) {
            if (failedPrivate.hash.equals(hash) && (owner == null
                    || failedPrivate.owner.equals(owner) && failedPrivate.instance.equals(instance))) {
                this.privateModels.rejectRemote(failedPrivate.owner, UUID.fromString(failedPrivate.instance),
                        "本机绘制失败，已恢复原版玩家：" + reason);
            }

        }

        if (this.localSelf != null && this.localSelf.hash.equals(hash)
                && (owner == null || this.localSelf.owner.equals(owner) && this.localSelf.instance.equals(instance))) {
            this.localSelf = null;
            this.localAppearanceRequest++;
            this.localAppearancePending = false;
            this.localAppearanceError = "本地外观绘制失败：" + reason;
            this.localAppearanceRetryAfter = System.nanoTime() + 30 * SECOND;
        }
        if (owner == null) {
            for (Binding binding : this.bindings.values()) {
                if (binding.hash.equals(hash)) {
                    failed(binding, "模型纹理恢复失败");
                }

            }

            this.failedAssets.put(hash, System.nanoTime());
        }
        Binding binding = this.bindings.get(owner);
        if (binding != null && binding.instance.equals(instance) && binding.hash.equals(hash)) {
            failed(binding, "本地绘制失败，恢复服务器显示");
            this.failedAssets.put(hash, System.nanoTime());
            this.bindings.remove(owner);
            MEPlayerActionsClient.LOGGER.warn("Local renderer failed: {}", reason);
        }
        if (this.previewHash.equals(hash)) {
            this.previewId = "";
            this.previewHash = "";
        }
    }

    /** Pause GPU leases for texture reload while retaining protocol identities and pending downloads. */
    public void releaseAll() {
        for (Binding binding : this.bindings.values()) {
            failed(binding, "");
        }
    }

    public boolean serverBridgeReady() {
        return this.connected && this.acknowledged && this.client.getNetworkHandler() != null
                && System.nanoTime() - this.lastReceived < this.leaseTicks * 50_000_000L;
    }

    public boolean serverBridgeConnected() {
        return serverBridgeReady();
    }

    /** Local cache preview metadata never grants permission to request or bind a server model. */
    public List<Action> serverCatalog() {
        LinkedHashMap<String, ClientRuntime.Action> result = new LinkedHashMap<String, Action>();
        if (serverCatalogReady()) {
            for (ServerModelCatalogSnapshot.Model model : this.serverModelCatalog.models()) {
                result.put(model.id(), new Action(model.id(), model.label()));
            }

        }

        for (ServerCachedModelCatalog.Model model : this.cachedServerCatalog) {
            result.putIfAbsent(model.id(), new Action(model.id(), model.label()));
        }

        return List.copyOf(result.values());
    }

    /** Current-session directory labels do not grant downloads; older cached IDs keep a separate source. */
    public List<ServerModelCatalogSnapshot.Model> serverCatalogDirectory() {
        return serverCatalogReady() ? this.serverModelCatalog.models() : List.of();
    }

    public long serverCatalogRevision() {
        return this.serverModelCatalog.displayRevision() + this.serverCacheCatalogRevision;
    }

    public boolean serverCatalogReady() {
        return serverBridgeReady() && this.stateProtocol.serverCatalog() && this.serverModelCatalog.ready();
    }

    public boolean serverCatalogOfflineMode() {
        return !serverBridgeReady();
    }

    public boolean serverCachedPreviewOnly(String id) {
        return this.cachedServerCatalog.stream().anyMatch(model -> model.id().equals(id))
                && !(serverCatalogReady() && this.serverModelCatalog.contains(id));
    }

    public boolean serverModelHasPersistentId(String id) {
        return ServerModelCatalogSnapshot.validId(id) && (serverCatalogReady() && this.serverModelCatalog.contains(id)
                || this.cachedServerCatalog.stream().anyMatch(model -> model.identified() && model.id().equals(id)));
    }

    public String serverCatalogStatus() {
        String cached = this.cachedServerCatalog.isEmpty() ? ""
                : " · 本地缓存 " + this.cachedServerCatalog.size() + " 个（仅预览）";
        if (serverCatalogOfflineMode()) {
            return !this.serverCacheIndexReady ? "离线模式 · 正在读取本地缓存"
                    : "离线模式 · 本地缓存 " + this.cachedServerCatalog.size() + " 个，仅可预览";
        }

        if (!this.stateProtocol.serverCatalog()) {
            return "服务器尚未提供模型图库，请更新服务端" + cached;
        }

        if (this.serverModelCatalog.receiving()) {
            return "正在接收服务端模型目录" + cached;
        }

        if (!this.serverModelCatalog.ready()) {
            return "等待服务端模型目录" + cached;
        }

        if (!this.serverModelCatalog.canDisguise()) {
            return "服务器未授予伪装权限" + cached;
        }

        if (this.serverModelCatalog.models().isEmpty()) {
            return "暂无可用服务端模型" + cached;
        }

        return (this.serverModelCatalog.truncated() ? "已列出 " + this.serverModelCatalog.models().size() + " 个模型 · 目录达到上限"
                : "服务端模型 · " + this.serverModelCatalog.models().size() + " 个") + cached;
    }

    private LocalModelLibrary.Loaded preparedServerPreview(String id) {
        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        String currentId = own == null ? "" : own.modelId, currentHash = own == null ? "" : own.hash;
        Binding current = this.bindings.values().stream()
                .filter(binding -> binding.modelId.equals(id)
                        && ServerPreviewIdentity.mayShow(id, currentId, currentHash, binding.hash)
                        && this.serverAssets.contains(binding.hash) && this.assets.containsKey(binding.hash)
                        && ModelRenderer.has(binding.hash))
                .findFirst().orElse(null);
        String hash = current == null ? this.serverModelAssets.get(id) : current.hash;
        if (hash == null || !ServerPreviewIdentity.mayShow(id, currentId, currentHash, hash)
                || !this.serverAssets.contains(hash) || !this.assets.containsKey(hash) || !ModelRenderer.has(hash)) {
            return null;
        }

        BbModelAsset.Decoded metadata = this.assetMetadata.get(hash);
        return new LocalModelLibrary.Loaded(hash, this.assets.get(hash),
                metadata == null ? "" : metadata.previewAnimation(), installedProfile(hash));
    }

    private ServerCachedModelCatalog.Model cachedServerPreviewMetadata(String id) {
        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        String currentId = own == null ? "" : own.modelId, currentHash = own == null ? "" : own.hash;
        return this.cachedServerCatalog.stream()
                .filter(model -> model.id().equals(id)
                        && ServerPreviewIdentity.mayShow(id, currentId, currentHash, model.hash()))
                .findFirst().orElse(null);
    }

    public boolean serverModelPreviewUsesCache(String id) {
        return !(serverCatalogReady() && this.serverModelCatalog.contains(id) && preparedServerPreview(id) != null)
                && cachedServerPreviewMetadata(id) != null;
    }

    /** Presentation reads protocol/cache facts, never assumes a gallery preview proves an active render lease. */
    public ServerModelPresentation.Display serverModelPresentation(String id) {
        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        Binding binding = own != null && own.modelId.equals(id) ? own
                : this.bindings.values().stream().filter(value -> value.modelId.equals(id)).findFirst().orElse(null);
        boolean current = serverOwnModelPresent() && id.equals(serverOwnModelId());
        ServerCachedModelCatalog.Model cached = cachedServerPreviewMetadata(id);
        if (cached != null && !ServerModelPresentation.cacheMatchesCurrentResource(binding != null,
                binding == null ? "" : binding.hash, cached.hash())) {
            cached = null;
        }

        boolean prepared = binding != null && this.assets.containsKey(binding.hash) && ModelRenderer.has(binding.hash);
        ServerModelCatalogSnapshot.Model directory = serverCatalogReady()
                ? this.serverModelCatalog.model(id).orElse(null)
                : null;
        ServerModelPresentation.CatalogResource publication = directory == null
                ? ServerModelPresentation.CatalogResource.unknown()
                : new ServerModelPresentation.CatalogResource(directory.clientResource(), directory.source(),
                        directory.folder());
        return ServerModelPresentation.describe(new ServerModelPresentation.Facts(serverBridgeReady(), current,
                !id.isEmpty() && id.equals(pendingServerDisguiseModelId()), this.options.enabled,
                binding == null || binding.showSelf, this.options.showSelf,
                binding != null && usable(binding, System.nanoTime()), prepared,
                binding != null && this.loading.contains(binding.hash), cached != null,
                cached != null && this.cachedServerPreviewLoading.contains(cached.hash()),
                cached != null && this.cachedServerPreviewErrors.containsKey(cached.hash()),
                binding == null ? "" : binding.serverAssetStatus, binding == null ? "" : binding.serverAssetSource,
                binding == null ? "" : binding.serverAssetReason, binding == null ? "" : binding.assetState,
                binding == null ? "" : binding.assetError), publication);
    }

    /** Reuses prepared assets or asynchronously reads the local cache; no asset_request, ACK or binding is created. */
    public LocalModelLibrary.Loaded serverModelForPreview(String id) {
        if (serverOwnModelPresent() && id.equals(serverOwnModelId())
                || serverCatalogReady() && this.serverModelCatalog.contains(id)) {
            LocalModelLibrary.Loaded prepared = preparedServerPreview(id);
            if (prepared != null) {
                return prepared;
            }

        }
        ServerCachedModelCatalog.Model cached = cachedServerPreviewMetadata(id);
        if (cached == null) {
            return null;
        }

        LocalModelLibrary.Loaded loaded = this.cachedServerPreviews.get(cached.hash());
        if (loaded == null) {
            loadCachedServerPreview(cached);
        }

        return loaded;
    }

    /** A bounded worker scans at startup and after a cache write, never on each UI/tick read. */
    public void refreshServerCacheIndex() {
        if (this.serverCacheIndexLoading) {
            this.serverCacheIndexRefreshPending = true;
            return;
        }
        this.serverCacheIndexLoading = true;
        try {
            this.previewDecoder.execute(() -> {
                List<ServerCachedModelCatalog.Model> result = this.serverCachedModelCatalog.load();
                this.client.execute(() -> {
                    this.serverCacheIndexLoading = false;
                    this.serverCacheIndexReady = true;
                    if (!this.cachedServerCatalog.equals(result)) {
                        this.cachedServerCatalog = result;
                        this.serverCacheCatalogRevision++;
                        Set<String> hashes = new HashSet<>();
                        result.forEach(model -> hashes.add(model.hash()));
                        this.cachedServerPreviews.keySet().retainAll(hashes);
                        this.cachedServerPreviewRetry.keySet().retainAll(hashes);
                        this.cachedServerPreviewErrors.keySet().retainAll(hashes);
                    }
                    if (this.serverCacheIndexRefreshPending) {
                        this.serverCacheIndexRefreshPending = false;
                        refreshServerCacheIndex();
                    }
                });
            });
        } catch (RejectedExecutionException busy) {
            this.serverCacheIndexLoading = false;
        }
    }

    private void rememberCachedServerModel(String id, String hash) {
        try {
            this.decoder.execute(() -> {
                try {
                    if (this.serverModelCache.rememberModel(id, hash)) {
                        this.client.execute(this::refreshServerCacheIndex);
                    }
                } catch (IOException ignored) {
                    /* An in-memory asset need not have a disk copy. */}
            });
        } catch (RejectedExecutionException ignored) {
            /* Browsing must not block authorized asset synchronization. */}
    }

    private void loadCachedServerPreview(ServerCachedModelCatalog.Model metadata) {
        String hash = metadata.hash();
        long now = System.nanoTime();
        if (now < this.cachedServerPreviewRetry.getOrDefault(hash, 0L) || !this.cachedServerPreviewLoading.add(hash)) {
            return;
        }

        long epoch = this.serverCachePreviewEpoch;
        try {
            this.previewDecoder.execute(() -> {
                LocalModelLibrary.Loaded loaded = null;
                String error = "";
                try {
                    byte[] raw = this.serverModelCache.readValidated(hash).orElse(null);
                    if (raw != null) {
                        BbModelAsset.Decoded decoded = BbModelAsset.read(raw);
                        loaded = new LocalModelLibrary.Loaded(hash, decoded.model(), decoded.previewAnimation(),
                                decoded.profile());
                    } else {
                        error = "缓存文件不存在或校验未通过";
                    }

                } catch (Exception failure) {
                    error = "缓存模型解析失败";
                }
                LocalModelLibrary.Loaded result = loaded;
                String failure = error;
                this.client.execute(() -> {
                    if (epoch != this.serverCachePreviewEpoch) {
                        return;
                    }

                    this.cachedServerPreviewLoading.remove(hash);
                    if (result == null) {
                        this.cachedServerPreviewErrors.put(hash, failure);
                        this.cachedServerPreviewRetry.put(hash, System.nanoTime() + 30 * SECOND);
                        return;
                    }
                    if (this.cachedServerCatalog.stream().noneMatch(model -> model.hash().equals(hash))) {
                        return;
                    }

                    this.cachedServerPreviewErrors.remove(hash);
                    this.cachedServerPreviews.put(hash, result);
                    while (this.cachedServerPreviews.size() > 16) {
                        this.cachedServerPreviews.remove(this.cachedServerPreviews.keySet().iterator().next());
                    }

                });
            });
        } catch (RejectedExecutionException busy) {
            this.cachedServerPreviewLoading.remove(hash);
            this.cachedServerPreviewRetry.put(hash, now + SECOND);
        }
    }

    public boolean canRequestServerDisguise(String id) {
        this.serverDisguiseRequest.expire(System.nanoTime());
        return this.serverDisguiseRequest.modelId().isEmpty() && this.serverModelCatalog.canRequest(id,
                serverCatalogReady() && this.client.player != null, System.nanoTime());
    }

    /** The server command owns gameplay permission checks and later sends the authoritative binding. */
    public boolean requestServerDisguise(String id) {
        if (!canRequestServerDisguise(id)) {
            return false;
        }

        try {
            ServerDisguisePreferences preferences = Objects.requireNonNull(this.options.serverDisguisePreferences(id));
            Optional<String> command = this.serverModelCatalog.command(id,
                    serverCatalogReady() && this.client.player != null, System.nanoTime(), preferences);
            if (command.isEmpty()) {
                return false;
            }

            UUID nonce = UUID.randomUUID();
            boolean resultRequired = this.stateProtocol.disguiseResults();
            String message = command.get() + (resultRequired ? " request-id=" + nonce : "");
            if (message.length() > 256) {
                throw new IllegalArgumentException("Disguise command length");
            }

            String previousInstance = serverOwnModelInstance();
            this.client.getNetworkHandler().sendChatCommand(message);
            this.requestPacketsSent++;
            this.serverDisguiseRequest.sent(nonce, id, previousInstance, preferences, resultRequired,
                    System.nanoTime());
            return true;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            this.lastError = "服务器伪装设置无效，请检查保存的参数";
            this.serverDisguiseRequest.failed(this.lastError);
            return false;
        } catch (RuntimeException unavailable) {
            this.lastError = "服务器伪装请求未发送：服务器连接不可用";
            this.serverDisguiseRequest.failed(this.lastError);
            return false;
        }
    }

    public String pendingServerDisguiseModelId() {
        this.serverDisguiseRequest.expire(System.nanoTime());
        return this.serverDisguiseRequest.modelId();
    }

    public String serverDisguiseRequestStatus() {
        this.serverDisguiseRequest.expire(System.nanoTime());
        return this.serverDisguiseRequest.status();
    }

    public void serverPreferencesSaved(String id) {
        this.serverDisguiseRequest.preferencesSaved(id);
    }

    public boolean serverPreferencesNeedApply(String id) {
        return this.serverDisguiseRequest.preferencesNeedApply(id,
                serverOwnModelId().equals(id) ? serverOwnModelInstance() : "",
                this.options.serverDisguisePreferences(id));
    }

    public Path localServerCacheDirectory() {
        return this.cache.toAbsolutePath().normalize();
    }

    public Path localPrivateModelCacheDirectory() {
        return this.cache.resolve("private-models").toAbsolutePath().normalize();
    }

    public boolean serverOwnModelReady() {
        return this.client.player != null && serverBridgeReady()
                && usable(this.bindings.get(this.client.player.getUuid()), System.nanoTime());
    }

    public PlayerInteractionPolicy interactionPolicy() {
        UUID self = this.client.player == null ? null : this.client.player.getUuid();
        Binding own = self == null ? null : this.bindings.get(self);
        boolean present = PlayerInteractionPolicy.isServerDisguised(self, self, this.bindings.keySet(),
                this.localAppearanceVisibility.hasServerAppearance());
        boolean previousServerAppearance = this.playerInteractionPolicy.serverOwnModelPresent();
        this.playerInteractionPolicy.observe(present, own == null ? "" : own.instance, serverBridgeReady());
        if (present && !previousServerAppearance) {
            this.privateModels.stopPublishing();
        }

        if (present && !this.options.privateAppearancePaused
                && (!previousServerAppearance || System.nanoTime() - this.lastPrivatePauseSaveAttempt >= SECOND)) {
            this.lastPrivatePauseSaveAttempt = System.nanoTime();
            if (!this.options.updatePrivateAppearancePaused(true)) {
                this.lastError = "私人模型暂停状态未能保存；请检查客户端配置文件";
            }

        }
        if (own != null) {
            this.knownOwnServerModelId = own.modelId;
        }

        else if (!present) {
            this.knownOwnServerModelId = "";
        }

        if (!this.playerInteractionPolicy.canUseLocalAppearance()) {
            suspendLocalAppearanceForServer();
        }

        return this.playerInteractionPolicy;
    }

    /** Presence survives loading/render failure and is independent of a GPU-ready lease. */
    public boolean serverOwnModelPresent() {
        return interactionPolicy().serverOwnModelPresent();
    }

    public boolean hasOwnServerAppearance() {
        return serverOwnModelPresent();
    }

    public boolean hasOwnServerDisguise() {
        return serverOwnModelPresent();
    }

    public boolean interactionLocalMode() {
        return interactionPolicy().interactionLocalMode();
    }

    /** Action source follows the current disguise; selecting a tab never resumes a paused private appearance. */
    public boolean selectInteractionSource(boolean local) {
        PlayerInteractionPolicy policy = interactionPolicy();
        if (!policy.allowsScope(local ? PlayerInteractionPolicy.Scope.CLIENT : PlayerInteractionPolicy.Scope.SERVER)) {
            return false;
        }

        WheelPreferences.Source source = local ? WheelPreferences.Source.CLIENT : WheelPreferences.Source.SERVER;
        WheelPreferences preferences = this.options.wheelPreferences();
        if (preferences.source() != source && !this.options.updateWheelPreferences(preferences.withSource(source))) {
            return false;
        }

        if (!policy.selectSource(local)) {
            return false;
        }

        if (local) {
            ensureLocalAppearance();
        } else {
            suspendLocalAppearanceForServer();
        }

        return true;
    }

    /** Server equipment rules follow the underlying disguise even when a private self mesh is selected. */
    public boolean isServerDisguised(UUID owner) {
        return PlayerInteractionPolicy.isServerDisguised(owner,
                this.client.player == null ? null : this.client.player.getUuid(), this.bindings.keySet(),
                this.localAppearanceVisibility.hasServerAppearance());
    }

    public boolean canEditLocalAppearance() {
        return interactionPolicy().canEditLocalAppearance();
    }

    public boolean canActivateLocalAppearance() {
        return interactionPolicy().canActivateLocalAppearance();
    }

    public boolean canUseLocalAppearance() {
        return interactionPolicy().canUseLocalAppearance();
    }

    public boolean canUseLocalActions() {
        return interactionPolicy().canUseLocalActions();
    }

    /** The actual applied identity, including a server fallback when local GPU rendering is paused/unavailable. */
    public AppearanceSource currentAppearanceSource() {
        PlayerInteractionPolicy policy = interactionPolicy();
        if (policy.serverOwnModelPresent()) {
            return AppearanceSource.SERVER;
        }

        if (policy.isCurrentLocalAppearance(localAppearance().enabled(), appliedLocalAppearance())) {
            return AppearanceSource.CLIENT;
        }

        if (policy.canUseLocalAppearance() && this.client.world != null && this.client.player != null
                && !this.previewId.isEmpty() && this.assets.containsKey(this.previewHash)) {
            return AppearanceSource.CLIENT;
        }

        return AppearanceSource.NONE;
    }

    private boolean appliedLocalAppearance() {
        return this.localSelf != null && this.client.world != null && this.client.player != null
                && this.localSelf.owner.equals(this.client.player.getUuid())
                && this.localSelf.modelId.equals(localAppearance().modelId())
                && this.assets.containsKey(this.localSelf.hash);
    }

    public String currentAppearanceSourceId() {
        return switch (currentAppearanceSource()) {
        case CLIENT -> "client";
        case SERVER -> "server";
        case NONE -> "vanilla";
        };
    }

    public String currentAppearanceModelId() {
        return switch (currentAppearanceSource()) {
        case SERVER -> serverOwnModelId();
        case CLIENT ->
            appliedLocalAppearance() && localAppearance().enabled() ? this.localSelf.modelId : this.previewId;
        case NONE -> "";
        };
    }

    public String currentAppearanceAssetHash() {
        return switch (currentAppearanceSource()) {
        case SERVER -> {
            Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
            yield own == null ? "" : own.hash;
        }
        case CLIENT -> appliedLocalAppearance() && localAppearance().enabled() ? this.localSelf.hash : this.previewHash;
        case NONE -> "";
        };
    }

    public String pendingLocalAppearanceModelId() {
        return this.localAppearancePending && canUseLocalAppearance() && localAppearance().enabled()
                ? localAppearance().modelId()
                : "";
    }

    public boolean canUseServerActions() {
        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        return interactionPolicy().canUseServerActions() && own != null
                && System.nanoTime() - own.lastPacket < this.leaseTicks * 50_000_000L;
    }

    /** The existing command remains available without a rendered model or active GPU lease. */
    public boolean canRequestServerUndisguise() {
        return this.connected && this.client.player != null && this.client.getNetworkHandler() != null
                && serverOwnModelPresent();
    }

    /** The server owns permission checks and removal; retain bindings until its confirmation arrives. */
    public boolean requestServerUndisguise() {
        if (!canRequestServerUndisguise()) {
            return false;
        }

        try {
            this.client.getNetworkHandler().sendChatCommand("meplayeractions undisguise");
            return true;
        } catch (RuntimeException exception) {
            this.lastError = "解除服务器伪装请求未发送：服务器连接不可用";
            return false;
        }
    }

    public String localAppearanceSuspensionReason() {
        return interactionPolicy().suspensionReason();
    }

    public String serverOwnModelId() {
        interactionPolicy();
        return this.knownOwnServerModelId;
    }

    public String serverOwnModelInstance() {
        return interactionPolicy().serverInstance();
    }

    public WheelPreferences wheelPreferences() {
        return this.options.wheelPreferences();
    }

    public boolean updateWheelPreferences(WheelPreferences value) {
        return this.options.updateWheelPreferences(Objects.requireNonNull(value));
    }

    public Map<String, Object> ownServerAppearanceDiagnostics() {
        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        PlayerInteractionPolicy policy = interactionPolicy();
        boolean prepared = localAppearancePrepared(), active = localAppearanceActive();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("present", serverOwnModelPresent());
        values.put("bindingPresent", own != null);
        values.put("owner", this.client.player == null ? "" : this.client.player.getUuid().toString());
        values.put("instance", policy.serverInstance());
        values.put("modelId", this.knownOwnServerModelId);
        values.put("hash", own == null ? "" : own.hash);
        values.put("ready", serverOwnModelReady());
        values.put("bridgeReady", serverBridgeReady());
        values.put("assetState", own == null ? "" : own.assetState);
        values.put("assetError", own == null ? "" : own.assetError);
        values.put("serverAssetStatus", own == null ? "" : own.serverAssetStatus);
        values.put("serverAssetSource", own == null ? "" : own.serverAssetSource);
        values.put("serverAssetReason", own == null ? "" : own.serverAssetReason);
        values.put("presentation", serverModelPresentation(this.knownOwnServerModelId).lines().stream()
                .map(ServerModelPresentation.Line::text).toList());
        values.put("interactionLocalMode", policy.interactionLocalMode());
        values.put("interactionSource", policy.interactionLocalMode() ? "client" : "server");
        values.put("privateEnabled", localAppearance().enabled());
        values.put("privatePending", this.localAppearancePending);
        values.put("privatePrepared", prepared);
        values.put("privateActive", active);
        values.put("privateSuspended", policy.localSelectionPaused() && localAppearance().enabled());
        values.put("privateWaitingForLease", false);
        values.put("suspensionReason", localAppearanceSuspensionReason());
        return Map.copyOf(values);
    }

    /** Counts only server gameplay requests, so private action previews can prove that none were sent. */
    public long requestPacketsSent() {
        return this.requestPacketsSent;
    }

    /** Actual protocol/cache/GPU events, with cumulative counters surviving reconnects. */
    public Map<String, Object> serverPushDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetMode", this.serverAssetMode);
        result.put("capabilities", this.serverCapabilities);
        result.put("incrementalState", this.stateProtocol.incremental());
        result.put("serverTimeline", this.stateProtocol.serverTimeline());
        result.put("negotiated", this.pushNegotiated);
        result.put("generation", this.generation);
        result.put("unsupportedHandshake", this.unsupportedHandshake);
        result.put("offersReceived", this.pushOffersReceived);
        result.put("cacheHits", this.pushCacheHits);
        result.put("cacheMisses", this.pushCacheMisses);
        result.put("transfersBegun", this.pushTransfersBegun);
        result.put("transfersCompleted", this.pushTransfersCompleted);
        result.put("gpuPrepared", this.pushGpuPrepared);
        result.put("texturePreparation", com.simmc.meplayeractions.client.render.TexturePreparation.SHARED.diagnostics());
        result.put("textureInstalls", this.textureInstalls.size());
        result.put("failedAssetRecords", this.failedAssets.size());
        result.put("renderReadySent", this.pushReadySent);
        result.put("renderAcks", this.pushRenderAcks);
        result.put("assetRequestPacketsSent", this.pushAssetRequests);
        result.put("cancelled", this.pushCancelled);
        result.put("rejected", this.pushRejected);
        result.put("activeOffers", this.pushAuthorization.diagnostics());
        result.put("transfers", this.pushAuthorization.receiving());
        result.put("bindings", this.bindings.values().stream().map(binding -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("owner", binding.owner.toString());
            values.put("instance", binding.instance);
            values.put("modelId", binding.modelId);
            values.put("hash", binding.hash);
            values.put("active", binding.active);
            values.put("readySent", binding.readySent);
            values.put("assetState", binding.assetState);
            values.put("assetError", binding.assetError);
            values.put("serverAssetStatus", binding.serverAssetStatus);
            values.put("serverAssetReason", binding.serverAssetReason);
            values.put("serverAssetSource", binding.serverAssetSource);
            values.put("sourceInvisible", binding.sourceInvisible == null ? "unknown" : binding.sourceInvisible);
            return Map.copyOf(values);
        }).toList());
        return Map.copyOf(result);
    }

    public LocalAppearanceSettings localAppearance() {
        return this.options.localAppearance();
    }

    public Path localAppearanceSettingsPath() {
        return this.options.path();
    }

    public List<Action> localModels() {
        try {
            return this.localModelLibrary.models().stream().map(entry -> new Action(entry.id(), entry.label()))
                    .toList();
        } catch (IOException exception) {
            MEPlayerActionsClient.LOGGER.warn("Cannot list local model directory: {}", exception.toString());
            return BuiltinYsmModels.models().stream().map(model -> new Action(model.id(), model.label())).toList();
        }
    }

    public List<Action> localActions() {
        if (!canUseLocalActions()) {
            return List.of();
        }

        BbModel model = this.localSelf == null ? null : this.assets.get(this.localSelf.hash);
        return model == null ? List.of()
                : model.animations().stream().filter(id -> !id.startsWith("parallel") && !id.startsWith("pre_parallel"))
                        .sorted().map(id -> new Action(id, previewLabel(id))).toList();
    }

    /** Save a private draft; a known server disguise or paused selection cannot be activated by an edit. */
    public void updateLocalAppearance(LocalAppearanceSettings settings) {
        Objects.requireNonNull(settings);
        if (!canEditLocalAppearance()) {
            notify(localAppearanceSuspensionReason());
            return;
        }
        LocalAppearanceSettings previous = this.options.localAppearance();
        this.options.setLocalAppearance(settings);
        if (settings.enabled() && canUseLocalAppearance()) {
            this.options.enabled = true;
        }

        this.options.save();
        if (!settings.enabled()) {
            this.privateModels.stopPublishing();
        }

        if (!previous.modelId().equals(settings.modelId()) || !settings.enabled()) {
            invalidateLocalAppearance();
        }

        else {
            this.localAppearanceError = "";
            this.localAppearanceRetryAfter = 0;
        }
        ensureLocalAppearance();
    }

    public void selectLocalModel(String id) {
        if (!canActivateLocalAppearance()) {
            notify(localAppearanceSuspensionReason());
            return;
        }
        if (!LocalAppearanceSettings.isValidModelId(id)) {
            notify("请选择可用私人模型");
            return;
        }
        if (!this.options.updatePrivateAppearancePaused(false)) {
            notify("私人模型状态未能保存，请检查客户端配置文件后重试");
            return;
        }
        if (!interactionPolicy().activateLocalAppearance()) {
            return;
        }

        LocalAppearanceSettings current = localAppearance();
        updateLocalAppearance(new LocalAppearanceSettings(true, id, current.scale(), current.offsetX(),
                current.offsetY(), current.offsetZ()));
    }

    public void disableLocalAppearance() {
        LocalAppearanceSettings current = localAppearance();
        updateLocalAppearance(new LocalAppearanceSettings(false, current.modelId(), current.scale(), current.offsetX(),
                current.offsetY(), current.offsetZ()));
    }

    public void reloadLocalAppearanceSettings() {
        if (!canEditLocalAppearance()) {
            return;
        }

        this.options.reloadLocalAppearance();
        invalidateLocalAppearance();
        ensureLocalAppearance();
    }

    public String localAppearanceStatus() {
        if (!canUseLocalAppearance()) {
            return localAppearanceSuspensionReason();
        }

        if (!localAppearance().enabled()) {
            return "本地外观已关闭";
        }

        if (!this.options.enabled) {
            return "客户端渲染已关闭；本地外观设置已保留";
        }

        if (this.client.world == null || this.client.player == null) {
            return "本地外观已保存，进入世界后显示";
        }

        if (!this.localAppearanceError.isEmpty()) {
            return this.localAppearanceError;
        }

        if (this.localAppearancePending) {
            return "正在加载本地模型";
        }

        if (localAppearancePrepared() && !localAppearanceActive()) {
            return "等待服务器显示接管；本地设置已保留";
        }

        return localAppearanceActive() ? (this.options.privateSyncEnabled ? privateSyncStatus() : "本地外观仅自己可见")
                : "等待本地模型";
    }

    public boolean playLocal(String animation) {
        if (!canUseLocalActions() || animation == null || !localAppearancePrepared()) {
            return false;
        }

        BbModel model = this.assets.get(this.localSelf.hash);
        if (!model.animations().contains(animation) || animation.startsWith("parallel")
                || animation.startsWith("pre_parallel")) {
            return false;
        }

        this.localSelf.localServerLayers = List
                .of(new Layer("manual", animation, this.localTick, 1, model.animationLoop(animation), 2, 2));
        this.localExtraSequence++;
        return true;
    }

    public void stopLocal() {
        if (this.localSelf != null) {
            this.localSelf.localServerLayers = List.of();
            this.localExtraSequence++;
        }
    }

    private void invalidateLocalAppearance() {
        if (this.localSelf != null) {
            closeModelEffects(this.localSelf.owner);
        }

        this.localAppearanceRequest++;
        this.localAppearancePending = false;
        this.localSelf = null;
        this.localAppearanceError = "";
        this.localAppearanceRetryAfter = 0;
    }

    private void suspendLocalAppearanceForServer() {
        if (this.localSelf != null || this.localAppearancePending) {
            invalidateLocalAppearance();
        }

        if (!this.previewId.isEmpty() || this.previewPending) {
            this.previewRequest++;
            this.previewPending = false;
            this.previewId = "";
            this.previewHash = "";
            this.previewManual = "";
            this.previewPose = "";
        }
    }

    private boolean localAppearancePrepared() {
        return canUseLocalActions() && this.options.enabled && localAppearance().enabled() && this.client.world != null
                && this.client.player != null && this.localSelf != null
                && this.localSelf.owner.equals(this.client.player.getUuid())
                && this.localSelf.modelId.equals(localAppearance().modelId())
                && this.assets.containsKey(this.localSelf.hash) && ModelRenderer.has(this.localSelf.hash);
    }

    private boolean localAppearanceActive() {
        if (!localAppearancePrepared()) {
            return false;
        }

        boolean bridgeAvailable = this.connected && this.client.getNetworkHandler() != null
                && ClientPlayNetworking.canSend(ActionPayload.ID);
        return this.localAppearanceVisibility.canRender(bridgeAvailable, serverBridgeReady(),
                usable(this.bindings.get(this.client.player.getUuid()), System.nanoTime()));
    }

    public boolean privateAppearanceActive() {
        return localAppearanceActive();
    }

    private void ensureLocalAppearance() {
        if (!canUseLocalAppearance()) {
            suspendLocalAppearanceForServer();
            return;
        }
        LocalAppearanceSettings settings = localAppearance();
        if (!this.options.enabled || !settings.enabled() || this.client.world == null || this.client.player == null
                || localAppearancePrepared() || this.localAppearancePending
                || System.nanoTime() < this.localAppearanceRetryAfter) {
            return;
        }

        String id = settings.modelId(), texture = localTextureSelection(id);
        UUID owner = this.client.player.getUuid();
        long token = ++this.localAppearanceRequest, epoch = this.generation;
        this.localAppearancePending = true;
        try {
            this.decoder.execute(() -> {
                try {
                    LocalModelLibrary.Loaded loaded = this.localModelLibrary.load(id,
                            texture.isEmpty() ? null : texture);
                    this.client.execute(() -> {
                        if (token != this.localAppearanceRequest || epoch != this.generation) {
                            return;
                        }

                        if (this.client.world == null || this.client.player == null
                                || !owner.equals(this.client.player.getUuid()) || !localAppearance().enabled()
                                || !canUseLocalAppearance()) {
                            this.localAppearancePending = false;
                            return;
                        }
                        install(loaded.hash(), loaded.model(),
                                () -> token == this.localAppearanceRequest && epoch == this.generation
                                        && this.client.world != null && this.client.player != null
                                        && owner.equals(this.client.player.getUuid()) && localAppearance().enabled()
                                        && canUseLocalAppearance(), success -> {
                            this.localAppearancePending = false;
                            if (!success) {
                                this.localAppearanceError = "本地模型纹理加载失败";
                                this.localAppearanceRetryAfter = System.nanoTime() + 30 * SECOND;
                                return;
                            }
                            this.localSelf = new Binding(owner, "local-self:" + token, id, loaded.hash());
                            rememberLocalProfile(id, loaded);
                            this.localSelf.profile = loaded.profile();
                            this.localSelf.motion = localMotionPolicy(loaded.model());
                            this.localSelf.hidePlayer = true;
                            this.localAppearanceError = "";
                            this.localAppearanceRetryAfter = 0;
                        });
                    });
                } catch (Exception failure) {
                    this.client.execute(() -> {
                        if (token != this.localAppearanceRequest || epoch != this.generation) {
                            return;
                        }

                        this.localAppearancePending = false;
                        this.localAppearanceError = "本地模型加载失败：" + failure.getMessage();
                        this.localAppearanceRetryAfter = System.nanoTime() + 30 * SECOND;
                        MEPlayerActionsClient.LOGGER.warn("Cannot load private local appearance {}: {}", id,
                                failure.toString());
                    });
                }
            });
        } catch (RejectedExecutionException busy) {
            this.localAppearancePending = false;
            this.localAppearanceError = "模型加载繁忙，请稍后";
            this.localAppearanceRetryAfter = System.nanoTime() + SECOND;
        }
    }

    private void updateLocalAppearanceMotion() {
        if (!localAppearancePrepared()) {
            return;
        }

        BbModel model = this.assets.get(this.localSelf.hash);
        if (!this.localSelf.localServerLayers.isEmpty()) {
            Layer manual = this.localSelf.localServerLayers.get(0);
            if (manual.loop().equals("ONCE") && this.localTick > manual.startedAtTick()
                    + model.animationLengthTicks(manual.animation()) + manual.outTicks()) {
                stopLocal();
            }

        }
        entityBinding(this.localSelf, model, this.client.player);
    }

    private RenderBinding localAppearanceBinding() {
        LocalAppearanceSettings settings = localAppearance();
        RenderBinding binding = entityBinding(this.localSelf, this.assets.get(this.localSelf.hash), this.client.player);
        return new RenderBinding(binding.owner(), binding.instance(), binding.assetHash(), binding.model(),
                binding.layers(), binding.serverTick(), binding.x() + settings.offsetX(),
                binding.y() + settings.offsetY(), binding.z() + settings.offsetZ(), binding.bodyYaw(),
                binding.headYaw(), binding.headPitch(), settings.scale(), true, "local-self");
    }

    private static LocalMotionPolicy localMotionPolicy(BbModel model) {
        return LocalMotionPolicy.forModel(model);
    }

    public List<Action> actions() {
        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        if (own != null) {
            return serverActions();
        }

        if (serverOwnModelPresent()) {
            return List.of();
        }

        BbModel preview = this.assets.get(this.previewHash);
        return preview == null ? List.of()
                : preview.animations().stream()
                        .filter(id -> !id.startsWith("parallel") && !id.startsWith("pre_parallel")).sorted()
                        .map(id -> new Action(id, previewLabel(id))).toList();
    }

    /** Server-supplied actions remain available without a local render lease or loaded model. */
    public List<Action> serverActions() {
        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        return canUseServerActions() && own != null ? own.actions : List.of();
    }

    private static String previewLabel(String id) {
        return AnimationLabels.DEFAULTS.getOrDefault(id, id);
    }

    public List<String> status() {
        long active = this.bindings.values().stream().filter(b -> b.active).count();
        List<String> text = new ArrayList<>();
        text.add("客户端 " + clientVersion() + " · " + (this.acknowledged ? "已连接动作服务器" : "等待服务器 / 本地预览") + " · 资产模式 "
                + (this.serverAssetMode.isEmpty() ? "未协商" : this.serverAssetMode));
        text.add("已接管 " + active + " / " + this.bindings.size() + " 个模型 · 资产 " + this.assets.size() + " · 模型加载 "
                + this.loading.size());
        text.add((this.options.followServerTimeline ? "原版实体位置 · 服务器动画缓冲 " + this.options.interpolationTicks + " tick"
                : "原版实体渲染位置 · 无额外位置缓冲") + " · 伪装模型 " + (this.options.showSelf ? "显示" : "隐藏") + " · 玩家隐藏设置 "
                + (ownPlayerHideSetting() ? "开启" : "关闭") + " · 装备隐藏设置 "
                + (serverOwnModelPresent() || this.options.hideVanillaEquipment ? "开启" : "关闭"));
        if (localAppearance().enabled()) {
            text.add("本地外观：" + localAppearance().modelId() + " · " + localAppearanceStatus());
        }

        text.add("本人私人分享：" + privateSyncStatus() + " · 分享选择 " + (this.options.privateSyncEnabled ? "开启" : "关闭"));
        List<PrivateModelSyncClient.ReceivedModel> receipts = receivedPrivateModels();
        text.add("观看私人分享："
                + (!this.options.enabled ? "客户端渲染已关闭" : (!this.privateModels.canView() ? "尚未获服务器观看许可" : "已开启接收"))
                + " · 接收记录 " + receipts.size() + " · "
                + (this.privateModels.viewerStateNegotiated() ? "服务器已协商观看开关" : "服务器未协商观看开关"));
        for (PrivateModelSyncClient.ReceivedModel model : receipts.stream().limit(8).toList()) {
            PlayerEntity player = this.client.world == null ? null : this.client.world.getPlayerByUuid(model.owner());
            String ownerName = player == null ? model.owner().toString() : player.getName().getString();
            text.add("分享来源：" + ownerName + " · " + (model.modelId().isEmpty() ? "模型 ID 未提供" : model.modelId())
                    + " · hash " + model.hash().substring(0, 12));
            text.add("资源：" + (model.cacheHit() ? "本次校验命中磁盘缓存" : "本次未命中磁盘缓存") + " · " + model.receivedBytes() + " / "
                    + model.bytes() + " 字节 · " + privateViewingStatus(model));
        }
        if (receipts.size() > 8) {
            text.add("其余接收记录请查看图库的私人分享缓存或诊断文件");
        }

        if (this.client.player != null && localAppearance().enabled() && localModelProfile().isYsm()) {
            List<Map<String, Object>> fallbacks = ModelRenderer.queryDiagnostics(this.client.player.getUuid());
            for (Map<String, Object> fallback : fallbacks) {
                String reason = switch (String.valueOf(fallback.get("reason"))) {
                case "missing_query" -> "未接入或未绑定";
                case "unavailable_query" -> "当前查询不可用";
                default -> String.valueOf(fallback.get("reason"));
                };
                String actual = String.valueOf(fallback.get("actualFallback"));
                if (actual.length() > 96) {
                    actual = actual.substring(0, 96) + "…";
                }

                text.add("YSM 查询降级：" + fallback.get("name") + " · " + reason + " · " + fallback.get("count")
                        + " 次 · 实际默认 " + actual);
            }
        }
        if (!this.previewId.isEmpty()) {
            text.add("本地预览：" + this.previewId);
        }

        Binding own = this.client.player == null ? null : this.bindings.get(this.client.player.getUuid());
        if (own != null) {
            text.add("模型：" + own.modelId + " · hash " + (own.hash.isEmpty() ? "无" : own.hash.substring(0, 12)));
            text.addAll(serverModelPresentation(own.modelId).lines().stream().map(ServerModelPresentation.Line::text)
                    .toList());
            text.add("动画：" + (this.options.followServerTimeline ? own.layers : own.localMotion.layers()).stream()
                    .map(l -> l.layer() + "=" + l.animation()).reduce((a, b) -> a + " / " + b).orElse("基础姿态"));
        }
        if (!this.lastError.isEmpty()) {
            text.add(this.lastError);
        }

        return List.copyOf(text);
    }

    public void preview(String id) {
        if (id.equals("off")) {
            this.previewRequest++;
            this.previewPending = false;
            this.previewId = "";
            this.previewHash = "";
            this.previewManual = "";
            return;
        }
        if ((!LocalAppearanceSettings.isValidModelId(id) && !Set.of("ysm_02_jk", "ysm_01_jk").contains(id))
                || this.client.world == null || this.client.player == null) {
            notify("进入世界后可预览本地模型；服务器模型需要完整资源包");
            return;
        }
        if (!canUseLocalActions()) {
            notify(localAppearanceSuspensionReason());
            return;
        }
        if (this.previewPending || this.loading.size() >= 4) {
            notify("模型正在加载，请稍后");
            return;
        }
        long token = ++this.previewRequest;
        this.previewPending = true;
        long epoch = this.generation;
        this.decoder.execute(() -> {
            try {
                boolean packSource = !LocalAppearanceSettings.isValidModelId(id);
                LocalModelLibrary.Loaded loaded = packSource ? loadPackModel(id, null)
                        : this.localModelLibrary.load(id, this.options.defaultBlueTexture);
                BbModel model = loaded.model();
                String hash = loaded.hash();
                this.client.execute(() -> {
                    if (epoch == this.generation && token == this.previewRequest && canUseLocalActions()) {
                        if (packSource) {
                            this.packAssets.add(hash);
                        }
                        install(hash, model, () -> epoch == this.generation && token == this.previewRequest
                                && canUseLocalActions() && this.client.world != null, success -> {
                            this.previewPending = false;
                            if (success) {
                                this.assetMetadata.put(hash,
                                        new BbModelAsset.Decoded(model, loaded.previewAnimation(), loaded.profile()));
                                this.previewId = id;
                                this.previewHash = hash;
                                this.previewManual = "";
                                this.previewStarted = this.client.world.getTime();
                                notify("本地模型预览已开启，请切换第三人称；J 打开动作轮盘");
                            }
                        });
                    }
                });
            } catch (Exception exception) {
                this.client.execute(() -> {
                    if (epoch == this.generation && token == this.previewRequest) {
                        this.previewPending = false;
                        notify("预览失败：" + exception.getMessage());
                    }
                });
            }
        });
    }

    private RenderBinding previewBinding() {
        ClientPlayerEntity player = this.client.player;
        BbModel model = this.assets.get(this.previewHash);
        long tick = this.client.world.getTime();
        double renderTick = tick + this.client.getRenderTickCounter().getTickProgress(false);
        String pose;
        boolean moving = player.getVelocity().horizontalLengthSquared() > 0.0004;
        if (player.hasVehicle()) {
            pose = "sit";
        }

        else if (player.isSleeping()) {
            pose = model.animations().contains("bed_sleep") ? "bed_sleep" : "sleep";
        }

        else if (player.getPose() == EntityPose.SWIMMING) {
            pose = player.isTouchingWater() ? "swim" : (moving ? "crawl_walk" : "crawl_idle");
        }

        else if (player.getAbilities().flying) {
            pose = "fly";
        }

        else if (player.isSneaking()) {
            pose = moving ? "crouch_walk" : "crouch_idle";
        }

        else {
            if (this.previewWasGround && !player.isOnGround() && player.getVelocity().y > 0) {
                this.previewJumpUntil = tick + 17;
            }

            pose = tick < this.previewJumpUntil ? "player_jump"
                    : (!player.isOnGround() ? "jump" : (moving ? (player.isSprinting() ? "run" : "walk") : "idle"));
        }
        this.previewWasGround = player.isOnGround();
        if (!model.animations().contains(pose)) {
            pose = "idle";
        }

        if (!pose.equals(this.previewPose)) {
            this.previewPose = pose;
            this.previewStarted = tick;
        }
        List<Layer> layers = new ArrayList<>();
        layers.add(new Layer("posture", pose, this.previewStarted, 1, "LOOP", 2, 2));
        if (!this.previewManual.isEmpty()) {
            layers.add(new Layer("manual", this.previewManual, this.previewManualStarted, 1, "ONCE", 2, 2));
        }

        NativePlayerPresentation.Snapshot presentation = NativePlayerPresentation.frame(player);
        Vec3d pos = new Vec3d(presentation.x(), presentation.y(), presentation.z());
        float bodyYaw = presentation.bodyYaw(), headYaw = presentation.headYaw(), headPitch = presentation.headPitch();
        return new RenderBinding(player.getUuid(), "preview", this.previewHash, model, List.copyOf(layers), renderTick,
                pos.x, pos.y, pos.z, bodyYaw, headYaw, headPitch, 1, true, "preview");
    }

    private void notify(String message) {
        if (this.client.player != null) {
            this.client.player.sendMessage(Text.literal("[动作客户端] " + message), false);
        }
    }

    private static final class Binding {
        final UUID owner;
        final String instance, modelId, hash;
        final TransformTimeline timeline = new TransformTimeline();
        final SnapshotTimeline<List<Layer>> layerTimeline = new SnapshotTimeline<>();
        final EntityAnimationController localMotion = new EntityAnimationController();
        final LocalLayerClock localClock = new LocalLayerClock();
        LocalMotionPolicy motion;
        List<Layer> localServerLayers = List.of();
        YsmModelProfile profile = YsmModelProfile.empty();
        long sequence = -1, lastPacket, lastReady;
        boolean readySent, active, hidePlayer, unsupported, showSelf = true;
        float scale = 1;
        Boolean sourceInvisible;
        int foodLevel = 20;
        String assetState = "等待服务器模型", assetError = "", serverAssetStatus = "", serverAssetReason = "",
                serverAssetSource = "";
        Map<String, Double> accessories = Map.of();
        List<Layer> layers = List.of();
        List<Action> actions = List.of();

        Binding(UUID owner, String instance, String modelId, String hash) {
            this.owner = owner;
            this.instance = instance;
            this.modelId = modelId;
            this.hash = hash;
        }
    }
}
