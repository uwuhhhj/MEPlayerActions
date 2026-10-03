package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.client.model.BbModel.Layer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityAnimationControllerTest {
    private static final Set<String> FEATURES=Set.of("movement","sprint","jump","sit","sleep","ride","crawl","sneak","swim","flight","elytra","swing","mining");
    private static LocalMotionPolicy policy(Set<String> enabled,String special,String interaction) {
        Map<String,Layer> clips=new LinkedHashMap<>();
        for(String state:List.of("idle","walk","run","jump","fall","sit","sleep","bed-sleep","boat","minecart","ride","ride-pig","ladder-move","ladder-idle",
                "crawl-idle","crawl-walk","crouch-idle","crouch-walk","swim-idle","swim-prone-idle","swim-walk","hover","fly","elytra","swing-mainhand","swing-offhand","mining"))
            clips.put(state,new Layer("posture","custom_"+state.replace('-','_'),0,1,"LOOP",2,2));
        return new LocalMotionPolicy(enabled,clips,17,6,.025,true,true,false,interaction,"",special,0,1.2,0,90);
    }
    private static EntityAnimationController.Sample sample(double x,double y,boolean ground,boolean prone,boolean water,String vehicle,boolean local,boolean swing,int swingTick,boolean offhand,boolean mining) {
        return new EntityAnimationController.Sample(x,y,0,ground,false,prone,water,false,false,false,false,vehicle,swing,swingTick,offhand,mining,local);
    }
    private static EntityAnimationController.Sample normal(double x,double y,boolean ground,boolean local) {
        return sample(x,y,ground,false,false,"",local,false,0,false,false);
    }
    @Test void ownAndUnmoddedRemoteMotionUseCurrentNativeSamplesDespiteOldServerPosture() {
        for(boolean own:List.of(true,false)) {
            var c=new EntityAnimationController();var p=policy(FEATURES,"","");
            var stale=List.of(new Layer("posture","custom_idle",10000,1,"LOOP",2,2));
            c.update(100,normal(0,64,true,own),p,stale);
            c.update(101,normal(.2,64,true,own),p,stale);
            assertEquals("walk",c.state());assertEquals("custom_walk",c.layers().getFirst().animation());
            assertEquals(101,c.layers().getFirst().startedAtTick());
            c.update(102,normal(.2,64,true,own),p,stale);assertEquals("idle",c.state());
        }
    }
    @Test void landCrawlNeverUsesSwimAndDisabledPostureNeverFallsThrough() {
        var c=new EntityAnimationController();var p=policy(FEATURES,"","");
        c.update(100,sample(0,64,true,true,false,"",true,false,0,false,false),p,List.of());assertEquals("crawl-idle",c.state());
        c.update(101,sample(.1,64,true,true,false,"",true,false,0,false,false),p,List.of());assertEquals("crawl-walk",c.state());
        c.update(102,sample(.2,64,false,true,true,"",true,false,0,false,false),p,List.of());assertEquals("swim-walk",c.state());
        var features=new HashSet<>(FEATURES);features.remove("crawl");
        c.update(103,sample(.3,64,true,true,false,"",true,false,0,false,false),policy(features,"",""),List.of());
        assertEquals("",c.state());assertTrue(c.layers().isEmpty());
    }
    @Test void gsitSleepTakesPriorityOverInvisibleVehicleAndBedIsSeparate() {
        var c=new EntityAnimationController();var s=sample(0,62.8,false,false,false,"ride",true,false,0,false,false);
        c.update(100,s,policy(FEATURES,"sleep",""),List.of());assertEquals("sleep",c.state());
        var bed=new EntityAnimationController.Sample(0,64,0,true,true,false,false,false,false,false,false,"",false,0,false,false,true);
        c.update(101,bed,policy(FEATURES,"",""),List.of());assertEquals("bed-sleep",c.state());
        c.update(102,s,policy(FEATURES,"sit",""),List.of());assertEquals("sit",c.state());
    }
    @Test void serverFixedCrawlIsHonoredEvenWhenNativeOwnEntityReportsStanding() {
        var p=policy(FEATURES,"","");
        var fixed=new LocalMotionPolicy(p.features(),p.clips(),17,6,.025,true,true,false,"","crawl","",0,0,0,0);
        var c=new EntityAnimationController();
        c.update(100,normal(0,64,true,true),fixed,List.of());assertEquals("crawl-idle",c.state());
        c.update(101,normal(.1,64,true,true),fixed,List.of());assertEquals("crawl-walk",c.state());
        c.update(102,normal(.1,64,true,true),p,List.of());assertEquals("idle",c.state());
    }
    @Test void boatMinecartUseDistinctServerMappings() {
        var c=new EntityAnimationController();var p=policy(FEATURES,"","");
        c.update(100,sample(0,64,false,false,false,"boat",false,false,0,false,false),p,List.of());assertEquals("custom_boat",c.layers().getFirst().animation());
        c.update(101,sample(0,64,false,false,false,"minecart",false,false,0,false,false),p,List.of());assertEquals("custom_minecart",c.layers().getFirst().animation());
    }
    @Test void nativeTakeoffStartsImmediatelyFinishesBoundedTailAndLedgesAreFalls() {
        for(boolean own:List.of(true,false)) {
            var c=new EntityAnimationController();var p=policy(FEATURES,"","");
            c.update(100,normal(0,64,true,own),p,List.of());
            c.update(101,normal(0,64.42,false,own),p,List.of());assertEquals("jump",c.state());assertEquals(101,c.layers().getFirst().startedAtTick());
            c.update(108,normal(0,64,true,own),p,List.of());assertEquals("jump",c.state());
            c.update(113,normal(0,64,true,own),p,List.of());assertEquals("jump",c.state());
            c.update(114,normal(0,64,true,own),p,List.of());assertEquals("idle",c.state());
            c.update(115,normal(0,63.8,false,own),p,List.of());assertEquals("fall",c.state());
        }
    }
    @Test void dismountTeleportAndVelocityDoNotReplaceGroundedTakeoffEvidence() {
        // The old A/B fixture teleported from a boat four blocks away and applied upward
        // velocity in the same server tick. This is a discontinuity, not a sampled jump.
        for(boolean own:List.of(true,false)) {
            var c=new EntityAnimationController();var p=policy(FEATURES,"","");
            c.update(100,sample(4.5,-60.4125,false,false,false,"boat",own,false,0,false,false),p,List.of());
            c.update(101,normal(.5,-59.58,false,own),p,List.of());assertEquals("fall",c.state());
            c.update(102,normal(.5,-59.25,false,own),p,List.of());assertEquals("fall",c.state());
            c.update(112,normal(.5,-60,true,own),p,List.of());assertEquals("idle",c.state());
            c.update(113,normal(.5,-60,true,own),p,List.of());
            c.update(114,normal(.5,-59.58,false,own),p,List.of());
            assertEquals("jump",c.state());assertEquals(114,c.layers().getFirst().startedAtTick());
            c.update(126,normal(.5,-60,true,own),p,List.of());assertEquals("jump",c.state());
            c.update(131,normal(.5,-60,true,own),p,List.of());assertEquals("idle",c.state());
        }
    }
    @Test void teleportsDoNotBecomeJumpsOrWalkCycles() {
        var c=new EntityAnimationController();var p=policy(FEATURES,"","");
        c.update(100,normal(0,64,true,true),p,List.of());
        c.update(101,normal(100,80,false,true),p,List.of());assertEquals("fall",c.state());
        c.update(102,normal(100,80,true,true),p,List.of());assertEquals("idle",c.state());
    }
    @Test void swingBeginsLocallyAndServerEchoCannotRestartIt() {
        var c=new EntityAnimationController();var p=policy(FEATURES,"","");
        c.update(100,normal(0,64,true,true),p,List.of());
        c.update(101,sample(0,64,true,false,false,"",true,true,0,true,false),p,List.of());
        Layer swing=c.layers().get(1);assertEquals("custom_swing_offhand",swing.animation());assertEquals(101,swing.startedAtTick());
        c.update(102,sample(0,64,true,false,false,"",true,true,1,true,false),p,List.of(new Layer("interaction","custom_swing_offhand",9000,1,"ONCE",2,2)));
        assertEquals(swing,c.layers().get(1));
    }
    @Test void ownMiningStopsImmediatelyDespiteServerEchoAndRawSwingDoesNotBecomeMining() {
        var c=new EntityAnimationController();var p=policy(FEATURES,"","");
        c.update(100,sample(0,64,true,false,false,"",true,false,0,false,true),p,List.of());assertEquals("custom_mining",c.layers().get(1).animation());
        var server=List.of(new Layer("interaction","custom_mining",100,1,"LOOP",2,2));
        c.update(101,normal(0,64,true,true),policy(FEATURES,"","mining"),server);assertEquals(1,c.layers().size());
        c.update(102,normal(0,64,true,false),policy(FEATURES,"","mining"),server);assertEquals("custom_mining",c.layers().get(1).animation());
        c.update(103,sample(0,64,true,false,false,"",false,true,0,false,false),policy(FEATURES,"","swing-mainhand"),server);
        assertEquals("custom_swing_mainhand",c.layers().get(1).animation());
    }
    @Test void movementInterruptsManualOnceAndRepeatedServerPacketsCannotReviveIt() {
        var c=new EntityAnimationController();var p=policy(FEATURES,"","");
        var manual=List.of(new Layer("manual","wave",100,1,"ONCE",2,2));
        c.update(100,normal(0,64,true,true),p,manual);assertEquals(2,c.layers().size());
        c.update(101,normal(.2,64,true,true),p,manual);assertEquals(1,c.layers().size());
        c.update(102,normal(.2,64,true,true),p,manual);assertEquals(1,c.layers().size());
        c.update(103,normal(.2,64,true,true),p,List.of(new Layer("manual","wave",103,1,"ONCE",2,2)));assertEquals(2,c.layers().size());
    }
    @Test void authoritativeLayerClockRebasesOnceAcrossRepeatedPacketsAndServerTickWrap() {
        var clock=new LocalLayerClock();var manual=new Layer("manual","wave",0x1_0000_0000L,1,"ONCE",2,2);
        var first=clock.accept(0x1_0000_0002L,100,List.of(manual)).getFirst();assertEquals(98,first.startedAtTick());
        assertEquals(first,clock.accept(0x1_0000_0003L,102,List.of(manual)).getFirst());
        clock.accept(0x1_0000_0004L,103,List.of());
        assertEquals(104,clock.accept(0x1_0000_0005L,104,List.of(new Layer("manual","wave",0x1_0000_0005L,1,"ONCE",2,2))).getFirst().startedAtTick());
    }
    @Test void validatesRequiredMotionMetadataBeforeTakingOver() {
        assertThrows(IllegalArgumentException.class,()->LocalMotionPolicy.read(null));
        JsonObject json=new JsonObject();json.add("features",new JsonArray());json.add("clips",new JsonArray());
        json.addProperty("specialPose","sleep");json.addProperty("interaction","");json.addProperty("forcedPose","");json.addProperty("jumpMinTicks",17);json.addProperty("landingGraceTicks",6);
        json.addProperty("movementThreshold",.025);json.addProperty("interruptMove",true);json.addProperty("interruptPosture",true);json.addProperty("flying",false);
        json.addProperty("anchorX",0);json.addProperty("anchorY",1.2);json.addProperty("anchorZ",0);json.addProperty("anchorYaw",90);
        assertEquals("sleep",LocalMotionPolicy.read(json).specialPose());
        json.getAsJsonArray("features").add("arbitrary_feature");assertThrows(IllegalArgumentException.class,()->LocalMotionPolicy.read(json));
    }
    @Test void completeServerMappingsAreAcceptedAndDriveLadderAndPigPoses() {
        var source=policy(FEATURES,"","");
        JsonObject json=new JsonObject();JsonArray features=new JsonArray(),clips=new JsonArray();
        source.features().forEach(features::add);json.add("features",features);json.add("clips",clips);
        source.clips().forEach((state,clip)->{
            JsonObject item=new JsonObject();item.addProperty("state",state);item.addProperty("animation",clip.animation());
            item.addProperty("speed",clip.speed());item.addProperty("loop",clip.loop());
            item.addProperty("inTicks",clip.inTicks());item.addProperty("outTicks",clip.outTicks());clips.add(item);
        });
        json.addProperty("specialPose","");json.addProperty("interaction","");json.addProperty("forcedPose","");
        json.addProperty("jumpMinTicks",17);json.addProperty("landingGraceTicks",6);json.addProperty("movementThreshold",.025);
        json.addProperty("interruptMove",true);json.addProperty("interruptPosture",true);json.addProperty("flying",false);
        for(String name:List.of("anchorX","anchorY","anchorZ","anchorYaw"))json.addProperty(name,0);
        LocalMotionPolicy parsed=LocalMotionPolicy.read(json);
        assertEquals(27,parsed.clips().size());
        var controller=new EntityAnimationController();
        controller.update(100,new EntityAnimationController.Sample(0,64,0,false,false,false,false,false,false,false,false,
                "",false,0,false,false,true,true),parsed,List.of());
        assertEquals("ladder-idle",controller.state());assertEquals("custom_ladder_idle",controller.layers().getFirst().animation());
        controller.update(101,new EntityAnimationController.Sample(0,64.1,0,false,false,false,false,false,false,false,false,
                "",false,0,false,false,true,true),parsed,List.of());
        assertEquals("ladder-move",controller.state());assertEquals("custom_ladder_move",controller.layers().getFirst().animation());
        controller.update(102,sample(0,64.1,false,false,false,"ride-pig",true,false,0,false,false),parsed,List.of());
        assertEquals("ride-pig",controller.state());assertEquals("custom_ride_pig",controller.layers().getFirst().animation());
    }
}
