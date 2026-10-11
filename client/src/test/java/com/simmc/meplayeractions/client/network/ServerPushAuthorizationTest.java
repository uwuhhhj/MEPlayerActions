package com.simmc.meplayeractions.client.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ServerPushAuthorizationTest
{
    private final byte[] raw = "{\"valid\":\"asset\"}".getBytes(StandardCharsets.UTF_8);
    private final String hash = AssetTransfer.hash(this.raw);
    private final ServerPushAuthorization.Identity identity = identity(UUID.randomUUID(), this.hash);
    private final ServerPushAuthorization.Offer offer = new ServerPushAuthorization.Offer(UUID.randomUUID(),
            this.identity);

    private static ServerPushAuthorization.Identity identity(UUID owner, String hash)
    {
        return new ServerPushAuthorization.Identity(owner, UUID.randomUUID().toString(), "sample", hash);
    }

    @Test
    void handshakeMustEchoThePushModeAndBothCapabilities()
    {
        assertTrue(
                ServerPushAuthorization.acceptsHandshake("server-push", List.of("local_render", "server_push_models")));
        assertFalse(ServerPushAuthorization.acceptsHandshake("resource-pack",
                List.of("local_render", "server_push_models")));
        assertFalse(ServerPushAuthorization.acceptsHandshake("server-push",
                List.of("local_render", "resource_pack_models")));
        assertFalse(ServerPushAuthorization.acceptsHandshake("server-push", List.of("server_push_models")));
        assertFalse(ServerPushAuthorization.acceptsHandshake("server-push", null));
    }

    @Test
    void issuanceRequiresExactCurrentOwnerInstanceModelAndHash()
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        for (ServerPushAuthorization.Identity wrong : List.of(identity(UUID.randomUUID(), this.hash),
                new ServerPushAuthorization.Identity(this.identity.owner(), UUID.randomUUID().toString(), "sample",
                        this.hash),
                new ServerPushAuthorization.Identity(this.identity.owner(), this.identity.instance(), "another",
                        this.hash),
                new ServerPushAuthorization.Identity(this.identity.owner(), this.identity.instance(), "sample",
                        "b".repeat(64))))

        {
            assertThrows(IllegalArgumentException.class, () -> authorization.offer(this.offer, List.of(wrong), 0));
        }

        assertTrue(authorization.offer(this.offer, List.of(this.identity), 0));
        assertFalse(authorization.offer(this.offer, List.of(this.identity), 1));
    }

    @Test
    void bytesRequireAnIssuedMissingOfferAndExactTokenHashAndModel() throws Exception
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        List<ServerPushAuthorization.Identity> bindings = List.of(this.identity);
        byte[] gzip = gzip(this.raw);
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(this.offer.offerId(), this.hash,
                "sample", this.raw.length, gzip.length, 1, bindings, 0));
        authorization.offer(this.offer, bindings, 0);
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(this.offer.offerId(), this.hash,
                "sample", this.raw.length, gzip.length, 1, bindings, 1));
        authorization.missing(this.offer, bindings, 1);
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(UUID.randomUUID(), this.hash, "sample",
                this.raw.length, gzip.length, 1, bindings, 2));
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(this.offer.offerId(), "b".repeat(64),
                "sample", this.raw.length, gzip.length, 1, bindings, 2));
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(this.offer.offerId(), this.hash,
                "another", this.raw.length, gzip.length, 1, bindings, 2));
        authorization.begin(this.offer.offerId(), this.hash, "sample", this.raw.length, gzip.length, 1, bindings, 2);
        authorization.chunk(this.offer.offerId(), this.hash, 0, Base64.getEncoder().encodeToString(gzip), bindings, 3);
        assertArrayEquals(this.raw, authorization.end(this.offer.offerId(), this.hash, bindings, 4).finish());
        assertThrows(IllegalArgumentException.class,
                () -> authorization.chunk(this.offer.offerId(), this.hash, 0, "AA==", bindings, 5));
        assertThrows(IllegalArgumentException.class,
                () -> authorization.end(this.offer.offerId(), this.hash, bindings, 5));
    }

    @Test
    void aRemainingVisibleBindingCanFinishTheSameContentAfterFirstOwnerLeaves() throws Exception
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        ServerPushAuthorization.Identity other = identity(UUID.randomUUID(), this.hash);
        byte[] gzip = gzip(this.raw);
        authorization.offer(this.offer, List.of(this.identity), 0);
        authorization.missing(this.offer, List.of(this.identity), 1);
        authorization.begin(this.offer.offerId(), this.hash, "sample", this.raw.length, gzip.length, 1, List.of(other),
                2);
        authorization.chunk(this.offer.offerId(), this.hash, 0, Base64.getEncoder().encodeToString(gzip),
                List.of(other), 3);
        assertArrayEquals(this.raw, authorization.end(this.offer.offerId(), this.hash, List.of(other), 4).finish());
        assertTrue(authorization.current(this.offer, List.of(other), 5));
        assertEquals(List.of(this.offer), authorization.prune(List.of(), 6));
        assertFalse(authorization.current(this.offer, List.of(other), 7));
    }

    @Test
    void expiryCancelAndWorldResetInvalidateEvenAsynchronousCacheResults()
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        authorization.offer(this.offer, List.of(this.identity), 0);
        assertFalse(authorization.current(this.offer, List.of(this.identity), ServerPushAuthorization.IDLE_TIMEOUT));
        assertEquals(List.of(this.offer),
                authorization.prune(List.of(this.identity), ServerPushAuthorization.IDLE_TIMEOUT));
        ServerPushAuthorization.Offer second = new ServerPushAuthorization.Offer(UUID.randomUUID(), this.identity);
        authorization.offer(second, List.of(this.identity), 20_000_000_000L);
        assertTrue(authorization.remove(second));
        assertFalse(authorization.current(second, List.of(this.identity), 20_000_000_001L));
        ServerPushAuthorization.Offer third = new ServerPushAuthorization.Offer(UUID.randomUUID(), this.identity);
        authorization.offer(third, List.of(this.identity), 30_000_000_000L);
        authorization.clear();
        assertFalse(authorization.current(third, List.of(this.identity), 30_000_000_001L));
    }

    @Test
    void duplicateOfferCannotRestartTheIdleTimeout()
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        List<ServerPushAuthorization.Identity> bindings = List.of(this.identity);
        authorization.offer(this.offer, bindings, 0);
        authorization.missing(this.offer, bindings, 1);
        assertFalse(authorization.offer(this.offer, bindings, 10_000_000_000L));
        assertFalse(authorization.current(this.offer, bindings, 16_000_000_000L));
    }

    @Test
    void validChunkProgressCannotExtendTheAbsoluteLifetime() throws Exception
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        List<ServerPushAuthorization.Identity> bindings = List.of(this.identity);
        byte[] gzip = gzip(this.raw);
        authorization.offer(this.offer, bindings, 0);
        authorization.missing(this.offer, bindings, 1);
        authorization.begin(this.offer.offerId(), this.hash, "sample", this.raw.length, gzip.length, 1, bindings, 2);
        String encoded = Base64.getEncoder().encodeToString(gzip);
        for (long now = 10_000_000_000L; now < ServerPushAuthorization.MAX_LIFETIME; now += 10_000_000_000L)
        {
            authorization.chunk(this.offer.offerId(), this.hash, 0, encoded, bindings, now);
            assertTrue(authorization.current(this.offer, bindings, now));
        }
        assertThrows(IllegalArgumentException.class, () -> authorization.end(this.offer.offerId(), this.hash, bindings,
                ServerPushAuthorization.MAX_LIFETIME));
        assertEquals(List.of(this.offer), authorization.prune(bindings, ServerPushAuthorization.MAX_LIFETIME));
    }

    @Test
    void completedOrCancelledTokensCannotBeReissuedOrReboundToAnotherModel()
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        List<ServerPushAuthorization.Identity> bindings = List.of(this.identity);
        authorization.offer(this.offer, bindings, 0);
        assertTrue(authorization.remove(this.offer));
        assertFalse(authorization.offer(this.offer, bindings, 1));
        ServerPushAuthorization.Identity other = identity(UUID.randomUUID(), this.hash);
        assertThrows(IllegalArgumentException.class, () -> authorization
                .offer(new ServerPushAuthorization.Offer(this.offer.offerId(), other), List.of(other), 2));
        assertFalse(authorization.current(this.offer, bindings, 3));
        authorization.clear(); // A reconnect creates an independent protocol generation.
        assertTrue(authorization.offer(this.offer, bindings, 4));
    }

    @Test
    void concurrencyAndTransferMetadataAreBoundedWithoutEvictingAuthorizedWork()
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        List<ServerPushAuthorization.Identity> bindings = List.of(this.identity);
        for (int i = 0; i < ServerPushAuthorization.MAX_OFFERS; i++)

        {
            authorization.offer(new ServerPushAuthorization.Offer(UUID.randomUUID(), this.identity), bindings, 0);
        }

        assertThrows(IllegalArgumentException.class, () -> authorization.offer(this.offer, bindings, 1));
        assertEquals(ServerPushAuthorization.MAX_OFFERS, authorization.diagnostics().size());
        authorization.clear();
        authorization.offer(this.offer, bindings, 2);
        authorization.missing(this.offer, bindings, 3);
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(this.offer.offerId(), this.hash,
                "sample", AssetTransfer.MAX_RAW + 1, 1, 1, bindings, 4));
        assertThrows(IllegalArgumentException.class, () -> authorization.begin(this.offer.offerId(), this.hash,
                "sample", 1, AssetTransfer.MAX_COMPRESSED + 1, 1, bindings, 4));
        assertEquals(0, authorization.receiving());
    }

    @Test
    void corruptAndConflictingChunksNeverProduceValidatedBytes() throws Exception
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        List<ServerPushAuthorization.Identity> bindings = List.of(this.identity);
        byte[] gzip = gzip(this.raw);
        authorization.offer(this.offer, bindings, 0);
        authorization.missing(this.offer, bindings, 1);
        authorization.begin(this.offer.offerId(), this.hash, "sample", this.raw.length, gzip.length, 1, bindings, 2);
        authorization.chunk(this.offer.offerId(), this.hash, 0, Base64.getEncoder().encodeToString(gzip), bindings, 3);
        assertThrows(IllegalArgumentException.class,
                () -> authorization.chunk(this.offer.offerId(), this.hash, 0, "AA==", bindings, 4));
        AssetTransfer transfer = authorization.end(this.offer.offerId(), this.hash, bindings, 5);
        assertArrayEquals(this.raw, transfer.finish());
        AssetTransfer wrongHash = new AssetTransfer("sample", "b".repeat(64), this.raw.length, gzip.length, 1);
        wrongHash.put(0, Base64.getEncoder().encodeToString(gzip));
        assertThrows(IOException.class, wrongHash::finish);
    }

    @Test
    void incompleteTransferAndLastBindingHashChangeCannotAuthorizeInstallation() throws Exception
    {
        ServerPushAuthorization authorization = new ServerPushAuthorization();
        List<ServerPushAuthorization.Identity> bindings = List.of(this.identity);
        byte[] gzip = gzip(this.raw);
        authorization.offer(this.offer, bindings, 0);
        authorization.missing(this.offer, bindings, 1);
        authorization.begin(this.offer.offerId(), this.hash, "sample", this.raw.length, gzip.length, 1, bindings, 2);
        assertThrows(IOException.class, authorization.end(this.offer.offerId(), this.hash, bindings, 3)::finish);
        ServerPushAuthorization.Identity replacement = new ServerPushAuthorization.Identity(this.identity.owner(),
                this.identity.instance(), "sample", "b".repeat(64));
        assertFalse(authorization.current(this.offer, List.of(replacement), 4));
        assertEquals(List.of(this.offer), authorization.prune(List.of(replacement), 5));
        assertFalse(authorization.current(this.offer, bindings, 6));
    }

    private static byte[] gzip(byte[] raw) throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output))
        {
            gzip.write(raw);
        }
        return output.toByteArray();
    }
}
