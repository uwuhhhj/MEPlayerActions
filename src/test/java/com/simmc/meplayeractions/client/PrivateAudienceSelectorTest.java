package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PrivateAudienceSelectorTest {
    @Test
    void boundedHeapMatchesFullSortAcrossInputOrdersLimitsAndTies() {
        Random random = new Random(63);
        List<Candidate> candidates = new ArrayList<>();
        for (int number = 0; number < 2000; number++) {
            candidates.add(new Candidate(new UUID(random.nextLong(), number), random.nextInt(5000)));
        }
        Comparator<Candidate> order = Comparator.comparingDouble(Candidate::distance).thenComparing(Candidate::viewer);
        for (int limit : new int[] { 0, 1, 10, 100, 1000 }) {
            List<UUID> expected = candidates.stream().filter(candidate -> candidate.distance() < 4096)
                    .sorted(order).limit(limit).map(Candidate::viewer).toList();
            for (int iteration = 0; iteration < 5; iteration++) {
                Collections.shuffle(candidates, random);
                PrivateAudienceSelector selector = new PrivateAudienceSelector(limit, 4096);
                for (Candidate candidate : candidates) {
                    selector.consider(candidate.viewer(), candidate.distance());
                }
                assertEquals(expected, new ArrayList<>(selector.viewers()));
            }
        }
    }

    @Test
    void strictDistanceBoundaryAndUuidTieOrderRemainUnchanged() {
        PrivateAudienceSelector selector = new PrivateAudienceSelector(3, 4096);
        selector.consider(new UUID(0, 9), 4096);
        selector.consider(new UUID(0, 8), Double.NaN);
        selector.consider(new UUID(0, 7), Double.POSITIVE_INFINITY);
        selector.consider(new UUID(0, 4), 1);
        selector.consider(new UUID(0, 3), 1);
        selector.consider(new UUID(0, 2), 1);
        selector.consider(new UUID(0, 1), 1);
        assertEquals(List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3)),
                new ArrayList<>(selector.viewers()));
    }

    private record Candidate(UUID viewer, double distance) {
    }
}
