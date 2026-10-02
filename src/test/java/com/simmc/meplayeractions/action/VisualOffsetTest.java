package com.simmc.meplayeractions.action;

import com.simmc.meplayeractions.me.VisualOffset;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisualOffsetTest {
    @Test void nativeBedAnchorCanBeAppliedWhenServerPlayerCoordinatesRemainAtTheStandingOrigin() {
        var anchor = BedAnchor.of(java.util.UUID.randomUUID(), 2, -60, 2,
                org.bukkit.block.data.type.Bed.Part.FOOT, org.bukkit.block.BlockFace.EAST);
        var position = new Vector3f();
        new VisualOffset(anchor.x() - .5, anchor.y() + 60, anchor.z() - .5).apply(position, anchor.bodyYaw());
        position.rotateY((float) Math.toRadians(-anchor.bodyYaw())).add(.5f, -60, .5f);
        assertEquals(3, position.x, .0001); assertEquals(-59.4375, position.y, .0001); assertEquals(2.5, position.z, .0001);
    }
    @Test void worldOffsetSurvivesAllBodyYawsWithoutScalingTheJumpHeight() {
        for (float yaw : new float[]{0, 90, 180, 270}) {
            var position = new Vector3f(); new VisualOffset(-0.3, 0.4, 0.2).apply(position, yaw);
            position.rotateY((float) Math.toRadians(-yaw));
            assertEquals(-0.3, position.x, 0.0001); assertEquals(0.4, position.y, 0.0001); assertEquals(0.2, position.z, 0.0001);
        }
    }
}
