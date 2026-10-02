package com.simmc.meplayeractions.client;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
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
    public static final int PROTOCOL = 2;
    private static final int HEARTBEAT_TICKS = 20, MAX_TRANSFERS = 2;
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Pattern ANIMATION_NAME = Pattern.compile("[a-zA-Z0-9_.:/-]{1,128}");
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9_-]{1,64}"), HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> ACTIONS = Set.of("play", "stop", "sit", "crawl", "reset");
    private final JavaPlugin plugin;
    private final Supplier<List<StateSnapshot>> snapshots;
    private final Consumer<ActionRequest> actionRequests;
    private final ModelAssets assets;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, TrafficWindow> traffic = new HashMap<>();
    private boolean configuredEnabled = true, running;
    private int maxPayload = 16000, requestCooldownTicks = 4;
    private double viewDistanceBlocks = 64;
    private long lastHeartbeatTick;
    private BiPredicate<Player, UUID> audience = (viewer, owner) -> true;
    private RenderControl renderControl = (viewer, owner, instance, enabled) -> !enabled;
    private BukkitTask maintenanceTask;
    public ClientSyncService(JavaPlugin plugin, Supplier<List<StateSnapshot>> snapshots, Consumer<ActionRequest> requests) {
        this.plugin = Objects.requireNonNull(plugin); this.snapshots = Objects.requireNonNull(snapshots);
        actionRequests = Objects.requireNonNull(requests); assets = new ModelAssets(plugin);
    }
    public void configure(boolean enabled, int limit, int cooldown, double distance) {
        if (!enabled && configuredEnabled) clearSessions("sync_disabled"); configuredEnabled = enabled;
        maxPayload = Math.max(1024, Math.min(32766, limit)); requestCooldownTicks = Math.max(0, Math.min(1200, cooldown));
        viewDistanceBlocks = Double.isFinite(distance) ? Math.max(1, Math.min(1024, distance)) : 64;
    }
    public void audience(BiPredicate<Player, UUID> value) { audience = Objects.requireNonNull(value); }
    public void rendering(RenderControl value) { renderControl = Objects.requireNonNull(value); }
    public void enable() {
        if (running) return; var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        try {
            messenger.registerIncomingPluginChannel(plugin, CHANNEL, this); running = true; lastHeartbeatTick = currentTick();
            maintenanceTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::maintainSessions, 1L, 1L);
        } catch (RuntimeException exception) {
            running = false; messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this);
            messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL); throw exception;
        }
    }
    @Override public void close() {
        if (maintenanceTask != null) { maintenanceTask.cancel(); maintenanceTask = null; }
        clearSessions("plugin_stopping"); running = false; var messenger = plugin.getServer().getMessenger();
        try { messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this); }
        finally { messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL); }
    }
    public void broadcast(StateSnapshot snapshot) {
        if (!running || !configuredEnabled) return;
        for (var entry : List.copyOf(sessions.entrySet())) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()) endSession(entry.getKey(), viewer, entry.getValue(), "offline");
            else if (canObserve(viewer, snapshot.owner())) sendState(viewer, entry.getValue(), snapshot);
            else removeBinding(viewer, entry.getValue(), snapshot.owner(), "out_of_range");
        }
    }
    public void unbind(UUID owner, UUID instance, String reason) {
        for (var entry : List.copyOf(sessions.entrySet())) {
            var bound = entry.getValue().bindings.get(owner);
            if (bound != null && bound.instance().equals(instance)) removeBinding(Bukkit.getPlayer(entry.getKey()), entry.getValue(), owner, reason);
        }
    }
    public void forget(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) endSession(player.getUniqueId(), player, session, "session_ended"); traffic.remove(player.getUniqueId());
    }
    public void sendSnapshot(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (!running || !configuredEnabled || session == null || !player.isOnline()) return;
        long id = ++session.snapshotId; JsonObject begin = envelope("snapshot_begin");
        begin.addProperty("snapshotId", id); begin.addProperty("serverTick", currentTick());
        if (!send(player, begin)) return; reconcile(player, session, readSnapshots());
        JsonObject end = envelope("snapshot_end"); end.addProperty("snapshotId", id); send(player, end);
    }
    public String status(Player player) {
        if (!running || !configuredEnabled) return "disabled (ME rendering)";
        Session session = sessions.get(player.getUniqueId());
        return session == null ? "未握手（ME 渲染）" : "local-render v2；客户端 " + session.clientVersion
                + "；可见实例 " + session.bindings.size() + "；已确认本地渲染 " + session.leases.size();
    }
    @Override public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (!CHANNEL.equals(channel) || !running || !configuredEnabled || bytes == null || bytes.length == 0 || bytes.length > maxPayload) return;
        if (!Bukkit.isPrimaryThread()) {
            byte[] copy = bytes.clone(); if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin,
                    () -> onPluginMessageReceived(channel, player, copy)); return;
        }
        if (!player.isOnline() || !allowTraffic(player.getUniqueId())) return;
        Inbound inbound;
        try { inbound = decode(bytes); }
        catch (IOException | IllegalArgumentException | IllegalStateException exception) { sendError(player, "invalid_payload"); return; }
        if (inbound.protocol() != PROTOCOL) { sendError(player, "unsupported_protocol"); forget(player); return; }
        if (inbound.type().equals("hello")) {
            long now = System.nanoTime(); Session previous = sessions.get(player.getUniqueId());
            if (previous != null && now - previous.lastHelloAt < 1000000000L) return;
            if (previous != null) endSession(player.getUniqueId(), player, previous, "new_handshake");
            Session session = new Session(player.getUniqueId()); session.lastHelloAt = now; session.clientVersion = inbound.clientVersion();
            sessions.put(player.getUniqueId(), session); JsonObject ack = envelope("hello_ack");
            ack.addProperty("mode", "local-render"); ack.addProperty("serverTick", currentTick());
            ack.addProperty("heartbeatTicks", HEARTBEAT_TICKS); ack.addProperty("leaseTicks", RenderLeases.LEASE_TICKS);
            ack.addProperty("maxPayload", maxPayload); ack.addProperty("requestCooldownTicks", requestCooldownTicks);
            JsonArray capabilities = new JsonArray(); capabilities.add("local_render"); ack.add("capabilities", capabilities);
            if (send(player, ack)) sendSnapshot(player); return;
        }
        Session session = sessions.get(player.getUniqueId()); if (session == null) return; long tick = currentTick();
        switch (inbound.type()) {
            case "asset_request" -> requestAsset(player, session, inbound, tick);
            case "render_ready" -> ready(player, session, inbound.binding(), tick);
            case "render_failed" -> {
                if (session.leases.contains(inbound.binding())) {
                    session.leases.remove(inbound.binding().owner()); restore(player.getUniqueId(), inbound.binding());
                    sendUnbind(player, inbound.binding().owner(), inbound.binding().instance(), "render_failed");
                    session.bindings.remove(inbound.binding().owner());
                }
            }
            case "render_heartbeat" -> session.leases.renew(inbound.bindings(), tick);
            case "snapshot_request", "request" -> {
                if (!session.requests.allow(inbound.type(), inbound.action(), tick, requestCooldownTicks)) { sendError(player, "request_cooldown"); return; }
                if (inbound.type().equals("snapshot_request")) sendSnapshot(player);
                else try { actionRequests.accept(new ActionRequest(player, inbound.action(), inbound.argument())); }
                catch (RuntimeException exception) { plugin.getLogger().log(Level.WARNING, "Client action failed", exception); sendError(player, "action_failed"); }
            }
            default -> throw new IllegalStateException("Unsupported decoded message");
        }
    }
    private void ready(Player viewer, Session session, RenderLeases.Binding binding, long tick) {
        BoundState bound = session.bindings.get(binding.owner());
        StateSnapshot current = readSnapshots().stream().filter(s -> s.owner().equals(binding.owner())).findFirst().orElse(null);
        if (bound == null || current == null || !current.localRenderable() || !current.instance().equals(binding.instance())
                || !bound.instance().equals(binding.instance()) || !bound.hash().equals(binding.hash()) || !canObserve(viewer, binding.owner())) {
            sendError(viewer, "render_not_authorized", binding); return;
        }
        try {
            if (!renderControl.set(viewer.getUniqueId(), binding.owner(), binding.instance(), true)) {
                restore(viewer.getUniqueId(), binding); sendError(viewer, "render_unavailable", binding); return;
            }
        } catch (RuntimeException exception) {
            restore(viewer.getUniqueId(), binding);
            plugin.getLogger().log(Level.WARNING, "Cannot enable client rendering", exception);
            sendError(viewer, "render_unavailable", binding); return;
        }
        session.leases.ready(binding, tick); JsonObject ack = envelope("render_ack"); addBinding(ack, binding);
        if (!send(viewer, ack)) { session.leases.remove(binding.owner()); restore(viewer.getUniqueId(), binding); }
    }
    private void requestAsset(Player viewer, Session session, Inbound inbound, long tick) {
        if (session.bindings.entrySet().stream().noneMatch(e -> e.getValue().modelId().equals(inbound.modelId())
                && e.getValue().hash().equals(inbound.hash()) && canObserve(viewer, e.getKey()))) {
            sendError(viewer, "asset_not_authorized"); return;
        }
        if (session.transfers.stream().anyMatch(t -> t.asset.hash().equals(inbound.hash()))) return;
        Long previous = session.assetRequests.get(inbound.hash());
        if (previous != null && tickDistance(tick, previous) < 100) { sendError(viewer, "asset_cooldown"); return; }
        if (session.transfers.size() >= MAX_TRANSFERS) { sendError(viewer, "asset_queue_full"); return; }
        var asset = assets.get(inbound.modelId()).filter(a -> a.hash().equals(inbound.hash()));
        if (asset.isEmpty()) { sendError(viewer, "asset_unavailable"); return; }
        if (session.assetRequests.size() >= 128) session.assetRequests.clear(); session.assetRequests.put(inbound.hash(), tick);
        session.transfers.add(new Transfer(asset.get(), Math.min(9000, (maxPayload - 512) * 3 / 4)));
    }
    private void maintainSessions() {
        if (!configuredEnabled || sessions.isEmpty()) return; long tick = currentTick();
        List<StateSnapshot> current = tick % 2 == 0 ? readSnapshots() : List.of();
        boolean heartbeat = tickDistance(tick, lastHeartbeatTick) >= HEARTBEAT_TICKS; if (heartbeat) lastHeartbeatTick = tick;
        for (var entry : List.copyOf(sessions.entrySet())) {
            Player viewer = Bukkit.getPlayer(entry.getKey()); Session session = entry.getValue();
            if (viewer == null || !viewer.isOnline()) { endSession(entry.getKey(), viewer, session, "offline"); continue; }
            for (var expired : session.leases.expired(tick)) {
                restore(viewer.getUniqueId(), expired); sendUnbind(viewer, expired.owner(), expired.instance(), "render_lease_expired"); session.bindings.remove(expired.owner());
            }
            if (tick % 2 == 0) reconcile(viewer, session, current); pumpAssets(viewer, session);
            if (heartbeat) { JsonObject message = envelope("heartbeat"); message.addProperty("serverTick", tick);
                message.addProperty("leaseTicks", RenderLeases.LEASE_TICKS); send(viewer, message); }
        }
        traffic.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }
    private void pumpAssets(Player viewer, Session session) {
        int sentChunks = 0;
        while (sentChunks < 2 && !session.transfers.isEmpty()) {
            Transfer transfer = session.transfers.peek();
            if (session.bindings.values().stream().noneMatch(b -> b.hash().equals(transfer.asset.hash()))) { session.transfers.remove(); continue; }
            if (!transfer.started) {
                JsonObject begin = envelope("asset_begin"); begin.addProperty("modelId", transfer.asset.modelId()); begin.addProperty("hash", transfer.asset.hash());
                begin.addProperty("rawBytes", transfer.asset.rawBytes()); begin.addProperty("compressedBytes", transfer.asset.compressed().length);
                begin.addProperty("chunks", transfer.asset.chunks(transfer.chunkBytes));
                if (!send(viewer, begin)) { session.transfers.remove(); continue; } transfer.started = true;
            }
            JsonObject chunk = envelope("asset_chunk"); chunk.addProperty("hash", transfer.asset.hash()); chunk.addProperty("index", transfer.index);
            chunk.addProperty("data", Base64.getEncoder().encodeToString(transfer.asset.chunk(transfer.index, transfer.chunkBytes)));
            if (!send(viewer, chunk)) { session.transfers.remove(); continue; } sentChunks++; transfer.index++;
            if (transfer.index == transfer.asset.chunks(transfer.chunkBytes)) {
                JsonObject end = envelope("asset_end"); end.addProperty("hash", transfer.asset.hash()); send(viewer, end); session.transfers.remove();
            }
        }
    }
    private List<StateSnapshot> readSnapshots() {
        try { return List.copyOf(snapshots.get()); }
        catch (RuntimeException exception) { plugin.getLogger().log(Level.WARNING, "Cannot obtain client snapshot", exception); return List.of(); }
    }
    private void reconcile(Player viewer, Session session, List<StateSnapshot> current) {
        Set<UUID> visible = new HashSet<>();
        for (StateSnapshot snapshot : current) if (canObserve(viewer, snapshot.owner())) { visible.add(snapshot.owner()); sendState(viewer, session, snapshot); }
        for (UUID owner : List.copyOf(session.bindings.keySet())) if (!visible.contains(owner)) removeBinding(viewer, session, owner, "not_visible_or_active");
    }
    private boolean canObserve(Player viewer, UUID ownerId) {
        Player owner = Bukkit.getPlayer(ownerId);
        return owner != null && owner.isOnline() && audience.test(viewer, ownerId) && viewer.getWorld().equals(owner.getWorld())
                && (viewer.getUniqueId().equals(ownerId) || viewer.canSee(owner)
                && viewer.getLocation().distanceSquared(owner.getLocation()) <= viewDistanceBlocks * viewDistanceBlocks);
    }
    private void sendState(Player viewer, Session session, StateSnapshot snapshot) {
        String hash = snapshot.localRenderable() ? assets.get(snapshot.modelId()).map(ModelAssets.Asset::hash).orElse("") : "";
        BoundState previous = session.bindings.get(snapshot.owner());
        if (previous != null && (!previous.instance().equals(snapshot.instance()) || !previous.hash().equals(hash)))
            removeBinding(viewer, session, snapshot.owner(), "instance_or_asset_changed");
        JsonObject state = envelope("state"); state.addProperty("owner", snapshot.owner().toString()); state.addProperty("instance", snapshot.instance().toString());
        state.addProperty("modelId", snapshot.modelId()); state.addProperty("assetHash", hash); state.addProperty("sequence", snapshot.sequence());
        state.addProperty("serverTick", snapshot.serverTick()); state.addProperty("world", snapshot.world().toString());
        state.addProperty("x", snapshot.x()); state.addProperty("y", snapshot.y()); state.addProperty("z", snapshot.z());
        state.addProperty("bodyYaw", snapshot.bodyYaw()); state.addProperty("headYaw", snapshot.headYaw()); state.addProperty("headPitch", snapshot.headPitch());
        state.addProperty("scale", snapshot.scale()); state.addProperty("hidePlayer", snapshot.hidePlayer()); state.addProperty("showSelf", snapshot.showSelf());
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
        if (!fitStatePacket(state, maxPayload)) {
            removeBinding(viewer, session, snapshot.owner(), "state_payload_limit");
            if (session.payloadFailures.add(snapshot.owner())) plugin.getLogger().warning("客户端状态超过负载上限，保持 ME：" + snapshot.modelId());
            return;
        }
        session.payloadFailures.remove(snapshot.owner());
        if (send(viewer, state)) session.bindings.put(snapshot.owner(), new BoundState(snapshot.instance(), snapshot.modelId(), hash));
        else removeBinding(viewer, session, snapshot.owner(), "state_send_failed");
    }
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
        var lease = session.leases.remove(owner); if (lease != null) restore(session.viewer, lease);
        BoundState previous = session.bindings.remove(owner); if (previous != null && viewer != null) sendUnbind(viewer, owner, previous.instance(), reason);
    }
    private void restore(UUID viewer, RenderLeases.Binding binding) {
        try { renderControl.set(viewer, binding.owner(), binding.instance(), false); }
        catch (RuntimeException exception) { plugin.getLogger().log(Level.WARNING, "Cannot restore ME viewer rendering", exception); }
    }
    private void sendUnbind(Player viewer, UUID owner, UUID instance, String reason) {
        JsonObject message = envelope("unbind"); message.addProperty("owner", owner.toString()); message.addProperty("instance", instance.toString());
        message.addProperty("reason", reason == null ? "unspecified" : reason.substring(0, Math.min(128, reason.length()))); send(viewer, message);
    }
    private void endSession(UUID id, Player viewer, Session session, String reason) {
        for (UUID owner : List.copyOf(session.bindings.keySet())) removeBinding(viewer, session, owner, reason);
        for (var binding : session.leases.clear()) restore(id, binding);
        session.transfers.clear(); session.assetRequests.clear(); sessions.remove(id); traffic.remove(id);
    }
    private void clearSessions(String reason) {
        for (var entry : List.copyOf(sessions.entrySet())) endSession(entry.getKey(), Bukkit.getPlayer(entry.getKey()), entry.getValue(), reason);
        sessions.clear(); traffic.clear();
    }
    private boolean send(Player player, JsonObject message) {
        if (player == null || !running || !configuredEnabled || !plugin.isEnabled() || !player.isOnline() || !sessions.containsKey(player.getUniqueId())) return false;
        try {
            byte[] payload = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
            if (payload.length > maxPayload) { plugin.getLogger().warning("Client packet exceeds payload limit: " + message.get("type")); return false; }
            player.sendPluginMessage(plugin, CHANNEL, payload); return true;
        } catch (RuntimeException exception) { plugin.getLogger().log(Level.FINE, "Cannot send client packet", exception); return false; }
    }
    private void sendError(Player player, String code) { JsonObject message = envelope("error"); message.addProperty("code", code); send(player, message); }
    private void sendError(Player player, String code, RenderLeases.Binding binding) {
        JsonObject message = envelope("error"); message.addProperty("code", code); addBinding(message, binding); send(player, message);
    }
    private boolean allowTraffic(UUID player) {
        long now = System.nanoTime(); TrafficWindow window = traffic.computeIfAbsent(player, ignored -> new TrafficWindow(now));
        if (now - window.startedAt >= 1000000000L) { window.startedAt = now; window.packets = 0; } return ++window.packets <= 48;
    }
    private static Inbound decode(byte[] bytes) throws IOException {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException exception) { throw new IOException("Invalid UTF-8", exception); }
        int protocol = -1; String type = "", version = "unspecified", action = "", argument = "", modelId = "", hash = "";
        UUID owner = null, instance = null; List<RenderLeases.Binding> bindings = List.of(); Set<String> fields = new HashSet<>(), capabilities = new HashSet<>();
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
                    case "owner" -> owner = readUuid(reader);
                    case "instance" -> instance = readUuid(reader);
                    case "bindings" -> bindings = readBindings(reader);
                    case "capabilities" -> {
                        reader.beginArray(); while (reader.hasNext()) {
                            String capability = readString(reader, 32);
                            if (!capability.equals("local_render") || !capabilities.add(capability)) throw new IllegalArgumentException("Unsupported capability");
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
            case "render_ready", "render_failed" -> { allowed = Set.of("protocol", "type", "owner", "instance", "hash");
                if (owner == null || instance == null || !HASH.matcher(hash).matches()) throw new IllegalArgumentException("Incomplete render binding"); }
            case "render_heartbeat" -> { allowed = Set.of("protocol", "type", "bindings");
                if (!fields.contains("bindings")) throw new IllegalArgumentException("Bindings required"); }
            default -> throw new IllegalArgumentException("Unknown message type");
        }
        if (!allowed.containsAll(fields)) throw new IllegalArgumentException("Field not allowed for message");
        return new Inbound(protocol, type, version, action, argument, modelId, hash, owner == null ? null : new RenderLeases.Binding(owner, instance, hash), bindings);
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
    public record StateSnapshot(UUID owner, UUID instance, String modelId, long sequence, long serverTick, List<LayerState> layers,
            UUID world, double x, double y, double z, double bodyYaw, double headYaw, double headPitch, double scale,
            boolean hidePlayer, boolean showSelf, List<AnimationInfo> animations, boolean localRenderable) {
        public StateSnapshot {
            Objects.requireNonNull(owner); Objects.requireNonNull(instance); Objects.requireNonNull(modelId); Objects.requireNonNull(world);
            layers = List.copyOf(layers); animations = List.copyOf(animations);
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
    @FunctionalInterface public interface RenderControl { boolean set(UUID viewer, UUID owner, UUID instance, boolean enabled); }
    private record Inbound(int protocol, String type, String clientVersion, String action, String argument, String modelId,
            String hash, RenderLeases.Binding binding, List<RenderLeases.Binding> bindings) {}
    private record BoundState(UUID instance, String modelId, String hash) {}
    private static final class Session {
        final UUID viewer;
        Session(UUID viewer) { this.viewer = viewer; }
        final Map<UUID, BoundState> bindings = new HashMap<>(); final RenderLeases leases = new RenderLeases();
        final Deque<Transfer> transfers = new ArrayDeque<>(); final Map<String, Long> assetRequests = new HashMap<>();
        final Set<UUID> payloadFailures = new HashSet<>();
        final RequestCooldown requests = new RequestCooldown();
        String clientVersion = "unspecified"; long lastHelloAt, snapshotId;
    }
    private static final class Transfer {
        final ModelAssets.Asset asset; final int chunkBytes; int index; boolean started;
        Transfer(ModelAssets.Asset asset, int chunkBytes) { this.asset = asset; this.chunkBytes = chunkBytes; }
    }
    private static final class TrafficWindow { long startedAt; int packets; TrafficWindow(long time) { startedAt = time; } }
}
