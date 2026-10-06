package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.protection.ResourceSettings;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Authorization and lifecycle regression tests without trusting client-selected model keys. */
class PushAssetOffersTest {
    private static final UUID OWNER = new UUID(0, 1), INSTANCE = new UUID(0, 2);
    private static final String HASH = "a".repeat(64);
    private static PushAssetOffers.Offer issue(PushAssetOffers offers, String hash, long tick) {
        return offers.issue(OWNER, INSTANCE, "ysm_01_jk", hash, tick);
    }
    @Test void feedbackRequiresExactIssuedTokenAndHashAndIsConsumedOnlyOnce() {
        var offers = new PushAssetOffers(); var offer = issue(offers, HASH, 0);
        assertEquals(PushAssetOffers.Feedback.INVALID, offers.feedback(UUID.randomUUID(), HASH, "missing", 1));
        assertEquals(PushAssetOffers.Feedback.INVALID, offers.feedback(offer.id(), "b".repeat(64), "missing", 1));
        assertEquals(PushAssetOffers.Feedback.INVALID, offers.feedback(offer.id(), HASH, "download", 1));
        assertTrue(offers.missing().isEmpty()); assertFalse(offers.start(offer));
        assertEquals(PushAssetOffers.Feedback.MISSING, offers.feedback(offer.id(), HASH, "missing", 2));
        assertEquals(PushAssetOffers.Feedback.INVALID, offers.feedback(offer.id(), HASH, "missing", 3));
        assertEquals(1, offers.missing().size()); assertTrue(offers.start(offer)); assertFalse(offers.start(offer));
    }
    @Test void cachedDoesNotFreePendingSlotOrCreateATransferOrReplaceRenderAcknowledgment() {
        var offers = new PushAssetOffers(); var first = issue(offers, HASH, 0);
        var second = issue(offers, "b".repeat(64), 10); assertNotNull(second);
        assertEquals(PushAssetOffers.Feedback.CACHED, offers.feedback(first.id(), HASH, "cached", 11));
        assertEquals(2, offers.pending()); assertFalse(offers.canIssue("c".repeat(64))); assertFalse(offers.start(first));
        assertTrue(offers.missing().isEmpty()); offers.ready(HASH);
        assertEquals(1, offers.pending()); assertTrue(offers.canIssue("c".repeat(64)));
    }
    @Test void sameContentAliasesShareOneOfferUntilTheFinalVisibleBindingLeaves() {
        var offers = new PushAssetOffers(); var offer = issue(offers, HASH, 0);
        assertNull(offers.issue(new UUID(0, 3), new UUID(0, 4), "alias", HASH, 1));
        assertEquals(PushAssetOffers.Feedback.MISSING, offers.feedback(offer.id(), HASH, "missing", 1));
        assertTrue(offers.start(offer));
        assertTrue(offers.expire(2, authorization -> authorization.hash().equals(HASH)).isEmpty());
        var cancelled = offers.expire(3, authorization -> false);
        assertEquals("asset_not_authorized", cancelled.getFirst().reason()); assertEquals(offer, cancelled.getFirst().offer());
        assertFalse(offers.transferring(offer)); assertTrue(offers.missing().isEmpty());
    }
    @Test void rejectedAndExpiredTokensCannotAuthorizeAReplacementOffer() {
        var offers = new PushAssetOffers(); var old = issue(offers, HASH, 0);
        assertEquals(PushAssetOffers.Feedback.REJECTED, offers.feedback(old.id(), HASH, "rejected", 1));
        var replacement = issue(offers, HASH, 100); assertNotEquals(old.id(), replacement.id());
        assertEquals(PushAssetOffers.Feedback.INVALID, offers.feedback(old.id(), HASH, "missing", 101));
        assertTrue(offers.expire(199, authorization -> true).isEmpty());
        assertEquals("asset_offer_expired", offers.expire(200, authorization -> true).getFirst().reason());
        assertEquals(PushAssetOffers.Feedback.INVALID, offers.feedback(replacement.id(), HASH, "missing", 200));
    }
    @Test void steadyProgressSurvivesTheFormerThirtySecondCutoffButHasAnAbsoluteDeadline() {
        var offers = new PushAssetOffers(); var offer = issue(offers, HASH, 0);
        offers.feedback(offer.id(), HASH, "missing", 1); assertTrue(offers.start(offer));
        for (int tick = 250; tick <= 1000; tick += 250) {
            offers.progress(offer, tick); assertTrue(offers.expire(tick, authorization -> true).isEmpty());
        }
        assertTrue(offers.expire(1199, authorization -> true).isEmpty()); offers.progress(offer, 1200);
        assertEquals("asset_offer_expired", offers.expire(1200, authorization -> true).getFirst().reason());
    }
    @Test void QueueAndStreamingIdleTimeoutsDoNotExtendOnInvalidFeedback() {
        var offers = new PushAssetOffers(); var offer = issue(offers, HASH, 0);
        offers.feedback(offer.id(), HASH, "missing", 10);
        assertEquals(PushAssetOffers.Feedback.INVALID, offers.feedback(offer.id(), HASH, "missing", 300));
        assertTrue(offers.expire(309, authorization -> true).isEmpty());
        assertEquals("asset_queue_timeout", offers.expire(310, authorization -> true).getFirst().reason());
        offer = issue(offers, HASH, 400); offers.feedback(offer.id(), HASH, "missing", 401); offers.start(offer);
        offers.progress(offer, 450); assertTrue(offers.expire(749, authorization -> true).isEmpty());
        assertEquals("asset_transfer_timeout", offers.expire(750, authorization -> true).getFirst().reason());
    }
    @Test void deliveredOrCachedAssetsTimeOutWithoutARealRenderReady() {
        for (boolean cached : new boolean[]{false, true}) {
            var offers = new PushAssetOffers(); var offer = issue(offers, HASH, 0);
            offers.feedback(offer.id(), HASH, cached ? "cached" : "missing", 1);
            if (!cached) { offers.start(offer); offers.delivered(offer, 1); }
            assertTrue(offers.expire(200, authorization -> true).isEmpty());
            assertEquals("asset_render_timeout", offers.expire(201, authorization -> true).getFirst().reason());
        }
    }
    @Test void completedHistoryIsBoundedAndSessionEndInvalidatesAllTokens() {
        var offers = new PushAssetOffers();
        for (int i = 0; i < PushAssetOffers.MAX_RECORDS; i++) {
            String hash = String.format("%064x", i); assertNotNull(issue(offers, hash, i)); offers.ready(hash);
        }
        assertEquals(0, offers.pending()); assertFalse(offers.canIssue(HASH));
        assertEquals(PushAssetOffers.MAX_RECORDS, offers.clear().size()); assertTrue(offers.canIssue(HASH));
        assertEquals(PushAssetOffers.Feedback.INVALID, offers.feedback(UUID.randomUUID(), HASH, "missing", 100));
    }
    @Test void deadlinesHandleUnsignedServerTickWrap() {
        var offers = new PushAssetOffers(); var offer = issue(offers, HASH, 0xfffffff0L);
        assertTrue(offers.expire(0x53, authorization -> true).isEmpty());
        assertEquals("asset_offer_expired", offers.expire(0x54, authorization -> true).getFirst().reason());
        assertFalse(offers.start(offer));
    }
    @Test void configuredQueueStreamingTotalAndReadyDeadlinesAreIndependent() {
        var d = ResourceSettings.defaults().network();
        var policy = new ResourceSettings.Network(d.globalUploadBytesPerSecond(), d.globalDownloadBytesPerSecond(), d.uploadBytesPerSecond(),
                d.downloadBytesPerSecond(), d.assetDownloadBytesPerSecond(), d.globalPacketsPerSecond(), d.packetsPerSecond(), d.burstBytes(),
                d.bytesPerTick(), d.assetBytesPerTick(), d.globalTransfers(), d.transfersPerPlayer(), d.waitingTransfers(), 40, 20, 100, 30, d.maxQueuedOutgoingBytes());
        var offers = new PushAssetOffers(); offers.configure(policy); var queued = issue(offers, HASH, 0);
        assertEquals(PushAssetOffers.Feedback.MISSING, offers.feedback(queued.id(), HASH, "missing", 1));
        assertTrue(offers.expire(40, ignored -> true).isEmpty());
        assertEquals("asset_queue_timeout", offers.expire(41, ignored -> true).getFirst().reason());
        var streaming = issue(offers, HASH, 50); offers.feedback(streaming.id(), HASH, "missing", 51); assertTrue(offers.start(streaming));
        offers.progress(streaming, 55); assertTrue(offers.expire(74, ignored -> true).isEmpty());
        assertEquals("asset_transfer_timeout", offers.expire(75, ignored -> true).getFirst().reason());
        var cached = issue(offers, HASH, 80); offers.feedback(cached.id(), HASH, "cached", 81);
        assertTrue(offers.expire(110, ignored -> true).isEmpty()); assertEquals("asset_render_timeout", offers.expire(111, ignored -> true).getFirst().reason());
    }
}
