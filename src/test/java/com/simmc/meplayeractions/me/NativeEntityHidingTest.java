package com.simmc.meplayeractions.me;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class NativeEntityHidingTest {
    @Test void onlyLegalVanillaTrackedViewersAreHidden() {
        World world=world();Person owner=new Person(world),viewer=new Person(world),untracked=new Person(world);
        owner.tracked.add(viewer.player);
        assertEquals(List.of(viewer.player),NativeEntityHiding.recipients(owner.player,id -> false));
        assertFalse(NativeEntityHiding.recipients(owner.player,id -> false).contains(untracked.player));
    }

    @Test void selfLocalLeaseVanishOtherWorldAndOfflineAreExcluded() {
        World world=world();Person owner=new Person(world),plain=new Person(world),local=new Person(world),vanished=new Person(world),
                departed=new Person(world),otherWorld=new Person(world());
        vanished.visible=false;departed.online=false;
        owner.tracked.addAll(List.of(owner.player,plain.player,local.player,vanished.player,departed.player,otherWorld.player));
        assertEquals(List.of(plain.player),NativeEntityHiding.recipients(owner.player,id -> id.equals(local.id)));
    }

    @Test void anOfflineOwnerNeverRemovesEntitiesFromViewers() {
        World world=world();Person owner=new Person(world),viewer=new Person(world);owner.tracked.add(viewer.player);owner.online=false;
        assertTrue(NativeEntityHiding.recipients(owner.player,id -> false).isEmpty());
    }

    @Test void delayedReconcileRechecksTrackingAndLocalLeasesAtExecution() {
        World world=world();Person owner=new Person(world),plain=new Person(world),newLocal=new Person(world),departed=new Person(world);
        owner.tracked.addAll(List.of(plain.player,newLocal.player,departed.player));
        Set<UUID> localLeases=new HashSet<>();Object session=new Object();AtomicReference<Object> current=new AtomicReference<>(session);
        List<Player> hidden=new ArrayList<>();
        Runnable callback=NativeEntityHiding.afterTick(session,current::get,() -> true,
                () -> hidden.addAll(NativeEntityHiding.recipients(owner.player,localLeases::contains)));
        localLeases.add(newLocal.id);owner.tracked.remove(departed.player);callback.run();
        assertEquals(List.of(plain.player),hidden);
    }

    @Test void replacementOrReleasedSessionCannotRunAnOldRemovalCallback() {
        // Equal session values still represent separate lifetimes and must not share callbacks.
        record Session(int id) { }
        var expected=new Session(1);var current=new AtomicReference<>(expected);List<String> calls=new ArrayList<>();
        Runnable callback=NativeEntityHiding.afterTick(expected,current::get,() -> true,() -> calls.add("remove"));
        current.set(new Session(1));callback.run();assertTrue(calls.isEmpty());
        current.set(null);callback.run();assertTrue(calls.isEmpty());
        current.set(expected);callback.run();assertEquals(List.of("remove"),calls);
    }

    @Test void restoredBaseVisibilityStopsAnAlreadyScheduledRemoval() {
        Object session=new Object();List<String> calls=new ArrayList<>();boolean[] hidden={true};
        Runnable callback=NativeEntityHiding.afterTick(session,() -> session,() -> hidden[0],() -> calls.add("remove"));
        hidden[0]=false;callback.run();assertTrue(calls.isEmpty());
        hidden[0]=true;callback.run();assertEquals(List.of("remove"),calls);
    }

    private static World world() {
        return (World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class[]{World.class},(proxy,method,args) ->
                method.getName().equals("equals")?proxy==args[0]:method.getName().equals("hashCode")?System.identityHashCode(proxy):null);
    }
    private static final class Person {
        final UUID id=UUID.randomUUID();final Player player;final Set<Player> tracked=new LinkedHashSet<>();
        final World world;boolean online=true,visible=true;
        Person(World world) {
            this.world=world;
            player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},(proxy,method,args) -> switch(method.getName()) {
                case "getUniqueId" -> id;case "getWorld" -> this.world;case "isOnline" -> online;case "canSee" -> visible;
                case "getTrackedBy" -> tracked;case "equals" -> proxy==args[0];case "hashCode" -> id.hashCode();default -> null;
            });
        }
    }
}
