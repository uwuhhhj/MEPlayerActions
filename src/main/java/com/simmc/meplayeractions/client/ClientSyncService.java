package com.simmc.meplayeractions.client;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.simmc.meplayeractions.config.PerformanceSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import com.simmc.meplayeractions.client.DeferredClientPackets.Result;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.regex.Pattern;

/** Verified local-render takeover with per-viewer fallback and bounded asset transfers. */
public final class ClientSyncService implements PluginMessageListener, AutoCloseable {
    public static final String CHANNEL = "meplayeractions:main";
    public static final int PROTOCOL = 3;
    private static final int HEARTBEAT_TICKS = 20;
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Pattern ANIMATION_NAME = Pattern.compile("[a-zA-Z0-9_.:/-]{1,128}");
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9_-]{1,64}"), HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> ACTIONS = Set.of("play", "stop", "sit", "crawl", "reset");
    private static final Set<String> CAPABILITIES = Set.of("local_render", "resource_pack_models", "server_push_models", "incremental_state", "server_timeline", ServerModelCatalog.CAPABILITY);
    private final Plugin plugin;
    private final ClientSnapshotCache snapshotCache;
    private final Consumer<ActionRequest> actionRequests;
    private final ModelAssets assets;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Set<UUID>> viewersByOwner = new HashMap<>();
    private final Map<UUID, StatePacket> statePackets = new HashMap<>();
    private final Map<UUID, StateMetadata> stateMetadata = new HashMap<>();
    private final ConnectionLimits limits = new ConnectionLimits();
    private final PrivateModelSyncService privateModels;
    private ServerModelCatalog modelCatalog = new ServerModelCatalog(List::of);
    private boolean configuredEnabled = true, running;
    private int maxPayload = 16000, requestCooldownTicks = 4;
    private double viewDistanceBlocks = 64;
    private long packetTick = -1;
    private int audienceRefreshTicks = 40, validationTicks = 20, viewerRotation;
    private BiPredicate<Player, UUID> audience = (viewer, owner) -> true;
    private RenderControl renderControl = (viewer, owner, instance, enabled) -> !enabled;
    private BukkitTask maintenanceTask;
    public ClientSyncService(JavaPlugin plugin, Supplier<List<StateSnapshot>> snapshots, Consumer<ActionRequest> requests) {
        this(plugin, snapshots, requests, new ModelAssets(plugin));
    }
    ClientSyncService(Plugin plugin, Supplier<List<StateSnapshot>> snapshots, Consumer<ActionRequest> requests, ModelAssets assets) {
        this.plugin = Objects.requireNonNull(plugin); snapshotCache = new ClientSnapshotCache(snapshots);
        actionRequests = Objects.requireNonNull(requests); this.assets = Objects.requireNonNull(assets);
        privateModels = new PrivateModelSyncService(plugin, this::readOwnerIds, limits);
    }
    public void modelCatalog(Supplier<List<String>> choices) { modelCatalog = new ServerModelCatalog(choices); }
    public void snapshotSources(Supplier<Set<UUID>> ownerIds, Function<UUID, StateSnapshot> snapshot) { snapshotCache.configure(ownerIds, snapshot); }
    public void configurePerformance(PerformanceSettings performance) {
        Objects.requireNonNull(performance); audienceRefreshTicks = performance.audienceRefreshTicks(); validationTicks = performance.validationTicks();
        privateModels.configurePerformance(performance);
    }
    public void configure(boolean enabled, int limit, int cooldown, double distance) {
        if (!enabled && configuredEnabled) clearSessions("sync_disabled"); configuredEnabled = enabled;
        maxPayload = Math.max(1024, Math.min(32766, limit)); requestCooldownTicks = Math.max(0, Math.min(1200, cooldown));
        viewDistanceBlocks = Double.isFinite(distance) ? Math.max(1, Math.min(1024, distance)) : 64;
        statePackets.clear(); stateMetadata.clear(); packetTick = -1;
    }
    public void audience(BiPredicate<Player, UUID> value) { audience = Objects.requireNonNull(value); }
    public void configurePrivateModels(PrivateModelSyncService.Policy policy) { privateModels.configure(policy); }
    public void rendering(RenderControl value) { renderControl = Objects.requireNonNull(value); }
    public void enable() {
        if (running) return; var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        try {
            messenger.registerIncomingPluginChannel(plugin, CHANNEL, this); running = true;
            maintenanceTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::maintainSessions, 1L, 1L);
            privateModels.enable();
        } catch (RuntimeException exception) {
            running = false; messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this);
            messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL); throw exception;
        }
    }
    @Override public void close() {
        privateModels.close();
        if (maintenanceTask != null) { maintenanceTask.cancel(); maintenanceTask = null; }
        clearSessions("plugin_stopping"); assets.invalidate(); running = false; var messenger = plugin.getServer().getMessenger();
        try { messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this); }
        finally { messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL); }
    }
    public void broadcast(StateSnapshot snapshot) {
        if (!running || !configuredEnabled) return;
        snapshotCache.supplied(snapshot, currentTick()); statePackets.remove(snapshot.owner()); stateMetadata.remove(snapshot.owner());
        Set<UUID> targets = new HashSet<>(viewersByOwner.getOrDefault(snapshot.owner(), Set.of()));
        if (sessions.containsKey(snapshot.owner())) targets.add(snapshot.owner());
        for (UUID id : targets) {
            Session session = sessions.get(id); if (session == null) continue;
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null || !viewer.isOnline()) { endSession(id, viewer, session, "offline"); limits.forget(id); }
            else if (canObserve(viewer, snapshot.owner())) sendState(viewer, session, snapshot, false);
            else removeBinding(viewer, session, snapshot.owner(), "out_of_range");
        }
    }
    public void unbind(UUID owner, UUID instance, String reason) {
        statePackets.remove(owner); stateMetadata.remove(owner);
        for (UUID id : List.copyOf(viewersByOwner.getOrDefault(owner, Set.of()))) {
            Session session = sessions.get(id); if (session == null) continue;
            var bound = session.bindings.get(owner);
            if (bound != null && bound.instance().equals(instance)) removeBinding(Bukkit.getPlayer(id), session, owner, reason);
        }
    }
    /** Teleports/world changes revoke only existing relations; discovery remains background work. */
    public void observationChanged(Player player) {
        Session own = sessions.get(player.getUniqueId()); if (own != null) validateBindings(player, own);
        for (UUID id : List.copyOf(viewersByOwner.getOrDefault(player.getUniqueId(), Set.of()))) {
            Session session = sessions.get(id); Player viewer = Bukkit.getPlayer(id);
            if (session != null && (viewer == null || !viewer.isOnline() || !canObserve(viewer, player.getUniqueId())))
                removeBinding(viewer, session, player.getUniqueId(), "observation_changed");
        }
        privateModels.observationChanged(player);
    }
    public void forget(Player player) {
        privateModels.forget(player);
        Session session = sessions.get(player.getUniqueId());
        if (session != null) endSession(player.getUniqueId(), player, session, "session_ended"); limits.forget(player.getUniqueId());
    }
    public void sendSnapshot(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (!running || !configuredEnabled || session == null || !player.isOnline()) return;
        long id = ++session.snapshotId; JsonObject begin = envelope("snapshot_begin");
        begin.addProperty("snapshotId", id); begin.addProperty("serverTick", currentTick());
        critical(player, session, "snapshot_begin", begin, () -> {
            reconcile(player, session, true); session.lastDiscoveryTick = ClientSyncCadence.initialTick(session.viewer, currentTick(), audienceRefreshTicks);
            JsonObject end = envelope("snapshot_end"); end.addProperty("snapshotId", id);
            critical(player, session, "snapshot_end", end, () -> {}, () -> {}, () -> true);
        }, () -> {}, () -> sessions.get(session.viewer) == session);
    }
    public String status(Player player) {
        String diagnostic = Optional.ofNullable(readSnapshot(player.getUniqueId())).map(snapshot -> {
                    ModelAssets.Status asset = assets.status(snapshot.modelId());
                    return "；本人资产 " + snapshot.modelId() + "=" + (snapshot.localRenderable() ? asset.state() : "server-only")
                            + "；来源 " + asset.source() + "；原因 " + (snapshot.localRenderable() ? asset.reason() : "当前伪装不允许客户端接管");
                }).orElse("") + "；" + privateModels.status(player) + "；服务器可选目录 " + modelCatalog.status();
        if (!running || !configuredEnabled) return "disabled（服务器渲染）" + diagnostic;
        Session session = sessions.get(player.getUniqueId());
        return (session == null ? "未握手（服务器渲染）" : "local-render v3；客户端 " + session.clientVersion
                + "；资产模式 " + session.assetMode() + "；可见实例 " + session.bindings.size()
                + "；已确认本地渲染 " + session.leases.size()) + diagnostic;
    }
    @Override public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (!CHANNEL.equals(channel) || !running || !configuredEnabled || bytes == null || bytes.length == 0 || bytes.length > maxPayload) return;
        if (!Bukkit.isPrimaryThread()) {
            byte[] copy = bytes.clone(); if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin,
                    () -> onPluginMessageReceived(channel, player, copy)); return;
        }
        if (!player.isOnline() || !limits.allowInbound(player.getUniqueId(), bytes.length, System.nanoTime())) return;
        Inbound inbound;
        try { inbound = decode(bytes); }
        catch (IOException | IllegalArgumentException | IllegalStateException exception) { sendError(player, "invalid_payload"); return; }
        if (inbound.protocol() != PROTOCOL) {
            sendError(player, "unsupported_protocol"); Session previous = sessions.get(player.getUniqueId());
            if (previous != null) endSession(player.getUniqueId(), player, previous, "unsupported_protocol"); return;
        }
        if (inbound.type().equals("hello")) {
            long now = System.nanoTime(); Session previous = sessions.get(player.getUniqueId());
            if (!limits.allowHello(player.getUniqueId(), now)) return;
            if (previous != null) endSession(player.getUniqueId(), player, previous, "new_handshake");
            Session session = new Session(player.getUniqueId(), inbound.packModels(), inbound.pushModels(), inbound.incremental(), inbound.timeline(), inbound.catalog(), currentTick(), audienceRefreshTicks, validationTicks); session.clientVersion = inbound.clientVersion();
            sessions.put(player.getUniqueId(), session); JsonObject ack = envelope("hello_ack");
            ack.addProperty("mode", "local-render"); ack.addProperty("serverTick", currentTick());
            ack.addProperty("assetMode", session.assetMode());
            ack.addProperty("heartbeatTicks", HEARTBEAT_TICKS); ack.addProperty("leaseTicks", RenderLeases.LEASE_TICKS);
            ack.addProperty("maxPayload", maxPayload); ack.addProperty("requestCooldownTicks", requestCooldownTicks);
            JsonArray capabilities = new JsonArray(); capabilities.add("local_render");
            if (session.pushModels) capabilities.add("server_push_models");
            else if (session.packModels) capabilities.add("resource_pack_models"); ack.add("capabilities", capabilities);
            if (session.incremental) capabilities.add("incremental_state");
            if (session.timeline) capabilities.add("server_timeline");
            if (session.catalog) capabilities.add(ServerModelCatalog.CAPABILITY);
            critical(player, session, "hello_ack", ack, () -> {
                session.helloAcknowledged = true; updateCatalog(player, session); pumpCatalog(player, session); sendSnapshot(player);
            }, () -> {}, () -> sessions.get(session.viewer) == session); return;
        }
        Session session = sessions.get(player.getUniqueId()); if (session == null) return; long tick = currentTick();
        switch (inbound.type()) {
            case "asset_request" -> requestAsset(player, session, inbound, tick);
            case "asset_status" -> assetStatus(player, session, inbound, tick);
            case "render_ready" -> ready(player, session, inbound.binding(), tick);
            case "render_failed" -> {
                PendingReady pending = session.pendingReady.get(inbound.binding().owner());
                if (session.leases.contains(inbound.binding()) || pending != null && pending.binding().equals(inbound.binding())) {
                    removeBinding(player, session, inbound.binding().owner(), "render_failed");
                }
            }
            case "render_heartbeat" -> session.leases.renew(inbound.bindings(), tick);
            case "snapshot_request", "request" -> {
                if (!limits.allowRequest(player.getUniqueId(), inbound.type(), inbound.action(), tick, requestCooldownTicks)) { sendError(player, "request_cooldown"); return; }
                if (inbound.type().equals("snapshot_request")) sendSnapshot(player);
                else try { actionRequests.accept(new ActionRequest(player, inbound.action(), inbound.argument())); }
                catch (RuntimeException exception) { plugin.getLogger().log(Level.WARNING, "Client action failed", exception); sendError(player, "action_failed"); }
            }
            default -> throw new IllegalStateException("Unsupported decoded message");
        }
    }
    private void ready(Player viewer, Session session, RenderLeases.Binding binding, long tick) {
        BoundState bound = session.bindings.get(binding.owner());
        StateSnapshot current = readSnapshot(binding.owner());
        if (bound == null || current == null || !current.localRenderable() || !current.instance().equals(binding.instance())
                || !bound.instance().equals(binding.instance()) || !bound.hash().equals(binding.hash()) || !canObserve(viewer, binding.owner())
                || assets.get(current.modelId()).filter(asset -> asset.hash().equals(binding.hash())).isEmpty()) {
            PendingReady pending = session.pendingReady.get(binding.owner());
            if (pending != null && pending.binding().equals(binding)) { session.pendingReady.remove(binding.owner()); restore(session.viewer, binding); }
            sendError(viewer, "render_not_authorized", binding); return;
        }
        JsonObject ack = envelope("render_ack"); addBinding(ack, binding); byte[] ackBytes = encode(ack);
        Result reserved = reserveBytes(viewer, ackBytes, false);
        if (reserved == Result.DEFERRED) { session.pendingReady.putIfAbsent(binding.owner(), new PendingReady(binding, tick)); return; }
        if (reserved == Result.FAILED) { failReady(session, binding); return; }
        try {
            if (!renderControl.set(viewer.getUniqueId(), binding.owner(), binding.instance(), true)) {
                if (renderControl.pending(viewer.getUniqueId(), binding.owner(), binding.instance())) {
                    session.pendingReady.putIfAbsent(binding.owner(), new PendingReady(binding, tick)); return;
                }
                failReady(session, binding); sendError(viewer, "render_unavailable", binding); return;
            }
        } catch (RuntimeException exception) {
            failReady(session, binding);
            plugin.getLogger().log(Level.WARNING, "Cannot enable client rendering", exception);
            sendError(viewer, "render_unavailable", binding); return;
        }
        session.pendingReady.remove(binding.owner());
        session.leases.ready(binding, tick);
        if (sendReservedBytes(viewer, ackBytes) == Result.FAILED) { failReady(session, binding); return; }
        if (session.pushModels) {
            session.offers.ready(binding.hash()); limits.pushRenderReady(viewer.getUniqueId(), binding.hash()); removeOfferTransfers(session, binding.hash());
        }
    }
    private void failReady(Session session, RenderLeases.Binding binding) {
        session.pendingReady.remove(binding.owner());
        if (session.leases.contains(binding)) session.leases.remove(binding.owner());
        restore(session.viewer, binding);
    }
    private void assetStatus(Player viewer, Session session, Inbound inbound, long tick) {
        if (!session.pushModels) { sendError(viewer, "asset_status_wrong_mode"); return; }
        PushAssetOffers.Offer offer = session.offers.find(inbound.hash());
        if (offer == null || !offer.id().equals(inbound.offerId()) || !authorizedHash(viewer, session, inbound.hash())) return;
        PushAssetOffers.Feedback feedback = session.offers.feedback(inbound.offerId(), inbound.hash(), inbound.assetStatus(), tick);
        if (feedback == PushAssetOffers.Feedback.REJECTED) cancelOffer(viewer, session, offer, "asset_rejected");
        // Late/repeated feedback is harmless, including cached that arrived after an already verified ready.
        // cached is not a render lease. missing is queued by the server maintenance pump.
    }
    private boolean authorizedHash(Player viewer, Session session, String hash) {
        if (hash.isEmpty()) return false;
        for (var entry : session.bindings.entrySet()) if (entry.getValue().hash().equals(hash)) {
            StateSnapshot current = readSnapshot(entry.getKey()); BoundState bound = entry.getValue();
            if (current != null && current.localRenderable() && current.instance().equals(bound.instance())
                    && current.modelId().equals(bound.modelId()) && canObserve(viewer, entry.getKey())
                    && assets.get(current.modelId()).filter(asset -> asset.hash().equals(hash)).isPresent()) return true;
        }
        return false;
    }
    private Set<String> authorizedHashes(Player viewer, Session session) {
        Set<String> result = new HashSet<>();
        if (viewer == null) return result;
        for (var entry : session.bindings.entrySet()) {
            BoundState bound = entry.getValue(); StateSnapshot snapshot = readSnapshot(entry.getKey());
            if (snapshot != null && snapshot.localRenderable() && !bound.hash().isEmpty() && bound.instance().equals(snapshot.instance())
                    && bound.modelId().equals(snapshot.modelId()) && canObserve(viewer, snapshot.owner())) result.add(bound.hash());
        }
        return result;
    }
    private void offerAsset(Player viewer, Session session, UUID owner, BoundState bound, long tick) {
        if (!session.pushModels || bound.hash().isEmpty() || !session.offers.canIssue(bound.hash())) return;
        if (session.bindings.entrySet().stream().anyMatch(entry -> entry.getValue().hash().equals(bound.hash())
                && session.leases.contains(new RenderLeases.Binding(entry.getKey(), entry.getValue().instance(), bound.hash())))) return;
        Set<UUID> instances = new HashSet<>();
        // Already authorized aliases share attempt history, without scanning every disguise for each viewer.
        for (var entry : session.bindings.entrySet()) if (entry.getValue().hash().equals(bound.hash()) && canObserve(viewer, entry.getKey()))
            instances.add(entry.getValue().instance());
        if (!instances.contains(bound.instance()) || !limits.canPushOffer(session.viewer, bound.hash(), instances, tick)) return;
        PushAssetOffers.Offer offer = session.offers.issue(owner, bound.instance(), bound.modelId(), bound.hash(), tick);
        if (offer == null) return;
        JsonObject packet = envelope("asset_offer"); packet.addProperty("owner", owner.toString());
        packet.addProperty("instance", bound.instance().toString()); packet.addProperty("modelId", bound.modelId());
        addOffer(packet, offer);
        if (sendPacket(viewer, packet, false) == Result.SENT)
            limits.pushOfferSent(session.viewer, bound.hash(), instances, tick);
        else session.offers.remove(bound.hash());
    }
    private void maintainOffers(Player viewer, Session session, long tick, Set<String> authorized) {
        if (!session.pushModels) return;
        expireOffers(viewer, session, tick, authorized);
        for (PushAssetOffers.Offer offer : session.offers.missing()) {
            if (!authorized.contains(offer.hash())) { cancelOffer(viewer, session, offer, "asset_not_authorized"); continue; }
            var asset = assets.get(offer.modelId()).filter(value -> value.hash().equals(offer.hash()));
            if (asset.isEmpty()) {
                for (BoundState bound : session.bindings.values()) if (bound.hash().equals(offer.hash())) {
                    asset = assets.get(bound.modelId()).filter(value -> value.hash().equals(offer.hash()));
                    if (asset.isPresent()) break;
                }
            }
            if (asset.isEmpty()) { cancelOffer(viewer, session, offer, "asset_unavailable"); continue; }
            if (!limits.reserveTransfer(session.viewer)) continue;
            if (!limits.allowAsset(session.viewer, offer.hash(), tick)) { limits.releaseTransfer(session.viewer); continue; }
            if (!session.offers.start(offer)) { limits.releaseTransfer(session.viewer); continue; }
            session.transfers.add(new Transfer(asset.get(), Math.min(9000, (maxPayload - 512) * 3 / 4), offer));
        }
    }
    private void cancelUnauthorizedOffers(Player viewer, Session session, long tick) {
        expireOffers(viewer, session, tick, authorizedHashes(viewer, session));
    }
    private void expireOffers(Player viewer, Session session, long tick, Set<String> authorized) {
        for (PushAssetOffers.Cancelled cancelled : session.offers.expire(tick, offer -> authorized.contains(offer.hash())))
            cancelOffer(viewer, session, cancelled.offer(), cancelled.reason());
    }
    private void cancelOffer(Player viewer, Session session, PushAssetOffers.Offer offer, String reason) {
        PushAssetOffers.Offer current = session.offers.find(offer.hash());
        if (current != null && current.id().equals(offer.id())) session.offers.remove(offer.hash());
        removeOfferTransfers(session, offer.hash());
        JsonObject packet = envelope("asset_cancel"); addOffer(packet, offer); packet.addProperty("reason", reason); send(viewer, packet);
    }
    private void removeOfferTransfers(Session session, String hash) {
        Iterator<Transfer> iterator = session.transfers.iterator();
        while (iterator.hasNext()) {
            Transfer transfer = iterator.next();
            if (transfer.offer != null && transfer.offer.hash().equals(hash)) { iterator.remove(); limits.releaseTransfer(session.viewer); }
        }
    }
    private static void addOffer(JsonObject packet, PushAssetOffers.Offer offer) {
        packet.addProperty("offerId", offer.id().toString()); packet.addProperty("hash", offer.hash());
    }
    private void requestAsset(Player viewer, Session session, Inbound inbound, long tick) {
        if (session.pushModels) { sendError(viewer, "asset_server_push_mode"); return; }
        if (session.packModels) { sendError(viewer, "asset_resource_pack_mode"); return; }
        if (session.bindings.entrySet().stream().noneMatch(e -> e.getValue().modelId().equals(inbound.modelId())
                && e.getValue().hash().equals(inbound.hash()) && canObserve(viewer, e.getKey()))) {
            sendError(viewer, "asset_not_authorized"); return;
        }
        if (session.transfers.stream().anyMatch(t -> t.asset.hash().equals(inbound.hash()))) return;
        var asset = assets.get(inbound.modelId()).filter(a -> a.hash().equals(inbound.hash()));
        if (asset.isEmpty()) { sendError(viewer, "asset_unavailable"); return; }
        if (!limits.reserveTransfer(viewer.getUniqueId())) { sendError(viewer, "asset_queue_full"); return; }
        if (!limits.allowAsset(viewer.getUniqueId(), inbound.hash(), tick)) {
            limits.releaseTransfer(viewer.getUniqueId()); sendError(viewer, "asset_cooldown"); return;
        }
        session.transfers.add(new Transfer(asset.get(), Math.min(9000, (maxPayload - 512) * 3 / 4)));
    }
    private void maintainSessions() {
        if (!configuredEnabled || sessions.isEmpty()) return; long tick = currentTick();
        if (tick % HEARTBEAT_TICKS == 0) limits.pruneOffline(id -> { Player viewer = Bukkit.getPlayer(id); return viewer != null && viewer.isOnline(); });
        List<Session> ordered = new ArrayList<>(sessions.values());
        Collections.rotate(ordered, -(viewerRotation++ % ordered.size()));
        for (Session session : ordered) {
            Player viewer = Bukkit.getPlayer(session.viewer);
            if (viewer == null || !viewer.isOnline()) { endSession(session.viewer, viewer, session, "offline"); continue; }
            session.controls.drain(payload -> sendBytes(viewer, payload, false), tick, 4);
            maintainReady(viewer, session, tick);
            retryStates(viewer, session);
            if (ClientSyncCadence.due(tick, session.lastDiscoveryTick, audienceRefreshTicks)) {
                reconcile(viewer, session, false); updateCatalog(viewer, session); session.lastDiscoveryTick = tick;
            }
            if (ClientSyncCadence.due(tick, session.lastValidationTick, validationTicks)) {
                validateBindings(viewer, session); session.lastValidationTick = tick;
                for (var expired : session.leases.expired(tick)) { restore(session.viewer, expired); removeBinding(viewer, session, expired.owner(), "render_lease_expired"); }
                for (UUID owner : List.copyOf(session.bindings.keySet())) {
                    StateSnapshot state = readSnapshot(owner); if (state != null) sendState(viewer, session, state, false);
                }
                session.authorized = session.pushModels ? authorizedHashes(viewer, session) : Set.of();
            }
            if (!session.incremental && ClientSyncCadence.due(tick, session.lastLegacyTick, ClientSyncCadence.LEGACY_STATE_TICKS)
                    || session.timeline && tick % ClientSyncCadence.TIMELINE_TICKS == 0) {
                for (UUID owner : List.copyOf(session.bindings.keySet())) {
                    StateSnapshot state = readSnapshot(owner);
                    if (state != null && canObserve(viewer, owner)) sendState(viewer, session, state, true);
                    else removeBinding(viewer, session, owner, "not_visible_or_active");
                }
                if (!session.incremental) session.lastLegacyTick = tick;
            }
            pumpCatalog(viewer, session);
            maintainOffers(viewer, session, tick, session.authorized); pumpAssets(viewer, session, session.authorized);
            if (ClientSyncCadence.due(tick, session.lastHeartbeatTick, HEARTBEAT_TICKS)) {
                heartbeat(viewer, session, tick); session.lastHeartbeatTick = tick;
            }
        }
    }
    private boolean catalogPermitted(Player viewer) {
        return viewer.hasPermission("mact.use") && viewer.hasPermission("mact.disguise");
    }
    private void updateCatalog(Player viewer, Session session) {
        if (!session.catalog || !session.helloAcknowledged) return;
        modelCatalog.refresh(currentTick());
        ServerModelCatalog.Plan plan = modelCatalog.plan(catalogPermitted(viewer), maxPayload);
        if (session.catalogPlan == plan) return;
        session.catalogPlan = plan; session.catalogIndex = 0; session.catalogRevision++;
        session.pendingCatalogBytes = null;
    }
    private void pumpCatalog(Player viewer, Session session) {
        if (!session.catalog || !session.helloAcknowledged || session.catalogPlan == null
                || session.catalogIndex >= session.catalogPlan.count()) return;
        // A permission revocation cancels a partially sent directory before another chunk leaves.
        if (session.catalogPlan.canDisguise != catalogPermitted(viewer)) updateCatalog(viewer, session);
        long tick = currentTick();
        if (session.catalogIndex >= session.catalogPlan.count() || session.lastCatalogSentTick == tick) return;
        if (session.pendingCatalogBytes == null)
            session.pendingCatalogBytes = session.catalogPlan.packet(session.catalogIndex, session.catalogRevision);
        Result result = sendBytes(viewer, session.pendingCatalogBytes, false);
        if (result == Result.SENT) { session.catalogIndex++; session.pendingCatalogBytes = null; session.lastCatalogSentTick = tick; }
    }
    private void validateBindings(Player viewer, Session session) {
        for (var entry : List.copyOf(session.bindings.entrySet())) {
            StateSnapshot current = readSnapshot(entry.getKey()); BoundState bound = entry.getValue();
            if (current == null || !current.instance().equals(bound.instance()) || !current.modelId().equals(bound.modelId()) || !canObserve(viewer, entry.getKey()))
                removeBinding(viewer, session, entry.getKey(), "not_visible_or_active");
        }
    }
    private void maintainReady(Player viewer, Session session, long tick) {
        for (var pending : List.copyOf(session.pendingReady.values())) {
            if (!matches(session.bindings.get(pending.binding().owner()), pending.binding()) || !canObserve(viewer, pending.binding().owner())) {
                failReady(session, pending.binding());
            } else if (tickDistance(tick, pending.since()) >= RenderLeases.LEASE_TICKS) {
                session.pendingReady.remove(pending.binding().owner());
                // A duplicate ready delayed by budget cannot revoke an already acknowledged, renewing lease.
                if (!session.leases.contains(pending.binding())) restore(session.viewer, pending.binding());
            } else ready(viewer, session, pending.binding(), tick);
        }
    }
    private void heartbeat(Player viewer, Session session, long tick) {
        if (!session.incremental) { send(viewer, heartbeatPacket(tick, null)); return; }
        List<RenderLeases.Binding> bindings = new ArrayList<>();
        for (var entry : session.bindings.entrySet()) {
            if (liveBinding(viewer, entry.getKey(), entry.getValue()))
                bindings.add(new RenderLeases.Binding(entry.getKey(), entry.getValue().instance(), entry.getValue().hash()));
        }
        for (byte[] packet : heartbeatPackets(tick, bindings, maxPayload)) sendBytes(viewer, packet, false);
    }
    private boolean liveBinding(Player viewer, UUID owner, BoundState bound) {
        if (!canObserve(viewer, owner)) return false;
        StateSnapshot current = readSnapshot(owner);
        if (current == null || !current.instance().equals(bound.instance()) || !current.modelId().equals(bound.modelId())) return false;
        String hash = current.localRenderable() ? assets.get(current.modelId()).map(ModelAssets.Asset::hash).orElse("") : "";
        return hash.equals(bound.hash());
    }
    static List<byte[]> heartbeatPackets(long tick, List<RenderLeases.Binding> bindings, int maxPayload) {
        List<byte[]> packets = new ArrayList<>(); JsonArray identities = new JsonArray();
        int baseBytes = payloadSize(heartbeatPacket(tick, identities)), size = baseBytes;
        for (RenderLeases.Binding binding : bindings) {
            JsonObject identity = new JsonObject(); addBinding(identity, binding);
            int itemBytes = payloadSize(identity);
            if (identities.size() == 64 || size + itemBytes + (identities.isEmpty() ? 0 : 1) > maxPayload) {
                packets.add(encode(heartbeatPacket(tick, identities))); identities = new JsonArray(); size = baseBytes;
            }
            identities.add(identity); size += itemBytes + (identities.size() == 1 ? 0 : 1);
        }
        packets.add(encode(heartbeatPacket(tick, identities))); return List.copyOf(packets);
    }
    static JsonObject heartbeatPacket(long tick, JsonArray identities) {
        JsonObject message = envelope("heartbeat"); message.addProperty("serverTick", tick);
        message.addProperty("leaseTicks", RenderLeases.LEASE_TICKS);
        if (identities != null) message.add("bindings", identities); return message;
    }
    private void pumpAssets(Player viewer, Session session, Set<String> authorized) {
        int sentChunks = 0;
        while (sentChunks < 2 && !session.transfers.isEmpty()) {
            Transfer transfer = session.transfers.peek();
            if (transfer.offer != null && (!session.offers.transferring(transfer.offer) || !authorized.contains(transfer.asset.hash())
                    || !authorizedHash(viewer, session, transfer.asset.hash()))) {
                cancelOffer(viewer, session, transfer.offer, "asset_not_authorized"); continue;
            }
            if (transfer.offer == null && !authorizedHash(viewer, session, transfer.asset.hash())) {
                removeTransfer(session); continue;
            }
            if (!transfer.started) {
                if (transfer.pendingPayload == null) {
                JsonObject begin = envelope("asset_begin"); begin.addProperty("modelId", transfer.offer == null ? transfer.asset.modelId() : transfer.offer.modelId());
                begin.addProperty("hash", transfer.asset.hash()); if (transfer.offer != null) addOffer(begin, transfer.offer);
                begin.addProperty("rawBytes", transfer.asset.rawBytes()); begin.addProperty("compressedBytes", transfer.asset.compressed().length);
                begin.addProperty("chunks", transfer.asset.chunks(transfer.chunkBytes));
                transfer.pendingPayload = encode(begin);
                }
                Result result = sendTransfer(viewer, session, transfer);
                if (result == Result.DEFERRED) return;
                if (result == Result.FAILED) { failTransfer(viewer, session, transfer); continue; } transfer.started = true;
                if (transfer.offer != null) session.offers.progress(transfer.offer, currentTick());
            }
            if (transfer.index == transfer.asset.chunks(transfer.chunkBytes)) {
                if (transfer.pendingPayload == null) {
                JsonObject end = envelope("asset_end"); end.addProperty("hash", transfer.asset.hash());
                if (transfer.offer != null) addOffer(end, transfer.offer);
                transfer.pendingPayload = encode(end);
                }
                Result result = sendTransfer(viewer, session, transfer);
                if (result == Result.DEFERRED) return;
                if (result == Result.FAILED) failTransfer(viewer, session, transfer);
                else { if (transfer.offer != null) session.offers.delivered(transfer.offer, currentTick()); removeTransfer(session); }
                continue;
            }
            if (transfer.pendingPayload == null) {
            JsonObject chunk = envelope("asset_chunk"); chunk.addProperty("hash", transfer.asset.hash()); chunk.addProperty("index", transfer.index);
            if (transfer.offer != null) addOffer(chunk, transfer.offer);
            chunk.addProperty("data", Base64.getEncoder().encodeToString(transfer.asset.chunk(transfer.index, transfer.chunkBytes)));
            transfer.pendingPayload = encode(chunk);
            }
            Result result = sendTransfer(viewer, session, transfer);
            if (result == Result.DEFERRED) return;
            if (result == Result.FAILED) { failTransfer(viewer, session, transfer); continue; } sentChunks++; transfer.index++;
            if (transfer.offer != null) session.offers.progress(transfer.offer, currentTick());
        }
    }
    private Result sendTransfer(Player viewer, Session session, Transfer transfer) {
        if (!authorizedHash(viewer, session, transfer.asset.hash())) return Result.FAILED;
        Result result = sendBytes(viewer, transfer.pendingPayload, true);
        if (result != Result.DEFERRED) transfer.pendingPayload = null;
        return result;
    }
    private void removeTransfer(Session session) { session.transfers.remove(); limits.releaseTransfer(session.viewer); }
    private void failTransfer(Player viewer, Session session, Transfer transfer) {
        if (transfer.offer == null) removeTransfer(session); else cancelOffer(viewer, session, transfer.offer, "asset_send_failed");
    }
    private Set<UUID> readOwnerIds() {
        try { return snapshotCache.owners(currentTick()); }
        catch (RuntimeException exception) { plugin.getLogger().log(Level.WARNING, "Cannot obtain client owner directory", exception); return Set.of(); }
    }
    private StateSnapshot readSnapshot(UUID owner) {
        try { return snapshotCache.get(owner, currentTick()); }
        catch (RuntimeException exception) { plugin.getLogger().log(Level.WARNING, "Cannot obtain client snapshot", exception); return null; }
    }
    private void reconcile(Player viewer, Session session, boolean fullSnapshot) {
        Set<UUID> visible = new HashSet<>();
        for (UUID owner : readOwnerIds()) if (canObserve(viewer, owner)) {
            StateSnapshot snapshot = readSnapshot(owner);
            if (snapshot != null) { visible.add(owner); sendState(viewer, session, snapshot, fullSnapshot, fullSnapshot); }
        }
        for (UUID owner : List.copyOf(session.bindings.keySet())) if (!visible.contains(owner)) removeBinding(viewer, session, owner, "not_visible_or_active");
        session.authorized = session.pushModels ? authorizedHashes(viewer, session) : Set.of();
    }
    private boolean canObserve(Player viewer, UUID ownerId) {
        if (viewer == null || !viewer.isOnline()) return false;
        Player owner = Bukkit.getPlayer(ownerId);
        return owner != null && owner.isOnline() && audience.test(viewer, ownerId) && viewer.getWorld().equals(owner.getWorld())
                && (viewer.getUniqueId().equals(ownerId) || viewer.canSee(owner)
                && viewer.getLocation().distanceSquared(owner.getLocation()) <= viewDistanceBlocks * viewDistanceBlocks);
    }
    private void sendState(Player viewer, Session session, StateSnapshot snapshot, boolean force) {
        sendState(viewer, session, snapshot, force, false);
    }
    private void sendState(Player viewer, Session session, StateSnapshot snapshot, boolean force, boolean completeMenu) {
        StateMetadata metadata = stateMetadata(snapshot); BoundState previous = session.bindings.get(snapshot.owner());
        if (previous != null && !previous.equals(metadata.binding())) removeBinding(viewer, session, snapshot.owner(), "instance_or_asset_changed");
        if (!force && metadata.signature().equals(session.sentState.get(snapshot.owner()))) {
            offerAsset(viewer, session, snapshot.owner(), metadata.binding(), currentTick()); return;
        }
        StatePacket packet = statePacket(snapshot, metadata);
        PendingState waiting = session.pendingStates.get(snapshot.owner());
        boolean menu = completeMenu || waiting != null && waiting.menu() || !session.incremental
                || !Objects.equals(session.sentMenus.get(snapshot.owner()), snapshot.animations());
        deliverState(viewer, session, new PendingState(packet, menu));
    }
    private void deliverState(Player viewer, Session session, PendingState pending) {
        StatePacket packet = pending.packet(); UUID owner = packet.snapshot().owner();
        byte[] payload = pending.menu() ? packet.full() : packet.delta();
        if (payload == null) {
            removeBinding(viewer, session, owner, "state_payload_limit");
            if (session.payloadFailures.add(owner)) plugin.getLogger().warning("客户端状态超过负载上限，保持 ME：" + packet.snapshot().modelId());
            return;
        }
        Result result = sendBytes(viewer, payload, false);
        if (result == Result.DEFERRED) {
            if (session.pendingStates.containsKey(owner) || session.pendingStates.size() < DeferredClientPackets.CAPACITY)
                session.pendingStates.put(owner, pending);
            return;
        }
        session.pendingStates.remove(owner);
        if (result == Result.FAILED) { removeBinding(viewer, session, owner, "state_send_failed"); return; }
        session.payloadFailures.remove(owner); session.bindings.put(owner, packet.binding());
        session.controls.cancel("unbind:" + owner + ":" + packet.binding().instance());
        viewersByOwner.computeIfAbsent(owner, ignored -> new HashSet<>()).add(session.viewer);
        session.sentState.put(owner, packet.signature());
        if (pending.menu()) session.sentMenus.put(owner, packet.publishedMenu());
        if (!packet.binding().hash().isEmpty()) {
            var authorized = new HashSet<>(session.authorized); authorized.add(packet.binding().hash()); session.authorized = Set.copyOf(authorized);
        }
        offerAsset(viewer, session, owner, packet.binding(), currentTick());
    }
    private void retryStates(Player viewer, Session session) {
        int attempts = 0;
        for (PendingState pending : List.copyOf(session.pendingStates.values())) {
            if (attempts++ >= 4) break;
            UUID owner = pending.packet().snapshot().owner(); StateSnapshot current = readSnapshot(owner);
            if (current == null || !current.instance().equals(pending.packet().binding().instance()) || !canObserve(viewer, owner)) {
                session.pendingStates.remove(owner); removeBinding(viewer, session, owner, "not_visible_or_active"); continue;
            }
            deliverState(viewer, session, pending);
            if (session.pendingStates.containsKey(owner)) break;
        }
    }
    private StateMetadata stateMetadata(StateSnapshot snapshot) {
        long tick = currentTick();
        if (packetTick != tick) { packetTick = tick; statePackets.clear(); stateMetadata.clear(); }
        StateMetadata known = stateMetadata.get(snapshot.owner());
        if (known != null && known.snapshot().equals(snapshot)) return known;
        String hash = snapshot.localRenderable() ? assets.get(snapshot.modelId()).map(ModelAssets.Asset::hash).orElse("") : "";
        ModelAssets.Status status = assets.status(snapshot.modelId()); Player owner = Bukkit.getPlayer(snapshot.owner());
        int food = owner == null ? 20 : owner.getFoodLevel();
        StateSignature signature = new StateSignature(snapshot.instance(), snapshot.modelId(), hash, snapshot.sequence(), snapshot.world(),
                snapshot.layers(), snapshot.motion(), snapshot.accessories(), snapshot.scale(), snapshot.hidePlayer(), snapshot.showSelf(),
                snapshot.animations(), status.state(), status.reason(), status.source(), food);
        StateMetadata result = new StateMetadata(snapshot, new BoundState(snapshot.instance(), snapshot.modelId(), hash), signature, status, food);
        stateMetadata.put(snapshot.owner(), result); return result;
    }
    private StatePacket statePacket(StateSnapshot snapshot, StateMetadata metadata) {
        StatePacket known = statePackets.get(snapshot.owner());
        if (known != null && known.snapshot().equals(snapshot)) return known;
        String hash = metadata.binding().hash(); ModelAssets.Status assetStatus = metadata.status();
        JsonObject state = envelope("state"); state.addProperty("owner", snapshot.owner().toString()); state.addProperty("instance", snapshot.instance().toString());
        state.addProperty("modelId", snapshot.modelId()); state.addProperty("assetHash", hash); state.addProperty("sequence", snapshot.sequence());
        state.addProperty("assetStatus", snapshot.localRenderable() ? assetStatus.state() : "server-only");
        state.addProperty("assetReason", snapshot.localRenderable() ? assetStatus.reason() : "当前伪装不允许客户端接管");
        state.addProperty("assetSource", assetStatus.source());
        state.addProperty("serverTick", snapshot.serverTick()); state.addProperty("world", snapshot.world().toString());
        state.addProperty("x", snapshot.x()); state.addProperty("y", snapshot.y()); state.addProperty("z", snapshot.z());
        state.addProperty("bodyYaw", snapshot.bodyYaw()); state.addProperty("headYaw", snapshot.headYaw()); state.addProperty("headPitch", snapshot.headPitch());
        state.addProperty("scale", snapshot.scale()); state.addProperty("hidePlayer", snapshot.hidePlayer()); state.addProperty("showSelf", snapshot.showSelf());
        state.addProperty("foodLevel", metadata.food());
        state.add("accessories", accessoriesJson(snapshot.accessories()));
        state.add("motion", motionJson(snapshot.motion()));
        JsonArray layers = new JsonArray();
        for (LayerState layer : snapshot.layers()) {
            JsonObject value = new JsonObject(); value.addProperty("layer", layer.layer()); value.addProperty("animation", layer.animation());
            value.addProperty("startedAtTick", layer.startedAtTick()); value.addProperty("speed", layer.speed()); value.addProperty("loop", layer.loop());
            value.addProperty("inTicks", layer.inTicks()); value.addProperty("outTicks", layer.outTicks()); layers.add(value);
        }
        state.add("layers", layers); JsonArray animations = new JsonArray();
        for (AnimationInfo info : snapshot.animations()) {
            JsonObject value = new JsonObject(); value.addProperty("id", info.id());
            value.addProperty("label", truncateLabel(info.label())); animations.add(value);
        }
        state.add("animations", animations);
        byte[] full = encode(state); List<AnimationInfo> published = snapshot.animations();
        if (full.length > maxPayload) { state.add("animations", new JsonArray()); full = encode(state); published = List.of(); }
        if (full.length > maxPayload) full = null;
        state.remove("animations"); byte[] delta = encode(state); if (delta.length > maxPayload) delta = null;
        StatePacket result = new StatePacket(snapshot, metadata.binding(), metadata.signature(), full, delta, published);
        statePackets.put(snapshot.owner(), result); return result;
    }
    private static byte[] encode(JsonObject message) { return GSON.toJson(message).getBytes(StandardCharsets.UTF_8); }
    static String truncateLabel(String label) {
        if (label == null) return "";
        StringBuilder safe = new StringBuilder();
        label.codePoints().filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(64).forEach(safe::appendCodePoint);
        return safe.toString();
    }
    static boolean fitStatePacket(JsonObject state, int maxPayload) {
        if (payloadSize(state) <= maxPayload) return true;
        state.add("animations", new JsonArray());
        return payloadSize(state) <= maxPayload;
    }
    private static int payloadSize(JsonObject packet) { return GSON.toJson(packet).getBytes(StandardCharsets.UTF_8).length; }
    private void removeBinding(Player viewer, Session session, UUID owner, String reason) {
        PendingReady pending = session.pendingReady.remove(owner); if (pending != null) restore(session.viewer, pending.binding());
        var lease = session.leases.remove(owner); if (lease != null) restore(session.viewer, lease);
        BoundState previous = session.bindings.remove(owner); if (previous != null && viewer != null) sendUnbind(viewer, owner, previous.instance(), reason);
        session.pendingStates.remove(owner); session.sentState.remove(owner); session.sentMenus.remove(owner);
        Set<UUID> indexed = viewersByOwner.get(owner); if (indexed != null) { indexed.remove(session.viewer); if (indexed.isEmpty()) viewersByOwner.remove(owner); }
        if (session.pushModels) cancelUnauthorizedOffers(viewer, session, currentTick());
    }
    private void restore(UUID viewer, RenderLeases.Binding binding) {
        try { renderControl.set(viewer, binding.owner(), binding.instance(), false); }
        catch (RuntimeException exception) { plugin.getLogger().log(Level.WARNING, "Cannot restore ME viewer rendering", exception); }
    }
    private void sendUnbind(Player viewer, UUID owner, UUID instance, String reason) {
        JsonObject message = envelope("unbind"); message.addProperty("owner", owner.toString()); message.addProperty("instance", instance.toString());
        message.addProperty("reason", reason == null ? "unspecified" : reason.substring(0, Math.min(128, reason.length())));
        Session session = viewer == null ? null : sessions.get(viewer.getUniqueId());
        if (session != null) critical(viewer, session, "unbind:" + owner + ":" + instance, message, () -> {}, () -> {},
                () -> { BoundState rebound = session.bindings.get(owner); return rebound == null || !rebound.instance().equals(instance); });
    }
    private void endSession(UUID id, Player viewer, Session session, String reason) {
        for (UUID owner : List.copyOf(session.bindings.keySet())) removeBinding(viewer, session, owner, reason);
        for (var binding : session.leases.clear()) restore(id, binding);
        for (PushAssetOffers.Offer offer : session.offers.clear()) cancelOffer(viewer, session, offer, reason);
        while (!session.transfers.isEmpty()) { session.transfers.remove(); limits.releaseTransfer(id); }
        session.controls.clear(); session.pendingStates.clear(); session.pendingReady.clear();
        sessions.remove(id);
    }
    private void clearSessions(String reason) {
        for (var entry : List.copyOf(sessions.entrySet())) endSession(entry.getKey(), Bukkit.getPlayer(entry.getKey()), entry.getValue(), reason);
        sessions.clear();
        viewersByOwner.clear(); statePackets.clear(); stateMetadata.clear();
    }
    private boolean send(Player player, JsonObject message) {
        return sendPacket(player, message, false) == Result.SENT;
    }
    private Result sendPacket(Player player, JsonObject message, boolean asset) {
        return sendBytes(player, encode(message), asset);
    }
    private Result sendBytes(Player player, byte[] payload, boolean asset) {
        Result reserved = reserveBytes(player, payload, asset);
        return reserved == Result.SENT ? sendReservedBytes(player, payload) : reserved;
    }
    private Result reserveBytes(Player player, byte[] payload, boolean asset) {
        if (player == null || !running || !configuredEnabled || !plugin.isEnabled() || !player.isOnline()
                || !sessions.containsKey(player.getUniqueId())) return Result.FAILED;
        try {
            if (payload.length > maxPayload) { plugin.getLogger().warning("Client packet exceeds payload limit"); return Result.FAILED; }
            if (!limits.allowOutbound(player.getUniqueId(), payload.length, asset, System.nanoTime(), currentTick())) return Result.DEFERRED;
            return Result.SENT;
        } catch (RuntimeException exception) { plugin.getLogger().log(Level.FINE, "Cannot send client packet", exception); return Result.FAILED; }
    }
    private Result sendReservedBytes(Player player, byte[] payload) {
        if (player == null || !running || !configuredEnabled || !plugin.isEnabled() || !player.isOnline()
                || !sessions.containsKey(player.getUniqueId())) return Result.FAILED;
        try { player.sendPluginMessage(plugin, CHANNEL, payload); return Result.SENT; }
        catch (RuntimeException exception) { plugin.getLogger().log(Level.FINE, "Cannot send client packet", exception); return Result.FAILED; }
    }
    private void critical(Player viewer, Session session, String key, JsonObject message, Runnable sent, Runnable failed, BooleanSupplier valid) {
        byte[] bytes = encode(message); Result result = sendBytes(viewer, bytes, false);
        if (result == Result.SENT) sent.run();
        else if (result == Result.DEFERRED) session.controls.add(key, bytes, currentTick(), valid, sent, failed);
        else failed.run();
    }
    private static boolean matches(BoundState bound, RenderLeases.Binding identity) {
        return bound != null && bound.instance().equals(identity.instance()) && bound.hash().equals(identity.hash());
    }
    private void sendError(Player player, String code) { JsonObject message = envelope("error"); message.addProperty("code", code); send(player, message); }
    private void sendError(Player player, String code, RenderLeases.Binding binding) {
        JsonObject message = envelope("error"); message.addProperty("code", code); addBinding(message, binding); send(player, message);
    }
    private static Inbound decode(byte[] bytes) throws IOException {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException exception) { throw new IOException("Invalid UTF-8", exception); }
        int protocol = -1; String type = "", version = "unspecified", action = "", argument = "", modelId = "", hash = "", assetStatus = "";
        UUID owner = null, instance = null, offerId = null; List<RenderLeases.Binding> bindings = List.of(); Set<String> fields = new HashSet<>(), capabilities = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT); reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName(); if (!fields.add(key) || fields.size() > 10) throw new IllegalArgumentException("Duplicate or excessive fields");
                switch (key) {
                    case "protocol" -> {
                        if (reader.peek() != JsonToken.NUMBER) throw new IllegalArgumentException("Protocol must be an integer");
                        String number = reader.nextString(); if (number.length() > 12) throw new IllegalArgumentException("Protocol overflow"); protocol = new BigDecimal(number).intValueExact();
                    }
                    case "type" -> type = readString(reader, 32);
                    case "clientVersion" -> version = readString(reader, 64);
                    case "action" -> action = readString(reader, 16);
                    case "argument" -> argument = readString(reader, 128);
                    case "modelId" -> modelId = readString(reader, 64);
                    case "hash" -> hash = readString(reader, 64);
                    case "offerId" -> offerId = readUuid(reader);
                    case "status" -> assetStatus = readString(reader, 16);
                    case "owner" -> owner = readUuid(reader);
                    case "instance" -> instance = readUuid(reader);
                    case "bindings" -> bindings = readBindings(reader);
                    case "capabilities" -> {
                        reader.beginArray(); while (reader.hasNext()) {
                            String capability = readString(reader, 32);
                            if (!CAPABILITIES.contains(capability) || !capabilities.add(capability)) throw new IllegalArgumentException("Unsupported capability");
                        } reader.endArray();
                    }
                    default -> throw new IllegalArgumentException("Unknown field");
                }
            }
            reader.endObject(); if (reader.peek() != JsonToken.END_DOCUMENT || !fields.containsAll(Set.of("protocol", "type"))) throw new IllegalArgumentException("Incomplete/trailing payload");
        } catch (ArithmeticException exception) { throw new IllegalArgumentException("Protocol must be an integer", exception); }
        Set<String> allowed;
        switch (type) {
            case "hello" -> { allowed = Set.of("protocol", "type", "clientVersion", "capabilities");
                if (!capabilities.contains("local_render")) throw new IllegalArgumentException("local_render capability required"); }
            case "snapshot_request" -> allowed = Set.of("protocol", "type");
            case "request" -> { allowed = Set.of("protocol", "type", "action", "argument");
                if (!ACTIONS.contains(action) || (action.equals("play") ? !ANIMATION_NAME.matcher(argument).matches() : !argument.isEmpty())) throw new IllegalArgumentException("Invalid action request"); }
            case "asset_request" -> { allowed = Set.of("protocol", "type", "modelId", "hash");
                if (!MODEL_ID.matcher(modelId).matches() || !HASH.matcher(hash).matches()) throw new IllegalArgumentException("Invalid asset key"); }
            case "asset_status" -> { allowed = Set.of("protocol", "type", "offerId", "hash", "status");
                if (offerId == null || !HASH.matcher(hash).matches() || !Set.of("cached", "missing", "rejected").contains(assetStatus))
                    throw new IllegalArgumentException("Invalid asset feedback"); }
            case "render_ready", "render_failed" -> { allowed = Set.of("protocol", "type", "owner", "instance", "hash");
                if (owner == null || instance == null || !HASH.matcher(hash).matches()) throw new IllegalArgumentException("Incomplete render binding"); }
            case "render_heartbeat" -> { allowed = Set.of("protocol", "type", "bindings");
                if (!fields.contains("bindings")) throw new IllegalArgumentException("Bindings required"); }
            default -> throw new IllegalArgumentException("Unknown message type");
        }
        if (!allowed.containsAll(fields)) throw new IllegalArgumentException("Field not allowed for message");
        return new Inbound(protocol, type, version, capabilities.contains("resource_pack_models"), capabilities.contains("server_push_models"),
                capabilities.contains("incremental_state"), capabilities.contains("server_timeline"), capabilities.contains(ServerModelCatalog.CAPABILITY), action, argument, modelId, hash, offerId, assetStatus,
                owner == null ? null : new RenderLeases.Binding(owner, instance, hash), bindings);
    }
    private static List<RenderLeases.Binding> readBindings(JsonReader reader) throws IOException {
        List<RenderLeases.Binding> bindings = new ArrayList<>(); Set<UUID> owners = new HashSet<>(); reader.beginArray();
        while (reader.hasNext()) {
            if (bindings.size() >= 64) throw new IllegalArgumentException("Too many render bindings");
            reader.beginObject(); Set<String> keys = new HashSet<>(); UUID owner = null, instance = null; String hash = "";
            while (reader.hasNext()) {
                String key = reader.nextName(); if (!keys.add(key)) throw new IllegalArgumentException("Duplicate binding field");
                switch (key) { case "owner" -> owner = readUuid(reader); case "instance" -> instance = readUuid(reader);
                    case "hash" -> hash = readString(reader, 64); default -> throw new IllegalArgumentException("Unknown binding field"); }
            }
            reader.endObject(); if (owner == null || instance == null || !HASH.matcher(hash).matches() || !owners.add(owner)) throw new IllegalArgumentException("Invalid/duplicate render binding");
            bindings.add(new RenderLeases.Binding(owner, instance, hash));
        }
        reader.endArray(); return List.copyOf(bindings);
    }
    private static UUID readUuid(JsonReader reader) throws IOException {
        String text = readString(reader, 36); UUID uuid = UUID.fromString(text);
        if (!uuid.toString().equals(text)) throw new IllegalArgumentException("UUID must be canonical lowercase"); return uuid;
    }
    private static String readString(JsonReader reader, int maxLength) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException("Expected string");
        String value = reader.nextString(); if (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid string"); return value;
    }
    private static void addBinding(JsonObject object, RenderLeases.Binding binding) {
        object.addProperty("owner", binding.owner().toString()); object.addProperty("instance", binding.instance().toString()); object.addProperty("hash", binding.hash());
    }
    private static JsonObject envelope(String type) { JsonObject object = new JsonObject(); object.addProperty("protocol", PROTOCOL); object.addProperty("type", type); return object; }
    private static long currentTick() { return Integer.toUnsignedLong(Bukkit.getCurrentTick()); }
    private static long tickDistance(long current, long previous) { return (current - previous) & 0xffffffffL; }
    private static JsonObject motionJson(MotionState motion) {
        JsonObject value = new JsonObject();
        JsonArray features = new JsonArray(); motion.features().forEach(features::add); value.add("features", features);
        JsonArray clips = new JsonArray();
        for (LayerState clip : motion.clips()) {
            JsonObject entry = new JsonObject(); entry.addProperty("state", clip.layer()); entry.addProperty("animation", clip.animation());
            entry.addProperty("speed", clip.speed()); entry.addProperty("loop", clip.loop());
            entry.addProperty("inTicks", clip.inTicks()); entry.addProperty("outTicks", clip.outTicks()); clips.add(entry);
        }
        value.add("clips", clips); value.addProperty("jumpMinTicks", motion.jumpMinTicks());
        value.addProperty("landingGraceTicks", motion.landingGraceTicks()); value.addProperty("movementThreshold", motion.movementThreshold());
        value.addProperty("interruptMove", motion.interruptMove()); value.addProperty("interruptPosture", motion.interruptPosture());
        value.addProperty("flying", motion.flying());
        value.addProperty("interaction", motion.interaction());
        value.addProperty("forcedPose", motion.forcedPose());
        value.addProperty("specialPose", motion.specialPose()); value.addProperty("anchorX", motion.anchorX());
        value.addProperty("anchorY", motion.anchorY()); value.addProperty("anchorZ", motion.anchorZ()); value.addProperty("anchorYaw", motion.anchorYaw());
        return value;
    }
    static JsonObject accessoriesJson(Map<String,Double> accessories) {
        JsonObject result=new JsonObject();accessories.forEach(result::addProperty);return result;
    }
    /** Immediate client entity presentation is independent of the ME visual-follow history. */
    public record MotionState(List<String> features, List<LayerState> clips, int jumpMinTicks, int landingGraceTicks,
                              double movementThreshold, boolean interruptMove, boolean interruptPosture, boolean flying, String interaction, String forcedPose,
                              String specialPose, double anchorX, double anchorY, double anchorZ, float anchorYaw) {
        public MotionState { features = List.copyOf(features); clips = List.copyOf(clips); Objects.requireNonNull(specialPose); Objects.requireNonNull(interaction); Objects.requireNonNull(forcedPose); }
    }
    public record StateSnapshot(UUID owner, UUID instance, String modelId, long sequence, long serverTick, List<LayerState> layers,
            UUID world, double x, double y, double z, double bodyYaw, double headYaw, double headPitch, double scale,
            boolean hidePlayer, boolean showSelf, List<AnimationInfo> animations, boolean localRenderable, MotionState motion,
            Map<String,Double> accessories) {
        public StateSnapshot(UUID owner, UUID instance, String modelId, long sequence, long serverTick, List<LayerState> layers,
                UUID world, double x, double y, double z, double bodyYaw, double headYaw, double headPitch, double scale,
                boolean hidePlayer, boolean showSelf, List<AnimationInfo> animations, boolean localRenderable, MotionState motion) {
            this(owner,instance,modelId,sequence,serverTick,layers,world,x,y,z,bodyYaw,headYaw,headPitch,scale,
                    hidePlayer,showSelf,animations,localRenderable,motion,Map.of());
        }
        public StateSnapshot {
            Objects.requireNonNull(owner); Objects.requireNonNull(instance); Objects.requireNonNull(modelId); Objects.requireNonNull(world);
            layers = List.copyOf(layers); animations = List.copyOf(animations); Objects.requireNonNull(motion);
            accessories=Map.copyOf(accessories);
            if(!accessories.isEmpty() && (!accessories.keySet().equals(Set.of("a","b"))
                    || accessories.values().stream().anyMatch(value->!Double.isFinite(value)||value<0||value>1)))
                throw new IllegalArgumentException("Invalid accessory state");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(bodyYaw) || !Double.isFinite(headYaw)
                    || !Double.isFinite(headPitch) || !Double.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("Invalid visual transform");
        }
    }
    public record LayerState(String layer, String animation, long startedAtTick, double speed, String loop, int inTicks, int outTicks) {
        public LayerState { Objects.requireNonNull(layer); Objects.requireNonNull(animation); Objects.requireNonNull(loop);
            if (!Double.isFinite(speed) || speed <= 0 || inTicks < 0 || outTicks < 0) throw new IllegalArgumentException("Invalid animation parameters"); }
    }
    public record AnimationInfo(String id, String label) {}
    public record ActionRequest(Player player, String action, String argument) {}
    @FunctionalInterface public interface RenderControl {
        boolean set(UUID viewer, UUID owner, UUID instance, boolean enabled);
        default boolean pending(UUID viewer, UUID owner, UUID instance) { return false; }
    }
    private record Inbound(int protocol, String type, String clientVersion, boolean packModels, boolean pushModels, boolean incremental, boolean timeline, boolean catalog, String action, String argument, String modelId,
            String hash, UUID offerId, String assetStatus, RenderLeases.Binding binding, List<RenderLeases.Binding> bindings) {}
    private record BoundState(UUID instance, String modelId, String hash) {}
    private record StateSignature(UUID instance, String modelId, String hash, long sequence, UUID world, List<LayerState> layers,
            MotionState motion, Map<String, Double> accessories, double scale, boolean hide, boolean show, List<AnimationInfo> animations,
            String assetState, String assetReason, String assetSource, int food) {}
    private record StatePacket(StateSnapshot snapshot, BoundState binding, StateSignature signature, byte[] full, byte[] delta, List<AnimationInfo> publishedMenu) {}
    private record StateMetadata(StateSnapshot snapshot, BoundState binding, StateSignature signature, ModelAssets.Status status, int food) {}
    private record PendingState(StatePacket packet, boolean menu) {}
    private record PendingReady(RenderLeases.Binding binding, long since) {}
    private static final class Session {
        final UUID viewer; final boolean packModels, pushModels, incremental, timeline, catalog;
        Session(UUID viewer, boolean packModels, boolean pushModels, boolean incremental, boolean timeline, boolean catalog, long tick, int discovery, int validation) {
            this.viewer = viewer; this.packModels = packModels; this.pushModels = pushModels; this.incremental = incremental; this.timeline = timeline; this.catalog = catalog;
            lastDiscoveryTick = ClientSyncCadence.initialTick(viewer, tick, discovery);
            lastValidationTick = ClientSyncCadence.initialTick(viewer, tick, validation);
            lastHeartbeatTick = ClientSyncCadence.initialTick(viewer, tick, HEARTBEAT_TICKS); lastLegacyTick = tick;
        }
        String assetMode() { return pushModels ? "server-push" : packModels ? "resource-pack" : "legacy-download"; }
        final Map<UUID, BoundState> bindings = new HashMap<>(); final RenderLeases leases = new RenderLeases();
        final PushAssetOffers offers = new PushAssetOffers();
        final Deque<Transfer> transfers = new ArrayDeque<>();
        final Set<UUID> payloadFailures = new HashSet<>();
        final Map<UUID, StateSignature> sentState = new HashMap<>();
        final Map<UUID, List<AnimationInfo>> sentMenus = new HashMap<>();
        final LinkedHashMap<UUID, PendingState> pendingStates = new LinkedHashMap<>();
        final Map<UUID, PendingReady> pendingReady = new HashMap<>();
        final DeferredClientPackets controls = new DeferredClientPackets();
        Set<String> authorized = Set.of();
        long lastDiscoveryTick, lastValidationTick, lastLegacyTick, lastHeartbeatTick;
        String clientVersion = "unspecified"; long snapshotId;
        boolean helloAcknowledged; ServerModelCatalog.Plan catalogPlan;
        int catalogIndex; long catalogRevision, lastCatalogSentTick = -1; byte[] pendingCatalogBytes;
    }
    private static final class Transfer {
        final ModelAssets.Asset asset; final int chunkBytes; final PushAssetOffers.Offer offer; int index; boolean started;
        byte[] pendingPayload;
        Transfer(ModelAssets.Asset asset, int chunkBytes) { this(asset, chunkBytes, null); }
        Transfer(ModelAssets.Asset asset, int chunkBytes, PushAssetOffers.Offer offer) { this.asset = asset; this.chunkBytes = chunkBytes; this.offer = offer; }
    }
}
