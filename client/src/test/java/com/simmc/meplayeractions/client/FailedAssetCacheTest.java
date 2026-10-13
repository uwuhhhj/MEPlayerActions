package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class FailedAssetCacheTest {
    @Test
    void repeatedDistinctFailuresRemainBoundedAndActiveCooldownSurvivesChurn() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        FailedAssetCache failures = new FailedAssetCache(() -> Set.of("active"), clock::get);
        failures.put("active", clock.get());
        for (int number = 0; number < 4096; number++) {
            failures.put("unused" + number, clock.get());
        }
        assertEquals(FailedAssetCache.CAPACITY, failures.size());
        assertTrue(failures.containsKey("active"));
        clock.addAndGet(29_000_000_000L);
        assertTrue(clock.get() - failures.getOrDefault("active", 0) < 30_000_000_000L);
        clock.addAndGet(2_000_000_000L);
        assertTrue(clock.get() - failures.getOrDefault("active", 0) > 30_000_000_000L);
    }

    @Test
    void expirySuccessAndResetRemoveMetadataWithoutExtendingTheRetryWindow() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        FailedAssetCache failures = new FailedAssetCache(Set::of, clock::get);
        failures.put("expired", clock.get());
        failures.put("success", clock.get());
        failures.remove("success");
        assertFalse(failures.containsKey("success"));
        clock.addAndGet(FailedAssetCache.TTL);
        assertFalse(failures.containsKey("expired"));
        failures.put("old", clock.get());
        clock.addAndGet(FailedAssetCache.TTL);
        failures.prune(clock.get());
        assertEquals(0, failures.size());
        failures.put("reset", clock.get());
        failures.clear();
        assertEquals(0, failures.size());
    }

    @Test
    void pruningUsesElapsedTimeWhenNanoTimeOriginIsNegative() {
        AtomicLong clock = new AtomicLong(-300_000_000_000L);
        FailedAssetCache failures = new FailedAssetCache(Set::of, clock::get);
        assertFalse(failures.coolingDown("missing", clock.get()));
        failures.put("old", clock.get());
        assertTrue(failures.coolingDown("old", clock.get()));
        failures.prune(clock.get());
        clock.addAndGet(FailedAssetCache.TTL);
        assertFalse(failures.coolingDown("old", clock.get()));
        failures.prune(clock.get());
        assertEquals(0, failures.size());
    }

    @Test
    void latestFailureRenewsOnlyItsOwnTimestamp() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        FailedAssetCache failures = new FailedAssetCache(Set::of, clock::get);
        failures.put("a", clock.get());
        failures.put("b", clock.get());
        clock.addAndGet(20_000_000_000L);
        failures.put("b", clock.get());
        clock.addAndGet(41_000_000_000L);
        assertFalse(failures.containsKey("a"));
        assertTrue(failures.containsKey("b"));
    }
}
