package com.simmc.meplayeractions.expression;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline numeric blueprint generator. The authoritative client models retain their expressions. */
public final class ModelBaker {
    private ModelBaker() { }
    public static void main(String[] args) throws Exception {
        Path raw=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        try(var files=Files.list(raw)) { for(Path file:files.filter(p->p.toString().endsWith(".bbmodel")).toList()) {
            JsonObject model=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject baked=bake(model);Path destination=output.resolve(file.getFileName());
            Files.writeString(destination,new Gson().toJson(baked)+"\n",StandardCharsets.UTF_8);
            System.out.println("Baked "+file.getFileName()+": "+baked.getAsJsonArray("animations").size()+" clips");
        }}
    }
    public static JsonObject bake(JsonObject raw) {
        JsonObject result=raw.deepCopy();result.remove("mpa_runtime");
        JsonArray actions=new JsonArray();
        for(JsonElement element:raw.getAsJsonArray("animations")) {
            JsonObject original=element.getAsJsonObject(),animation=original.deepCopy();
            String name=animation.get("name").getAsString();
            if(name.equals("parallel1")||name.equals("parallel2")) continue;
            double length=animation.get("length").getAsDouble(); if(length==0){length=4;animation.addProperty("length",length);}
            JsonObject tracks=new JsonObject();YsmRuntime physics=new YsmRuntime(raw,0);
            for(var entry:original.getAsJsonObject("animators").entrySet()) {
                JsonObject track=entry.getValue().getAsJsonObject();
                if(!track.get("type").getAsString().equals("bone")||track.get("name").getAsString().startsWith("molang"))continue;
                JsonObject converted=track.deepCopy();JsonArray frames=new JsonArray();
                for(String channel:List.of("position","rotation","scale")) {
                    List<JsonObject> keys=new ArrayList<>();
                    for(JsonElement key:track.getAsJsonArray("keyframes"))if(key.getAsJsonObject().get("channel").getAsString().equals(channel))keys.add(key.getAsJsonObject());
                    if(keys.isEmpty())continue; keys.sort(Comparator.comparingDouble(k->k.get("time").getAsDouble()));
                    boolean dynamic=keys.stream().anyMatch(k-> {for(JsonElement dp:k.getAsJsonArray("data_points"))for(String axis:List.of("x","y","z")) {
                        if(!dp.getAsJsonObject().has(axis))continue;
                        try {Double.parseDouble(dp.getAsJsonObject().get(axis).getAsString());}catch(NumberFormatException e){return true;}
                    }return false;});
                    if(!dynamic){keys.forEach(frames::add);continue;}
                    physics.reset();
                    for(int step=0;step<=Math.ceil(length*20);step++) {
                        double time=Math.min(length,step/20d);
                        Map<String,Double> queries=Map.of("ysm.food_level",20d,"query.ground_speed",name.contains("walk")||name.equals("run")?3d:0d,
                                "query.vertical_speed",name.equals("jump")?3*Math.cos(time*Math.PI/Math.max(length,.1)):0d);
                        physics.update(step,queries,Map.of(name,time));Molang.Context context=physics.snapshot().context(time);
                        JsonObject point=sample(keys,time,context);JsonObject frame=new JsonObject();frame.addProperty("channel",channel);frame.addProperty("time",time);
                        frame.addProperty("interpolation","linear");JsonArray points=new JsonArray();points.add(point);frame.add("data_points",points);
                        frames.add(frame);
                    }
                }
                converted.add("keyframes",frames);tracks.add(entry.getKey(),converted);
            }
            if(tracks.size()==0) continue;
            animation.add("animators",tracks);actions.add(animation);
        }
        result.add("animations",actions);return result;
    }
    private static JsonObject sample(List<JsonObject> keys,double time,Molang.Context context) {
        JsonObject left=keys.getFirst(),right=left;
        for(JsonObject key:keys) { if(key.get("time").getAsDouble()<=time)left=key;else {right=key;break;}right=left; }
        double a=left.get("time").getAsDouble(),b=right.get("time").getAsDouble();
        double t=b==a||left.get("interpolation").getAsString().equals("step")?0:(time-a)/(b-a);
        JsonArray l=left.getAsJsonArray("data_points"),r=right.getAsJsonArray("data_points");
        JsonObject lp=l.get(l.size()-1).getAsJsonObject(),rp=r.get(0).getAsJsonObject(),point=new JsonObject();
        for(String axis:List.of("x","y","z")) {
            double x=Molang.compile(lp.has(axis)?lp.get(axis).getAsString():"0").evaluate(context),
                    y=Molang.compile(rp.has(axis)?rp.get(axis).getAsString():"0").evaluate(context);
            double value=x+(y-x)*t;double bound=36000;
            point.addProperty(axis,Double.toString(Math.max(-bound,Math.min(bound,value))));
        }
        return point;
    }
}
