package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.VanillaYsmAnimations.*;
import com.simmc.meplayeractions.client.model.BbModel.Layer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real YSM metadata selects Sparkle's ControllerActionResolver rules, independently of legacy BB policy. */
class NativeVanillaControllerParityTest {
    private static final Set<String> FEATURES=Set.of("movement","sprint","jump","sit","sleep","ride","crawl","sneak","swim","flight","elytra","swing","mining");
    private static final List<String> NAMES=List.of("idle","walk","run","jump","fly","elytra_fly","ladder_up","ladder_down","ladder_stillness","swim","sleep");
    private static Catalog catalog() {
        Map<String,String> loops=new LinkedHashMap<>();NAMES.forEach(name->loops.put(name,name.equals("jump")?"HOLD":"LOOP"));
        return new Catalog(NAMES,65535,loops,new HashSet<>(NAMES));
    }
    private static LocalMotionPolicy policy() {
        Map<String,Layer> clips=new LinkedHashMap<>();
        Map.of("idle","idle","walk","walk","run","run","jump","jump","fly","fly","elytra","elytra_fly",
                "ladder-move","ladder_up","ladder-idle","ladder_stillness","ladder-down","ladder_down").forEach((state,clip)->
                clips.put(state,new Layer("posture",clip,0,1,"LOOP",2,2)));
        return new LocalMotionPolicy(FEATURES,clips,17,8,.02,true,true,false,"","","",0,0,0,0);
    }
    private static EntityAnimationController.Sample sample(double x,double y,boolean grounded,boolean climbing,boolean fly,boolean glide,boolean sprint) {
        return new EntityAnimationController.Sample(x,y,0,grounded,false,false,false,fly,glide,false,sprint,"",false,0,false,false,
                false,climbing,VanillaState.NONE);
    }
    @Test void sprintingStillRequiresMeasuredMovementAndSpeedUsesSecondsNotDistancePerFrame() {
        var c=new EntityAnimationController();c.update(100,sample(0,64,true,false,false,false,true),policy(),List.of(),catalog());
        assertEquals("idle",c.state());
        c.update(101,sample(.001,64,true,false,false,false,true),policy(),List.of(),catalog());assertEquals("idle",c.state());
        c.update(102,sample(.004,64,true,false,false,false,true),policy(),List.of(),catalog());assertEquals("run",c.state());
        c.update(103,sample(.004,64,true,false,false,false,true),policy(),List.of(),catalog());assertEquals("idle",c.state());
    }
    @Test void nativeJumpHasNoManufacturedFallOrPostLandingTailAndRetainsAuthorsLoopType() {
        var c=new EntityAnimationController();c.update(100,sample(0,64,true,false,false,false,false),policy(),List.of(),catalog());
        c.update(101,sample(0,63.8,false,false,false,false,false),policy(),List.of(),catalog());
        assertEquals("jump",c.state());assertEquals("jump",c.layers().getFirst().animation());assertEquals("HOLD",c.layers().getFirst().loop());
        c.update(102,sample(0,64,true,false,false,false,false),policy(),List.of(),catalog());assertEquals("idle",c.state());
    }
    @Test void nativeLadderThresholdHasAStableCenterAndWinsOverFlying() {
        var c=new EntityAnimationController();c.update(100,sample(0,64,false,true,true,false,false),policy(),List.of(),catalog());
        assertEquals("ladder-idle",c.state());
        c.update(101,sample(0,64.0004,false,true,true,false,false),policy(),List.of(),catalog());assertEquals("ladder-idle",c.state());
        c.update(102,sample(0,64.002,false,true,true,false,false),policy(),List.of(),catalog());assertEquals("ladder-move",c.state());
        c.update(103,sample(0,64.0004,false,true,true,false,false),policy(),List.of(),catalog());assertEquals("ladder-down",c.state());
    }
    @Test void nativeGlidingWinsOverCreativeFlyingAsInTheCanonicalQueryState() {
        var c=new EntityAnimationController();c.update(100,sample(0,64,false,false,true,true,false),policy(),List.of(),catalog());
        assertEquals("elytra",c.state());assertEquals("elytra_fly",c.layers().getFirst().animation());
    }
    @Test void localFirstFrameUsesNativeMovementFallbackWithoutASecondPositionBuffer() {
        var c=new EntityAnimationController();
        var still=sample(0,64,true,false,false,false,false);
        var initial=new EntityAnimationController.Sample(still.x(),still.y(),still.z(),true,false,false,false,false,false,false,false,
                "",false,0,false,false,true,false,VanillaState.NONE,new VanillaYsmQueries.MovementFallback(.12,0));
        c.update(100,initial,policy(),List.of(),catalog());assertEquals("walk",c.state());
        c.update(101,still,policy(),List.of(),catalog());assertEquals("idle",c.state());
    }
    @Test void livingVehicleSuppressesBodyDeathAsInSourceRidePriority() {
        var c=new EntityAnimationController();
        var inventory=new ArrayList<>(NAMES);inventory.add("vehicle$minecraft:horse");inventory.add("death");
        var loops=new LinkedHashMap<String,String>();inventory.forEach(name->loops.put(name,"LOOP"));
        var nativeCatalog=new Catalog(inventory,65535,loops,new HashSet<>(inventory));
        var riding=new VanillaState(true,0,false,false,false,ItemState.EMPTY,ItemState.EMPTY,Hand.NONE,0,Hand.NONE,0,false,
                "minecraft:horse",Set.of(),true,true);
        var sample=new EntityAnimationController.Sample(0,64,0,true,false,false,false,false,false,false,false,"ride",false,0,
                false,false,false,false,riding);
        c.update(100,sample,policy(),List.of(),nativeCatalog);
        assertEquals("ride",c.state());assertEquals("vehicle$minecraft:horse",c.layers().getFirst().animation());
        assertEquals("player.vehicle",c.layers().getFirst().layer());
    }
}
