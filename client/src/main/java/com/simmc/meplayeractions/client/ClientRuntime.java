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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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
                                float scale,boolean hidePlayer,String motionSource) {}
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
    private final LocalModelLibrary localModelLibrary=new LocalModelLibrary(
            FabricLoader.getInstance().getConfigDir().resolve("meplayeractions/models"));
    public final ClientOptions options;
    private boolean connected,acknowledged;
    private long generation,lastHello,lastHeartbeat,lastReceived;
    private long localTick;
    private int leaseTicks=100,maxPayload=16_000;
    private Object world;
    private String lastError="",previewId="",previewHash="",previewManual="",previewPose="";
    private long previewStarted,previewManualStarted,previewJumpUntil;
    private long previewRequest;
    private boolean previewPending;
    private boolean previewWasGround=true;
    private Binding localSelf;
    private final LocalAppearanceVisibility localAppearanceVisibility=new LocalAppearanceVisibility();
    private boolean localAppearancePending;
    private long localAppearanceRequest,localAppearanceRetryAfter,requestPacketsSent;
    private long ownAppearanceMissingSince,lastAppearanceSnapshotRequest;
    private String localAppearanceError="";

    public ClientRuntime(MinecraftClient client) {
        this.client=client;
        options=new ClientOptions(FabricLoader.getInstance().getConfigDir().resolve("meplayeractions-client.json"));
    }
    public void joined() { reset(); connected=true; world=client.world; }
    public void reset() {
        releaseBindings();abortTransfers();connected=false;acknowledged=false;
        loading.clear(); requested.clear(); failedAssets.clear(); assets.clear(); clock.reset();
        lastHello=0;lastHeartbeat=0;lastReceived=0;previewId="";previewHash="";previewManual="";previewPose="";
        localSelf=null;localAppearanceError="";localAppearanceRetryAfter=0;
        ownAppearanceMissingSince=0;lastAppearanceSnapshotRequest=0;
        localAppearanceVisibility.reset();
        ModelRenderer.clear();
    }
    public void tick() {
        localTick++;
        long now=System.nanoTime();
        if (client.world!=world) {
            boolean wasConnected=connected;reset();connected=wasConnected;world=client.world;
        }
        // This private mode is independent of the server handshake and also runs in single player.
        ensureLocalAppearance();
        updateLocalAppearanceMotion();
        if (!connected || client.getNetworkHandler()==null) return;
        if (acknowledged && now-lastReceived>leaseTicks*50_000_000L) {
            releaseBindings();abortTransfers();acknowledged=false;lastHello=0;lastError="服务器同步已超时，恢复服务器显示";
        }
        if (options.enabled && !acknowledged && now-lastHello>3*SECOND && ClientPlayNetworking.canSend(ActionPayload.ID)) {
            JsonObject hello=WireJson.envelope("hello");hello.addProperty("clientVersion","0.4.0");
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
            // A lost lease can remove the server binding before an undisguise notice is sent.
            // A bounded full snapshot proves when private rendering can safely resume.
            boolean missingOwn=localAppearance().enabled() && localAppearanceVisibility.hasServerAppearance()
                    && client.player!=null && !bindings.containsKey(client.player.getUuid());
            if(!missingOwn)ownAppearanceMissingSince=0;
            else {
                if(ownAppearanceMissingSince==0)ownAppearanceMissingSince=now;
                if(now-ownAppearanceMissingSince>200_000_000L && now-lastAppearanceSnapshotRequest>2*SECOND) {
                    if(send(WireJson.envelope("snapshot_request")))lastAppearanceSnapshotRequest=now;
                }
            }
        }
        for (String hash:List.copyOf(requested)) if(!loading.contains(hash) && now-transferTimes.getOrDefault(hash,now)>15*SECOND)
            failAsset(hash,"模型下载超时");
        if(!options.followServerTimeline && client.world!=null) for(Binding binding:bindings.values()) {
            BbModel model=assets.get(binding.hash);PlayerEntity player=client.world.getPlayerByUuid(binding.owner);
            if(model!=null && player!=null && usable(binding,now)) entityBinding(binding,model,player);
        }
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
                localAppearanceVisibility.serverSessionStarted();
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
                    String instance=WireJson.string(json,"instance",36);
                    Binding binding=bindings.get(owner);
                    if(binding!=null && binding.instance.equals(instance)) bindings.remove(owner);
                    String reason=WireJson.string(json,"reason",128);
                    if(client.player!=null && owner.equals(client.player.getUuid()))localAppearanceVisibility.serverUnbound(instance,reason);
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
                case "snapshot_begin" -> localAppearanceVisibility.beginSnapshot(WireJson.integer(json,"snapshotId",0,Long.MAX_VALUE));
                case "snapshot_end" -> localAppearanceVisibility.endSnapshot(WireJson.integer(json,"snapshotId",0,Long.MAX_VALUE));
                default -> throw new IllegalArgumentException("Unknown packet");
            }
            lastReceived=now;
        } catch(Exception exception) {
            lastError="同步包校验失败，恢复服务器显示";
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
        LocalMotionPolicy motion=LocalMotionPolicy.read(json.getAsJsonObject("motion"));
        Map<String,Double> accessories=readAccessoryState(json);
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
        if(client.player!=null && owner.equals(client.player.getUuid()))localAppearanceVisibility.serverOwnState(instance);
        binding.sequence=sequence;binding.layers=List.copyOf(layers);binding.scale=scale;binding.accessories=accessories;
        binding.foodLevel=json.has("foodLevel")?(int)WireJson.integer(json,"foodLevel",0,20):20;
        binding.layerTimeline.add(tick,binding.layers);
        binding.motion=motion;binding.localServerLayers=binding.localClock.accept(tick,localTick,binding.layers);
        binding.hidePlayer=hide;binding.actions=List.copyOf(actions);binding.lastPacket=now;
        BbModel loaded=assets.get(hash);
        binding.unsupported=loaded!=null && layers.stream().anyMatch(layer->!loaded.animations().contains(layer.animation()));
        if(binding.unsupported)failed(binding,"客户端模型缺少对应动画，请更新服务器 bbmodel");
        clock.observe(rawTick,now);
        if(!hash.isEmpty() && !assets.containsKey(hash) && !loading.contains(hash) && !requested.contains(hash)
                && now-failedAssets.getOrDefault(hash,0L)>30*SECOND) tryCached(modelId,hash);
    }
    private static Map<String,Double> readAccessoryState(JsonObject json) {
        JsonElement value=json.get("accessories");
        if(value==null)return Map.of();
        if(!value.isJsonObject())throw new IllegalArgumentException("Accessories");
        JsonObject accessories=value.getAsJsonObject();
        if(accessories.size()==0)return Map.of();
        if(!accessories.keySet().equals(Set.of("a","b")))throw new IllegalArgumentException("Accessory fields");
        return Map.of("a",WireJson.number(accessories,"a",0,1),"b",WireJson.number(accessories,"b",0,1));
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
        if(assets.size()>=16 && !assets.containsKey(hash) && !evictInactive()) {failAsset(hash,"同时显示的模型过多，保持服务器显示");return;}
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
        if(localSelf!=null)active.add(localSelf.hash);
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
        JsonObject json=identityFields(binding);json.addProperty("protocol",3);json.addProperty("type",type);return json;
    }
    private void failed(Binding binding,String reason) {
        if(binding.readySent) send(identity("render_failed",binding));
        binding.active=false;binding.readySent=false;if(!reason.isEmpty())lastError=reason;
    }
    private void releaseBindings() {for(Binding binding:bindings.values())failed(binding,"");bindings.clear();}
    private void abortTransfers() {
        generation++;previewRequest++;previewPending=false;decoder.getQueue().clear();
        localAppearanceRequest++;localAppearancePending=false;
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
        if(!serverBridgeReady())return;
        JsonObject json=WireJson.envelope("request");json.addProperty("action",action);
        if(!argument.isEmpty())json.addProperty("argument",argument);
        if(send(json))requestPacketsSent++;
    }
    public void toggleEnabled() {
        if(options.enabled) releaseBindings();
        abortTransfers();
        options.enabled=!options.enabled;options.save();acknowledged=false;lastHello=0;
    }
    public Collection<RenderBinding> renderBindings() {
        return animationBindings().stream().filter(binding -> shouldShowModel(binding.owner())).toList();
    }
    /** Hidden self models keep instance scripts and physics alive without submitting geometry. */
    public boolean shouldShowModel(UUID owner) {
        return client.player == null || !owner.equals(client.player.getUuid()) || options.showSelf;
    }
    public Collection<RenderBinding> animationBindings() {
        if(!options.enabled || client.world==null) return List.of();
        long now=System.nanoTime();double tick=clock.estimate(now)-options.interpolationTicks;
        List<RenderBinding> result=new ArrayList<>();
        for(Binding binding:bindings.values()) {
            BbModel model=assets.get(binding.hash);
            if(!usable(binding,now) || model==null)continue;
            if(options.followServerTimeline) {
                TransformTimeline.Transform t=binding.timeline.sample(tick);
                List<Layer> presentationLayers=binding.layerTimeline.sample(tick);
                result.add(new RenderBinding(binding.owner,binding.instance,binding.hash,model,presentationLayers,tick,
                        t.x(),t.y(),t.z(),t.bodyYaw(),t.headYaw(),t.headPitch(),binding.scale,binding.hidePlayer,"server-timeline"));
            } else {
                PlayerEntity player=client.world.getPlayerByUuid(binding.owner);
                if(player!=null) result.add(entityBinding(binding,model,player));
            }
        }
        if(localAppearanceActive()) {
            result.removeIf(binding -> binding.owner().equals(client.player.getUuid()));
            result.add(localAppearanceBinding());
            return List.copyOf(result);
        }
        Binding own=client.player==null?null:bindings.get(client.player.getUuid());
        if(own==null && !previewId.isEmpty() && client.player!=null
                && assets.containsKey(previewHash) && ModelRenderer.has(previewHash))result.add(previewBinding());
        return List.copyOf(result);
    }
    /** Diagnostics only: compare the ME's delayed trajectory with the native entity presentation. */
    public TransformTimeline.Transform serverTransform(UUID owner) {
        Binding binding=bindings.get(owner);
        return binding==null?null:binding.timeline.sample(clock.estimate(System.nanoTime())-options.interpolationTicks);
    }
    public Map<String,Double> expressionQueries(UUID owner) {
        PlayerEntity player=client.world==null?null:client.world.getPlayerByUuid(owner);
        if(player==null)return Map.of();
        Binding binding=bindings.get(owner);
        // Native entity deltas also work for remote players whose getVelocity is not populated.
        Vec3d delta=player.getEntityPos().subtract(player.lastX,player.lastY,player.lastZ);
        if(delta.lengthSquared()>16)delta=Vec3d.ZERO;
        Map<String,Double> values=new HashMap<>();
        values.put("ysm.food_level",(double)(player==client.player?player.getHungerManager().getFoodLevel():binding==null?20:binding.foodLevel));
        values.put("ysm.has_mainhand",player.getMainHandStack().isEmpty()?0d:1d);
        values.put("ysm.has_offhand",player.getOffHandStack().isEmpty()?0d:1d);
        values.put("query.ground_speed",Math.sqrt(delta.x*delta.x+delta.z*delta.z)*20);
        values.put("query.vertical_speed",delta.y*20);
        values.put("query.yaw_speed",(double)MathHelper.wrapDegrees(player.getYaw()-player.lastYaw)*20);
        values.put("query.position_delta_0",delta.x);values.put("query.position_delta_1",delta.y);values.put("query.position_delta_2",delta.z);
        values.put("query.is_sneaking",player.isSneaking()?1d:0d);
        values.put("query.time_stamp",(double)client.world.getTimeOfDay());
        return Map.copyOf(values);
    }
    /** Empty for local previews and models without server-owned accessory state. */
    public Map<String,Double> accessoryState(UUID owner) {
        if(localAppearanceActive() && owner.equals(client.player.getUuid()))return Map.of();
        Binding binding=bindings.get(owner);
        return binding==null?Map.of():binding.accessories;
    }
    private RenderBinding entityBinding(Binding binding,BbModel model,PlayerEntity player) {
        float delta=client.getRenderTickCounter().getTickProgress(false);
        Vec3d pos=player.getLerpedPos(delta);
        float bodyYaw=MathHelper.lerpAngleDegrees(delta,player.lastBodyYaw,player.bodyYaw);
        float headYaw=MathHelper.lerpAngleDegrees(delta,player.lastHeadYaw,player.headYaw),headPitch=player.getPitch(delta);
        LocalMotionPolicy policy=binding.motion;
        boolean own=player==client.player,bedSleeping=false;
        if(policy.specialPose().isEmpty() && player.isSleeping()) {
            var bed=player.getSleepingPosition().orElse(null);
            if(bed!=null) {
                var state=client.world.getBlockState(bed);
                if(state.getBlock() instanceof net.minecraft.block.BedBlock) {
                    var second=bed.offset(net.minecraft.block.BedBlock.getOppositePartDirection(state));
                    pos=new Vec3d((bed.getX()+second.getX()+1)*.5,bed.getY()+9.0/16,(bed.getZ()+second.getZ()+1)*.5);
                    bodyYaw=switch(state.get(net.minecraft.block.BedBlock.FACING)) {
                        case SOUTH->0;case WEST->90;case NORTH->180;case EAST->-90;default->bodyYaw;
                    };
                    headYaw=bodyYaw;headPitch=0;bedSleeping=true;
                }
            }
        }
        if(!policy.specialPose().isEmpty()) {
            pos=pos.add(policy.anchorX(),policy.anchorY(),policy.anchorZ());
            bodyYaw=policy.anchorYaw();
            if(policy.specialPose().equals("sleep")){headYaw=bodyYaw;headPitch=0;}
        }
        var vehicle=player.getVehicle();
        String riding=vehicle==null?"":vehicle instanceof AbstractBoatEntity?"boat":vehicle instanceof AbstractMinecartEntity?"minecart":vehicle instanceof net.minecraft.entity.passive.PigEntity?"ride-pig":"ride";
        var current=player.getEntityPos();
        binding.localMotion.update(localTick,new EntityAnimationController.Sample(current.x,current.y,current.z,player.isOnGround(),
                bedSleeping,player.getPose()==EntityPose.SWIMMING || policy.forcedPose().equals("crawl"),player.isTouchingWater(),own?player.getAbilities().flying:policy.flying(),
                player.isGliding(),player.isSneaking() || player.getPose()==EntityPose.CROUCHING || policy.forcedPose().equals("sneak"),player.isSprinting(),riding,player.handSwinging,player.handSwingTicks,
                player.preferredHand==Hand.OFF_HAND,own && client.interactionManager!=null && client.interactionManager.isBreakingBlock(),own,player.isClimbing()),
                policy,binding.localServerLayers);
        return new RenderBinding(binding.owner,binding.instance,binding.hash,model,binding.localMotion.layers(),localTick+delta,
                pos.x,pos.y,pos.z,bodyYaw,headYaw,headPitch,binding.scale,binding.hidePlayer,own?"local-player":"tracked-player");
    }
    public boolean shouldHidePlayer(UUID owner) {
        if(!options.enabled)return false;
        if(localAppearanceActive() && owner.equals(client.player.getUuid()))return true;
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
        if(localSelf!=null && localSelf.hash.equals(hash)
                && (owner==null || localSelf.owner.equals(owner) && localSelf.instance.equals(instance))) {
            localSelf=null;localAppearanceRequest++;localAppearancePending=false;
            localAppearanceError="本地外观绘制失败："+reason;localAppearanceRetryAfter=System.nanoTime()+30*SECOND;
        }
        if(owner==null) {
            for(Binding binding:bindings.values())if(binding.hash.equals(hash))failed(binding,"模型纹理恢复失败");
            failedAssets.put(hash,System.nanoTime());
        }
        Binding binding=bindings.get(owner);
        if(binding!=null && binding.instance.equals(instance) && binding.hash.equals(hash)) {
            failed(binding,"本地绘制失败，恢复服务器显示");failedAssets.put(hash,System.nanoTime());
            bindings.remove(owner);MEPlayerActionsClient.LOGGER.warn("Local renderer failed: {}",reason);
        }
        if(previewHash.equals(hash)){previewId="";previewHash="";}
    }
    public void releaseAll() {releaseBindings();}

    public boolean serverBridgeReady() {
        return connected && acknowledged && options.enabled && client.getNetworkHandler()!=null
                && System.nanoTime()-lastReceived<leaseTicks*50_000_000L;
    }
    public boolean serverBridgeConnected() {return serverBridgeReady();}
    public boolean serverOwnModelReady() {
        return client.player!=null && serverBridgeReady() && usable(bindings.get(client.player.getUuid()),System.nanoTime());
    }
    /** Counts only server gameplay requests, so private action previews can prove that none were sent. */
    public long requestPacketsSent() {return requestPacketsSent;}
    public LocalAppearanceSettings localAppearance() {return options.localAppearance();}
    public Path localAppearanceSettingsPath() {return options.path();}

    public List<Action> localModels() {
        try {return localModelLibrary.models().stream().map(entry -> new Action(entry.id(),entry.label())).toList();}
        catch(IOException exception) {
            MEPlayerActionsClient.LOGGER.warn("Cannot list local model directory: {}",exception.toString());
            return List.of(new Action("ysm_01_jk","银灰蓝眼 · 01"),new Action("ysm_02_jk","酒狐 · 02"));
        }
    }
    public List<Action> localActions() {
        BbModel model=localSelf==null?null:assets.get(localSelf.hash);
        return model==null?List.of():model.animations().stream()
                .filter(id -> !id.startsWith("parallel") && !id.startsWith("pre_parallel"))
                .sorted().map(id -> new Action(id,previewLabel(id))).toList();
    }

    /** Apply and persist only the viewer's own appearance; server identities and profiles are untouched. */
    public void updateLocalAppearance(LocalAppearanceSettings settings) {
        Objects.requireNonNull(settings);
        LocalAppearanceSettings previous=options.localAppearance();
        options.setLocalAppearance(settings);
        if(settings.enabled())options.enabled=true;
        options.save();
        if(!previous.modelId().equals(settings.modelId()) || !settings.enabled())invalidateLocalAppearance();
        else {localAppearanceError="";localAppearanceRetryAfter=0;}
        ensureLocalAppearance();
    }
    public void selectLocalModel(String id) {
        var current=localAppearance();
        updateLocalAppearance(new LocalAppearanceSettings(true,id,current.scale(),current.offsetX(),current.offsetY(),current.offsetZ()));
    }
    public void disableLocalAppearance() {
        var current=localAppearance();
        updateLocalAppearance(new LocalAppearanceSettings(false,current.modelId(),current.scale(),current.offsetX(),current.offsetY(),current.offsetZ()));
    }
    public void reloadLocalAppearanceSettings() {
        options.reloadLocalAppearance();invalidateLocalAppearance();ensureLocalAppearance();
    }
    public String localAppearanceStatus() {
        if(!localAppearance().enabled())return "本地外观已关闭";
        if(!options.enabled)return "客户端渲染已关闭；本地外观设置已保留";
        if(client.world==null || client.player==null)return "本地外观已保存，进入世界后显示";
        if(!localAppearanceError.isEmpty())return localAppearanceError;
        if(localAppearancePending)return "正在加载本地模型";
        if(localAppearancePrepared() && !localAppearanceActive())return "等待服务器显示接管；本地设置已保留";
        return localAppearanceActive()?"本地外观仅自己可见":"等待本地模型";
    }
    public boolean playLocal(String animation) {
        if(animation==null || !localAppearancePrepared())return false;
        BbModel model=assets.get(localSelf.hash);
        if(!model.animations().contains(animation) || animation.startsWith("parallel") || animation.startsWith("pre_parallel"))return false;
        localSelf.localServerLayers=List.of(new Layer("manual",animation,localTick,1,"ONCE",2,2));
        return true;
    }
    public void stopLocal() {if(localSelf!=null)localSelf.localServerLayers=List.of();}

    private void invalidateLocalAppearance() {
        localAppearanceRequest++;localAppearancePending=false;localSelf=null;
        localAppearanceError="";localAppearanceRetryAfter=0;
    }
    private boolean localAppearancePrepared() {
        return options.enabled && localAppearance().enabled() && client.world!=null && client.player!=null && localSelf!=null
                && localSelf.owner.equals(client.player.getUuid()) && localSelf.modelId.equals(localAppearance().modelId())
                && assets.containsKey(localSelf.hash) && ModelRenderer.has(localSelf.hash);
    }
    private boolean localAppearanceActive() {
        if(!localAppearancePrepared())return false;
        boolean bridgeAvailable=connected && client.getNetworkHandler()!=null && ClientPlayNetworking.canSend(ActionPayload.ID);
        return localAppearanceVisibility.canRender(bridgeAvailable,serverBridgeReady(),
                usable(bindings.get(client.player.getUuid()),System.nanoTime()));
    }
    private void ensureLocalAppearance() {
        var settings=localAppearance();
        if(!options.enabled || !settings.enabled() || client.world==null || client.player==null
                || localAppearancePrepared() || localAppearancePending || System.nanoTime()<localAppearanceRetryAfter)return;
        String id=settings.modelId();UUID owner=client.player.getUuid();long token=++localAppearanceRequest,epoch=generation;
        localAppearancePending=true;
        try {
            decoder.execute(()->{
                try {
                    var loaded=localModelLibrary.load(id);
                    client.execute(()->{
                        if(token!=localAppearanceRequest || epoch!=generation)return;
                        localAppearancePending=false;
                        if(client.world==null || client.player==null || !owner.equals(client.player.getUuid()) || !localAppearance().enabled())return;
                        install(loaded.hash(),loaded.model());
                        if(!assets.containsKey(loaded.hash()) || !ModelRenderer.has(loaded.hash())) {
                            localAppearanceError="本地模型纹理加载失败";localAppearanceRetryAfter=System.nanoTime()+30*SECOND;return;
                        }
                        localSelf=new Binding(owner,"local-self:"+token,id,loaded.hash());
                        localSelf.motion=localMotionPolicy(loaded.model());localSelf.hidePlayer=true;
                        localAppearanceError="";localAppearanceRetryAfter=0;
                    });
                } catch(Exception failure) {
                    client.execute(()->{
                        if(token!=localAppearanceRequest || epoch!=generation)return;
                        localAppearancePending=false;localAppearanceError="本地模型加载失败："+failure.getMessage();
                        localAppearanceRetryAfter=System.nanoTime()+30*SECOND;
                        MEPlayerActionsClient.LOGGER.warn("Cannot load private local appearance {}: {}",id,failure.toString());
                    });
                }
            });
        } catch(RejectedExecutionException busy) {
            localAppearancePending=false;localAppearanceError="模型加载繁忙，请稍后";localAppearanceRetryAfter=System.nanoTime()+SECOND;
        }
    }
    private void updateLocalAppearanceMotion() {
        if(!localAppearancePrepared())return;
        BbModel model=assets.get(localSelf.hash);
        if(!localSelf.localServerLayers.isEmpty()) {
            Layer manual=localSelf.localServerLayers.get(0);
            if(localTick>manual.startedAtTick()+model.animationLengthTicks(manual.animation())+manual.outTicks())stopLocal();
        }
        entityBinding(localSelf,model,client.player);
    }
    private RenderBinding localAppearanceBinding() {
        var settings=localAppearance();
        RenderBinding binding=entityBinding(localSelf,assets.get(localSelf.hash),client.player);
        return new RenderBinding(binding.owner(),binding.instance(),binding.assetHash(),binding.model(),binding.layers(),binding.serverTick(),
                binding.x()+settings.offsetX(),binding.y()+settings.offsetY(),binding.z()+settings.offsetZ(),
                binding.bodyYaw(),binding.headYaw(),binding.headPitch(),settings.scale(),true,"local-self");
    }
    private static LocalMotionPolicy localMotionPolicy(BbModel model) {
        String[][] mappings={
                {"idle","idle"},{"walk","walk"},{"run","run","walk"},{"jump","player_jump","jump"},{"fall","fall","jump"},
                {"sit","sit","minecart"},{"sleep","bed_sleep","sleep"},{"bed-sleep","bed_sleep","sleep"},
                {"boat","boat","sit"},{"minecart","minecart","sit"},{"ride","ride","sit"},{"ride-pig","ride_pig","ride","sit"},
                {"ladder-move","ladder_up","climb"},{"ladder-idle","ladder_stillness","ladder_up","climb"},
                {"crawl-idle","climbing","crawl_idle","crawl"},{"crawl-walk","climbing","crawl_walk","crawl"},
                {"crouch-idle","sneaking","crouch_idle","sneak"},{"crouch-walk","sneaking","crouch_walk","sneak"},
                {"swim-idle","swim_stand","swim_idle","swim"},{"swim-prone-idle","swim_idle","swim"},{"swim-walk","swim","swim_idle"},
                {"hover","hover","fly"},{"fly","fly","hover"},{"elytra","elytra_fly","fly"},
                {"swing-mainhand","use_mainhand","swing_hand","attack"},{"swing-offhand","use_offhand","swing_hand","attack"},
                {"mining","mining","attack"}
        };
        Map<String,Layer> clips=new LinkedHashMap<>();
        for(String[] mapping:mappings)for(int i=1;i<mapping.length;i++)if(model.animations().contains(mapping[i])) {
            String loop=Set.of("jump","swing-mainhand","swing-offhand").contains(mapping[0])?"ONCE"
                    :Set.of("sleep","bed-sleep").contains(mapping[0])?"HOLD":"LOOP";
            clips.put(mapping[0],new Layer("posture",mapping[i],0,1,loop,2,2));break;
        }
        return new LocalMotionPolicy(Set.of("movement","sprint","jump","sit","sleep","ride","crawl","sneak","swim","flight","elytra","swing","mining"),
                clips,17,8,.02,true,true,false,"","","",0,0,0,0);
    }

    public List<Action> actions() {
        Binding own=client.player==null?null:bindings.get(client.player.getUuid());
        if(own!=null)return own.actions;
        BbModel preview=assets.get(previewHash);
        return preview==null?List.of():preview.animations().stream().filter(id->!id.startsWith("parallel")&&!id.startsWith("pre_parallel")).sorted().map(id->new Action(id,previewLabel(id))).toList();
    }
    private static String previewLabel(String id) {
        return com.simmc.meplayeractions.config.AnimationLabels.DEFAULTS.getOrDefault(id,"自定义动作");
    }
    public List<String> status() {
        long active=bindings.values().stream().filter(b->b.active).count();
        List<String> text=new ArrayList<>();
        text.add("客户端 0.4.0 · "+(acknowledged?"已连接动作服务器":"等待服务器 / 本地预览"));
        text.add("已接管 "+active+" / "+bindings.size()+" 个模型 · 资产 "+assets.size()+" · 下载 "+transfers.size());
        text.add((options.followServerTimeline?"服务器拖后轨迹 · 缓冲 "+options.interpolationTicks+" tick":"客户端实体即时跟随 · 无额外位置缓冲")
                +" · 本人模型 "+(options.showSelf?"显示":"隐藏"));
        if(localAppearance().enabled())text.add("本地外观："+localAppearance().modelId()+" · "+localAppearanceStatus());
        if(!previewId.isEmpty())text.add("本地预览："+previewId);
        Binding own=client.player==null?null:bindings.get(client.player.getUuid());
        if(own!=null){text.add("模型："+own.modelId+" · "+(own.active?"本地渲染":own.readySent?"等待确认":"加载中"));
            text.add("动画："+(options.followServerTimeline?own.layers:own.localMotion.layers()).stream()
                    .map(l->l.layer()+"="+l.animation()).reduce((a,b)->a+" / "+b).orElse("基础姿态"));}
        if(!lastError.isEmpty())text.add(lastError);
        return List.copyOf(text);
    }
    public void preview(String id) {
        if(id.equals("off")){previewRequest++;previewPending=false;previewId="";previewHash="";previewManual="";return;}
        if(!Set.of("ysm_02_jk","ysm_01_jk").contains(id) || client.world==null || client.player==null) {
            notify("进入世界后可预览 ysm_02_jk / ysm_01_jk");return;
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
                pos.x,pos.y,pos.z,bodyYaw,headYaw,headPitch,1,true,"preview");
    }
    private void notify(String message) {if(client.player!=null)client.player.sendMessage(Text.literal("[动作客户端] "+message),false);}
    private static final class Binding {
        final UUID owner;final String instance,modelId,hash;final TransformTimeline timeline=new TransformTimeline();
        final SnapshotTimeline<List<Layer>> layerTimeline=new SnapshotTimeline<>();
        final EntityAnimationController localMotion=new EntityAnimationController();
        final LocalLayerClock localClock=new LocalLayerClock();
        LocalMotionPolicy motion;List<Layer> localServerLayers=List.of();
        long sequence=-1,lastPacket,lastReady;boolean readySent,active,hidePlayer,unsupported;float scale=1;
        int foodLevel=20;
        Map<String,Double> accessories=Map.of();
        List<Layer> layers=List.of();List<Action> actions=List.of();
        Binding(UUID owner,String instance,String modelId,String hash){this.owner=owner;this.instance=instance;this.modelId=modelId;this.hash=hash;}
    }
}
