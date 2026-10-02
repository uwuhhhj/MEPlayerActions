package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MovementSamplerTest {
    @Test void irregularSamplingUsesElapsedTicksAndSameTickDoesNotOverwriteHistory() {
        var s = new MovementSampler(); var world = UUID.randomUUID();
        assertFalse(s.sample(world, 0, 0, 0, 100, false, false, 0.015));
        assertFalse(s.sample(world, 0.03, 0, 0, 104, false, false, 0.015));
        assertFalse(s.sample(world, 0.03, 0, 0, 104, false, false, 0.015));
        assertTrue(s.sample(world, 0.07, 0, 0, 105, false, false, 0.015));
    }

    @Test void verticalFlightAndInputAreMovementButWorldChangesAndTeleportsAreNot() {
        var s = new MovementSampler(); var world = UUID.randomUUID();
        s.sample(world, 0, 0, 0, 100, true, false, 0.015);
        assertTrue(s.sample(world, 0, 0.2, 0, 101, true, false, 0.015));
        assertFalse(s.sample(world, 100, 0.2, 0, 102, true, false, 0.015));
        assertFalse(s.sample(UUID.randomUUID(), 0, 0, 0, 103, true, false, 0.015));
        assertTrue(s.sample(world, 0, 0, 0, 104, false, true, 0.015));
    }
}
