package com.simmc.meplayeractions.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActionDialogSessionTest {
    @Test
    void choosingAnActionInvalidatesEveryOtherButtonFromThatDialog() {
        UUID owner = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        ActionDialog.Session session = new ActionDialog.Session(owner, instance, 0);
        session.actions.add(new String[] { "play", "wave" });
        session.actions.add(new String[] { "reset" });

        assertArrayEquals(new String[] { "play", "wave" },
                session.claim(owner, session.id.toString(), "0", instance, 1));
        assertNull(session.claim(owner, session.id.toString(), "0", instance, 2));
        assertNull(session.claim(owner, session.id.toString(), "1", instance, 2));
    }

    @Test
    void anotherPlayerOrAnOldPageCannotConsumeTheCurrentPlayersButton() {
        UUID owner = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        ActionDialog.Session previous = new ActionDialog.Session(owner, instance, 0);
        ActionDialog.Session current = new ActionDialog.Session(owner, instance, 0);
        current.actions.add(new String[] { "page", "2" });

        assertNull(current.claim(UUID.randomUUID(), current.id.toString(), "0", instance, 1));
        assertNull(current.claim(owner, previous.id.toString(), "0", instance, 1));
        assertArrayEquals(new String[] { "page", "2" },
                current.claim(owner, current.id.toString(), "0", instance, 1));
    }

    @Test
    void changedOrRemovedModelCannotReceiveAnActionFromTheOldDialog() {
        UUID owner = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        ActionDialog.Session session = new ActionDialog.Session(owner, instance, 0);
        session.actions.add(new String[] { "play", "wave" });

        assertNull(session.claim(owner, session.id.toString(), "0", UUID.randomUUID(), 1));
        assertNull(session.claim(owner, session.id.toString(), "0", null, 1));
    }

    @Test
    void malformedResponsesDoNotConsumeTheLegitimateButton() {
        UUID owner = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        ActionDialog.Session session = new ActionDialog.Session(owner, instance, 0);
        session.actions.add(new String[] { "pose", "sit" });

        assertNull(session.claim(owner, null, "0", instance, 1));
        for (String button : new String[] { null, "", "play wave", "-1", "1", "999999999999999999999" }) {
            assertNull(session.claim(owner, session.id.toString(), button, instance, 1));
        }
        assertArrayEquals(new String[] { "pose", "sit" },
                session.claim(owner, session.id.toString(), "0", instance, 1));
    }

    @Test
    void expiredDialogCannotStartAnAction() {
        UUID owner = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        ActionDialog.Session session = new ActionDialog.Session(owner, instance, 0);
        session.actions.add(new String[] { "play", "wave" });

        assertNull(session.claim(owner, session.id.toString(), "0", instance, Duration.ofMinutes(5).toNanos()));
    }

    @Test
    void closingTheDialogAlsoInvalidatesItsActions() {
        UUID owner = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        ActionDialog.Session session = new ActionDialog.Session(owner, instance, 0);
        session.actions.add(new String[] { "close" });
        session.actions.add(new String[] { "play", "wave" });

        assertArrayEquals(new String[] { "close" }, session.claim(owner, session.id.toString(), "0", instance, 1));
        assertNull(session.claim(owner, session.id.toString(), "1", instance, 2));
    }
}
