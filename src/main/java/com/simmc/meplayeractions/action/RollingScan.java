package com.simmc.meplayeractions.action;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** One roster per cycle, with bounded batches and no catch-up burst after a clock jump. */
final class RollingScan<T> {
    private final int interval;
    private List<T> roster = List.of();
    private long nextCycle = Long.MIN_VALUE, lastTick = Long.MIN_VALUE;
    private int cursor, step;

    RollingScan(int interval) {
        if (interval < 1) throw new IllegalArgumentException("Scan interval must be positive");
        this.interval = interval;
    }

    List<T> next(long now, Supplier<? extends Collection<? extends T>> source) {
        if (now == lastTick) return List.of();
        if (now < lastTick || now >= nextCycle) {
            roster = List.copyOf(source.get());
            cursor = 0; step = 0; nextCycle = now + interval;
        }
        lastTick = now;
        if (step >= interval) return List.of();
        int target = (int) (((long) roster.size() * ++step + interval - 1) / interval);
        List<T> batch = roster.subList(cursor, target);
        cursor = target;
        if (cursor == roster.size()) { roster = List.of(); cursor = 0; step = interval; }
        return batch;
    }

    void clear() { roster = List.of(); cursor = step = 0; nextCycle = lastTick = Long.MIN_VALUE; }
}
