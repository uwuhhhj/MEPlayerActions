package com.simmc.meplayeractions.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Metadata for the upload owner's saved resources; never a download or publication grant. */
final class PrivateUploadCatalog {
    static final String CAPABILITY="private_upload_catalog_v1";
    static final int REFRESH_TICKS=100,MAX_MODELS=64;
    private static final Gson GSON=new Gson();
    private PrivateUploadCatalog(){}
    static List<JsonObject> packets(List<PrivateModelStore.UploadedModel> models,boolean available,int maxPayload,long revision) {
        if(models.size()>MAX_MODELS||revision<1||revision>9_007_199_254_740_991L||maxPayload<1024)throw new IllegalArgumentException("Upload catalog budget");
        var chunks=new ArrayList<JsonArray>();var current=new JsonArray();var ids=new HashSet<String>();
        for(var model:available?models:List.<PrivateModelStore.UploadedModel>of()) {
            if(!ids.add(model.sourceId()))throw new IllegalArgumentException("Upload catalog duplicate source");
            JsonObject entry=new JsonObject();entry.addProperty("modelId",model.sourceId());entry.addProperty("hash",model.hash());
            entry.addProperty("kind",model.kind());entry.addProperty("bytes",model.bytes());current.add(entry);
            if(size(packet(current,MAX_MODELS-1,MAX_MODELS,available,revision))>maxPayload) {
                current.remove(current.size()-1);if(current.isEmpty())throw new IllegalArgumentException("Upload catalog entry too large");
                chunks.add(current);current=new JsonArray();current.add(entry);
                if(size(packet(current,MAX_MODELS-1,MAX_MODELS,available,revision))>maxPayload)throw new IllegalArgumentException("Upload catalog entry too large");
            }
        }
        chunks.add(current);var result=new ArrayList<JsonObject>();
        for(int index=0;index<chunks.size();index++)result.add(packet(chunks.get(index),index,chunks.size(),available,revision));
        return List.copyOf(result);
    }
    private static int size(JsonObject message){return GSON.toJson(message).getBytes(StandardCharsets.UTF_8).length;}
    private static JsonObject packet(JsonArray models,int index,int count,boolean available,long revision) {
        JsonObject packet=new JsonObject();packet.addProperty("protocol",PrivateModelSyncService.PROTOCOL);packet.addProperty("type","upload_catalog");
        packet.addProperty("revision",revision);packet.addProperty("index",index);packet.addProperty("count",count);packet.addProperty("available",available);packet.add("models",models);return packet;
    }
}
