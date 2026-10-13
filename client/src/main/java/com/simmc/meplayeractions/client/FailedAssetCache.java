package com.simmc.meplayeractions.client;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class FailedAssetCache {
    static final int CAPACITY = 256;
    static final long TTL = 60_000_000_000L;
    private final Map<String, Long> failures = new LinkedHashMap<>();
    private final Supplier<Set<String>> active;
    private final LongSupplier clock;
    private long lastPrune;
    private boolean pruneInitialized;

    FailedAssetCache(Supplier<Set<String>> active) {
        this(active, System::nanoTime);
    }

    FailedAssetCache(Supplier<Set<String>> active, LongSupplier clock) {
        this.active = active;
        this.clock = clock;
    }

    void put(String hash, long at) {
        if (hash == null || hash.isEmpty()) {
            return;
        }
        this.failures.remove(hash);
        this.failures.put(hash, at);
        if (this.failures.size() > CAPACITY) {
            this.failures.entrySet().removeIf(entry -> at - entry.getValue() >= TTL);
            Set<String> pinned = this.active.get();
            Iterator<String> candidates = this.failures.keySet().iterator();
            while (this.failures.size() > CAPACITY && candidates.hasNext()) {
                if (!pinned.contains(candidates.next())) {
                    candidates.remove();
                }
            }
            while (this.failures.size() > CAPACITY) {
                this.failures.remove(this.failures.keySet().iterator().next());
            }
        }
    }

    long getOrDefault(String hash, long fallback) {
        Long at = this.failures.get(hash);
        if (at != null && this.clock.getAsLong() - at >= TTL) {
            this.failures.remove(hash);
            at = null;
        }
        return at == null ? fallback : at;
    }

    boolean containsKey(String hash) {
        return getOrDefault(hash, Long.MIN_VALUE) != Long.MIN_VALUE;
    }

    boolean coolingDown(String hash, long now) {
        Long at = this.failures.get(hash);
        return at != null && now - at <= 30_000_000_000L;
    }

    void prune(long now) {
        if (!this.pruneInitialized || now - this.lastPrune >= 1_000_000_000L) {
            this.pruneInitialized = true;
            this.lastPrune = now;
            this.failures.entrySet().removeIf(entry -> now - entry.getValue() >= TTL);
        }
    }

    void remove(String hash) {
        this.failures.remove(hash);
    }

    void clear() {
        this.failures.clear();
        this.lastPrune = 0;
        this.pruneInitialized = false;
    }

    int size() {
        return this.failures.size();
    }
}
