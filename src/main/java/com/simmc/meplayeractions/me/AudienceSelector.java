package com.simmc.meplayeractions.me;

import java.util.*;

/** Deterministic nearest-viewer selection; distances include the vertical axis. */
public final class AudienceSelector {
    private AudienceSelector() {}
    public record Candidate(UUID id, double distanceSquared) {}
    private static final Comparator<Candidate> NEAREST = Comparator.comparingDouble(Candidate::distanceSquared)
            .thenComparing(Candidate::id);
    public static Set<UUID> select(UUID owner, boolean showSelf, double distance, int limit,
                                   Collection<Candidate> candidates) {
        var selected = new LinkedHashSet<UUID>();
        if (showSelf) selected.add(owner);
        if (limit < 0) throw new IllegalArgumentException("Viewer limit must not be negative");
        if (limit == 0) return Set.copyOf(selected);
        // Keep only the nearest entry per UUID, then a bounded top-K heap instead
        // of sorting every tracked player. The original UUID tie-break is retained.
        Map<UUID, Candidate> distinct = new HashMap<>();
        double bound = distance * distance;
        for (Candidate candidate : candidates) {
            if (candidate.id().equals(owner) || !Double.isFinite(candidate.distanceSquared())
                    || candidate.distanceSquared() < 0 || candidate.distanceSquared() >= bound) continue;
            distinct.merge(candidate.id(), candidate, (a, b) -> NEAREST.compare(a, b) <= 0 ? a : b);
        }
        PriorityQueue<Candidate> nearest = new PriorityQueue<>(Math.min(limit, 16), NEAREST.reversed());
        for (Candidate candidate : distinct.values()) {
            if (nearest.size() < limit) nearest.add(candidate);
            else if (NEAREST.compare(candidate, nearest.peek()) < 0) {
                nearest.remove(); nearest.add(candidate);
            }
        }
        nearest.forEach(candidate -> selected.add(candidate.id()));
        return Set.copyOf(selected);
    }
}
