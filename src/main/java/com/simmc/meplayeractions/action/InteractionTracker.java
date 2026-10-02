package com.simmc.meplayeractions.action;

import java.util.Objects;
import java.util.UUID;

/** Event history, independent of posture sampling. Times are server ticks. */
public final class InteractionTracker {
    public record Target(UUID world, int x, int y, int z) {}
    private final int swingTicks, miningTimeout;
    private Target target;
    private long miningUntil, swingUntil;
    private long swingSequence;
    private boolean offhand;

    public InteractionTracker(int swingTicks, int miningTimeout) {
        this.swingTicks = swingTicks;
        this.miningTimeout = miningTimeout;
    }

    public void swing(boolean offhand, long tick) {
        this.offhand = offhand;
        swingSequence++;
        swingUntil = tick + swingTicks;
        if (!offhand && target != null && tick < miningUntil) miningUntil = tick + miningTimeout;
    }

    public void startMining(Target target, long tick) {
        this.target = Objects.requireNonNull(target);
        miningUntil = tick + miningTimeout;
        swingUntil = 0;
    }

    public void stopMining(Target target) {
        // A late abort for the previous block must not stop the next block.
        if (Objects.equals(this.target, target)) { this.target = null; swingUntil = 0; }
    }

    public Target target() { return target; }
    public long swingSequence() { return swingSequence; }

    public void validateTarget(Target observed) {
        if (target != null && !target.equals(observed)) { target = null; swingUntil = 0; }
    }

    public ActionState select(long tick, boolean miningEnabled, boolean swingEnabled) {
        if (target != null && tick >= miningUntil) { target = null; swingUntil = 0; }
        if (target != null) return miningEnabled ? ActionState.MINING : null;
        if (!swingEnabled || tick >= swingUntil) return null;
        return offhand ? ActionState.SWING_OFFHAND : ActionState.SWING_MAINHAND;
    }

    public void clear() { target = null; swingUntil = 0; }
}
