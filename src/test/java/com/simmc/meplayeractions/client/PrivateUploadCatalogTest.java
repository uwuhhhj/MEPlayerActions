package com.simmc.meplayeractions.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivateUploadCatalogTest {
    private static final Gson WIRE=new Gson();

    @Test void maximumLengthUnicodeAndEscapedLocalNamesRespectTheActualWireBudgetInEveryFragment() {
        for(boolean escaped:List.of(false,true)) {
            List<PrivateModelStore.UploadedModel> models=models(64,escaped);
            List<JsonObject> packets=PrivateUploadCatalog.packets(models,true,1024,9_007_199_254_740_991L);
            assertTrue(packets.size()>1);var received=new ArrayList<String>();
            for(int index=0;index<packets.size();index++) {
                JsonObject packet=packets.get(index);byte[] bytes=WIRE.toJson(packet).getBytes(StandardCharsets.UTF_8);
                assertTrue(bytes.length<=1024,"Actual serializer exceeded the negotiated budget: "+bytes.length);
                assertEquals(Set.of("protocol","type","revision","index","count","available","models"),packet.keySet());
                assertEquals(1,packet.get("protocol").getAsInt());assertEquals("upload_catalog",packet.get("type").getAsString());
                assertEquals(index,packet.get("index").getAsInt());assertEquals(packets.size(),packet.get("count").getAsInt());
                assertEquals(9_007_199_254_740_991L,packet.get("revision").getAsLong());assertTrue(packet.get("available").getAsBoolean());
                for(var value:packet.getAsJsonArray("models")) {
                    JsonObject entry=value.getAsJsonObject();assertEquals(Set.of("modelId","hash","kind","bytes"),entry.keySet());
                    received.add(entry.get("modelId").getAsString());
                }
                String text=new String(bytes,StandardCharsets.UTF_8);
                for(String secret:List.of("owner","http://","https://","token","download","generation"))assertFalse(text.contains(secret),secret);
            }
            assertEquals(models.stream().map(PrivateModelStore.UploadedModel::sourceId).toList(),received);
            assertEquals(64,new HashSet<>(received).size());
        }
    }
    @Test void unavailableDirectoriesAreExplicitlyEmptyAndMalformedBudgetsOrDuplicatesAreRejected() {
        JsonObject empty=PrivateUploadCatalog.packets(models(64,false),false,1024,1).getFirst();
        assertFalse(empty.get("available").getAsBoolean());assertTrue(empty.getAsJsonArray("models").isEmpty());
        assertEquals(0,empty.get("index").getAsInt());assertEquals(1,empty.get("count").getAsInt());
        assertThrows(IllegalArgumentException.class,()->PrivateUploadCatalog.packets(models(65,false),true,1024,1));
        assertThrows(IllegalArgumentException.class,()->PrivateUploadCatalog.packets(List.of(),true,1023,1));
        assertThrows(IllegalArgumentException.class,()->PrivateUploadCatalog.packets(List.of(),true,1024,0));
        assertThrows(IllegalArgumentException.class,()->PrivateUploadCatalog.packets(List.of(),true,1024,9_007_199_254_740_992L));
        var repeated=models(1,false).getFirst();
        assertThrows(IllegalArgumentException.class,()->PrivateUploadCatalog.packets(List.of(repeated,repeated),true,1024,1));
    }
    static List<PrivateModelStore.UploadedModel> models(int count,boolean escaped) {
        var result=new ArrayList<PrivateModelStore.UploadedModel>();
        for(int index=0;index<count;index++) {
            String prefix="ysm:"+index,tail=escaped?"&='".repeat(128):"鲸".repeat(128);
            String id=prefix+tail.substring(0,128-prefix.length());
            assertTrue(PrivateModelStore.validSourceId(id));
            result.add(new PrivateModelStore.UploadedModel(id,String.format(Locale.ROOT,"%064x",index+1),"ysm",PrivateModelBundle.MAX_BYTES));
        }
        return List.copyOf(result);
    }
}
