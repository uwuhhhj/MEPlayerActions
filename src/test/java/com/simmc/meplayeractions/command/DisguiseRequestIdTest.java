package com.simmc.meplayeractions.command;

import com.simmc.meplayeractions.action.DisguiseOptions;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DisguiseRequestIdTest {
    private static final String NONCE = "00000000-0000-0000-0000-000000000012";
    @Test void optionalMetadataLeavesOrdinaryGrammarAndOptionEqualityIntact() {
        String[] command = {"disguise", "fixture", "scale=2", "effect=slowness:1"};
        var defaults = DisguiseOptions.defaults("fixture", 1, true, 2);
        var plain = DisguiseRequestId.parse(command);
        var correlated = DisguiseRequestId.parse(new String[]{"disguise", "fixture", "scale=2", "effect=slowness:1", "request-id=" + NONCE});
        assertNull(plain.requestId()); assertEquals(UUID.fromString(NONCE), correlated.requestId());
        assertArrayEquals(command, correlated.arguments());
        assertEquals(DisguiseOptions.parse(command, defaults), DisguiseOptions.parse(correlated.arguments(), defaults));
        assertEquals(plain.fingerprint(), correlated.fingerprint());
    }
    @Test void malformedDuplicatedOrNonFinalNoncesAreRejectedBeforeOptionsAreApplied() {
        for (String[] args : List.of(
                new String[]{"disguise", "fixture", "request-id=0-0-0-0-12"},
                new String[]{"disguise", "fixture", "request-id=AAAAAAAA-0000-0000-0000-000000000012"},
                new String[]{"disguise", "fixture", "request-id="},
                new String[]{"disguise", "fixture", "--request-id=" + NONCE},
                new String[]{"disguise", "fixture", "request-id", NONCE},
                new String[]{"disguise", "fixture", "request-id=" + NONCE, "scale=2"},
                new String[]{"disguise", "fixture", "request-id=" + NONCE, "request-id=" + NONCE}))
            assertThrows(IllegalArgumentException.class, () -> DisguiseRequestId.parse(args));
    }
    @Test void requestMetadataCannotMutateTheCapturedCommandOrBypassOtherCommandGrammar() {
        String[] args = {"disguise", "fixture", "request-id=" + NONCE}; var parsed = DisguiseRequestId.parse(args);
        args[1] = "changed"; parsed.arguments()[1] = "other";
        assertEquals("fixture", parsed.modelId()); assertTrue(parsed.wireBytes() > 36);
        var stop = DisguiseRequestId.parse(new String[]{"stop", "request-id=" + NONCE});
        assertNull(stop.requestId()); assertThrows(IllegalArgumentException.class, () -> CommandLayout.normalize(stop.arguments()));
    }
}
