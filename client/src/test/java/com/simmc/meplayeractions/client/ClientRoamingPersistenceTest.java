package com.simmc.meplayeractions.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Author roaming changes are batched in memory; explicit lifecycle flush owns the single atomic write. */
class ClientRoamingPersistenceTest {
    @TempDir Path directory;

    @Test void batchesAcrossModelsPersistOnlyAtFlushAndRetainOrdinaryAuthorSettings() throws Exception {
        Path path=directory.resolve("options.json");var options=new ClientOptions(path);
        var original=new ClientOptions.ModelProfile("blue",Map.of("v.eye",3d),Map.of("eyes:0",2));
        assertTrue(options.updateModelProfile("ysm:sample",original));
        byte[] previous=Files.readAllBytes(path);
        assertTrue(options.updateRoamingVariables("ysm:sample",Map.of("v.roaming.眼睛٢",1d,"variable.roaming.hat",0d)));
        assertTrue(options.updateRoamingVariables("ysm:sample",Map.of("variable.roaming.眼睛٢",2d)));
        assertTrue(options.updateRoamingVariables("ysm:other",Map.of("variable.roaming.color",7d)));
        assertTrue(options.hasPendingRoamingVariables());assertArrayEquals(previous,Files.readAllBytes(path));
        assertEquals(3d,options.modelProfile("ysm:sample").variables().get("variable.eye"));
        assertEquals("blue",options.modelProfile("ysm:sample").textureId());
        assertEquals(original.radioSelections(),options.modelProfile("ysm:sample").radioSelections());
        assertTrue(options.flushRoamingVariables());assertFalse(options.hasPendingRoamingVariables());
        var restored=new ClientOptions(path);
        assertEquals(options.modelProfile("ysm:sample"),restored.modelProfile("ysm:sample"));
        assertEquals(7d,restored.modelProfile("ysm:other").variables().get("variable.roaming.color"));
    }

    @Test void repeatedValuesEmptyBatchesAndCleanFlushNeverRewriteTheOptionsFile() throws Exception {
        Path path=directory.resolve("options.json");var options=new ClientOptions(path);
        var values=Map.of("variable.roaming.hat",1d);
        assertTrue(options.updateRoamingVariables("ysm:sample",values));assertFalse(Files.exists(path));
        assertTrue(options.flushRoamingVariables());
        Files.setLastModifiedTime(path,FileTime.fromMillis(123_456_000));
        FileTime timestamp=Files.getLastModifiedTime(path);byte[] original=Files.readAllBytes(path);
        assertFalse(options.updateRoamingVariables("ysm:sample",values));
        assertFalse(options.updateRoamingVariables("ysm:sample",Map.of()));
        assertFalse(options.updateRoamingVariables("ysm:sample",Map.of("variable.ordinary",1d)));
        assertFalse(options.hasPendingRoamingVariables());assertTrue(options.flushRoamingVariables());
        assertArrayEquals(original,Files.readAllBytes(path));assertEquals(timestamp,Files.getLastModifiedTime(path));
    }

    @Test void completeDirtyBatchesRejectUnsafeNamesValuesAndCountsWithoutPartialMutation() {
        var options=new ClientOptions(directory.resolve("options.json"));
        Map<String,Double> ordinary=new LinkedHashMap<>(),roaming=new LinkedHashMap<>();
        for(int i=0;i<64;i++){ordinary.put("variable.form"+i,(double)i);roaming.put("variable.roaming.key"+i,i%2==0?1_000_000d:-1_000_000d);}
        assertTrue(options.updateModelProfile("ysm:sample",new ClientOptions.ModelProfile("blue",ordinary,Map.of())));
        assertTrue(options.updateRoamingVariables("ysm:sample",roaming));
        var before=options.modelProfile("ysm:sample");assertEquals(ClientOptions.MAX_MODEL_VARIABLES,before.variables().size());
        for(String name:List.of("variable.x","variable.roaming","variable.roaming.","variable.roaming.1bad",
                "variable.roaming.a.b","variable.roaming."+"a".repeat(33),"query.roaming.x"))
            assertFalse(options.updateRoamingVariables("ysm:sample",Map.of(name,1d)),name);
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,1_000_000.01d})
            assertFalse(options.updateRoamingVariables("ysm:sample",Map.of("variable.roaming.key0",value)));
        assertFalse(options.updateRoamingVariables("ysm:sample",Map.of("variable.roaming.extra",1d)));
        assertFalse(options.updateRoamingVariables("local:../escape.bbmodel",Map.of("variable.roaming.key0",1d)));
        var collision=new LinkedHashMap<String,Double>();collision.put("v.roaming.key0",1d);collision.put("variable.roaming.key0",2d);
        assertFalse(options.updateRoamingVariables("ysm:sample",collision));
        var partial=new LinkedHashMap<String,Double>();partial.put("variable.roaming.key0",4d);partial.put("variable.roaming.key1",null);
        assertFalse(options.updateRoamingVariables("ysm:sample",partial));
        assertEquals(before,options.modelProfile("ysm:sample"));assertTrue(options.hasPendingRoamingVariables());
        var crowded=new LinkedHashMap<>(ordinary);crowded.put("variable.one_more",1d);
        assertTrue(options.updateModelProfile("ysm:other",new ClientOptions.ModelProfile("",crowded,Map.of())));
        assertFalse(options.updateRoamingVariables("ysm:other",roaming));
        assertEquals(crowded,options.modelProfile("ysm:other").variables());
    }

    @Test void profileConstructionAndDiskLoadingCannotBypassTheNativeRoamingBudget() throws Exception {
        assertTrue(ClientOptions.isRoamingVariable("variable.roaming."+"眼".repeat(32)));
        assertTrue(ClientOptions.isRoamingVariable("variable.roaming._eye٢"));
        assertFalse(ClientOptions.isRoamingVariable("variable.roaming.a.b"));
        assertFalse(ClientOptions.isRoamingVariable("variable.roaming."+"a".repeat(33)));
        Map<String,Double> tooMany=new LinkedHashMap<>();
        for(int i=0;i<65;i++)tooMany.put("variable.roaming.key"+i,(double)i);
        assertThrows(IllegalArgumentException.class,()->new ClientOptions.ModelProfile("",tooMany,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new ClientOptions.ModelProfile("",Map.of("v.roaming.a.b",1d),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new ClientOptions.ModelProfile("",Map.of("variable.roaming",1d),Map.of()));
        Path path=directory.resolve("options.json");JsonObject values=new JsonObject();tooMany.forEach(values::addProperty);
        JsonObject profile=new JsonObject();profile.add("variables",values);
        JsonObject profiles=new JsonObject();profiles.add("ysm:sample",profile);
        JsonObject json=new JsonObject();json.addProperty("enabled",false);json.add("modelProfiles",profiles);
        Files.writeString(path,json.toString());var restored=new ClientOptions(path);
        assertFalse(restored.enabled);assertEquals(ClientOptions.ModelProfile.defaults(),restored.modelProfile("ysm:sample"));
        assertFalse(restored.hasPendingRoamingVariables());
    }

    @Test void failedFlushAndFailedProfileEditsKeepThePendingBatchForAnExplicitRetry() throws Exception {
        Path path=directory.resolve("options.json");var options=new ClientOptions(path);
        assertTrue(options.updateModelProfile("ysm:sample",new ClientOptions.ModelProfile("blue",Map.of("variable.eye",3d),Map.of())));
        Files.delete(path);Files.createDirectory(path);Path sentinel=path.resolve("keep.txt");Files.writeString(sentinel,"preserve");
        assertTrue(options.updateRoamingVariables("ysm:sample",Map.of("variable.roaming.hat",1d)));
        var pending=options.modelProfile("ysm:sample");assertFalse(options.flushRoamingVariables());
        assertTrue(options.hasPendingRoamingVariables());assertEquals(pending,options.modelProfile("ysm:sample"));
        assertFalse(options.updateModelProfile("ysm:sample",new ClientOptions.ModelProfile("changed",Map.of("variable.eye",4d),Map.of())));
        assertEquals(pending,options.modelProfile("ysm:sample"));assertTrue(options.hasPendingRoamingVariables());
        options.enabled=false;options.save();assertTrue(options.hasPendingRoamingVariables());
        assertEquals("preserve",Files.readString(sentinel));
        try(var children=Files.list(directory)){assertEquals(List.of(path),children.toList());}
        Files.delete(sentinel);Files.delete(path);
        assertTrue(options.flushRoamingVariables());assertFalse(options.hasPendingRoamingVariables());
        var restored=new ClientOptions(path);assertFalse(restored.enabled);assertEquals(pending,restored.modelProfile("ysm:sample"));
    }

    @Test void successfulOrdinaryOptionsSaveAlsoAcknowledgesAllPendingRoamingValues() throws Exception {
        Path path=directory.resolve("options.json");var options=new ClientOptions(path);
        assertTrue(options.updateRoamingVariables("ysm:sample",Map.of("variable.roaming.hat",1d)));
        options.privateSyncEnabled=true;options.save();assertFalse(options.hasPendingRoamingVariables());
        var restored=new ClientOptions(path);assertTrue(restored.privateSyncEnabled);
        assertEquals(1d,restored.modelProfile("ysm:sample").variables().get("variable.roaming.hat"));
        Files.setLastModifiedTime(path,FileTime.fromMillis(123_456_000));var timestamp=Files.getLastModifiedTime(path);
        assertTrue(options.flushRoamingVariables());assertEquals(timestamp,Files.getLastModifiedTime(path));
        assertTrue(JsonParser.parseString(Files.readString(path)).getAsJsonObject().has("modelProfiles"));
    }
}
