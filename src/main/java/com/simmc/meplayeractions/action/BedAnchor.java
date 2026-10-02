package com.simmc.meplayeractions.action;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Geometric bed surface, independent of which half the player clicked or slept on. */
public record BedAnchor(UUID world, double x, double y, double z, float bodyYaw) {
    public static BedAnchor of(UUID world, int x, int y, int z, Bed.Part part, BlockFace facing) {
        float yaw = switch (facing) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> -90;
            default -> throw new IllegalArgumentException("Bed direction must be horizontal");
        };
        double direction = part == Bed.Part.FOOT ? 0.5 : -0.5;
        return new BedAnchor(world, x + 0.5 + facing.getModX() * direction,
                y + 0.5625, z + 0.5 + facing.getModZ() * direction, yaw);
    }

    public static BedAnchor observe(Player player) {
        if (!player.isSleeping()) return null;
        try {
            var location = player.getBedLocation();
            if (location == null || !(location.getBlock().getBlockData() instanceof Bed bed)) return null;
            return of(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), bed.getPart(), bed.getFacing());
        } catch (IllegalStateException noLongerSleeping) { return null; }
    }
}
