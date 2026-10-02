package com.simmc.meplayeractions.client;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class RenderLeasesTest {
    private static RenderLeases.Binding binding(UUID owner) { return new RenderLeases.Binding(owner, UUID.randomUUID(), "a".repeat(64)); }
    @Test void onlyExactReadyBindingsCanRenewAndOmittedEntriesExpire() {
        var leases = new RenderLeases(); var first = binding(UUID.randomUUID()); var other = binding(UUID.randomUUID());
        leases.ready(first, 10); leases.ready(other, 10);
        leases.renew(List.of(new RenderLeases.Binding(first.owner(), UUID.randomUUID(), first.hash())), 100);
        leases.renew(List.of(new RenderLeases.Binding(first.owner(), first.instance(), "b".repeat(64))), 100);
        assertTrue(leases.expired(109).isEmpty()); leases.renew(List.of(first), 109);
        assertEquals(List.of(other), leases.expired(110)); assertTrue(leases.contains(first));
        assertEquals(List.of(first), leases.expired(209)); assertEquals(0, leases.size());
    }
    @Test void replacingAnInstanceInvalidatesOldHeartbeatAndCleanupReturnsOnlyCurrentInstance() {
        var leases = new RenderLeases(); var old = binding(UUID.randomUUID()); var next = binding(old.owner());
        leases.ready(old, 1); leases.ready(next, 2); assertFalse(leases.contains(old)); assertTrue(leases.contains(next));
        leases.renew(List.of(old), 80); assertEquals(List.of(next), leases.expired(102));
        leases.ready(next, 120); assertEquals(List.of(next), leases.clear()); assertEquals(0, leases.size());
    }
    @Test void expiryWorksAcrossUnsignedPaperTickRolloverAndRemovalEndsLease() {
        var leases = new RenderLeases(); var bound = binding(UUID.randomUUID()); leases.ready(bound, 0xfffffff0L);
        assertTrue(leases.expired(50).isEmpty()); assertEquals(List.of(bound), leases.expired(84));
        leases.ready(bound, 100); assertEquals(bound, leases.remove(bound.owner())); assertFalse(leases.contains(bound));
    }
}
