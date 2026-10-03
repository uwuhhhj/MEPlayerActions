package com.simmc.meplayeractions.me;

import com.ticxo.modelengine.api.nms.entity.wrapper.TrackedEntity;
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
        final UUID id = UUID.randomUUID(); final Player player;
        World world; boolean online = true, visible = true;
        Person(World world) {
            this.world = world;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class}, (p,m,a) -> switch(m.getName()) {
                case "getUniqueId" -> id; case "getWorld" -> this.world; case "isOnline" -> online; case "canSee" -> visible;
                case "equals" -> p == a[0]; case "hashCode" -> id.hashCode(); default -> null;
            });
        }
    }
    private static TrackedEntity tracked(UUID... viewers) {
        return (TrackedEntity) Proxy.newProxyInstance(TrackedEntity.class.getClassLoader(), new Class[]{TrackedEntity.class},
                (p,m,a) -> m.getName().equals("getTrackedPlayer") ? Set.of(viewers) : null);
    }
    @Test void onlyFormerLocalViewersStillTrackedReceivePairing() {
        World world = world(); Person owner = new Person(world), local = new Person(world), plain = new Person(world), far = new Person(world);
        Map<UUID,Player> players = Map.of(local.id, local.player, plain.id, plain.player, far.id, far.player);
        assertEquals(List.of(local.player), NativeEntityRestoration.recipients(owner.player, Set.of(local.id,far.id),
                tracked(local.id,plain.id), players::get));
    }
    @Test void vanishWorldChangeOfflineAndMissingPlayersAreExcluded() {
        World world = world(); Person owner = new Person(world), hidden = new Person(world), departed = new Person(world), otherWorld = new Person(world());
        hidden.visible = false; departed.online = false; UUID missing = UUID.randomUUID();
        Map<UUID,Player> players = Map.of(hidden.id,hidden.player,departed.id,departed.player,otherWorld.id,otherWorld.player,owner.id,owner.player);
        Set<UUID> previous = new HashSet<>(players.keySet()); previous.add(missing);
        assertTrue(NativeEntityRestoration.recipients(owner.player, previous,
                tracked(hidden.id,departed.id,otherWorld.id,owner.id,missing), players::get).isEmpty());
    }
    @Test void disconnectedOwnerOrUnavailableTrackerNeverRespawns() {
        World world = world(); Person owner = new Person(world), local = new Person(world); owner.online = false;
        assertTrue(NativeEntityRestoration.recipients(owner.player,Set.of(local.id),tracked(local.id),id -> local.player).isEmpty());
        owner.online = true;
        assertTrue(NativeEntityRestoration.recipients(owner.player,Set.of(local.id),null,id -> local.player).isEmpty());
    }
}
