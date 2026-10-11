package com.simmc.meplayeractions.client;

/** Monotonic wall time keeps leases expiring even when the client world is paused. */
public final class ServerClock {
    private double anchorTick;
    private long anchorNanos;
    private boolean initialized;

    public void observe(long tick, long now) {
        tick = unwrap(tick);
        if (!this.initialized) {
            this.anchorTick = tick;
            this.anchorNanos = now;
            this.initialized = true;
            return;
        }
        double estimated = estimate(now), error = tick - estimated;
        this.anchorTick = Math.abs(error) > 10 ? tick : estimated + Math.max(-1, Math.min(1, error * 0.15));
        this.anchorNanos = now;
    }

    public double estimate(long now) {
        return this.anchorTick + (now - this.anchorNanos) / 50_000_000.0;
    }

    public long unwrap(long tick) {
        if (!this.initialized) {
            return tick;
        }

        long cycle = 1L << 32, reference = (long) this.anchorTick;
        long candidate = (reference & ~0xffff_ffffL) | tick;
        if (candidate - reference > cycle / 2) {
            candidate -= cycle;
        }

        else if (reference - candidate > cycle / 2) {
            candidate += cycle;
        }

        return candidate;
    }

    public void reset() {
        this.initialized = false;
        this.anchorTick = 0;
        this.anchorNanos = 0;
    }
}
