package com.simmc.meplayeractions.client;

import java.util.*;
import java.util.function.*;

/** One immutable snapshot per owner per tick; the owner directory need not build model state. */
final class ClientSnapshotCache {
    private final Supplier<List<ClientSyncService.StateSnapshot>> fallback;
    private Supplier<Set<UUID>> owners;
    private Function<UUID, ClientSyncService.StateSnapshot> single;
    private final Map<UUID, ClientSyncService.StateSnapshot> current = new HashMap<>();
    private final Set<UUID> read = new HashSet<>();
    private Set<UUID> ids;
    private long tick;
    private boolean initialized;
    ClientSnapshotCache(Supplier<List<ClientSyncService.StateSnapshot>> fallback) { this.fallback = Objects.requireNonNull(fallback); }
    void configure(Supplier<Set<UUID>> owners, Function<UUID, ClientSyncService.StateSnapshot> single) {
        this.owners = Objects.requireNonNull(owners); this.single = Objects.requireNonNull(single); initialized = false;
    }
    private void roll(long now) {
        if (!initialized || tick != now) { initialized = true; tick = now; ids = null; read.clear(); current.clear(); }
    }
    Set<UUID> owners(long now) {
        roll(now);
        if (ids == null) {
            if (owners != null) ids = Set.copyOf(owners.get());
            else {
                for (var state : List.copyOf(fallback.get())) { current.put(state.owner(), state); read.add(state.owner()); }
                ids = Set.copyOf(current.keySet());
            }
        }
        return ids;
    }
    ClientSyncService.StateSnapshot get(UUID owner, long now) {
        roll(now);
        if (single == null) owners(now);
        else if (read.add(owner)) {
            var state = single.apply(owner); if (state != null) current.put(owner, state);
        }
        return current.get(owner);
    }
    List<ClientSyncService.StateSnapshot> all(long now) {
        List<ClientSyncService.StateSnapshot> result = new ArrayList<>();
        for (UUID owner : owners(now)) { var state = get(owner, now); if (state != null) result.add(state); }
        return List.copyOf(result);
    }
    void supplied(ClientSyncService.StateSnapshot state, long now) {
        roll(now); current.put(state.owner(), state); read.add(state.owner());
        if (ids != null && !ids.contains(state.owner())) { var expanded = new HashSet<>(ids); expanded.add(state.owner()); ids = Set.copyOf(expanded); }
    }
}
