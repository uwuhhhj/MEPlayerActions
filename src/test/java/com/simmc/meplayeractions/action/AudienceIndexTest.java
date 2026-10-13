package com.simmc.meplayeractions.action;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudienceIndexTest {
    @Test
    void reverseLookupContainsOnlySelectedOwnersAndRetainsHiddenSelf() {
        AudienceIndex index = new AudienceIndex();
        UUID owner = new UUID(1, 1), second = new UUID(1, 2), viewer = new UUID(2, 1);
        index.update(owner, Set.of(viewer));
        index.update(second, Set.of());
        assertEquals(Set.of(owner), index.candidates(viewer));
        assertEquals(Set.of(owner), index.candidates(owner));
        assertEquals(Set.of(second), index.candidates(second));
        assertTrue(index.candidates(new UUID(2, 2)).isEmpty());
    }

    @Test
    void replacementsRemovalAndUnrestrictedTransitionsLeaveNoStalePairings() {
        AudienceIndex index = new AudienceIndex();
        UUID owner = new UUID(1, 1), first = new UUID(2, 1), second = new UUID(2, 2);
        Set<UUID> supplied = new HashSet<>(Set.of(first));
        index.update(owner, supplied);
        supplied.clear();
        Set<UUID> before = index.candidates(first);
        index.update(owner, Set.of(second));
        assertEquals(Set.of(owner), before);
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
        assertTrue(index.candidates(first).isEmpty());
        assertEquals(Set.of(owner), index.candidates(second));
        index.unrestricted(owner);
        assertEquals(Set.of(owner), index.candidates(first));
        assertEquals(Set.of(owner), index.candidates(second));
        index.update(owner, Set.of());
        assertTrue(index.candidates(first).isEmpty());
        assertTrue(index.candidates(second).isEmpty());
        index.remove(owner);
        assertTrue(index.candidates(owner).isEmpty());
        index.unrestricted(owner);
        index.clear();
        assertTrue(index.candidates(first).isEmpty());
    }

    @Test
    void twoHundredOwnersAndFiveHundredObserversRequireTwoThousandSelectedPairs() {
        AudienceIndex index = new AudienceIndex();
        for (int ownerNumber = 0; ownerNumber < 200; ownerNumber++) {
            Set<UUID> viewers = new HashSet<>();
            for (int slot = 0; slot < 10; slot++) {
                viewers.add(new UUID(2, (ownerNumber * 10 + slot) % 500));
            }
            index.update(new UUID(1, ownerNumber), viewers);
        }
        int candidates = 0;
        for (int viewerNumber = 0; viewerNumber < 500; viewerNumber++) {
            candidates += index.candidates(new UUID(2, viewerNumber)).size();
        }
        assertEquals(2000, candidates);
        for (int ownerNumber = 0; ownerNumber < 200; ownerNumber++) {
            assertEquals(Set.of(new UUID(1, ownerNumber)), index.candidates(new UUID(1, ownerNumber)));
        }
    }
}
