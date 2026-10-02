package com.simmc.meplayeractions.client;

/** Monotonic wall time keeps leases expiring even when the client world is paused. */
public final class ServerClock {
    private double anchorTick;
    private long anchorNanos;
    private boolean initialized;
    public void observe(long tick, long now) {
        tick=unwrap(tick);
        if (!initialized) { anchorTick = tick; anchorNanos = now; initialized = true; return; }
        double estimated = estimate(now), error = tick - estimated;
        anchorTick = Math.abs(error) > 10 ? tick : estimated + Math.max(-1, Math.min(1, error * 0.15));
        anchorNanos = now;
    }
    public double estimate(long now) { return anchorTick + (now - anchorNanos) / 50_000_000.0; }
    public long unwrap(long tick) {
        if(!initialized)return tick;
        long cycle=1L<<32,reference=(long)anchorTick;
        long candidate=(reference&~0xffff_ffffL)|tick;
        if(candidate-reference>cycle/2)candidate-=cycle;
        else if(reference-candidate>cycle/2)candidate+=cycle;
        return candidate;
    }
    public void reset() { initialized = false; anchorTick = 0; anchorNanos = 0; }
}
