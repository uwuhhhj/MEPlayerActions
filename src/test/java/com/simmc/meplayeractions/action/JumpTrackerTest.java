package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JumpTrackerTest {
    @Test void landingDoesNotCutShortPoseAndTailExpires() {
        var j = new JumpTracker(); j.sample(true, 0, false, 0, 16, 6); j.signal(1);
        assertEquals(ActionState.JUMP, j.sample(false, 0.4, false, 2, 16, 6));
        assertEquals(ActionState.JUMP, j.sample(true, -0.2, false, 12, 16, 6));
        assertEquals(ActionState.JUMP, j.sample(true, 0, false, 16, 16, 6));
        assertNull(j.sample(true, 0, false, 17, 16, 6));
    }
    @Test void walkingOffLedgeIsFallingAndLongJumpChangesToFalling() {
        var j = new JumpTracker(); j.sample(true, 0, false, 0, 16, 6);
        assertEquals(ActionState.FALL, j.sample(false, -0.2, false, 1, 16, 6));
        j.signal(2);
        assertEquals(ActionState.JUMP, j.sample(false, 0.4, false, 3, 16, 6));
        assertEquals(ActionState.FALL, j.sample(false, -0.3, false, 18, 16, 6));
    }
    @Test void takeoffFallbackAndRepeatedJumpsGetDifferentCycles() {
        var j = new JumpTracker(); j.sample(true, 0, false, 0, 16, 6);
        assertEquals(ActionState.JUMP, j.sample(false, 0.4, false, 1, 16, 6));
        long first = j.cycle(); j.sample(true, -0.2, false, 10, 16, 6);
        assertEquals(ActionState.JUMP, j.sample(false, 0.4, false, 11, 16, 6));
        assertTrue(j.cycle() > first);
    }
    @Test void otherPosturesInterruptAndAnUnconfirmedTakeoffCannotStick() {
        var j = new JumpTracker(); j.signal(1);
        assertNull(j.sample(false, 0.4, true, 2, 16, 6));
        assertNull(j.sample(true, 0, false, 3, 16, 6));
        j.signal(4); assertNull(j.sample(true, 0, false, 8, 16, 6));
    }
    @Test void lowCeilingLandingTailIsCapped() {
        var j = new JumpTracker(); j.signal(1); j.sample(false, 0.2, false, 2, 30, 6);
        assertEquals(ActionState.JUMP, j.sample(true, 0, false, 3, 30, 6));
        assertNull(j.sample(true, 0, false, 9, 30, 6));
    }
}
