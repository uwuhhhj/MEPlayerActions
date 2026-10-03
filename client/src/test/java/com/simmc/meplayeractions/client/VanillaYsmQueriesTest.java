package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.expression.Molang;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VanillaYsmQueriesTest {
    @Test void officialSlotNamesDoNotInventNumericOrPrefixedEquipmentMappings() {
        assertEquals(EquipmentSlot.MAINHAND,VanillaYsmQueries.slot("mainhand"));
        assertEquals(EquipmentSlot.OFFHAND,VanillaYsmQueries.slot("OFFHAND"));
        assertEquals(EquipmentSlot.HEAD,VanillaYsmQueries.slot("head"));
        assertEquals(EquipmentSlot.CHEST,VanillaYsmQueries.slot("chest"));
        assertEquals(EquipmentSlot.LEGS,VanillaYsmQueries.slot("legs"));
        assertEquals(EquipmentSlot.FEET,VanillaYsmQueries.slot("feet"));
        for(Object slot:List.of(0d,"2","slot.weapon.mainhand","main_hand"))
            assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.slot(slot));
    }
    @Test void relativeBlockCoordinatesKeepEntityFractionAndCrossNegativeBlockEdges() {
        assertEquals(1,VanillaYsmQueries.relativeCoordinate(.8,.3));
        assertEquals(-1,VanillaYsmQueries.relativeCoordinate(-.2,-.3));
        assertEquals(0,VanillaYsmQueries.relativeCoordinate(-.2,.3));
        assertEquals(32,VanillaYsmQueries.relativeCoordinate(.25,32));
        assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.relativeCoordinate(0,32.01));
        assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.relativeCoordinate(0,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.relativeCoordinate(Double.POSITIVE_INFINITY,0));
    }
    @Test void rotationToCameraReadsCameraAnglesWithoutTargetVectorOrYawWrap() {
        assertEquals(-17,VanillaYsmQueries.cameraRotation(0,17,-200));
        assertEquals(-20,VanillaYsmQueries.cameraRotation(1,17,-200));
        assertEquals(530,VanillaYsmQueries.cameraRotation(1,17,350));
        assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.cameraRotation(2,17,350));
    }
    @Test void normalizedDayStartsWithQuarterDayOffsetFromMinecraftSunrise() {
        assertEquals(.25,VanillaYsmQueries.timeOfDay(0));
        assertEquals(.5,VanillaYsmQueries.timeOfDay(6000));
        assertEquals(0,VanillaYsmQueries.timeOfDay(18000));
        assertEquals(.25,VanillaYsmQueries.timeOfDay(24000));
    }
    @Test void motionDirectionUsesBodyRelativeForwardAndStrafeAxes() {
        assertEquals(1,VanillaYsmQueries.inputDirection(0,1,0,false),1e-10);
        assertEquals(0,VanillaYsmQueries.inputDirection(0,1,0,true),1e-10);
        assertEquals(-1,VanillaYsmQueries.inputDirection(1,0,0,true),1e-10);
        assertEquals(1,VanillaYsmQueries.inputDirection(-1,0,90,false),1e-10);
        assertEquals(0,VanillaYsmQueries.inputDirection(1e-6,0,0,true));
    }
    @Test void capeFlapIncludesWalkBobCrouchAndOriginalClamps() {
        assertEquals(6d/108,VanillaYsmQueries.capeFlap(0,0,0,0,0,0,false),1e-10);
        assertEquals(31d/108,VanillaYsmQueries.capeFlap(0,0,0,0,0,0,true),1e-10);
        assertEquals(38d/108,VanillaYsmQueries.capeFlap(0,0,0,0,Math.PI/12,1,false),1e-10);
        assertEquals(1,VanillaYsmQueries.capeFlap(0,100,-100,0,0,0,true));
        assertEquals(0,VanillaYsmQueries.capeFlap(0,-100,100,0,0,0,false));
    }
    @Test void blinkWindowIsUuidOffsetAndSleepOverridesOpenEyePhase() {
        assertFalse(VanillaYsmQueries.closeEyes(85,0,false));
        assertTrue(VanillaYsmQueries.closeEyes(85.1,0,false));
        assertFalse(VanillaYsmQueries.closeEyes(90,0,false));
        assertTrue(VanillaYsmQueries.closeEyes(81,5,false));
        assertTrue(VanillaYsmQueries.closeEyes(0,0,true));
    }
    @Test void motionSamplerReusesSameFrameAndRejectsTeleportOrClockResetDeltas() {
        var sampler=new VanillaYsmQueries.MotionSampler();
        assertEquals(0,sampler.sample(100,0,64,0,.25).x());
        var next=sampler.sample(100.25,.1,64,0,.25);
        assertEquals(.1,next.x(),1e-10);assertEquals(.25,next.ticks());
        assertSame(next,sampler.sample(100.25,.1,64,0,.25));
        assertEquals(0,sampler.sample(101,100,64,0,.25).x());
        assertEquals(0,sampler.sample(1,100.1,64,0,.25).x());
    }
    @Test void shieldWindowRequiresActualBlockAndIsNotItemDisableCooldown() {
        var window=new VanillaYsmQueries.ShieldWindow();assertFalse(window.observed());assertFalse(window.inCooldown(100));
        window.hit(100);assertTrue(window.observed());assertTrue(window.inCooldown(100));assertTrue(window.inCooldown(105));
        assertFalse(window.inCooldown(99));assertFalse(window.inCooldown(106));
        window.hit(110);assertTrue(window.inCooldown(115));assertFalse(window.inCooldown(116));
    }
    private static String control(double vertical,String... flags) {
        Set<String> f=Set.of(flags);
        return VanillaYsmQueries.controlState(new VanillaYsmQueries.ControlSample(f.contains("death"),f.contains("riptide"),
                f.contains("sleep"),f.contains("swim"),f.contains("prone"),f.contains("ladder"),vertical,f.contains("fly"),
                f.contains("elytra"),f.contains("water"),!f.contains("air"),f.contains("hurt"),f.contains("sneak"),
                f.contains("run"),f.contains("walk"),f.contains("vehicle")));
    }
    @Test void nativeControlPriorityIsExclusiveAndIndependentOfClipPresence() {
        assertEquals("death",control(0,"death","riptide","sleep","hurt","walk"));
        assertEquals("riptide",control(0,"riptide","sleep","swim"));
        assertEquals("sleep",control(0,"sleep","swim"));assertEquals("swim",control(0,"swim","prone"));
        assertEquals("climb",control(0,"prone","walk"));assertEquals("climbing",control(0,"prone"));
        assertEquals("ladder_up",control(.1,"ladder","fly"));assertEquals("ladder_down",control(-.1,"ladder"));
        assertEquals("ladder_stillness",control(0,"ladder"));assertEquals("fly",control(0,"fly","elytra"));
        assertEquals("elytra_fly",control(0,"elytra","water","air"));assertEquals("swim_stand",control(0,"water","air","hurt"));
        assertEquals("attacked",control(0,"hurt","air"));assertEquals("jump",control(0,"air"));
        assertEquals("sneak",control(0,"sneak","walk","run"));assertEquals("sneaking",control(0,"sneak"));
        assertEquals("run",control(0,"run"));assertEquals("walk",control(0,"walk"));assertEquals("idle",control(0));
        assertEquals("",control(0,"vehicle","death","walk"));
    }
    @Test void nativeConditionalFunctionsMatchEquipmentAndNativeActionsRatherThanClipInventory() {
        Set<String> tags=Set.of("minecraft:swords");
        assertTrue(VanillaYsmQueries.itemCondition("$minecraft:iron_sword",false,"minecraft:iron_sword",tags,"sword","none",true));
        assertTrue(VanillaYsmQueries.itemCondition("#minecraft:swords",false,"minecraft:iron_sword",tags,"sword","none",true));
        assertTrue(VanillaYsmQueries.itemCondition(":sword",false,"minecraft:iron_sword",tags,"sword","none",true));
        assertFalse(VanillaYsmQueries.itemCondition(":sword",false,"minecraft:iron_sword",tags,"sword","none",false));
        assertFalse(VanillaYsmQueries.itemCondition(":",true,"minecraft:air",Set.of(),"","none",true));
        assertTrue(VanillaYsmQueries.itemCondition("empty",true,"minecraft:air",Set.of(),"","none",true));
        assertFalse(VanillaYsmQueries.handPredicate("ctrl.swing",false,false,false));
        assertTrue(VanillaYsmQueries.handPredicate("ctrl.swing",true,false,false));
        assertFalse(VanillaYsmQueries.handPredicate("ctrl.use",false,true,true));
        assertTrue(VanillaYsmQueries.handPredicate("ctrl.hold",false,false,true));
    }
    @Test void vanillaKindsMatchReferenceSpearAndNewLanceNames() {
        assertEquals("sword",VanillaYsmQueries.itemKind("minecraft:diamond_sword",Set.of("minecraft:swords")));
        assertEquals("shield",VanillaYsmQueries.itemKind("minecraft:shield",Set.of()));
        assertEquals("spear",VanillaYsmQueries.itemKind("minecraft:trident",Set.of()));
        assertEquals("lance",VanillaYsmQueries.itemKind("minecraft:iron_spear",Set.of("minecraft:spears")));
        assertEquals("throwable_potion",VanillaYsmQueries.itemKind("minecraft:lingering_potion",Set.of()));
        assertEquals("",VanillaYsmQueries.itemKind("minecraft:stone",Set.of()));
    }
    @Test void functionArityRejectsMissingTagSelectorsAndWrongAxesArguments() {
        assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.validateArguments("query.relative_block_has_all_tags",3));
        assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.validateArguments("ysm.keyboard",0));
        assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.validateArguments("ysm.mouse",2));
        assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.validateArguments("ctrl.hold",1));
        assertThrows(IllegalArgumentException.class,()->VanillaYsmQueries.validateArguments("ctrl.hold",4));
        assertDoesNotThrow(()->VanillaYsmQueries.validateArguments("ctrl.hold",2));
        assertDoesNotThrow(()->VanillaYsmQueries.validateArguments("ctrl.hold",3));
    }
    @Test void componentSpatialSampleReplacesOwnerPositionAndUsesComponentFrameMotion() {
        var context=new Molang.Context();
        context.query("query.position_0",2d);context.query("query.position_1",64d);context.query("query.position_2",3d);
        context.query("query.position_delta_0",0d);context.query("query.vertical_speed",0d);
        var component=new VanillaYsmQueries.MotionSampler();
        component.sample(100,102.4,70,-8,.25);
        var motion=component.sample(100.25,102.5,70.05,-8.2,.25);
        VanillaYsmQueries.populateSpatial(context,new Vec3d(102.5,70.05,-8.2),motion,new Vec3d(.4,.2,-.3),40);
        assertEquals(102.5,context.get("query.position_0"));assertEquals(70.05,context.get("query.position_1"));
        assertEquals(-8.2,context.get("query.position_2"));
        assertEquals(.1,context.get("query.position_delta_0"),1e-10);assertEquals(.05,context.get("query.position_delta_1"),1e-10);
        assertEquals(-.2,context.get("query.position_delta_2"),1e-10);
        assertEquals(10,context.get("query.ground_speed"),1e-10);assertEquals(4,context.get("query.vertical_speed"),1e-10);
        assertEquals(Math.hypot(.1,.2)*80,context.get("ysm.ground_speed2"),1e-10);
        assertEquals(.0125,context.get("query.delta_time"));assertEquals(40,context.get("query.yaw_speed"));
        assertEquals(102,VanillaYsmQueries.relativeCoordinate(context.get("query.position_0"),0));
    }
    @Test void nonLivingComponentDoesNotInheritOwnerHealthEquipmentOrPlayerControllerState() {
        var context=new Molang.Context();
        context.query("query.health",17d);context.query("query.equipment_count",4d);context.query("ysm.has_mainhand",1d);
        context.stringQuery("ysm.texture_name","owner_texture");context.stringQuery("ysm.left_shoulder_parrot_variant","blue");
        context.query("ctrl.walk",1d);context.query("ysm.in_shield_block_cooldown",1d);
        context.query("query.is_in_water",1d);
        VanillaYsmQueries.clearUnsupportedQueries(context,false);
        for(String key:List.of("query.health","query.equipment_count","ysm.has_mainhand","ysm.texture_name",
                "ysm.left_shoulder_parrot_variant","ctrl.walk","ysm.in_shield_block_cooldown",
                "ysm.native_living_available","ysm.native_player_available"))assertEquals(0,context.get(key),key);
        assertEquals(1,context.get("query.is_in_water")); // Spatial/environment values survive the type boundary.
    }
    @Test void nativeEquipmentFunctionsRequireLivingEntityWhileSpaceAndRideUseEveryEntity() {
        for(String function:List.of("query.max_durability","query.is_item_name_any","ysm.effect_level","ctrl.hold","ctrl.swing","ctrl.use","ctrl.armor"))
            assertTrue(VanillaYsmQueries.requiresLiving(function),function);
        for(String function:List.of("query.position","query.position_delta","query.rotation_to_camera",
                "query.biome_has_any_tag","ysm.relative_block_name","ctrl.ride","ysm.keyboard"))
            assertFalse(VanillaYsmQueries.requiresLiving(function),function);
    }
}
