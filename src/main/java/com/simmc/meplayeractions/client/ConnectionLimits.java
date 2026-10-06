package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.protection.ResourceSettings;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.DoubleSupplier;

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
    private volatile ResourceSettings.Network policy = ResourceSettings.defaults().network();
    private final Bucket upload = new Bucket(), download = new Bucket(), assetDownload = new Bucket();
    private final Bucket incomingPackets = new Bucket(), outgoingPackets = new Bucket();
    private final Bucket outgoingAssetPackets = new Bucket();
    private final Bucket decodeBytes = new Bucket(), decodePackets = new Bucket();
    private final Rate incomingRate = new Rate(), outgoingRate = new Rate();
    private Predicate<UUID> writable = ignored -> true;
    private DoubleSupplier assetFactor = () -> 1;
    private int activeTransfers, waitingTransfers, globalBytes, globalAssetBytes;
    private long queuedBytes, uploadedBytes, downloadedBytes, rejectedPackets, deferredPackets, timedOutTransfers;
    private long peakQueuedBytes; private int peakTransfers; private volatile int pendingInboundPackets; private volatile long pendingInboundBytes;
    private long globalTick;
    private boolean tickInitialized;

    void configure(ResourceSettings.Network value) {
        policy = Objects.requireNonNull(value);
        upload.reset(); download.reset(); assetDownload.reset(); incomingPackets.reset(); outgoingPackets.reset(); outgoingAssetPackets.reset(); decodeBytes.reset(); decodePackets.reset();
    }
    void writability(Predicate<UUID> value) { writable = Objects.requireNonNull(value); }
    void assetFactor(DoubleSupplier value) { assetFactor = Objects.requireNonNull(value); }

    /** Foreign-thread callbacks may not create an unlimited Bukkit main-thread queue. */
    synchronized boolean reserveInboundDispatch(int bytes) {
        int count = Math.max(16, Math.min(1024, policy.waitingTransfers() * 2));
        long cap = Math.max(256 * 1024L, Math.min(16 * 1024 * 1024L, policy.maxQueuedOutgoingBytes()));
        if (bytes <= 0 || pendingInboundPackets >= count || bytes > cap - pendingInboundBytes) return false;
        pendingInboundPackets++; pendingInboundBytes += bytes; return true;
    }
    synchronized void releaseInboundDispatch(int bytes) {
        pendingInboundPackets = Math.max(0, pendingInboundPackets - 1);
        pendingInboundBytes = Math.max(0, pendingInboundBytes - Math.max(0, bytes));
    }

    boolean allowInbound(UUID viewer, int bytes, long now) {
        if (bytes <= 0) return false;
        Window window = connection(viewer).inbound;
        window.roll(now);
        if (!inboundAvailable(window, bytes, now)) { rejectedPackets++; return false; }
        chargeInbound(window, bytes, now); uploadedBytes += bytes; incomingRate.record(bytes, now);
        return true;
    }

    /** CPU admission is independent of upload credit, and is charged even for malformed frames. */
    boolean allowInboundDecode(UUID viewer, int bytes, long now) {
        if (bytes <= 0) return false;
        Window window = connection(viewer).decode; window.roll(now);
        if (window.packets >= policy.packetsPerSecond() || bytes > policy.uploadBytesPerSecond() - window.bytes
                || !decodeBytes.available(bytes, policy.globalUploadBytesPerSecond(), policy.burstBytes(), now)
                || !decodePackets.available(1, policy.globalPacketsPerSecond(), policy.globalPacketsPerSecond(), now)) { rejectedPackets++; return false; }
        window.packets++; window.bytes += bytes; decodeBytes.take(bytes); decodePackets.take(1); return true;
    }
    /** Credit charges the complete maximum wire packet before the client is allowed to upload it. */
    boolean grantUploadCredit(UUID viewer, int maxWireBytes, long now) {
        if (maxWireBytes <= 0) return false;
        Connection connection = connection(viewer); Window window = connection.inbound; window.roll(now);
        if (connection.uploadCredits.size() >= 4 || !inboundAvailable(window, maxWireBytes, now)) return false;
        chargeInbound(window, maxWireBytes, now); connection.uploadCredits.addLast(maxWireBytes); return true;
    }
    boolean allowInboundCredited(UUID viewer, int wireBytes, long now) {
        Connection connection = connection(viewer); Integer credit = connection.uploadCredits.peekFirst();
        if (wireBytes <= 0 || credit == null || wireBytes > credit) { rejectedPackets++; return false; }
        connection.uploadCredits.removeFirst(); uploadedBytes += wireBytes; incomingRate.record(wireBytes, now); return true;
    }
    void clearUploadCredits(UUID viewer) { Connection connection = connections.get(viewer); if (connection != null) connection.uploadCredits.clear(); }
    private boolean inboundAvailable(Window window, int bytes, long now) {
        return window.packets < policy.packetsPerSecond() && bytes <= policy.uploadBytesPerSecond() - window.bytes
                && upload.available(bytes, policy.globalUploadBytesPerSecond(), policy.burstBytes(), now)
                && incomingPackets.available(1, policy.globalPacketsPerSecond(), policy.globalPacketsPerSecond(), now);
    }
    private void chargeInbound(Window window, int bytes, long now) {
        window.packets++;
        window.bytes += bytes;
        upload.take(bytes); incomingPackets.take(1);
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
        if (connection.transfers >= policy.transfersPerPlayer() || activeTransfers >= policy.globalTransfers()) return false;
        connection.transfers++;
        activeTransfers++;
        peakTransfers = Math.max(peakTransfers, activeTransfers);
        return true;
    }

    boolean reserveWaiting(UUID viewer) {
        Connection connection = connection(viewer);
        if (connection.waiting >= policy.transfersPerPlayer() || waitingTransfers >= policy.waitingTransfers()) return false;
        connection.waiting++; waitingTransfers++; return true;
    }
    void releaseWaiting(UUID viewer) {
        Connection connection = connections.get(viewer);
        if (connection != null && connection.waiting > 0) { connection.waiting--; waitingTransfers--; }
    }
    void releaseWaitingTransfers(UUID viewer) {
        Connection connection = connections.get(viewer);
        if (connection != null) { waitingTransfers -= connection.waiting; connection.waiting = 0; }
    }
    boolean queueOutgoing(UUID viewer, int bytes) {
        if (!canQueueOutgoing(viewer, bytes)) return false;
        connection(viewer).queuedBytes += bytes; queuedBytes += bytes; peakQueuedBytes = Math.max(peakQueuedBytes, queuedBytes); return true;
    }
    boolean canQueueOutgoing(UUID viewer, int bytes) { return bytes > 0 && bytes <= policy.maxQueuedOutgoingBytes() - queuedBytes; }
    void releaseOutgoing(UUID viewer, int bytes) {
        Connection connection = connections.get(viewer);
        if (connection != null) { long released = Math.min(connection.queuedBytes, Math.max(0, bytes)); connection.queuedBytes -= released; queuedBytes -= released; }
    }
    void transferTimedOut() { timedOutTransfers++; }
    int transferQueueTicks() { return policy.transferQueueTicks(); }
    int transferIdleTicks() { return policy.transferIdleTicks(); }
    int transferTotalTicks() { return policy.transferTotalTicks(); }
    int readyTicks() { return policy.readyTicks(); }
    int waitingLimit() { return policy.waitingTransfers(); }
    int transferLimit() { return policy.globalTransfers(); }
    long metric(String name) {
        return switch (name) {
            case "connections" -> connections.size(); case "transfers" -> activeTransfers; case "transfers.limit" -> policy.globalTransfers();
            case "waiting" -> waitingTransfers; case "waiting.limit" -> policy.waitingTransfers();
            case "queuedBytes" -> queuedBytes; case "queuedBytes.limit" -> policy.maxQueuedOutgoingBytes(); case "queuedBytes.peak" -> peakQueuedBytes;
            case "uploadBytes" -> uploadedBytes; case "downloadBytes" -> downloadedBytes;
            case "uploadBytesPerSecond" -> incomingRate.value(System.nanoTime()); case "downloadBytesPerSecond" -> outgoingRate.value(System.nanoTime());
            case "uploadBytesPerSecond.limit" -> policy.globalUploadBytesPerSecond(); case "downloadBytesPerSecond.limit" -> policy.globalDownloadBytesPerSecond();
            case "packetsPerSecond.limit" -> policy.globalPacketsPerSecond(); case "assetPacketsPerSecond.limit" -> assetPacketQuota(policy.globalPacketsPerSecond());
            case "rejected" -> rejectedPackets; case "deferred" -> deferredPackets; case "timeouts" -> timedOutTransfers; case "transfers.peak" -> peakTransfers;
            case "ingressCallbacks" -> pendingInboundPackets; case "ingressBytes" -> pendingInboundBytes;
            default -> throw new IllegalArgumentException("Unknown network metric");
        };
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
        if (!canOutbound(viewer, bytes, asset, now, tick)) { deferredPackets++; return false; }
        Window window = connection(viewer).outbound;
        window.bytes += bytes; window.packets++; globalBytes += bytes;
        download.take(bytes); outgoingPackets.take(1); downloadedBytes += bytes; outgoingRate.record(bytes, now);
        if (asset) { window.assetBytes += bytes; window.assetPackets++; globalAssetBytes += bytes; assetDownload.take(bytes); outgoingAssetPackets.take(1); }
        return true;
    }
    /** Cheap preflight before slicing, Base64 encoding or constructing a large JSON packet. */
    boolean canOutbound(UUID viewer, int bytes, boolean asset, long now, long tick) {
        if (bytes <= 0) return false;
        double factor = asset ? Math.max(0, Math.min(1, assetFactor.getAsDouble())) : 1;
        if (asset && factor <= 0) return false;
        Window window = connection(viewer).outbound;
        window.roll(now);
        if (!tickInitialized || globalTick != tick) {
            tickInitialized = true;
            globalTick = tick;
            globalBytes = globalAssetBytes = 0;
        }
        return window.packets < policy.packetsPerSecond() && bytes <= policy.downloadBytesPerSecond() - window.bytes
                && bytes <= policy.bytesPerTick() - globalBytes
                && download.available(bytes, policy.globalDownloadBytesPerSecond(), policy.burstBytes(), now)
                && outgoingPackets.available(1, policy.globalPacketsPerSecond(), policy.globalPacketsPerSecond(), now)
                && (!asset || window.assetPackets < assetPacketQuota(policy.packetsPerSecond())
                && outgoingAssetPackets.available(1, assetPacketQuota(policy.globalPacketsPerSecond()), assetPacketQuota(policy.globalPacketsPerSecond()), now)
                && writable.test(viewer) && bytes <= policy.assetDownloadBytesPerSecond() - window.assetBytes
                && bytes <= (long) (policy.assetBytesPerTick() * factor) - globalAssetBytes
                && assetDownload.available(bytes, (long) (policy.globalDownloadBytesPerSecond() / 2 * factor), policy.burstBytes(), now));
    }
    /** At least one and at least a quarter of packet capacity remain available to ACK/revoke/cleanup. */
    private static int assetPacketQuota(int total) { return Math.max(1, total - Math.max(1, (total + 3) / 4)); }

    String status(long now) {
        return "连接 " + connections.size() + "；活动传输 " + activeTransfers + "/" + policy.globalTransfers()
                + "；等待 " + waitingTransfers + "/" + policy.waitingTransfers() + "；待发 " + queuedBytes + "/" + policy.maxQueuedOutgoingBytes()
                + " B（峰值 " + peakQueuedBytes + "）；累计上下行 " + uploadedBytes + "/" + downloadedBytes
                + " B；限流拒绝/延后 " + rejectedPackets + "/" + deferredPackets + "；超时 " + timedOutTransfers
                + "；传输峰值 " + peakTransfers + "；入站回调 " + pendingInboundPackets + "（" + pendingInboundBytes + " B）";
    }

    /** Only call for an actual disconnect/offline player, never for protocol/session errors. */
    void forget(UUID viewer) {
        Connection removed = connections.remove(viewer);
        if (removed != null) { activeTransfers -= removed.transfers; waitingTransfers -= removed.waiting; queuedBytes -= removed.queuedBytes; }
    }

    void pruneOffline(Predicate<UUID> online) {
        Objects.requireNonNull(online);
        Iterator<Map.Entry<UUID, Connection>> iterator = connections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Connection> entry = iterator.next();
            if (!online.test(entry.getKey())) {
                activeTransfers -= entry.getValue().transfers;
                waitingTransfers -= entry.getValue().waiting; queuedBytes -= entry.getValue().queuedBytes;
                iterator.remove();
            }
        }
    }

    private Connection connection(UUID viewer) { return connections.computeIfAbsent(Objects.requireNonNull(viewer), ignored -> new Connection()); }
    private static long tickDistance(long current, long previous) { return (current - previous) & 0xffffffffL; }
    private static final class Connection {
        final Window inbound = new Window(), outbound = new Window(), decode = new Window();
        final RequestCooldown requests = new RequestCooldown();
        final Map<String, Long> assetRequests = new HashMap<>();
        final Map<String, PushAttempt> pushAttempts = new LinkedHashMap<>();
        final Deque<Integer> uploadCredits = new ArrayDeque<>();
        long lastHelloAt, lastOfferTick;
        boolean helloInitialized, offerInitialized;
        int transfers, waiting; long queuedBytes;
    }
    private static final class Bucket {
        double tokens; long at; boolean initialized;
        boolean available(long bytes, long rate, long burst, long now) {
            long capacity = Math.max(1, burst);
            if (!initialized) { initialized = true; tokens = capacity; at = now; }
            else {
                long elapsed = now - at;
                if (elapsed > 0) tokens = Math.min(capacity, tokens + elapsed / (double) SECOND_NANOS * rate);
                at = now;
            }
            return bytes <= tokens;
        }
        void take(long bytes) { tokens -= bytes; }
        void reset() { initialized = false; tokens = 0; }
    }
    private static final class Rate {
        long at, current, previous; boolean initialized;
        void record(long bytes, long now) { roll(now); current += bytes; }
        long value(long now) { roll(now); return previous; }
        private void roll(long now) {
            if (!initialized) { initialized = true; at = now; }
            long elapsed = now - at;
            if (elapsed >= SECOND_NANOS) { previous = elapsed < SECOND_NANOS * 2 ? current : 0; current = 0; at = now; }
        }
    }
    private record PushAttempt(Set<UUID> instances, long lastTick, int attempts) {
        private PushAttempt { instances = Set.copyOf(instances); }
    }
    private static final class Window {
        long startedAt;
        int packets, assetPackets, bytes, assetBytes;
        boolean initialized;
        void roll(long now) {
            if (!initialized || now - startedAt >= SECOND_NANOS) {
                initialized = true;
                startedAt = now;
                packets = assetPackets = bytes = assetBytes = 0;
            }
        }
    }
}
