package com.simmc.meplayeractions.gameplay;

import org.bukkit.Location;
import java.util.UUID;

/** GSit's seat origin includes an implementation offset; the model contacts its pose surface. */
public record GSitAnchor(UUID world, double x, double y, double z, float bodyYaw) {
    public static GSitAnchor of(Location seat, double baseOffset) {
        if (seat == null || seat.getWorld() == null || !Double.isFinite(baseOffset)) return null;
        return new GSitAnchor(seat.getWorld().getUID(), seat.getX(), seat.getY() + baseOffset,
                seat.getZ(), seat.getYaw());
    }
}
