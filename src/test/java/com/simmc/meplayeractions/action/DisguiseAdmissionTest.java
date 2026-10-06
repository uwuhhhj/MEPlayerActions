package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DisguiseAdmissionTest {
    @Test void cappedServerRejectsNewOwnersButAllowsReplacementWithoutLosingItsSlot() {
        var admission = new DisguiseAdmission(2, 0);
        var owner = UUID.randomUUID();
        assertEquals("disguise_limit", admission.admit(owner, false, 2, 10).code());
        assertNull(admission.admit(owner, true, 2, 10));
        assertNull(admission.admit(owner, false, 1, 10));
    }

    @Test void cooldownChargesFailedCreationAttemptsAndIsIsolatedPerPlayer() {
        var admission = new DisguiseAdmission(4, 20);
        var owner = UUID.randomUUID();
        assertNull(admission.admit(owner, false, 0, 100));
        var rejected = admission.admit(owner, true, 1, 105);
        assertEquals("disguise_cooldown", rejected.code());
        assertEquals(15, rejected.retryTicks());
        assertNull(admission.admit(UUID.randomUUID(), false, 1, 105));
        assertNull(admission.admit(owner, true, 1, 120));
    }

    @Test void quitAndClockResetReleaseOnlyTheRelevantCooldowns() {
        var admission = new DisguiseAdmission(4, 20);
        var first = UUID.randomUUID(); var second = UUID.randomUUID();
        assertNull(admission.admit(first, false, 0, 100));
        assertNull(admission.admit(second, false, 1, 100));
        admission.forget(first);
        assertNull(admission.admit(first, false, 1, 101));
        assertNotNull(admission.admit(second, false, 1, 101));
        assertNull(admission.admit(second, false, 1, 1));
    }
}
