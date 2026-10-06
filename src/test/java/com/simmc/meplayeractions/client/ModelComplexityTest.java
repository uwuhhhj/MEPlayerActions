package com.simmc.meplayeractions.client;

import com.google.gson.JsonParser;
import com.simmc.meplayeractions.config.ModelComplexityLimits;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ModelComplexityTest {
    private static ModelComplexityLimits limits(int bones,int animations,int frames,int expressions,int chars,int nodes) {
        return new ModelComplexityLimits(bones,animations,frames,expressions,chars,nodes,256,8388608,16777216);
    }
    @Test void nativeTimestampChannelsAndBlockbenchKeyframesShareWholeAssetBudget() throws Exception {
        var budget=new ModelComplexity(limits(4096,10,3,32768,2097152,200000));
        budget.document(JsonParser.parseString("{\"animations\":{\"walk\":{\"bones\":{\"Head\":{\"rotation\":{\"0.0\":[0,0,0],\"0.5\":[1,0,0]}}}}}}"));
        IOException failure=assertThrows(IOException.class,()->budget.document(JsonParser.parseString("{\"animations\":[{\"animators\":{\"bone\":{\"keyframes\":[{},{}]}}}]}")));
        assertEquals("model_complexity:keyframes",failure.getMessage());
    }
    @Test void manySmallDocumentsCannotResetJsonAndExpressionBudget() throws Exception {
        var json=new ModelComplexity(limits(4096,1024,65536,32768,2097152,10));
        json.document(JsonParser.parseString("[1,2,3,4,5]"));
        assertEquals("model_complexity:json_nodes",assertThrows(IOException.class,()->json.document(JsonParser.parseString("[1,2,3,4,5]"))).getMessage());
        var expressions=new ModelComplexity(limits(4096,1024,65536,10,9,200000));
        expressions.document(JsonParser.parseString("{\"script\":\"v.a=1;\"}"));
        assertEquals("model_complexity:expression_chars",assertThrows(IOException.class,()->expressions.script("v.b=1;")).getMessage());
    }
    @Test void independentGroupsAndNativeBonesCountWithoutCountingAnimationTrackNames() throws Exception {
        var groups=new ModelComplexity(limits(1,1024,65536,32768,2097152,200000));
        groups.document(JsonParser.parseString("{\"groups\":[{\"uuid\":\"bone\"}],\"outliner\":[\"bone\"]}"));
        assertEquals("model_complexity:bones",assertThrows(IOException.class,()->groups.document(JsonParser.parseString("{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"native\"}]}]}"))).getMessage());
        new ModelComplexity(limits(1,1024,65536,32768,2097152,200000)).document(JsonParser.parseString("{\"animations\":{\"walk\":{\"bones\":{\"one\":{},\"two\":{}}}}}"));
    }
    @Test void modelDisplayNamesAreNotMistakenForProgramsAndLimitsCannotDisableHardCeilings() throws Exception {
        new ModelComplexity(limits(1,1024,65536,1,1,200000)).document(JsonParser.parseString("{\"name\":\"long author display name\",\"animation\":\"walk\",\"rotation\":[\"0.0\",1,2]}"));
        assertThrows(IllegalArgumentException.class,()->new ModelComplexityLimits(4097,1024,65536,32768,2097152,200000,256,8388608,16777216));
        assertThrows(IllegalArgumentException.class,()->limits(1,1,1,1,1,0));
    }
}
