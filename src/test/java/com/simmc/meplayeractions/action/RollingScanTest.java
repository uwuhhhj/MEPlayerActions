package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RollingScanTest {
    @Test void thousandPlayersAreVisitedOnceAcrossOneHundredTicksInTenPlayerBatches() {
        var roster = IntStream.range(0, 1000).boxed().toList();
        var loads = new AtomicInteger(); var scan = new RollingScan<Integer>(100);
        Set<Integer> visited = new HashSet<>();
        for (int tick = 0; tick < 100; tick++) {
            var batch = scan.next(tick, () -> { loads.incrementAndGet(); return roster; });
            assertEquals(10, batch.size());
            for (int id : batch) assertTrue(visited.add(id), "No duplicate work inside a cycle");
        }
        assertEquals(new HashSet<>(roster), visited); assertEquals(1, loads.get());
        assertEquals(10, scan.next(100, () -> { loads.incrementAndGet(); return roster; }).size());
        assertEquals(2, loads.get());
    }

    @Test void unevenRosterIsSpreadAndChangesBecomeVisibleOnTheNextCycle() {
        List<Integer> roster = new ArrayList<>(IntStream.range(0, 101).boxed().toList());
        var scan = new RollingScan<Integer>(100); Set<Integer> visited = new HashSet<>();
        visited.addAll(scan.next(0, () -> roster)); roster.add(101);
        for (int tick = 1; tick < 100; tick++) {
            var batch = scan.next(tick, () -> roster); assertTrue(batch.size() <= 2); visited.addAll(batch);
        }
        assertEquals(101, visited.size()); assertFalse(visited.contains(101));
        for (int tick = 100; tick < 200; tick++) visited.addAll(scan.next(tick, () -> roster));
        assertTrue(visited.contains(101));
    }

    @Test void tinyEmptyAndRepeatedTickDoNotReloadOrAccessAnExhaustedRoster() {
        var loads = new AtomicInteger(); var scan = new RollingScan<Integer>(100);
        assertEquals(List.of(1), scan.next(0, () -> { loads.incrementAndGet(); return List.of(1); }));
        assertTrue(scan.next(0, () -> { fail("Same tick must not scan twice"); return List.of(); }).isEmpty());
        for (int tick = 1; tick < 100; tick++) assertTrue(scan.next(tick, () -> { fail("Roster retained for cycle"); return List.of(); }).isEmpty());
        assertEquals(1, loads.get()); assertTrue(scan.next(100, List::of).isEmpty());
        assertTrue(scan.next(101, () -> { fail("Empty cycle also stays cached"); return List.of(); }).isEmpty());
        scan.clear(); assertEquals(List.of(2), scan.next(102, () -> List.of(2)));
    }

    @Test void clockJumpStartsOneBoundedBatchRatherThanCatchingUpAllMissingWork() {
        var roster = IntStream.range(0, 1000).boxed().toList(); var scan = new RollingScan<Integer>(100);
        assertEquals(10, scan.next(0, () -> roster).size());
        assertEquals(10, scan.next(80, () -> roster).size());
        assertEquals(10, scan.next(200, () -> roster).size());
        assertEquals(10, scan.next(1, () -> roster).size());
    }
}
