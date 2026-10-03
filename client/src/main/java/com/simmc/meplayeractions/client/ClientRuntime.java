package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.BbModel.Layer;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.expression.Molang;
import com.simmc.meplayeractions.client.ui.ModelConfigSchema;
import com.simmc.meplayeractions.client.network.*;
import com.simmc.meplayeractions.client.render.ModelRenderer;
import com.simmc.meplayeractions.client.effects.YsmModelEffects;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
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
    private final Set<String> packAssets=new HashSet<>();
    private final Set<String> serverAssets=new HashSet<>();
    private final ServerPushAuthorization pushAuthorization=new ServerPushAuthorization();
    private final Map<String,Long> failedAssets=new HashMap<>();
    private final Set<String> loading=new HashSet<>();
    private final ThreadPoolExecutor decoder=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(8),
            r->{Thread t=new Thread(r,"MPA-model-loader");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor previewDecoder=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(8),
            r->{Thread t=new Thread(r,"MPA-preview-loader");t.setDaemon(true);return t;});
    private final Path cache=FabricLoader.getInstance().getGameDir().resolve("config/meplayeractions/cache");
    private final ServerModelCache serverModelCache=new ServerModelCache(cache);
    private final LocalModelLibrary localModelLibrary=new LocalModelLibrary(
            FabricLoader.getInstance().getConfigDir().resolve("meplayeractions/models"));
    private final Map<String,LocalModelLibrary.Loaded> localProfiles=new LinkedHashMap<>(16,.75f,true);
    private record EffectKey(UUID owner,UUID entity) { }
    private record EffectState(YsmModelEffects effects,Entity entity,String instance,String hash,YsmModelProfile profile) { }
    private final Map<EffectKey,EffectState> modelEffects=new LinkedHashMap<>();
    public final ClientOptions options;
    private boolean connected,acknowledged;
    private boolean pushNegotiated,unsupportedHandshake;
    private String serverAssetMode="";
    private List<String> serverCapabilities=List.of();
    private long pushOffersReceived,pushCacheHits,pushCacheMisses,pushTransfersBegun,pushTransfersCompleted,
            pushGpuPrepared,pushReadySent,pushRenderAcks,pushAssetRequests,pushCancelled,pushRejected;
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
        closeModelEffects();
        VanillaYsmQueries.reset();
        releaseBindings();abortTransfers();connected=false;acknowledged=false;
        loading.clear(); failedAssets.clear(); assets.clear(); packAssets.clear(); serverAssets.clear(); clock.reset();
        pushNegotiated=false;unsupportedHandshake=false;serverAssetMode="";serverCapabilities=List.of();
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
        tickModelEffects();
        if (!connected || client.getNetworkHandler()==null) return;
        if (acknowledged && now-lastReceived>leaseTicks*50_000_000L) {
            releaseBindings();abortTransfers();acknowledged=false;pushNegotiated=false;lastHello=0;lastError="服务器同步已超时，恢复服务器显示";
        }
        if (options.enabled && !acknowledged && !unsupportedHandshake && now-lastHello>3*SECOND && ClientPlayNetworking.canSend(ActionPayload.ID)) {
            JsonObject hello=WireJson.envelope("hello");hello.addProperty("clientVersion",clientVersion());
            JsonArray caps=new JsonArray();caps.add("local_render");caps.add("server_push_models");hello.add("capabilities",caps);
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
                    if(send(ready)){binding.readySent=true;binding.lastReady=now;binding.assetState="等待服务器渲染确认";pushReadySent++;}
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
        prunePushOffers(now);
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
                List<String> caps=readCapabilities(json);
                String mode=json.has("assetMode")?WireJson.string(json,"assetMode",32):"";
                if(!ServerPushAuthorization.acceptsHandshake(mode,caps)) {
                    releaseBindings();abortTransfers();acknowledged=false;pushNegotiated=false;
                    unsupportedHandshake=true;
                    serverAssetMode=mode;serverCapabilities=caps;lastHello=now;
                    lastError="服务器未协商模型主动推送，请升级 MEPlayerActions 服务端到 0.4.2；保持服务器显示";
                    return;
                }
                leaseTicks=(int)WireJson.integer(json,"leaseTicks",20,400);
                maxPayload=(int)WireJson.integer(json,"maxPayload",384,32_766);
                localAppearanceVisibility.serverSessionStarted();
                releaseBindings();abortTransfers();
                clock.observe(WireJson.integer(json,"serverTick",0,0xffff_ffffL),now);
                serverAssetMode=mode;serverCapabilities=caps;pushNegotiated=true;unsupportedHandshake=false;
                acknowledged=true;lastReceived=now;lastError="";return;
            }
            if(!acknowledged) {
                if(type.equals("error")) {
                    String code=WireJson.string(json,"code",64);
                    if(lastHello!=0 && Set.of("unsupported_protocol","invalid_payload","unsupported_capability").contains(code)) {
                        unsupportedHandshake=true;
                        lastError="服务器不支持模型主动推送，请升级 MEPlayerActions 服务端到 0.4.2（"+code+"）；保持服务器显示";
                    } else lastError="服务器："+code;
                }
                return;
            }
            switch(type) {
                case "state" -> state(json,now);
                case "render_ack" -> {
                    Binding binding=matching(json);
                    if(binding!=null && binding.readySent && assets.containsKey(binding.hash) && ModelRenderer.has(binding.hash))
                        {binding.active=true;binding.assetState="本地渲染";binding.assetError="";pushRenderAcks++;}
                }
                case "unbind" -> {
                    UUID owner=UUID.fromString(WireJson.string(json,"owner",36));
                    String instance=WireJson.string(json,"instance",36);
                    Binding binding=bindings.get(owner);
                    if(binding!=null && binding.instance.equals(instance)) {bindings.remove(owner);closeModelEffects(owner);}
                    prunePushOffers(now);
                    String reason=WireJson.string(json,"reason",128);
                    if(client.player!=null && owner.equals(client.player.getUuid()))localAppearanceVisibility.serverUnbound(instance,reason);
                    if(Set.of("plugin-close","plugin_stopping","sync_disabled","session_ended").contains(reason)) {
                        releaseBindings();abortTransfers();acknowledged=false;pushNegotiated=false;lastHello=0;
                    }
                }
                case "heartbeat" -> clock.observe(WireJson.integer(json,"serverTick",0,0xffff_ffffL),now);
                case "asset_offer" -> acceptOffer(json,now);
                case "asset_begin" -> begin(json,now);
                case "asset_chunk" -> pushChunk(json,now);
                case "asset_end" -> finish(json,now);
                case "asset_cancel" -> cancelOffer(json);
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
            releaseBindings();abortTransfers();acknowledged=false;pushNegotiated=false;lastHello=System.nanoTime();
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
        if(binding==null || !binding.instance.equals(instance) || !binding.hash.equals(hash) || !binding.modelId.equals(modelId)) {
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
        binding.serverAssetStatus=json.has("assetStatus")?WireJson.string(json,"assetStatus",64):"";
        binding.serverAssetReason=json.has("assetReason")?WireJson.string(json,"assetReason",256):"";
        binding.serverAssetSource=json.has("assetSource")?WireJson.string(json,"assetSource",128):"";
        BbModel loaded=assets.get(hash);
        binding.unsupported=loaded!=null && layers.stream().anyMatch(layer->!loaded.animations().contains(layer.animation()));
        if(binding.unsupported)failed(binding,"客户端模型缺少对应动画，请更新服务器 bbmodel");
        clock.observe(rawTick,now);
        if(hash.isEmpty()) {
            binding.assetState=switch(binding.serverAssetStatus) {
                case "pending" -> "服务器正在准备模型";
                case "missing" -> "服务器缺少客户端原模型";
                case "invalid" -> "服务器原模型无效";
                case "server-only" -> "该模型仅服务器渲染";
                default -> "服务器模型资产不可用";
            };
            binding.assetError=binding.serverAssetReason.isEmpty()?"服务端未提供客户端原模型，保持服务器显示":binding.serverAssetReason;
        } else if(!assets.containsKey(hash) && binding.assetState.equals("等待服务器模型")) {
            binding.assetError="";
        }
        prunePushOffers(now);
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
    private LocalModelLibrary.Loaded loadPackModel(String id, String hash) throws IOException {
        var manager=client.getResourceManager();
        return PackModelLibrary.load((resource,maximum)->{
            var found=manager.getResource(net.minecraft.util.Identifier.of(resource));
            if(found.isEmpty())throw new IOException("缺少资源 "+resource);
            try(InputStream input=found.get().getInputStream()) {
                byte[] data=input.readNBytes(maximum+1);
                if(data.length==0 || data.length>maximum)throw new IOException("资源大小超出限制");
                return data;
            }
        },id,hash);
    }
    private static String clientVersion() {
        return FabricLoader.getInstance().getModContainer("meplayeractions")
                .map(mod->mod.getMetadata().getVersion().getFriendlyString()).orElse("0.4.2");
    }
    private static List<String> readCapabilities(JsonObject json) {
        JsonArray values=json.getAsJsonArray("capabilities");
        if(values==null || values.size()>16)throw new IllegalArgumentException("Server capabilities");
        Set<String> result=new LinkedHashSet<>();
        for(JsonElement value:values) {
            if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Capability type");
            String capability=value.getAsString();
            if(!capability.matches("[a-z_]{1,64}") || !result.add(capability))throw new IllegalArgumentException("Capability value");
        }
        return List.copyOf(result);
    }
    private static UUID offerId(JsonObject json) {
        String value=WireJson.string(json,"offerId",36);UUID id=UUID.fromString(value);
        if(!id.toString().equals(value))throw new IllegalArgumentException("Offer UUID");
        return id;
    }
    private List<ServerPushAuthorization.Identity> pushBindings() {
        return bindings.values().stream().filter(binding->!binding.hash.isEmpty())
                .map(binding->new ServerPushAuthorization.Identity(binding.owner,binding.instance,binding.modelId,binding.hash)).toList();
    }
    private boolean currentPush(ServerPushAuthorization.Offer offer,long epoch) {
        return epoch==generation && pushNegotiated && acknowledged && client.world==world
                && pushAuthorization.current(offer,pushBindings(),System.nanoTime());
    }
    private void pushState(String hash,String state,String error) {
        for(Binding binding:bindings.values())if(binding.hash.equals(hash)) {
            binding.assetState=state;binding.assetError=error;
        }
    }
    private boolean assetStatus(ServerPushAuthorization.Offer offer,String status) {
        JsonObject feedback=WireJson.envelope("asset_status");
        feedback.addProperty("offerId",offer.offerId().toString());feedback.addProperty("hash",offer.identity().hash());
        feedback.addProperty("status",status);return send(feedback);
    }
    private void acceptOffer(JsonObject json,long now) {
        if(!pushNegotiated)throw new IllegalArgumentException("Unnegotiated server push");
        var identity=new ServerPushAuthorization.Identity(UUID.fromString(WireJson.string(json,"owner",36)),
                WireJson.string(json,"instance",36),WireJson.modelId(json),WireJson.hash(json,"hash"));
        var offer=new ServerPushAuthorization.Offer(offerId(json),identity);pushOffersReceived++;
        try {
            if(!pushAuthorization.offer(offer,pushBindings(),now))return;
        } catch(IllegalArgumentException rejected) {
            pushRejected++;assetStatus(offer,"rejected");return;
        }
        String hash=identity.hash();long epoch=generation;
        BbModel prepared=assets.get(hash);
        if(prepared!=null && ModelRenderer.has(hash)) {
            completePush(offer,prepared,true,epoch);return;
        }
        loading.add(hash);pushState(hash,"正在校验服务器模型缓存","");
        try {decoder.execute(()->{
            BbModel model=null;
            try {
                var cached=serverModelCache.readValidated(hash);
                if(cached.isPresent())model=BbModel.parse(cached.get());
            } catch(RuntimeException corrupt) {
                MEPlayerActionsClient.LOGGER.debug("Server model cache parse rejected {}",hash.substring(0,12),corrupt);
            }
            var result=model;
            client.execute(()->{
                if(!currentPush(offer,epoch))return;
                if(result!=null) {completePush(offer,result,true,epoch);return;}
                pushCacheMisses++;pushState(hash,"正在下载服务器模型","");
                pushAuthorization.missing(offer,pushBindings(),System.nanoTime());
                if(!assetStatus(offer,"missing"))rejectPush(offer,"无法反馈服务器模型缓存状态");
            });
        });} catch(RejectedExecutionException busy) {rejectPush(offer,"服务器模型缓存校验队列已满");}
    }
    private void completePush(ServerPushAuthorization.Offer offer,BbModel model,boolean cached,long epoch) {
        if(!currentPush(offer,epoch))return;
        String hash=offer.identity().hash();pushState(hash,"准备服务器模型渲染","");
        install(hash,model);
        boolean supported=bindings.values().stream().filter(binding->binding.hash.equals(hash)).anyMatch(binding->!binding.unsupported);
        if(!assets.containsKey(hash) || !ModelRenderer.has(hash) || !supported) {
            rejectPush(offer,"服务器模型无法准备渲染或缺少对应动画");return;
        }
        failedAssets.remove(hash);loading.remove(hash);pushGpuPrepared++;
        if(cached) {
            if(!assetStatus(offer,"cached")){rejectPush(offer,"无法确认已校验的服务器模型缓存");return;}
            pushCacheHits++;
        } else pushTransfersCompleted++;
        serverAssets.add(hash);pushState(hash,"等待服务器渲染确认","");pushAuthorization.remove(offer);
    }
    private void rejectPush(ServerPushAuthorization.Offer offer,String reason) {
        if(!pushAuthorization.remove(offer))return;
        pushRejected++;loading.remove(offer.identity().hash());assetStatus(offer,"rejected");
        failAsset(offer.identity().hash(),reason);pushState(offer.identity().hash(),"服务器模型同步失败",reason);
    }
    private void prunePushOffers(long now) {
        for(var offer:pushAuthorization.prune(pushBindings(),now)) {
            pushCancelled++;loading.remove(offer.identity().hash());assetStatus(offer,"rejected");
            pushState(offer.identity().hash(),"等待服务器模型","模型推送已取消或超时，保持服务器显示");
        }
    }
    public Set<String> resourcesReloaded() {
        closeModelEffects();
        // Server-pushed models own embedded textures and remain valid across an unrelated RP reload.
        // Invalidate only resource/private preview callbacks; do not discard in-flight push grants.
        previewRequest++;previewPending=false;localAppearanceRequest++;localAppearancePending=false;failedAssets.clear();
        Set<String> refresh=new HashSet<>(packAssets);refresh.removeAll(serverAssets);
        refresh.forEach(assets::remove);packAssets.clear();
        if(refresh.contains(previewHash)){previewHash="";previewId="";}
        return refresh;
    }
    /** Independent browser decoding; it never creates a world binding or acknowledges a server lease. */
    public CompletableFuture<LocalModelLibrary.Loaded> loadLocalPreview(String id) {
        String texture=localTextureSelection(id);
        try {return CompletableFuture.supplyAsync(()->{
            try {return localModelLibrary.load(id,texture.isEmpty()?null:texture);}
            catch(IOException failure) {throw new CompletionException(failure);}
        },previewDecoder).thenApply(loaded->{client.execute(()->rememberLocalProfile(id,loaded));return loaded;});}
        catch(RejectedExecutionException busy) {return CompletableFuture.failedFuture(busy);}
    }
    private String localTextureSelection(String id) {
        String texture=options.modelProfile(id).textureId();
        if(texture.isEmpty() && id.equals("openysm_default") && options.defaultBlueTexture)texture="blue";
        return texture;
    }
    private void rememberLocalProfile(String id,LocalModelLibrary.Loaded loaded) {
        localProfiles.put(id,loaded);while(localProfiles.size()>12)localProfiles.remove(localProfiles.keySet().iterator().next());
    }
    public YsmModelProfile localModelProfile(){return localModelProfile(localAppearance().modelId());}
    public YsmModelProfile localModelProfile(String id){
        if(localSelf!=null && localSelf.modelId.equals(id))return localSelf.profile;
        var loaded=localProfiles.get(id);return loaded==null?YsmModelProfile.empty():loaded.profile();
    }
    public YsmModelProfile modelProfile(UUID owner){return localAppearanceActive() && owner.equals(client.player.getUuid())?localSelf.profile:YsmModelProfile.empty();}
    public PlayerEntity nativePlayer(UUID owner){return client.world==null?null:client.world.getPlayerByUuid(owner);}
    public RenderBinding appearanceBinding(UUID owner){return animationBindings().stream().filter(binding->binding.owner().equals(owner)).findFirst().orElse(null);}
    public boolean localActionLocked(){return options.localActionLocked;}
    public void setLocalActionLocked(boolean locked){options.localActionLocked=locked;options.save();}
    public Map<String,Double> localModelVariables(String id){
        var loaded=localProfiles.get(id);BbModel model=loaded==null?null:loaded.model();
        if(model==null && localSelf!=null && localSelf.modelId.equals(id))model=assets.get(localSelf.hash);
        Map<String,Double> values=new LinkedHashMap<>();
        if(model!=null)values.putAll(model.initialVariables());
        if(client.player!=null && localAppearanceActive() && localSelf.modelId.equals(id))values.putAll(ModelRenderer.expressionVariables(client.player.getUuid()));
        if(id.equals("openysm_default"))values.put("variable.roaming.red_bow_headdress",options.defaultHeaddress?1d:0d);
        values.putAll(options.modelProfile(id).variables());return Map.copyOf(values);
    }
    public boolean runLocalScript(String id,String script){
        return script!=null && runLocalScripts(id,List.of(script),options.modelProfile(id).radioSelections());
    }
    public boolean runLocalScripts(String id,List<String> scripts,Map<String,Integer> radios){
        if(!LocalAppearanceSettings.isValidModelId(id)||scripts==null||radios==null||scripts.size()>128)return false;
        if(scripts.stream().anyMatch(Objects::isNull)||scripts.stream().mapToLong(String::length).sum()>32768)return false;
        try{
            var schema=ModelConfigSchema.from(localModelProfile(id),client.options.language);
            if(schema.variables().isEmpty())return false;
            var context=new Molang.Context();context.frame(Map.of());localModelVariables(id).forEach(context::set);
            for(String script:scripts){
                var program=Molang.compile(script);
                if(program.references().stream().anyMatch(name->!name.startsWith("variable.")&&!name.startsWith("temp.")&&!name.startsWith("math.")))return false;
                program.evaluate(context);
            }
            Map<String,Double> values=new LinkedHashMap<>(options.modelProfile(id).variables());
            for(String key:schema.variables())if(context.has(key))values.put(key,context.get(key));
            var previous=options.modelProfile(id);
            return options.updateModelProfile(id,new ClientOptions.ModelProfile(previous.textureId(),values,radios));
        }catch(RuntimeException invalid){MEPlayerActionsClient.LOGGER.debug("Private model configuration rejected: {}",invalid.getMessage());return false;}
    }
    public boolean selectLocalTexture(String id,String texture){
        var profile=localModelProfile(id);
        if(texture==null||profile.textures().stream().noneMatch(choice->choice.id().equals(texture)))return false;
        var previous=options.modelProfile(id);
        if(!options.updateModelProfile(id,new ClientOptions.ModelProfile(texture,previous.variables(),previous.radioSelections())))return false;
        localProfiles.remove(id);
        if(localSelf!=null && localSelf.modelId.equals(id)){invalidateLocalAppearance();ensureLocalAppearance();}
        return true;
    }
    public Path localModelDirectory() {return localModelLibrary.directory();}
    public void refreshLocalAppearance() {invalidateLocalAppearance();ensureLocalAppearance();}
    private void begin(JsonObject json,long now) {
        String hash=WireJson.hash(json,"hash");
        pushAuthorization.begin(offerId(json),hash,WireJson.modelId(json),(int)WireJson.integer(json,"rawBytes",1,AssetTransfer.MAX_RAW),
                (int)WireJson.integer(json,"compressedBytes",1,AssetTransfer.MAX_COMPRESSED),
                (int)WireJson.integer(json,"chunks",1,AssetTransfer.MAX_CHUNKS),pushBindings(),now);
        pushTransfersBegun++;pushState(hash,"正在下载服务器模型","");
    }
    private void pushChunk(JsonObject json,long now) {
        pushAuthorization.chunk(offerId(json),WireJson.hash(json,"hash"),
                (int)WireJson.integer(json,"index",0,AssetTransfer.MAX_CHUNKS-1),
                WireJson.string(json,"data",12_000),pushBindings(),now);
    }
    private void cancelOffer(JsonObject json) {
        UUID id=offerId(json);String hash=WireJson.hash(json,"hash"),reason=WireJson.string(json,"reason",128);
        var offer=pushAuthorization.find(id).orElse(null);
        if(offer==null)return;
        if(!offer.identity().hash().equals(hash))throw new IllegalArgumentException("Cancel offer hash");
        if(pushAuthorization.remove(offer)) {
            pushCancelled++;loading.remove(hash);
            pushState(hash,"等待服务器模型","服务器取消模型推送："+reason+"；保持服务器显示");
        }
    }
    private void finish(JsonObject json,long now) {
        UUID id=offerId(json);String hash=WireJson.hash(json,"hash");
        AssetTransfer transfer=pushAuthorization.end(id,hash,pushBindings(),now);
        var offer=pushAuthorization.find(id).orElseThrow();long epoch=generation;
        loading.add(hash);pushState(hash,"正在校验下载模型","");
        try {decoder.execute(()->{
            try {
                byte[] raw=transfer.finish();BbModel model=BbModel.parse(raw);
                client.execute(()->{
                    if(!currentPush(offer,epoch))return;
                    completePush(offer,model,false,epoch);
                    // Only a successfully prepared, still-authorized model may enter the disk cache.
                    // Cache I/O remains off the Minecraft thread, and precedes later cache reads on this executor.
                    if(assets.containsKey(hash) && ModelRenderer.has(hash) && !failedAssets.containsKey(hash)) {
                        try {decoder.execute(()->{
                            try {serverModelCache.writeValidated(hash,raw);}
                            catch(IOException failure) {MEPlayerActionsClient.LOGGER.debug("Server model cache write skipped {}: {}",hash.substring(0,12),failure.toString());}
                        });} catch(RejectedExecutionException busy) {
                            MEPlayerActionsClient.LOGGER.debug("Server model cache write queue full {}",hash.substring(0,12));
                        }
                    }
                });
            } catch(Exception exception) {
                client.execute(()->{if(currentPush(offer,epoch))rejectPush(offer,"服务器模型校验失败："+exception.getMessage());});
            }
        });} catch(RejectedExecutionException busy) {rejectPush(offer,"服务器模型解析队列已满");}
    }
    private void install(String hash,BbModel model) {
        if(assets.size()>=16 && !assets.containsKey(hash) && !evictInactive()) {failAsset(hash,"同时显示的模型过多，保持服务器显示");return;}
        try {
            if(!ModelRenderer.prepare(hash,model)) {
                while(evictInactive()) if(ModelRenderer.prepare(hash,model))break;
                if(!ModelRenderer.has(hash))throw new IllegalStateException("Texture preparation failed");
            }
            assets.put(hash,model);if(!unsupportedHandshake)lastError="";
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
            iterator.remove();packAssets.remove(hash);serverAssets.remove(hash);ModelRenderer.release(hash);return true;
        }
        return false;
    }
    private void failAsset(String hash,String reason) {
        loading.remove(hash);
        failedAssets.put(hash,System.nanoTime());lastError=reason;
        for(Binding binding:bindings.values()) if(binding.hash.equals(hash)) {failed(binding,"");binding.assetState="服务器模型同步失败";binding.assetError=reason;}
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
        closeModelEffects(binding.owner);
        if(binding.readySent) send(identity("render_failed",binding));
        binding.active=false;binding.readySent=false;
        if(!reason.isEmpty()){lastError=reason;binding.assetState="保持服务器显示";binding.assetError=reason;}
    }
    private void releaseBindings() {for(Binding binding:bindings.values())failed(binding,"");bindings.clear();}
    private void abortTransfers() {
        generation++;previewRequest++;previewPending=false;decoder.getQueue().clear();
        localAppearanceRequest++;localAppearancePending=false;
        pushCancelled+=pushAuthorization.diagnostics().size();pushAuthorization.clear();loading.clear();
    }
    private boolean send(JsonObject json) {
        if(!connected || client.getNetworkHandler()==null || !ClientPlayNetworking.canSend(ActionPayload.ID))return false;
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>maxPayload){lastError="动作数据超过频道负载限制";return false;}
        try {ClientPlayNetworking.send(new ActionPayload(bytes));
            if(json.has("type") && json.get("type").getAsString().equals("asset_request"))pushAssetRequests++;
            return true;}
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
        options.enabled=!options.enabled;options.save();acknowledged=false;pushNegotiated=false;unsupportedHandshake=false;lastHello=0;
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
    public Map<String,Double> localParameters(UUID owner) {
        if(!localAppearanceActive() || !owner.equals(client.player.getUuid()))return Map.of();
        Map<String,Double> values=new LinkedHashMap<>();
        if(localSelf.modelId.equals("openysm_default"))values.put("variable.roaming.red_bow_headdress",options.defaultHeaddress?1d:0d);
        values.putAll(options.modelProfile(localSelf.modelId).variables());return Map.copyOf(values);
    }
    public void configureExpressionContext(UUID owner,Molang.Context context){
        PlayerEntity player=nativePlayer(owner);Binding binding=bindings.get(owner);
        int food=player==client.player&&player!=null?player.getHungerManager().getFoodLevel():binding==null?20:binding.foodLevel;
        VanillaYsmQueries.populate(client,player,food,modelProfile(owner).selectedTexture(),context);
        context.query("ysm.is_first_person",player!=null && player==client.player && client.options.getPerspective().isFirstPerson()?1d:0d);
        Molang.FunctionResolver nativeFunctions=context.functionResolver();
        RenderBinding appearance=appearanceBinding(owner);
        YsmModelEffects frameEffects=appearance==null || player==null?null:
                frameEffects(owner,player,appearance.instance(),appearance.assetHash(),modelProfile(owner));
        installFrameEffects(context,nativeFunctions,frameEffects);
        Binding motion=localAppearanceActive()&&owner.equals(client.player.getUuid())?localSelf:binding;
        if(motion!=null)for(String controller:motion.localMotion.pausedControllers())context.query("ysm.pause."+controller,1d);
    }
    public void configureComponentExpressionContext(UUID owner,Entity entity,String componentHash,Molang.Context context){
        YsmModelProfile profile=modelProfile(owner);
        var component=profile.components().stream().filter(value->value.hash().equals(componentHash)).findFirst().orElse(null);
        VanillaYsmQueries.populateEntity(client,entity,20,component==null?"":component.selectedTexture(),context);
        context.query("ysm.is_first_person",component!=null && component.kind().equals("fp_arm")?1d:0d);
        Molang.FunctionResolver nativeFunctions=context.functionResolver();
        RenderBinding appearance=appearanceBinding(owner);
        YsmModelEffects effects=appearance==null || entity==null || component==null?null:
                frameEffects(owner,entity,appearance.instance(),componentHash,profile);
        installFrameEffects(context,nativeFunctions,effects);
    }
    private YsmModelEffects frameEffects(UUID owner,Entity entity,String instance,String hash,YsmModelProfile profile){
        EffectKey key=new EffectKey(owner,entity.getUuid());EffectState state=modelEffects.get(key);
        if(state!=null && (state.entity()!=entity || !state.instance().equals(instance) || !state.hash().equals(hash))){
            state.effects().close();modelEffects.remove(key);state=null;
        }
        if(state==null){
            if(modelEffects.size()>=256)return null;
            state=new EffectState(new YsmModelEffects(client),entity,instance,hash,profile);modelEffects.put(key,state);
        }
        state.effects().update(entity,instance,hash,profile);return state.effects();
    }
    private static void installFrameEffects(Molang.Context context,Molang.FunctionResolver nativeFunctions,YsmModelEffects effects){
        context.functions((function,arguments)->switch(function) {
            case "ysm.play_sound","ysm.stop_sound","ysm.stop_all_sounds","ysm.particle","ysm.abs_particle" ->
                    effects!=null && Boolean.TRUE.equals(effects.handle(function,arguments));
            default -> nativeFunctions==null?0d:nativeFunctions.call(function,arguments);
        });
    }
    private void tickModelEffects() {
        Map<UUID,RenderBinding> current=new HashMap<>();
        for(RenderBinding binding:animationBindings())current.put(binding.owner(),binding);
        for(EffectKey key:List.copyOf(modelEffects.keySet())) {
            RenderBinding binding=current.get(key.owner());EffectState state=modelEffects.get(key);
            boolean valid=binding!=null && binding.instance().equals(state.instance())
                    && (key.owner().equals(key.entity())?binding.assetHash().equals(state.hash()):
                        modelProfile(key.owner()).components().stream().anyMatch(component->component.hash().equals(state.hash())));
            if(!valid || state.entity().isRemoved() || state.entity().getEntityWorld()!=client.world){
                state.effects().close();modelEffects.remove(key);continue;
            }
            state.effects().update(state.entity(),state.instance(),state.hash(),state.profile());
        }
    }
    private void closeModelEffects(UUID owner) {
        for(EffectKey key:List.copyOf(modelEffects.keySet()))if(key.owner().equals(owner)){
            modelEffects.remove(key).effects().close();
        }
    }
    private void closeModelEffects() {
        modelEffects.values().forEach(state->state.effects().close());modelEffects.clear();
    }
    public Map<String,Object> modelEffectsDiagnostics() {
        Map<String,Object> result=new LinkedHashMap<>();
        modelEffects.forEach((key,state)->result.put(key.owner().equals(key.entity())?key.owner().toString():key.owner()+"/"+key.entity(),state.effects().diagnostics()));
        return Map.copyOf(result);
    }
    public VanillaYsmAnimations.VanillaState vanillaState(UUID owner){
        PlayerEntity player=nativePlayer(owner);if(player==null)return VanillaYsmAnimations.VanillaState.NONE;
        var vehicle=player.getVehicle();Set<String> tags=new LinkedHashSet<>();
        if(vehicle!=null)vehicle.getType().getRegistryEntry().streamTags().limit(128).forEach(tag->tags.add(tag.id().toString()));
        return new VanillaYsmAnimations.VanillaState(player.isDead(),player.hurtTime,player.isUsingRiptide(),player.isSleeping(),player.isSwimming(),
                vanillaItem(player.getMainHandStack()),vanillaItem(player.getOffHandStack()),
                !player.isUsingItem()?VanillaYsmAnimations.Hand.NONE:player.getActiveHand()==Hand.OFF_HAND?VanillaYsmAnimations.Hand.OFF:VanillaYsmAnimations.Hand.MAIN,
                // Native item components can shorten max use time while its remaining ticks are unchanged.
                Math.max(0,player.getItemUseTime()),!player.handSwinging?VanillaYsmAnimations.Hand.NONE:player.preferredHand==Hand.OFF_HAND?VanillaYsmAnimations.Hand.OFF:VanillaYsmAnimations.Hand.MAIN,
                // Vanilla starts a swing at -1 before its first tick; keep the active hand at frame zero.
                Math.max(0,player.handSwingTicks),player.fishHook!=null,vehicle==null?"":Registries.ENTITY_TYPE.getId(vehicle.getType()).toString(),tags,
                vehicle!=null&&vehicle.isAlive(),vehicle instanceof LivingEntity living&&!living.getEquippedStack(EquipmentSlot.SADDLE).isEmpty());
    }
    private static VanillaYsmAnimations.ItemState vanillaItem(ItemStack stack){
        if(stack.isEmpty())return VanillaYsmAnimations.ItemState.EMPTY;
        String id=Registries.ITEM.getId(stack.getItem()).toString();Set<String> tags=new LinkedHashSet<>();
        stack.streamTags().limit(128).forEach(tag->tags.add(tag.id().toString()));
        String kind="";
        for(String tool:List.of("sword","axe","pickaxe","shovel","hoe","shield","crossbow","bow","fishing_rod"))if(tags.contains("minecraft:"+tool+"s")||tags.contains("c:"+tool+"s")||id.equals("minecraft:"+tool)){kind=tool;break;}
        if(kind.isEmpty() && (id.equals("minecraft:trident")||tags.contains("c:tridents")))kind="spear";
        if(kind.isEmpty() && (id.endsWith("_spear")||tags.contains("minecraft:spears")||tags.contains("c:spears")))kind="lance";
        if(kind.isEmpty() && (id.equals("minecraft:splash_potion")||id.equals("minecraft:lingering_potion")))kind="throwable_potion";
        if(kind.isEmpty() && id.equals("minecraft:mace"))kind="mace";
        return new VanillaYsmAnimations.ItemState(id,tags,kind,stack.getUseAction().name().toLowerCase(Locale.ROOT),false,VanillaYsmQueries.charged(stack),ItemStack.hashCode(stack));
    }
    private RenderBinding entityBinding(Binding binding,BbModel model,PlayerEntity player) {
        float delta=client.getRenderTickCounter().getTickProgress(false);
        Vec3d pos=player.getLerpedPos(delta);
        float bodyYaw=MathHelper.lerpAngleDegrees(delta,player.lastBodyYaw,player.bodyYaw);
        float headYaw=MathHelper.lerpAngleDegrees(delta,player.lastHeadYaw,player.headYaw),headPitch=player.getPitch(delta);
        LocalMotionPolicy policy=binding.motion;
        if(binding==localSelf)policy=policy.withActionLock(options.localActionLocked);
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
                player.preferredHand==Hand.OFF_HAND,own && client.interactionManager!=null && client.interactionManager.isBreakingBlock(),own,player.isClimbing(),vanillaState(player.getUuid())),
                policy,binding.localServerLayers,model.animations());
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
    public boolean shouldHideFirstPersonArm() {return !localAppearanceActive() && client.player!=null && shouldHidePlayer(client.player.getUuid());}
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
    /** Pause GPU leases for texture reload while retaining protocol identities and pending downloads. */
    public void releaseAll() {for(Binding binding:bindings.values())failed(binding,"");}

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
    /** Actual protocol/cache/GPU events, with cumulative counters surviving reconnects. */
    public Map<String,Object> serverPushDiagnostics() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("assetMode",serverAssetMode);result.put("capabilities",serverCapabilities);
        result.put("negotiated",pushNegotiated);result.put("generation",generation);
        result.put("unsupportedHandshake",unsupportedHandshake);
        result.put("offersReceived",pushOffersReceived);result.put("cacheHits",pushCacheHits);result.put("cacheMisses",pushCacheMisses);
        result.put("transfersBegun",pushTransfersBegun);result.put("transfersCompleted",pushTransfersCompleted);
        result.put("gpuPrepared",pushGpuPrepared);result.put("renderReadySent",pushReadySent);result.put("renderAcks",pushRenderAcks);
        result.put("assetRequestPacketsSent",pushAssetRequests);result.put("cancelled",pushCancelled);result.put("rejected",pushRejected);
        result.put("activeOffers",pushAuthorization.diagnostics());result.put("transfers",pushAuthorization.receiving());
        result.put("bindings",bindings.values().stream().map(binding->{
            Map<String,Object> values=new LinkedHashMap<>();
            values.put("owner",binding.owner.toString());values.put("instance",binding.instance);values.put("modelId",binding.modelId);
            values.put("hash",binding.hash);values.put("active",binding.active);values.put("readySent",binding.readySent);
            values.put("assetState",binding.assetState);values.put("assetError",binding.assetError);
            values.put("serverAssetStatus",binding.serverAssetStatus);values.put("serverAssetReason",binding.serverAssetReason);
            values.put("serverAssetSource",binding.serverAssetSource);
            return Map.copyOf(values);
        }).toList());
        return Map.copyOf(result);
    }
    public LocalAppearanceSettings localAppearance() {return options.localAppearance();}
    public Path localAppearanceSettingsPath() {return options.path();}

    public List<Action> localModels() {
        try {return localModelLibrary.models().stream().map(entry -> new Action(entry.id(),entry.label())).toList();}
        catch(IOException exception) {
            MEPlayerActionsClient.LOGGER.warn("Cannot list local model directory: {}",exception.toString());
            return List.of(new Action("openysm_default","OpenYSM 默认模型"));
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
        localSelf.localServerLayers=List.of(new Layer("manual",animation,localTick,1,model.animationLoop(animation),2,2));
        return true;
    }
    public void stopLocal() {if(localSelf!=null)localSelf.localServerLayers=List.of();}

    private void invalidateLocalAppearance() {
        if(localSelf!=null)closeModelEffects(localSelf.owner);
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
    public boolean privateAppearanceActive() {return localAppearanceActive();}
    private void ensureLocalAppearance() {
        var settings=localAppearance();
        if(!options.enabled || !settings.enabled() || client.world==null || client.player==null
                || localAppearancePrepared() || localAppearancePending || System.nanoTime()<localAppearanceRetryAfter)return;
        String id=settings.modelId(),texture=localTextureSelection(id);UUID owner=client.player.getUuid();long token=++localAppearanceRequest,epoch=generation;
        localAppearancePending=true;
        try {
            decoder.execute(()->{
                try {
                    var loaded=localModelLibrary.load(id,texture.isEmpty()?null:texture);
                    client.execute(()->{
                        if(token!=localAppearanceRequest || epoch!=generation)return;
                        localAppearancePending=false;
                        if(client.world==null || client.player==null || !owner.equals(client.player.getUuid()) || !localAppearance().enabled())return;
                        install(loaded.hash(),loaded.model());
                        if(!assets.containsKey(loaded.hash()) || !ModelRenderer.has(loaded.hash())) {
                            localAppearanceError="本地模型纹理加载失败";localAppearanceRetryAfter=System.nanoTime()+30*SECOND;return;
                        }
                        localSelf=new Binding(owner,"local-self:"+token,id,loaded.hash());
                        rememberLocalProfile(id,loaded);localSelf.profile=loaded.profile();
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
            if(manual.loop().equals("ONCE") && localTick>manual.startedAtTick()+model.animationLengthTicks(manual.animation())+manual.outTicks())stopLocal();
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
    private static LocalMotionPolicy localMotionPolicy(BbModel model) {return LocalMotionPolicy.forModel(model);}

    public List<Action> actions() {
        Binding own=client.player==null?null:bindings.get(client.player.getUuid());
        if(own!=null)return own.actions;
        BbModel preview=assets.get(previewHash);
        return preview==null?List.of():preview.animations().stream().filter(id->!id.startsWith("parallel")&&!id.startsWith("pre_parallel")).sorted().map(id->new Action(id,previewLabel(id))).toList();
    }
    private static String previewLabel(String id) {
        return com.simmc.meplayeractions.config.AnimationLabels.DEFAULTS.getOrDefault(id,id);
    }
    public List<String> status() {
        long active=bindings.values().stream().filter(b->b.active).count();
        List<String> text=new ArrayList<>();
        text.add("客户端 "+clientVersion()+" · "+(acknowledged?"已连接动作服务器":"等待服务器 / 本地预览")
                +" · 资产模式 "+(serverAssetMode.isEmpty()?"未协商":serverAssetMode));
        text.add("已接管 "+active+" / "+bindings.size()+" 个模型 · 资产 "+assets.size()+" · 模型加载 "+loading.size());
        text.add((options.followServerTimeline?"服务器拖后轨迹 · 缓冲 "+options.interpolationTicks+" tick":"客户端实体即时跟随 · 无额外位置缓冲")
                +" · 本人模型 "+(options.showSelf?"显示":"隐藏"));
        if(localAppearance().enabled())text.add("本地外观："+localAppearance().modelId()+" · "+localAppearanceStatus());
        if(!previewId.isEmpty())text.add("本地预览："+previewId);
        Binding own=client.player==null?null:bindings.get(client.player.getUuid());
        if(own!=null){text.add("模型："+own.modelId+" · "+own.assetState+" · hash "+(own.hash.isEmpty()?"无":own.hash.substring(0,12)));
            if(!own.assetError.isEmpty())text.add("模型错误："+own.assetError);
            if(!own.serverAssetStatus.isEmpty())text.add("服务器资产："+own.serverAssetStatus
                    +(own.serverAssetSource.isEmpty()?"":" · 来源 "+own.serverAssetSource));
            if(!own.serverAssetReason.isEmpty() && !own.serverAssetReason.equals(own.assetError))text.add("服务器资产原因："+own.serverAssetReason);
            text.add("动画："+(options.followServerTimeline?own.layers:own.localMotion.layers()).stream()
                    .map(l->l.layer()+"="+l.animation()).reduce((a,b)->a+" / "+b).orElse("基础姿态"));}
        if(!lastError.isEmpty())text.add(lastError);
        return List.copyOf(text);
    }
    public void preview(String id) {
        if(id.equals("off")){previewRequest++;previewPending=false;previewId="";previewHash="";previewManual="";return;}
        if((!LocalAppearanceSettings.isValidModelId(id) && !Set.of("ysm_02_jk","ysm_01_jk").contains(id)) || client.world==null || client.player==null) {
            notify("进入世界后可预览本地模型；服务器模型需要完整资源包");return;
        }
        if(bindings.containsKey(client.player.getUuid())){notify("请先解除服务器伪装，再使用本地预览");return;}
        if(previewPending || loading.size()>=4){notify("模型正在加载，请稍后");return;}
        long token=++previewRequest;
        previewPending=true;
        long epoch=generation;
        decoder.execute(()->{
            try {
                boolean packSource=!LocalAppearanceSettings.isValidModelId(id);
                var loaded=packSource?loadPackModel(id,null):localModelLibrary.load(id,options.defaultBlueTexture);
                BbModel model=loaded.model();String hash=loaded.hash();
                client.execute(()->{if(epoch==generation && token==previewRequest){previewPending=false;if(packSource)packAssets.add(hash);install(hash,model);if(assets.containsKey(hash)){
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
        YsmModelProfile profile=YsmModelProfile.empty();
        long sequence=-1,lastPacket,lastReady;boolean readySent,active,hidePlayer,unsupported;float scale=1;
        int foodLevel=20;
        String assetState="等待服务器模型",assetError="",serverAssetStatus="",serverAssetReason="",serverAssetSource="";
        Map<String,Double> accessories=Map.of();
        List<Layer> layers=List.of();List<Action> actions=List.of();
        Binding(UUID owner,String instance,String modelId,String hash){this.owner=owner;this.instance=instance;this.modelId=modelId;this.hash=hash;}
    }
}
