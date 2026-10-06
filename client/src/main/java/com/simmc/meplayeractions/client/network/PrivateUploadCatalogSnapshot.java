package com.simmc.meplayeractions.client.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import java.util.*;

/** Owner-only saved upload metadata. It grants neither publication nor resource access. */
public final class PrivateUploadCatalogSnapshot {
    public static final String CAPABILITY = "private_upload_catalog_v1";
    public static final int MAX_MODELS = 64;
    public record Model(String modelId, String hash, String kind, int bytes) { }
    private List<Model> models=List.of();
    private long revision;
    private boolean available;
    private Pending pending;
    private static final class Pending {
        final long revision;final int count;final boolean available;
        final Map<Integer,List<Model>> chunks=new HashMap<>();final Set<String> ids=new HashSet<>();
        Pending(long revision,int count,boolean available){this.revision=revision;this.count=count;this.available=available;}
    }
    public void reset(){models=List.of();revision=0;available=false;pending=null;}
    public boolean accept(JsonObject packet) {
        if(!packet.keySet().equals(Set.of("protocol","type","revision","index","count","available","models")))
            throw new IllegalArgumentException("Private upload directory fields");
        long incoming=WireJson.integer(packet,"revision",1,9_007_199_254_740_991L);
        int count=(int)WireJson.integer(packet,"count",1,MAX_MODELS),index=(int)WireJson.integer(packet,"index",0,count-1);
        boolean allowed=WireJson.bool(packet,"available");JsonElement values=packet.get("models");
        if(values==null||!values.isJsonArray()||values.getAsJsonArray().size()>MAX_MODELS)
            throw new IllegalArgumentException("Private upload directory budget");
        var entries=new ArrayList<Model>();var ids=new HashSet<String>();
        for(JsonElement value:values.getAsJsonArray()) {
            if(!value.isJsonObject())throw new IllegalArgumentException("Private upload directory entry");
            JsonObject entry=value.getAsJsonObject();
            if(!entry.keySet().equals(Set.of("modelId","hash","kind","bytes")))throw new IllegalArgumentException("Private upload directory entry fields");
            String id=WireJson.string(entry,"modelId",128),hash=WireJson.hash(entry,"hash"),kind=WireJson.string(entry,"kind",16);
            int bytes=(int)WireJson.integer(entry,"bytes",22,AssetTransfer.MAX_RAW);
            if(!LocalAppearanceSettings.isValidModelId(id)||!ids.add(id)||!Set.of("ysm","bbmodel").contains(kind))
                throw new IllegalArgumentException("Private upload directory identity");
            entries.add(new Model(id,hash,kind,bytes));
        }
        if((!allowed||entries.isEmpty())&&(count!=1||!entries.isEmpty()))throw new IllegalArgumentException("Private upload directory availability");
        if(incoming<=revision||pending!=null&&incoming<pending.revision)return false;
        Pending next=pending==null||incoming>pending.revision?new Pending(incoming,count,allowed):pending;
        if(count!=next.count||allowed!=next.available)throw new IllegalArgumentException("Private upload directory revision");
        List<Model> prior=next.chunks.get(index);
        if(prior!=null){if(!prior.equals(entries))throw new IllegalArgumentException("Private upload directory changed fragment");return false;}
        if(next.ids.size()+entries.size()>MAX_MODELS||entries.stream().anyMatch(model->next.ids.contains(model.modelId())))
            throw new IllegalArgumentException("Private upload directory duplicate or budget");
        next.chunks.put(index,List.copyOf(entries));next.ids.addAll(ids);pending=next;
        if(next.chunks.size()!=count)return false;
        var complete=new ArrayList<Model>();for(int i=0;i<count;i++)complete.addAll(next.chunks.get(i));
        models=List.copyOf(complete);revision=incoming;available=allowed;pending=null;return true;
    }
    public List<Model> models(){return ready()&&available?models:List.of();}
    public boolean ready(){return revision>0&&pending==null;}
    public boolean receiving(){return pending!=null;}
    public long revision(){return revision;}
    public boolean available(){return ready()&&available;}
    /** A matching server deletion ACK can remove an already displayed entry before its next full snapshot. */
    public void deleted(String id,String hash){models=models.stream().filter(model->!model.modelId().equals(id)||!model.hash().equals(hash)).toList();}
}
