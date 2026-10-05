package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.expression.Molang;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Fixed Sparkle RoamingStruct dirty float semantics, independent of schema/UI declarations. */
class NativeRoamingStateTest {
    private static Molang.Context context() {var c=new Molang.Context();c.enableNativeYsm();c.enableNativeRoaming();return c;}
    private static Object eval(Molang.Context c,String script) {return Molang.compileNativeYsm(script).evaluateValue(c);}

    @Test void numericChangesCoalesceAtFloatPrecisionAndBaselineValuesDoNotEcho() {
        var c=context();c.seedRoamingValues(Map.of("variable.roaming.counter",2d));assertTrue(c.consumeRoamingChanges().isEmpty());
        assertEquals(0d,eval(c,"v.roaming.unset ?? 8"));
        eval(c,"v.roaming.counter=2;v.roaming.first_zero=0;");assertTrue(c.consumeRoamingChanges().isEmpty());
        eval(c,"v.roaming.counter=3;v.roaming.counter=4;v.roaming.ratio=.1;");
        assertEquals(Map.of("variable.roaming.counter",4d,"variable.roaming.ratio",(double).1f),c.consumeRoamingChanges());
        eval(c,"v.roaming.ratio=.100000002;");assertTrue(c.consumeRoamingChanges().isEmpty());
        eval(c,"v.roaming.flag='text';");assertEquals(Map.of("variable.roaming.flag",1d),c.consumeRoamingChanges());
    }

    @Test void copyingRoamingMakesAPlainStructWhileChildEntityWritesBelongToTheOriginalChannel() {
        var c=context();eval(c,"v.roaming.counter=1;");c.consumeRoamingChanges();
        eval(c,"v.copy=v.roaming;v.copy.counter=99;");assertTrue(c.consumeRoamingChanges().isEmpty());
        assertEquals(1d,eval(c,"v.roaming.counter"));assertEquals(99d,eval(c,"v.copy.counter"));
        var owner=new Molang.Context();owner.enableNativeYsm();c.query("ysm.projectile_owner",(Molang.ContextValue)()->owner);
        eval(c,"ysm.projectile_owner->(v.roaming.counter=3)");
        assertEquals(Map.of("variable.roaming.counter",3d),c.consumeRoamingChanges());assertTrue(owner.consumeRoamingChanges().isEmpty());
    }

    @Test void sourceCumulativeNameAndWireNameBudgetsPreserveLocalValuesButBoundDirtyExport() {
        var c=context();for(int i=0;i<64;i++)eval(c,"v.roaming.field_"+i+"=1;");
        eval(c,"v.roaming.overflow=1;");assertEquals(64,c.consumeRoamingChanges().size());
        eval(c,"v.roaming.field_0=2;");assertEquals(2d,eval(c,"v.roaming.field_0"));
        assertTrue(c.consumeRoamingChanges().isEmpty(),"Source stops collecting after more than 64 distinct changed names");
        var names=context();String tooLong="x".repeat(33);eval(names,"v.roaming."+tooLong+"=1;");
        assertEquals(1d,eval(names,"v.roaming."+tooLong));assertTrue(names.consumeRoamingChanges().isEmpty());
        eval(names,"v.roaming.a.b=8;");assertNull(eval(names,"v.roaming.a.b"));assertTrue(names.consumeRoamingChanges().isEmpty());
        names.clear();names.enableNativeRoaming();assertEquals(0d,eval(names,"v.roaming."+tooLong));
    }

    @Test void nativeFramesDoNotReplaceDynamicRoamingWithAnUnchangedPersistedProfile() throws Exception {
        var player=player();Map<String,Double> saved=Map.of("variable.roaming.counter",0d);
        sample(player,0,saved);assertEquals(Map.of("variable.roaming.counter",2d),player.consumeRoamingChanges());
        sample(player,1,saved);assertEquals(Map.of("variable.roaming.counter",3d),player.consumeRoamingChanges());
        sample(player,2,Map.of("variable.roaming.counter",9d));assertEquals(Map.of("variable.roaming.counter",10d),player.consumeRoamingChanges());
        assertEquals(10d,player.expressionVariables().get("variable.roaming.counter"));
        player.reset();assertTrue(player.consumeRoamingChanges().isEmpty());sample(player,20,saved);
        assertEquals(Map.of("variable.roaming.counter",2d),player.consumeRoamingChanges());
    }

    @Test void configurationReadsAndDraftScriptsCannotConsumeOrDirtyTheWorldChannel() throws Exception {
        var player=player();sample(player,0,Map.of());player.consumeRoamingChanges();
        assertEquals(50,player.readConfiguration("v.roaming.counter=50",Map.of()));
        assertTrue(player.consumeRoamingChanges().isEmpty());assertEquals(2d,player.expressionVariables().get("variable.roaming.counter"));
        var draft=player.authorConfiguration(List.of("v.roaming.counter=80;"),Map.of());
        assertEquals(80d,draft.get("variable.roaming.counter"));assertTrue(player.consumeRoamingChanges().isEmpty());
        assertEquals(2d,player.expressionVariables().get("variable.roaming.counter"));
    }
    private static void sample(AnimationPlayer player,double tick,Map<String,Double> values) {
        player.sample(tick,List.of(),0,0,Map.of(),Map.of(),values);
    }
    private static AnimationPlayer player() throws Exception {
        JsonObject raw=JsonParser.parseString("""
            {"meta":{"format_version":"5.0"},"ysm_format_version":65535,"ysm_controller_family":"player","textures":[],"animations":[],
             "ysm_events":{"player_init":["v.roaming.counter=1;"],"player_update":["v.roaming.counter+=1;"]},
             "elements":[{"uuid":"cube","from":[0,0,0],"to":[16,16,16],"faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
             "outliner":[{"uuid":"root","name":"root","origin":[0,0,0],"children":["cube"]}]}
            """).getAsJsonObject();
        BufferedImage image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xffffffff);
        ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);
        JsonObject texture=new JsonObject();texture.addProperty("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(png.toByteArray()));raw.getAsJsonArray("textures").add(texture);
        var player=new AnimationPlayer(BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8)));player.enableNativeYsm();return player;
    }
}
