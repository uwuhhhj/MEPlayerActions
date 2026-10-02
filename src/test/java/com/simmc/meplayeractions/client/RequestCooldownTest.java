package com.simmc.meplayeractions.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RequestCooldownTest {
    @Test void playCanBeStoppedAndResetImmediatelyWithoutExtendingStartCooldown() {
        var gate = new RequestCooldown(); assertTrue(gate.allow("request", "play", 100, 4));
        assertTrue(gate.allow("request", "stop", 100, 4)); assertTrue(gate.allow("request", "reset", 100, 4));
        assertFalse(gate.allow("request", "play", 103, 4)); assertTrue(gate.allow("request", "crawl", 104, 4));
    }
    @Test void snapshotsAndStartingPosturesShareCooldownButEmergencyStopDoesNotConsumeIt() {
        var gate = new RequestCooldown(); assertTrue(gate.allow("request", "stop", 10, 4));
        assertTrue(gate.allow("snapshot_request", "", 10, 4)); assertFalse(gate.allow("request", "sit", 11, 4));
        assertTrue(gate.allow("request", "reset", 13, 4)); assertTrue(gate.allow("request", "sit", 14, 4));
    }
    @Test void unsignedPaperTickWrapIsHandled() {
        var gate = new RequestCooldown(); assertTrue(gate.allow("request", "play", 0xfffffffeL, 4));
        assertFalse(gate.allow("request", "play", 0, 4)); assertTrue(gate.allow("request", "reset", 0, 4));
        assertTrue(gate.allow("request", "play", 2, 4));
    }
}
