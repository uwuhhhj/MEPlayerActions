package com.simmc.meplayeractions.client;

import java.util.*;
import java.util.function.*;

/** Bounded, serialized-once control messages. Budget deferral never executes failure cleanup. */
final class DeferredClientPackets {
    enum Result { SENT, DEFERRED, FAILED }
    static final int CAPACITY = 128, RETENTION_TICKS = 100;
    private final LinkedHashMap<String, Packet> pending = new LinkedHashMap<>();
    boolean add(String key, byte[] payload, long tick, BooleanSupplier valid, Runnable sent, Runnable failed) {
        if (!pending.containsKey(key) && pending.size() >= CAPACITY) { failed.run(); return false; }
        pending.put(key, new Packet(payload, tick, valid, sent, failed)); return true;
    }
    void drain(Function<byte[], Result> sender, long tick, int maxAttempts) {
        int attempts = 0;
        for (String key : List.copyOf(pending.keySet())) {
            if (attempts++ >= maxAttempts) break;
            Packet packet = pending.get(key); if (packet == null) continue;
            if (!packet.valid.getAsBoolean() || ClientSyncCadence.distance(tick, packet.tick) >= RETENTION_TICKS) {
                pending.remove(key); packet.failed.run(); continue;
            }
            Result result = sender.apply(packet.payload);
            if (result == Result.DEFERRED) return;
            pending.remove(key); if (result == Result.SENT) packet.sent.run(); else packet.failed.run();
        }
    }
    int size() { return pending.size(); }
    void cancel(String key) { pending.remove(key); }
    void clear() { var values = List.copyOf(pending.values()); pending.clear(); values.forEach(packet -> packet.failed.run()); }
    private record Packet(byte[] payload, long tick, BooleanSupplier valid, Runnable sent, Runnable failed) {}
}
