package com.simmc.meplayeractions.client;

import java.util.HashMap;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Main-thread connection budgets. A protocol session ending never resets these counters. */
final class ConnectionLimits {
    static final int INBOUND_PACKETS_PER_SECOND = 48;
    static final int INBOUND_BYTES_PER_SECOND = 256 * 1024;
    static final int OUTBOUND_BYTES_PER_SECOND = 2 * 1024 * 1024;
    static final int ASSET_BYTES_PER_SECOND = 512 * 1024;
    static final int GLOBAL_BYTES_PER_TICK = 512 * 1024;
    static final int GLOBAL_ASSET_BYTES_PER_TICK = 256 * 1024;
    static final int TRANSFERS_PER_CLIENT = 2;
    static final int GLOBAL_TRANSFERS = 32;
    static final int ASSET_COOLDOWN_TICKS = 100;
    static final int PUSH_OFFER_INTERVAL_TICKS = 10, PUSH_MAX_ATTEMPTS = 3, PUSH_MAX_RECORDS = 64;
    private static final long SECOND_NANOS = 1_000_000_000L;

    private final Map<UUID, Connection> connections = new HashMap<>();
    private int activeTransfers, globalBytes, globalAssetBytes;
    private long globalTick;
    private boolean tickInitialized;

    boolean allowInbound(UUID viewer, int bytes, long now) {
        if (bytes <= 0) return false;
        Window window = connection(viewer).inbound;
        window.roll(now);
        if (window.packets >= INBOUND_PACKETS_PER_SECOND || bytes > INBOUND_BYTES_PER_SECOND - window.bytes) return false;
        window.packets++;
        window.bytes += bytes;
        return true;
    }

    boolean allowHello(UUID viewer, long now) {
        Connection connection = connection(viewer);
        if (connection.helloInitialized && now - connection.lastHelloAt < SECOND_NANOS) return false;
        connection.helloInitialized = true;
        connection.lastHelloAt = now;
        return true;
    }

    boolean allowRequest(UUID viewer, String type, String action, long tick, int cooldownTicks) {
        return connection(viewer).requests.allow(type, action, tick, cooldownTicks);
    }

    /** Records an accepted transfer, including one subsequently cancelled by a new handshake. */
    boolean allowAsset(UUID viewer, String hash, long tick) {
        Objects.requireNonNull(hash);
        Connection connection = connection(viewer);
        connection.assetRequests.entrySet().removeIf(entry -> tickDistance(tick, entry.getValue()) >= ASSET_COOLDOWN_TICKS);
        if (connection.assetRequests.containsKey(hash)) return false;
        connection.assetRequests.put(hash, tick);
        return true;
    }

    /** A new hello cannot reset offer pacing or the attempts for an unchanged server instance. */
    boolean canPushOffer(UUID viewer, String hash, Set<UUID> instances, long tick) {
        Connection connection = connection(viewer);
        if (connection.offerInitialized && tickDistance(tick, connection.lastOfferTick) < PUSH_OFFER_INTERVAL_TICKS) return false;
        PushAttempt previous = connection.pushAttempts.get(hash);
        return previous == null || tickDistance(tick, previous.lastTick) >= ASSET_COOLDOWN_TICKS
                && (Collections.disjoint(previous.instances, instances) || previous.attempts < PUSH_MAX_ATTEMPTS);
    }

    void pushOfferSent(UUID viewer, String hash, Set<UUID> instances, long tick) {
        Connection connection = connection(viewer);
        PushAttempt previous = connection.pushAttempts.remove(hash);
        int attempts = previous != null && !Collections.disjoint(previous.instances, instances) ? previous.attempts + 1 : 1;
        connection.pushAttempts.put(hash, new PushAttempt(instances, tick, attempts));
        while (connection.pushAttempts.size() > PUSH_MAX_RECORDS)
            connection.pushAttempts.remove(connection.pushAttempts.keySet().iterator().next());
        connection.offerInitialized = true; connection.lastOfferTick = tick;
    }

    /** Only an authorized render_ready resets failure attempts; cached feedback cannot do it. */
    void pushRenderReady(UUID viewer, String hash) {
        Connection connection = connection(viewer); PushAttempt previous = connection.pushAttempts.get(hash);
        if (previous != null) connection.pushAttempts.put(hash, new PushAttempt(previous.instances, previous.lastTick, 0));
    }

    boolean reserveTransfer(UUID viewer) {
        Connection connection = connection(viewer);
        if (connection.transfers >= TRANSFERS_PER_CLIENT || activeTransfers >= GLOBAL_TRANSFERS) return false;
        connection.transfers++;
        activeTransfers++;
        return true;
    }

    void releaseTransfer(UUID viewer) {
        Connection connection = connections.get(viewer);
        if (connection != null && connection.transfers > 0) {
            connection.transfers--;
            activeTransfers--;
        }
    }

    /** Releases session work without erasing connection traffic, hello or asset cooldowns. */
    void releaseTransfers(UUID viewer) {
        Connection connection = connections.get(viewer);
        if (connection != null) {
            activeTransfers -= connection.transfers;
            connection.transfers = 0;
        }
    }

    /** Checks every budget before charging any bytes, so a deferred packet consumes nothing. */
    boolean allowOutbound(UUID viewer, int bytes, boolean asset, long now, long tick) {
        if (bytes <= 0) return false;
        Window window = connection(viewer).outbound;
        window.roll(now);
        if (!tickInitialized || globalTick != tick) {
            tickInitialized = true;
            globalTick = tick;
            globalBytes = globalAssetBytes = 0;
        }
        if (bytes > OUTBOUND_BYTES_PER_SECOND - window.bytes || bytes > GLOBAL_BYTES_PER_TICK - globalBytes
                || asset && (bytes > ASSET_BYTES_PER_SECOND - window.assetBytes
                || bytes > GLOBAL_ASSET_BYTES_PER_TICK - globalAssetBytes)) return false;
        window.bytes += bytes;
        globalBytes += bytes;
        if (asset) {
            window.assetBytes += bytes;
            globalAssetBytes += bytes;
        }
        return true;
    }

    /** Only call for an actual disconnect/offline player, never for protocol/session errors. */
    void forget(UUID viewer) {
        Connection removed = connections.remove(viewer);
        if (removed != null) activeTransfers -= removed.transfers;
    }

    void pruneOffline(Predicate<UUID> online) {
        Objects.requireNonNull(online);
        Iterator<Map.Entry<UUID, Connection>> iterator = connections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Connection> entry = iterator.next();
            if (!online.test(entry.getKey())) {
                activeTransfers -= entry.getValue().transfers;
                iterator.remove();
            }
        }
    }

    private Connection connection(UUID viewer) { return connections.computeIfAbsent(Objects.requireNonNull(viewer), ignored -> new Connection()); }
    private static long tickDistance(long current, long previous) { return (current - previous) & 0xffffffffL; }
    private static final class Connection {
        final Window inbound = new Window(), outbound = new Window();
        final RequestCooldown requests = new RequestCooldown();
        final Map<String, Long> assetRequests = new HashMap<>();
        final Map<String, PushAttempt> pushAttempts = new LinkedHashMap<>();
        long lastHelloAt, lastOfferTick;
        boolean helloInitialized, offerInitialized;
        int transfers;
    }
    private record PushAttempt(Set<UUID> instances, long lastTick, int attempts) {
        private PushAttempt { instances = Set.copyOf(instances); }
    }
    private static final class Window {
        long startedAt;
        int packets, bytes, assetBytes;
        boolean initialized;
        void roll(long now) {
            if (!initialized || now - startedAt >= SECOND_NANOS) {
                initialized = true;
                startedAt = now;
                packets = bytes = assetBytes = 0;
            }
        }
    }
}
