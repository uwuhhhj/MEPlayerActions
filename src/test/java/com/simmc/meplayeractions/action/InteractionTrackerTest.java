package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InteractionTrackerTest {
    private static final InteractionTracker.Target A = new InteractionTracker.Target(UUID.randomUUID(), 1, 2, 3);
    private static final InteractionTracker.Target B = new InteractionTracker.Target(A.world(), 2, 2, 3);

    @Test void handsHaveIndependentMappingsAndSwingExpires() {
        var t = new InteractionTracker(8, 12);
        t.swing(false, 100);
        assertEquals(ActionState.SWING_MAINHAND, t.select(101, true, true));
        assertNull(t.select(108, true, true));
        t.swing(true, 109);
        assertEquals(ActionState.SWING_OFFHAND, t.select(110, true, true));
    }

    @Test void miningRequiresBlockDamageAndWinsOverSwingsWithoutFallbackWhenDisabled() {
        var t = new InteractionTracker(8, 12);
        t.swing(false, 100);
        assertEquals(ActionState.SWING_MAINHAND, t.select(101, true, true));
        t.startMining(A, 101); t.swing(false, 102);
        assertEquals(ActionState.MINING, t.select(103, true, false));
        assertNull(t.select(103, false, true));
    }

    @Test void miningIsRefreshedByMainhandSwingsAndTimeoutClearsAllHistory() {
        var t = new InteractionTracker(8, 12);
        t.startMining(A, 100); t.swing(false, 110);
        assertEquals(ActionState.MINING, t.select(120, true, true));
        assertNull(t.select(122, true, true)); assertNull(t.target());
        t.startMining(A, 200); t.swing(true, 210);
        assertNull(t.select(212, true, true));
    }

    @Test void lateSwingCannotResurrectExpiredMining() {
        var t = new InteractionTracker(8, 12);
        t.startMining(A, 100); t.swing(false, 120);
        assertNull(t.select(120, true, true));
    }

    @Test void abortOfPreviousBlockCannotStopNewBlockAndMatchingAbortClearsSwing() {
        var t = new InteractionTracker(8, 12);
        t.startMining(A, 100); t.startMining(B, 101); t.stopMining(A);
        assertEquals(ActionState.MINING, t.select(102, true, true));
        t.swing(false, 102); t.stopMining(B);
        assertNull(t.select(103, true, true));
    }

    @Test void changingTargetOrResetStopsMiningAndPendingSwings() {
        var t = new InteractionTracker(8, 12);
        t.startMining(A, 100); t.swing(false, 101); t.validateTarget(B);
        assertNull(t.select(102, true, true));
        t.startMining(A, 103); t.validateTarget(null);
        assertNull(t.select(104, true, true));
        t.swing(true, 105); t.clear(); assertNull(t.select(106, true, true));
    }
}
