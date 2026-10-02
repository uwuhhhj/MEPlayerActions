package com.simmc.meplayeractions.gameplay;

import org.bukkit.entity.Player;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * GSit 3.5.1 poses render a packet-only player, which Bukkit.hideEntity cannot remove.
 * Its per-tick viewer cache is replaced only while our owned disguise hides that avatar.
 * The guard reports prospective viewers as already present but emits no viewers during
 * iteration, so GSit neither spawns replicas nor sends their metadata/equipment updates.
 * Restoring the original empty cache lets GSit resume its normal tracking next tick.
 */
final class PoseReplicaVisibility implements AutoCloseable {
    private record Access(Field viewers, Field equipmentCache, Method removeViewer, Method nearbyViewers) {}
    private record Hidden(Object pose, Access access, Set<Player> original, Set<Player> guard, List<Player> existing) {}
    private final Map<Class<?>, Access> access = new HashMap<>();
    private final Map<UUID, Hidden> hidden = new HashMap<>();

    void suppress(UUID owner, Object pose) {
        Hidden previous = hidden.get(owner);
        if (previous != null && previous.pose == pose) {
            try { if (previous.access.viewers.get(pose) == previous.guard) return; }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("GSit pose viewer cache is unavailable", failure); }
        }
        restore(owner);
        if (pose == null) return;
        try {
            Access api = access.get(pose.getClass());
            if (api == null) {
                Field viewers = pose.getClass().getDeclaredField("nearbyPlayers");
                Field equipmentCache = pose.getClass().getDeclaredField("equipmentSlotCache");
                if (!Set.class.isAssignableFrom(viewers.getType()) || !Collection.class.isAssignableFrom(equipmentCache.getType()))
                    throw new IllegalStateException("Unexpected GSit viewer/equipment cache field types");
                Method removeViewer = pose.getClass().getDeclaredMethod("removeViewerPlayer", Player.class);
                Method nearbyViewers = pose.getClass().getDeclaredMethod("getNearbyPlayers");
                if (!Set.class.isAssignableFrom(nearbyViewers.getReturnType()) || removeViewer.getReturnType() != void.class)
                    throw new IllegalStateException("Unexpected GSit replica method signatures");
                viewers.setAccessible(true); equipmentCache.setAccessible(true); removeViewer.setAccessible(true); nearbyViewers.setAccessible(true);
                api = new Access(viewers, equipmentCache, removeViewer, nearbyViewers); access.put(pose.getClass(), api);
            }
            @SuppressWarnings("unchecked") Set<Player> original = (Set<Player>) api.viewers.get(pose);
            List<Player> existing = List.copyOf(original);
            Set<Player> guard = new AbstractSet<>() {
                @Override public boolean contains(Object prospectiveViewer) { return true; }
                @Override public Iterator<Player> iterator() { return Collections.emptyIterator(); }
                @Override public int size() { return 0; }
                @Override public boolean add(Player viewer) { return false; }
            };
            api.viewers.set(pose, guard);
            original.clear();
            hidden.put(owner, new Hidden(pose, api, original, guard, existing));
            for (Player viewer : existing) api.removeViewer.invoke(pose, viewer);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            restore(owner);
            throw new IllegalStateException("GSit packet-only pose visibility adapter failed", failure);
        }
    }

    void restore(UUID owner) {
        Hidden entry = hidden.get(owner);
        if (entry == null) return;
        try {
            // Another GSit lifecycle owns a replaced cache. Never overwrite that update.
            if (entry.access.viewers.get(entry.pose) == entry.guard) {
                entry.access.viewers.set(entry.pose, entry.original);
                // GSit updated this cache while our guard had no recipients. Its spawn
                // bundle omits equipment; invalidate so its own next tick resends the
                // real inventory to the recreated replica without changing any item.
                entry.access.equipmentCache.set(entry.pose, null);
            }
            hidden.remove(owner, entry);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("GSit pose visibility restoration failed", failure);
        }
    }

    /** GSit.remove also uses this cache to restore real-player equipment, before discarding the pose. */
    void beforeRemoval(UUID owner, Object pose) {
        beforeRemoval(owner, pose, List.of());
    }

    void beforeRemoval(UUID owner, Object pose, Collection<Player> fallbackNearby) {
        Hidden entry = hidden.get(owner);
        if (entry == null || entry.pose != pose) return;
        try {
            if (entry.access.viewers.get(pose) == entry.guard) {
                ReflectiveOperationException samplingFailure = null;
                try {
                    @SuppressWarnings("unchecked") Set<Player> nearby = (Set<Player>) entry.access.nearbyViewers.invoke(pose);
                    entry.original.addAll(nearby);
                } catch (ReflectiveOperationException failure) {
                    samplingFailure = failure;
                    entry.original.addAll(entry.existing);
                    entry.original.addAll(fallbackNearby);
                } finally {
                    // Even a failed optional sampling hook must not leave GSit's remove with an empty guard.
                    entry.access.viewers.set(pose, entry.original);
                    hidden.remove(owner, entry);
                }
                if (samplingFailure != null) throw samplingFailure;
            }
            hidden.remove(owner, entry);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("GSit pose equipment restoration failed", failure);
        }
    }

    @Override public void close() {
        for (UUID owner : List.copyOf(hidden.keySet())) restore(owner);
    }
}
