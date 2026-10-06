package com.simmc.meplayeractions.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Optional overrides for the existing server disguise command; null inherits server defaults. */
public record ServerDisguisePreferences(Double scale, Boolean hideSelf, Boolean showSelf,
                                        Double viewDistance, Integer maxViewers, Integer delay, String effect) {
    private static final Set<String> FIELDS=Set.of("scale","hideSelf","showSelf","viewDistance","maxViewers","delay","effect");
    public ServerDisguisePreferences {
        numberRange(scale,.05,8,"缩放范围：0.05–8");
        numberRange(viewDistance,.1,256,"观看距离范围：0.1–256 格");
        intRange(maxViewers,0,1000,"观看人数范围：0–1000");
        intRange(delay,0,20,"视觉延迟范围：0–20 tick");
        effect=canonicalEffect(effect);
    }
    public static ServerDisguisePreferences defaults() {return new ServerDisguisePreferences(null,null,null,null,null,null,null);}
    public boolean isDefault() {return equals(defaults());}
    public static ServerDisguisePreferences fromFields(String scale,String hideSelf,String showSelf,String distance,String viewers,String delay,String effect) {
        return new ServerDisguisePreferences(decimal(scale,"缩放"),bool(hideSelf,"隐藏原人物"),bool(showSelf,"本人可见"),
                decimal(distance,"观看距离"),integer(viewers,"观看人数"),integer(delay,"视觉延迟"),effect);
    }
    public List<String> arguments() {
        List<String> values=new ArrayList<>();
        if(scale!=null)values.add("scale="+format(scale));
        if(hideSelf!=null)values.add("hide-self="+hideSelf);
        if(showSelf!=null)values.add("show-self="+showSelf);
        if(viewDistance!=null)values.add("view-distance="+format(viewDistance));
        if(maxViewers!=null)values.add("max-viewers="+maxViewers);
        if(delay!=null)values.add("delay="+delay);
        if(effect!=null)values.add("effect="+effect);
        return List.copyOf(values);
    }
    public String command(String modelId) {
        if(modelId==null||!modelId.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("无效的服务器模型 ID");
        String command="meplayeractions disguise "+modelId;
        if(!arguments().isEmpty())command+=" "+String.join(" ",arguments());
        // Reserve 48 characters for the optional request-id UUID confirmation suffix.
        if(command.length()>208)throw new IllegalArgumentException("参数命令过长，请缩短小数位数");
        return command;
    }
    public JsonObject toJson() {
        JsonObject json=new JsonObject();
        if(scale!=null)json.addProperty("scale",scale);
        if(hideSelf!=null)json.addProperty("hideSelf",hideSelf);
        if(showSelf!=null)json.addProperty("showSelf",showSelf);
        if(viewDistance!=null)json.addProperty("viewDistance",viewDistance);
        if(maxViewers!=null)json.addProperty("maxViewers",maxViewers);
        if(delay!=null)json.addProperty("delay",delay);
        if(effect!=null)json.addProperty("effect",effect);
        return json;
    }
    public static ServerDisguisePreferences fromJson(JsonObject json) {
        if(!FIELDS.containsAll(json.keySet()))throw new IllegalArgumentException("Unknown server disguise preference");
        return new ServerDisguisePreferences(number(json,"scale"),booleanValue(json,"hideSelf"),booleanValue(json,"showSelf"),
                number(json,"viewDistance"),integerValue(json,"maxViewers"),integerValue(json,"delay"),stringValue(json,"effect"));
    }
    private static String canonicalEffect(String text) {
        if(text==null||text.isBlank()||text.trim().equalsIgnoreCase("none"))return null;
        String value=text.trim().toLowerCase(Locale.ROOT);
        if(!value.matches("slowness(?::[0-9]{1,3})?(?::[0-9]{1,5})?"))throw new IllegalArgumentException("药水仅支持 slowness:等级[:秒数]");
        String[] parts=value.split(":");int level=parts.length>1?Integer.parseInt(parts[1]):1;
        intRange(level,1,256,"缓慢等级范围：1–256");
        if(parts.length<3)return "slowness:"+level;
        int seconds=Integer.parseInt(parts[2]);intRange(seconds,1,86400,"缓慢持续时间范围：1–86400 秒");
        return "slowness:"+level+":"+seconds;
    }
    private static String format(double value) {return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private static Double decimal(String value,String label) {
        if(value==null||value.isBlank())return null;
        try {return Double.valueOf(value.trim());}catch(NumberFormatException invalid){throw new IllegalArgumentException(label+"请输入数字");}
    }
    private static Integer integer(String value,String label) {
        if(value==null||value.isBlank())return null;
        try {return Integer.valueOf(value.trim());}catch(NumberFormatException invalid){throw new IllegalArgumentException(label+"请输入整数");}
    }
    private static Boolean bool(String value,String label) {
        if(value==null||value.isBlank())return null;
        return switch(value.trim().toLowerCase(Locale.ROOT)){case "true"->true;case "false"->false;default->throw new IllegalArgumentException(label+"使用 true／false 或留空");};
    }
    private static void numberRange(Double value,double min,double max,String error) {if(value!=null&&(!Double.isFinite(value)||value<min||value>max))throw new IllegalArgumentException(error);}
    private static void intRange(Integer value,int min,int max,String error) {if(value!=null&&(value<min||value>max))throw new IllegalArgumentException(error);}
    private static JsonElement primitive(JsonObject json,String key) {
        JsonElement value=json.get(key);
        if(value==null||value.isJsonNull())return null;
        if(!value.isJsonPrimitive())throw new IllegalArgumentException("Invalid preference type: "+key);
        return value;
    }
    private static Double number(JsonObject json,String key) {
        JsonElement value=primitive(json,key);if(value==null)return null;
        if(!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid number: "+key);
        return value.getAsDouble();
    }
    private static Integer integerValue(JsonObject json,String key) {
        JsonElement value=primitive(json,key);if(value==null)return null;
        if(!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid integer: "+key);
        return value.getAsBigDecimal().intValueExact();
    }
    private static Boolean booleanValue(JsonObject json,String key) {
        JsonElement value=primitive(json,key);if(value==null)return null;
        if(!value.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Invalid boolean: "+key);
        return value.getAsBoolean();
    }
    private static String stringValue(JsonObject json,String key) {
        JsonElement value=primitive(json,key);if(value==null)return null;
        if(!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid string: "+key);
        return value.getAsString();
    }
}
