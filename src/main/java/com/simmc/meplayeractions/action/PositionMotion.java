package com.simmc.meplayeractions.action;

import java.util.Objects;
import java.util.UUID;

/** Real position deltas, normalized to a server tick rather than Bukkit's retained velocity. */
public final class PositionMotion {
    public record Sample(double x, double y, double z, double yawSpeed, boolean discontinuity) {
        public double groundSpeed() { return Math.hypot(x, z) * 20; }
        public double verticalSpeed() { return y * 20; }
    }
    private static final Sample RESET = new Sample(0, 0, 0, 0, true);
    private UUID world;
    private double x, y, z, yaw;
    private long tick;
    private Sample current = RESET;

    public Sample sample(UUID world, double x, double y, double z, double yaw, long tick) {
        Objects.requireNonNull(world);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(yaw))
            throw new IllegalArgumentException("Non-finite motion sample");
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        if (this.world == null || !this.world.equals(world) || tick < this.tick || tick - this.tick > 100
                || dx * dx + dy * dy + dz * dz > 16) {
            current = RESET;
        } else if (tick == this.tick) {
            // Repeated commands in one tick cannot erase movement or move the baseline.
            return current;
        } else {
            double elapsed = tick - this.tick;
            double turn = yaw - this.yaw;
            turn -= Math.floor((turn + 180) / 360) * 360;
            current = new Sample(dx / elapsed, dy / elapsed, dz / elapsed, turn * 20 / elapsed, false);
        }
        this.world = world; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.tick = tick;
        return current;
    }
    public void reset() { world = null; current = RESET; }
}
