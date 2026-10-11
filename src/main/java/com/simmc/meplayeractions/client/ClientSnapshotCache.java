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

    ClientSnapshotCache(Supplier<List<ClientSyncService.StateSnapshot>> fallback) {
        this.fallback = Objects.requireNonNull(fallback);
    }

    void configure(Supplier<Set<UUID>> owners, Function<UUID, ClientSyncService.StateSnapshot> single) {
        this.owners = Objects.requireNonNull(owners);
        this.single = Objects.requireNonNull(single);
        this.initialized = false;
    }

    private void roll(long now) {
        if (!this.initialized || this.tick != now) {
            this.initialized = true;
            this.tick = now;
            this.ids = null;
            this.read.clear();
            this.current.clear();
        }
    }

    Set<UUID> owners(long now) {
        roll(now);
        if (this.ids == null) {
            if (this.owners != null) {
                this.ids = Set.copyOf(this.owners.get());
            }

            else {
                for (ClientSyncService.StateSnapshot state : List.copyOf(this.fallback.get())) {
                    this.current.put(state.owner(), state);
                    this.read.add(state.owner());
                }
                this.ids = Set.copyOf(this.current.keySet());
            }
        }
        return this.ids;
    }

    ClientSyncService.StateSnapshot get(UUID owner, long now) {
        roll(now);
        if (this.single == null) {
            owners(now);
        }

        else if (this.read.add(owner)) {
            ClientSyncService.StateSnapshot state = this.single.apply(owner);
            if (state != null) {
                this.current.put(owner, state);
            }

        }
        return this.current.get(owner);
    }

    List<ClientSyncService.StateSnapshot> all(long now) {
        List<ClientSyncService.StateSnapshot> result = new ArrayList<>();
        for (UUID owner : owners(now)) {
            ClientSyncService.StateSnapshot state = get(owner, now);
            if (state != null) {
                result.add(state);
            }
        }
        return List.copyOf(result);
    }

    void supplied(ClientSyncService.StateSnapshot state, long now) {
        roll(now);
        this.current.put(state.owner(), state);
        this.read.add(state.owner());
        if (this.ids != null && !this.ids.contains(state.owner())) {
            HashSet<UUID> expanded = new HashSet<>(this.ids);
            expanded.add(state.owner());
            this.ids = Set.copyOf(expanded);
        }
    }
}
