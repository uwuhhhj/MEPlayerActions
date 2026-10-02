package com.simmc.meplayeractions.action;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class BedAnchorTest {
    @Test void eitherBedHalfProducesTheSameSurfaceCenterInAllFourDirections() {
        UUID world = UUID.randomUUID();
        for (BlockFace facing : new BlockFace[]{BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST}) {
            var foot = BedAnchor.of(world, -10, -60, 20, Bed.Part.FOOT, facing);
            var head = BedAnchor.of(world, -10 + facing.getModX(), -60, 20 + facing.getModZ(), Bed.Part.HEAD, facing);
            assertEquals(foot, head);
            assertEquals(-59.4375, foot.y());
            assertEquals(-9.5 + facing.getModX() * .5, foot.x());
            assertEquals(20.5 + facing.getModZ() * .5, foot.z());
            // Minecraft body yaw maps local Blockbench -Z towards the bed's head.
            assertEquals(facing.getModX(), -Math.sin(Math.toRadians(foot.bodyYaw())), 1e-6);
            assertEquals(facing.getModZ(), Math.cos(Math.toRadians(foot.bodyYaw())), 1e-6);
        }
    }
    @Test void nonBedDirectionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BedAnchor.of(UUID.randomUUID(), 0, 0, 0, Bed.Part.FOOT, BlockFace.UP));
    }
    @Test void aGsitVirtualSleepingPoseIsNeverClassifiedAsANativeBed() {
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{World.class}, (proxy, method, args) -> {
            if (method.getName().equals("getBlockAt")) return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Block.class}, (block, blockMethod, blockArgs) -> {
                if (blockMethod.getName().equals("getBlockData")) return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{BlockData.class}, (data, dataMethod, dataArgs) -> null);
                return null;
            });
            return null;
        });
        Player virtualSleeper = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "isSleeping" -> true;
            case "getBedLocation" -> new Location(world, 2, -60, 2);
            default -> null;
        });
        assertNull(BedAnchor.observe(virtualSleeper));
    }
}
