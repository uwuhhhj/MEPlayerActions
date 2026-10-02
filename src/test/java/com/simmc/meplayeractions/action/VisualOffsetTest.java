package com.simmc.meplayeractions.action;

import com.simmc.meplayeractions.me.VisualOffset;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisualOffsetTest {
    @Test void worldOffsetSurvivesAllBodyYawsWithoutScalingTheJumpHeight() {
        for (float yaw : new float[]{0, 90, 180, 270}) {
            var position = new Vector3f(); new VisualOffset(-0.3, 0.4, 0.2).apply(position, yaw);
            position.rotateY((float) Math.toRadians(-yaw));
            assertEquals(-0.3, position.x, 0.0001); assertEquals(0.4, position.y, 0.0001); assertEquals(0.2, position.z, 0.0001);
        }
    }
}
