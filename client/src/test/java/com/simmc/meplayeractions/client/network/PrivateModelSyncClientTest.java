package com.simmc.meplayeractions.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.NativeModelBundle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Real bundle decoding with a deterministic host clock; no game, network or sleeps. */
class PrivateModelSyncClientTest {
    private static final long NOW=10_000_000_000L,SECOND=1_000_000_000L;

    @Test void anUnnegotiatedOrUnauthorizedOfferCannotLoadOrRenderAModel() throws Exception {
        Rig rig=new Rig();Offer offer=rig.offer();
        rig.receive(offer.packet(),NOW);rig.receive(offer.identity("private_ack"),NOW);rig.host.drain();
        assertTrue(rig.client.remotes().isEmpty());assertEquals(0,rig.host.cacheReads);assertEquals(0,rig.host.decodes);
        rig.acknowledge(false,false);rig.receive(offer.packet(),NOW);rig.receive(offer.identity("private_ack"),NOW);rig.host.drain();
        assertTrue(rig.client.remotes().isEmpty());assertTrue(rig.host.visible.isEmpty());assertEquals(0,rig.host.prepared);
    }

    @Test void aValidatedCacheStillWaitsForMatchingServerAcknowledgement() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);Offer offer=rig.offer();rig.host.cached=CompletableFuture.completedFuture(rig.bundle);
        rig.receive(offer.packet(),NOW);rig.host.drain();
        assertEquals(1,rig.host.decodes);assertEquals(1,rig.host.prepared);assertTrue(rig.client.remote(offer.owner).ready);
        assertFalse(rig.client.remote(offer.owner).active);assertTrue(rig.host.visible.isEmpty());
        assertEquals(1,rig.host.sent("private_ready").size());
        rig.receive(offer.event(1,List.of(7d)),NOW);assertTrue(rig.host.events.isEmpty());
        JsonObject stale=offer.identity("private_ack");stale.addProperty("generation",UUID.randomUUID().toString());rig.receive(stale,NOW);
        assertTrue(rig.host.visible.isEmpty());
        rig.receive(offer.identity("private_ack"),NOW);assertEquals(offer.generation,rig.host.visible.get(offer.owner));
    }

    @Test void cachedFeedbackAndReadinessRetryOncePerSecondUntilTheExactAcknowledgement() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);rig.host.cached=CompletableFuture.completedFuture(rig.bundle);
        rig.host.blockedTypes.addAll(Set.of("private_status","private_ready"));Offer offer=rig.offer();
        rig.receive(offer.packet(),NOW);rig.host.drain();
        assertEquals(1,rig.host.attempts("private_status"));assertEquals(0,rig.host.attempts("private_ready"));
        rig.receive(offer.packet(),NOW+SECOND/2);rig.client.tick(NOW+SECOND/2);
        assertEquals(1,rig.host.attempts("private_status"));assertFalse(rig.client.remote(offer.owner).active);
        rig.host.blockedTypes.remove("private_status");rig.receive(heartbeat(),NOW+SECOND);rig.client.tick(NOW+SECOND);
        assertEquals(1,rig.host.sent("private_status").size());assertEquals(1,rig.host.attempts("private_ready"));
        rig.client.tick(NOW+SECOND+SECOND/2);assertEquals(1,rig.host.attempts("private_ready"));
        rig.host.blockedTypes.remove("private_ready");rig.receive(heartbeat(),NOW+2*SECOND);rig.client.tick(NOW+2*SECOND);
        assertEquals(1,rig.host.sent("private_ready").size());assertTrue(rig.host.visible.isEmpty());
        JsonObject stale=offer.identity("private_ack");stale.addProperty("generation",UUID.randomUUID().toString());rig.receive(stale,NOW+2*SECOND);
        assertFalse(rig.client.remote(offer.owner).active);
        rig.receive(heartbeat(),NOW+3*SECOND);rig.client.tick(NOW+3*SECOND);assertEquals(2,rig.host.sent("private_ready").size());
        rig.receive(offer.identity("private_ack"),NOW+3*SECOND);rig.client.tick(NOW+3*SECOND+SECOND/2);
        assertEquals(2,rig.host.sent("private_ready").size());assertEquals(offer.generation,rig.host.visible.get(offer.owner));
    }

    @Test void missingFeedbackSurvivesCongestionWithoutRequestingAnotherModelOrDecodingUnsentBytes() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);rig.host.blockedTypes.add("private_status");Offer offer=rig.offer();
        rig.receive(offer.packet(),NOW);rig.host.drain();assertEquals(0,rig.host.decodes);
        rig.host.blockedTypes.remove("private_status");rig.receive(heartbeat(),NOW+SECOND);rig.client.tick(NOW+SECOND);
        assertEquals(offer.offerId.toString(),rig.host.sent("private_status").getLast().get("offerId").getAsString());
        assertEquals("missing",rig.host.sent("private_status").getLast().get("status").getAsString());
        assertEquals(1,rig.host.cacheReads);assertTrue(rig.host.sent("asset_request").isEmpty());
    }

    @Test void successfulTransportWritesRetryFeedbackUntilTheServerConfirmsTheirMeaning() throws Exception {
        Rig missing=new Rig();missing.acknowledge(false,true);Offer download=missing.offer();
        missing.receive(download.packet(),NOW);missing.host.drain();
        missing.receive(heartbeat(),NOW+SECOND);missing.client.tick(NOW+SECOND);
        assertEquals(2,missing.host.sent("private_status").size());
        missing.begin(download);missing.receive(heartbeat(),NOW+2*SECOND);missing.client.tick(NOW+2*SECOND);
        assertEquals(2,missing.host.sent("private_status").size());
        Rig cached=new Rig();cached.acknowledge(false,true);cached.host.cached=CompletableFuture.completedFuture(cached.bundle);Offer ready=cached.offer();
        cached.receive(ready.packet(),NOW);cached.host.drain();
        cached.receive(heartbeat(),NOW+SECOND);cached.client.tick(NOW+SECOND);
        assertEquals(2,cached.host.sent("private_status").size());assertEquals(2,cached.host.sent("private_ready").size());
        cached.receive(ready.identity("private_ack"),NOW+SECOND);cached.client.tick(NOW+SECOND+SECOND/2);
        assertEquals(2,cached.host.sent("private_status").size());assertEquals(2,cached.host.sent("private_ready").size());
    }

    @Test void publicationOffersAndEndsRetryTheirExactIdentityAndDuplicateAcceptNeverRestartsChunks() throws Exception {
        Rig rig=new Rig();rig.acknowledge(true,false);rig.host.explicitLocal=true;
        rig.host.blockedTypes.add("upload_offer");rig.client.tick(NOW);rig.host.drain();assertEquals(1,rig.host.attempts("upload_offer"));
        rig.host.blockedTypes.remove("upload_offer");rig.receive(heartbeat(),NOW+SECOND);rig.client.tick(NOW+SECOND);
        JsonObject offer=rig.host.sent("upload_offer").getLast();
        rig.receive(heartbeat(),NOW+2*SECOND);rig.client.tick(NOW+2*SECOND);
        assertEquals(offer,rig.host.sent("upload_offer").getLast());
        JsonObject accept=publicationNotice("upload_accept",rig.host.local.owner(),offer);accept.addProperty("uploadId",UUID.randomUUID().toString());accept.addProperty("chunkBytes",128);
        rig.receive(accept,NOW+2*SECOND);rig.client.tick(NOW+2*SECOND);assertEquals(0,rig.host.sent("upload_chunk").getLast().get("index").getAsInt());
        rig.receive(accept,NOW+2*SECOND);rig.client.tick(NOW+2*SECOND);assertEquals(1,rig.host.sent("upload_chunk").getLast().get("index").getAsInt());
        for(int i=0;i<rig.bundle.length/128+2;i++)rig.client.tick(NOW+2*SECOND);
        assertEquals(1,rig.host.sent("upload_end").size());JsonObject end=rig.host.sent("upload_end").getLast();
        int chunks=rig.host.sent("upload_chunk").size();rig.client.tick(NOW+2*SECOND+SECOND/2);assertEquals(1,rig.host.sent("upload_end").size());
        rig.receive(heartbeat(),NOW+3*SECOND);rig.client.tick(NOW+3*SECOND);
        assertEquals(2,rig.host.sent("upload_end").size());assertEquals(end,rig.host.sent("upload_end").getLast());assertEquals(chunks,rig.host.sent("upload_chunk").size());
        rig.receive(publicationNotice("upload_committed",rig.host.local.owner(),offer),NOW+3*SECOND);rig.receive(heartbeat(),NOW+4*SECOND);rig.client.tick(NOW+4*SECOND);
        assertTrue(rig.client.committed());assertEquals(2,rig.host.sent("upload_end").size());
    }

    @Test void sixteenOfferedModelsShareABoundedControlBudgetAndLaterOwnersStillBecomeReady() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);rig.host.cached=CompletableFuture.completedFuture(rig.bundle);
        List<Offer> offers=new ArrayList<>();
        for(int i=0;i<16;i++){Offer offer=rig.offer();offers.add(offer);rig.receive(offer.packet(),NOW);rig.host.drain();}
        Set<String> controls=Set.of("private_status","private_ready","upload_offer","upload_end");
        assertEquals(8,rig.host.controlTimes.stream().filter(value->controls.contains(value.type)&&value.time==NOW).count());
        Set<UUID> acknowledged=new HashSet<>();
        for(int frame=1;frame<=80;frame++) {
            long now=NOW+frame*50_000_000L;rig.host.now=now;
            JsonObject heartbeat=heartbeat();JsonArray bindings=heartbeat.getAsJsonArray("bindings");
            for(Offer offer:offers){JsonObject value=offer.identity("heartbeat");value.remove("protocol");value.remove("type");bindings.add(value);}
            rig.receive(heartbeat,now);rig.client.tick(now);
            for(JsonObject packet:rig.host.sent("private_ready")) {
                UUID owner=UUID.fromString(packet.get("owner").getAsString());
                if(acknowledged.add(owner))rig.receive(offers.stream().filter(value->value.owner.equals(owner)).findFirst().orElseThrow().identity("private_ack"),now);
            }
        }
        assertEquals(16,acknowledged.size());assertEquals(16,rig.host.visible.size());
        List<FakeHost.TimedControl> attempts=rig.host.controlTimes.stream().filter(value->controls.contains(value.type)).toList();
        for(FakeHost.TimedControl attempt:attempts)
            assertTrue(attempts.stream().filter(value->value.time>=attempt.time&&value.time<attempt.time+SECOND).count()<=16,"One-second handshake budget exceeded");
    }

    @Test void heartbeatPermissionsRevokeUploadsAndViewingIndependentlyWithoutLosingTheUsersShareIntent() throws Exception {
        Rig rig=new Rig();rig.acknowledge(true,true);rig.host.explicitLocal=true;
        rig.host.cached=CompletableFuture.completedFuture(rig.bundle);Offer offer=rig.offer();rig.activate(offer);
        rig.client.tick(NOW);rig.host.drain();JsonObject upload=rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed",rig.host.local.owner(),upload),NOW);assertTrue(rig.client.committed());
        JsonObject revokeUpload=heartbeat();revokeUpload.addProperty("allowedUpload",false);rig.receive(revokeUpload,NOW);
        assertFalse(rig.client.available());assertFalse(rig.client.committed());assertTrue(rig.client.canView());
        assertNotNull(rig.client.remote(offer.owner));assertEquals(1,rig.host.sent("clear").size());assertTrue(rig.host.explicitLocal);
        JsonObject revokeView=heartbeat();revokeView.addProperty("allowedView",false);rig.receive(revokeView,NOW);
        assertFalse(rig.client.canView());assertTrue(rig.client.remotes().isEmpty());assertTrue(rig.host.visible.isEmpty());
        JsonObject grant=heartbeat();grant.addProperty("allowedUpload",true);grant.addProperty("allowedView",true);rig.receive(grant,NOW+1);
        rig.client.tick(NOW+1);rig.host.drain();assertTrue(rig.client.available());assertTrue(rig.client.canView());
        assertEquals(2,rig.host.sent("upload_offer").size());
        assertNotEquals(upload.get("generation"),rig.host.sent("upload_offer").getLast().get("generation"));
    }

    @Test void legacyHeartbeatsPreserveNegotiatedPermissionsAndViewingDoesNotRequirePublishing() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);rig.receive(heartbeat(),NOW);
        assertFalse(rig.client.available());assertTrue(rig.client.canView());assertFalse(rig.host.explicitLocal);
        rig.host.cached=CompletableFuture.completedFuture(rig.bundle);Offer offer=rig.offer();rig.activate(offer);
        assertEquals(offer.generation,rig.host.visible.get(offer.owner));assertEquals(0,rig.host.bundleReads);
    }

    @Test void aServerCachedPublicationCommitsWithoutAnUploadAcceptOrDuplicateBytes() throws Exception {
        Rig rig=new Rig();rig.acknowledge(true,false);rig.host.explicitLocal=true;
        rig.client.tick(NOW);rig.host.drain();JsonObject upload=rig.host.sent("upload_offer").getLast();
        assertEquals(rig.bundle.length,rig.client.publicationBytes());assertEquals(0,rig.client.uploadedBytes());
        rig.receive(publicationNotice("upload_committed",rig.host.local.owner(),upload),NOW);rig.client.tick(NOW);
        assertTrue(rig.client.committed());assertTrue(rig.host.sent("upload_chunk").isEmpty());assertTrue(rig.host.sent("upload_end").isEmpty());
        assertEquals(0,rig.client.uploadedBytes());assertTrue(rig.client.status().contains("已分享"));assertTrue(rig.client.status().contains("复用服务器缓存"));
    }

    @Test void theLastPublishedAppearanceRetriesEveryTwoSecondsWithoutStartingTheActionAgain() throws Exception {
        Rig rig=new Rig();rig.acknowledge(true,false);rig.host.explicitLocal=true;
        rig.client.tick(NOW);rig.host.drain();JsonObject upload=rig.host.sent("upload_offer").getLast();
        rig.receive(publicationNotice("upload_committed",rig.host.local.owner(),upload),NOW);rig.client.tick(NOW);
        JsonObject initial=rig.host.sent("private_state").getLast();assertEquals(1,rig.host.sent("private_state").size());
        rig.receive(heartbeat(),NOW+SECOND);rig.client.tick(NOW+SECOND);assertEquals(1,rig.host.sent("private_state").size());
        rig.receive(heartbeat(),NOW+2*SECOND);rig.client.tick(NOW+2*SECOND);
        assertEquals(2,rig.host.sent("private_state").size());assertEquals(initial,rig.host.sent("private_state").getLast());
        assertEquals(0,rig.host.sent("private_state").getLast().getAsJsonObject("extra").get("sequence").getAsInt());
    }

    @Test void uploadProgressAndServerRejectionAreReadableWithoutClearingExplicitSharing() throws Exception {
        Rig rig=new Rig();rig.acknowledge(true,false);rig.host.explicitLocal=true;
        rig.client.tick(NOW);rig.host.drain();JsonObject upload=rig.host.sent("upload_offer").getLast();
        JsonObject accept=publicationNotice("upload_accept",rig.host.local.owner(),upload);accept.addProperty("uploadId",UUID.randomUUID().toString());accept.addProperty("chunkBytes",128);rig.receive(accept,NOW);
        rig.client.tick(NOW);assertEquals(128,rig.client.uploadedBytes());assertTrue(rig.client.status().contains("%"));
        JsonObject error=PrivateModelSyncClient.envelope("error");error.addProperty("code","private_upload_denied");rig.receive(error,NOW);
        assertEquals("没有私人模型上传权限",rig.client.status());assertTrue(rig.host.explicitLocal);assertEquals(0,rig.client.publicationBytes());
    }

    @Test void bytesWithTheWrongChecksumAbortTheOfferAndNeverReachTheDecoder() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);Offer offer=rig.offer();rig.receive(offer.packet(),NOW);rig.host.drain();
        rig.begin(offer);byte[] corrupt=rig.bundle.clone();corrupt[corrupt.length-1]^=1;
        rig.chunk(offer,corrupt);rig.receive(offer.asset("asset_end"),NOW);rig.host.drain();
        assertNull(rig.client.remote(offer.owner));assertEquals(0,rig.host.decodes);assertTrue(rig.host.visible.isEmpty());
        assertEquals("rejected",rig.host.sent("private_status").getLast().get("status").getAsString());
        // A late valid end cannot revive the rejected authorization.
        rig.chunk(offer,rig.bundle);rig.receive(offer.asset("asset_end"),NOW);rig.host.drain();assertEquals(0,rig.host.decodes);
    }

    @Test void conflictingDuplicateChunksAbortRatherThanAcceptingTheEarlierChunk() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);Offer offer=rig.offer();rig.receive(offer.packet(),NOW);rig.host.drain();
        rig.begin(offer);rig.chunk(offer,rig.bundle);byte[] conflict=rig.bundle.clone();conflict[0]^=1;rig.chunk(offer,conflict);
        rig.receive(offer.asset("asset_end"),NOW);rig.host.drain();
        assertNull(rig.client.remote(offer.owner));assertEquals(0,rig.host.decodes);assertEquals(0,rig.host.prepared);
    }

    @Test void staleGenerationsAndRepeatedStateOrEventSequencesCannotChangeTheCurrentModel() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);rig.host.cached=CompletableFuture.completedFuture(rig.bundle);
        Offer old=rig.offer();rig.activate(old);Offer current=new Offer(old.owner,UUID.randomUUID(),UUID.randomUUID(),old.hash,old.bytes);
        rig.activate(current);assertEquals(current.generation,rig.host.visible.get(current.owner));
        rig.receive(old.state(100,7),NOW);assertEquals(1,rig.client.remote(current.owner).appearance.get("scale").getAsDouble());
        rig.receive(current.state(8,2),NOW);rig.receive(current.state(7,5),NOW);
        assertEquals(2,rig.client.remote(current.owner).appearance.get("scale").getAsDouble());
        rig.receive(old.event(100,List.of(99d)),NOW);
        rig.receive(current.event(8,List.of(3d)),NOW);rig.receive(current.event(8,List.of(4d)),NOW);rig.receive(current.event(7,List.of(5d)),NOW);
        assertEquals(List.of(new Event(current.owner,current.generation,List.of(3d))),rig.host.events);
        assertEquals(8,rig.client.remote(current.owner).sequence);
    }

    @Test void stoppingALocalPublicationDoesNotCancelAnAuthorizedRemoteDecode() throws Exception {
        Rig rig=new Rig();rig.acknowledge(true,true);rig.host.cached=CompletableFuture.completedFuture(rig.bundle);
        CompletableFuture<LocalModelLibrary.Loaded> remoteDecode=new CompletableFuture<>();rig.host.decodeResults.add(remoteDecode);
        Offer remote=rig.offer();rig.receive(remote.packet(),NOW);rig.host.drain();assertEquals(1,rig.host.decodes);
        CompletableFuture<byte[]> localBuild=new CompletableFuture<>();rig.host.publication=localBuild;rig.host.explicitLocal=true;
        rig.client.tick(NOW);assertEquals(1,rig.host.bundleReads);rig.client.stopPublishing();rig.host.explicitLocal=false;
        localBuild.complete(rig.bundle);remoteDecode.complete(NativeModelBundle.decode(rig.bundle,""));rig.host.drain();
        assertTrue(rig.host.sent("upload_offer").isEmpty());assertEquals(1,rig.host.prepared);assertTrue(rig.client.remote(remote.owner).ready);
        rig.receive(remote.identity("private_ack"),NOW);assertEquals(remote.generation,rig.host.visible.get(remote.owner));
    }

    @Test void remoteLeasesExpireEvenWhenTheServerKeepsTheTransportAliveAndLateDecodesStayCancelled() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);rig.host.cached=CompletableFuture.completedFuture(rig.bundle);Offer active=rig.offer();rig.activate(active);
        rig.receive(heartbeat(),NOW+2*SECOND);rig.client.tick(NOW+2*SECOND);
        assertNull(rig.client.remote(active.owner));assertTrue(rig.host.visible.isEmpty());
        CompletableFuture<LocalModelLibrary.Loaded> delayed=new CompletableFuture<>();rig.host.decodeResults.add(delayed);
        Offer pending=rig.offer();rig.receive(pending.packet(),NOW+2*SECOND);rig.host.drain();int prepared=rig.host.prepared;
        long expired=NOW+63*SECOND;rig.receive(heartbeat(),expired);rig.client.tick(expired);
        assertNull(rig.client.remote(pending.owner));delayed.complete(NativeModelBundle.decode(rig.bundle,""));rig.host.drain();
        assertEquals(prepared,rig.host.prepared);assertTrue(rig.host.visible.isEmpty());
    }

    @Test void aConnectionResetInvalidatesCachedAndDecodedFuturesFromTheOldWorld() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);CompletableFuture<byte[]> cache=new CompletableFuture<>();rig.host.cached=cache;
        rig.receive(rig.offer().packet(),NOW);rig.client.reset();cache.complete(rig.bundle);rig.host.drain();assertEquals(0,rig.host.decodes);
        rig.acknowledge(false,true);rig.host.cached=CompletableFuture.completedFuture(rig.bundle);
        CompletableFuture<LocalModelLibrary.Loaded> decoded=new CompletableFuture<>();rig.host.decodeResults.add(decoded);
        rig.receive(rig.offer().packet(),NOW);rig.host.drain();rig.host.channel=false;rig.client.tick(NOW);
        decoded.complete(NativeModelBundle.decode(rig.bundle,""));rig.host.drain();
        assertTrue(rig.client.remotes().isEmpty());assertEquals(0,rig.host.prepared);assertTrue(rig.host.sent("private_ready").isEmpty());
    }

    @Test void onlyExplicitLocalSharingUploadsAndOnlyMatchingCommitMakesItPublished() throws Exception {
        Rig rig=new Rig();rig.acknowledge(true,true);rig.client.tick(NOW);rig.host.drain();
        assertEquals(0,rig.host.bundleReads);assertTrue(rig.host.sent("upload_offer").isEmpty());
        rig.host.explicitLocal=true;rig.client.tick(NOW);rig.host.drain();JsonObject offer=rig.host.sent("upload_offer").getFirst();
        JsonObject accept=PrivateModelSyncClient.envelope("upload_accept");accept.add("generation",offer.get("generation"));accept.add("hash",offer.get("hash"));
        accept.addProperty("uploadId",UUID.randomUUID().toString());accept.addProperty("chunkBytes",8192);
        JsonObject wrong=accept.deepCopy();wrong.addProperty("generation",UUID.randomUUID().toString());rig.receive(wrong,NOW);rig.client.tick(NOW);
        assertTrue(rig.host.sent("upload_chunk").isEmpty());
        rig.receive(accept,NOW);rig.client.tick(NOW);rig.client.tick(NOW);assertEquals(1,rig.host.sent("upload_chunk").size());assertEquals(1,rig.host.sent("upload_end").size());
        JsonObject commit=PrivateModelSyncClient.envelope("upload_committed");commit.add("generation",offer.get("generation"));commit.add("hash",offer.get("hash"));
        JsonObject stale=commit.deepCopy();stale.addProperty("hash","0".repeat(64));rig.receive(stale,NOW);assertFalse(rig.client.committed());
        rig.receive(commit,NOW);assertTrue(rig.client.committed());rig.host.explicitLocal=false;rig.client.tick(NOW);
        assertFalse(rig.client.committed());assertEquals(1,rig.host.sent("clear").size());
    }

    @Test void aTamperedCacheDoesNotSkipValidationOrGrantRendering() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);byte[] corrupt=rig.bundle.clone();corrupt[0]^=1;
        rig.host.cached=CompletableFuture.completedFuture(corrupt);Offer offer=rig.offer();rig.receive(offer.packet(),NOW);rig.host.drain();
        rig.receive(offer.identity("private_ack"),NOW);assertEquals(0,rig.host.decodes);assertTrue(rig.host.visible.isEmpty());
        assertEquals("missing",rig.host.sent("private_status").getLast().get("status").getAsString());
    }

    @Test void persistentAuthorRoamingConfigurationIsRetainedAcrossOfferAndStateUpdates() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);rig.host.cached=CompletableFuture.completedFuture(rig.bundle);Offer offer=rig.offer();
        JsonObject packet=offer.packet();packet.getAsJsonObject("appearance").getAsJsonObject("variables").addProperty("variable.roaming.red_bow_headdress",0);
        packet.getAsJsonObject("appearance").getAsJsonObject("variables").addProperty("variable.装饰.颜色",3);
        rig.receive(packet,NOW);rig.host.drain();rig.receive(offer.identity("private_ack"),NOW);
        assertEquals(offer.generation,rig.host.visible.get(offer.owner));
        assertEquals(0,rig.client.remote(offer.owner).appearance.getAsJsonObject("variables").get("variable.roaming.red_bow_headdress").getAsDouble());
        assertEquals(3,rig.client.remote(offer.owner).appearance.getAsJsonObject("variables").get("variable.装饰.颜色").getAsDouble());
        JsonObject state=offer.state(1,1);state.getAsJsonObject("appearance").getAsJsonObject("variables").addProperty("variable.roaming.red_bow_headdress",1);
        rig.receive(state,NOW);
        assertEquals(1,rig.client.remote(offer.owner).appearance.getAsJsonObject("variables").get("variable.roaming.red_bow_headdress").getAsDouble());
    }

    @Test void aWorldRenderFailureRevokesOnlyTheMatchingPrivateInstanceAndStopsItsHeartbeat() throws Exception {
        Rig rig=new Rig();rig.acknowledge(false,true);rig.host.cached=CompletableFuture.completedFuture(rig.bundle);
        Offer old=rig.offer();rig.activate(old);
        Offer current=new Offer(old.owner,UUID.randomUUID(),UUID.randomUUID(),old.hash,old.bytes);rig.activate(current);
        assertFalse(rig.client.rejectRemote(old.owner,old.generation));
        assertEquals(current.generation,rig.host.visible.get(current.owner));
        assertTrue(rig.client.rejectRemote(current.owner,current.generation));
        assertNull(rig.client.remote(current.owner));assertTrue(rig.host.visible.isEmpty());
        JsonObject rejected=rig.host.sent("private_status").getLast();
        assertEquals(current.offerId.toString(),rejected.get("offerId").getAsString());
        assertEquals("rejected",rejected.get("status").getAsString());
        rig.receive(current.identity("private_ack"),NOW);rig.client.tick(NOW);
        assertTrue(rig.host.sent("private_heartbeat").getLast().getAsJsonArray("bindings").isEmpty());
        assertTrue(rig.host.visible.isEmpty());
    }

    @Test void serverRevocationClearsTheCurrentPublicationAndRetriesWithAFreshGenerationAfterBackoff() throws Exception {
        Rig rig=new Rig();rig.acknowledge(true,false);rig.host.explicitLocal=true;
        rig.client.tick(NOW);rig.host.drain();JsonObject offer=rig.host.sent("upload_offer").getLast();
        JsonObject committed=publicationNotice("upload_committed",rig.host.local.owner(),offer);rig.receive(committed,NOW);
        assertTrue(rig.client.committed());
        JsonObject remove=publicationNotice("private_remove",rig.host.local.owner(),offer);
        JsonObject wrongOwner=remove.deepCopy();wrongOwner.addProperty("owner",UUID.randomUUID().toString());rig.receive(wrongOwner,NOW);
        JsonObject wrongHash=remove.deepCopy();wrongHash.addProperty("hash","0".repeat(64));rig.receive(wrongHash,NOW);
        assertTrue(rig.client.committed());
        rig.receive(remove,NOW);assertFalse(rig.client.committed());
        rig.receive(heartbeat(),NOW+9*SECOND);rig.client.tick(NOW+9*SECOND);assertEquals(1,rig.host.bundleReads);
        rig.receive(heartbeat(),NOW+11*SECOND);rig.client.tick(NOW+11*SECOND);rig.host.drain();
        assertEquals(2,rig.host.bundleReads);JsonObject replacement=rig.host.sent("upload_offer").getLast();
        assertNotEquals(offer.get("generation"),replacement.get("generation"));
        rig.receive(publicationNotice("upload_committed",rig.host.local.owner(),replacement),NOW+11*SECOND);
        rig.receive(remove,NOW+11*SECOND);assertTrue(rig.client.committed());
    }

    private static JsonObject publicationNotice(String type,UUID owner,JsonObject offer){
        JsonObject packet=PrivateModelSyncClient.envelope(type);packet.addProperty("owner",owner.toString());
        packet.add("generation",offer.get("generation"));packet.add("hash",offer.get("hash"));return packet;
    }

    @Test void actualServerUploadRejectionsCancelIdentityAndNeverContinueSendingChunks() throws Exception {
        for(String code:List.of("private_upload_denied","private_upload_busy","private_upload_integrity",
                "private_upload_cooldown","private_upload_expired","private_storage_busy","private_generation_reused",
                "private_bundle_invalid","server_model_priority","private_appearance_size")){
            Rig rig=new Rig();rig.acknowledge(true,false);rig.host.explicitLocal=true;
            rig.client.tick(NOW);rig.host.drain();JsonObject offer=rig.host.sent("upload_offer").getLast();
            JsonObject accepted=publicationNotice("upload_accept",rig.host.local.owner(),offer);
            accepted.addProperty("uploadId",UUID.randomUUID().toString());accepted.addProperty("chunkBytes",8192);rig.receive(accepted,NOW);
            JsonObject rejected=PrivateModelSyncClient.envelope("error");rejected.addProperty("code",code);rig.receive(rejected,NOW);
            rig.receive(publicationNotice("upload_committed",rig.host.local.owner(),offer),NOW);assertFalse(rig.client.committed(),code);
            rig.client.tick(NOW+1);assertTrue(rig.host.sent("upload_chunk").isEmpty(),code);
            rig.receive(heartbeat(),NOW+9*SECOND);rig.client.tick(NOW+9*SECOND);assertEquals(1,rig.host.bundleReads,code);
            rig.receive(heartbeat(),NOW+11*SECOND);rig.client.tick(NOW+11*SECOND);rig.host.drain();assertEquals(2,rig.host.bundleReads,code);
            assertNotEquals(offer.get("generation"),rig.host.sent("upload_offer").getLast().get("generation"),code);
        }
    }

    @Test void permittingAuthorRoamingKeepsVariableNamesFiniteValuesAndCountBudgetsEnforced() throws Exception {
        for(String name:List.of("query.health","variable.roaming.bad-name","variable."+"x".repeat(120),
                "variable.MixedCase","variable.roaming."+"x".repeat(33))) {
            Rig rig=new Rig();rig.acknowledge(false,true);Offer offer=rig.offer();JsonObject packet=offer.packet();
            packet.getAsJsonObject("appearance").getAsJsonObject("variables").addProperty(name,1);
            rig.receive(packet,NOW);rig.host.drain();assertNull(rig.client.remote(offer.owner));assertEquals(0,rig.host.decodes);
        }
        for(double value:new double[]{1_000_001,-1_000_001}) {
            Rig rig=new Rig();rig.acknowledge(false,true);Offer offer=rig.offer();JsonObject packet=offer.packet();
            packet.getAsJsonObject("appearance").getAsJsonObject("variables").addProperty("variable.roaming.red_bow_headdress",value);
            rig.receive(packet,NOW);rig.host.drain();assertNull(rig.client.remote(offer.owner));assertEquals(0,rig.host.decodes);
        }
        for(String overflow:List.of("1e309","-1e309")) {
            Rig rig=new Rig();rig.acknowledge(false,true);Offer offer=rig.offer();JsonObject packet=offer.packet();
            packet.getAsJsonObject("appearance").getAsJsonObject("variables").addProperty("variable.roaming.red_bow_headdress",0);
            // Valid JSON syntax that overflows a double must still fail the finite-value guard.
            String json=packet.toString().replace("\"variable.roaming.red_bow_headdress\":0","\"variable.roaming.red_bow_headdress\":"+overflow);
            rig.client.receive(json.getBytes(StandardCharsets.UTF_8),NOW);rig.host.drain();assertNull(rig.client.remote(offer.owner));assertEquals(0,rig.host.decodes);
        }
        for(String prefix:List.of("variable.roaming.","variable.")){
            Rig rig=new Rig();rig.acknowledge(false,true);Offer offer=rig.offer();JsonObject packet=offer.packet();
            JsonObject variables=packet.getAsJsonObject("appearance").getAsJsonObject("variables");
            int count=prefix.equals("variable.")?129:65;
            for(int i=0;i<count;i++)variables.addProperty(prefix+"option_"+i,0);
            rig.receive(packet,NOW);rig.host.drain();assertNull(rig.client.remote(offer.owner));assertEquals(0,rig.host.decodes);
        }
    }

    private record Event(UUID owner,UUID generation,List<Double> args) { }
    private record Offer(UUID owner,UUID generation,UUID offerId,String hash,int bytes) {
        JsonObject identity(String type) {
            JsonObject packet=PrivateModelSyncClient.envelope(type);packet.addProperty("owner",owner.toString());
            packet.addProperty("generation",generation.toString());packet.addProperty("hash",hash);return packet;
        }
        JsonObject packet() {
            JsonObject packet=identity("private_offer");packet.addProperty("offerId",offerId.toString());packet.addProperty("kind","bbmodel");
            packet.addProperty("bytes",bytes);packet.addProperty("sequence",0);packet.add("appearance",appearance(1));return packet;
        }
        JsonObject asset(String type) {JsonObject packet=PrivateModelSyncClient.envelope(type);packet.addProperty("offerId",offerId.toString());packet.addProperty("hash",hash);return packet;}
        JsonObject event(long sequence,List<Double> args) {JsonObject packet=identity("private_event");packet.addProperty("sequence",sequence);JsonArray values=new JsonArray();args.forEach(values::add);packet.add("args",values);return packet;}
        JsonObject state(long sequence,double scale) {JsonObject packet=identity("private_state");packet.addProperty("sequence",sequence);packet.add("appearance",appearance(scale));return packet;}
    }
    private static final class Rig {
        final byte[] bundle;
        final FakeHost host;
        final PrivateModelSyncClient client;
        Rig() throws Exception {bundle=fixtureBundle();host=new FakeHost(bundle);client=new PrivateModelSyncClient(host);}
        Offer offer() {return new Offer(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),AssetTransfer.hash(bundle),bundle.length);}
        void receive(JsonObject packet,long now) {client.receive(packet.toString().getBytes(StandardCharsets.UTF_8),now);}
        void acknowledge(boolean upload,boolean view) {
            JsonObject packet=PrivateModelSyncClient.envelope("hello_ack");JsonArray caps=new JsonArray();caps.add("private_models_v1");packet.add("capabilities",caps);
            packet.addProperty("allowedUpload",upload);packet.addProperty("allowedView",view);packet.addProperty("maxPayload",16000);
            packet.addProperty("maxBundleBytes",AssetTransfer.MAX_RAW);packet.addProperty("leaseTicks",20);receive(packet,NOW);
        }
        void begin(Offer offer) {JsonObject packet=offer.asset("asset_begin");packet.addProperty("bytes",bundle.length);packet.addProperty("chunks",1);receive(packet,NOW);}
        void chunk(Offer offer,byte[] bytes) {JsonObject packet=offer.asset("asset_chunk");packet.addProperty("index",0);packet.addProperty("data",Base64.getEncoder().encodeToString(bytes));receive(packet,NOW);}
        void activate(Offer offer) {receive(offer.packet(),NOW);host.drain();receive(offer.identity("private_ack"),NOW);}
    }
    private static final class FakeHost implements PrivateModelSyncClient.Host {
        boolean channel=true,explicitLocal;
        int cacheReads,bundleReads,decodes,prepared;
        CompletableFuture<byte[]> cached=CompletableFuture.completedFuture(null),publication;
        final Deque<CompletableFuture<LocalModelLibrary.Loaded>> decodeResults=new ArrayDeque<>();
        final Deque<Runnable> dispatch=new ArrayDeque<>();
        final List<JsonObject> packets=new ArrayList<>();
        final List<String> controlAttempts=new ArrayList<>();
        final Set<String> blockedTypes=new HashSet<>();
        private record TimedControl(String type,long time) { }
        final List<TimedControl> controlTimes=new ArrayList<>();
        long now=NOW;
        final Map<UUID,UUID> visible=new HashMap<>();
        final List<Event> events=new ArrayList<>();
        final PrivateModelSyncClient.Local local;
        FakeHost(byte[] bundle) {publication=CompletableFuture.completedFuture(bundle);local=new PrivateModelSyncClient.Local(UUID.randomUUID(),"local:test.bbmodel",appearance(1),extra());}
        public boolean channelAvailable(){return channel;}
        public boolean send(JsonObject packet){String type=packet.get("type").getAsString();controlAttempts.add(type);controlTimes.add(new TimedControl(type,now));if(blockedTypes.contains(type))return false;packets.add(packet.deepCopy());return true;}
        public PrivateModelSyncClient.Local local(){return explicitLocal?local:null;}
        public CompletableFuture<byte[]> bundle(String modelId){bundleReads++;return publication;}
        public CompletableFuture<byte[]> cached(String hash){cacheReads++;return cached;}
        public CompletableFuture<LocalModelLibrary.Loaded> decode(byte[] bundle,String texture){
            decodes++;if(!decodeResults.isEmpty())return decodeResults.removeFirst();
            try{return CompletableFuture.completedFuture(NativeModelBundle.decode(bundle,texture));}
            catch(Exception invalid){return CompletableFuture.failedFuture(invalid);}
        }
        public void cache(String hash,byte[] bundle){assertEquals(hash,AssetTransfer.hash(bundle));}
        public void dispatch(Runnable task){dispatch.addLast(task);}
        public boolean prepare(PrivateModelSyncClient.Remote remote,LocalModelLibrary.Loaded loaded){assertNotNull(loaded.model());prepared++;return true;}
        public void remove(UUID owner,UUID generation){visible.remove(owner,generation);}
        public void state(PrivateModelSyncClient.Remote remote){if(remote.ready&&remote.active)visible.put(remote.owner,remote.generation);else visible.remove(remote.owner,remote.generation);}
        public void event(UUID owner,UUID generation,List<Double> args){events.add(new Event(owner,generation,args));}
        void drain(){while(!dispatch.isEmpty())dispatch.removeFirst().run();}
        List<JsonObject> sent(String type){return packets.stream().filter(packet->packet.get("type").getAsString().equals(type)).toList();}
        long attempts(String type){return controlAttempts.stream().filter(type::equals).count();}
    }
    private static JsonObject heartbeat(){JsonObject packet=PrivateModelSyncClient.envelope("heartbeat");packet.add("bindings",new JsonArray());return packet;}
    private static JsonObject appearance(double scale){
        JsonObject value=new JsonObject();value.addProperty("scale",scale);value.addProperty("offsetX",0);value.addProperty("offsetY",0);value.addProperty("offsetZ",0);
        value.addProperty("textureId","");value.add("variables",new JsonObject());value.add("radioSelections",new JsonObject());return value;
    }
    private static JsonObject extra(){JsonObject value=new JsonObject();value.addProperty("id","");value.addProperty("loop","ONCE");value.addProperty("locked",false);value.addProperty("sequence",0);return value;}
    private static byte[] fixtureBundle() throws Exception {
        JsonObject raw=JsonParser.parseString("""
            {"meta":{"format_version":"5.0"},"textures":[],"animations":[],
             "elements":[{"uuid":"cube","from":[0,0,0],"to":[16,16,16],"faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
             "outliner":[{"uuid":"root","name":"root","origin":[0,0,0],"children":["cube"]}]}
            """).getAsJsonObject();
        BufferedImage image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xffffffff);ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);
        JsonObject texture=new JsonObject();texture.addProperty("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(png.toByteArray()));raw.getAsJsonArray("textures").add(texture);
        return NativeModelBundle.encode("bbmodel","model.bbmodel",Map.of("model.bbmodel",raw.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
