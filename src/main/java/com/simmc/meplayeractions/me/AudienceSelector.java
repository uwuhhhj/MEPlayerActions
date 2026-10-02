package com.simmc.meplayeractions.me;

import java.util.*;

/** Deterministic nearest-viewer selection; distances include the vertical axis. */
public final class AudienceSelector {
    private AudienceSelector() {}
    public record Candidate(UUID id, double distanceSquared) {}
    public static Set<UUID> select(UUID owner, boolean showSelf, double distance, int limit,
                                   Collection<Candidate> candidates) {
        var selected = new LinkedHashSet<UUID>();
        if (showSelf) selected.add(owner);
        candidates.stream().filter(c -> !c.id().equals(owner) && Double.isFinite(c.distanceSquared())
                        && c.distanceSquared() >= 0 && c.distanceSquared() < distance * distance)
                .sorted(Comparator.comparingDouble(Candidate::distanceSquared).thenComparing(Candidate::id))
                .map(Candidate::id).distinct().limit(limit).forEach(selected::add);
        return Set.copyOf(selected);
    }
}
