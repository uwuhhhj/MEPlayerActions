package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.VanillaYsmAnimations.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VanillaYsmAnimationsTest {
    private static ItemState item(String id,String kind,String action,boolean charged,long revision,String... tags) {
        return new ItemState(id,Set.of(tags),kind,action,false,charged,revision);
    }
    private static VanillaState state(ItemState main,ItemState off,Hand using,int useTicks,Hand swinging,int swingTicks,boolean fishing) {
        return new VanillaState(false,0,false,false,false,main,off,using,useTicks,swinging,swingTicks,fishing,"",Set.of(),false,false);
    }
    private static Selection pick(VanillaState state,String slot,String... clips) {
        return VanillaYsmAnimations.select(state,new Catalog(List.of(clips))).slots().get(slot);
    }
    @Test void bothHandsCanHoldDifferentItemsIndependently() {
        var state=state(item("minecraft:diamond_sword","sword","none",false,1),
                item("minecraft:shield","shield","block",false,2),Hand.NONE,0,Hand.NONE,0,false);
        var selected=VanillaYsmAnimations.select(state,new Catalog(List.of("hold_mainhand:sword","hold_offhand:shield")));
        assertEquals("hold_mainhand:sword",selected.slots().get("player.hold_mainhand").animation());
        assertEquals("hold_offhand:shield",selected.slots().get("player.hold_offhand").animation());
    }
    @Test void conditionPrecedenceIsItemIdThenAuthoredTagThenClassThenUseAction() {
        var state=state(item("minecraft:iron_axe","axe","block",false,0,"minecraft:tools","minecraft:axes"),ItemState.EMPTY,Hand.NONE,0,Hand.NONE,0,false);
        String[] clips={"hold_mainhand:axe","hold_mainhand:block","hold_mainhand#minecraft:tools","hold_mainhand#minecraft:axes","hold_mainhand$minecraft:iron_axe"};
        assertEquals("hold_mainhand$minecraft:iron_axe",pick(state,"player.hold_mainhand",clips).animation());
        assertEquals("hold_mainhand#minecraft:tools",pick(state,"player.hold_mainhand",Arrays.copyOf(clips,4)).animation());
        assertEquals("hold_mainhand:axe",pick(state,"player.hold_mainhand",Arrays.copyOf(clips,2)).animation());
        assertEquals("hold_mainhand:block",pick(state,"player.hold_mainhand","hold_mainhand:block").animation());
    }
    @Test void chargedCrossbowSpecialWinsOverConfiguredIdForBothHands() {
        var crossbow=item("minecraft:crossbow","crossbow","crossbow",true,1);
        var state=state(crossbow,crossbow,Hand.NONE,0,Hand.NONE,0,false);
        for(String hand:List.of("mainhand","offhand")) {
            assertEquals("hold_"+hand+":charged_crossbow",pick(state,"player.hold_"+hand,
                    "hold_"+hand+"$minecraft:crossbow","hold_"+hand+":charged_crossbow").animation());
            assertEquals(Directive.STOP,pick(state,"player.hold_"+hand,"hold_"+hand+"$minecraft:crossbow").directive());
        }
    }
    @Test void mainhandFishingSpecialAndEmptyHandRulesUseOnlyExistingClips() {
        var fishing=state(item("minecraft:fishing_rod","fishing_rod","none",false,0),ItemState.EMPTY,Hand.NONE,0,Hand.NONE,0,true);
        assertEquals("hold_mainhand:fishing",pick(fishing,"player.hold_mainhand","hold_mainhand:fishing","hold_mainhand:fishing_rod").animation());
        assertEquals("hold_offhand:empty",pick(fishing,"player.hold_offhand","hold_offhand:empty").animation());
        assertEquals(Directive.STOP,pick(fishing,"player.hold_offhand","hold_offhand:fishing").directive());
    }
    @Test void useActionsCoverVanillaBowCrossbowTridentShieldFoodAndDrinksInEitherHand() {
        for(var entry:Map.of("bow","bow","crossbow","crossbow","spear","spear","shield","block","food","eat","potion","drink").entrySet()) {
            var held=item("minecraft:test_item",entry.getKey(),entry.getValue(),false,1);
            for(Hand hand:List.of(Hand.MAIN,Hand.OFF)) {
                var state=state(held,held,hand,7,Hand.NONE,0,false);
                String prefix=hand==Hand.MAIN?"use_mainhand":"use_offhand";
                var selected=pick(state,"player.use",prefix+":"+entry.getValue(),prefix);
                assertEquals(prefix+":"+entry.getValue(),selected.animation());assertEquals("LOOP",selected.loop());
                assertEquals(Set.of(hand==Hand.MAIN?"player.hold_mainhand":"player.hold_offhand"),
                        VanillaYsmAnimations.select(state,new Catalog(List.of(prefix))).pauseSlots());
            }
        }
    }
    @Test void newVanillaSpearChargeCanUseLanceClassBeforeTheNativeSpearUseAction() {
        var spear=item("minecraft:iron_spear","lance","spear",false,1);
        var state=state(spear,ItemState.EMPTY,Hand.MAIN,5,Hand.NONE,0,false);
        assertEquals("use_mainhand:lance",pick(state,"player.use","use_mainhand:lance","use_mainhand:spear").animation());
        assertEquals("use_mainhand:spear",pick(state,"player.use","use_mainhand:spear").animation());
    }
    @Test void missingUseConditionFallsBackToGenericButMissingGenericStops() {
        var state=state(item("minecraft:apple","food","eat",false,1),ItemState.EMPTY,Hand.MAIN,2,Hand.NONE,0,false);
        assertEquals("use_mainhand",pick(state,"player.use","use_mainhand","use_offhand:eat").animation());
        assertEquals(Directive.STOP,pick(state,"player.use","use_offhand").directive());
    }
    @Test void swingingOneHandPausesOnlyItsHoldAndUsesItsOwnConditionalClip() {
        var state=state(item("minecraft:diamond_sword","sword","none",false,1),
                item("minecraft:diamond_axe","axe","none",false,1),Hand.NONE,0,Hand.OFF,0,false);
        var selected=VanillaYsmAnimations.select(state,new Catalog(List.of("hold_mainhand:sword","hold_offhand:axe","swing_offhand:axe","swing_offhand")));
        assertEquals(Set.of("player.hold_offhand"),selected.pauseSlots());
        assertEquals(Directive.PLAY,selected.slots().get("player.hold_mainhand").directive());
        assertEquals("swing_offhand:axe",selected.slots().get("player.swing").animation());
        assertEquals("ONCE",selected.slots().get("player.swing").loop());
    }
    @Test void normalizedNativeSwingStartStillPlaysTheActiveHandAtTickZero() {
        var sword=item("minecraft:diamond_sword","sword","none",false,1);
        var shield=item("minecraft:shield","shield","block",false,2);
        var catalog=new Catalog(List.of("hold_mainhand:sword","hold_offhand:shield","swing:sword","swing_offhand:shield"));
        // LivingEntity.swingHand marks an active native swing with -1 before its first tick.
        // Runtime normalizes elapsed time, while the independent active-hand signal must survive.
        for(Hand hand:List.of(Hand.MAIN,Hand.OFF)) {
            var start=state(sword,shield,Hand.NONE,0,hand,Math.max(0,-1),false);
            var decision=VanillaYsmAnimations.select(start,catalog);
            var swing=decision.slots().get("player.swing");
            assertEquals(Directive.PLAY,swing.directive());assertEquals("ONCE",swing.loop());
            assertEquals(hand==Hand.MAIN?"swing:sword":"swing_offhand:shield",swing.animation());
            assertEquals(Set.of(hand==Hand.MAIN?"player.hold_mainhand":"player.hold_offhand"),decision.pauseSlots());
            assertEquals(Directive.PLAY,decision.slots().get(hand==Hand.MAIN?"player.hold_offhand":"player.hold_mainhand").directive());
            var firstTick=state(sword,shield,Hand.NONE,0,hand,0,false);
            assertEquals(swing,VanillaYsmAnimations.select(firstTick,catalog).slots().get("player.swing"));
        }
        // Zero elapsed time alone must never invent a swing after the active flag clears.
        assertEquals(Directive.CONTINUE,VanillaYsmAnimations.select(
                state(sword,shield,Hand.NONE,0,Hand.NONE,0,false),catalog).slots().get("player.swing").directive());
    }
    @Test void swingFallbackIsHandSpecificAndSleepStopsSwingAndUse() {
        var active=state(ItemState.EMPTY,ItemState.EMPTY,Hand.OFF,3,Hand.MAIN,0,false);
        var catalog=new Catalog(List.of("swing_hand","swing_offhand","use_mainhand","use_offhand"));
        assertEquals("swing_hand",VanillaYsmAnimations.select(active,catalog).slots().get("player.swing").animation());
        var sleep=new VanillaState(false,0,false,true,false,active.mainhand(),active.offhand(),Hand.OFF,3,Hand.MAIN,0,false,"",Set.of(),false,false);
        assertEquals(Directive.STOP,VanillaYsmAnimations.select(sleep,catalog).slots().get("player.swing").directive());
        assertEquals(Directive.STOP,VanillaYsmAnimations.select(sleep,catalog).slots().get("player.use").directive());
    }
    @Test void nativeSwingEndKeepsItsOnceClipWhileUseEndsImmediately() {
        var catalog=new Catalog(List.of("swing_hand","use_mainhand"));
        var selected=VanillaYsmAnimations.select(VanillaState.NONE,catalog);
        assertEquals(Directive.CONTINUE,selected.slots().get("player.swing").directive());
        assertEquals(Directive.STOP,selected.slots().get("player.use").directive());
    }
    @Test void vehicleIdPrecedesAuthoredEntityTagsAndDeadVehiclesNeverMatch() {
        var state=new VanillaState(false,0,false,false,false,ItemState.EMPTY,ItemState.EMPTY,Hand.NONE,0,Hand.NONE,0,false,
                "minecraft:horse",Set.of("minecraft:horses"),true,true);
        assertEquals("vehicle$minecraft:horse",new Catalog(List.of("vehicle#minecraft:horses","vehicle$minecraft:horse")).vehicle(state));
        assertEquals("vehicle#minecraft:horses",new Catalog(List.of("vehicle#minecraft:horses")).vehicle(state));
        var removed=new VanillaState(false,0,false,false,false,ItemState.EMPTY,ItemState.EMPTY,Hand.NONE,0,Hand.NONE,0,false,
                state.vehicleId(),state.vehicleTags(),false,true);
        assertEquals("",new Catalog(List.of("vehicle$minecraft:horse")).vehicle(removed));
    }
    @Test void itemRevisionChangesEventIdentityWhileEquivalentNativeSnapshotsRemainStable() {
        var first=state(item("minecraft:bow","bow","bow",false,12),ItemState.EMPTY,Hand.NONE,0,Hand.NONE,0,false);
        var second=state(item("minecraft:bow","bow","bow",false,13),ItemState.EMPTY,Hand.NONE,0,Hand.NONE,0,false);
        assertNotEquals(pick(first,"player.hold_mainhand","hold_mainhand:bow").eventKey(),
                pick(second,"player.hold_mainhand","hold_mainhand:bow").eventKey());
        assertEquals(pick(first,"player.hold_mainhand","hold_mainhand:bow"),pick(first,"player.hold_mainhand","hold_mainhand:bow"));
    }
    @Test void snapshotBoundsRejectMalformedRegistryIdsAndUnboundedMetadata() {
        assertThrows(IllegalArgumentException.class,()->item("../outside","bow","bow",false,0));
        assertThrows(IllegalArgumentException.class,()->item("minecraft:bow","BOW","bow",false,0));
        assertThrows(IllegalArgumentException.class,()->new ItemState("minecraft:bow",Set.of("../tag"),"bow","bow",false,false,0));
        assertThrows(IllegalArgumentException.class,()->new Catalog(Collections.nCopies(129,"idle")));
        assertThrows(IllegalArgumentException.class,()->new Catalog(List.of("use_mainhand\n")));
    }
}
