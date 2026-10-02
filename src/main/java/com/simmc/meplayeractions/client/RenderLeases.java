package com.simmc.meplayeractions.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exact instance/hash leases; an old ready or heartbeat cannot hide a replacement model. */
final class RenderLeases {
    static final int LEASE_TICKS = 100;
    private final Map<UUID, Lease> leases = new HashMap<>();

    void ready(Binding binding, long tick) { leases.put(binding.owner(), new Lease(binding, tick)); }
    boolean contains(Binding binding) {
        Lease lease = leases.get(binding.owner());
        return lease != null && lease.binding().equals(binding);
    }
    void renew(List<Binding> bindings, long tick) {
        for (Binding binding : bindings) if (contains(binding)) ready(binding, tick);
    }
    Binding remove(UUID owner) {
        Lease removed = leases.remove(owner);
        return removed == null ? null : removed.binding();
    }
    List<Binding> expired(long tick) {
        List<Binding> result = new ArrayList<>();
        for (Lease lease : List.copyOf(leases.values())) {
            if (((tick - lease.renewedAt()) & 0xffff_ffffL) >= LEASE_TICKS) {
                result.add(lease.binding()); leases.remove(lease.binding().owner());
            }
        }
        return List.copyOf(result);
    }
    List<Binding> clear() {
        List<Binding> result = leases.values().stream().map(Lease::binding).toList();
        leases.clear(); return result;
    }
    int size() { return leases.size(); }
    record Binding(UUID owner, UUID instance, String hash) {}
    private record Lease(Binding binding, long renewedAt) {}
}
