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
        if (interval < 1) {
            throw new IllegalArgumentException("Scan interval must be positive");
        }

        this.interval = interval;
    }

    List<T> next(long now, Supplier<? extends Collection<? extends T>> source) {
        if (now == this.lastTick) {
            return List.of();
        }

        if (now < this.lastTick || now >= this.nextCycle) {
            this.roster = List.copyOf(source.get());
            this.cursor = 0;
            this.step = 0;
            this.nextCycle = now + this.interval;
        }
        this.lastTick = now;
        if (this.step >= this.interval) {
            return List.of();
        }

        int target = (int) (((long) this.roster.size() * ++this.step + this.interval - 1) / this.interval);
        List<T> batch = this.roster.subList(this.cursor, target);
        this.cursor = target;
        if (this.cursor == this.roster.size()) {
            this.roster = List.of();
            this.cursor = 0;
            this.step = this.interval;
        }
        return batch;
    }

    void clear() {
        this.roster = List.of();
        this.cursor = this.step = 0;
        this.nextCycle = this.lastTick = Long.MIN_VALUE;
    }
}
