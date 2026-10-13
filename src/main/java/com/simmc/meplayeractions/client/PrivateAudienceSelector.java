package com.simmc.meplayeractions.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

final class PrivateAudienceSelector {
    private static final Comparator<Candidate> ORDER = Comparator.comparingDouble(Candidate::distance)
            .thenComparing(Candidate::viewer);
    private final int limit;
    private final double rangeSquared;
    private final PriorityQueue<Candidate> nearest;

    PrivateAudienceSelector(int limit, double rangeSquared) {
        this.limit = limit;
        this.rangeSquared = rangeSquared;
        this.nearest = new PriorityQueue<>(Math.max(1, limit), ORDER.reversed());
    }

    void consider(UUID viewer, double distance) {
        if (this.limit == 0 || !(distance < this.rangeSquared)) {
            return;
        }

        if (this.nearest.size() == this.limit) {
            Candidate farthest = this.nearest.peek();
            int comparison = Double.compare(distance, farthest.distance());
            if (comparison > 0 || comparison == 0 && viewer.compareTo(farthest.viewer()) >= 0) {
                return;
            }
            this.nearest.remove();
        }
        this.nearest.add(new Candidate(viewer, distance));
    }

    Set<UUID> viewers() {
        List<Candidate> ordered = new ArrayList<>(this.nearest);
        ordered.sort(ORDER);
        Set<UUID> result = new LinkedHashSet<>();
        for (Candidate candidate : ordered) {
            result.add(candidate.viewer());
        }
        return result;
    }

    private record Candidate(UUID viewer, double distance) {
    }
}
