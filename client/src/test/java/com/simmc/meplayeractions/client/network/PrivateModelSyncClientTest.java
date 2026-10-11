package com.simmc.meplayeractions.client.network;

import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.NativeModelBundle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** Real bundle decoding with a deterministic host clock; no game, network or sleeps. */
class PrivateModelSyncClientTest
{
    private static final long NOW = 10_000_000_000L, SECOND = 1_000_000_000L;

    @Test
    void pausedViewerNegotiatesItsIntentWithoutLoadingOffersOrClaimingReadiness() throws Exception
    {
        Rig rig = new Rig();
        rig.host.viewEnabled = false;
        rig.client.tick(NOW);
        JsonObject hello = rig.host.sent("hello").getLast();
        assertFalse(hello.get("viewEnabled").getAsBoolean());
        assertTrue(hello.getAsJsonArray("capabilities").asList().stream()
                .anyMatch(value -> value.getAsString().equals(PrivateModelSyncClient.VIEW_STATE)));
        rig.acknowledgeViewState(true, true);
        Offer offer = rig.offer();
        rig.receive(offer.packet(), NOW);
        rig.host.drain();
        assertTrue(rig.client.viewerStateNegotiated());
        assertFalse(rig.client.viewingEnabled());
        assertTrue(rig.client.receivedModels().isEmpty());
        assertTrue(rig.host.sent("private_ready").isEmpty());
        assertEquals(0, rig.host.cacheReads);
        assertEquals(0, rig.host.decodes);
        assertEquals(0, rig.host.prepared);
    }

    @Test
    void viewingPauseCancelsReceiverCallbacksButKeepsOwnPublicationAndResumesWithoutRejectingTheOwner() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeViewState(true, true);
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject publication = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), publication), NOW);
        assertTrue(rig.client.committed());
        Offer remote = rig.offer();
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        CompletableFuture<LocalModelLibrary.Loaded> delayed = new CompletableFuture<>();
        rig.host.decodeResults.add(delayed);
        rig.receive(remote.packet(), NOW);
        rig.host.drain();
        assertEquals(PrivateModelSyncClient.ReceivingPhase.DECODING, rig.client.receivedModels().getFirst().phase());
        rig.host.viewEnabled = false;
        rig.client.viewerRenderingChanged();
        assertTrue(rig.client.committed());
        assertNull(rig.client.remote(remote.owner));
        JsonObject paused = rig.host.sent("private_heartbeat").getLast();
        assertFalse(paused.get("viewEnabled").getAsBoolean());
        assertEquals(1, paused.getAsJsonArray("bindings").size());
        assertEquals(rig.host.local.owner().toString(),
                paused.getAsJsonArray("bindings").get(0).getAsJsonObject().get("owner").getAsString());
        delayed.complete(NativeModelBundle.decode(rig.bundle, ""));
        rig.host.drain();
        assertEquals(0, rig.host.prepared);
        assertTrue(rig.host.sent("private_ready").isEmpty());
        assertTrue(rig.host.sent("private_status").stream()
                .noneMatch(packet -> packet.get("status").getAsString().equals("rejected")));
        assertTrue(rig.host.sent("clear").isEmpty());
        rig.host.viewEnabled = true;
        rig.client.viewerRenderingChanged();
        assertTrue(rig.host.sent("private_heartbeat").getLast().get("viewEnabled").getAsBoolean());
        rig.receive(remote.packet(), NOW);
        rig.host.drain();
        rig.receive(remote.identity("private_ack"), NOW);
        assertTrue(rig.client.remote(remote.owner).active);
        assertEquals(remote.generation, rig.host.visible.get(remote.owner));
        assertTrue(rig.client.committed());
    }

    @Test
    void viewerStateRetriesCongestedControlAndLegacyServersReceiveOnlyTheirOriginalHeartbeatFields() throws Exception
    {
        Rig current = new Rig();
        current.acknowledgeViewState(false, true);
        current.host.blockedTypes.add("private_heartbeat");
        current.host.viewEnabled = false;
        current.client.viewerRenderingChanged();
        assertEquals(1, current.host.attempts("private_heartbeat"));
        current.client.viewerRenderingChanged();
        assertEquals(1, current.host.attempts("private_heartbeat"));
        current.host.blockedTypes.remove("private_heartbeat");
        JsonObject alive = heartbeat();
        alive.addProperty("viewEnabled", true);
        current.receive(alive, NOW + SECOND);
        current.client.tick(NOW + SECOND);
        assertFalse(current.host.sent("private_heartbeat").getLast().get("viewEnabled").getAsBoolean());
        Rig legacy = new Rig();
        legacy.acknowledge(false, true);
        legacy.host.cached = CompletableFuture.completedFuture(legacy.bundle);
        Offer offer = legacy.offer();
        legacy.activate(offer);
        legacy.host.viewEnabled = false;
        legacy.client.viewerRenderingChanged();
        legacy.client.tick(NOW);
        JsonObject packet = legacy.host.sent("private_heartbeat").getLast();
        assertEquals(Set.of("protocol", "type", "bindings"), packet.keySet());
        assertTrue(packet.getAsJsonArray("bindings").isEmpty());
        assertTrue(legacy.host.sent("private_status").stream()
                .noneMatch(value -> value.get("status").getAsString().equals("rejected")));
        legacy.host.viewEnabled = true;
        legacy.client.viewerRenderingChanged();
        legacy.receive(offer.packet(), NOW);
        legacy.host.drain();
        legacy.receive(offer.identity("private_ack"), NOW);
        assertTrue(legacy.client.remote(offer.owner).active);
    }

    @Test
    void malformedViewerStateEchoCannotRevokePermissionOrCancelTheActiveReceipt() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeViewState(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        Offer offer = rig.offer();
        rig.activate(offer);
        JsonObject malformed = heartbeat();
        malformed.addProperty("allowedView", false);
        malformed.addProperty("viewEnabled", 1);
        rig.receive(malformed, NOW);
        assertTrue(rig.client.canView());
        assertTrue(rig.client.remote(offer.owner).active);
        assertEquals(PrivateModelSyncClient.ReceivingPhase.ACTIVE, rig.client.receivedModels().getFirst().phase());
    }

    @Test
    void receivingDiagnosticsSeparateChecksumCacheHitReadinessAcknowledgementAndExactRenderFailure() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeViewState(false, true);
        Offer offer = rig.offer();
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        JsonObject packet = offer.packet();
        packet.addProperty("modelId", "ysm:wine_fox.ysm");
        rig.receive(packet, NOW);
        assertEquals(PrivateModelSyncClient.ReceivingPhase.CHECKING_CACHE,
                rig.client.receivedModels().getFirst().phase());
        rig.host.drain();
        PrivateModelSyncClient.ReceivedModel prepared = rig.client.receivedModels().getFirst();
        assertTrue(prepared.cacheHit());
        assertTrue(prepared.ready());
        assertFalse(prepared.active());
        assertEquals("ysm:wine_fox.ysm", prepared.modelId());
        assertEquals(offer.bytes, prepared.receivedBytes());
        assertEquals(PrivateModelSyncClient.ReceivingPhase.WAITING_ACK, prepared.phase());
        rig.receive(offer.identity("private_ack"), NOW);
        assertEquals(PrivateModelSyncClient.ReceivingPhase.ACTIVE, rig.client.receivedModels().getFirst().phase());
        assertFalse(rig.client.rejectRemote(offer.owner, UUID.randomUUID(), "stale"));
        assertTrue(rig.client.remote(offer.owner).active);
        assertTrue(rig.client.rejectRemote(offer.owner, offer.generation, "贴图绘制失败\n恢复原版"));
        assertNull(rig.client.remote(offer.owner));
        assertTrue(rig.host.visible.isEmpty());
        PrivateModelSyncClient.ReceivedModel failure = rig.client.receivedModels().getFirst();
        assertEquals(PrivateModelSyncClient.ReceivingPhase.FAILED, failure.phase());
        assertFalse(failure.ready());
        assertFalse(failure.active());
        assertFalse(failure.failure().contains("\n"));
        assertThrows(UnsupportedOperationException.class, () -> rig.client.receivedModels().clear());
        rig.client.reset();
        assertTrue(rig.client.receivedModels().isEmpty());
    }

    @Test
    void liveReceivingMetadataCannotUseUnsafeSourceIdsOrTurnGalleryQueriesIntoUploads() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeViewState(true, true);
        Offer offer = rig.offer();
        JsonObject invalid = offer.packet();
        invalid.addProperty("modelId", "local:../../outside.bbmodel");
        rig.receive(invalid, NOW);
        rig.host.drain();
        assertTrue(rig.client.receivedModels().isEmpty());
        assertEquals(0, rig.host.cacheReads);
        rig.receive(offer.packet(), NOW);
        rig.host.drain();
        for (int i = 0; i < 5; i++)
        {
            rig.client.receivedModels();
        }

        assertEquals(1, rig.host.cacheReads);
        assertEquals(0, rig.host.bundleReads);
        assertTrue(rig.host.sent("upload_offer").isEmpty());
        assertEquals(PrivateModelSyncClient.ReceivingPhase.WAITING_DOWNLOAD,
                rig.client.receivedModels().getFirst().phase());
    }

    @Test
    void negotiatedUploadWindowWaitsForMatchingAbsoluteCreditAndDuplicateGrantsCannotSendAhead() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, false, false, true);
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject offer = rig.host.sent("upload_offer").getLast();
        JsonObject accept = publicationNotice("upload_accept", rig.host.local.owner(), offer);
        UUID upload = UUID.randomUUID();
        accept.addProperty("uploadId", upload.toString());
        accept.addProperty("chunkBytes", 128);
        rig.receive(accept, NOW);
        rig.client.tick(NOW);
        assertTrue(rig.host.sent("upload_chunk").isEmpty());
        JsonObject credit = PrivateModelSyncClient.envelope("upload_credit");
        credit.addProperty("uploadId", UUID.randomUUID().toString());
        credit.addProperty("nextIndex", 2);
        rig.receive(credit, NOW);
        rig.client.tick(NOW);
        assertTrue(rig.host.sent("upload_chunk").isEmpty());
        credit.addProperty("uploadId", upload.toString());
        rig.receive(credit, NOW);
        rig.client.tick(NOW);
        rig.client.tick(NOW);
        assertEquals(2, rig.host.sent("upload_chunk").size());
        rig.receive(credit, NOW);
        rig.client.tick(NOW);
        assertEquals(2, rig.host.sent("upload_chunk").size());
        credit.addProperty("nextIndex", 3);
        rig.receive(credit, NOW);
        rig.client.tick(NOW);
        assertEquals(3, rig.host.sent("upload_chunk").size());
        assertEquals(2, rig.host.sent("upload_chunk").getLast().get("index").getAsInt());
    }

    @Test
    void structuredTerminalErrorsDoNotRetryAndBusyRetryStopsAfterThreeFailures() throws Exception
    {
        Rig terminal = new Rig();
        terminal.acknowledge(true, false);
        terminal.host.explicitLocal = true;
        terminal.client.tick(NOW);
        terminal.host.drain();
        JsonObject invalid = PrivateModelSyncClient.envelope("error");
        invalid.addProperty("code", "model_complexity_limit");
        invalid.addProperty("stage", "validation");
        invalid.addProperty("retryable", false);
        invalid.addProperty("retryAfter", 0);
        terminal.receive(invalid, NOW);
        terminal.receive(heartbeat(), NOW + 30 * SECOND);
        terminal.client.tick(NOW + 30 * SECOND);
        assertEquals(1, terminal.host.bundleReads);
        terminal.client.stopPublishing();
        terminal.client.tick(NOW + 30 * SECOND);
        terminal.host.drain();
        assertEquals(2, terminal.host.bundleReads);
        Rig busy = new Rig();
        busy.acknowledge(true, false);
        busy.host.explicitLocal = true;
        JsonObject rejected = PrivateModelSyncClient.envelope("error");
        rejected.addProperty("code", "asset_queue_full");
        rejected.addProperty("stage", "upload");
        rejected.addProperty("retryable", true);
        rejected.addProperty("retryAfter", 2);
        for (int attempt = 0; attempt < 3; attempt++)
        {
            long now = NOW + attempt * 3 * SECOND;
            busy.receive(heartbeat(), now);
            busy.client.tick(now);
            busy.host.drain();
            busy.receive(rejected, now);
        }
        busy.receive(heartbeat(), NOW + 30 * SECOND);
        busy.client.tick(NOW + 30 * SECOND);
        busy.host.drain();
        assertEquals(3, busy.host.bundleReads);
    }

    @ParameterizedTest(name = "{0} failure waits for explicit sharing retry")
    @ValueSource(strings =
    { "terminal", "busy" })
    void explicitRestartUnlocksTheSavedModelAfterFailureWithoutAcceptingTheOldGeneration(String mode) throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        String id = rig.host.local.modelId();
        rig.savedModels.put(id, savedEntry(id, rig.bundle, "bbmodel"));
        rig.catalog(true, NOW);
        rig.host.drain();
        List<PrivateUploadCatalogSnapshot.Model> savedDirectory = rig.client.uploadedModels();
        int sourceReads = rig.host.bundleReads;
        rig.host.explicitLocal = true;
        int attempts = mode.equals("busy") ? 3 : 1;
        PrivateModelSyncClient.PublicationIdentity blocked = null;
        JsonObject oldCommit = null;
        for (int i = 0; i < attempts; i++)
        {
            long now = NOW + i * 3 * SECOND;
            rig.receive(heartbeat(), now);
            rig.client.tick(now);
            rig.host.drain();
            JsonObject offered = rig.host.sent("upload_offer").getLast();
            blocked = new PrivateModelSyncClient.PublicationIdentity(id,
                    UUID.fromString(offered.get("generation").getAsString()), offered.get("hash").getAsString());
            assertNull(rig.client.publicationIdentity(), "Pending uploads cannot be presented as a confirmed share");
            assertEquals(AssetTransfer.hash(rig.bundle), blocked.hash());
            oldCommit = publicationNotice("upload_committed", rig.host.local.owner(), offered);
            JsonObject failure = publicationNotice("error", rig.host.local.owner(), offered);
            failure.addProperty("code", mode.equals("busy") ? "asset_queue_full" : "model_complexity_limit");
            failure.addProperty("stage", mode.equals("busy") ? "upload" : "validation");
            failure.addProperty("retryable", mode.equals("busy"));
            failure.addProperty("retryAfter", 2);
            rig.receive(failure, now);
            assertFalse(rig.client.committed());
            assertNull(rig.client.publicationIdentity());
        }
        assertEquals(attempts, rig.host.sent("upload_offer").size());
        assertEquals(PrivateModelSyncClient.UploadPhase.UPLOADED, rig.client.uploadState(id).phase(),
                "The saved badge is independent of the failed live publication");
        long retry = NOW + 30 * SECOND;
        rig.receive(heartbeat(), retry);
        rig.client.tick(retry);
        rig.host.drain();
        assertEquals(sourceReads + attempts, rig.host.bundleReads);
        assertEquals(attempts, rig.host.sent("upload_offer").size());
        assertEquals(savedDirectory, rig.client.uploadedModels());
        assertTrue(rig.client.uploadedModelIds().contains(id));

        rig.client.restartPublishing();
        assertEquals("已请求重新分享，等待本机模型准备", rig.client.status());
        rig.client.tick(retry);
        assertNull(rig.client.publicationIdentity());
        rig.host.drain();
        JsonObject current = rig.host.sent("upload_offer").getLast();
        assertEquals(attempts + 1, rig.host.sent("upload_offer").size());
        assertEquals(blocked.hash(), current.get("hash").getAsString());
        assertNotEquals(blocked.generation().toString(), current.get("generation").getAsString());
        assertNull(rig.client.publicationIdentity());
        rig.receive(oldCommit, retry);
        assertFalse(rig.client.committed());
        assertNull(rig.client.publicationIdentity());
        assertEquals(savedDirectory, rig.client.uploadedModels());
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), current), retry);
        assertTrue(rig.client.committed());
        PrivateModelSyncClient.PublicationIdentity currentIdentity = rig.client.publicationIdentity();
        assertNotNull(currentIdentity);
        assertEquals(blocked.hash(), currentIdentity.hash());
        assertNotEquals(blocked, currentIdentity);
        assertTrue(rig.client.uploadState(id).published());
        assertEquals(savedDirectory, rig.client.uploadedModels());
        assertTrue(rig.host.sent("upload_chunk").isEmpty());
        assertTrue(rig.host.sent("upload_end").isEmpty());
    }

    @Test
    void serverFlightStateWorksWithCachedAssetsAndChangesWithoutReplacingTheRemoteOrExtraAction() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        Offer offer = rig.offer();
        JsonObject packet = offer.packet();
        packet.add("state", flight(true));
        rig.receive(packet, NOW);
        rig.host.drain();
        PrivateModelSyncClient.Remote remote = rig.client.remote(offer.owner);
        assertTrue(remote.flying);
        assertFalse(rig.client.isFlying(offer.owner));
        rig.receive(offer.identity("private_ack"), NOW);
        assertTrue(rig.client.isFlying(offer.owner));
        JsonObject originalExtra = remote.extra.deepCopy();
        JsonObject grounded = offer.state(1, 1);
        grounded.add("state", flight(false));
        rig.receive(grounded, NOW);
        assertFalse(rig.client.isFlying(offer.owner));
        assertSame(remote, rig.client.remote(offer.owner));
        assertEquals(originalExtra, remote.extra);
        assertEquals(1, rig.host.decodes);
        assertEquals(1, rig.host.prepared);
        JsonObject repeated = offer.state(1, 1);
        repeated.add("state", flight(true));
        rig.receive(repeated, NOW);
        assertFalse(remote.flying);
        JsonObject stale = offer.state(99, 1);
        stale.addProperty("generation", UUID.randomUUID().toString());
        stale.add("state", flight(true));
        rig.receive(stale, NOW);
        assertFalse(remote.flying);
        JsonObject flying = offer.state(2, 1);
        flying.add("state", flight(true));
        rig.receive(flying, NOW);
        assertTrue(rig.client.isFlying(offer.owner));
        JsonObject revoked = heartbeat();
        revoked.addProperty("allowedView", false);
        rig.receive(revoked, NOW);
        assertFalse(rig.client.isFlying(offer.owner));
    }

    @Test
    void legacyOfferAndStateDefaultToNoAuthoritativeFlightAndMalformedFlightCannotChangeCurrentState() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        Offer offer = rig.offer();
        rig.activate(offer);
        assertFalse(rig.client.isFlying(offer.owner));
        JsonObject flying = offer.state(1, 1);
        flying.add("state", flight(true));
        rig.receive(flying, NOW);
        assertTrue(rig.client.isFlying(offer.owner));
        for (String invalid : List.of("{\"flying\":1}", "{\"flying\":\"true\"}", "{\"flying\":true,\"positionX\":10}",
                "[]"))
        {
            JsonObject state = offer.state(2, 1);
            state.add("state", JsonParser.parseString(invalid));
            rig.receive(state, NOW);
            assertTrue(rig.client.isFlying(offer.owner));
            assertEquals(1, rig.client.remote(offer.owner).sequence);
        }
        rig.receive(offer.state(2, 1), NOW);
        assertFalse(rig.client.isFlying(offer.owner));
        JsonObject empty = offer.state(3, 1);
        empty.add("state", new JsonObject());
        rig.receive(empty, NOW);
        assertFalse(rig.client.isFlying(offer.owner));
    }

    @Test
    void anUnnegotiatedOrUnauthorizedOfferCannotLoadOrRenderAModel() throws Exception
    {
        Rig rig = new Rig();
        Offer offer = rig.offer();
        rig.receive(offer.packet(), NOW);
        rig.receive(offer.identity("private_ack"), NOW);
        rig.host.drain();
        assertTrue(rig.client.remotes().isEmpty());
        assertEquals(0, rig.host.cacheReads);
        assertEquals(0, rig.host.decodes);
        rig.acknowledge(false, false);
        rig.receive(offer.packet(), NOW);
        rig.receive(offer.identity("private_ack"), NOW);
        rig.host.drain();
        assertTrue(rig.client.remotes().isEmpty());
        assertTrue(rig.host.visible.isEmpty());
        assertEquals(0, rig.host.prepared);
    }

    @Test
    void aValidatedCacheStillWaitsForMatchingServerAcknowledgement() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        Offer offer = rig.offer();
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        rig.receive(offer.packet(), NOW);
        rig.host.drain();
        assertEquals(1, rig.host.decodes);
        assertEquals(1, rig.host.prepared);
        assertTrue(rig.client.remote(offer.owner).ready);
        assertFalse(rig.client.remote(offer.owner).active);
        assertTrue(rig.host.visible.isEmpty());
        assertEquals(1, rig.host.sent("private_ready").size());
        rig.receive(offer.event(1, List.of(7d)), NOW);
        assertTrue(rig.host.events.isEmpty());
        JsonObject stale = offer.identity("private_ack");
        stale.addProperty("generation", UUID.randomUUID().toString());
        rig.receive(stale, NOW);
        assertTrue(rig.host.visible.isEmpty());
        rig.receive(offer.identity("private_ack"), NOW);
        assertEquals(offer.generation, rig.host.visible.get(offer.owner));
    }

    @Test
    void cachedFeedbackAndReadinessRetryOncePerSecondUntilTheExactAcknowledgement() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        rig.host.blockedTypes.addAll(Set.of("private_status", "private_ready"));
        Offer offer = rig.offer();
        rig.receive(offer.packet(), NOW);
        rig.host.drain();
        assertEquals(1, rig.host.attempts("private_status"));
        assertEquals(0, rig.host.attempts("private_ready"));
        rig.receive(offer.packet(), NOW + SECOND / 2);
        rig.client.tick(NOW + SECOND / 2);
        assertEquals(1, rig.host.attempts("private_status"));
        assertFalse(rig.client.remote(offer.owner).active);
        rig.host.blockedTypes.remove("private_status");
        rig.receive(heartbeat(), NOW + SECOND);
        rig.client.tick(NOW + SECOND);
        assertEquals(1, rig.host.sent("private_status").size());
        assertEquals(1, rig.host.attempts("private_ready"));
        rig.client.tick(NOW + SECOND + SECOND / 2);
        assertEquals(1, rig.host.attempts("private_ready"));
        rig.host.blockedTypes.remove("private_ready");
        rig.receive(heartbeat(), NOW + 2 * SECOND);
        rig.client.tick(NOW + 2 * SECOND);
        assertEquals(1, rig.host.sent("private_ready").size());
        assertTrue(rig.host.visible.isEmpty());
        JsonObject stale = offer.identity("private_ack");
        stale.addProperty("generation", UUID.randomUUID().toString());
        rig.receive(stale, NOW + 2 * SECOND);
        assertFalse(rig.client.remote(offer.owner).active);
        rig.receive(heartbeat(), NOW + 3 * SECOND);
        rig.client.tick(NOW + 3 * SECOND);
        assertEquals(2, rig.host.sent("private_ready").size());
        rig.receive(offer.identity("private_ack"), NOW + 3 * SECOND);
        rig.client.tick(NOW + 3 * SECOND + SECOND / 2);
        assertEquals(2, rig.host.sent("private_ready").size());
        assertEquals(offer.generation, rig.host.visible.get(offer.owner));
    }

    @Test
    void missingFeedbackSurvivesCongestionWithoutRequestingAnotherModelOrDecodingUnsentBytes() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.host.blockedTypes.add("private_status");
        Offer offer = rig.offer();
        rig.receive(offer.packet(), NOW);
        rig.host.drain();
        assertEquals(0, rig.host.decodes);
        rig.host.blockedTypes.remove("private_status");
        rig.receive(heartbeat(), NOW + SECOND);
        rig.client.tick(NOW + SECOND);
        assertEquals(offer.offerId.toString(), rig.host.sent("private_status").getLast().get("offerId").getAsString());
        assertEquals("missing", rig.host.sent("private_status").getLast().get("status").getAsString());
        assertEquals(1, rig.host.cacheReads);
        assertTrue(rig.host.sent("asset_request").isEmpty());
    }

    @Test
    void successfulTransportWritesRetryFeedbackUntilTheServerConfirmsTheirMeaning() throws Exception
    {
        Rig missing = new Rig();
        missing.acknowledge(false, true);
        Offer download = missing.offer();
        missing.receive(download.packet(), NOW);
        missing.host.drain();
        missing.receive(heartbeat(), NOW + SECOND);
        missing.client.tick(NOW + SECOND);
        assertEquals(2, missing.host.sent("private_status").size());
        missing.begin(download);
        missing.receive(heartbeat(), NOW + 2 * SECOND);
        missing.client.tick(NOW + 2 * SECOND);
        assertEquals(2, missing.host.sent("private_status").size());
        Rig cached = new Rig();
        cached.acknowledge(false, true);
        cached.host.cached = CompletableFuture.completedFuture(cached.bundle);
        Offer ready = cached.offer();
        cached.receive(ready.packet(), NOW);
        cached.host.drain();
        cached.receive(heartbeat(), NOW + SECOND);
        cached.client.tick(NOW + SECOND);
        assertEquals(2, cached.host.sent("private_status").size());
        assertEquals(2, cached.host.sent("private_ready").size());
        cached.receive(ready.identity("private_ack"), NOW + SECOND);
        cached.client.tick(NOW + SECOND + SECOND / 2);
        assertEquals(2, cached.host.sent("private_status").size());
        assertEquals(2, cached.host.sent("private_ready").size());
    }

    @Test
    void publicationOffersAndEndsRetryTheirExactIdentityAndDuplicateAcceptNeverRestartsChunks() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, false);
        rig.host.explicitLocal = true;
        rig.host.blockedTypes.add("upload_offer");
        rig.client.tick(NOW);
        rig.host.drain();
        assertEquals(1, rig.host.attempts("upload_offer"));
        rig.host.blockedTypes.remove("upload_offer");
        rig.receive(heartbeat(), NOW + SECOND);
        rig.client.tick(NOW + SECOND);
        JsonObject offer = rig.host.sent("upload_offer").getLast();
        rig.receive(heartbeat(), NOW + 2 * SECOND);
        rig.client.tick(NOW + 2 * SECOND);
        assertEquals(offer, rig.host.sent("upload_offer").getLast());
        JsonObject accept = publicationNotice("upload_accept", rig.host.local.owner(), offer);
        accept.addProperty("uploadId", UUID.randomUUID().toString());
        accept.addProperty("chunkBytes", 128);
        rig.receive(accept, NOW + 2 * SECOND);
        rig.client.tick(NOW + 2 * SECOND);
        assertEquals(0, rig.host.sent("upload_chunk").getLast().get("index").getAsInt());
        rig.receive(accept, NOW + 2 * SECOND);
        rig.client.tick(NOW + 2 * SECOND);
        assertEquals(1, rig.host.sent("upload_chunk").getLast().get("index").getAsInt());
        for (int i = 0; i < rig.bundle.length / 128 + 2; i++)
        {
            rig.client.tick(NOW + 2 * SECOND);
        }

        assertEquals(1, rig.host.sent("upload_end").size());
        JsonObject end = rig.host.sent("upload_end").getLast();
        int chunks = rig.host.sent("upload_chunk").size();
        rig.client.tick(NOW + 2 * SECOND + SECOND / 2);
        assertEquals(1, rig.host.sent("upload_end").size());
        rig.receive(heartbeat(), NOW + 3 * SECOND);
        rig.client.tick(NOW + 3 * SECOND);
        assertEquals(2, rig.host.sent("upload_end").size());
        assertEquals(end, rig.host.sent("upload_end").getLast());
        assertEquals(chunks, rig.host.sent("upload_chunk").size());
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), offer), NOW + 3 * SECOND);
        rig.receive(heartbeat(), NOW + 4 * SECOND);
        rig.client.tick(NOW + 4 * SECOND);
        assertTrue(rig.client.committed());
        assertEquals(2, rig.host.sent("upload_end").size());
    }

    @Test
    void sixteenOfferedModelsShareABoundedControlBudgetAndLaterOwnersStillBecomeReady() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        List<Offer> offers = new ArrayList<>();
        for (int i = 0; i < 16; i++)
        {
            Offer offer = rig.offer();
            offers.add(offer);
            rig.receive(offer.packet(), NOW);
            rig.host.drain();
        }
        Set<String> controls = Set.of("private_status", "private_ready", "upload_offer", "upload_end");
        assertEquals(8, rig.host.controlTimes.stream()
                .filter(value -> controls.contains(value.type) && value.time == NOW).count());
        Set<UUID> acknowledged = new HashSet<>();
        for (int frame = 1; frame <= 80; frame++)
        {
            long now = NOW + frame * 50_000_000L;
            rig.host.now = now;
            JsonObject heartbeat = heartbeat();
            JsonArray bindings = heartbeat.getAsJsonArray("bindings");
            for (Offer offer : offers)
            {
                JsonObject value = offer.identity("heartbeat");
                value.remove("protocol");
                value.remove("type");
                bindings.add(value);
            }
            rig.receive(heartbeat, now);
            rig.client.tick(now);
            for (JsonObject packet : rig.host.sent("private_ready"))
            {
                UUID owner = UUID.fromString(packet.get("owner").getAsString());
                if (acknowledged.add(owner))
                {
                    rig.receive(offers.stream().filter(value -> value.owner.equals(owner)).findFirst().orElseThrow()
                            .identity("private_ack"), now);
                }

            }
        }
        assertEquals(16, acknowledged.size());
        assertEquals(16, rig.host.visible.size());
        List<FakeHost.TimedControl> attempts = rig.host.controlTimes.stream()
                .filter(value -> controls.contains(value.type)).toList();
        for (FakeHost.TimedControl attempt : attempts)

        {
            assertTrue(attempts.stream()
                    .filter(value -> value.time >= attempt.time && value.time < attempt.time + SECOND).count() <= 16,
                    "One-second handshake budget exceeded");
        }

    }

    @Test
    void heartbeatPermissionsRevokeUploadsAndViewingIndependentlyWithoutLosingTheUsersShareIntent() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, true);
        rig.host.explicitLocal = true;
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        Offer offer = rig.offer();
        rig.activate(offer);
        assertNotNull(rig.client.remote(offer.owner),
                "The fixture must decode before checking independent permissions");
        assertTrue(rig.client.remote(offer.owner).active);
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject upload = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), upload), NOW);
        assertTrue(rig.client.committed());
        JsonObject revokeUpload = heartbeat();
        revokeUpload.addProperty("allowedUpload", false);
        rig.receive(revokeUpload, NOW);
        assertFalse(rig.client.available());
        assertFalse(rig.client.committed());
        assertTrue(rig.client.canView());
        assertNotNull(rig.client.remote(offer.owner));
        assertEquals(1, rig.host.sent("clear").size());
        assertTrue(rig.host.explicitLocal);
        JsonObject revokeView = heartbeat();
        revokeView.addProperty("allowedView", false);
        rig.receive(revokeView, NOW);
        assertFalse(rig.client.canView());
        assertTrue(rig.client.remotes().isEmpty());
        assertTrue(rig.host.visible.isEmpty());
        JsonObject grant = heartbeat();
        grant.addProperty("allowedUpload", true);
        grant.addProperty("allowedView", true);
        rig.receive(grant, NOW + 1);
        rig.client.tick(NOW + 1);
        rig.host.drain();
        assertTrue(rig.client.available());
        assertTrue(rig.client.canView());
        assertEquals(2, rig.host.sent("upload_offer").size());
        assertNotEquals(upload.get("generation"), rig.host.sent("upload_offer").getLast().get("generation"));
    }

    @Test
    void legacyHeartbeatsPreserveNegotiatedPermissionsAndViewingDoesNotRequirePublishing() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.receive(heartbeat(), NOW);
        assertFalse(rig.client.available());
        assertTrue(rig.client.canView());
        assertFalse(rig.host.explicitLocal);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        Offer offer = rig.offer();
        rig.activate(offer);
        assertEquals(offer.generation, rig.host.visible.get(offer.owner));
        assertEquals(0, rig.host.bundleReads);
    }

    @Test
    void aServerCachedPublicationCommitsWithoutAnUploadAcceptOrDuplicateBytes() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, false);
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject upload = rig.host.sent("upload_offer").getLast();
        assertEquals(rig.bundle.length, rig.client.publicationBytes());
        assertEquals(0, rig.client.uploadedBytes());
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), upload), NOW);
        rig.client.tick(NOW);
        assertTrue(rig.client.committed());
        assertTrue(rig.host.sent("upload_chunk").isEmpty());
        assertTrue(rig.host.sent("upload_end").isEmpty());
        assertEquals(0, rig.client.uploadedBytes());
        assertTrue(rig.client.status().contains("已分享"));
        assertTrue(rig.client.status().contains("复用服务器缓存"));
    }

    @Test
    void explicitSharingAgainReusesSavedMatchingBytesWithANewGenerationAndIgnoresThePreviousCommit() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        String id = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject original = rig.host.sent("upload_offer").getLast();
        JsonObject oldCommit = publicationNotice("upload_committed", rig.host.local.owner(), original);
        rig.receive(oldCommit, NOW);
        rig.confirmSaved(id, original, NOW);
        assertTrue(rig.client.committed());
        assertTrue(rig.client.uploadedModelIds().contains(id));
        PrivateModelSyncClient.PublicationIdentity originalIdentity = rig.client.publicationIdentity();
        assertNotNull(originalIdentity);
        List<PrivateUploadCatalogSnapshot.Model> savedDirectory = rig.client.uploadedModels();
        assertEquals(1, savedDirectory.size());
        assertEquals(AssetTransfer.hash(rig.bundle), savedDirectory.getFirst().hash());

        rig.host.explicitLocal = false;
        rig.client.stopPublishing();
        for (int i = 1; i <= 3; i++)
        {
            long now = NOW + i * SECOND / 8;
            rig.receive(heartbeat(), now);
            rig.client.tick(now);
            rig.host.drain();
        }
        assertEquals(1, rig.host.sent("upload_offer").size(),
                "Stopping sharing must not resume a server-saved model automatically");
        assertFalse(rig.client.committed());
        assertEquals(savedDirectory, rig.client.uploadedModels());
        assertNull(rig.client.publicationIdentity());
        assertEquals(PrivateModelSyncClient.UploadPhase.UPLOADED, rig.client.uploadState(id).phase());
        assertFalse(rig.client.uploadState(id).published());

        long again = NOW + SECOND / 2;
        rig.host.explicitLocal = true;
        rig.client.tick(again);
        rig.host.drain();
        JsonObject current = rig.host.sent("upload_offer").getLast();
        assertEquals(2, rig.host.sent("upload_offer").size());
        assertNotEquals(original.get("generation"), current.get("generation"));
        assertEquals(original.get("hash"), current.get("hash"));
        assertEquals(id, current.get("modelId").getAsString());
        assertFalse(rig.client.committed());
        assertNull(rig.client.publicationIdentity());
        rig.receive(oldCommit, again);
        assertFalse(rig.client.committed(), "A late previous-generation ACK cannot publish the new request");
        assertEquals(PrivateModelSyncClient.UploadPhase.WAITING_APPROVAL, rig.client.uploadState(id).phase());
        assertEquals(savedDirectory, rig.client.uploadedModels());
        assertTrue(rig.client.uploadedModelIds().contains(id));

        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), current), again);
        rig.receive(oldCommit, again);
        assertTrue(rig.client.committed());
        PrivateModelSyncClient.PublicationIdentity currentIdentity = rig.client.publicationIdentity();
        assertNotNull(currentIdentity);
        assertEquals(originalIdentity.hash(), currentIdentity.hash());
        assertNotEquals(originalIdentity, currentIdentity);
        assertEquals(original.get("generation").getAsString(), originalIdentity.generation().toString(),
                "A captured confirmation cannot mutate when the same source is published again");
        assertEquals(PrivateModelSyncClient.UploadPhase.UPLOADED, rig.client.uploadState(id).phase());
        assertTrue(rig.client.uploadState(id).published());
        assertEquals(savedDirectory, rig.client.uploadedModels());
        assertEquals(0, rig.client.uploadedBytes());
        assertTrue(rig.host.sent("upload_chunk").isEmpty());
        assertTrue(rig.host.sent("upload_end").isEmpty());
        assertTrue(rig.client.status().contains("复用服务器缓存"));
    }

    @Test
    void theLastPublishedAppearanceRetriesEveryTwoSecondsWithoutStartingTheActionAgain() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, false);
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject upload = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), upload), NOW);
        rig.client.tick(NOW);
        JsonObject initial = rig.host.sent("private_state").getLast();
        assertEquals(1, rig.host.sent("private_state").size());
        rig.receive(heartbeat(), NOW + SECOND);
        rig.client.tick(NOW + SECOND);
        assertEquals(1, rig.host.sent("private_state").size());
        rig.receive(heartbeat(), NOW + 2 * SECOND);
        rig.client.tick(NOW + 2 * SECOND);
        assertEquals(2, rig.host.sent("private_state").size());
        assertEquals(initial, rig.host.sent("private_state").getLast());
        assertEquals(0, rig.host.sent("private_state").getLast().getAsJsonObject("extra").get("sequence").getAsInt());
    }

    @Test
    void uploadProgressAndServerRejectionAreReadableWithoutClearingExplicitSharing() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, false);
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject upload = rig.host.sent("upload_offer").getLast();
        JsonObject accept = publicationNotice("upload_accept", rig.host.local.owner(), upload);
        accept.addProperty("uploadId", UUID.randomUUID().toString());
        accept.addProperty("chunkBytes", 128);
        rig.receive(accept, NOW);
        rig.client.tick(NOW);
        assertEquals(128, rig.client.uploadedBytes());
        assertTrue(rig.client.status().contains("%"));
        JsonObject error = PrivateModelSyncClient.envelope("error");
        error.addProperty("code", "private_upload_denied");
        rig.receive(error, NOW);
        assertTrue(rig.client.status().startsWith("没有私人模型上传权限"));
        assertTrue(rig.client.status().contains("重新开启分享"));
        assertTrue(rig.host.explicitLocal);
        assertEquals(0, rig.client.publicationBytes());
        rig.receive(heartbeat(), NOW + 30 * SECOND);
        rig.client.tick(NOW + 30 * SECOND);
        rig.host.drain();
        assertEquals(1, rig.host.bundleReads,
                "A terminal denial preserves explicit sharing but does not archive/retry the same model automatically");
        assertTrue(rig.host.explicitLocal);
    }

    @Test
    void bytesWithTheWrongChecksumAbortTheOfferAndNeverReachTheDecoder() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        Offer offer = rig.offer();
        rig.receive(offer.packet(), NOW);
        rig.host.drain();
        rig.begin(offer);
        byte[] corrupt = rig.bundle.clone();
        corrupt[corrupt.length - 1] ^= 1;
        rig.chunk(offer, corrupt);
        rig.receive(offer.asset("asset_end"), NOW);
        rig.host.drain();
        assertNull(rig.client.remote(offer.owner));
        assertEquals(0, rig.host.decodes);
        assertTrue(rig.host.visible.isEmpty());
        assertEquals("rejected", rig.host.sent("private_status").getLast().get("status").getAsString());
        // A late valid end cannot revive the rejected authorization.
        rig.chunk(offer, rig.bundle);
        rig.receive(offer.asset("asset_end"), NOW);
        rig.host.drain();
        assertEquals(0, rig.host.decodes);
    }

    @Test
    void conflictingDuplicateChunksAbortRatherThanAcceptingTheEarlierChunk() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        Offer offer = rig.offer();
        rig.receive(offer.packet(), NOW);
        rig.host.drain();
        rig.begin(offer);
        rig.chunk(offer, rig.bundle);
        byte[] conflict = rig.bundle.clone();
        conflict[0] ^= 1;
        rig.chunk(offer, conflict);
        rig.receive(offer.asset("asset_end"), NOW);
        rig.host.drain();
        assertNull(rig.client.remote(offer.owner));
        assertEquals(0, rig.host.decodes);
        assertEquals(0, rig.host.prepared);
    }

    @Test
    void staleGenerationsAndRepeatedStateOrEventSequencesCannotChangeTheCurrentModel() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        Offer old = rig.offer();
        rig.activate(old);
        Offer current = new Offer(old.owner, UUID.randomUUID(), UUID.randomUUID(), old.hash, old.bytes);
        rig.activate(current);
        assertEquals(current.generation, rig.host.visible.get(current.owner));
        rig.receive(old.state(100, 7), NOW);
        assertEquals(1, rig.client.remote(current.owner).appearance.get("scale").getAsDouble());
        rig.receive(current.state(8, 2), NOW);
        rig.receive(current.state(7, 5), NOW);
        assertEquals(2, rig.client.remote(current.owner).appearance.get("scale").getAsDouble());
        rig.receive(old.event(100, List.of(99d)), NOW);
        rig.receive(current.event(8, List.of(3d)), NOW);
        rig.receive(current.event(8, List.of(4d)), NOW);
        rig.receive(current.event(7, List.of(5d)), NOW);
        assertEquals(List.of(new Event(current.owner, current.generation, List.of(3d))), rig.host.events);
        assertEquals(8, rig.client.remote(current.owner).sequence);
    }

    @Test
    void stoppingALocalPublicationDoesNotCancelAnAuthorizedRemoteDecode() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        CompletableFuture<LocalModelLibrary.Loaded> remoteDecode = new CompletableFuture<>();
        rig.host.decodeResults.add(remoteDecode);
        Offer remote = rig.offer();
        rig.receive(remote.packet(), NOW);
        rig.host.drain();
        assertEquals(1, rig.host.decodes);
        CompletableFuture<byte[]> localBuild = new CompletableFuture<>();
        rig.host.publication = localBuild;
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        assertEquals(1, rig.host.bundleReads);
        rig.client.stopPublishing();
        rig.host.explicitLocal = false;
        localBuild.complete(rig.bundle);
        remoteDecode.complete(NativeModelBundle.decode(rig.bundle, ""));
        rig.host.drain();
        assertTrue(rig.host.sent("upload_offer").isEmpty());
        assertEquals(1, rig.host.prepared);
        assertTrue(rig.client.remote(remote.owner).ready);
        rig.receive(remote.identity("private_ack"), NOW);
        assertEquals(remote.generation, rig.host.visible.get(remote.owner));
    }

    @Test
    void remoteLeasesExpireEvenWhenTheServerKeepsTheTransportAliveAndLateDecodesStayCancelled() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        Offer active = rig.offer();
        rig.activate(active);
        rig.receive(heartbeat(), NOW + 2 * SECOND);
        rig.client.tick(NOW + 2 * SECOND);
        assertNull(rig.client.remote(active.owner));
        assertTrue(rig.host.visible.isEmpty());
        CompletableFuture<LocalModelLibrary.Loaded> delayed = new CompletableFuture<>();
        rig.host.decodeResults.add(delayed);
        Offer pending = rig.offer();
        rig.receive(pending.packet(), NOW + 2 * SECOND);
        rig.host.drain();
        int prepared = rig.host.prepared;
        long expired = NOW + 63 * SECOND;
        rig.receive(heartbeat(), expired);
        rig.client.tick(expired);
        assertNull(rig.client.remote(pending.owner));
        delayed.complete(NativeModelBundle.decode(rig.bundle, ""));
        rig.host.drain();
        assertEquals(prepared, rig.host.prepared);
        assertTrue(rig.host.visible.isEmpty());
    }

    @Test
    void aConnectionResetInvalidatesCachedAndDecodedFuturesFromTheOldWorld() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        CompletableFuture<byte[]> cache = new CompletableFuture<>();
        rig.host.cached = cache;
        rig.receive(rig.offer().packet(), NOW);
        rig.client.reset();
        cache.complete(rig.bundle);
        rig.host.drain();
        assertEquals(0, rig.host.decodes);
        rig.acknowledge(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        CompletableFuture<LocalModelLibrary.Loaded> decoded = new CompletableFuture<>();
        rig.host.decodeResults.add(decoded);
        rig.receive(rig.offer().packet(), NOW);
        rig.host.drain();
        rig.host.channel = false;
        rig.client.tick(NOW);
        decoded.complete(NativeModelBundle.decode(rig.bundle, ""));
        rig.host.drain();
        assertTrue(rig.client.remotes().isEmpty());
        assertEquals(0, rig.host.prepared);
        assertTrue(rig.host.sent("private_ready").isEmpty());
    }

    @Test
    void onlyExplicitLocalSharingUploadsAndOnlyMatchingCommitMakesItPublished() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, true);
        rig.client.tick(NOW);
        rig.host.drain();
        assertEquals(0, rig.host.bundleReads);
        assertTrue(rig.host.sent("upload_offer").isEmpty());
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject offer = rig.host.sent("upload_offer").getFirst();
        JsonObject accept = PrivateModelSyncClient.envelope("upload_accept");
        accept.add("generation", offer.get("generation"));
        accept.add("hash", offer.get("hash"));
        accept.addProperty("uploadId", UUID.randomUUID().toString());
        accept.addProperty("chunkBytes", 8192);
        JsonObject wrong = accept.deepCopy();
        wrong.addProperty("generation", UUID.randomUUID().toString());
        rig.receive(wrong, NOW);
        rig.client.tick(NOW);
        assertTrue(rig.host.sent("upload_chunk").isEmpty());
        rig.receive(accept, NOW);
        rig.client.tick(NOW);
        rig.client.tick(NOW);
        assertEquals(1, rig.host.sent("upload_chunk").size());
        assertEquals(1, rig.host.sent("upload_end").size());
        JsonObject commit = PrivateModelSyncClient.envelope("upload_committed");
        commit.add("generation", offer.get("generation"));
        commit.add("hash", offer.get("hash"));
        JsonObject stale = commit.deepCopy();
        stale.addProperty("hash", "0".repeat(64));
        rig.receive(stale, NOW);
        assertFalse(rig.client.committed());
        rig.receive(commit, NOW);
        assertTrue(rig.client.committed());
        rig.host.explicitLocal = false;
        rig.client.tick(NOW);
        assertFalse(rig.client.committed());
        assertEquals(1, rig.host.sent("clear").size());
    }

    @Test
    void galleryUploadQueriesNeverReadSourceBytesOrStartAPublication() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, true);
        for (int i = 0; i < 3; i++)
        {
            PrivateModelSyncClient.UploadState state = rig.client.uploadState(rig.host.local.modelId());
            assertEquals(PrivateModelSyncClient.UploadPhase.NOT_UPLOADED, state.phase());
            assertFalse(state.uploaded());
            assertFalse(state.inProgress());
            assertFalse(state.published());
            assertTrue(rig.client.uploadedModelIds().isEmpty());
        }
        assertEquals(0, rig.host.bundleReads);
        assertTrue(rig.host.sent("upload_offer").isEmpty());
    }

    @Test
    void galleryUploadPhasesSeparatePublicationFromServerSavedDirectoryAfterUnsharing() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        CompletableFuture<byte[]> archive = new CompletableFuture<>();
        rig.host.publication = archive;
        String id = rig.host.local.modelId();
        rig.client.tick(NOW);
        assertEquals(PrivateModelSyncClient.UploadPhase.BUILDING, rig.client.uploadState(id).phase());
        archive.complete(rig.bundle);
        rig.host.drain();
        JsonObject offer = rig.host.sent("upload_offer").getLast();
        assertEquals(PrivateModelSyncClient.UploadPhase.WAITING_APPROVAL, rig.client.uploadState(id).phase());
        JsonObject accept = publicationNotice("upload_accept", rig.host.local.owner(), offer);
        accept.addProperty("uploadId", UUID.randomUUID().toString());
        accept.addProperty("chunkBytes", 128);
        rig.receive(accept, NOW);
        rig.client.tick(NOW);
        PrivateModelSyncClient.UploadState partial = rig.client.uploadState(id);
        assertEquals(PrivateModelSyncClient.UploadPhase.UPLOADING, partial.phase());
        assertEquals(128, partial.sentBytes());
        assertEquals(rig.bundle.length, partial.totalBytes());
        assertTrue(partial.inProgress());
        assertFalse(partial.uploaded());
        for (int i = 0; i < rig.bundle.length / 128 + 2; i++)
        {
            rig.client.tick(NOW);
        }

        assertEquals(PrivateModelSyncClient.UploadPhase.VALIDATING, rig.client.uploadState(id).phase());
        assertFalse(rig.client.uploadState(id).uploaded());
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), offer), NOW);
        assertEquals(PrivateModelSyncClient.UploadPhase.PUBLISHED, rig.client.uploadState(id).phase());
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        rig.confirmSaved(id, offer, NOW);
        PrivateModelSyncClient.UploadState uploaded = rig.client.uploadState(id);
        assertTrue(uploaded.uploaded());
        assertTrue(uploaded.published());
        assertFalse(uploaded.inProgress());
        assertEquals(offer.get("hash").getAsString(), uploaded.hash());
        assertEquals(Set.of(id), rig.client.uploadedModelIds());
        rig.host.explicitLocal = false;
        rig.client.tick(NOW);
        assertTrue(rig.client.uploadState(id).uploaded());
        assertFalse(rig.client.uploadState(id).published());
        assertEquals(Set.of(id), rig.client.uploadedModelIds());
        rig.client.reset();
        assertEquals(PrivateModelSyncClient.UploadPhase.NOT_UPLOADED, rig.client.uploadState(id).phase());
        assertTrue(rig.client.uploadedModelIds().isEmpty());
    }

    @Test
    void explicitSourceReloadInvalidatesSameIdReceiptsAndLateCommitsFromOldBytes() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        String id = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject old = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), old), NOW);
        rig.confirmSaved(id, old, NOW);
        assertTrue(rig.client.uploadState(id).uploaded());
        rig.host.publication = CompletableFuture.completedFuture(fixtureBundle(0xff00ffff));
        rig.client.invalidateSources();
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        assertFalse(rig.client.uploadState(id).uploaded());
        rig.client.tick(NOW + 1);
        rig.host.drain();
        JsonObject current = rig.host.sent("upload_offer").getLast();
        assertNotEquals(old.get("generation"), current.get("generation"));
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), old), NOW + 1);
        assertFalse(rig.client.uploadState(id).uploaded());
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), current), NOW + 1);
        assertFalse(rig.client.uploadState(id).uploaded());
        rig.confirmSaved(id, current, NOW + 1);
        assertTrue(rig.client.uploadState(id).uploaded());
        assertTrue(rig.client.uploadState(id).published());
    }

    @Test
    void aLateCommitCannotMarkADifferentSelectedModelAsUploadedBeforeTheNextTick() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        String oldId = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject old = rig.host.sent("upload_offer").getLast();
        rig.host.local = new PrivateModelSyncClient.Local(rig.host.local.owner(), "local:replacement.bbmodel",
                appearance(1), extra());
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), old), NOW);
        assertFalse(rig.client.committed());
        assertFalse(rig.client.uploadState(oldId).uploaded());
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        rig.client.tick(NOW + 1);
        rig.host.drain();
        JsonObject current = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), current), NOW + 1);
        rig.confirmSaved("local:replacement.bbmodel", current, NOW + 1);
        assertEquals(Set.of("local:replacement.bbmodel"), rig.client.uploadedModelIds());
    }

    @Test
    void galleryKeepsPerModelFailureWithoutClaimingRejectedBytesWereUploaded() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, true);
        rig.host.explicitLocal = true;
        String id = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject old = rig.host.sent("upload_offer").getLast();
        JsonObject reject = PrivateModelSyncClient.envelope("error");
        reject.addProperty("code", "private_bundle_invalid");
        rig.receive(reject, NOW);
        PrivateModelSyncClient.UploadState state = rig.client.uploadState(id);
        assertEquals(PrivateModelSyncClient.UploadPhase.FAILED, state.phase());
        assertTrue(state.message().contains("格式无效"));
        assertFalse(state.uploaded());
        assertFalse(state.published());
        assertFalse(state.inProgress());
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), old), NOW);
        assertEquals(PrivateModelSyncClient.UploadPhase.FAILED, rig.client.uploadState(id).phase());
        assertTrue(rig.client.uploadedModelIds().isEmpty());
    }

    @Test
    void receiptsTrackSeveralAcknowledgedModelsWithoutStartingUploadsDuringFiltering() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        String firstId = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject first = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), first), NOW);
        rig.confirmSaved(firstId, first, NOW);
        String secondId = "local:second.bbmodel";
        rig.host.local = new PrivateModelSyncClient.Local(rig.host.local.owner(), secondId, appearance(1), extra());
        rig.host.publication = CompletableFuture.completedFuture(fixtureBundle(0xff00ff00));
        rig.client.tick(NOW + 1);
        rig.host.drain();
        JsonObject second = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), second), NOW + 1);
        rig.confirmSaved(secondId, second, NOW + 1);
        int reads = rig.host.bundleReads;
        assertEquals(Set.of(firstId, secondId), rig.client.uploadedModelIds());
        assertTrue(rig.client.uploadState(firstId).uploaded());
        assertFalse(rig.client.uploadState(firstId).published());
        assertTrue(rig.client.uploadState(secondId).published());
        assertEquals(reads, rig.host.bundleReads);
        rig.host.channel = false;
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        assertFalse(rig.client.uploadState(secondId).uploaded());
        rig.client.tick(NOW + 2);
        rig.host.channel = true;
        rig.acknowledge(true, true);
        assertTrue(rig.client.uploadedModelIds().isEmpty());
    }

    @Test
    void aTamperedCacheDoesNotSkipValidationOrGrantRendering() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        byte[] corrupt = rig.bundle.clone();
        corrupt[0] ^= 1;
        rig.host.cached = CompletableFuture.completedFuture(corrupt);
        Offer offer = rig.offer();
        rig.receive(offer.packet(), NOW);
        rig.host.drain();
        rig.receive(offer.identity("private_ack"), NOW);
        assertEquals(0, rig.host.decodes);
        assertTrue(rig.host.visible.isEmpty());
        assertEquals("missing", rig.host.sent("private_status").getLast().get("status").getAsString());
    }

    @Test
    void persistentAuthorRoamingConfigurationIsRetainedAcrossOfferAndStateUpdates() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        Offer offer = rig.offer();
        JsonObject packet = offer.packet();
        packet.getAsJsonObject("appearance").getAsJsonObject("variables")
                .addProperty("variable.roaming.red_bow_headdress", 0);
        packet.getAsJsonObject("appearance").getAsJsonObject("variables").addProperty("variable.装饰.颜色", 3);
        rig.receive(packet, NOW);
        rig.host.drain();
        rig.receive(offer.identity("private_ack"), NOW);
        assertEquals(offer.generation, rig.host.visible.get(offer.owner));
        assertEquals(0, rig.client.remote(offer.owner).appearance.getAsJsonObject("variables")
                .get("variable.roaming.red_bow_headdress").getAsDouble());
        assertEquals(3, rig.client.remote(offer.owner).appearance.getAsJsonObject("variables").get("variable.装饰.颜色")
                .getAsDouble());
        JsonObject state = offer.state(1, 1);
        state.getAsJsonObject("appearance").getAsJsonObject("variables")
                .addProperty("variable.roaming.red_bow_headdress", 1);
        rig.receive(state, NOW);
        assertEquals(1, rig.client.remote(offer.owner).appearance.getAsJsonObject("variables")
                .get("variable.roaming.red_bow_headdress").getAsDouble());
    }

    @Test
    void aWorldRenderFailureRevokesOnlyTheMatchingPrivateInstanceAndStopsItsHeartbeat() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(false, true);
        rig.host.cached = CompletableFuture.completedFuture(rig.bundle);
        Offer old = rig.offer();
        rig.activate(old);
        Offer current = new Offer(old.owner, UUID.randomUUID(), UUID.randomUUID(), old.hash, old.bytes);
        rig.activate(current);
        assertFalse(rig.client.rejectRemote(old.owner, old.generation));
        assertEquals(current.generation, rig.host.visible.get(current.owner));
        assertTrue(rig.client.rejectRemote(current.owner, current.generation));
        assertNull(rig.client.remote(current.owner));
        assertTrue(rig.host.visible.isEmpty());
        JsonObject rejected = rig.host.sent("private_status").getLast();
        assertEquals(current.offerId.toString(), rejected.get("offerId").getAsString());
        assertEquals("rejected", rejected.get("status").getAsString());
        rig.receive(current.identity("private_ack"), NOW);
        rig.client.tick(NOW);
        assertTrue(rig.host.sent("private_heartbeat").getLast().getAsJsonArray("bindings").isEmpty());
        assertTrue(rig.host.visible.isEmpty());
    }

    @Test
    void serverRevocationClearsTheCurrentPublicationAndRetriesWithAFreshGenerationAfterBackoff() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledge(true, false);
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject offer = rig.host.sent("upload_offer").getLast();
        JsonObject committed = publicationNotice("upload_committed", rig.host.local.owner(), offer);
        rig.receive(committed, NOW);
        assertTrue(rig.client.committed());
        JsonObject remove = publicationNotice("private_remove", rig.host.local.owner(), offer);
        JsonObject wrongOwner = remove.deepCopy();
        wrongOwner.addProperty("owner", UUID.randomUUID().toString());
        rig.receive(wrongOwner, NOW);
        JsonObject wrongHash = remove.deepCopy();
        wrongHash.addProperty("hash", "0".repeat(64));
        rig.receive(wrongHash, NOW);
        assertTrue(rig.client.committed());
        rig.receive(remove, NOW);
        assertFalse(rig.client.committed());
        rig.receive(heartbeat(), NOW + 9 * SECOND);
        rig.client.tick(NOW + 9 * SECOND);
        assertEquals(1, rig.host.bundleReads);
        rig.receive(heartbeat(), NOW + 11 * SECOND);
        rig.client.tick(NOW + 11 * SECOND);
        rig.host.drain();
        assertEquals(2, rig.host.bundleReads);
        JsonObject replacement = rig.host.sent("upload_offer").getLast();
        assertNotEquals(offer.get("generation"), replacement.get("generation"));
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), replacement), NOW + 11 * SECOND);
        rig.receive(remove, NOW + 11 * SECOND);
        assertTrue(rig.client.committed());
    }

    @Test
    void reconnectRestoresOnlySavedMatchingLocalBytesWithoutStartingSharingOrDownloading() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        String id = rig.host.local.modelId();
        rig.savedModels.put(id, savedEntry(id, rig.bundle, "bbmodel"));
        rig.catalog(true, NOW);
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        assertEquals(1, rig.host.bundleReads);
        rig.host.drain();
        assertEquals(Set.of(id), rig.client.uploadedModelIds());
        assertFalse(rig.client.uploadState(id).published());
        assertTrue(rig.host.sent("upload_offer").isEmpty());
        assertEquals(0, rig.host.cacheReads);
        assertTrue(rig.host.visible.isEmpty());
        rig.client.reset();
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        rig.acknowledgeCatalog();
        rig.catalog(true, NOW);
        rig.host.drain();
        assertEquals(Set.of(id), rig.client.uploadedModelIds());
        assertEquals(2, rig.host.bundleReads);
        assertTrue(rig.host.sent("upload_offer").isEmpty());
    }

    @Test
    void permissionRevocationAndServerCacheEvictionRemoveBadgesWithoutDeletingLocalAppearance() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        String id = rig.host.local.modelId();
        rig.savedModels.put(id, savedEntry(id, rig.bundle, "bbmodel"));
        rig.catalog(true, NOW);
        rig.host.drain();
        assertTrue(rig.client.uploadState(id).uploaded());
        JsonObject revoked = heartbeat();
        revoked.addProperty("allowedUpload", false);
        rig.receive(revoked, NOW);
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        assertEquals(id, rig.host.local.modelId());
        JsonObject restored = heartbeat();
        restored.addProperty("allowedUpload", true);
        rig.receive(restored, NOW);
        rig.savedModels.clear();
        rig.catalog(true, NOW);
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        rig.savedModels.put(id, savedEntry(id, rig.bundle, "bbmodel"));
        rig.catalog(false, NOW);
        assertFalse(rig.client.uploadState(id).uploaded());
        assertTrue(rig.client.uploadedDirectoryStatus().contains("权限或资源缓存不可用"));
    }

    @Test
    void localIdentityMismatchOrFailedSourceReadCannotRestoreServerSavedBadge() throws Exception
    {
        for (String mismatch : List.of("hash", "bytes", "kind"))
        {
            Rig rig = new Rig();
            rig.acknowledgeCatalog();
            String id = rig.host.local.modelId();
            JsonObject entry = savedEntry(id, rig.bundle, "bbmodel");
            switch (mismatch)
            {
            case "hash" -> entry.addProperty("hash", "a".repeat(64));
            case "bytes" -> entry.addProperty("bytes", rig.bundle.length + 1);
            case "kind" -> entry.addProperty("kind", "ysm");
            }
            rig.savedModels.put(id, entry);
            rig.catalog(true, NOW);
            rig.host.drain();
            assertTrue(rig.client.uploadedModelIds().isEmpty(), mismatch);
        }
        Rig absent = new Rig();
        absent.acknowledgeCatalog();
        absent.host.publication = CompletableFuture.failedFuture(new IOException("source missing"));
        absent.savedModels.put(absent.host.local.modelId(),
                savedEntry(absent.host.local.modelId(), absent.bundle, "bbmodel"));
        absent.catalog(true, NOW);
        absent.host.drain();
        assertTrue(absent.client.uploadedModelIds().isEmpty());
        assertTrue(absent.host.sent("upload_offer").isEmpty());
    }

    @Test
    void savedDirectorySourceChecksStaySingleFlightAndLateCallbacksCannotCrossServers() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        CompletableFuture<byte[]> delayed = new CompletableFuture<>();
        rig.host.publication = delayed;
        for (String id : List.of("local:first.bbmodel", "local:second.bbmodel", "local:third.bbmodel"))
        {
            rig.savedModels.put(id, savedEntry(id, rig.bundle, "bbmodel"));
        }

        rig.catalog(true, NOW);
        assertEquals(1, rig.host.bundleReads);
        assertTrue(rig.client.uploadedDirectoryStatus().contains("正在核对"));
        rig.client.reset();
        rig.acknowledgeCatalog();
        delayed.complete(rig.bundle);
        rig.host.drain();
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        assertEquals(1, rig.host.bundleReads);
        assertTrue(rig.host.sent("upload_offer").isEmpty());
    }

    @Test
    void extendedHelloFallsBackOnceForLegacyServersAndNeverSendsUnnegotiatedSourceIds() throws Exception
    {
        Rig rig = new Rig();
        rig.client.tick(NOW);
        JsonArray caps = rig.host.sent("hello").getLast().getAsJsonArray("capabilities");
        assertEquals(4, caps.size());
        JsonObject denied = PrivateModelSyncClient.envelope("error");
        denied.addProperty("code", "invalid_private_payload");
        rig.receive(denied, NOW);
        rig.client.tick(NOW + 1);
        assertEquals(3, rig.host.sent("hello").getLast().getAsJsonArray("capabilities").size());
        assertFalse(rig.host.sent("hello").getLast().has("viewEnabled"));
        rig.receive(denied, NOW + 1);
        rig.client.tick(NOW + 2);
        assertEquals(2, rig.host.sent("hello").getLast().getAsJsonArray("capabilities").size());
        rig.receive(denied, NOW + 2);
        rig.client.tick(NOW + 3);
        assertEquals(1, rig.host.sent("hello").getLast().getAsJsonArray("capabilities").size());
        rig.receive(denied, NOW + 3);
        rig.client.tick(NOW + 4);
        assertEquals(4, rig.host.sent("hello").size());
        rig.acknowledge(true, false);
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject offer = rig.host.sent("upload_offer").getLast();
        assertFalse(offer.has("modelId"));
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), offer), NOW);
        assertEquals(PrivateModelSyncClient.UploadPhase.PUBLISHED,
                rig.client.uploadState(rig.host.local.modelId()).phase());
        assertTrue(rig.client.uploadedModelIds().isEmpty());
    }

    @Test
    void savedFilesWithoutLocalSourcesRemainBrowsableAndDeleteOnlyAfterAnExactServerAck() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        String id = "ysm:已移走的模型.ysm";
        rig.host.publication = CompletableFuture.failedFuture(new IOException("not on this client"));
        rig.savedModels.put(id, savedEntry(id, rig.bundle, "ysm"));
        rig.catalog(true, NOW);
        rig.host.drain();
        assertEquals(1, rig.client.uploadedModels().size());
        assertTrue(rig.client.uploadedModelIds().isEmpty());
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        rig.client.tick(NOW);
        JsonObject request = rig.host.sent("upload_delete").getLast();
        assertEquals(rig.savedModels.get(id).get("hash"), request.get("hash"));
        assertEquals(1, rig.client.uploadedModels().size());
        JsonObject ack = deleteReply(request);
        JsonObject stale = ack.deepCopy();
        stale.addProperty("requestId", UUID.randomUUID().toString());
        rig.receive(stale, NOW);
        assertTrue(rig.client.deletingUploadedModel(id));
        stale = ack.deepCopy();
        stale.addProperty("hash", "b".repeat(64));
        rig.receive(stale, NOW);
        assertEquals(1, rig.client.uploadedModels().size());
        rig.receive(ack, NOW);
        assertFalse(rig.client.deletingUploadedModel(id));
        assertTrue(rig.client.uploadedModels().isEmpty());
        assertTrue(rig.host.sent("upload_offer").isEmpty());
        assertEquals(0, rig.host.deleted.size());
        rig.client.reset();
        rig.acknowledgeCatalog();
        rig.catalog(true, NOW);
        rig.host.drain();
        rig.receive(ack, NOW);
        assertEquals(1, rig.client.uploadedModels().size());
    }

    @Test
    void currentPublicationDeletionCannotRestartSharingBetweenRemoveAndTheSuccessfulAck() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        String id = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject offer = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), offer), NOW);
        rig.confirmSaved(id, offer, NOW);
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        rig.client.tick(NOW);
        JsonObject request = rig.host.sent("upload_delete").getLast();
        assertTrue(rig.client.committed());
        rig.receive(publicationNotice("private_remove", rig.host.local.owner(), offer), NOW);
        assertFalse(rig.client.committed());
        rig.receive(heartbeat(), NOW + 11 * SECOND);
        rig.client.tick(NOW + 11 * SECOND);
        assertEquals(1, rig.host.sent("upload_offer").size());
        rig.receive(deleteReply(request), NOW + 11 * SECOND);
        assertEquals(List.of(id), rig.host.deleted);
        assertFalse(rig.host.explicitLocal);
        rig.receive(heartbeat(), NOW + 15 * SECOND);
        rig.client.tick(NOW + 15 * SECOND);
        assertEquals(1, rig.host.sent("upload_offer").size());
        assertEquals(id, rig.host.local.modelId());
    }

    @Test
    void failedDeletionKeepsSavedMetadataAndRetryUsesANewRequestIdentity() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        String id = rig.host.local.modelId();
        rig.savedModels.put(id, savedEntry(id, rig.bundle, "bbmodel"));
        rig.catalog(true, NOW);
        rig.host.drain();
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        rig.client.tick(NOW);
        JsonObject original = rig.host.sent("upload_delete").getLast();
        JsonObject failure = PrivateModelSyncClient.envelope("upload_delete_failed");
        failure.add("requestId", original.get("requestId"));
        failure.addProperty("code", "private_delete_busy");
        rig.receive(failure, NOW);
        assertFalse(rig.client.deletingUploadedModel(id));
        assertTrue(rig.client.uploadState(id).uploaded());
        assertTrue(rig.client.uploadDeleteStatus(id).contains("稍后"));
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        rig.client.tick(NOW + 1);
        JsonObject retry = rig.host.sent("upload_delete").getLast();
        assertNotEquals(original.get("requestId"), retry.get("requestId"));
        assertEquals(original.get("catalogToken"), retry.get("catalogToken"));
        assertEquals(original.get("requestSequence").getAsLong() + 1, retry.get("requestSequence").getAsLong());
        rig.receive(deleteReply(original), NOW + 1);
        assertTrue(rig.client.deletingUploadedModel(id));
        assertTrue(rig.client.uploadState(id).uploaded());
    }

    @Test
    void deletingAnOldSavedModelDoesNotStopADifferentCurrentPublication() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        String old = "local:old-model.bbmodel";
        rig.savedModels.put(old, savedEntry(old, fixtureBundle(0xff00ffff), "bbmodel"));
        rig.catalog(true, NOW);
        rig.host.drain();
        rig.host.explicitLocal = true;
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject active = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), active), NOW);
        rig.confirmSaved(rig.host.local.modelId(), active, NOW);
        assertTrue(rig.client.requestDeleteUploadedModel(old));
        rig.client.tick(NOW);
        rig.receive(deleteReply(rig.host.sent("upload_delete").getLast()), NOW);
        assertTrue(rig.client.committed());
        assertTrue(rig.host.explicitLocal);
        assertTrue(rig.host.deleted.isEmpty());
        assertEquals(1, rig.client.uploadedModels().size());
    }

    @Test
    void deletingTheLatestSavedVersionAlsoStopsAnOlderPublicationOfThatSameSource() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        String id = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject offer = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), offer), NOW);
        rig.savedModels.put(id, savedEntry(id, fixtureBundle(0xff315599), "bbmodel"));
        rig.catalog(true, NOW);
        rig.host.drain();
        assertFalse(rig.client.uploadState(id).uploaded());
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        rig.client.tick(NOW);
        JsonObject request = rig.host.sent("upload_delete").getLast();
        assertNotEquals(offer.get("hash"), request.get("hash"));
        rig.receive(deleteReply(request), NOW);
        assertFalse(rig.client.committed());
        assertFalse(rig.host.explicitLocal);
        assertEquals(List.of(id), rig.host.deleted);
        assertTrue(rig.client.uploadedModels().isEmpty());
    }

    @Test
    void revokingUploadBeforeADeleteWasSentReleasesTheButtonAndNeverPretendsTheFileWasDeleted() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        String id = rig.host.local.modelId();
        rig.savedModels.put(id, savedEntry(id, rig.bundle, "bbmodel"));
        rig.catalog(true, NOW);
        rig.host.drain();
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        JsonObject revoked = heartbeat();
        revoked.addProperty("allowedUpload", false);
        rig.receive(revoked, NOW);
        assertFalse(rig.client.deletingUploadedModel(id));
        assertTrue(rig.client.uploadDeleteStatus(id).contains("未发送"));
        assertTrue(rig.host.sent("upload_delete").isEmpty());
        assertTrue(rig.host.deleted.isEmpty());
        JsonObject restored = heartbeat();
        restored.addProperty("allowedUpload", true);
        rig.receive(restored, NOW + 1);
        assertTrue(rig.client.uploadState(id).uploaded());
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        rig.client.tick(NOW + 1);
        assertEquals(1, rig.host.sent("upload_delete").size());
    }

    @Test
    void aConfirmationOfAnOlderSavedVersionCannotDeleteASameIdReplacement() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        String id = rig.host.local.modelId();
        rig.savedModels.put(id, savedEntry(id, rig.bundle, "bbmodel"));
        rig.catalog(true, NOW);
        rig.host.drain();
        PrivateUploadCatalogSnapshot.Model displayed = rig.client.uploadedModels().getFirst();
        rig.savedModels.put(id, savedEntry(id, fixtureBundle(0xff001155), "bbmodel"));
        rig.catalog(true, NOW + 1);
        rig.host.drain();
        assertFalse(rig.client.requestDeleteUploadedModel(displayed));
        assertFalse(rig.client.deletingUploadedModel(id));
        assertTrue(rig.client.uploadDeleteStatus(id).contains("重新确认"));
        assertTrue(rig.host.sent("upload_delete").isEmpty());
        assertEquals(1, rig.client.uploadedModels().size());
        assertTrue(rig.client.requestDeleteUploadedModel(rig.client.uploadedModels().getFirst()));
        rig.client.tick(NOW + 1);
        assertEquals(1, rig.host.sent("upload_delete").size());
    }

    @Test
    void aSentDeleteExpiresWithoutClaimingSuccessAndItsLateAckStillStopsTheOriginalPublication() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        String id = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject offer = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), offer), NOW);
        rig.confirmSaved(id, offer, NOW);
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        rig.client.tick(NOW);
        JsonObject request = rig.host.sent("upload_delete").getLast();
        long expired = NOW + 90 * SECOND;
        rig.receive(heartbeat(), expired);
        rig.client.tick(expired);
        assertFalse(rig.client.deletingUploadedModel(id));
        assertTrue(rig.client.uploadDeleteStatus(id).contains("未确认删除"));
        assertTrue(rig.client.uploadState(id).uploaded());
        assertTrue(rig.client.committed());
        assertTrue(rig.host.deleted.isEmpty());
        rig.receive(deleteReply(request), expired + 1);
        assertFalse(rig.client.committed());
        assertFalse(rig.host.explicitLocal);
        assertEquals(List.of(id), rig.host.deleted);
        assertTrue(rig.client.uploadedModels().isEmpty());
    }

    @Test
    void aLateDeleteAckCannotStopANewerPublicationOrEraseItsNewerDirectoryReceiptEvenWithTheSameHash() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        String id = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject original = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), original), NOW);
        rig.confirmSaved(id, original, NOW);
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        rig.client.tick(NOW);
        JsonObject request = rig.host.sent("upload_delete").getLast();
        long expired = NOW + 90 * SECOND;
        rig.receive(heartbeat(), expired);
        rig.client.tick(expired);
        rig.client.stopPublishing();
        rig.client.tick(expired + 1);
        rig.host.drain();
        JsonObject replacement = rig.host.sent("upload_offer").getLast();
        assertNotEquals(original.get("generation"), replacement.get("generation"));
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), replacement), expired + 1);
        rig.confirmSaved(id, replacement, expired + 1);
        rig.receive(deleteReply(request), expired + 2);
        assertTrue(rig.client.committed());
        assertTrue(rig.host.explicitLocal);
        assertTrue(rig.host.deleted.isEmpty());
        assertTrue(rig.client.uploadState(id).uploaded());
        assertEquals(1, rig.client.uploadedModels().size());
    }

    @Test
    void aLateDeleteAckInTheExplicitRestartBeforeBeginGapCannotCancelTheNewSharingIntent() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        rig.host.explicitLocal = true;
        String id = rig.host.local.modelId();
        rig.client.tick(NOW);
        rig.host.drain();
        JsonObject original = rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), original), NOW);
        rig.confirmSaved(id, original, NOW);
        assertTrue(rig.client.requestDeleteUploadedModel(id));
        rig.client.tick(NOW);
        JsonObject request = rig.host.sent("upload_delete").getLast();
        long expired = NOW + 90 * SECOND;
        rig.receive(heartbeat(), expired);
        rig.client.tick(expired);
        assertFalse(rig.client.deletingUploadedModel(id));
        assertTrue(rig.client.committed());
        assertTrue(rig.host.deleted.isEmpty());

        rig.client.restartPublishing();
        assertNull(rig.client.publicationIdentity());
        assertFalse(rig.client.committed());
        assertEquals(1, rig.host.sent("upload_offer").size(), "The new user intent exists before beginPublish runs");
        rig.receive(deleteReply(request), expired + 1);
        assertTrue(rig.host.explicitLocal,
                "The old deletion receipt must not disable the newly requested sharing option");
        assertTrue(rig.host.deleted.isEmpty());
        assertTrue(rig.client.uploadedModels().isEmpty(),
                "The actual old archive deletion still updates its directory receipt");
        rig.client.tick(expired + 2);
        rig.host.drain();
        JsonObject current = rig.host.sent("upload_offer").getLast();
        assertEquals(2, rig.host.sent("upload_offer").size());
        assertNotEquals(original.get("generation"), current.get("generation"));
        assertEquals(original.get("hash"), current.get("hash"));
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), original), expired + 2);
        assertFalse(rig.client.committed());
        rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), current), expired + 2);
        assertTrue(rig.client.committed());
        assertTrue(rig.host.explicitLocal);
        assertTrue(rig.host.deleted.isEmpty());
    }

    @Test
    void lateDeleteHistoryIsBoundedAndIsClearedWhenTheConnectionChanges() throws Exception
    {
        Rig rig = new Rig();
        rig.acknowledgeCatalog();
        String id = rig.host.local.modelId();
        rig.savedModels.put(id, savedEntry(id, rig.bundle, "bbmodel"));
        rig.catalog(true, NOW);
        rig.host.drain();
        long now = NOW;
        List<JsonObject> requests = new ArrayList<>();
        for (int index = 0; index < 17; index++)
        {
            assertTrue(rig.client.requestDeleteUploadedModel(id));
            rig.client.tick(now);
            requests.add(rig.host.sent("upload_delete").getLast());
            now += 90 * SECOND;
            rig.receive(heartbeat(), now);
            rig.client.tick(now);
            assertFalse(rig.client.deletingUploadedModel(id));
            assertTrue(rig.client.uploadState(id).uploaded());
        }
        rig.receive(deleteReply(requests.getFirst()), now);
        assertEquals(1, rig.client.uploadedModels().size());
        rig.receive(deleteReply(requests.getLast()), now);
        assertTrue(rig.client.uploadedModels().isEmpty());
        rig.client.reset();
        rig.acknowledgeCatalog();
        rig.catalog(true, now);
        rig.host.drain();
        rig.receive(deleteReply(requests.get(15)), now);
        assertEquals(1, rig.client.uploadedModels().size());
    }

    private static JsonObject deleteReply(JsonObject request)
    {
        JsonObject ack = PrivateModelSyncClient.envelope("upload_deleted");
        for (String key : List.of("requestId", "modelId", "hash"))
        {
            ack.add(key, request.get(key));
        }
        return ack;
    }

    private static JsonObject savedEntry(String id, byte[] bundle, String kind)
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("modelId", id);
        entry.addProperty("hash", AssetTransfer.hash(bundle));
        entry.addProperty("kind", kind);
        entry.addProperty("bytes", bundle.length);
        return entry;
    }

    private static JsonObject publicationNotice(String type, UUID owner, JsonObject offer)
    {
        JsonObject packet = PrivateModelSyncClient.envelope(type);
        packet.addProperty("owner", owner.toString());
        packet.add("generation", offer.get("generation"));
        packet.add("hash", offer.get("hash"));
        return packet;
    }

    @Test
    void actualServerUploadRejectionsCancelIdentityAndNeverContinueSendingChunks() throws Exception
    {
        for (String code : List.of("private_upload_denied", "private_upload_busy", "private_upload_integrity",
                "private_upload_cooldown", "private_upload_expired", "private_storage_busy",
                "private_generation_reused", "private_bundle_invalid", "server_model_priority",
                "private_appearance_size"))
        {
            Rig rig = new Rig();
            rig.acknowledge(true, false);
            rig.host.explicitLocal = true;
            rig.client.tick(NOW);
            rig.host.drain();
            JsonObject offer = rig.host.sent("upload_offer").getLast();
            JsonObject accepted = publicationNotice("upload_accept", rig.host.local.owner(), offer);
            accepted.addProperty("uploadId", UUID.randomUUID().toString());
            accepted.addProperty("chunkBytes", 8192);
            rig.receive(accepted, NOW);
            JsonObject rejected = PrivateModelSyncClient.envelope("error");
            rejected.addProperty("code", code);
            rig.receive(rejected, NOW);
            rig.receive(publicationNotice("upload_committed", rig.host.local.owner(), offer), NOW);
            assertFalse(rig.client.committed(), code);
            rig.client.tick(NOW + 1);
            assertTrue(rig.host.sent("upload_chunk").isEmpty(), code);
            rig.receive(heartbeat(), NOW + 9 * SECOND);
            rig.client.tick(NOW + 9 * SECOND);
            assertEquals(1, rig.host.bundleReads, code);
            rig.receive(heartbeat(), NOW + 11 * SECOND);
            rig.client.tick(NOW + 11 * SECOND);
            rig.host.drain();
            boolean retry = code.contains("busy") || code.contains("cooldown") || code.contains("expired")
                    || code.contains("unavailable");
            assertEquals(retry ? 2 : 1, rig.host.bundleReads, code);
            if (retry)
            {
                assertNotEquals(offer.get("generation"), rig.host.sent("upload_offer").getLast().get("generation"),
                        code);
            }

        }
    }

    @Test
    void permittingAuthorRoamingKeepsVariableNamesFiniteValuesAndCountBudgetsEnforced() throws Exception
    {
        for (String name : List.of("query.health", "variable.roaming.bad-name", "variable." + "x".repeat(120),
                "variable.MixedCase", "variable.roaming." + "x".repeat(33)))
        {
            Rig rig = new Rig();
            rig.acknowledge(false, true);
            Offer offer = rig.offer();
            JsonObject packet = offer.packet();
            packet.getAsJsonObject("appearance").getAsJsonObject("variables").addProperty(name, 1);
            rig.receive(packet, NOW);
            rig.host.drain();
            assertNull(rig.client.remote(offer.owner));
            assertEquals(0, rig.host.decodes);
        }
        for (double value : new double[]
        { 1_000_001, -1_000_001 })
        {
            Rig rig = new Rig();
            rig.acknowledge(false, true);
            Offer offer = rig.offer();
            JsonObject packet = offer.packet();
            packet.getAsJsonObject("appearance").getAsJsonObject("variables")
                    .addProperty("variable.roaming.red_bow_headdress", value);
            rig.receive(packet, NOW);
            rig.host.drain();
            assertNull(rig.client.remote(offer.owner));
            assertEquals(0, rig.host.decodes);
        }
        for (String overflow : List.of("1e309", "-1e309"))
        {
            Rig rig = new Rig();
            rig.acknowledge(false, true);
            Offer offer = rig.offer();
            JsonObject packet = offer.packet();
            packet.getAsJsonObject("appearance").getAsJsonObject("variables")
                    .addProperty("variable.roaming.red_bow_headdress", 0);
            // Valid JSON syntax that overflows a double must still fail the finite-value guard.
            String json = packet.toString().replace("\"variable.roaming.red_bow_headdress\":0",
                    "\"variable.roaming.red_bow_headdress\":" + overflow);
            rig.client.receive(json.getBytes(StandardCharsets.UTF_8), NOW);
            rig.host.drain();
            assertNull(rig.client.remote(offer.owner));
            assertEquals(0, rig.host.decodes);
        }
        for (String prefix : List.of("variable.roaming.", "variable."))
        {
            Rig rig = new Rig();
            rig.acknowledge(false, true);
            Offer offer = rig.offer();
            JsonObject packet = offer.packet();
            JsonObject variables = packet.getAsJsonObject("appearance").getAsJsonObject("variables");
            int count = prefix.equals("variable.") ? 129 : 65;
            for (int i = 0; i < count; i++)
            {
                variables.addProperty(prefix + "option_" + i, 0);
            }

            rig.receive(packet, NOW);
            rig.host.drain();
            assertNull(rig.client.remote(offer.owner));
            assertEquals(0, rig.host.decodes);
        }
    }

    private record Event(UUID owner, UUID generation, List<Double> args)
    {
    }

    private record Offer(UUID owner, UUID generation, UUID offerId, String hash, int bytes)
    {
        JsonObject identity(String type)
        {
            JsonObject packet = PrivateModelSyncClient.envelope(type);
            packet.addProperty("owner", this.owner.toString());
            packet.addProperty("generation", this.generation.toString());
            packet.addProperty("hash", this.hash);
            return packet;
        }

        JsonObject packet()
        {
            JsonObject packet = identity("private_offer");
            packet.addProperty("offerId", this.offerId.toString());
            packet.addProperty("kind", "bbmodel");
            packet.addProperty("bytes", this.bytes);
            packet.addProperty("sequence", 0);
            packet.add("appearance", appearance(1));
            return packet;
        }

        JsonObject asset(String type)
        {
            JsonObject packet = PrivateModelSyncClient.envelope(type);
            packet.addProperty("offerId", this.offerId.toString());
            packet.addProperty("hash", this.hash);
            return packet;
        }

        JsonObject event(long sequence, List<Double> args)
        {
            JsonObject packet = identity("private_event");
            packet.addProperty("sequence", sequence);
            JsonArray values = new JsonArray();
            args.forEach(values::add);
            packet.add("args", values);
            return packet;
        }

        JsonObject state(long sequence, double scale)
        {
            JsonObject packet = identity("private_state");
            packet.addProperty("sequence", sequence);
            packet.add("appearance", appearance(scale));
            return packet;
        }
    }

    private static final class Rig
    {
        final byte[] bundle;
        final FakeHost host;
        final PrivateModelSyncClient client;
        long catalogRevision;
        final Map<String, JsonObject> savedModels = new LinkedHashMap<>();

        Rig() throws Exception
        {
            this.bundle = fixtureBundle();
            this.host = new FakeHost(this.bundle);
            this.client = new PrivateModelSyncClient(this.host);
        }

        Offer offer()
        {
            return new Offer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AssetTransfer.hash(this.bundle),
                    this.bundle.length);
        }

        void receive(JsonObject packet, long now)
        {
            this.client.receive(packet.toString().getBytes(StandardCharsets.UTF_8), now);
        }

        void acknowledge(boolean upload, boolean view)
        {
            acknowledge(upload, view, false);
        }

        void acknowledgeCatalog()
        {
            acknowledge(true, true, true);
        }

        void acknowledge(boolean upload, boolean view, boolean catalogue)
        {
            acknowledge(upload, view, catalogue, false);
        }

        void acknowledge(boolean upload, boolean view, boolean catalogue, boolean credit)
        {
            acknowledge(upload, view, catalogue, credit, false);
        }

        void acknowledgeViewState(boolean upload, boolean view)
        {
            acknowledge(upload, view, false, false, true);
        }

        void acknowledge(boolean upload, boolean view, boolean catalogue, boolean credit, boolean viewState)
        {
            JsonObject packet = PrivateModelSyncClient.envelope("hello_ack");
            JsonArray caps = new JsonArray();
            caps.add("private_models_v1");
            packet.add("capabilities", caps);
            if (catalogue)
            {
                caps.add(PrivateUploadCatalogSnapshot.CAPABILITY);
                packet.addProperty("uploadCatalogToken", UUID.randomUUID().toString());
            }
            if (credit)
            {
                caps.add(PrivateModelSyncClient.UPLOAD_CREDIT);
            }

            if (viewState)
            {
                caps.add(PrivateModelSyncClient.VIEW_STATE);
                packet.addProperty("viewEnabled", this.host.viewEnabled);
            }
            packet.addProperty("allowedUpload", upload);
            packet.addProperty("allowedView", view);
            packet.addProperty("maxPayload", 16000);
            packet.addProperty("maxBundleBytes", AssetTransfer.MAX_RAW);
            packet.addProperty("leaseTicks", 20);
            receive(packet, NOW);
        }

        void confirmSaved(String id, JsonObject offer, long now)
        {
            this.savedModels.values().removeIf(entry -> entry.get("hash").equals(offer.get("hash")));
            JsonObject entry = new JsonObject();
            entry.addProperty("modelId", id);
            entry.add("hash", offer.get("hash"));
            entry.add("kind", offer.get("kind"));
            entry.add("bytes", offer.get("bytes"));
            this.savedModels.put(id, entry);
            catalog(true, now);
            this.host.drain();
        }

        void catalog(boolean available, long now)
        {
            JsonObject packet = PrivateModelSyncClient.envelope("upload_catalog");
            packet.addProperty("revision", ++this.catalogRevision);
            packet.addProperty("index", 0);
            packet.addProperty("count", 1);
            packet.addProperty("available", available);
            JsonArray models = new JsonArray();
            if (available)
            {
                this.savedModels.values().forEach(models::add);
            }
            packet.add("models", models);
            receive(packet, now);
        }

        void begin(Offer offer)
        {
            JsonObject packet = offer.asset("asset_begin");
            packet.addProperty("bytes", this.bundle.length);
            packet.addProperty("chunks", 1);
            receive(packet, NOW);
        }

        void chunk(Offer offer, byte[] bytes)
        {
            JsonObject packet = offer.asset("asset_chunk");
            packet.addProperty("index", 0);
            packet.addProperty("data", Base64.getEncoder().encodeToString(bytes));
            receive(packet, NOW);
        }

        void activate(Offer offer)
        {
            receive(offer.packet(), NOW);
            this.host.drain();
            receive(offer.identity("private_ack"), NOW);
        }
    }

    private static final class FakeHost implements PrivateModelSyncClient.Host
    {
        boolean channel = true, explicitLocal, viewEnabled = true;
        int cacheReads, bundleReads, decodes, prepared;
        CompletableFuture<byte[]> cached = CompletableFuture.completedFuture(null), publication;
        final Deque<CompletableFuture<LocalModelLibrary.Loaded>> decodeResults = new ArrayDeque<>();
        final Deque<Runnable> dispatch = new ArrayDeque<>();
        final List<JsonObject> packets = new ArrayList<>();
        final List<String> controlAttempts = new ArrayList<>();
        final Set<String> blockedTypes = new HashSet<>();

        private record TimedControl(String type, long time)
        {
        }

        final List<TimedControl> controlTimes = new ArrayList<>();
        long now = NOW;
        final Map<UUID, UUID> visible = new HashMap<>();
        final List<Event> events = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        PrivateModelSyncClient.Local local;

        FakeHost(byte[] bundle)
        {
            this.publication = CompletableFuture.completedFuture(bundle);
            this.local = new PrivateModelSyncClient.Local(UUID.randomUUID(), "local:test.bbmodel", appearance(1),
                    extra());
        }

        public boolean channelAvailable()
        {
            return this.channel;
        }

        public boolean viewEnabled()
        {
            return this.viewEnabled;
        }

        public boolean send(JsonObject packet)
        {
            String type = packet.get("type").getAsString();
            this.controlAttempts.add(type);
            this.controlTimes.add(new TimedControl(type, this.now));
            if (this.blockedTypes.contains(type))
            {
                return false;
            }
            this.packets.add(packet.deepCopy());
            return true;
        }

        public PrivateModelSyncClient.Local local()
        {
            return this.explicitLocal ? this.local : null;
        }

        public CompletableFuture<byte[]> bundle(String modelId)
        {
            this.bundleReads++;
            return this.publication;
        }

        public CompletableFuture<byte[]> cached(String hash)
        {
            this.cacheReads++;
            return this.cached;
        }

        public CompletableFuture<LocalModelLibrary.Loaded> decode(byte[] bundle, String texture)
        {
            this.decodes++;
            if (!this.decodeResults.isEmpty())
            {
                return this.decodeResults.removeFirst();
            }

            try
            {
                return CompletableFuture.completedFuture(NativeModelBundle.decode(bundle, texture));
            }
            catch (Exception invalid)
            {
                return CompletableFuture.failedFuture(invalid);
            }
        }

        public void cache(String hash, byte[] bundle)
        {
            assertEquals(hash, AssetTransfer.hash(bundle));
        }

        public void dispatch(Runnable task)
        {
            this.dispatch.addLast(task);
        }

        public boolean prepare(PrivateModelSyncClient.Remote remote, LocalModelLibrary.Loaded loaded)
        {
            assertNotNull(loaded.model());
            this.prepared++;
            return true;
        }

        public void remove(UUID owner, UUID generation)
        {
            this.visible.remove(owner, generation);
        }

        public void state(PrivateModelSyncClient.Remote remote)
        {
            if (remote.ready && remote.active)
            {
                this.visible.put(remote.owner, remote.generation);
            }
            else
            {
                this.visible.remove(remote.owner, remote.generation);
            }
        }

        public void event(UUID owner, UUID generation, List<Double> args)
        {
            this.events.add(new Event(owner, generation, args));
        }

        public void savedModelDeleted(String id, String hash)
        {
            this.deleted.add(id);
            if (this.local.modelId().equals(id))
            {
                this.explicitLocal = false;
            }
        }

        void drain()
        {
            while (!this.dispatch.isEmpty())
            {
                this.dispatch.removeFirst().run();
            }
        }

        List<JsonObject> sent(String type)
        {
            return this.packets.stream().filter(packet -> packet.get("type").getAsString().equals(type)).toList();
        }

        long attempts(String type)
        {
            return this.controlAttempts.stream().filter(type::equals).count();
        }
    }

    private static JsonObject heartbeat()
    {
        JsonObject packet = PrivateModelSyncClient.envelope("heartbeat");
        packet.add("bindings", new JsonArray());
        return packet;
    }

    private static JsonObject flight(boolean flying)
    {
        JsonObject state = new JsonObject();
        state.addProperty("flying", flying);
        return state;
    }

    private static JsonObject appearance(double scale)
    {
        JsonObject value = new JsonObject();
        value.addProperty("scale", scale);
        value.addProperty("offsetX", 0);
        value.addProperty("offsetY", 0);
        value.addProperty("offsetZ", 0);
        value.addProperty("textureId", "");
        value.add("variables", new JsonObject());
        value.add("radioSelections", new JsonObject());
        return value;
    }

    private static JsonObject extra()
    {
        JsonObject value = new JsonObject();
        value.addProperty("id", "");
        value.addProperty("loop", "ONCE");
        value.addProperty("locked", false);
        value.addProperty("sequence", 0);
        return value;
    }

    private static byte[] fixtureBundle() throws Exception
    {
        return fixtureBundle(0xffffffff);
    }

    private static byte[] fixtureBundle(int color) throws Exception
    {
        JsonObject raw = JsonParser
                .parseString(
                        """
                                {"meta":{"format_version":"5.0"},"textures":[],"animations":[],
                                 "elements":[{"uuid":"cube","from":[0,0,0],"to":[16,16,16],"faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
                                 "outliner":[{"uuid":"root","name":"root","origin":[0,0,0],"children":["cube"]}]}
                                """)
                .getAsJsonObject();
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        // The mature native converter preserves the author texture name as a resource path.
        // This protocol fixture must remain a valid, renderable Blockbench asset too.
        JsonObject texture = new JsonObject();
        texture.addProperty("uuid", "fixture-texture");
        texture.addProperty("name", "fixture.png");
        texture.addProperty("width", 1);
        texture.addProperty("height", 1);
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()));
        raw.getAsJsonArray("textures").add(texture);
        return NativeModelBundle.encode("bbmodel", "model.bbmodel",
                Map.of("model.bbmodel", raw.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
