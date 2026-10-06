package com.simmc.meplayeractions.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivateUploadCatalogSnapshotTest {
    @Test void completeRevisionsReplaceAtomicallyAndRestartDropsPriorServerMetadata() {
        var directory=new PrivateUploadCatalogSnapshot();assertTrue(directory.accept(packet(1,0,1,true,"local:first.bbmodel")));
        assertEquals("local:first.bbmodel",directory.models().getFirst().modelId());
        assertFalse(directory.accept(packet(2,1,2,true,"local:third.bbmodel")));assertFalse(directory.ready());assertTrue(directory.models().isEmpty());
        assertFalse(directory.accept(packet(2,1,2,true,"local:third.bbmodel")));
        assertTrue(directory.accept(packet(2,0,2,true,"local:second.bbmodel")));
        assertEquals(List.of("local:second.bbmodel","local:third.bbmodel"),directory.models().stream().map(PrivateUploadCatalogSnapshot.Model::modelId).toList());
        assertFalse(directory.accept(packet(1,0,1,true,"local:first.bbmodel")));directory.reset();assertTrue(directory.models().isEmpty());assertFalse(directory.ready());
    }
    @Test void revokedDirectoryCarriesNoModelsAndCannotBeMixedWithAuthorizedFragments() {
        var directory=new PrivateUploadCatalogSnapshot();assertTrue(directory.accept(packet(1,0,1,true,"ysm:鲸鱼娘flash.ysm")));
        assertTrue(directory.accept(packet(2,0,1,false)));assertFalse(directory.available());assertTrue(directory.models().isEmpty());
        assertThrows(IllegalArgumentException.class,()->directory.accept(packet(3,0,1,false,"local:first.bbmodel")));
        assertThrows(IllegalArgumentException.class,()->directory.accept(packet(3,0,2,true)));
    }
    @Test void duplicateChangedOrOverBudgetFragmentsAreRejectedBeforeTheyCanPublish() {
        var directory=new PrivateUploadCatalogSnapshot();assertFalse(directory.accept(packet(1,0,2,true,"local:first.bbmodel")));
        assertThrows(IllegalArgumentException.class,()->directory.accept(packet(1,0,2,true,"local:second.bbmodel")));
        assertThrows(IllegalArgumentException.class,()->directory.accept(packet(1,1,2,true,"local:first.bbmodel")));
        assertThrows(IllegalArgumentException.class,()->directory.accept(packet(1,1,3,true,"local:second.bbmodel")));
        String[] many=new String[65];for(int i=0;i<many.length;i++)many[i]="local:model"+i+".bbmodel";
        assertThrows(IllegalArgumentException.class,()->directory.accept(packet(2,0,1,true,many)));
        assertTrue(directory.models().isEmpty());
    }
    @Test void entriesAreTypedBoundedLocalSourceIdentitiesWithoutPathsOrAssetTokens() {
        for(String id:List.of("../server.bbmodel","local:../server.bbmodel","https://server/model.ysm","local:x")) {
            var directory=new PrivateUploadCatalogSnapshot();assertThrows(IllegalArgumentException.class,()->directory.accept(packet(1,0,1,true,id)));
        }
        for(String field:List.of("owner","offerId","url","command")) {
            JsonObject value=packet(1,0,1,true,"local:model.bbmodel");value.getAsJsonArray("models").get(0).getAsJsonObject().addProperty(field,"injected");
            assertThrows(IllegalArgumentException.class,()->new PrivateUploadCatalogSnapshot().accept(value));
        }
        JsonObject wrong=packet(1,0,1,true,"local:model.bbmodel");wrong.getAsJsonArray("models").get(0).getAsJsonObject().addProperty("bytes","2048");
        assertThrows(IllegalArgumentException.class,()->new PrivateUploadCatalogSnapshot().accept(wrong));
    }
    private static JsonObject packet(long revision,int index,int count,boolean available,String...ids) {
        JsonObject result=PrivateModelSyncClient.envelope("upload_catalog");result.addProperty("revision",revision);result.addProperty("index",index);result.addProperty("count",count);result.addProperty("available",available);
        JsonArray models=new JsonArray();for(String id:ids){JsonObject model=new JsonObject();model.addProperty("modelId",id);model.addProperty("hash","a".repeat(64));model.addProperty("kind","bbmodel");model.addProperty("bytes",2048);models.add(model);}result.add("models",models);return result;
    }
}
