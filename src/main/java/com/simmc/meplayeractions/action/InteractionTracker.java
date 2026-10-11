package com.simmc.meplayeractions.action;

import java.util.Objects;
import java.util.UUID;

/** Event history, independent of posture sampling. Times are server ticks. */
public final class InteractionTracker {
    public record Target(UUID world, int x, int y, int z) {
    }

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
        this.swingSequence++;
        this.swingUntil = tick + this.swingTicks;
        if (!offhand && this.target != null && tick < this.miningUntil) {
            this.miningUntil = tick + this.miningTimeout;
        }

    }

    public void startMining(Target target, long tick) {
        this.target = Objects.requireNonNull(target);
        this.miningUntil = tick + this.miningTimeout;
        this.swingUntil = 0;
    }

    public void stopMining(Target target) {
        // A late abort for the previous block must not stop the next block.
        if (Objects.equals(this.target, target)) {
            this.target = null;
            this.swingUntil = 0;
        }
    }

    public Target target() {
        return this.target;
    }

    public long swingSequence() {
        return this.swingSequence;
    }

    public void validateTarget(Target observed) {
        if (this.target != null && !this.target.equals(observed)) {
            this.target = null;
            this.swingUntil = 0;
        }
    }

    public ActionState select(long tick, boolean miningEnabled, boolean swingEnabled) {
        if (this.target != null && tick >= this.miningUntil) {
            this.target = null;
            this.swingUntil = 0;
        }
        if (this.target != null) {
            return miningEnabled ? ActionState.MINING : null;
        }

        if (!swingEnabled || tick >= this.swingUntil) {
            return null;
        }

        return this.offhand ? ActionState.SWING_OFFHAND : ActionState.SWING_MAINHAND;
    }

    public void clear() {
        this.target = null;
        this.swingUntil = 0;
    }
}
