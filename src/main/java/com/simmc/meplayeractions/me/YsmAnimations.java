package com.simmc.meplayeractions.me;

import com.google.gson.*;
import com.simmc.meplayeractions.expression.*;
import com.simmc.meplayeractions.action.PositionMotion;
import com.ticxo.modelengine.api.animation.*;
import com.ticxo.modelengine.api.animation.keyframe.KeyframeTypes;
import com.ticxo.modelengine.api.animation.keyframe.data.*;
import com.ticxo.modelengine.api.animation.keyframe.type.VectorKeyframe;
import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import org.bukkit.entity.Player;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private dynamic clips for a disguise; shared ME blueprints and other sessions remain immutable. */
final class YsmAnimations {
    final Map<String,BlueprintAnimation> clips=new LinkedHashMap<>();
    private final Map<String,String> sources=new LinkedHashMap<>();
    private final YsmRuntime runtime;
    private volatile YsmRuntime.Snapshot snapshot;
    private final ThreadLocal<Cache> evaluator=ThreadLocal.withInitial(Cache::new);
    private final PositionMotion motion=new PositionMotion();
    private final String diagnosis;
    private static final class Cache { YsmRuntime.Snapshot snapshot; double time; String animation; Molang.Context context; }
    YsmAnimations(ModelBlueprint blueprint) {
        JsonObject raw=null;
        try(InputStream stream=YsmAnimations.class.getClassLoader().getResourceAsStream("models/"+blueprint.getName()+".bbmodel")) {
            if(stream!=null)raw=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException error){throw new IllegalStateException("Cannot read runtime model",error);}
        if(raw==null||!raw.has("mpa_runtime")) {runtime=null;diagnosis="使用模型自带数值动画";return;}
        runtime=new YsmRuntime(raw,0);runtime.update(0,Map.of("ysm.food_level",20d),Map.of("idle",0d));snapshot=runtime.snapshot();
        for(JsonElement element:raw.getAsJsonArray("animations")) {
            JsonObject animation=element.getAsJsonObject();String name=animation.get("name").getAsString();
            BlueprintAnimation base=blueprint.getAnimations().get(name);if(base==null)continue;
            BlueprintAnimation clip=new BlueprintAnimation(blueprint,name);clip.setLength(base.getLength());clip.setLoopMode(base.getLoopMode());clip.setOverride(base.isOverride());
            for(var entry:animation.getAsJsonObject("animators").entrySet()) {
                JsonObject animator=entry.getValue().getAsJsonObject();if(!animator.get("type").getAsString().equals("bone"))continue;
                UUID id=UUID.fromString(entry.getKey());Timeline timeline=new Timeline(clip,false);
                for(JsonElement key:animator.getAsJsonArray("keyframes")) {
                    JsonObject frame=key.getAsJsonObject();String channel=frame.get("channel").getAsString();
                    JsonArray points=frame.getAsJsonArray("data_points");if(points.isEmpty())continue;
                    double time=frame.get("time").getAsDouble();VectorKeyframe vector=new VectorKeyframe();
                    JsonObject pre=points.get(0).getAsJsonObject(),post=points.get(points.size()-1).getAsJsonObject();
                    vector.setX(data(pre,"x",channel));vector.setY(data(pre,"y",channel));vector.setZ(data(pre,"z",channel));
                    vector.setPostX(data(post,"x",channel));vector.setPostY(data(post,"y",channel));vector.setPostZ(data(post,"z",channel));
                    vector.setDiscontinuous(points.size()>1);vector.setInterpolation(frame.get("interpolation").getAsString());
                    switch(channel) {
                        case "position"->timeline.getInterpolator(KeyframeTypes.POSITION).put((float)time,vector);
                        case "rotation"->timeline.getInterpolator(KeyframeTypes.ROTATION).put((float)time,vector);
                        case "scale"->timeline.getInterpolator(KeyframeTypes.SCALE).put((float)time,vector);
                        default->throw new IllegalArgumentException("Unexpected runtime channel");
                    }
                }
                clip.getTimelines().put(id,timeline);
            }
            clips.put(name,clip);sources.put(name,"YSM 实时条件表达式／独立物理控制器");
        }
        diagnosis="原始尺寸；YSM 条件动作与物理已接入（ME 按服务器 tick 更新）";
    }
    private IKeyframeData data(JsonObject point,String axis,String channel) {
        String text=point.has(axis)?point.get(axis).getAsString():channel.equals("scale")?"1":"0";
        double factor=channel.equals("position")?(axis.equals("z")?-1:1)/16d:
                channel.equals("rotation")?(axis.equals("x")?1:-1)*Math.PI/180:1;
        try{return new DoubleData(Double.parseDouble(text)*factor);}catch(NumberFormatException expression){
            Molang.Program program=Molang.compile(text);
            return property->{
                YsmRuntime.Snapshot current=snapshot;Cache cache=evaluator.get();
                double time=property==null?0:property.getTime();String name=property==null?"":property.getName();
                if(cache.snapshot!=current||cache.time!=time||!Objects.equals(cache.animation,name)) {
                    cache.snapshot=current;cache.time=time;cache.animation=name;cache.context=current.context(time);
                }
                double value=program.evaluate(cache.context);double bound=channel.equals("scale")?64:channel.equals("position")?4096:36000;
                return Math.max(-bound,Math.min(bound,value))*factor;
            };
        }
    }
    void update(Player player,long tick,float bodyYaw,float headYaw,float pitch,Map<String,Double> active) {
        if(runtime==null)return;
        var location=player.getLocation();
        var movement=motion.sample(location.getWorld().getUID(),location.getX(),location.getY(),location.getZ(),location.getYaw(),tick);
        Map<String,Double> values=new HashMap<>();
        values.put("ysm.food_level",(double)player.getFoodLevel());values.put("ysm.has_mainhand",player.getInventory().getItemInMainHand().getType().isAir()?0d:1d);
        values.put("ysm.has_offhand",player.getInventory().getItemInOffHand().getType().isAir()?0d:1d);
        values.put("ysm.head_yaw",wrap(headYaw-bodyYaw));values.put("ysm.head_pitch",(double)pitch);
        values.put("query.head_x_rotation",wrap(headYaw-bodyYaw));values.put("query.head_y_rotation",(double)pitch);
        values.put("query.ground_speed",movement.groundSpeed());values.put("query.vertical_speed",movement.verticalSpeed());
        values.put("query.yaw_speed",movement.yawSpeed());values.put("query.position_delta_0",movement.x());
        values.put("query.position_delta_1",movement.y());values.put("query.position_delta_2",movement.z());
        values.put("query.is_sneaking",player.isSneaking()?1d:0d);values.put("query.time_stamp",(double)player.getWorld().getTime());
        runtime.update(tick,values,active);snapshot=runtime.snapshot();
    }
    private static double wrap(double value){return value-Math.floor((value+180)/360)*360;}
    Map<String,Double> accessories(){return accessories(runtime==null?null:snapshot);}
    static Map<String,Double> accessories(YsmRuntime.Snapshot state) {
        if(state==null)return Map.of();
        return Map.of("a",Math.max(0,Math.min(1,Molang.finite(state.variables().getOrDefault("variable.roaming.a",0d)))),
                "b",Math.max(0,Math.min(1,Molang.finite(state.variables().getOrDefault("variable.roaming.b",0d)))));
    }
    Map<String,BlueprintAnimation> clips(){return clips;} Map<String,String> sources(){return sources;} String diagnosis(){return diagnosis;}
}
