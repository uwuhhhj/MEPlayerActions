package com.simmc.meplayeractions.me;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeEntityRestorationTest {
    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class[] { World.class },
                (p, m, a) -> m.getName().equals("equals") ? p == a[0]
                        : (m.getName().equals("hashCode") ? System.identityHashCode(p) : null));
    }

    private static final class Person {
        final UUID id = UUID.randomUUID();
        final Player player;
        final Set<Player> tracked = new HashSet<>();
        World world;
        boolean online = true, visible = true;

        Person(World world) {
            this.world = world;
            this.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[] { Player.class },
                    (p, m, a) -> switch (m.getName()) {
                    case "getUniqueId" -> this.id;
                    case "getWorld" -> this.world;
                    case "isOnline" -> this.online;
                    case "canSee" -> this.visible;
                    case "getTrackedBy" -> this.tracked;
                    case "equals" -> p == a[0];
                    case "hashCode" -> this.id.hashCode();
                    default -> null;
                    });
        }
    }

    @Test
    void onlyFormerLocalViewersStillTrackedReceivePairing() {
        World world = world();
        Person owner = new Person(world), local = new Person(world), plain = new Person(world), far = new Person(world);
        owner.tracked.addAll(List.of(local.player, plain.player));
        assertEquals(List.of(local.player), NativeEntityRestoration.recipients(owner.player, Set.of(local.id, far.id)));
    }

    @Test
    void vanishWorldChangeOfflineAndMissingPlayersAreExcluded() {
        World world = world();
        Person owner = new Person(world), hidden = new Person(world), departed = new Person(world),
                otherWorld = new Person(world());
        hidden.visible = false;
        departed.online = false;
        UUID missing = UUID.randomUUID();
        owner.tracked.addAll(List.of(hidden.player, departed.player, otherWorld.player, owner.player));
        Set<UUID> previous = new HashSet<>(Set.of(hidden.id, departed.id, otherWorld.id, owner.id, missing));
        assertTrue(NativeEntityRestoration.recipients(owner.player, previous).isEmpty());
    }

    @Test
    void disconnectedOwnerOrAbsentVanillaTrackingNeverRespawns() {
        World world = world();
        Person owner = new Person(world), local = new Person(world);
        owner.online = false;
        owner.tracked.add(local.player);
        assertTrue(NativeEntityRestoration.recipients(owner.player, Set.of(local.id)).isEmpty());
        owner.online = true;
        owner.tracked.clear();
        assertTrue(NativeEntityRestoration.recipients(owner.player, Set.of(local.id)).isEmpty());
    }

    @Test
    void historicalPairingDoesNotForceAPlayerOutsideCurrentVanillaTracking() {
        World world = world();
        Person owner = new Person(world), former = new Person(world), current = new Person(world);
        owner.tracked.add(current.player);
        // ModelEngine forced pairings may remember former viewers even after vanilla tracking ends.
        assertTrue(NativeEntityRestoration.recipients(owner.player, Set.of(former.id)).isEmpty());
    }

    @Test
    void historicalViewerRotationKeepsOnlyCurrentTrackingRecords() {
        World world = world();
        Person owner = new Person(world), persistent = new Person(world);
        Set<UUID> previous = new HashSet<>(Set.of(persistent.id));
        Set<UUID> allVisitors = new HashSet<>(previous);
        for (int rotation = 0; rotation < 200; rotation++) {
            owner.tracked.clear();
            owner.tracked.add(persistent.player);
            Set<UUID> expected = new HashSet<>(Set.of(persistent.id));
            for (int index = 0; index < 8; index++) {
                Person viewer = new Person(world);
                owner.tracked.add(viewer.player);
                previous.add(viewer.id);
                allVisitors.add(viewer.id);
                expected.add(viewer.id);
            }

            NativeEntityRestoration.prune(owner.player, previous);
            assertEquals(expected, previous, "Rotation " + rotation);
            assertEquals(9, NativeEntityRestoration.recipients(owner.player, previous).size());
        }

        assertEquals(1601, allVisitors.size());
        assertEquals(9, previous.size());
    }

    @Test
    void trackingChangesPruneWorldVanishOfflineSelfAndLostTrackingRecords() {
        World world = world();
        Person owner = new Person(world), local = new Person(world), hidden = new Person(world),
                departed = new Person(world), moved = new Person(world), lostTracking = new Person(world),
                newlyTracked = new Person(world);
        owner.tracked.addAll(List.of(local.player, hidden.player, departed.player, moved.player, lostTracking.player,
                owner.player));
        Set<UUID> previous = new HashSet<>(
                Set.of(local.id, hidden.id, departed.id, moved.id, lostTracking.id, owner.id, UUID.randomUUID()));
        NativeEntityRestoration.prune(owner.player, previous);
        assertEquals(Set.of(local.id, hidden.id, departed.id, moved.id, lostTracking.id), previous);

        hidden.visible = false;
        departed.online = false;
        moved.world = world();
        owner.tracked.remove(lostTracking.player);
        owner.tracked.add(newlyTracked.player);
        NativeEntityRestoration.prune(owner.player, previous);

        assertEquals(Set.of(local.id), previous);
        assertEquals(List.of(local.player), NativeEntityRestoration.recipients(owner.player, previous));
    }

    @Test
    void offlineOwnerClearsHistoryEvenWhenPaperTrackingStillContainsViewers() {
        World world = world();
        Person owner = new Person(world), first = new Person(world), second = new Person(world);
        owner.tracked.addAll(List.of(first.player, second.player));
        Set<UUID> previous = new HashSet<>(Set.of(first.id, second.id));
        NativeEntityRestoration.prune(owner.player, previous);
        assertEquals(Set.of(first.id, second.id), previous);

        owner.online = false;
        NativeEntityRestoration.prune(owner.player, previous);
        assertTrue(previous.isEmpty());

        owner.online = true;
        NativeEntityRestoration.prune(owner.player, previous);
        assertTrue(previous.isEmpty());
        assertTrue(NativeEntityRestoration.recipients(owner.player, previous).isEmpty());
    }

    @Test
    void moreThanTenCurrentPaperTrackersSurviveTheModelEngineAudienceLimit() {
        World world = world();
        Person owner = new Person(world);
        Set<UUID> expected = new HashSet<>();
        Set<Player> expectedRecipients = new HashSet<>();
        for (int index = 0; index < 32; index++) {
            Person viewer = new Person(world);
            owner.tracked.add(viewer.player);
            expected.add(viewer.id);
            expectedRecipients.add(viewer.player);
        }

        Set<UUID> previous = new HashSet<>(expected);
        previous.add(UUID.randomUUID());
        NativeEntityRestoration.prune(owner.player, previous);

        assertEquals(32, previous.size());
        assertEquals(expected, previous);
        assertEquals(expectedRecipients, new HashSet<>(NativeEntityRestoration.recipients(owner.player, previous)));
    }

    @Test
    void unrestoredCurrentViewerRemainsEligibleForRetryAfterRepeatedPruning() {
        World world = world();
        Person owner = new Person(world), restored = new Person(world), retry = new Person(world),
                former = new Person(world);
        owner.tracked.addAll(List.of(restored.player, retry.player));
        Set<UUID> previous = new HashSet<>(Set.of(restored.id, retry.id, former.id));
        NativeEntityRestoration.prune(owner.player, previous);
        assertEquals(Set.of(restored.player, retry.player),
                new HashSet<>(NativeEntityRestoration.recipients(owner.player, previous)));
        previous.remove(restored.id);

        for (int attempt = 0; attempt < 5; attempt++) {
            NativeEntityRestoration.prune(owner.player, previous);
            assertEquals(Set.of(retry.id), previous);
            assertEquals(List.of(retry.player), NativeEntityRestoration.recipients(owner.player, previous));
        }

        owner.tracked.remove(retry.player);
        NativeEntityRestoration.prune(owner.player, previous);
        assertTrue(previous.isEmpty());
    }
}
