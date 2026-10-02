package com.simmc.meplayeractions.action;

import java.util.UUID;

/** Divide by actual elapsed ticks, including command-triggered or irregular samples. */
public final class MovementSampler {
    private UUID world;
    private double x, y, z;
    private long tick;
    private boolean moving;

    public boolean sample(UUID world, double x, double y, double z, long tick,
                          boolean vertical, boolean input, double threshold) {
        if (this.world != null && this.world.equals(world) && tick == this.tick) return moving || input;
        double speed = 0;
        if (this.world != null && this.world.equals(world) && tick > this.tick) {
            double distance = Math.hypot(x - this.x, z - this.z);
            if (vertical) distance = Math.max(distance, Math.abs(y - this.y));
            // Teleports are discontinuities, not walking or flying.
            if (distance < 8) speed = distance / (tick - this.tick);
        }
        this.world = world; this.x = x; this.y = y; this.z = z; this.tick = tick;
        moving = input || speed > threshold;
        return moving;
    }
}
