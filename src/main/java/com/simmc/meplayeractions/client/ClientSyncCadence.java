package com.simmc.meplayeractions.client;

import java.util.UUID;

/** Unsigned server ticks with deterministic initial staggering and independent legacy cadence. */
final class ClientSyncCadence {
    static final int LEGACY_STATE_TICKS = 20, TIMELINE_TICKS = 2;
    static long initialTick(UUID viewer, long now, int interval) {
        return (now - Math.floorMod(viewer.hashCode(), Math.max(1, interval))) & 0xffffffffL;
    }
    static boolean due(long now, long previous, int interval) { return distance(now, previous) >= interval; }
    static long distance(long now, long previous) { return (now - previous) & 0xffffffffL; }
}
