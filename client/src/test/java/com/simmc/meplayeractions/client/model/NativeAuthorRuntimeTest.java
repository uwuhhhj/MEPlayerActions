package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.expression.Molang;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorRuntimeTest {
    @Test void capturedBoneStructFollowsTheLivePoseWhileItsScalarCaptureDoesNot() throws Exception {
        JsonObject raw=fixture();raw.add("ysm_events",JsonParser.parseString("{\"player_init\":[\"v.live=ysm.bone_pos('root');v.scalar=v.live.x;\"]}"));
        raw.add("animations",JsonParser.parseString("""
            [{"name":"idle","length":100,"loop":"LOOP","animators":{"root":{"keyframes":[
              {"channel":"position","time":0,"data_points":[{"x":"q.anim_time*16","y":0,"z":0}]}]}}}]
            """));
        var player=player(raw);var layers=List.of(new BbModel.Layer("movement","idle",0,1,"LOOP",0,0));
        player.sample(0,layers);player.sample(20,layers);
        assertEquals(16,player.readConfiguration("v.live.x",Map.of()),1e-6);
        assertEquals(0,player.readConfiguration("v.scalar",Map.of()));
        assertEquals(12,player.readConfiguration("ysm.bone_pos('missing').x ?? 12",Map.of()));
        player.authorConfiguration(List.of("v.live.x=99;"),Map.of());
        assertEquals(16,player.readConfiguration("v.live.x",Map.of()),1e-6);
    }
    @Test void configurationUsesRealFunctionsQueriesAndIsolatesReadSideEffects() throws Exception {
        JsonObject raw=fixture();raw.add("ysm_functions",JsonParser.parseString("{\"pick\":\"return args[-1]+q.health;\"}"));
        raw.add("ysm_events",JsonParser.parseString("{\"player_init\":[\"v.box.x=5;v.init+=1;\"],\"player_update\":[\"v.update+=1;\"]}"));
        var player=player(raw);List<String> effects=new ArrayList<>();
        player.configureFrame(c->{c.query("q.health",9d);c.functions((name,args)->{effects.add(name);return null;});});
        player.sample(0,List.of());
        var before=player.expressionVariables();
        assertEquals(99,player.readConfiguration("v.box.x=99",Map.of()));
        assertEquals(15,player.readConfiguration("v.box.x",Map.of("variable.box.x",15d)));
        assertEquals(0,player.readConfiguration("ysm.play_sound('id','read')",Map.of()));
        assertEquals(before,player.expressionVariables());assertTrue(effects.isEmpty());
        Map<String,Double> result=player.authorConfiguration(List.of("v.reply=fn.pick(5);ysm.play_sound('id','apply');"),Map.of("variable.mode",2d));
        assertEquals(14d,result.get("variable.reply"));assertEquals(2d,result.get("variable.mode"));
        assertEquals(List.of("ysm.play_sound"),effects);
        assertEquals(before,player.expressionVariables(),"Applying a UI draft does not corrupt the running slot's state");
        assertEquals(1d,result.get("variable.init"));assertEquals(1d,result.get("variable.update"));
    }
    @Test void connectedSyncOnlyRelaysAndServerEchoExecutesOnceWithoutForwardingAgain() throws Exception {
        JsonObject raw=fixture();raw.add("ysm_events",JsonParser.parseString("{\"sync\":[\"v.received+=1;v.value=args[0];ysm.sync(999);\"]}"));
        var player=player(raw);List<List<Double>> sent=new ArrayList<>();player.syncListener(sent::add);
        player.sample(0,List.of());
        player.authorConfiguration(List.of("ysm.sync(3);"),Map.of());
        assertEquals(List.of(List.of(3d)),sent);assertFalse(player.expressionVariables().containsKey("variable.received"));
        assertTrue(player.applySync(List.of(3d)));
        assertEquals(1d,player.expressionVariables().get("variable.received"));assertEquals(3d,player.expressionVariables().get("variable.value"));
        assertEquals(1,sent.size(),"An incoming @sync cannot send another network packet");
    }
    @Test void childOwnerCallsAuthorFunctionsWithItsEntityQueriesAndTheParentStorage() throws Exception {
        JsonObject raw=fixture();raw.add("ysm_functions",JsonParser.parseString("{\"pick\":\"v.total+=q.health;return v.total;\"}"));
        var player=player(raw);player.configureFrame(c->{
            c.query("q.health",99d);c.query("ysm.projectile_owner",(Molang.ContextValue)()->{
                var owner=new Molang.Context();owner.enableNativeYsm();owner.query("q.health",6d);return owner;
            });
        });
        assertEquals(9,player.readConfiguration("ysm.projectile_owner->fn.pick()",Map.of("variable.total",3d)));
        assertFalse(player.expressionVariables().containsKey("variable.total"));
        var values=player.authorConfiguration(List.of("ysm.projectile_owner->fn.pick();"),Map.of("variable.total",3d));
        assertEquals(9d,values.get("variable.total"));
    }
    @Test void syncBeforeTheFirstFrameIsBoundedOrderedAndDroppedWhenTheWorldIsReset() throws Exception {
        JsonObject raw=fixture();raw.add("ysm_events",JsonParser.parseString("{\"player_init\":[\"v.order=0;\"],\"sync\":[\"v.order=v.order*10+args[0];\"]}"));
        var player=player(raw);
        assertTrue(player.applySync(List.of(1d)));assertTrue(player.applySync(List.of(2d)));
        player.sample(0,List.of());assertEquals(12d,player.expressionVariables().get("variable.order"));
        var pending=player(raw);for(int i=0;i<32;i++)assertTrue(pending.applySync(List.of(1d)));
        assertFalse(pending.applySync(List.of(2d)));pending.reset();pending.sample(0,List.of());
        assertEquals(0d,pending.expressionVariables().get("variable.order"));
    }
    private static AnimationPlayer player(JsonObject raw) {
        var player=new AnimationPlayer(BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8)));
        player.enableNativeYsm();return player;
    }
    private static JsonObject fixture() throws Exception {
        JsonObject raw=JsonParser.parseString("""
            {"meta":{"format_version":"5.0"},"ysm_format_version":65535,"ysm_controller_family":"player","textures":[],"animations":[],
             "elements":[{"uuid":"cube","from":[0,0,0],"to":[16,16,16],"faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
             "outliner":[{"uuid":"root","name":"root","origin":[0,0,0],"children":["cube"]}]}
            """).getAsJsonObject();
        BufferedImage image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xffffffff);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        JsonObject texture=new JsonObject();texture.addProperty("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray()));
        raw.getAsJsonArray("textures").add(texture);return raw;
    }
}
