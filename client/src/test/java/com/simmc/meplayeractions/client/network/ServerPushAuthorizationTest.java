package com.simmc.meplayeractions.client.network;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ServerPushAuthorizationTest {
    private final byte[] raw = "{\"valid\":\"asset\"}".getBytes(StandardCharsets.UTF_8);
    private final String hash = AssetTransfer.hash(raw);
    private final ServerPushAuthorization.Identity identity = identity(UUID.randomUUID(), hash);
    private final ServerPushAuthorization.Offer offer = new ServerPushAuthorization.Offer(UUID.randomUUID(), identity);
    private static ServerPushAuthorization.Identity identity(UUID owner, String hash) {
        return new ServerPushAuthorization.Identity(owner, UUID.randomUUID().toString(), "sample", hash);
    }

    @Test void handshakeMustEchoThePushModeAndBothCapabilities() {
        assertTrue(ServerPushAuthorization.acceptsHandshake("server-push", List.of("local_render", "server_push_models")));
        assertFalse(ServerPushAuthorization.acceptsHandshake("resource-pack", List.of("local_render", "server_push_models")));
        assertFalse(ServerPushAuthorization.acceptsHandshake("server-push", List.of("local_render", "resource_pack_models")));
        assertFalse(ServerPushAuthorization.acceptsHandshake("server-push", List.of("server_push_models")));
        assertFalse(ServerPushAuthorization.acceptsHandshake("server-push", null));
    }
    @Test void issuanceRequiresExactCurrentOwnerInstanceModelAndHash() {
        var authorization = new ServerPushAuthorization();
        for (var wrong : List.of(identity(UUID.randomUUID(), hash),
                new ServerPushAuthorization.Identity(identity.owner(), UUID.randomUUID().toString(), "sample", hash),
                new ServerPushAuthorization.Identity(identity.owner(), identity.instance(), "another", hash),
                new ServerPushAuthorization.Identity(identity.owner(), identity.instance(), "sample", "b".repeat(64))))
            assertThrows(IllegalArgumentException.class, () -> authorization.offer(offer, List.of(wrong), 0));
        assertTrue(authorization.offer(offer, List.of(identity), 0));
        assertFalse(authorization.offer(offer, List.of(identity), 1));
    }
    @Test void bytesRequireAnIssuedMissingOfferAndExactTokenHashAndModel() throws Exception {
        var authorization = new ServerPushAuthorization(); var bindings = List.of(identity); byte[] gzip = gzip(raw);
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(offer.offerId(), hash, "sample", raw.length, gzip.length, 1, bindings, 0));
        authorization.offer(offer, bindings, 0);
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(offer.offerId(), hash, "sample", raw.length, gzip.length, 1, bindings, 1));
        authorization.missing(offer, bindings, 1);
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(UUID.randomUUID(), hash, "sample", raw.length, gzip.length, 1, bindings, 2));
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(offer.offerId(), "b".repeat(64), "sample", raw.length, gzip.length, 1, bindings, 2));
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(offer.offerId(), hash, "another", raw.length, gzip.length, 1, bindings, 2));
        authorization.begin(offer.offerId(), hash, "sample", raw.length, gzip.length, 1, bindings, 2);
        authorization.chunk(offer.offerId(), hash, 0, Base64.getEncoder().encodeToString(gzip), bindings, 3);
        assertArrayEquals(raw, authorization.end(offer.offerId(), hash, bindings, 4).finish());
        assertThrows(IllegalArgumentException.class, () -> authorization.chunk(offer.offerId(), hash, 0, "AA==", bindings, 5));
        assertThrows(IllegalArgumentException.class, () -> authorization.end(offer.offerId(), hash, bindings, 5));
    }
    @Test void aRemainingVisibleBindingCanFinishTheSameContentAfterFirstOwnerLeaves() throws Exception {
        var authorization = new ServerPushAuthorization(); var other = identity(UUID.randomUUID(), hash);
        byte[] gzip = gzip(raw); authorization.offer(offer, List.of(identity), 0);
        authorization.missing(offer, List.of(identity), 1);
        authorization.begin(offer.offerId(), hash, "sample", raw.length, gzip.length, 1, List.of(other), 2);
        authorization.chunk(offer.offerId(), hash, 0, Base64.getEncoder().encodeToString(gzip), List.of(other), 3);
        assertArrayEquals(raw, authorization.end(offer.offerId(), hash, List.of(other), 4).finish());
        assertTrue(authorization.current(offer, List.of(other), 5));
        assertEquals(List.of(offer), authorization.prune(List.of(), 6));
        assertFalse(authorization.current(offer, List.of(other), 7));
    }
    @Test void expiryCancelAndWorldResetInvalidateEvenAsynchronousCacheResults() {
        var authorization = new ServerPushAuthorization(); authorization.offer(offer, List.of(identity), 0);
        assertFalse(authorization.current(offer, List.of(identity), ServerPushAuthorization.IDLE_TIMEOUT));
        assertEquals(List.of(offer), authorization.prune(List.of(identity), ServerPushAuthorization.IDLE_TIMEOUT));
        var second = new ServerPushAuthorization.Offer(UUID.randomUUID(), identity);
        authorization.offer(second, List.of(identity), 20_000_000_000L);
        assertTrue(authorization.remove(second)); assertFalse(authorization.current(second, List.of(identity), 20_000_000_001L));
        var third = new ServerPushAuthorization.Offer(UUID.randomUUID(), identity);
        authorization.offer(third, List.of(identity), 30_000_000_000L); authorization.clear();
        assertFalse(authorization.current(third, List.of(identity), 30_000_000_001L));
    }
    @Test void duplicateOfferCannotRestartTheIdleTimeout() {
        var authorization = new ServerPushAuthorization(); var bindings = List.of(identity);
        authorization.offer(offer, bindings, 0); authorization.missing(offer, bindings, 1);
        assertFalse(authorization.offer(offer, bindings, 10_000_000_000L));
        assertFalse(authorization.current(offer, bindings, 16_000_000_000L));
    }
    @Test void validChunkProgressCannotExtendTheAbsoluteLifetime() throws Exception {
        var authorization = new ServerPushAuthorization(); var bindings = List.of(identity); byte[] gzip = gzip(raw);
        authorization.offer(offer, bindings, 0); authorization.missing(offer, bindings, 1);
        authorization.begin(offer.offerId(), hash, "sample", raw.length, gzip.length, 1, bindings, 2);
        String encoded = Base64.getEncoder().encodeToString(gzip);
        for (long now = 10_000_000_000L; now < ServerPushAuthorization.MAX_LIFETIME; now += 10_000_000_000L) {
            authorization.chunk(offer.offerId(), hash, 0, encoded, bindings, now);
            assertTrue(authorization.current(offer, bindings, now));
        }
        assertThrows(IllegalArgumentException.class, () -> authorization.end(offer.offerId(), hash, bindings, ServerPushAuthorization.MAX_LIFETIME));
        assertEquals(List.of(offer), authorization.prune(bindings, ServerPushAuthorization.MAX_LIFETIME));
    }
    @Test void completedOrCancelledTokensCannotBeReissuedOrReboundToAnotherModel() {
        var authorization = new ServerPushAuthorization(); var bindings = List.of(identity);
        authorization.offer(offer, bindings, 0); assertTrue(authorization.remove(offer));
        assertFalse(authorization.offer(offer, bindings, 1));
        var other = identity(UUID.randomUUID(), hash);
        assertThrows(IllegalArgumentException.class, () -> authorization.offer(
                new ServerPushAuthorization.Offer(offer.offerId(), other), List.of(other), 2));
        assertFalse(authorization.current(offer, bindings, 3));
        authorization.clear(); // A reconnect creates an independent protocol generation.
        assertTrue(authorization.offer(offer, bindings, 4));
    }
    @Test void concurrencyAndTransferMetadataAreBoundedWithoutEvictingAuthorizedWork() {
        var authorization = new ServerPushAuthorization(); var bindings = List.of(identity);
        for (int i = 0; i < ServerPushAuthorization.MAX_OFFERS; i++)
            authorization.offer(new ServerPushAuthorization.Offer(UUID.randomUUID(), identity), bindings, 0);
        assertThrows(IllegalArgumentException.class, () -> authorization.offer(offer, bindings, 1));
        assertEquals(ServerPushAuthorization.MAX_OFFERS, authorization.diagnostics().size());
        authorization.clear(); authorization.offer(offer, bindings, 2); authorization.missing(offer, bindings, 3);
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(offer.offerId(), hash, "sample", AssetTransfer.MAX_RAW + 1, 1, 1, bindings, 4));
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(offer.offerId(), hash, "sample", 1, AssetTransfer.MAX_COMPRESSED + 1, 1, bindings, 4));
        assertEquals(0, authorization.receiving());
    }
    @Test void corruptAndConflictingChunksNeverProduceValidatedBytes() throws Exception {
        var authorization = new ServerPushAuthorization(); var bindings = List.of(identity); byte[] gzip = gzip(raw);
        authorization.offer(offer, bindings, 0); authorization.missing(offer, bindings, 1);
        authorization.begin(offer.offerId(), hash, "sample", raw.length, gzip.length, 1, bindings, 2);
        authorization.chunk(offer.offerId(), hash, 0, Base64.getEncoder().encodeToString(gzip), bindings, 3);
        assertThrows(IllegalArgumentException.class, () -> authorization.chunk(offer.offerId(), hash, 0, "AA==", bindings, 4));
        var transfer = authorization.end(offer.offerId(), hash, bindings, 5);
        assertArrayEquals(raw, transfer.finish());
        var wrongHash = new AssetTransfer("sample", "b".repeat(64), raw.length, gzip.length, 1);
        wrongHash.put(0, Base64.getEncoder().encodeToString(gzip));
        assertThrows(java.io.IOException.class, wrongHash::finish);
    }
    @Test void incompleteTransferAndLastBindingHashChangeCannotAuthorizeInstallation() throws Exception {
        var authorization = new ServerPushAuthorization(); var bindings = List.of(identity); byte[] gzip = gzip(raw);
        authorization.offer(offer, bindings, 0); authorization.missing(offer, bindings, 1);
        authorization.begin(offer.offerId(), hash, "sample", raw.length, gzip.length, 1, bindings, 2);
        assertThrows(java.io.IOException.class, authorization.end(offer.offerId(), hash, bindings, 3)::finish);
        var replacement = new ServerPushAuthorization.Identity(identity.owner(), identity.instance(), "sample", "b".repeat(64));
        assertFalse(authorization.current(offer, List.of(replacement), 4));
        assertEquals(List.of(offer), authorization.prune(List.of(replacement), 5));
        assertFalse(authorization.current(offer, bindings, 6));
    }
    private static byte[] gzip(byte[] raw) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) { gzip.write(raw); }
        return output.toByteArray();
    }
}
