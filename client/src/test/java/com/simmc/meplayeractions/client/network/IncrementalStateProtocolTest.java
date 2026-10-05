package com.simmc.meplayeractions.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalStateProtocolTest {
    private static final String INSTANCE = "8b3a986f-f7d9-4bd5-9cee-6c310ef2ba00";
    private static final String NEXT_INSTANCE = "8b3a986f-f7d9-4bd5-9cee-6c310ef2ba01";
    private static final String HASH = "1".repeat(64), NEXT_HASH = "2".repeat(64);
    private static final UUID OWNER = UUID.fromString("ea8ab104-10c7-4a8e-bb82-9aeeef29a601");
    private static final UUID OTHER = UUID.fromString("ea8ab104-10c7-4a8e-bb82-9aeeef29a602");

    @Test void bothPeersMustAdvertiseTheIncrementalCapabilityBeforeOmissionsHaveMeaning() {
        var session = new IncrementalStateProtocol();
        session.acknowledge(List.of(IncrementalStateProtocol.INCREMENTAL));
        assertFalse(session.incremental(), "An unsolicited ACK cannot enable a capability never offered");
        session.helloSent(IncrementalStateProtocol.helloCapabilities(false));
        assertFalse(session.incremental(), "A client offer is not a completed negotiation");
        session.acknowledge(List.of("local_render", "server_push_models"));
        assertFalse(session.incremental(), "An old server keeps the complete state semantics");
        session.acknowledge(List.of("local_render", "server_push_models", IncrementalStateProtocol.INCREMENTAL));
        assertTrue(session.incremental());
        session.reset();
        assertFalse(session.incremental());
    }

    @Test void serverTimelineIsOfferedOnlyWhenRequestedAndMustAlsoBeAcknowledged() {
        var session = new IncrementalStateProtocol();
        assertFalse(IncrementalStateProtocol.helloCapabilities(false).contains(IncrementalStateProtocol.TIMELINE));
        assertTrue(IncrementalStateProtocol.helloCapabilities(true).contains(IncrementalStateProtocol.TIMELINE));
        session.helloSent(IncrementalStateProtocol.helloCapabilities(false));
        session.acknowledge(List.of(IncrementalStateProtocol.INCREMENTAL, IncrementalStateProtocol.TIMELINE));
        assertFalse(session.requestedTimeline()); assertFalse(session.serverTimeline());
        session.helloSent(IncrementalStateProtocol.helloCapabilities(true));
        assertTrue(session.requestedTimeline()); assertFalse(session.serverTimeline());
        session.acknowledge(List.of(IncrementalStateProtocol.INCREMENTAL));
        assertFalse(session.serverTimeline(), "The option does not imply that an old server negotiated the new capability");
        session.acknowledge(List.of(IncrementalStateProtocol.INCREMENTAL, IncrementalStateProtocol.TIMELINE));
        assertTrue(session.serverTimeline());
        session.helloSent(IncrementalStateProtocol.helloCapabilities(false));
        assertFalse(session.incremental()); assertFalse(session.serverTimeline());
    }

    @Test void legacyCapabilityRejectionRetriesTheOldHelloOnceWithoutLosingTheTimelinePreference() {
        for (String code : List.of("invalid_payload", "unsupported_capability")) {
            var session = new IncrementalStateProtocol();
            session.helloSent(session.nextHelloCapabilities(true), true);
            assertTrue(session.fallbackForError(code));
            var legacy = session.nextHelloCapabilities(true);
            assertEquals(List.of("local_render", "server_push_models"), legacy);
            session.helloSent(legacy, true); session.acknowledge(legacy);
            assertFalse(session.incremental()); assertFalse(session.serverTimeline());
            assertTrue(session.requestedTimeline(), "The original requested setting does not force endless rehandshakes with an old server");
            assertFalse(session.fallbackForError(code), "A malformed legacy hello cannot trigger unlimited retry");
        }
        var incompatible = new IncrementalStateProtocol();
        incompatible.helloSent(incompatible.nextHelloCapabilities(false));
        assertFalse(incompatible.fallbackForError("unsupported_protocol"));
    }

    @Test void omittedCatalogueRetainsActionsWhileAnExplicitEmptyOrChangedCatalogueReplacesThem() {
        var session = negotiated();
        var initial = state("{\"animations\":[{\"id\":\"wave\",\"label\":\"招手\"}]}");
        var first = session.animations(initial, List.<IncrementalStateProtocol.Animation>of(), Function.identity());
        assertEquals(List.of(new IncrementalStateProtocol.Animation("wave", "招手")), first);
        assertEquals(first, session.animations(state("{}"), first, Function.identity()),
                "Receiving a pose-only state must not empty the server action wheel");
        assertEquals(List.of(), session.animations(state("{\"animations\":[]}"), first, Function.identity()));
        var replacement = state("{\"animations\":[{\"id\":\"salute\",\"label\":\"敬礼\"}]}");
        assertEquals(List.of(new IncrementalStateProtocol.Animation("salute", "敬礼")),
                session.animations(replacement, first, Function.identity()));
        assertEquals(List.of(), session.animations(state("{}"), List.of(), Function.identity()),
                "A new binding starts with its own empty catalogue rather than inheriting a previous instance");
    }

    @Test void oldServersKeepTheLegacyMissingCatalogueAndClockOnlyHeartbeatBehaviour() {
        var session = new IncrementalStateProtocol();
        session.helloSent(IncrementalStateProtocol.helloCapabilities(false));
        session.acknowledge(List.of("local_render", "server_push_models"));
        var previous = List.of(new IncrementalStateProtocol.Animation("wave", "Wave"));
        assertEquals(List.of(), session.animations(state("{}"), previous, Function.identity()));
        var binding = new Lease(OWNER, INSTANCE, HASH, 10);
        assertFalse(renew(session, state("{}"), Map.of(OWNER, binding), 100),
                "Legacy heartbeats must not trigger incremental snapshot recovery");
        assertFalse(renew(session, heartbeat(identity(OTHER, NEXT_INSTANCE, NEXT_HASH)), Map.of(OWNER, binding), 200));
        assertEquals(10, binding.lastPacket, "A legacy/global heartbeat must not keep every binding alive forever");
    }

    @Test void fragmentedHeartbeatRenewsOnlyItsListedCurrentBindings() {
        var session = negotiated();
        var first = new Lease(OWNER, INSTANCE, HASH, 10);
        var second = new Lease(OTHER, NEXT_INSTANCE, NEXT_HASH, 20);
        Map<UUID, Lease> current = Map.of(OWNER, first, OTHER, second);
        assertFalse(renew(session, heartbeat(identity(OWNER, INSTANCE, HASH)), current, 100));
        assertEquals(100, first.lastPacket); assertEquals(20, second.lastPacket);
        assertTrue(current.containsKey(OTHER), "A heartbeat fragment is not a snapshot or an unbind instruction");
        assertFalse(renew(session, heartbeat(), current, 200), "An empty fragment does not report missing owners");
        assertEquals(100, first.lastPacket); assertEquals(20, second.lastPacket);
        assertFalse(renew(session, heartbeat(identity(OTHER, NEXT_INSTANCE, NEXT_HASH)), current, 300));
        assertEquals(100, first.lastPacket); assertEquals(300, second.lastPacket);
    }

    @Test void previousInstancesAndHashesCannotRenewAReplacementOrRecreateAnUnboundModel() {
        var session = negotiated();
        var replacement = new Lease(OWNER, NEXT_INSTANCE, NEXT_HASH, 10);
        Map<UUID, Lease> current = new LinkedHashMap<>(); current.put(OWNER, replacement);
        for (JsonObject previous : List.of(identity(OWNER, INSTANCE, HASH), identity(OWNER, NEXT_INSTANCE, HASH),
                identity(OWNER, INSTANCE, NEXT_HASH)))
            assertTrue(renew(session, heartbeat(previous), current, 100),
                    "Each instance/hash mismatch requires authoritative state without renewing or replacing the binding");
        assertEquals(10, replacement.lastPacket);
        assertEquals(1, current.size(), "Heartbeats cannot authorize a new binding");
        assertFalse(renew(session, heartbeat(identity(OWNER, NEXT_INSTANCE, NEXT_HASH)), current, 200));
        assertEquals(200, replacement.lastPacket);
        current.clear();
        assertTrue(renew(session, heartbeat(identity(OWNER, NEXT_INSTANCE, NEXT_HASH)), current, 300));
        assertTrue(current.isEmpty(), "A delayed heartbeat cannot resurrect an explicit unbind");
    }

    @Test void anExpiredRemoteBindingRequestsAFullSnapshotWhileKnownBindingsKeepTheirOwnLeases() {
        var session = negotiated();
        var own = new Lease(OWNER, INSTANCE, HASH, 10);
        var remote = new Lease(OTHER, NEXT_INSTANCE, NEXT_HASH, 20);
        Map<UUID, Lease> current = new LinkedHashMap<>(); current.put(OWNER, own); current.put(OTHER, remote);
        current.remove(OTHER); // A per-binding timeout can occur while the global connection remains live.
        assertTrue(renew(session, heartbeat(identity(OWNER, INSTANCE, HASH),
                identity(OTHER, NEXT_INSTANCE, NEXT_HASH)), current, 100));
        assertEquals(100, own.lastPacket);
        assertEquals(20, remote.lastPacket);
        assertFalse(current.containsKey(OTHER), "Only a subsequent server state may restore the remote binding");
    }

    @Test void missingAssetBindingsCanRenewOnlyTheSameEmptyHashIdentity() {
        var session = negotiated();
        var pending = new Lease(OWNER, INSTANCE, "", 10);
        assertTrue(renew(session, heartbeat(identity(OWNER, INSTANCE, HASH)), Map.of(OWNER, pending), 100));
        assertEquals(10, pending.lastPacket);
        assertFalse(renew(session, heartbeat(identity(OWNER, INSTANCE, "")), Map.of(OWNER, pending), 200));
        assertEquals(200, pending.lastPacket);
    }

    @Test void heartbeatSizeAndMalformedEntriesAreValidatedBeforeAnyLeaseChanges() {
        var session = negotiated();
        var binding = new Lease(OWNER, INSTANCE, HASH, 10);
        JsonObject maximum = heartbeat();
        for (int i = 0; i < 64; i++) maximum.getAsJsonArray("bindings").add(identity(OWNER, INSTANCE, HASH));
        renew(session, maximum, Map.of(OWNER, binding), 100);
        assertEquals(100, binding.lastPacket);
        maximum.getAsJsonArray("bindings").add(identity(OWNER, INSTANCE, HASH));
        assertThrows(IllegalArgumentException.class, () -> renew(session, maximum, Map.of(OWNER, binding), 200));
        assertEquals(100, binding.lastPacket);
        var invalid = heartbeat(identity(OWNER, INSTANCE, HASH), identity(OTHER, NEXT_INSTANCE, NEXT_HASH),
                identity(OWNER, INSTANCE, "not-a-hash"));
        assertThrows(IllegalArgumentException.class, () -> renew(session, invalid, Map.of(OWNER, binding), 300));
        assertEquals(100, binding.lastPacket, "Neither renewal nor a gap result occurs before the entire fragment validates");
        assertThrows(IllegalArgumentException.class, () -> renew(session, state("{}"), Map.of(OWNER, binding), 400));
    }

    @Test void malformedCataloguesCannotMasqueradeAsOmissionsOrClearTheCurrentWheel() {
        var session = negotiated();
        var previous = List.of(new IncrementalStateProtocol.Animation("wave", "Wave"));
        for (String invalid : List.of("{\"animations\":{}}", "{\"animations\":[0]}",
                "{\"animations\":[{\"id\":\"../ wave\",\"label\":\"Wave\"}]}",
                "{\"animations\":[{\"id\":\"wave\",\"label\":true}]}"))
            assertThrows(IllegalArgumentException.class, () -> session.animations(state(invalid), previous, Function.identity()));
        assertEquals(List.of(new IncrementalStateProtocol.Animation("wave", "Wave")), previous);
    }

    private static IncrementalStateProtocol negotiated() {
        var result = new IncrementalStateProtocol();
        var capabilities = IncrementalStateProtocol.helloCapabilities(false);
        result.helloSent(capabilities); result.acknowledge(capabilities); return result;
    }
    private static JsonObject state(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static JsonObject identity(UUID owner, String instance, String hash) {
        JsonObject result = new JsonObject(); result.addProperty("owner", owner.toString());
        result.addProperty("instance", instance); result.addProperty("hash", hash); return result;
    }
    private static JsonObject heartbeat(JsonObject... identities) {
        JsonObject result = WireJson.envelope("heartbeat"); result.addProperty("serverTick", 50);
        JsonArray bindings = new JsonArray(); for (JsonObject identity : identities) bindings.add(identity);
        result.add("bindings", bindings); return result;
    }
    private static boolean renew(IncrementalStateProtocol session, JsonObject packet, Map<UUID, Lease> current, long now) {
        return session.renewBindings(packet, current, lease -> lease.identity, (lease, received) -> lease.lastPacket = received, now);
    }
    private static final class Lease {
        final IncrementalStateProtocol.Identity identity;
        long lastPacket;
        Lease(UUID owner, String instance, String hash, long lastPacket) {
            this.identity = new IncrementalStateProtocol.Identity(owner, instance, hash); this.lastPacket = lastPacket;
        }
    }
}
