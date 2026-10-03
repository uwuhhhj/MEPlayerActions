package com.simmc.meplayeractions.me;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeEntityRestorationTest {
    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class[]{World.class}, (p,m,a) ->
                m.getName().equals("equals") ? p == a[0] : m.getName().equals("hashCode") ? System.identityHashCode(p) : null);
    }
    private static final class Person {
        final UUID id = UUID.randomUUID(); final Player player; final Set<Player> tracked = new HashSet<>();
        World world; boolean online = true, visible = true;
        Person(World world) {
            this.world = world;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class}, (p,m,a) -> switch(m.getName()) {
                case "getUniqueId" -> id; case "getWorld" -> this.world; case "isOnline" -> online; case "canSee" -> visible;
                case "getTrackedBy" -> tracked;
                case "equals" -> p == a[0]; case "hashCode" -> id.hashCode(); default -> null;
            });
        }
    }
    @Test void onlyFormerLocalViewersStillTrackedReceivePairing() {
        World world = world(); Person owner = new Person(world), local = new Person(world), plain = new Person(world), far = new Person(world);
        owner.tracked.addAll(List.of(local.player,plain.player));
        assertEquals(List.of(local.player), NativeEntityRestoration.recipients(owner.player, Set.of(local.id,far.id)));
    }
    @Test void vanishWorldChangeOfflineAndMissingPlayersAreExcluded() {
        World world = world(); Person owner = new Person(world), hidden = new Person(world), departed = new Person(world), otherWorld = new Person(world());
        hidden.visible = false; departed.online = false; UUID missing = UUID.randomUUID();
        owner.tracked.addAll(List.of(hidden.player,departed.player,otherWorld.player,owner.player));
        Set<UUID> previous = new HashSet<>(Set.of(hidden.id,departed.id,otherWorld.id,owner.id,missing));
        assertTrue(NativeEntityRestoration.recipients(owner.player, previous).isEmpty());
    }
    @Test void disconnectedOwnerOrAbsentVanillaTrackingNeverRespawns() {
        World world = world(); Person owner = new Person(world), local = new Person(world); owner.online = false;
        owner.tracked.add(local.player);
        assertTrue(NativeEntityRestoration.recipients(owner.player,Set.of(local.id)).isEmpty());
        owner.online = true; owner.tracked.clear();
        assertTrue(NativeEntityRestoration.recipients(owner.player,Set.of(local.id)).isEmpty());
    }
    @Test void historicalPairingDoesNotForceAPlayerOutsideCurrentVanillaTracking() {
        World world = world(); Person owner = new Person(world), former = new Person(world), current = new Person(world);
        owner.tracked.add(current.player);
        // ModelEngine forced pairings may remember former viewers even after vanilla tracking ends.
        assertTrue(NativeEntityRestoration.recipients(owner.player,Set.of(former.id)).isEmpty());
    }
}
