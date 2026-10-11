package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DisguiseAdmissionTest {
    @Test
    void cappedServerRejectsNewOwnersButAllowsReplacementWithoutLosingItsSlot() {
        DisguiseAdmission admission = new DisguiseAdmission(2, 0);
        UUID owner = UUID.randomUUID();
        assertEquals("disguise_limit", admission.admit(owner, false, 2, 10).code());
        assertNull(admission.admit(owner, true, 2, 10));
        assertNull(admission.admit(owner, false, 1, 10));
    }

    @Test
    void cooldownChargesFailedCreationAttemptsAndIsIsolatedPerPlayer() {
        DisguiseAdmission admission = new DisguiseAdmission(4, 20);
        UUID owner = UUID.randomUUID();
        assertNull(admission.admit(owner, false, 0, 100));
        DisguiseAdmission.Rejection rejected = admission.admit(owner, true, 1, 105);
        assertEquals("disguise_cooldown", rejected.code());
        assertEquals(15, rejected.retryTicks());
        assertNull(admission.admit(UUID.randomUUID(), false, 1, 105));
        assertNull(admission.admit(owner, true, 1, 120));
    }

    @Test
    void quitAndClockResetReleaseOnlyTheRelevantCooldowns() {
        DisguiseAdmission admission = new DisguiseAdmission(4, 20);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertNull(admission.admit(first, false, 0, 100));
        assertNull(admission.admit(second, false, 1, 100));
        admission.forget(first);
        assertNull(admission.admit(first, false, 1, 101));
        assertNotNull(admission.admit(second, false, 1, 101));
        assertNull(admission.admit(second, false, 1, 1));
    }
}
