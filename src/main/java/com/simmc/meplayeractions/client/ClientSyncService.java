package com.simmc.meplayeractions.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.regex.Pattern;

/** Protocol reservation only: a handshake never hides or replaces ModelEngine rendering. */
public final class ClientSyncService implements PluginMessageListener, AutoCloseable {
    public static final String CHANNEL = "mact:main";
    public static final int PROTOCOL = 1;
    private static final int HEARTBEAT_TICKS = 100;
    private static final int LEASE_TICKS = 400;
    private static final int MAX_FIELDS = 7;
    private static final int MAX_PACKETS_PER_SECOND = 32;
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Pattern ANIMATION_NAME = Pattern.compile("[a-zA-Z0-9_.:/-]{1,128}");
    private static final Set<String> ACTIONS = Set.of("play", "stop", "sit", "crawl", "reset");

    private final JavaPlugin plugin;
    private final Supplier<List<StateSnapshot>> snapshots;
    private final Consumer<ActionRequest> actionRequests;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, TrafficWindow> traffic = new HashMap<>();
    private boolean configuredEnabled = true;
    private boolean running;
    private int maxPayload = 16_384;
    private int requestCooldownTicks = 4;
    private double viewDistanceBlocks = 64;
    private long lastHeartbeatTick;
    private BiPredicate<Player, UUID> audience = (viewer, owner) -> true;
    private BukkitTask maintenanceTask;

    public ClientSyncService(JavaPlugin plugin, Supplier<List<StateSnapshot>> snapshots,
                             Consumer<ActionRequest> actionRequests) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.actionRequests = Objects.requireNonNull(actionRequests, "actionRequests");
    }

    public void configure(boolean enabled, int maxPayload, int requestCooldownTicks,
                          double viewDistanceBlocks) {
        if (!enabled && configuredEnabled) {
            clearSessions("sync_disabled");
        }
        configuredEnabled = enabled;
        this.maxPayload = Math.max(1_024, Math.min(32_766, maxPayload));
        this.requestCooldownTicks = Math.max(0, Math.min(1_200, requestCooldownTicks));
        this.viewDistanceBlocks = Double.isFinite(viewDistanceBlocks)
                ? Math.max(1, Math.min(1_024, viewDistanceBlocks)) : 64;
    }
    public void audience(BiPredicate<Player, UUID> audience) { this.audience = Objects.requireNonNull(audience); }

    public void enable() {
        if (running) {
            return;
        }
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        try {
            messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
            running = true;
            lastHeartbeatTick = currentTick();
            maintenanceTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                    this::maintainSessions, 10L, 10L);
        } catch (RuntimeException exception) {
            running = false;
            messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this);
            messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL);
            throw exception;
        }
    }

    @Override
    public void close() {
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
        // Bukkit rejects plugin messages once onDisable has marked the plugin disabled.
        // Future clients must expire bindings through the advertised lease as well.
        clearSessions("plugin_stopping");
        running = false;
        var messenger = plugin.getServer().getMessenger();
        try {
            messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        } finally {
            messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL);
        }
    }

    public void broadcast(StateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!running || !configuredEnabled) {
            return;
        }
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()) {
                sessions.remove(entry.getKey());
                traffic.remove(entry.getKey());
            } else if (canObserve(viewer, snapshot.owner())) {
                sendState(viewer, entry.getValue(), snapshot, true);
            } else {
                removeBinding(viewer, entry.getValue(), snapshot.owner(), "out_of_range");
            }
        }
    }

    public void unbind(UUID owner, UUID instance, String reason) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(instance, "instance");
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            BoundState bound = entry.getValue().bindings.get(owner);
            if (bound != null && bound.instance().equals(instance)) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null) {
                    sendUnbind(viewer, owner, instance, reason);
                }
                entry.getValue().bindings.remove(owner);
            }
        }
    }

    public void forget(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && player.isOnline()) {
            clearBindings(player, session, "session_ended");
        }
        sessions.remove(player.getUniqueId());
        traffic.remove(player.getUniqueId());
    }

    public void sendSnapshot(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (!running || !configuredEnabled || session == null || !player.isOnline()) {
            return;
        }
        List<StateSnapshot> current = readSnapshots();
        long snapshotId = ++session.snapshotId;
        JsonObject begin = envelope("snapshot_begin");
        begin.addProperty("snapshotId", snapshotId);
        begin.addProperty("serverTick", currentTick());
        if (!send(player, begin)) {
            return;
        }
        reconcile(player, session, current, true);
        JsonObject end = envelope("snapshot_end");
        end.addProperty("snapshotId", snapshotId);
        send(player, end);
    }

    public String status(Player player) {
        if (!running || !configuredEnabled) {
            return "disabled (ME rendering remains enabled)";
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return "not handshaken (ME rendering remains enabled)";
        }
        return "state-sync-only; protocol=1; client=" + session.clientVersion
                + "; bindings=" + session.bindings.size() + "; ME rendering remains enabled";
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || !running || !configuredEnabled
                || message == null || message.length == 0 || message.length > maxPayload) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            byte[] copy = message.clone();
            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> onPluginMessageReceived(channel, player, copy));
            }
            return;
        }
        if (!player.isOnline() || !allowTraffic(player.getUniqueId())) {
            return;
        }
        Inbound inbound;
        try {
            inbound = decode(message);
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            sendError(player, "invalid_payload");
            return;
        }
        if (inbound.protocol() != PROTOCOL) {
            sendError(player, "unsupported_protocol");
            forget(player);
            return;
        }
        if (inbound.type().equals("hello")) {
            long now = System.nanoTime();
            Session previous = sessions.get(player.getUniqueId());
            if (previous != null && now - previous.lastHelloAt < 1_000_000_000L) {
                return;
            }
            Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
            session.lastHelloAt = now;
            session.clientVersion = inbound.clientVersion();
            JsonObject ack = envelope("hello_ack");
            ack.addProperty("mode", "state-sync-only");
            ack.addProperty("serverTick", currentTick());
            ack.addProperty("heartbeatTicks", HEARTBEAT_TICKS);
            ack.addProperty("leaseTicks", LEASE_TICKS);
            ack.addProperty("maxPayload", maxPayload);
            ack.addProperty("requestCooldownTicks", requestCooldownTicks);
            JsonArray capabilities = new JsonArray();
            capabilities.add("state_sync");
            ack.add("capabilities", capabilities);
            if (send(player, ack)) {
                sendSnapshot(player);
            }
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        long tick = currentTick();
        if (session.hasRequested && tickDistance(tick, session.lastRequestTick) < requestCooldownTicks) {
            sendError(player, "request_cooldown");
            return;
        }
        session.hasRequested = true;
        session.lastRequestTick = tick;
        if (inbound.type().equals("snapshot_request")) {
            sendSnapshot(player);
            return;
        }
        try {
            // The owner is always this connection's player. Authorization belongs to the plugin.
            actionRequests.accept(new ActionRequest(player, inbound.action(), inbound.argument()));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Client action handler failed", exception);
            sendError(player, "action_failed");
        }
    }

    private void maintainSessions() {
        if (!configuredEnabled || sessions.isEmpty()) {
            return;
        }
        List<StateSnapshot> current = readSnapshots();
        long tick = currentTick();
        boolean heartbeat = tickDistance(tick, lastHeartbeatTick) >= HEARTBEAT_TICKS;
        if (heartbeat) {
            lastHeartbeatTick = tick;
        }
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()) {
                sessions.remove(entry.getKey());
                traffic.remove(entry.getKey());
                continue;
            }
            reconcile(viewer, entry.getValue(), current, false);
            if (heartbeat) {
                JsonObject message = envelope("heartbeat");
                message.addProperty("serverTick", tick);
                message.addProperty("leaseTicks", LEASE_TICKS);
                send(viewer, message);
            }
        }
        traffic.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private List<StateSnapshot> readSnapshots() {
        try {
            return List.copyOf(snapshots.get());
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Cannot obtain client state snapshot", exception);
            return List.of();
        }
    }

    private void reconcile(Player viewer, Session session, List<StateSnapshot> current, boolean force) {
        Set<UUID> visible = new HashSet<>();
        for (StateSnapshot snapshot : current) {
            if (canObserve(viewer, snapshot.owner())) {
                visible.add(snapshot.owner());
                sendState(viewer, session, snapshot, force);
            }
        }
        for (UUID owner : List.copyOf(session.bindings.keySet())) {
            if (!visible.contains(owner)) {
                removeBinding(viewer, session, owner, "not_visible_or_active");
            }
        }
    }

    private boolean canObserve(Player viewer, UUID ownerId) {
        Player owner = Bukkit.getPlayer(ownerId);
        return owner != null && owner.isOnline() && audience.test(viewer, ownerId) && viewer.getWorld().equals(owner.getWorld())
                && (viewer.getUniqueId().equals(ownerId) || (viewer.canSee(owner)
                && viewer.getLocation().distanceSquared(owner.getLocation())
                <= viewDistanceBlocks * viewDistanceBlocks));
    }

    private void sendState(Player viewer, Session session, StateSnapshot snapshot, boolean force) {
        BoundState previous = session.bindings.get(snapshot.owner());
        if (previous != null && !previous.instance().equals(snapshot.instance())) {
            removeBinding(viewer, session, snapshot.owner(), "instance_changed");
            previous = null;
        }
        if (!force && previous != null && previous.sequence() == snapshot.sequence()) {
            return;
        }
        JsonObject state = envelope("state");
        state.addProperty("owner", snapshot.owner().toString());
        state.addProperty("instance", snapshot.instance().toString());
        state.addProperty("modelId", snapshot.modelId());
        state.addProperty("sequence", snapshot.sequence());
        state.addProperty("serverTick", snapshot.serverTick());
        JsonArray layers = new JsonArray();
        for (LayerState layer : snapshot.layers()) {
            JsonObject value = new JsonObject();
            value.addProperty("layer", layer.layer());
            value.addProperty("animation", layer.animation());
            value.addProperty("startedAtTick", layer.startedAtTick());
            value.addProperty("speed", layer.speed());
            value.addProperty("loop", layer.loop());
            value.addProperty("inTicks", layer.inTicks());
            value.addProperty("outTicks", layer.outTicks());
            layers.add(value);
        }
        state.add("layers", layers);
        if (send(viewer, state)) {
            session.bindings.put(snapshot.owner(), new BoundState(snapshot.instance(), snapshot.sequence()));
        }
    }

    private void removeBinding(Player viewer, Session session, UUID owner, String reason) {
        BoundState previous = session.bindings.remove(owner);
        if (previous != null) {
            sendUnbind(viewer, owner, previous.instance(), reason);
        }
    }

    private void sendUnbind(Player viewer, UUID owner, UUID instance, String reason) {
        JsonObject message = envelope("unbind");
        message.addProperty("owner", owner.toString());
        message.addProperty("instance", instance.toString());
        String text = reason == null ? "unspecified" : reason;
        message.addProperty("reason", text.substring(0, Math.min(128, text.length())));
        send(viewer, message);
    }

    private void clearBindings(Player viewer, Session session, String reason) {
        for (Map.Entry<UUID, BoundState> entry : List.copyOf(session.bindings.entrySet())) {
            sendUnbind(viewer, entry.getKey(), entry.getValue().instance(), reason);
        }
        session.bindings.clear();
    }

    private void clearSessions(String reason) {
        try {
            for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null && viewer.isOnline()) {
                    clearBindings(viewer, entry.getValue(), reason);
                }
            }
        } finally {
            sessions.clear();
            traffic.clear();
        }
    }

    private boolean send(Player player, JsonObject message) {
        if (!running || !configuredEnabled || !plugin.isEnabled() || !player.isOnline()
                || !sessions.containsKey(player.getUniqueId())) {
            return false;
        }
        try {
            byte[] payload = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
            if (payload.length > maxPayload) {
                plugin.getLogger().warning("Client state packet exceeds configured payload limit; packet skipped");
                return false;
            }
            player.sendPluginMessage(plugin, CHANNEL, payload);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Cannot send client state packet", exception);
            return false;
        }
    }

    private void sendError(Player player, String code) {
        JsonObject message = envelope("error");
        message.addProperty("code", code);
        send(player, message);
    }

    private boolean allowTraffic(UUID player) {
        long now = System.nanoTime();
        TrafficWindow window = traffic.computeIfAbsent(player, ignored -> new TrafficWindow(now));
        if (now - window.startedAt >= 1_000_000_000L) {
            window.startedAt = now;
            window.packets = 0;
        }
        return ++window.packets <= MAX_PACKETS_PER_SECOND;
    }

    private static Inbound decode(byte[] bytes) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8", exception);
        }
        int protocol = -1;
        String type = "";
        String version = "unspecified";
        String action = "";
        String argument = "";
        Set<String> fields = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!fields.add(key) || fields.size() > MAX_FIELDS) {
                    throw new IllegalArgumentException("Duplicate or excessive fields");
                }
                switch (key) {
                    case "protocol" -> {
                        if (reader.peek() != JsonToken.NUMBER) {
                            throw new IllegalArgumentException("Protocol must be an integer");
                        }
                        String number = reader.nextString();
                        if (number.length() > 12) {
                            throw new IllegalArgumentException("Protocol value is too long");
                        }
                        protocol = new BigDecimal(number).intValueExact();
                    }
                    case "type" -> type = readString(reader, 32);
                    case "clientVersion" -> version = readString(reader, 64);
                    case "action" -> action = readString(reader, 16);
                    case "argument" -> argument = readString(reader, 128);
                    case "capabilities" -> {
                        reader.beginArray();
                        Set<String> capabilities = new HashSet<>();
                        while (reader.hasNext()) {
                            String capability = readString(reader, 32);
                            if (!capability.equals("state_sync") || !capabilities.add(capability)) {
                                throw new IllegalArgumentException("Unsupported capability");
                            }
                        }
                        reader.endArray();
                    }
                    default -> throw new IllegalArgumentException("Unknown field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !fields.containsAll(Set.of("protocol", "type"))) {
                throw new IllegalArgumentException("Incomplete or trailing payload");
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Protocol must be an integer", exception);
        }
        Set<String> allowed;
        switch (type) {
            case "hello" -> allowed = Set.of("protocol", "type", "clientVersion", "capabilities");
            case "snapshot_request" -> allowed = Set.of("protocol", "type");
            case "request" -> {
                allowed = Set.of("protocol", "type", "action", "argument");
                if (!ACTIONS.contains(action) || (action.equals("play")
                        ? !ANIMATION_NAME.matcher(argument).matches() : !argument.isEmpty())) {
                    throw new IllegalArgumentException("Invalid action request");
                }
            }
            default -> throw new IllegalArgumentException("Unknown message type");
        }
        if (!allowed.containsAll(fields)) {
            throw new IllegalArgumentException("Field is not allowed for this message type");
        }
        return new Inbound(protocol, type, version, action, argument);
    }

    private static String readString(JsonReader reader, int maxLength) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw new IllegalArgumentException("Expected string");
        }
        String value = reader.nextString();
        if (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid string value");
        }
        return value;
    }

    private static JsonObject envelope(String type) {
        JsonObject message = new JsonObject();
        message.addProperty("protocol", PROTOCOL);
        message.addProperty("type", type);
        return message;
    }

    private static long currentTick() {
        return Integer.toUnsignedLong(Bukkit.getCurrentTick());
    }

    private static long tickDistance(long current, long previous) {
        return (current - previous) & 0xffff_ffffL;
    }

    public record StateSnapshot(UUID owner, UUID instance, String modelId, long sequence,
                                long serverTick, List<LayerState> layers) {
        public StateSnapshot {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(modelId, "modelId");
            layers = List.copyOf(layers);
        }
    }

    public record LayerState(String layer, String animation, long startedAtTick, double speed,
                             String loop, int inTicks, int outTicks) {
        public LayerState {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(animation, "animation");
            Objects.requireNonNull(loop, "loop");
            if (!Double.isFinite(speed) || speed <= 0 || inTicks < 0 || outTicks < 0) {
                throw new IllegalArgumentException("Invalid animation playback parameters");
            }
        }
    }

    public record ActionRequest(Player player, String action, String argument) {}

    private record Inbound(int protocol, String type, String clientVersion, String action, String argument) {}
    private record BoundState(UUID instance, long sequence) {}

    private static final class Session {
        private final Map<UUID, BoundState> bindings = new HashMap<>();
        private String clientVersion = "unspecified";
        private long lastRequestTick;
        private long lastHelloAt;
        private long snapshotId;
        private boolean hasRequested;
    }

    private static final class TrafficWindow {
        private long startedAt;
        private int packets;

        private TrafficWindow(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
