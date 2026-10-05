package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.expression.Molang;
import com.simmc.meplayeractions.client.VanillaYsmAnimations.*;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary examples from Sparkle b1230a4 MovementQuery, WeaponActionBridgeImpl and native predicates. */
class NativeVanillaQueryParityTest {
    @Test void nativeRemoteMovementNeverInventsWalkingFromResidualVelocityOrLimbAnimation() {
        var still=new VanillaYsmQueries.Motion(1,0,0,0);
        var velocity=new Vec3d(.2,-.08,.3);
        var tick=new Vec3d(.1,0,0);
        assertEquals(0,VanillaYsmQueries.groundSpeed(still,velocity,tick,1,true));
        assertEquals(Vec3d.ZERO,VanillaYsmQueries.positionDelta(still,velocity,tick,true));
        var moved=new VanillaYsmQueries.Motion(.5,.1,0,.2);
        assertEquals(Math.hypot(.1,.2)*40,VanillaYsmQueries.groundSpeed(moved,velocity,tick,1,true),1e-10);
        assertEquals(new Vec3d(.1,0,.2),VanillaYsmQueries.positionDelta(moved,velocity,tick,true));
    }
    @Test void localMovementHasTheUpstreamOrderedFallbacksAndNoGroundGravityBounce() {
        var still=new VanillaYsmQueries.Motion(.25,0,0,0);
        assertEquals(.7,VanillaYsmQueries.groundSpeed(still,new Vec3d(.2,0,0),new Vec3d(.3,0,0),.7,false));
        assertEquals(4,VanillaYsmQueries.groundSpeed(still,new Vec3d(.2,0,0),new Vec3d(.3,0,0),0,false));
        assertEquals(6,VanillaYsmQueries.groundSpeed(still,Vec3d.ZERO,new Vec3d(.3,0,0),0,false));
        assertEquals(0,VanillaYsmQueries.verticalSpeed(still,-.08,0));
        assertEquals(-4,VanillaYsmQueries.verticalSpeed(still,-.2,0));
        assertEquals(new Vec3d(.05,0,0),VanillaYsmQueries.positionDelta(still,new Vec3d(.2,0,0),Vec3d.ZERO,false));
    }
    @Test void arrowEnvironmentalPriorityMatchesTheProjectilePredicateWithoutGenericOnGroundGuessing() {
        assertEquals("water",VanillaYsmQueries.projectileAnimationState(true,true,true));
        assertEquals("fire",VanillaYsmQueries.projectileAnimationState(false,true,true));
        assertEquals("ground",VanillaYsmQueries.projectileAnimationState(false,false,true));
        assertEquals("air",VanillaYsmQueries.projectileAnimationState(false,false,false));
    }
    @Test void changingComponentTypeClearsEveryProjectileSpecificValueToNativeNull() {
        var context=new Molang.Context();context.enableNativeYsm();
        context.stringQuery("ysm.throwable_item","minecraft:egg");context.query("ysm.in_ground",1d);
        context.query("ysm.projectile_owner",(Molang.ContextValue)()->context);
        VanillaYsmQueries.clearProjectileQueries(context);
        for(String key:List.of("projectile_owner","throwable_item","hooked_in","is_biting","on_ground_time","in_ground","is_spectral_arrow","shoot_item_id"))
            assertEquals(9,Molang.compile("ysm."+key+" ?? 9").evaluate(context),key);
    }
    @Test void additionalNativeWeaponTagsAndBedrockTcosUseTheReferenceDeclarations() {
        assertEquals("spear",VanillaYsmQueries.itemKind("test:dual_tag",Set.of("minecraft:swords","ysm:tridents")));
        assertEquals("lance",VanillaYsmQueries.itemKind("test:long_weapon",Set.of("sparkle_morpher:lances")));
        assertEquals("mace",VanillaYsmQueries.itemKind("test:hammer",Set.of("ysm:mace")));
        assertEquals(0,VanillaYsmQueries.bedrockTcos(0,0));assertEquals(20,VanillaYsmQueries.bedrockTcos(0,.25));
        assertEquals(10,VanillaYsmQueries.bedrockTcos(0,.125));
    }
    private static VanillaYsmWeaponQueries.Sample weapon(String kind,boolean using,boolean attack,double speed,double vy,double fall,boolean riding) {
        return new VanillaYsmWeaponQueries.Sample(kind,using,"spear",attack,false,riding,false,false,speed,vy,fall,12.25,2.5,20);
    }
    @Test void tridentThrowingMeansAnActualNativeUseAndDoesNotInventAReleaseEvent() {
        var held=VanillaYsmWeaponQueries.values(weapon("spear",false,false,0,0,0,false));
        assertEquals(1d,held.get("weapon_type"));assertEquals(1d,held.get("trident_holding"));
        assertEquals(0d,held.get("trident_throwing"));assertEquals(0d,held.get("weapon_attack_ticks"));
        var using=VanillaYsmWeaponQueries.values(weapon("spear",true,true,0,0,0,false));
        assertEquals(1d,using.get("trident_throwing"));assertEquals(12.25,using.get("trident_use_ticks"));
        assertEquals(2.5,using.get("weapon_attack_ticks"));
    }
    @Test void lanceLungeAndJabAreExclusiveAtTheOriginalPoint35Boundary() {
        var slow=VanillaYsmWeaponQueries.values(weapon("lance",false,true,.349,0,0,false));
        assertEquals(1d,slow.get("lance_jabbing"));assertEquals(0d,slow.get("lance_lunging"));
        var fast=VanillaYsmWeaponQueries.values(weapon("lance",false,true,.35,0,0,false));
        assertEquals(0d,fast.get("lance_jabbing"));assertEquals(1d,fast.get("lance_lunging"));
        assertEquals(0d,fast.get("weapon_attacking"));
        var ride=VanillaYsmWeaponQueries.values(weapon("lance",true,false,.35,0,0,true));
        assertEquals(1d,ride.get("lance_riding_charge"));assertEquals(.6125,ride.get("lance_charge_progress"),1e-10);
    }
    @Test void maceEligibilityUsesObservedFallingAndKeepsUnobservedWindBurstFalse() {
        var tooSlow=VanillaYsmWeaponQueries.values(weapon("mace",false,true,0,-.08,1.5,false));
        assertEquals(0d,tooSlow.get("mace_can_smash"));
        var falling=VanillaYsmWeaponQueries.values(weapon("mace",false,true,0,-.081,1.5,false));
        assertEquals(1d,falling.get("mace_can_smash"));assertEquals(1d,falling.get("mace_smashing"));
        assertEquals(.5,falling.get("mace_smash_progress"));assertEquals(0d,falling.get("mace_wind_bursting"));
        assertEquals(0d,falling.get("weapon_use_ticks"));
        assertTrue(VanillaYsmWeaponQueries.values(weapon("",true,true,5,5,5,true)).values().stream().allMatch(v->v==0));
    }
    @Test void armorAndPassengerSlotsMatchOrderedIdsTagsAndDefaultWithIndependentLoops() {
        var helmet=new ItemState("minecraft:iron_helmet",Set.of("minecraft:head_armor"),"","none",false,false,1);
        var state=new VanillaState(false,0,false,false,false,ItemState.EMPTY,ItemState.EMPTY,Hand.NONE,0,Hand.NONE,0,false,
                "",Set.of(),false,false,Map.of("head",helmet),"minecraft:parrot",Set.of("minecraft:small"),true);
        var catalog=new Catalog(List.of("head#minecraft:head_armor","head:default","head$minecraft:iron_helmet",
                "passenger#minecraft:small","passenger$minecraft:parrot"));
        var slots=VanillaYsmAnimations.select(state,catalog).slots();
        assertEquals("head$minecraft:iron_helmet",slots.get("player.armor_head").animation());
        assertEquals("passenger$minecraft:parrot",slots.get("player.passenger").animation());
        assertEquals("LOOP",slots.get("player.armor_head").loop());assertEquals(Directive.STOP,slots.get("player.armor_chest").directive());
        var fallback=VanillaYsmAnimations.select(state,new Catalog(List.of("head:default"))).slots().get("player.armor_head");
        assertEquals("head:default",fallback.animation());
        var playback=new HandPlayback();assertEquals(10,playback.start("player.armor_head",fallback,state,10));
        assertEquals(10,playback.start("player.armor_head",fallback,state,12));
    }
    @Test void nativeSwingCanUseTheSparkleAttackEmptyFallbackWithoutAnInventedClip() {
        var state=new VanillaState(false,0,false,false,false,ItemState.EMPTY,ItemState.EMPTY,Hand.NONE,0,Hand.MAIN,0,false,"",Set.of(),false,false);
        assertEquals("attack_empty",VanillaYsmAnimations.select(state,new Catalog(List.of("attack_empty"))).slots().get("player.swing").animation());
        assertEquals(Directive.STOP,VanillaYsmAnimations.select(state,new Catalog(List.of())).slots().get("player.swing").directive());
    }
    @Test void nativeBlockLookupAndSlotParsingUseTheMatureUnavailableBoundaries() {
        assertTrue(VanillaYsmQueries.nativeRelativeRange(-5,5,0));
        assertFalse(VanillaYsmQueries.nativeRelativeRange(5.001,0,0));
        assertFalse(VanillaYsmQueries.nativeRelativeRange(0,Double.NaN,0));
        assertTrue(VanillaYsmQueries.knownSlot("MAINHAND"));
        assertFalse(VanillaYsmQueries.knownSlot("main_hand"));assertFalse(VanillaYsmQueries.knownSlot(0d));
        assertFalse(VanillaYsmQueries.requiresLiving("ysm.effect_level"));
    }
    @Test void nativeInventoryAcceptsFullConditionLibrariesWithinItsSeparateBoundedBudget() {
        var names=new ArrayList<String>();var loops=new LinkedHashMap<String,String>();
        for(int index=0;index<1024;index++){String name="authored_"+index;names.add(name);loops.put(name,"ONCE");}
        assertDoesNotThrow(()->new Catalog(names,65535,loops,Set.of()));
        assertThrows(IllegalArgumentException.class,()->new Catalog(names));
        names.add("beyond_budget");loops.put("beyond_budget","ONCE");
        assertThrows(IllegalArgumentException.class,()->new Catalog(names,65535,loops,Set.of()));
    }
}
