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
final class ConnectionLimits
{
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
    private long peakQueuedBytes;
    private int peakTransfers;
    private volatile int pendingInboundPackets;
    private volatile long pendingInboundBytes;
    private long globalTick;
    private boolean tickInitialized;

    void configure(ResourceSettings.Network value)
    {
        this.policy = Objects.requireNonNull(value);
        this.upload.reset();
        this.download.reset();
        this.assetDownload.reset();
        this.incomingPackets.reset();
        this.outgoingPackets.reset();
        this.outgoingAssetPackets.reset();
        this.decodeBytes.reset();
        this.decodePackets.reset();
    }

    void writability(Predicate<UUID> value)
    {
        this.writable = Objects.requireNonNull(value);
    }

    void assetFactor(DoubleSupplier value)
    {
        this.assetFactor = Objects.requireNonNull(value);
    }

    /** Foreign-thread callbacks may not create an unlimited Bukkit main-thread queue. */
    synchronized boolean reserveInboundDispatch(int bytes)
    {
        int count = Math.max(16, Math.min(1024, this.policy.waitingTransfers() * 2));
        long cap = Math.max(256 * 1024L, Math.min(16 * 1024 * 1024L, this.policy.maxQueuedOutgoingBytes()));
        if (bytes <= 0 || this.pendingInboundPackets >= count || bytes > cap - this.pendingInboundBytes)
        {
            return false;
        }

        this.pendingInboundPackets++;
        this.pendingInboundBytes += bytes;
        return true;
    }

    synchronized void releaseInboundDispatch(int bytes)
    {
        this.pendingInboundPackets = Math.max(0, this.pendingInboundPackets - 1);
        this.pendingInboundBytes = Math.max(0, this.pendingInboundBytes - Math.max(0, bytes));
    }

    boolean allowInbound(UUID viewer, int bytes, long now)
    {
        if (bytes <= 0)
        {
            return false;
        }

        Window window = connection(viewer).inbound;
        window.roll(now);
        if (!inboundAvailable(window, bytes, now))
        {
            this.rejectedPackets++;
            return false;
        }
        chargeInbound(window, bytes, now);
        this.uploadedBytes += bytes;
        this.incomingRate.record(bytes, now);
        return true;
    }

    /** CPU admission is independent of upload credit, and is charged even for malformed frames. */
    boolean allowInboundDecode(UUID viewer, int bytes, long now)
    {
        if (bytes <= 0)
        {
            return false;
        }

        Window window = connection(viewer).decode;
        window.roll(now);
        if (window.packets >= this.policy.packetsPerSecond()
                || bytes > this.policy.uploadBytesPerSecond() - window.bytes
                || !this.decodeBytes.available(bytes, this.policy.globalUploadBytesPerSecond(),
                        this.policy.burstBytes(), now)
                || !this.decodePackets.available(1, this.policy.globalPacketsPerSecond(),
                        this.policy.globalPacketsPerSecond(), now))
        {
            this.rejectedPackets++;
            return false;
        }
        window.packets++;
        window.bytes += bytes;
        this.decodeBytes.take(bytes);
        this.decodePackets.take(1);
        return true;
    }

    /** Credit charges the complete maximum wire packet before the client is allowed to upload it. */
    boolean grantUploadCredit(UUID viewer, int maxWireBytes, long now)
    {
        if (maxWireBytes <= 0)
        {
            return false;
        }

        Connection connection = connection(viewer);
        Window window = connection.inbound;
        window.roll(now);
        if (connection.uploadCredits.size() >= 4 || !inboundAvailable(window, maxWireBytes, now))
        {
            return false;
        }

        chargeInbound(window, maxWireBytes, now);
        connection.uploadCredits.addLast(maxWireBytes);
        return true;
    }

    boolean allowInboundCredited(UUID viewer, int wireBytes, long now)
    {
        Connection connection = connection(viewer);
        Integer credit = connection.uploadCredits.peekFirst();
        if (wireBytes <= 0 || credit == null || wireBytes > credit)
        {
            this.rejectedPackets++;
            return false;
        }
        connection.uploadCredits.removeFirst();
        this.uploadedBytes += wireBytes;
        this.incomingRate.record(wireBytes, now);
        return true;
    }

    void clearUploadCredits(UUID viewer)
    {
        Connection connection = this.connections.get(viewer);
        if (connection != null)
        {
            connection.uploadCredits.clear();
        }
    }

    private boolean inboundAvailable(Window window, int bytes, long now)
    {
        return window.packets < this.policy.packetsPerSecond()
                && bytes <= this.policy.uploadBytesPerSecond() - window.bytes
                && this.upload.available(bytes, this.policy.globalUploadBytesPerSecond(), this.policy.burstBytes(), now)
                && this.incomingPackets.available(1, this.policy.globalPacketsPerSecond(),
                        this.policy.globalPacketsPerSecond(), now);
    }

    private void chargeInbound(Window window, int bytes, long now)
    {
        window.packets++;
        window.bytes += bytes;
        this.upload.take(bytes);
        this.incomingPackets.take(1);
    }

    boolean allowHello(UUID viewer, long now)
    {
        Connection connection = connection(viewer);
        if (connection.helloInitialized && now - connection.lastHelloAt < SECOND_NANOS)
        {
            return false;
        }

        connection.helloInitialized = true;
        connection.lastHelloAt = now;
        return true;
    }

    boolean allowRequest(UUID viewer, String type, String action, long tick, int cooldownTicks)
    {
        return connection(viewer).requests.allow(type, action, tick, cooldownTicks);
    }

    /** Records an accepted transfer, including one subsequently cancelled by a new handshake. */
    boolean allowAsset(UUID viewer, String hash, long tick)
    {
        Objects.requireNonNull(hash);
        Connection connection = connection(viewer);
        connection.assetRequests.entrySet()
                .removeIf(entry -> tickDistance(tick, entry.getValue()) >= ASSET_COOLDOWN_TICKS);
        if (connection.assetRequests.containsKey(hash))
        {
            return false;
        }

        connection.assetRequests.put(hash, tick);
        return true;
    }

    /** A new hello cannot reset offer pacing or the attempts for an unchanged server instance. */
    boolean canPushOffer(UUID viewer, String hash, Set<UUID> instances, long tick)
    {
        Connection connection = connection(viewer);
        if (connection.offerInitialized && tickDistance(tick, connection.lastOfferTick) < PUSH_OFFER_INTERVAL_TICKS)
        {
            return false;
        }

        PushAttempt previous = connection.pushAttempts.get(hash);
        return previous == null || tickDistance(tick, previous.lastTick) >= ASSET_COOLDOWN_TICKS
                && (Collections.disjoint(previous.instances, instances) || previous.attempts < PUSH_MAX_ATTEMPTS);
    }

    void pushOfferSent(UUID viewer, String hash, Set<UUID> instances, long tick)
    {
        Connection connection = connection(viewer);
        PushAttempt previous = connection.pushAttempts.remove(hash);
        int attempts = previous != null && !Collections.disjoint(previous.instances, instances) ? previous.attempts + 1
                : 1;
        connection.pushAttempts.put(hash, new PushAttempt(instances, tick, attempts, true));
        while (connection.pushAttempts.size() > PUSH_MAX_RECORDS)

        {
            connection.pushAttempts.remove(connection.pushAttempts.keySet().iterator().next());
        }

        connection.offerInitialized = true;
        connection.lastOfferTick = tick;
    }

    /** Refunds this pending offer once when a negotiated viewer pauses; keeps failures and pacing. */
    void cancelPausedPushOffer(UUID viewer, String hash, Set<UUID> instances, long issuedTick)
    {
        Connection connection = this.connections.get(viewer);
        if (connection == null)
        {
            return;
        }

        PushAttempt previous = connection.pushAttempts.get(hash);
        if (previous == null || !previous.cancellable || previous.attempts <= 0 || previous.lastTick != issuedTick
                || !previous.instances.equals(instances))
        {
            return;
        }

        connection.pushAttempts.put(hash,
                new PushAttempt(previous.instances, previous.lastTick, previous.attempts - 1, false));
    }

    /** Only an authorized render_ready resets failure attempts; cached feedback cannot do it. */
    void pushRenderReady(UUID viewer, String hash)
    {
        Connection connection = connection(viewer);
        PushAttempt previous = connection.pushAttempts.get(hash);
        if (previous != null)
        {
            connection.pushAttempts.put(hash, new PushAttempt(previous.instances, previous.lastTick, 0, false));
        }

    }

    boolean reserveTransfer(UUID viewer)
    {
        Connection connection = connection(viewer);
        if (connection.transfers >= this.policy.transfersPerPlayer()
                || this.activeTransfers >= this.policy.globalTransfers())
        {
            return false;
        }

        connection.transfers++;
        this.activeTransfers++;
        this.peakTransfers = Math.max(this.peakTransfers, this.activeTransfers);
        return true;
    }

    boolean reserveWaiting(UUID viewer)
    {
        Connection connection = connection(viewer);
        if (connection.waiting >= this.policy.transfersPerPlayer()
                || this.waitingTransfers >= this.policy.waitingTransfers())
        {
            return false;
        }

        connection.waiting++;
        this.waitingTransfers++;
        return true;
    }

    void releaseWaiting(UUID viewer)
    {
        Connection connection = this.connections.get(viewer);
        if (connection != null && connection.waiting > 0)
        {
            connection.waiting--;
            this.waitingTransfers--;
        }
    }

    void releaseWaitingTransfers(UUID viewer)
    {
        Connection connection = this.connections.get(viewer);
        if (connection != null)
        {
            this.waitingTransfers -= connection.waiting;
            connection.waiting = 0;
        }
    }

    boolean queueOutgoing(UUID viewer, int bytes)
    {
        if (!canQueueOutgoing(viewer, bytes))
        {
            return false;
        }

        connection(viewer).queuedBytes += bytes;
        this.queuedBytes += bytes;
        this.peakQueuedBytes = Math.max(this.peakQueuedBytes, this.queuedBytes);
        return true;
    }

    boolean canQueueOutgoing(UUID viewer, int bytes)
    {
        return bytes > 0 && bytes <= this.policy.maxQueuedOutgoingBytes() - this.queuedBytes;
    }

    void releaseOutgoing(UUID viewer, int bytes)
    {
        Connection connection = this.connections.get(viewer);
        if (connection != null)
        {
            long released = Math.min(connection.queuedBytes, Math.max(0, bytes));
            connection.queuedBytes -= released;
            this.queuedBytes -= released;
        }
    }

    void transferTimedOut()
    {
        this.timedOutTransfers++;
    }

    int transferQueueTicks()
    {
        return this.policy.transferQueueTicks();
    }

    int transferIdleTicks()
    {
        return this.policy.transferIdleTicks();
    }

    int transferTotalTicks()
    {
        return this.policy.transferTotalTicks();
    }

    int readyTicks()
    {
        return this.policy.readyTicks();
    }

    int waitingLimit()
    {
        return this.policy.waitingTransfers();
    }

    int transferLimit()
    {
        return this.policy.globalTransfers();
    }

    long metric(String name)
    {
        return switch (name)
        {
        case "connections" -> this.connections.size();
        case "transfers" -> this.activeTransfers;
        case "transfers.limit" -> this.policy.globalTransfers();
        case "waiting" -> this.waitingTransfers;
        case "waiting.limit" -> this.policy.waitingTransfers();
        case "queuedBytes" -> this.queuedBytes;
        case "queuedBytes.limit" -> this.policy.maxQueuedOutgoingBytes();
        case "queuedBytes.peak" -> this.peakQueuedBytes;
        case "uploadBytes" -> this.uploadedBytes;
        case "downloadBytes" -> this.downloadedBytes;
        case "uploadBytesPerSecond" -> this.incomingRate.value(System.nanoTime());
        case "downloadBytesPerSecond" -> this.outgoingRate.value(System.nanoTime());
        case "uploadBytesPerSecond.limit" -> this.policy.globalUploadBytesPerSecond();
        case "downloadBytesPerSecond.limit" -> this.policy.globalDownloadBytesPerSecond();
        case "packetsPerSecond.limit" -> this.policy.globalPacketsPerSecond();
        case "assetPacketsPerSecond.limit" -> assetPacketQuota(this.policy.globalPacketsPerSecond());
        case "rejected" -> this.rejectedPackets;
        case "deferred" -> this.deferredPackets;
        case "timeouts" -> this.timedOutTransfers;
        case "transfers.peak" -> this.peakTransfers;
        case "ingressCallbacks" -> this.pendingInboundPackets;
        case "ingressBytes" -> this.pendingInboundBytes;
        default -> throw new IllegalArgumentException("Unknown network metric");
        };
    }

    void releaseTransfer(UUID viewer)
    {
        Connection connection = this.connections.get(viewer);
        if (connection != null && connection.transfers > 0)
        {
            connection.transfers--;
            this.activeTransfers--;
        }
    }

    /** Releases session work without erasing connection traffic, hello or asset cooldowns. */
    void releaseTransfers(UUID viewer)
    {
        Connection connection = this.connections.get(viewer);
        if (connection != null)
        {
            this.activeTransfers -= connection.transfers;
            connection.transfers = 0;
        }
    }

    /** Checks every budget before charging any bytes, so a deferred packet consumes nothing. */
    boolean allowOutbound(UUID viewer, int bytes, boolean asset, long now, long tick)
    {
        if (!canOutbound(viewer, bytes, asset, now, tick))
        {
            this.deferredPackets++;
            return false;
        }
        Window window = connection(viewer).outbound;
        window.bytes += bytes;
        window.packets++;
        this.globalBytes += bytes;
        this.download.take(bytes);
        this.outgoingPackets.take(1);
        this.downloadedBytes += bytes;
        this.outgoingRate.record(bytes, now);
        if (asset)
        {
            window.assetBytes += bytes;
            window.assetPackets++;
            this.globalAssetBytes += bytes;
            this.assetDownload.take(bytes);
            this.outgoingAssetPackets.take(1);
        }
        return true;
    }

    /** Cheap preflight before slicing, Base64 encoding or constructing a large JSON packet. */
    boolean canOutbound(UUID viewer, int bytes, boolean asset, long now, long tick)
    {
        if (bytes <= 0)
        {
            return false;
        }

        double factor = asset ? Math.max(0, Math.min(1, this.assetFactor.getAsDouble())) : 1;
        if (asset && factor <= 0)
        {
            return false;
        }

        Window window = connection(viewer).outbound;
        window.roll(now);
        if (!this.tickInitialized || this.globalTick != tick)
        {
            this.tickInitialized = true;
            this.globalTick = tick;
            this.globalBytes = this.globalAssetBytes = 0;
        }
        return window.packets < this.policy.packetsPerSecond()
                && bytes <= this.policy.downloadBytesPerSecond() - window.bytes
                && bytes <= this.policy.bytesPerTick() - this.globalBytes
                && this.download.available(bytes, this.policy.globalDownloadBytesPerSecond(), this.policy.burstBytes(),
                        now)
                && this.outgoingPackets.available(1, this.policy.globalPacketsPerSecond(),
                        this.policy.globalPacketsPerSecond(), now)
                && (!asset || window.assetPackets < assetPacketQuota(this.policy.packetsPerSecond())
                        && this.outgoingAssetPackets.available(1,
                                assetPacketQuota(this.policy.globalPacketsPerSecond()),
                                assetPacketQuota(this.policy.globalPacketsPerSecond()), now)
                        && this.writable.test(viewer)
                        && bytes <= this.policy.assetDownloadBytesPerSecond() - window.assetBytes
                        && bytes <= (long) (this.policy.assetBytesPerTick() * factor) - this.globalAssetBytes
                        && this.assetDownload.available(bytes,
                                (long) (this.policy.globalDownloadBytesPerSecond() / 2 * factor),
                                this.policy.burstBytes(), now));
    }

    /** At least one and at least a quarter of packet capacity remain available to ACK/revoke/cleanup. */
    private static int assetPacketQuota(int total)
    {
        return Math.max(1, total - Math.max(1, (total + 3) / 4));
    }

    String status(long now)
    {
        return "连接 " + this.connections.size() + "；活动传输 " + this.activeTransfers + "/" + this.policy.globalTransfers()
                + "；等待 " + this.waitingTransfers + "/" + this.policy.waitingTransfers() + "；待发 " + this.queuedBytes
                + "/" + this.policy.maxQueuedOutgoingBytes() + " B（峰值 " + this.peakQueuedBytes + "）；累计上下行 "
                + this.uploadedBytes + "/" + this.downloadedBytes + " B；限流拒绝/延后 " + this.rejectedPackets + "/"
                + this.deferredPackets + "；超时 " + this.timedOutTransfers + "；传输峰值 " + this.peakTransfers + "；入站回调 "
                + this.pendingInboundPackets + "（" + this.pendingInboundBytes + " B）";
    }

    /** Only call for an actual disconnect/offline player, never for protocol/session errors. */
    void forget(UUID viewer)
    {
        Connection removed = this.connections.remove(viewer);
        if (removed != null)
        {
            this.activeTransfers -= removed.transfers;
            this.waitingTransfers -= removed.waiting;
            this.queuedBytes -= removed.queuedBytes;
        }
    }

    void pruneOffline(Predicate<UUID> online)
    {
        Objects.requireNonNull(online);
        Iterator<Map.Entry<UUID, Connection>> iterator = this.connections.entrySet().iterator();
        while (iterator.hasNext())
        {
            Map.Entry<UUID, Connection> entry = iterator.next();
            if (!online.test(entry.getKey()))
            {
                this.activeTransfers -= entry.getValue().transfers;
                this.waitingTransfers -= entry.getValue().waiting;
                this.queuedBytes -= entry.getValue().queuedBytes;
                iterator.remove();
            }
        }
    }

    private Connection connection(UUID viewer)
    {
        return this.connections.computeIfAbsent(Objects.requireNonNull(viewer), ignored -> new Connection());
    }

    private static long tickDistance(long current, long previous)
    {
        return (current - previous) & 0xffffffffL;
    }

    private static final class Connection
    {
        final Window inbound = new Window(), outbound = new Window(), decode = new Window();
        final RequestCooldown requests = new RequestCooldown();
        final Map<String, Long> assetRequests = new HashMap<>();
        final Map<String, PushAttempt> pushAttempts = new LinkedHashMap<>();
        final Deque<Integer> uploadCredits = new ArrayDeque<>();
        long lastHelloAt, lastOfferTick;
        boolean helloInitialized, offerInitialized;
        int transfers, waiting;
        long queuedBytes;
    }

    private static final class Bucket
    {
        double tokens;
        long at;
        boolean initialized;

        boolean available(long bytes, long rate, long burst, long now)
        {
            long capacity = Math.max(1, burst);
            if (!this.initialized)
            {
                this.initialized = true;
                this.tokens = capacity;
                this.at = now;
            }
            else
            {
                long elapsed = now - this.at;
                if (elapsed > 0)
                {
                    this.tokens = Math.min(capacity, this.tokens + elapsed / (double) SECOND_NANOS * rate);
                }

                this.at = now;
            }
            return bytes <= this.tokens;
        }

        void take(long bytes)
        {
            this.tokens -= bytes;
        }

        void reset()
        {
            this.initialized = false;
            this.tokens = 0;
        }
    }

    private static final class Rate
    {
        long at, current, previous;
        boolean initialized;

        void record(long bytes, long now)
        {
            roll(now);
            this.current += bytes;
        }

        long value(long now)
        {
            roll(now);
            return this.previous;
        }

        private void roll(long now)
        {
            if (!this.initialized)
            {
                this.initialized = true;
                this.at = now;
            }
            long elapsed = now - this.at;
            if (elapsed >= SECOND_NANOS)
            {
                this.previous = elapsed < SECOND_NANOS * 2 ? this.current : 0;
                this.current = 0;
                this.at = now;
            }
        }
    }

    private record PushAttempt(Set<UUID> instances, long lastTick, int attempts, boolean cancellable)
    {
        private PushAttempt
        {
            instances = Set.copyOf(instances);
        }
    }

    private static final class Window
    {
        long startedAt;
        int packets, assetPackets, bytes, assetBytes;
        boolean initialized;

        void roll(long now)
        {
            if (!this.initialized || now - this.startedAt >= SECOND_NANOS)
            {
                this.initialized = true;
                this.startedAt = now;
                this.packets = this.assetPackets = this.bytes = this.assetBytes = 0;
            }
        }
    }
}
