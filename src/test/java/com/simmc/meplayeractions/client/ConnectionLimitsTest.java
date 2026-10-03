package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionLimitsTest {
    private static final UUID VIEWER = new UUID(0, 1);
    private static final long SECOND = 1_000_000_000L;

    @Test void endingOrReplacingSessionReleasesWorkButRetainsEveryConnectionLimit() {
        ConnectionLimits limits = new ConnectionLimits();
        assertTrue(limits.allowHello(VIEWER, 0));
        assertTrue(limits.allowAsset(VIEWER, "a", 100));
        assertTrue(limits.allowRequest(VIEWER, "request", "play", 100, 1200));
        for (int i = 0; i < ConnectionLimits.INBOUND_PACKETS_PER_SECOND; i++) assertTrue(limits.allowInbound(VIEWER, 100, 0));
        assertTrue(limits.allowOutbound(VIEWER, ConnectionLimits.GLOBAL_ASSET_BYTES_PER_TICK, true, 0, 100));
        assertTrue(limits.allowOutbound(VIEWER, ConnectionLimits.GLOBAL_ASSET_BYTES_PER_TICK, true, 0, 101));
        assertTrue(limits.reserveTransfer(VIEWER));
        assertTrue(limits.reserveTransfer(VIEWER));

        limits.releaseTransfers(VIEWER); // unsupported protocol/new hello/config disable all end only the session
        assertFalse(limits.allowInbound(VIEWER, 1, SECOND - 1));
        assertFalse(limits.allowHello(VIEWER, SECOND - 1));
        assertFalse(limits.allowAsset(VIEWER, "a", 199));
        assertFalse(limits.allowRequest(VIEWER, "snapshot_request", "", 101, 1200));
        assertFalse(limits.allowOutbound(VIEWER, 1, true, SECOND - 1, 102));
        assertTrue(limits.reserveTransfer(VIEWER));
        assertTrue(limits.reserveTransfer(VIEWER));
        assertFalse(limits.reserveTransfer(VIEWER));
        assertTrue(limits.allowInbound(VIEWER, 1, SECOND));
        assertTrue(limits.allowHello(VIEWER, SECOND));
        assertTrue(limits.allowAsset(VIEWER, "a", 200));
        assertTrue(limits.allowRequest(VIEWER, "request", "stop", 101, 1200));
        assertTrue(limits.allowRequest(VIEWER, "request", "reset", 101, 1200));
    }

    @Test void inboundByteBudgetIsIndependentOfPacketCountAndOtherConnections() {
        ConnectionLimits limits = new ConnectionLimits();
        assertTrue(limits.allowInbound(VIEWER, ConnectionLimits.INBOUND_BYTES_PER_SECOND - 10, 0));
        assertFalse(limits.allowInbound(VIEWER, 11, 0));
        assertTrue(limits.allowInbound(VIEWER, 10, 0)); // rejected bytes were not charged
        assertFalse(limits.allowInbound(VIEWER, 1, 0));
        assertTrue(limits.allowInbound(new UUID(0, 2), 1, 0));
        assertTrue(limits.allowInbound(VIEWER, 1, SECOND));
    }

    @Test void globalAndClientTransferSlotsAreReclaimedOnCancelCompletionAndDisconnect() {
        ConnectionLimits limits = new ConnectionLimits();
        for (int i = 1; i <= ConnectionLimits.GLOBAL_TRANSFERS / 2; i++) {
            UUID viewer = new UUID(0, i);
            assertTrue(limits.reserveTransfer(viewer));
            assertTrue(limits.reserveTransfer(viewer));
            assertFalse(limits.reserveTransfer(viewer));
        }
        UUID waiting = new UUID(0, 100);
        assertFalse(limits.reserveTransfer(waiting));
        limits.releaseTransfer(VIEWER);
        assertTrue(limits.reserveTransfer(waiting));
        assertFalse(limits.reserveTransfer(waiting));
        limits.releaseTransfers(VIEWER);
        limits.releaseTransfers(VIEWER); // repeated cleanup cannot manufacture additional global slots
        assertTrue(limits.reserveTransfer(waiting));
        assertFalse(limits.reserveTransfer(new UUID(0, 101)));
        limits.forget(new UUID(0, 2));
        assertTrue(limits.reserveTransfer(new UUID(0, 101)));
        assertTrue(limits.reserveTransfer(new UUID(0, 101)));
        assertFalse(limits.reserveTransfer(new UUID(0, 102)));
    }

    @Test void rejectedGlobalBudgetDoesNotConsumeClientBytesAndAssetBudgetLeavesControlHeadroom() {
        ConnectionLimits limits = new ConnectionLimits();
        UUID other = new UUID(0, 2);
        assertTrue(limits.allowOutbound(VIEWER, ConnectionLimits.GLOBAL_ASSET_BYTES_PER_TICK, true, 0, 1));
        assertFalse(limits.allowOutbound(other, 100, true, 0, 1));
        assertTrue(limits.allowOutbound(other, ConnectionLimits.GLOBAL_BYTES_PER_TICK - ConnectionLimits.GLOBAL_ASSET_BYTES_PER_TICK, false, 0, 1));
        assertFalse(limits.allowOutbound(other, 1, false, 0, 1));
        assertTrue(limits.allowOutbound(other, ConnectionLimits.GLOBAL_ASSET_BYTES_PER_TICK, true, 0, 2));
        assertTrue(limits.allowOutbound(other, ConnectionLimits.GLOBAL_ASSET_BYTES_PER_TICK, true, 0, 3));
        assertFalse(limits.allowOutbound(other, 1, true, 0, 4));
        assertTrue(limits.allowOutbound(other, 1, true, SECOND, 4));
    }

    @Test void totalOutboundBudgetIncludesControlPacketsAndResetsOnlyAtWindowBoundary() {
        ConnectionLimits limits = new ConnectionLimits();
        int ticks = ConnectionLimits.OUTBOUND_BYTES_PER_SECOND / ConnectionLimits.GLOBAL_BYTES_PER_TICK;
        for (int tick = 0; tick < ticks; tick++)
            assertTrue(limits.allowOutbound(VIEWER, ConnectionLimits.GLOBAL_BYTES_PER_TICK, false, 0, tick));
        assertFalse(limits.allowOutbound(VIEWER, 1, false, SECOND - 1, ticks));
        limits.releaseTransfers(VIEWER);
        assertFalse(limits.allowOutbound(VIEWER, 1, false, SECOND - 1, ticks));
        assertTrue(limits.allowOutbound(VIEWER, 1, false, SECOND, ticks));
    }

    @Test void assetCooldownSurvivesTickWrapAndKeepsOtherHashesIndependent() {
        ConnectionLimits limits = new ConnectionLimits();
        assertTrue(limits.allowAsset(VIEWER, "a", 0xffff_fff0L));
        assertTrue(limits.allowAsset(VIEWER, "b", 0xffff_fff0L));
        limits.releaseTransfers(VIEWER);
        assertFalse(limits.allowAsset(VIEWER, "a", 0x53));
        assertTrue(limits.allowAsset(VIEWER, "c", 0x53));
        assertTrue(limits.allowAsset(VIEWER, "a", 0x54));
        assertFalse(limits.allowAsset(VIEWER, "c", 0x54));
    }

    @Test void actualDisconnectResetsClientBudgetsButCannotResetGlobalBudget() {
        ConnectionLimits limits = new ConnectionLimits();
        assertTrue(limits.allowHello(VIEWER, 0));
        assertTrue(limits.allowAsset(VIEWER, "a", 0));
        assertTrue(limits.allowRequest(VIEWER, "request", "play", 0, 100));
        assertTrue(limits.allowOutbound(VIEWER, ConnectionLimits.GLOBAL_BYTES_PER_TICK, false, 0, 0));
        limits.forget(VIEWER);
        assertTrue(limits.allowHello(VIEWER, 1));
        assertTrue(limits.allowAsset(VIEWER, "a", 1));
        assertTrue(limits.allowRequest(VIEWER, "request", "play", 1, 100));
        assertFalse(limits.allowOutbound(VIEWER, 1, false, 1, 0));
        assertTrue(limits.allowOutbound(VIEWER, 1, false, 1, 1));
    }

    @Test void offlinePruningDoesNotResetOnlineConnectionsAndReclaimsOnlyDepartedSlots() {
        ConnectionLimits limits = new ConnectionLimits();
        UUID offline = new UUID(0, 2);
        assertTrue(limits.allowHello(VIEWER, 0));
        assertTrue(limits.allowHello(offline, 0));
        for (int i = 1; i <= ConnectionLimits.GLOBAL_TRANSFERS / 2; i++) {
            assertTrue(limits.reserveTransfer(new UUID(0, i)));
            assertTrue(limits.reserveTransfer(new UUID(0, i)));
        }
        limits.pruneOffline(viewer -> !viewer.equals(offline));
        assertFalse(limits.allowHello(VIEWER, 1));
        assertTrue(limits.allowHello(offline, 1));
        assertTrue(limits.reserveTransfer(offline));
        assertTrue(limits.reserveTransfer(offline));
        assertFalse(limits.reserveTransfer(new UUID(0, 100)));
    }

    @Test void monotonicClockWrapDoesNotBypassOrPermanentlyBlockWindow() {
        ConnectionLimits limits = new ConnectionLimits();
        long beforeWrap = Long.MAX_VALUE - SECOND / 2;
        assertTrue(limits.allowHello(VIEWER, beforeWrap));
        assertTrue(limits.allowInbound(VIEWER, ConnectionLimits.INBOUND_BYTES_PER_SECOND, beforeWrap));
        assertFalse(limits.allowHello(VIEWER, beforeWrap + SECOND - 1));
        assertFalse(limits.allowInbound(VIEWER, 1, beforeWrap + SECOND - 1));
        assertTrue(limits.allowHello(VIEWER, beforeWrap + SECOND));
        assertTrue(limits.allowInbound(VIEWER, 1, beforeWrap + SECOND));
    }
}
