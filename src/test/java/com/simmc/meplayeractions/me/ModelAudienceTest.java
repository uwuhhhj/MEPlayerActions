package com.simmc.meplayeractions.me;

import com.simmc.meplayeractions.action.DisguiseOptions;
import com.ticxo.modelengine.api.nms.entity.wrapper.TrackedEntity;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class ModelAudienceTest {
    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class[]{World.class}, (p, m, a) ->
                switch (m.getName()) { case "equals" -> p == a[0]; case "hashCode" -> System.identityHashCode(p); default -> null; });
    }
    private static class Person {
        final UUID id = UUID.randomUUID(); final Player player;
        Location location; boolean online = true, visible = true;
        final Set<Player> tracking = new HashSet<>();
        Person(World world, double x, double y, double z) {
            location = new Location(world, x, y, z);
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class}, (p, m, a) ->
                    switch (m.getName()) {
                        case "getUniqueId" -> id; case "getLocation" -> location.clone(); case "getWorld" -> location.getWorld();
                        case "getTrackedPlayers" -> Set.copyOf(tracking); case "isOnline" -> online; case "canSee" -> visible;
                        case "equals" -> p == a[0]; case "hashCode" -> id.hashCode(); case "toString" -> id.toString(); default -> null;
                    });
        }
    }
    /** ME forced pairings bypass predicates; forced hide removes pairing. */
    private static class Tracker implements TrackedEntity {
        final Person owner; final Set<UUID> paired = new HashSet<>(), hidden = new HashSet<>();
        Predicate<Player> predicate = DEFAULT_PREDICATE; int dirty;
        Tracker(Person owner) { this.owner = owner; }
        public Entity getEntity() { return owner.player; }
        public int getBaseRange() { return 64; } public void setBaseRange(int range) {} public int getEffectiveRange() { return 64; }
        public Set<UUID> getTrackedPlayer() { return getTrackedPlayer(p -> true); }
        public Set<UUID> getTrackedPlayer(Predicate<Player> extra) {
            var result = new HashSet<>(paired);
            for (Player p : owner.tracking) if (predicate.test(p) && extra.test(p) && !hidden.contains(p.getUniqueId())) result.add(p.getUniqueId());
            return result;
        }
        public void sendPairingData(Player p) {} public void broadcastSpawn() {} public void broadcastRemove() {}
        public void addForcedPairing(UUID id) { paired.add(id); hidden.remove(id); }
        public void removeForcedPairing(UUID id) { paired.remove(id); } public void clearForcedPairing() { paired.clear(); }
        public void addForcedHidden(UUID id) { hidden.add(id); paired.remove(id); }
        public void removeForcedHidden(UUID id) { hidden.remove(id); } public void clearForcedHidden() { hidden.clear(); }
        public void setPlayerPredicate(Predicate<Player> p) { predicate = p; } public Predicate<Player> getPlayerPredicate() { return predicate; }
        public void markViewersDirty() { dirty++; }
    }
    private static class Scene {
        final World world = world(); final Person owner = new Person(world, 0, 0, 0);
        final Tracker tracker = new Tracker(owner); final Map<UUID, Player> people = new HashMap<>();
        Person viewer(double x, double y, double z) {
            Person p = new Person(world, x, y, z); owner.tracking.add(p.player); people.put(p.id, p.player); return p;
        }
        ModelAudience audience(boolean self, double distance, int cap) {
            people.put(owner.id, owner.player);
            return new ModelAudience(new DisguiseOptions("ysm_02_jk", 1, true, 2, self, distance, cap, List.of()), people::get);
        }
    }
    @Test void strictThreeDimensionalBoundaryAndNearestCapApplyToActualTracker() {
        var s = new Scene(); var near = s.viewer(1, 0, 0); var second = s.viewer(0, 2, 0);
        s.viewer(3, 0, 0); s.viewer(0, 8, 0); s.viewer(8, 0, 0); s.viewer(9, 0, 0);
        var a = s.audience(false, 8, 2); assertTrue(a.update(s.owner.player, s.tracker));
        assertEquals(Set.of(near.id, second.id), s.tracker.getTrackedPlayer()); assertEquals(2, a.otherViewers(s.owner.id));
        assertFalse(a.allows(s.owner.id)); assertFalse(a.update(s.owner.player, s.tracker));
    }
    @Test void movementVacanciesAndNewEntrantsUseRawNativeTracking() {
        var s = new Scene(); var near = s.viewer(1, 0, 0); var waiting = s.viewer(3, 0, 0);
        var a = s.audience(false, 8, 1); a.update(s.owner.player, s.tracker);
        near.location.setX(8); a.update(s.owner.player, s.tracker); assertEquals(Set.of(waiting.id), s.tracker.getTrackedPlayer());
        var entering = s.viewer(0.5, 0, 0); a.update(s.owner.player, s.tracker); assertEquals(Set.of(entering.id), s.tracker.getTrackedPlayer());
        entering.online = false; a.update(s.owner.player, s.tracker); assertEquals(Set.of(waiting.id), s.tracker.getTrackedPlayer());
        assertTrue(s.tracker.dirty >= 4);
    }
    @Test void selfVisibilityUsesPairingAndDoesNotOccupyOtherViewerSlots() {
        var s = new Scene(); var other = s.viewer(1, 0, 0); var a = s.audience(true, 8, 1);
        a.update(s.owner.player, s.tracker); assertEquals(Set.of(s.owner.id, other.id), s.tracker.getTrackedPlayer());
        a.close(s.tracker, s.owner.id); assertFalse(s.tracker.paired.contains(s.owner.id));
        var privateModel = s.audience(true, 8, 0); privateModel.update(s.owner.player, s.tracker);
        assertEquals(Set.of(s.owner.id), s.tracker.getTrackedPlayer()); assertEquals(0, privateModel.otherViewers(s.owner.id));
    }
    @Test void vanishWorldOfflineAndOriginalPredicateCannotConsumeTheCap() {
        var s = new Scene(); var visible = s.viewer(4, 0, 0); var vanished = s.viewer(1, 0, 0); vanished.visible = false;
        var offline = s.viewer(1, 0, 0); offline.online = false;
        var hidden = s.viewer(0.5, 0, 0); s.tracker.addForcedHidden(hidden.id);
        var otherWorld = s.viewer(1, 0, 0); otherWorld.location.setWorld(world());
        var denied = s.viewer(1, 0, 0); Predicate<Player> original = p -> !p.getUniqueId().equals(denied.id);
        s.tracker.predicate = original; var a = s.audience(false, 8, 1); a.update(s.owner.player, s.tracker);
        assertEquals(Set.of(visible.id), s.tracker.getTrackedPlayer()); a.close(s.tracker, s.owner.id); assertSame(original, s.tracker.predicate);
        assertTrue(s.tracker.hidden.contains(hidden.id));
    }
    @Test void existingForcedPairingsCannotBypassLimitsAndAreRestoredAfterWrapperReplacement() {
        var s = new Scene(); var near = s.viewer(1, 0, 0); var far = s.viewer(9, 0, 0);
        s.tracker.addForcedPairing(far.id); s.tracker.addForcedPairing(s.owner.id);
        var original = s.tracker.predicate; var a = s.audience(false, 8, 1); a.update(s.owner.player, s.tracker);
        assertEquals(Set.of(near.id), s.tracker.getTrackedPlayer()); assertTrue(s.tracker.paired.isEmpty());
        var next = new Tracker(s.owner); next.predicate = s.tracker.predicate; next.paired.addAll(s.tracker.paired);
        a.update(s.owner.player, next); a.close(next, s.owner.id);
        assertSame(original, next.predicate); assertEquals(Set.of(s.owner.id, far.id), next.paired);
    }
    @Test void forcedViewerReentersWhenInRangeAndLeavesAtExactBoundary() {
        var s = new Scene(); var p = s.viewer(9, 0, 0); s.owner.tracking.remove(p.player); s.tracker.addForcedPairing(p.id);
        var a = s.audience(false, 8, 10); a.update(s.owner.player, s.tracker); assertTrue(s.tracker.getTrackedPlayer().isEmpty());
        p.location.setX(7.9); a.update(s.owner.player, s.tracker); assertEquals(Set.of(p.id), s.tracker.getTrackedPlayer());
        p.location.setX(8); a.update(s.owner.player, s.tracker); assertTrue(s.tracker.getTrackedPlayer().isEmpty());
        a.close(s.tracker, s.owner.id); assertTrue(s.tracker.paired.contains(p.id));
    }
    @Test void equalDistancesHaveDeterministicUuidOrderAndDuplicateEntriesDoNotConsumeSlots() {
        UUID owner = new UUID(0, 9), first = new UUID(0, 1), second = new UUID(0, 2);
        assertEquals(Set.of(first, second), AudienceSelector.select(owner, false, 8, 2,
                List.of(new AudienceSelector.Candidate(second, 1), new AudienceSelector.Candidate(first, 1),
                        new AudienceSelector.Candidate(first, 1), new AudienceSelector.Candidate(owner, 0),
                        new AudienceSelector.Candidate(new UUID(0, 3), Double.NaN))));
        assertEquals(Set.of(first), AudienceSelector.select(owner, false, 8, 1,
                List.of(new AudienceSelector.Candidate(second, 1), new AudienceSelector.Candidate(first, 1))));
    }
    @Test void cleanupKeepsAReplacementPredicateOwnedByAnotherPlugin() {
        var s = new Scene(); var a = s.audience(false, 8, 10); a.update(s.owner.player, s.tracker);
        Predicate<Player> external = p -> false; s.tracker.setPlayerPredicate(external); a.close(s.tracker, s.owner.id);
        assertSame(external, s.tracker.predicate);
    }
    @Test void readyViewerKeepsItsSlotWhileOnlyMeRenderingIsSuppressedThenRestored() {
        var s = new Scene(); var near = s.viewer(1, 0, 0); var waiting = s.viewer(2, 0, 0);
        var a = s.audience(false, 8, 1); a.update(s.owner.player, s.tracker);
        a.localRendering(near.id, true); a.update(s.owner.player, s.tracker);
        assertTrue(a.allows(near.id)); assertFalse(a.allows(waiting.id)); assertEquals(1, a.otherViewers(s.owner.id));
        assertTrue(s.tracker.getTrackedPlayer().isEmpty());
        a.update(s.owner.player, s.tracker); assertTrue(a.allows(near.id));
        a.localRendering(near.id, false); a.update(s.owner.player, s.tracker);
        assertEquals(Set.of(near.id), s.tracker.getTrackedPlayer());
    }
    @Test void readySelfRemovesForcedPairingAndFallbackRestoresIt() {
        var s = new Scene(); var other = s.viewer(1, 0, 0); var a = s.audience(true, 8, 1);
        a.update(s.owner.player, s.tracker); a.localRendering(s.owner.id, true); a.update(s.owner.player, s.tracker);
        assertTrue(a.allows(s.owner.id)); assertEquals(Set.of(other.id), s.tracker.getTrackedPlayer());
        a.localRendering(s.owner.id, false); a.update(s.owner.player, s.tracker);
        assertEquals(Set.of(s.owner.id, other.id), s.tracker.getTrackedPlayer());
        a.close(s.tracker, s.owner.id); assertFalse(s.tracker.paired.contains(s.owner.id));
    }
    @Test void forcedReadyObserverStillCountsAndIsRecoveredAcrossTrackerReplacement() {
        var s = new Scene(); var other = s.viewer(1, 0, 0); s.owner.tracking.remove(other.player); s.tracker.addForcedPairing(other.id);
        var a = s.audience(false, 8, 1); a.update(s.owner.player, s.tracker);
        a.localRendering(other.id, true); a.update(s.owner.player, s.tracker);
        assertTrue(a.allows(other.id)); assertTrue(s.tracker.paired.isEmpty());
        var next = new Tracker(s.owner); next.predicate = s.tracker.predicate; a.update(s.owner.player, next);
        assertTrue(a.allows(other.id)); a.localRendering(other.id, false); a.update(s.owner.player, next);
        assertEquals(Set.of(other.id), next.getTrackedPlayer());
    }
}
