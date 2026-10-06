package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.protection.ResourceSettings;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionLimitsTest {
    private static final UUID VIEWER = new UUID(0, 1);
    private static final long SECOND = 1_000_000_000L;

    private static ResourceSettings.Network smallPolicy() {
        return new ResourceSettings.Network(16384, 32768, 16384, 16384, 16384, 16, 4, 16384,
                16384, 16384, 2, 1, 2, 100, 40, 200, 100, 16384);
    }
    @Test void configuredGlobalUploadAndPacketLimitsApplyAcrossDifferentPlayersAndSurviveSessionCleanup() {
        var limits = new ConnectionLimits(); limits.configure(smallPolicy());
        for (int i = 0; i < 16; i++) assertTrue(limits.allowInbound(new UUID(1, i), 1024, 0));
        UUID waiting = new UUID(1, 30); assertFalse(limits.allowInbound(waiting, 1, 0));
        limits.releaseTransfers(VIEWER); limits.forget(new UUID(1, 0));
        assertFalse(limits.allowInbound(waiting, 1, SECOND / 100));
        assertTrue(limits.allowOutbound(waiting, 1024, false, SECOND / 100, 1));
        assertTrue(limits.allowInbound(waiting, 1024, SECOND));
    }
    @Test void creditedWirePacketRemainsUsableAfterOrdinaryGlobalBudgetExhaustionAndCannotBeReplayed() {
        var limits = new ConnectionLimits(); limits.configure(smallPolicy());
        assertTrue(limits.grantUploadCredit(VIEWER, 14000, 0));
        assertTrue(limits.allowInbound(new UUID(2, 1), 2048, 0));
        assertFalse(limits.allowInbound(new UUID(2, 2), 1024, 0));
        assertFalse(limits.allowInboundCredited(VIEWER, 14001, 0));
        assertTrue(limits.allowInboundCredited(VIEWER, 14000, 0));
        assertFalse(limits.allowInboundCredited(VIEWER, 14000, 0));
        assertEquals(16048, limits.metric("uploadBytes"));
        assertTrue(limits.grantUploadCredit(VIEWER, 1024, SECOND)); limits.clearUploadCredits(VIEWER);
        assertFalse(limits.allowInboundCredited(VIEWER, 1024, SECOND));
    }
    @Test void decodeAdmissionCannotBeBypassedByOutstandingUploadCreditOrMalformedFrames() {
        var limits = new ConnectionLimits(); limits.configure(smallPolicy());
        assertTrue(limits.grantUploadCredit(VIEWER, 1024, 0));
        for (int i = 0; i < 4; i++) assertTrue(limits.allowInboundDecode(VIEWER, 1024, 0));
        assertFalse(limits.allowInboundDecode(VIEWER, 1, 0));
        assertTrue(limits.allowInboundCredited(VIEWER, 1024, 0));
        assertFalse(limits.allowInboundDecode(VIEWER, 1, 0));
        assertTrue(limits.allowInboundDecode(VIEWER, 1024, SECOND));
    }
    @Test void waitingSlotsAndRetainedOutgoingBytesAreSharedAndReleasedExactlyOnce() {
        var limits = new ConnectionLimits(); limits.configure(smallPolicy()); UUID other = new UUID(2, 2);
        assertTrue(limits.reserveWaiting(VIEWER)); assertFalse(limits.reserveWaiting(VIEWER));
        assertTrue(limits.reserveWaiting(other)); assertFalse(limits.reserveWaiting(new UUID(2, 3)));
        assertTrue(limits.queueOutgoing(VIEWER, 10000)); assertFalse(limits.queueOutgoing(other, 7000));
        assertTrue(limits.queueOutgoing(other, 6384)); assertEquals(16384, limits.metric("queuedBytes"));
        limits.releaseOutgoing(VIEWER, 10000); limits.releaseOutgoing(VIEWER, 10000);
        assertEquals(6384, limits.metric("queuedBytes"));
        limits.forget(other); assertEquals(0, limits.metric("queuedBytes")); assertEquals(1, limits.metric("waiting"));
        limits.releaseWaiting(VIEWER); limits.releaseWaiting(VIEWER); assertEquals(0, limits.metric("waiting"));
    }
    @Test void UnwritableTransportDefersAssetsButPreservesControlAndDoesNotConsumeBudget() {
        var limits = new ConnectionLimits(); limits.configure(smallPolicy()); limits.writability(ignored -> false);
        assertFalse(limits.canOutbound(VIEWER, 1000, true, 0, 0)); assertFalse(limits.allowOutbound(VIEWER, 1000, true, 0, 0));
        assertEquals(0, limits.metric("downloadBytes"));
        assertTrue(limits.allowOutbound(VIEWER, 1000, false, 0, 0));
        limits.writability(ignored -> true); assertTrue(limits.allowOutbound(VIEWER, 1000, true, 0, 0));
    }
    @Test void protectedAssetFactorPausesOnlyResourcePacketsAndHalfFactorReducesPerTickBudget() {
        var limits = new ConnectionLimits(); limits.configure(smallPolicy()); limits.assetFactor(() -> 0);
        assertFalse(limits.allowOutbound(VIEWER, 100, true, 0, 0)); assertTrue(limits.allowOutbound(VIEWER, 100, false, 0, 0));
        limits.assetFactor(() -> .5); assertTrue(limits.allowOutbound(VIEWER, 8192, true, 0, 0));
        assertFalse(limits.allowOutbound(VIEWER, 1, true, 0, 0));
    }
    @Test void foreignThreadDispatchHasBoundedBytesAndReleasesReservationsAfterFailure() {
        var limits = new ConnectionLimits(); limits.configure(smallPolicy());
        for (int i = 0; i < 16; i++) assertTrue(limits.reserveInboundDispatch(16384));
        assertFalse(limits.reserveInboundDispatch(1));
        limits.releaseInboundDispatch(16384); assertTrue(limits.reserveInboundDispatch(16384));
        for (int i = 0; i < 16; i++) limits.releaseInboundDispatch(16384);
        assertEquals(0, limits.metric("ingressCallbacks")); assertEquals(0, limits.metric("ingressBytes"));
    }
    @Test void assetPacketsCannotConsumeReservedPerPlayerOrGlobalControlPacketCapacity() {
        var limits = new ConnectionLimits(); limits.configure(smallPolicy());
        for (int player = 0; player < 4; player++) {
            UUID viewer = new UUID(9, player);
            for (int packet = 0; packet < 3; packet++) assertTrue(limits.allowOutbound(viewer, 1, true, 0, 0));
            assertFalse(limits.allowOutbound(viewer, 1, true, 0, 0));
        }
        assertFalse(limits.allowOutbound(new UUID(9, 99), 1, true, 0, 0));
        for (int player = 0; player < 4; player++) assertTrue(limits.allowOutbound(new UUID(9, player), 1, false, 0, 0));
        assertFalse(limits.allowOutbound(new UUID(9, 99), 1, false, 0, 0));
        assertEquals(16, limits.metric("downloadBytes")); assertEquals(12, limits.metric("assetPacketsPerSecond.limit"));
    }

    @Test void pushOffersArePacedAndRehelloCannotResetThreeFailureAttempts() {
        var limits = new ConnectionLimits(); var instances = Set.of(new UUID(0, 20));
        assertTrue(limits.canPushOffer(VIEWER, "hash", instances, 0)); limits.pushOfferSent(VIEWER, "hash", instances, 0);
        assertFalse(limits.canPushOffer(VIEWER, "other", instances, 9));
        assertTrue(limits.canPushOffer(VIEWER, "other", instances, 10));
        assertFalse(limits.canPushOffer(VIEWER, "hash", instances, 99));
        for (int tick : new int[]{100, 200}) {
            limits.releaseTransfers(VIEWER);
            assertTrue(limits.canPushOffer(VIEWER, "hash", instances, tick)); limits.pushOfferSent(VIEWER, "hash", instances, tick);
        }
        limits.releaseTransfers(VIEWER); assertFalse(limits.canPushOffer(VIEWER, "hash", instances, 300));
        assertFalse(limits.canPushOffer(VIEWER, "hash", instances, 5000));
        assertTrue(limits.canPushOffer(VIEWER, "hash", Set.of(new UUID(0, 21)), 5000));
        limits.pushRenderReady(VIEWER, "hash"); assertTrue(limits.canPushOffer(VIEWER, "hash", instances, 5000));
    }
    @Test void switchingExistingSameHashAliasesDoesNotManufactureNewRetryGenerations() {
        var limits = new ConnectionLimits(); UUID first = new UUID(0, 20), second = new UUID(0, 21);
        var both = Set.of(first, second); limits.pushOfferSent(VIEWER, "hash", both, 0);
        limits.pushOfferSent(VIEWER, "hash", both, 100); limits.pushOfferSent(VIEWER, "hash", both, 200);
        assertFalse(limits.canPushOffer(VIEWER, "hash", Set.of(second), 300));
        assertFalse(limits.canPushOffer(VIEWER, "hash", Set.of(first), 300));
        assertFalse(limits.canPushOffer(VIEWER, "hash", Set.of(second, new UUID(0, 22)), 300));
        assertTrue(limits.canPushOffer(VIEWER, "hash", Set.of(new UUID(0, 22)), 300));
    }
    @Test void offerCooldownHandlesTickWrapAndOnlyActualDisconnectClearsFailures() {
        var limits = new ConnectionLimits(); var instances = Set.of(new UUID(0, 20));
        limits.pushOfferSent(VIEWER, "hash", instances, 0xfffffff0L);
        assertFalse(limits.canPushOffer(VIEWER, "hash", instances, 0x53));
        assertTrue(limits.canPushOffer(VIEWER, "hash", instances, 0x54));
        limits.pushOfferSent(VIEWER, "hash", instances, 0x54); limits.pushOfferSent(VIEWER, "hash", instances, 0xb8);
        limits.releaseTransfers(VIEWER); assertFalse(limits.canPushOffer(VIEWER, "hash", instances, 0x11c));
        limits.forget(VIEWER); assertTrue(limits.canPushOffer(VIEWER, "hash", instances, 0x11c));
    }

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
