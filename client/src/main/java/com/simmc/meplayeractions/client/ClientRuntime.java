package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.BbModel.Layer;
import com.simmc.meplayeractions.client.network.*;
import com.simmc.meplayeractions.client.render.ModelRenderer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityPose;
import net.minecraft.text.Text;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** All live session and GPU mutations run on the Minecraft thread. Asset decoding does not. */
public final class ClientRuntime {
    public record RenderBinding(UUID owner, String instance, String assetHash, BbModel model, List<Layer> layers,
                                double serverTick, double x,double y,double z,float bodyYaw,float headYaw,float headPitch,
                                float scale,boolean hidePlayer) {}
    public record Action(String id,String label) {}
    private static final long SECOND=1_000_000_000L;
    private final MinecraftClient client;
    private final ServerClock clock=new ServerClock();
    private final Map<UUID,Binding> bindings=new LinkedHashMap<>();
    private final Map<String,BbModel> assets=new LinkedHashMap<>(16,0.75f,true);
    private final Map<String,AssetTransfer> transfers=new HashMap<>();
    private final Map<String,Long> transferTimes=new HashMap<>(),failedAssets=new HashMap<>();
    private final Set<String> loading=new HashSet<>(),requested=new HashSet<>();
    private final ThreadPoolExecutor decoder=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(8),
            r->{Thread t=new Thread(r,"MPA-model-loader");t.setDaemon(true);return t;});
    private final Path cache=FabricLoader.getInstance().getGameDir().resolve("config/meplayeractions/cache");
    public final ClientOptions options;
    private boolean connected,acknowledged;
    private long generation,lastHello,lastHeartbeat,lastReceived;
    private int leaseTicks=100,maxPayload=16_000;
    private Object world;
    private String lastError="",previewId="",previewHash="",previewManual="",previewPose="";
    private long previewStarted,previewManualStarted,previewJumpUntil;
    private long previewRequest;
    private boolean previewPending;
    private boolean previewWasGround=true;

    public ClientRuntime(MinecraftClient client) {
        this.client=client;
        options=new ClientOptions(FabricLoader.getInstance().getConfigDir().resolve("meplayeractions-client.json"));
    }
    public void joined() { reset(); connected=true; world=client.world; }
    public void reset() {
        releaseBindings();abortTransfers();connected=false;acknowledged=false;
        loading.clear(); requested.clear(); failedAssets.clear(); assets.clear(); clock.reset();
        lastHello=0;lastHeartbeat=0;lastReceived=0;previewId="";previewHash="";previewManual="";previewPose="";
        ModelRenderer.clear();
    }
    public void tick() {
        long now=System.nanoTime();
        if (client.world!=world) {
            boolean wasConnected=connected;reset();connected=wasConnected;world=client.world;
        }
        if (!connected || client.getNetworkHandler()==null) return;
        if (acknowledged && now-lastReceived>leaseTicks*50_000_000L) {
            releaseBindings();abortTransfers();acknowledged=false;lastHello=0;lastError="服务器同步已超时，恢复 ModelEngine";
        }
        if (options.enabled && !acknowledged && now-lastHello>3*SECOND && ClientPlayNetworking.canSend(ActionPayload.ID)) {
            JsonObject hello=WireJson.envelope("hello");hello.addProperty("clientVersion","0.3.0");
            JsonArray caps=new JsonArray();caps.add("local_render");hello.add("capabilities",caps);
            send(hello);lastHello=now;
        }
        if (acknowledged && options.enabled) {
            for (Binding binding:List.copyOf(bindings.values())) {
                if (now-binding.lastPacket>leaseTicks*50_000_000L) {
                    failed(binding,"模型状态超时");bindings.remove(binding.owner);continue;
                }
                if (!binding.hash.isEmpty() && assets.containsKey(binding.hash) && ModelRenderer.has(binding.hash)
                        && !binding.active && !binding.unsupported && now-binding.lastReady>SECOND
                        && now-failedAssets.getOrDefault(binding.hash,0L)>30*SECOND) {
                    JsonObject ready=identity("render_ready",binding);
                    if(send(ready)){binding.readySent=true;binding.lastReady=now;}
                }
            }
            if (now-lastHeartbeat>SECOND) {
                sendHeartbeats();lastHeartbeat=now;
            }
        }
        for (String hash:List.copyOf(requested)) if(!loading.contains(hash) && now-transferTimes.getOrDefault(hash,now)>15*SECOND)
            failAsset(hash,"模型下载超时");
    }
    public void receive(byte[] bytes) {
        if(!options.enabled) return;
        try {
            JsonObject json=WireJson.decode(bytes);String type=WireJson.string(json,"type",32);
            long now=System.nanoTime();
            if(type.equals("hello_ack")) {
                if (!WireJson.string(json,"mode",32).equals("local-render")) throw new IllegalArgumentException("Server mode");
                leaseTicks=(int)WireJson.integer(json,"leaseTicks",20,400);
                maxPayload=(int)WireJson.integer(json,"maxPayload",384,32_766);
                releaseBindings();abortTransfers();
                clock.observe(WireJson.integer(json,"serverTick",0,0xffff_ffffL),now);
                acknowledged=true;lastReceived=now;lastError="";return;
            }
            if(!acknowledged) return;
            switch(type) {
                case "state" -> state(json,now);
                case "render_ack" -> {
                    Binding binding=matching(json);
                    if(binding!=null && binding.readySent && assets.containsKey(binding.hash) && ModelRenderer.has(binding.hash))
                        binding.active=true;
                }
                case "unbind" -> {
                    UUID owner=UUID.fromString(WireJson.string(json,"owner",36));
                    Binding binding=bindings.get(owner);
                    if(binding!=null && binding.instance.equals(WireJson.string(json,"instance",36))) bindings.remove(owner);
                    String reason=WireJson.string(json,"reason",128);
                    if(Set.of("plugin-close","plugin_stopping","sync_disabled","session_ended").contains(reason)) {
                        releaseBindings();abortTransfers();acknowledged=false;lastHello=0;
                    }
                }
                case "heartbeat" -> clock.observe(WireJson.integer(json,"serverTick",0,0xffff_ffffL),now);
                case "asset_begin" -> begin(json,now);
                case "asset_chunk" -> {
                    String hash=WireJson.hash(json,"hash");AssetTransfer transfer=transfers.get(hash);
                    if(transfer!=null) {transfer.put((int)WireJson.integer(json,"index",0,AssetTransfer.MAX_CHUNKS-1),WireJson.string(json,"data",12_000));transferTimes.put(hash,now);}
                }
                case "asset_end" -> finish(WireJson.hash(json,"hash"));
                case "error" -> {
                    String code=WireJson.string(json,"code",64);lastError="服务器："+code;
                    if((code.equals("render_unavailable") || code.equals("render_not_authorized")) && json.has("owner")) {
                        Binding binding=matching(json);if(binding!=null){binding.active=false;binding.readySent=false;
                            failedAssets.put(binding.hash,now);}
                    }
                }
                case "snapshot_begin","snapshot_end" -> { }
                default -> throw new IllegalArgumentException("Unknown packet");
            }
            lastReceived=now;
        } catch(Exception exception) {
            lastError="同步包校验失败，恢复 ModelEngine";
            MEPlayerActionsClient.LOGGER.warn("Rejected MPA packet: {}",exception.toString());
            releaseBindings();abortTransfers();acknowledged=false;lastHello=System.nanoTime();
        }
    }
    private void state(JsonObject json,long now) {
        UUID owner=UUID.fromString(WireJson.string(json,"owner",36));
        if(client.player!=null && owner.equals(client.player.getUuid()) && (!previewId.isEmpty() || previewPending)) {
            previewRequest++;previewPending=false;previewId="";previewHash="";previewManual="";
        }
        String instance=UUID.fromString(WireJson.string(json,"instance",36)).toString();
        String modelId=WireJson.modelId(json),hash=WireJson.string(json,"assetHash",64);
        if(!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Hash");
        long sequence=WireJson.integer(json,"sequence",0,Long.MAX_VALUE),rawTick=WireJson.integer(json,"serverTick",0,0xffff_ffffL);
        long tick=clock.unwrap(rawTick);
        TransformTimeline.Transform transform=new TransformTimeline.Transform(tick,
                WireJson.number(json,"x",-30_000_000,30_000_000),WireJson.number(json,"y",-4096,4096),WireJson.number(json,"z",-30_000_000,30_000_000),
                (float)WireJson.number(json,"bodyYaw",-360_000,360_000),(float)WireJson.number(json,"headYaw",-360_000,360_000),
                (float)WireJson.number(json,"headPitch",-360,360));
        float scale=(float)WireJson.number(json,"scale",0.05,8);
        boolean hide=WireJson.bool(json,"hidePlayer");WireJson.bool(json,"showSelf");
        List<Layer> layers=new ArrayList<>();
        JsonArray array=json.getAsJsonArray("layers");if(array==null || array.size()>16) throw new IllegalArgumentException("Layers");
        Set<String> layerNames=new HashSet<>();
        for(JsonElement value:array) {
            JsonObject layer=value.getAsJsonObject();String name=WireJson.string(layer,"layer",32),animation=WireJson.string(layer,"animation",128);
            String loop=WireJson.string(layer,"loop",16);
            if(!layerNames.add(name) || !Set.of("ONCE","LOOP","HOLD").contains(loop)) throw new IllegalArgumentException("Layer mode");
            long rawStarted=WireJson.integer(layer,"startedAtTick",0,0xffff_ffffL);
            long started=tick-((rawTick-rawStarted)&0xffff_ffffL);
            layers.add(new Layer(name,animation,started,WireJson.number(layer,"speed",0.01,20),loop,
                    (int)WireJson.integer(layer,"inTicks",0,200),(int)WireJson.integer(layer,"outTicks",0,200)));
        }
        List<Action> actions=new ArrayList<>();
        JsonArray labels=json.getAsJsonArray("animations");
        if(labels!=null) for(JsonElement value:labels) {
            JsonObject label=value.getAsJsonObject();String id=WireJson.string(label,"id",128);
            if(!id.matches("[a-zA-Z0-9_.:/-]{1,128}")) throw new IllegalArgumentException("Animation ID");
            actions.add(new Action(id,WireJson.string(label,"label",256)));
        }
        Binding binding=bindings.get(owner);
        if(binding==null || !binding.instance.equals(instance) || !binding.hash.equals(hash)) {
            if(binding!=null) failed(binding,"");
            if(binding==null && bindings.size()>=64) return;
            binding=new Binding(owner,instance,modelId,hash);bindings.put(owner,binding);
        }
        if(sequence<binding.sequence || !binding.timeline.add(transform)) return;
        binding.sequence=sequence;binding.layers=List.copyOf(layers);binding.scale=scale;
        binding.layerTimeline.add(tick,binding.layers);
        binding.hidePlayer=hide;binding.actions=List.copyOf(actions);binding.lastPacket=now;
        BbModel loaded=assets.get(hash);
        binding.unsupported=loaded!=null && layers.stream().anyMatch(layer->!loaded.animations().contains(layer.animation()));
        if(binding.unsupported)failed(binding,"客户端模型缺少对应动画，请更新服务器 bbmodel");
        clock.observe(rawTick,now);
        if(!hash.isEmpty() && !assets.containsKey(hash) && !loading.contains(hash) && !requested.contains(hash)
                && now-failedAssets.getOrDefault(hash,0L)>30*SECOND) tryCached(modelId,hash);
    }
    private void tryCached(String modelId,String hash) {
        if(loading.size()>=4 || requested.size()>=2)return;
        loading.add(hash);long epoch=generation;
        decoder.execute(()->{
            BbModel model=null;
            try {
                Path path=cache.resolve(hash+".bbmodel");
                if(Files.isRegularFile(path) && Files.size(path)<=AssetTransfer.MAX_RAW) {
                    byte[] raw=Files.readAllBytes(path);
                    if(AssetTransfer.hash(raw).equals(hash)) model=BbModel.parse(raw);
                }
            } catch(Exception exception) { MEPlayerActionsClient.LOGGER.debug("Cache miss: {}",exception.toString()); }
            BbModel loaded=model;
            client.execute(()->{
                if(epoch!=generation) return;
                loading.remove(hash);
                if(loaded!=null) install(hash,loaded); else if(requested.size()<2) {
                    requested.add(hash);transferTimes.put(hash,System.nanoTime());JsonObject request=WireJson.envelope("asset_request");
                    request.addProperty("modelId",modelId);request.addProperty("hash",hash);
                    if(!send(request))failAsset(hash,"模型请求发送失败");
                }
            });
        });
    }
    private void begin(JsonObject json,long now) {
        String hash=WireJson.hash(json,"hash");
        if(!requested.contains(hash) || transfers.size()>=4) throw new IllegalArgumentException("Unexpected asset transfer");
        AssetTransfer transfer=new AssetTransfer(WireJson.modelId(json),hash,(int)WireJson.integer(json,"rawBytes",1,AssetTransfer.MAX_RAW),
                (int)WireJson.integer(json,"compressedBytes",1,AssetTransfer.MAX_COMPRESSED),(int)WireJson.integer(json,"chunks",1,AssetTransfer.MAX_CHUNKS));
        if(transfers.putIfAbsent(hash,transfer)!=null) throw new IllegalArgumentException("Duplicate asset transfer");
        transferTimes.put(hash,now);
    }
    private void finish(String hash) {
        AssetTransfer transfer=transfers.remove(hash);transferTimes.remove(hash);
        if(transfer==null) return;
        loading.add(hash);long epoch=generation;
        decoder.execute(()->{
            try {
                byte[] raw=transfer.finish();BbModel model=BbModel.parse(raw);
                try {Files.createDirectories(cache);Files.write(cache.resolve(hash+".bbmodel"),raw);pruneCache();}
                catch(IOException exception) {MEPlayerActionsClient.LOGGER.debug("Cache write skipped: {}",exception.toString());}
                client.execute(()->{if(epoch==generation){loading.remove(hash);requested.remove(hash);install(hash,model);}});
            } catch(Exception exception) {
                client.execute(()->{if(epoch==generation)failAsset(hash,"模型加载失败："+exception.getMessage());});
            }
        });
    }
    private void install(String hash,BbModel model) {
        if(assets.size()>=16 && !assets.containsKey(hash) && !evictInactive()) {failAsset(hash,"同时显示的模型过多，保持 ModelEngine");return;}
        try {
            if(!ModelRenderer.prepare(hash,model)) {
                while(evictInactive()) if(ModelRenderer.prepare(hash,model))break;
                if(!ModelRenderer.has(hash))throw new IllegalStateException("Texture preparation failed");
            }
            assets.put(hash,model);lastError="";
            for(Binding binding:bindings.values())if(binding.hash.equals(hash)) {
                binding.unsupported=binding.layers.stream().anyMatch(layer->!model.animations().contains(layer.animation()));
                if(binding.unsupported)failed(binding,"客户端模型缺少对应动画，请更新服务器 bbmodel");
            }
            MEPlayerActionsClient.LOGGER.info("Prepared MPA model {}: {} cubes, {} animations",hash.substring(0,12),model.cubeCount(),model.animations().size());
        } catch(Exception exception) {failAsset(hash,"模型纹理加载失败");}
    }
    private boolean evictInactive() {
        Set<String> active=new HashSet<>();for(Binding binding:bindings.values())active.add(binding.hash);
        if(!previewHash.isEmpty())active.add(previewHash);
        var iterator=assets.entrySet().iterator();
        while(iterator.hasNext()) {
            String hash=iterator.next().getKey();
            if(active.contains(hash))continue;
            iterator.remove();ModelRenderer.release(hash);return true;
        }
        return false;
    }
    private void failAsset(String hash,String reason) {
        transfers.remove(hash);transferTimes.remove(hash);requested.remove(hash);loading.remove(hash);
        failedAssets.put(hash,System.nanoTime());lastError=reason;
        for(Binding binding:bindings.values()) if(binding.hash.equals(hash)) failed(binding,"");
    }
    private void pruneCache() throws IOException {
        try(var stream=Files.list(cache)) {
            List<Path> paths=stream.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}\\.bbmodel")).sorted(Comparator.comparingLong((Path p)->{
                try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0L;}
            }).reversed()).toList();
            long total=0;for(Path path:paths) {total+=Files.size(path);if(total>128L*1024*1024)Files.deleteIfExists(path);}
        }
    }
    private Binding matching(JsonObject json) {
        Binding binding=bindings.get(UUID.fromString(WireJson.string(json,"owner",36)));
        return binding!=null && binding.instance.equals(WireJson.string(json,"instance",36))
                && binding.hash.equals(WireJson.hash(json,"hash"))?binding:null;
    }
    private JsonObject identityFields(Binding binding) {
        JsonObject json=new JsonObject();json.addProperty("owner",binding.owner.toString());
        json.addProperty("instance",binding.instance);json.addProperty("hash",binding.hash);return json;
    }
    private JsonObject identity(String type,Binding binding) {
        JsonObject json=identityFields(binding);json.addProperty("protocol",2);json.addProperty("type",type);return json;
    }
    private void failed(Binding binding,String reason) {
        if(binding.readySent) send(identity("render_failed",binding));
        binding.active=false;binding.readySent=false;if(!reason.isEmpty())lastError=reason;
    }
    private void releaseBindings() {for(Binding binding:bindings.values())failed(binding,"");bindings.clear();}
    private void abortTransfers() {
        generation++;previewRequest++;previewPending=false;decoder.getQueue().clear();
        transfers.clear();transferTimes.clear();loading.clear();requested.clear();
    }
    private boolean send(JsonObject json) {
        if(!connected || client.getNetworkHandler()==null || !ClientPlayNetworking.canSend(ActionPayload.ID))return false;
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>maxPayload){lastError="动作数据超过频道负载限制";return false;}
        try {ClientPlayNetworking.send(new ActionPayload(bytes));return true;}
        catch(RuntimeException exception) {lastError="服务器动作频道不可用";return false;}
    }
    private void sendHeartbeats() {
        JsonObject packet=WireJson.envelope("render_heartbeat");JsonArray batch=new JsonArray();packet.add("bindings",batch);
        for(Binding binding:bindings.values())if(binding.readySent) {
            JsonObject identity=identityFields(binding);batch.add(identity);
            if(packet.toString().getBytes(StandardCharsets.UTF_8).length>maxPayload) {
                batch.remove(batch.size()-1);send(packet);
                batch=new JsonArray();batch.add(identity);packet=WireJson.envelope("render_heartbeat");packet.add("bindings",batch);
            }
        }
        send(packet);
    }
    public void request(String action,String argument) {
        Binding own=client.player==null?null:bindings.get(client.player.getUuid());
        if(!previewId.isEmpty() && own==null) {
            if(action.equals("play")){previewManual=argument;previewManualStarted=client.world.getTime();}
            else if(action.equals("stop") || action.equals("reset"))previewManual="";
            return;
        }
        JsonObject json=WireJson.envelope("request");json.addProperty("action",action);
        if(!argument.isEmpty())json.addProperty("argument",argument);send(json);
    }
    public void toggleEnabled() {
        if(options.enabled) releaseBindings();
        abortTransfers();
        options.enabled=!options.enabled;options.save();acknowledged=false;lastHello=0;
    }
    public Collection<RenderBinding> renderBindings() {
        if(!options.enabled || client.world==null) return List.of();
        long now=System.nanoTime();double tick=clock.estimate(now)-options.interpolationTicks;
        List<RenderBinding> result=new ArrayList<>();
        for(Binding binding:bindings.values()) {
            BbModel model=assets.get(binding.hash);
            if(!usable(binding,now) || model==null)continue;
            if(client.player!=null && binding.owner.equals(client.player.getUuid()) && !options.showSelf)continue;
            TransformTimeline.Transform t=binding.timeline.sample(tick);
            List<Layer> presentationLayers=binding.layerTimeline.sample(tick);
            result.add(new RenderBinding(binding.owner,binding.instance,binding.hash,model,presentationLayers,tick,
                    t.x(),t.y(),t.z(),t.bodyYaw(),t.headYaw(),t.headPitch(),binding.scale,binding.hidePlayer));
        }
        Binding own=client.player==null?null:bindings.get(client.player.getUuid());
        if(own==null && !previewId.isEmpty() && client.player!=null && options.showSelf
                && assets.containsKey(previewHash) && ModelRenderer.has(previewHash))result.add(previewBinding());
        return List.copyOf(result);
    }
    public boolean shouldHidePlayer(UUID owner) {
        if(!options.enabled)return false;
        Binding binding=bindings.get(owner);long now=System.nanoTime();
        if(usable(binding,now))return binding.hidePlayer;
        return binding==null && !previewId.isEmpty() && client.player!=null && owner.equals(client.player.getUuid()) && assets.containsKey(previewHash)
                && ModelRenderer.has(previewHash);
    }
    private boolean usable(Binding binding,long now) {
        return binding!=null && binding.active && !binding.unsupported && now-binding.lastPacket<leaseTicks*50_000_000L
                && now-lastReceived<leaseTicks*50_000_000L && ModelRenderer.has(binding.hash);
    }
    public boolean shouldHideFirstPersonArm() {return client.player!=null && shouldHidePlayer(client.player.getUuid());}
    public void renderFailed(UUID owner,String instance,String hash,String reason) {
        if(owner==null) {
            for(Binding binding:bindings.values())if(binding.hash.equals(hash))failed(binding,"模型纹理恢复失败");
            failedAssets.put(hash,System.nanoTime());
        }
        Binding binding=bindings.get(owner);
        if(binding!=null && binding.instance.equals(instance) && binding.hash.equals(hash)) {
            failed(binding,"本地绘制失败，恢复 ModelEngine");failedAssets.put(hash,System.nanoTime());
            bindings.remove(owner);MEPlayerActionsClient.LOGGER.warn("Local renderer failed: {}",reason);
        }
        if(previewHash.equals(hash)){previewId="";previewHash="";}
    }
    public void releaseAll() {releaseBindings();}
    public List<Action> actions() {
        Binding own=client.player==null?null:bindings.get(client.player.getUuid());
        if(own!=null)return own.actions;
        BbModel preview=assets.get(previewHash);
        return preview==null?List.of():preview.animations().stream().sorted().map(id->new Action(id,previewLabel(id))).toList();
    }
    private static String previewLabel(String id) {
        return switch(id){case "idle"->"站立待机";case "walk"->"行走";case "run"->"奔跑";case "wave"->"挥手";case "nod"->"点头";
            case "talk"->"说话";case "smile"->"微笑";case "surprised"->"惊讶";case "sit"->"坐下动画";case "sleep"->"卷曲睡眠";case "bed_sleep"->"横卧睡眠";
            case "crawl_idle"->"趴下待机";case "crawl_walk"->"爬行";case "player_jump","jump"->"跳跃";case "fly","hover"->"飞行";
            case "crouch_idle"->"潜行待机";case "crouch_walk"->"潜行移动";case "swim"->"游泳";case "swim_idle"->"踩水";
            case "attack"->"攻击";case "blink"->"眨眼";case "climb"->"攀爬";case "climb_idle"->"攀爬待机";
            case "death"->"死亡";case "hurt"->"受伤";case "ribbon_sway"->"飘带摆动";case "tail_hair_sway"->"尾巴和长发摆动";
            case "use_mainhand"->"主手使用";case "use_offhand"->"副手使用";default->id;};
    }
    public List<String> status() {
        long active=bindings.values().stream().filter(b->b.active).count();
        List<String> text=new ArrayList<>();
        text.add("客户端 0.3.0 · "+(acknowledged?"已连接动作服务器":"等待服务器 / 本地预览"));
        text.add("已接管 "+active+" / "+bindings.size()+" 个模型 · 资产 "+assets.size()+" · 下载 "+transfers.size());
        text.add("本地插值缓冲 "+options.interpolationTicks+" tick · 本人模型 "+(options.showSelf?"显示":"隐藏"));
        if(!previewId.isEmpty())text.add("本地预览："+previewId);
        Binding own=client.player==null?null:bindings.get(client.player.getUuid());
        if(own!=null){text.add("模型："+own.modelId+" · "+(own.active?"本地渲染":own.readySent?"等待确认":"加载中"));
            text.add("动画："+own.layers.stream().map(l->l.layer()+"="+l.animation()).reduce((a,b)->a+" / "+b).orElse("基础姿态"));}
        if(!lastError.isEmpty())text.add(lastError);
        return List.copyOf(text);
    }
    public void preview(String id) {
        if(id.equals("off")){previewRequest++;previewPending=false;previewId="";previewHash="";previewManual="";return;}
        if(!Set.of("ysm_01_jk_npc","ysm_01_jk_player").contains(id) || client.world==null || client.player==null) {
            notify("进入世界后可预览 ysm_01_jk_npc / ysm_01_jk_player");return;
        }
        if(bindings.containsKey(client.player.getUuid())){notify("请先解除服务器伪装，再使用本地预览");return;}
        if(previewPending || loading.size()>=4){notify("模型正在加载，请稍后");return;}
        long token=++previewRequest;
        previewPending=true;
        long epoch=generation;
        decoder.execute(()->{
            try(InputStream input=ClientRuntime.class.getResourceAsStream("/assets/meplayeractions/models/"+id+".bbmodel")) {
                if(input==null)throw new IOException("Missing preview model");
                byte[] raw=input.readNBytes(AssetTransfer.MAX_RAW+1);
                if(raw.length>AssetTransfer.MAX_RAW)throw new IOException("Preview asset size");
                BbModel model=BbModel.parse(raw);String hash=AssetTransfer.hash(raw);
                client.execute(()->{if(epoch==generation && token==previewRequest){previewPending=false;install(hash,model);if(assets.containsKey(hash)){
                    previewId=id;previewHash=hash;previewManual="";previewStarted=client.world.getTime();notify("本地模型预览已开启，请切换第三人称；N 打开动作面板");}}});
            }catch(Exception exception){client.execute(()->{if(epoch==generation && token==previewRequest){previewPending=false;notify("预览失败："+exception.getMessage());}});}
        });
    }
    private RenderBinding previewBinding() {
        var player=client.player;BbModel model=assets.get(previewHash);
        long tick=client.world.getTime();double renderTick=tick+client.getRenderTickCounter().getTickProgress(false);
        String pose;
        boolean moving=player.getVelocity().horizontalLengthSquared()>0.0004;
        if(player.hasVehicle())pose="sit";
        else if(player.isSleeping())pose=model.animations().contains("bed_sleep")?"bed_sleep":"sleep";
        else if(player.getPose()==EntityPose.SWIMMING)pose=player.isTouchingWater()?"swim":moving?"crawl_walk":"crawl_idle";
        else if(player.getAbilities().flying)pose="fly";
        else if(player.isSneaking())pose=moving?"crouch_walk":"crouch_idle";
        else {
            if(previewWasGround && !player.isOnGround() && player.getVelocity().y>0)previewJumpUntil=tick+17;
            pose=tick<previewJumpUntil?"player_jump":!player.isOnGround()?"jump":moving?player.isSprinting()?"run":"walk":"idle";
        }
        previewWasGround=player.isOnGround();
        if(!model.animations().contains(pose))pose="idle";
        if(!pose.equals(previewPose)){previewPose=pose;previewStarted=tick;}
        List<Layer> layers=new ArrayList<>();layers.add(new Layer("posture",pose,previewStarted,1,"LOOP",2,2));
        if(!previewManual.isEmpty())layers.add(new Layer("manual",previewManual,previewManualStarted,1,"ONCE",2,2));
        var pos=player.getLerpedPos(client.getRenderTickCounter().getTickProgress(false));
        float bodyYaw=player.bodyYaw,headYaw=player.headYaw,headPitch=player.getPitch();
        if(player.isSleeping()) {
            var bed=player.getSleepingPosition().orElse(null);
            if(bed!=null) {
                var state=client.world.getBlockState(bed);
                if(state.getBlock() instanceof net.minecraft.block.BedBlock) {
                    var second=bed.offset(net.minecraft.block.BedBlock.getOppositePartDirection(state));
                    pos=new net.minecraft.util.math.Vec3d((bed.getX()+second.getX()+1)*.5,
                            bed.getY()+9.0/16,(bed.getZ()+second.getZ()+1)*.5);
                    bodyYaw=switch(state.get(net.minecraft.block.BedBlock.FACING)) {
                        case SOUTH->0;case WEST->90;case NORTH->180;case EAST->-90;default->bodyYaw;
                    };
                    headYaw=bodyYaw;headPitch=0;
                }
            }
        }
        return new RenderBinding(player.getUuid(),"preview",previewHash,model,List.copyOf(layers),renderTick,
                pos.x,pos.y,pos.z,bodyYaw,headYaw,headPitch,1,true);
    }
    private void notify(String message) {if(client.player!=null)client.player.sendMessage(Text.literal("[动作客户端] "+message),false);}
    private static final class Binding {
        final UUID owner;final String instance,modelId,hash;final TransformTimeline timeline=new TransformTimeline();
        final SnapshotTimeline<List<Layer>> layerTimeline=new SnapshotTimeline<>();
        long sequence=-1,lastPacket,lastReady;boolean readySent,active,hidePlayer,unsupported;float scale=1;
        List<Layer> layers=List.of();List<Action> actions=List.of();
        Binding(UUID owner,String instance,String modelId,String hash){this.owner=owner;this.instance=instance;this.modelId=modelId;this.hash=hash;}
    }
}
