package com.simmc.meplayeractions.client.model;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YsmModelTest {
    private static BbModel model(String id)throws Exception{return BbModel.parse(Files.readAllBytes(Path.of("../examples/models/"+id+".bbmodel")));}
    private static BbModel.Layer clip(String name){return new BbModel.Layer("posture",name,0,1,"LOOP",0,0);}
    private static double difference(List<BbModel.Vertex>a,List<BbModel.Vertex>b){
        double result=0;for(int i=0;i<Math.min(a.size(),b.size());i++)result+=Math.abs(a.get(i).x()-b.get(i).x())+Math.abs(a.get(i).y()-b.get(i).y())+Math.abs(a.get(i).z()-b.get(i).z());return result;
    }
    private static void accessories(AnimationPlayer player,double expected){
        assertEquals(expected,player.expressionVariables().getOrDefault("variable.roaming.a",0d));
        assertEquals(expected,player.expressionVariables().getOrDefault("variable.roaming.b",0d));
    }
    private static void sameGeometry(List<BbModel.Vertex> expected,List<BbModel.Vertex> actual){
        assertEquals(expected.size(),actual.size());assertEquals(0,difference(expected,actual),1e-6);
    }
    private static void accessoryGeometry(String id,List<BbModel.Vertex> initial,List<BbModel.Vertex> switched){
        // The first original has empty hat/flower bones; only the second ships their visible cubes.
        if(id.equals("ysm_01_jk"))sameGeometry(initial,switched);
        else assertTrue(initial.size()!=switched.size() || difference(initial,switched)>1,"Accessory switch changes visible geometry: "+id);
    }
    @Test void originalsKeepTheirDimensionsAndDedicatedClips()throws Exception{
        for(String id:List.of("ysm_01_jk","ysm_02_jk")){
            BbModel m=model(id);assertTrue(m.ysmPhysics());
            assertTrue(m.animations().containsAll(Set.of("boat","ride","ride_pig","elytra_fly","fall","minecart","mining","climb","climbing")));
            var vertices=m.sample(0,List.of(clip("idle")));
            double height=vertices.stream().mapToDouble(BbModel.Vertex::y).max().orElseThrow()-vertices.stream().mapToDouble(BbModel.Vertex::y).min().orElseThrow();
            assertTrue(height>2.4,"Model retains original dimensions rather than the former 1.75-block NPC scale: "+height);
        }
    }
    @Test void hungerAndHeldItemsAffectAuthoredGeometryAtRuntime()throws Exception{
        BbModel m=model("ysm_01_jk");AnimationPlayer a=new AnimationPlayer(m),b=new AnimationPlayer(m);
        var fed=a.sample(0,List.of(clip("idle")),0,0,Map.of("ysm.food_level",20d));
        var hungry=b.sample(0,List.of(clip("idle")),0,0,Map.of("ysm.food_level",5d));
        assertTrue(difference(fed,hungry)>1);
        var empty=new AnimationPlayer(m).sample(0,List.of(clip("fly")),0,0,Map.of("ysm.has_mainhand",0d));
        var held=new AnimationPlayer(m).sample(0,List.of(clip("fly")),0,0,Map.of("ysm.has_mainhand",1d));
        assertTrue(difference(empty,held)>1);
    }
    @Test void physicsRespondsToMovementAndDecaysWithoutSharingInstances()throws Exception{
        BbModel m=model("ysm_01_jk");AnimationPlayer moving=new AnimationPlayer(m),still=new AnimationPlayer(m);
        for(int i=0;i<=120;i++){
            moving.sample(i*.2,List.of(clip("idle")),0,0,Map.of("query.vertical_speed",1d));
            still.sample(i*.2,List.of(clip("idle")),0,0,Map.of("query.vertical_speed",0d));
        }
        double value=moving.expressionVariables().getOrDefault("variable.l1_p0",0d);
        assertTrue(Math.abs(value)>5,"Source spring responds to actual vertical speed");
        assertEquals(0,still.expressionVariables().getOrDefault("variable.l1_p0",0d),1e-8);
        for(int i=121;i<=800;i++)moving.sample(i*.2,List.of(clip("idle")),0,0,Map.of("query.vertical_speed",0d));
        assertTrue(Math.abs(moving.expressionVariables().getOrDefault("variable.l1_p0",0d))<Math.abs(value)*.1);
        moving.reset();assertTrue(moving.expressionVariables().isEmpty());
    }
    @Test void accessoryTimelineRunsOnceAcrossRepeatedSnapshots()throws Exception{
        AnimationPlayer player=new AnimationPlayer(model("ysm_02_jk"));
        var toggle=new BbModel.Layer("manual","extra0",0,1,"ONCE",0,0);
        player.sample(0,List.of(clip("idle"),toggle));
        assertEquals(0,player.expressionVariables().getOrDefault("variable.roaming.a",0d));
        player.sample(30,List.of(clip("idle"),toggle));
        double value=player.expressionVariables().getOrDefault("variable.roaming.a",0d);
        for(int i=0;i<10;i++)player.sample(30,List.of(clip("idle"),toggle));
        assertEquals(value,player.expressionVariables().getOrDefault("variable.roaming.a",0d));
        assertEquals(1,value);
        var nextToggle=new BbModel.Layer("manual","extra0",40,1,"ONCE",0,0);
        player.sample(40,List.of(clip("idle"),nextToggle));accessories(player,1);
        player.sample(70,List.of(clip("idle"),nextToggle),0,0,Map.of(),Map.of());accessories(player,0);
    }
    @Test void lateViewerUsesServerAccessoriesWithoutRequiringTheOldAction()throws Exception{
        for(String id:List.of("ysm_01_jk","ysm_02_jk")){
            BbModel m=model(id);
            var toggle=new BbModel.Layer("manual","extra0",0,1,"ONCE",0,0);
            AnimationPlayer preview=new AnimationPlayer(m);
            // Match a late viewer's first physics frame while deriving the reference state from the authored events.
            var toggled=preview.sample(63,List.of(clip("idle"),toggle));accessories(preview,1);
            AnimationPlayer joined=new AnimationPlayer(m);
            var restored=joined.sample(63,List.of(clip("idle")),0,0,Map.of(),Map.of("a",1d,"b",1d));
            accessories(joined,1);sameGeometry(toggled,restored);
            var initial=new AnimationPlayer(m).sample(63,List.of(clip("idle")),0,0,Map.of(),Map.of("a",0d,"b",0d));
            accessoryGeometry(id,initial,restored);
        }
    }
    @Test void historicalEventsCannotRetoggleAuthorityAndNewStateAppliesImmediately()throws Exception{
        for(String id:List.of("ysm_01_jk","ysm_02_jk")){
            BbModel m=model(id);AnimationPlayer player=new AnimationPlayer(m);
            var layers=List.of(clip("idle"),new BbModel.Layer("manual","extra0",0,1,"ONCE",0,0));
            Map<String,Double> on=Map.of("a",1d,"b",1d),off=Map.of("a",0d,"b",0d);
            player.sample(0,layers,0,0,Map.of(),on);accessories(player,1);
            // Both old events now execute against b=1; their local toggle must lose to the server's a=b=1.
            var switched=player.sample(30,layers,0,0,Map.of(),on);accessories(player,1);
            var expected=new AnimationPlayer(m).sample(30,layers,0,0,Map.of(),on);
            sameGeometry(expected,switched);
            for(int i=0;i<5;i++)sameGeometry(expected,player.sample(30,layers,0,0,Map.of(),on));
            var restored=player.sample(30,layers,0,0,Map.of(),off);accessories(player,0);
            sameGeometry(new AnimationPlayer(m).sample(30,layers,0,0,Map.of(),off),restored);
            accessoryGeometry(id,restored,switched);
        }
    }
    @Test void ownerAccessoryStateIsIsolatedAndDoesNotReplacePhysics()throws Exception{
        BbModel m=model("ysm_01_jk");UUID movingOwner=UUID.randomUUID(),stillOwner=UUID.randomUUID();
        Map<UUID,AnimationPlayer> owners=Map.of(movingOwner,new AnimationPlayer(m),stillOwner,new AnimationPlayer(m));
        AnimationPlayer moving=owners.get(movingOwner),still=owners.get(stillOwner);
        for(int i=0;i<=120;i++){
            moving.sample(i*.2,List.of(clip("idle")),0,0,Map.of("query.vertical_speed",1d),Map.of("a",1d,"b",1d));
            still.sample(i*.2,List.of(clip("idle")),0,0,Map.of("query.vertical_speed",0d),Map.of("a",0d,"b",0d));
        }
        accessories(moving,1);accessories(still,0);
        double spring=moving.expressionVariables().getOrDefault("variable.l1_p0",0d);assertTrue(Math.abs(spring)>5);
        moving.sample(24,List.of(clip("idle")),0,0,Map.of("query.vertical_speed",1d),Map.of("a",0d,"b",0d));
        accessories(moving,0);accessories(still,0);
        assertEquals(spring,moving.expressionVariables().getOrDefault("variable.l1_p0",0d),1e-8);
        assertEquals(0,still.expressionVariables().getOrDefault("variable.l1_p0",0d),1e-8);
        still.sample(24,List.of(clip("idle")),0,0,Map.of(),Map.of("a",1d,"b",1d));
        accessories(moving,0);accessories(still,1);
    }
    @Test void accessoriesAcceptOnlyTheirTwoBoundedVariables()throws Exception{
        AnimationPlayer player=new AnimationPlayer(model("ysm_01_jk"));
        for(Map<String,Double> invalid:List.of(Map.of("a",1d),Map.of("a",1d,"b",1d,"variable.l1_p0",100d),
                Map.of("a",Double.NaN,"b",0d),Map.of("a",0d,"b",Double.POSITIVE_INFINITY),
                Map.of("a",-.01,"b",0d),Map.of("a",0d,"b",1.01)))
            assertThrows(IllegalArgumentException.class,()->player.sample(0,List.of(clip("idle")),0,0,Map.of(),invalid));
        assertTrue(player.expressionVariables().isEmpty(),"Rejected accessory input must not mutate instance state");
    }
}
