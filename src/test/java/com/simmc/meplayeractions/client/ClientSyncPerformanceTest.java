package com.simmc.meplayeractions.client;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ClientSyncPerformanceTest {
    @Test void ownerDirectoryDoesNotBuildSnapshotsAndEachOwnerIsBuiltAtMostOncePerTick() {
        UUID owner = new UUID(0, 1); AtomicInteger directory = new AtomicInteger(), single = new AtomicInteger();
        var cache = new ClientSnapshotCache(() -> { throw new AssertionError("Full directory snapshot should not be used"); });
        cache.configure(() -> { directory.incrementAndGet(); return Set.of(owner); }, id -> { single.incrementAndGet(); return null; });
        assertEquals(Set.of(owner), cache.owners(10)); assertEquals(Set.of(owner), cache.owners(10));
        assertEquals(1, directory.get()); assertEquals(0, single.get());
        assertNull(cache.get(owner, 10)); assertNull(cache.get(owner, 10)); cache.all(10);
        assertEquals(1, single.get()); cache.get(owner, 11); assertEquals(2, single.get());
    }
    @Test void oldFullSnapshotSupplierIsReadOnceForAnyNumberOfViewersInOneTick() {
        AtomicInteger calls = new AtomicInteger(); UUID unknown = UUID.randomUUID();
        var cache = new ClientSnapshotCache(() -> { calls.incrementAndGet(); return List.of(); });
        for (int viewer = 0; viewer < 100; viewer++) { cache.owners(30); cache.get(unknown, 30); cache.all(30); }
        assertEquals(1, calls.get()); cache.owners(31); assertEquals(2, calls.get());
    }
    @Test void discoveryIsStaggeredAndLegacyHeartbeatAndTimelineKeepIndependentCadences() {
        Set<Long> nextDiscovery = new HashSet<>();
        for (int id = 0; id < 40; id++) {
            long previous = ClientSyncCadence.initialTick(new UUID(0, id), 100, 40);
            long next = 101; while (!ClientSyncCadence.due(next, previous, 40)) next++;
            nextDiscovery.add(next); assertTrue(next > 100 && next <= 140);
            assertFalse(ClientSyncCadence.due(next + 39, next, 40)); assertTrue(ClientSyncCadence.due(next + 40, next, 40));
        }
        assertEquals(40, nextDiscovery.size());
        assertFalse(ClientSyncCadence.due(119, 100, ClientSyncCadence.LEGACY_STATE_TICKS));
        assertTrue(ClientSyncCadence.due(120, 100, ClientSyncCadence.LEGACY_STATE_TICKS));
        assertTrue(ClientSyncCadence.due(102, 100, ClientSyncCadence.TIMELINE_TICKS));
        assertTrue(ClientSyncCadence.due(10, 0xfffffff6L, 20));
    }
    @Test void deferredControlRetainsSameSerializedBytesAndNeverRunsFailureCleanup() {
        var queue = new DeferredClientPackets(); byte[] bytes = {1, 2, 3}; AtomicInteger sent = new AtomicInteger(), failed = new AtomicInteger();
        assertTrue(queue.add("ack", bytes, 10, () -> true, sent::incrementAndGet, failed::incrementAndGet));
        for (int tick = 11; tick < 20; tick++) queue.drain(payload -> { assertSame(bytes, payload); return DeferredClientPackets.Result.DEFERRED; }, tick, 4);
        assertEquals(1, queue.size()); assertEquals(0, sent.get()); assertEquals(0, failed.get());
        queue.drain(payload -> { assertSame(bytes, payload); return DeferredClientPackets.Result.SENT; }, 20, 4);
        assertEquals(0, queue.size()); assertEquals(1, sent.get()); assertEquals(0, failed.get());
    }
    @Test void deferredControlIsBoundedAndInvalidOrExpiredIdentityExecutesCleanup() {
        var queue = new DeferredClientPackets(); AtomicInteger failed = new AtomicInteger();
        for (int i = 0; i < DeferredClientPackets.CAPACITY; i++) assertTrue(queue.add("unbind:" + i, new byte[]{1}, 0, () -> true, () -> {}, failed::incrementAndGet));
        assertFalse(queue.add("overflow", new byte[]{1}, 0, () -> true, () -> {}, failed::incrementAndGet)); assertEquals(1, failed.get());
        queue.drain(payload -> { fail("Expired control must not be sent"); return DeferredClientPackets.Result.SENT; }, 100, 128);
        assertEquals(129, failed.get()); assertEquals(0, queue.size());
        queue.add("invalid_ack", new byte[]{1}, 101, () -> false, () -> fail("Invalid ack sent"), failed::incrementAndGet);
        queue.drain(payload -> DeferredClientPackets.Result.SENT, 102, 4); assertEquals(130, failed.get());
    }
    @Test void controlSuccessCanEnqueueSnapshotWithoutMutatingAnActiveIterator() {
        var queue = new DeferredClientPackets(); AtomicInteger sent = new AtomicInteger();
        queue.add("hello", new byte[]{1}, 0, () -> true,
                () -> queue.add("snapshot", new byte[]{2}, 1, () -> true, sent::incrementAndGet, () -> fail()), () -> fail());
        queue.drain(payload -> DeferredClientPackets.Result.SENT, 1, 4);
        assertEquals(1, queue.size()); assertEquals(0, sent.get());
        queue.drain(payload -> DeferredClientPackets.Result.SENT, 2, 4); assertEquals(1, sent.get());
    }
    @Test void identityHeartbeatFragmentsKeepEveryBindingUnderBothSizeAndCountBudgets() {
        List<RenderLeases.Binding> bindings = new ArrayList<>();
        for (int id = 0; id < 150; id++) bindings.add(new RenderLeases.Binding(new UUID(0, id), new UUID(1, id), id % 2 == 0 ? "a".repeat(64) : ""));
        for (int maxPayload : List.of(1024, 16000)) {
            Set<UUID> seen = new HashSet<>(); var packets = ClientSyncService.heartbeatPackets(22, bindings, maxPayload);
            for (byte[] bytes : packets) {
                assertTrue(bytes.length <= maxPayload); JsonObject packet = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals("heartbeat", packet.get("type").getAsString()); assertEquals(22, packet.get("serverTick").getAsInt());
                JsonArray identities = packet.getAsJsonArray("bindings"); assertTrue(identities.size() <= 64);
                for (var identity : identities) assertTrue(seen.add(UUID.fromString(identity.getAsJsonObject().get("owner").getAsString())));
            }
            assertEquals(150, seen.size());
        }
        assertFalse(ClientSyncService.heartbeatPacket(22, null).has("bindings"));
        assertEquals(0, JsonParser.parseString(new String(ClientSyncService.heartbeatPackets(22, List.of(), 1024).getFirst(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("bindings").size());
    }
}
