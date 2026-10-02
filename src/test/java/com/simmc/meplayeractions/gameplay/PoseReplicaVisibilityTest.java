package com.simmc.meplayeractions.gameplay;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PoseReplicaVisibilityTest {
    private static Player viewer() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(PoseReplicaVisibilityTest.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "getUniqueId" -> id;
                    default -> null;
                });
    }
    /** Simulates the actual GSit 3.5.1 viewer loop, without packet/NMS dependencies. */
    private static final class Pose {
        private Set<Player> nearbyPlayers = new HashSet<>();
        private final Set<Player> currentlyNearby = new HashSet<>();
        private List<String> equipmentSlotCache;
        private final List<String> actualInventory = List.of("ELYTRA");
        int spawned, removed, equipmentUpdates;
        boolean samplingFails;
        private Set<Player> getNearbyPlayers() { if (samplingFails) throw new IllegalStateException("Simulated GSit failure"); return Set.copyOf(currentlyNearby); }
        private void removeViewerPlayer(Player player) { removed++; }
        void tick() {
            for (Player player : getNearbyPlayers()) {
                if (nearbyPlayers.contains(player)) continue;
                nearbyPlayers.add(player); spawned++;
            }
            for (Player player : new ArrayList<>(nearbyPlayers)) {
                if (currentlyNearby.contains(player)) continue;
                nearbyPlayers.remove(player); removeViewerPlayer(player);
            }
            if (equipmentSlotCache != actualInventory) {
                equipmentSlotCache = actualInventory;
                equipmentUpdates += nearbyPlayers.size();
            }
        }
        void remove() {
            nearbyPlayers.forEach(this::removeViewerPlayer);
            equipmentUpdates += nearbyPlayers.size();
        }
    }
    @Test void hiddenReplicaCannotRespawnOnViewerJoinReentryOrEquipmentChange() {
        UUID owner = UUID.randomUUID(); Pose pose = new Pose(); Player first = viewer(), second = viewer();
        pose.currentlyNearby.add(first); pose.tick();
        PoseReplicaVisibility visibility = new PoseReplicaVisibility(); visibility.suppress(owner, pose);
        assertEquals(1, pose.removed);
        pose.currentlyNearby.add(second); pose.tick();
        pose.currentlyNearby.clear(); pose.tick();
        pose.currentlyNearby.addAll(List.of(first, second)); pose.tick();
        visibility.suppress(owner, pose);
        assertEquals(1, pose.spawned); assertEquals(1, pose.removed);
        assertEquals(1, pose.equipmentUpdates);
        visibility.restore(owner); visibility.restore(owner); pose.tick();
        assertEquals(3, pose.spawned, "Restored empty original cache lets GSit recreate both viewers normally");
    }
    @Test void naturalStopRestoresNearbyViewersBeforeGsitRestoresRealEquipment() {
        UUID owner = UUID.randomUUID(); Pose pose = new Pose(); Player viewer = viewer();
        pose.currentlyNearby.add(viewer); pose.tick();
        PoseReplicaVisibility visibility = new PoseReplicaVisibility(); visibility.suppress(owner, pose);
        visibility.beforeRemoval(owner, new Pose());
        assertTrue(pose.nearbyPlayers.isEmpty(), "A stale stop event cannot release the current pose");
        visibility.beforeRemoval(owner, pose); pose.remove();
        assertEquals(Set.of(viewer), pose.nearbyPlayers);
        assertEquals(2, pose.equipmentUpdates, "GSit's own remove restores equipment to its actual current viewers");
        visibility.close();
    }
    @Test void cancelledStopReestablishesTheGuardAndPoseReplacementRestoresOldCache() {
        UUID owner = UUID.randomUUID(); Pose first = new Pose(), next = new Pose(); Player viewer = viewer();
        first.currentlyNearby.add(viewer); first.tick(); Set<Player> original = first.nearbyPlayers;
        PoseReplicaVisibility visibility = new PoseReplicaVisibility(); visibility.suppress(owner, first);
        visibility.beforeRemoval(owner, first); // Another plugin cancels after MONITOR: next controller tick hides again.
        visibility.suppress(owner, first); first.tick();
        assertTrue(first.nearbyPlayers.isEmpty());
        visibility.suppress(owner, next);
        assertSame(original, first.nearbyPlayers);
        visibility.close(); next.tick();
    }
    @Test void externalCacheReplacementAndUnknownImplementationsRemainUntouched() {
        UUID owner = UUID.randomUUID(); Pose pose = new Pose();
        PoseReplicaVisibility visibility = new PoseReplicaVisibility(); visibility.suppress(owner, pose);
        Set<Player> external = new HashSet<>(); pose.nearbyPlayers = external;
        visibility.suppress(owner, pose);
        assertNotSame(external, pose.nearbyPlayers, "An external cache update is guarded again on the next tick");
        pose.nearbyPlayers = external;
        visibility.restore(owner); assertSame(external, pose.nearbyPlayers);
        assertThrows(IllegalStateException.class, () -> visibility.suppress(owner, new Object()));
        visibility.close();
    }
    @Test void failedNearbyHookStillRestoresSavedAndCurrentViewersBeforeRemoval() {
        UUID owner = UUID.randomUUID(); Pose pose = new Pose(); Player old = viewer(), arrived = viewer();
        pose.currentlyNearby.add(old); pose.tick();
        PoseReplicaVisibility visibility = new PoseReplicaVisibility(); visibility.suppress(owner, pose);
        pose.samplingFails = true;
        assertThrows(IllegalStateException.class, () -> visibility.beforeRemoval(owner, pose, List.of(arrived)));
        assertEquals(Set.of(old, arrived), pose.nearbyPlayers);
        pose.remove(); assertEquals(3, pose.equipmentUpdates);
        visibility.close();
    }
    @Test void rediscoveredReplicaReceivesUnchangedActualEquipmentAfterGuardRestoration() {
        UUID owner = UUID.randomUUID(); Pose pose = new Pose(); Player viewer = viewer();
        pose.currentlyNearby.add(viewer); pose.tick();
        Object inventory = pose.actualInventory;
        PoseReplicaVisibility visibility = new PoseReplicaVisibility(); visibility.suppress(owner, pose);
        pose.tick(); assertSame(inventory, pose.equipmentSlotCache, "GSit caches inventory even with no packet recipients");
        visibility.restore(owner); assertNull(pose.equipmentSlotCache);
        pose.tick(); assertEquals(2, pose.equipmentUpdates, "GSit itself sends unchanged equipment after normal replica respawn");
        assertSame(inventory, pose.actualInventory, "The adapter only invalidates rendering cache, never the actual inventory");
        visibility.suppress(owner, pose); Set<Player> external = new HashSet<>(); pose.nearbyPlayers = external;
        visibility.restore(owner); assertSame(inventory, pose.equipmentSlotCache, "External viewer ownership also preserves its equipment cache");
    }
}
