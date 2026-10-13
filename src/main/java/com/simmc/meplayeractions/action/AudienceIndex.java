package com.simmc.meplayeractions.action;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class AudienceIndex {
    private final Map<UUID, Set<UUID>> viewersByOwner = new HashMap<>();
    private final Map<UUID, Set<UUID>> ownersByViewer = new HashMap<>();
    private final Set<UUID> unrestricted = new HashSet<>();

    void update(UUID owner, Set<UUID> viewers) {
        Set<UUID> next = Set.copyOf(viewers);
        Set<UUID> previous = this.viewersByOwner.put(owner, next);
        this.unrestricted.remove(owner);
        if (next.equals(previous)) {
            return;
        }

        if (previous != null) {
            for (UUID viewer : previous) {
                if (!next.contains(viewer)) {
                    removePair(viewer, owner);
                }
            }
        }

        for (UUID viewer : next) {
            if (previous == null || !previous.contains(viewer)) {
                this.ownersByViewer.computeIfAbsent(viewer, ignored -> new HashSet<>()).add(owner);
            }
        }
    }

    void unrestricted(UUID owner) {
        remove(owner);
        this.unrestricted.add(owner);
    }

    Set<UUID> candidates(UUID viewer) {
        Set<UUID> result = new HashSet<>(this.ownersByViewer.getOrDefault(viewer, Set.of()));
        result.addAll(this.unrestricted);
        if (this.viewersByOwner.containsKey(viewer)) {
            result.add(viewer);
        }
        return Set.copyOf(result);
    }

    void remove(UUID owner) {
        this.unrestricted.remove(owner);
        Set<UUID> previous = this.viewersByOwner.remove(owner);
        if (previous != null) {
            for (UUID viewer : previous) {
                removePair(viewer, owner);
            }
        }
    }

    private void removePair(UUID viewer, UUID owner) {
        Set<UUID> owners = this.ownersByViewer.get(viewer);
        if (owners != null) {
            owners.remove(owner);
            if (owners.isEmpty()) {
                this.ownersByViewer.remove(viewer);
            }
        }
    }

    void clear() {
        this.viewersByOwner.clear();
        this.ownersByViewer.clear();
        this.unrestricted.clear();
    }
}
