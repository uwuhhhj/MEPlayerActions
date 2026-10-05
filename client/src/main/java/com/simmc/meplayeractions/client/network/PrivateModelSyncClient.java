package com.simmc.meplayeractions.client.network;

import com.google.gson.*;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.ClientOptions;
import com.simmc.meplayeractions.client.model.NativeModelBundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Optional server-mediated native model sharing. Offers authorize bytes, leases authorize display. */
public final class PrivateModelSyncClient {
    public static final int PROTOCOL = 1;
    private static final long SECOND = 1_000_000_000L;
    private static final int MAX_REMOTES = 16;
    public interface Host {
        boolean channelAvailable();
        boolean send(JsonObject packet);
        Local local();
        CompletableFuture<byte[]> bundle(String modelId);
        CompletableFuture<byte[]> cached(String hash);
        CompletableFuture<LocalModelLibrary.Loaded> decode(byte[] bundle, String texture);
        void cache(String hash, byte[] bundle);
        void dispatch(Runnable task);
        boolean prepare(Remote remote, LocalModelLibrary.Loaded loaded);
        void remove(UUID owner, UUID generation);
        void state(Remote remote);
        void event(UUID owner, UUID generation, List<Double> args);
    }
    public record Local(UUID owner, String modelId, JsonObject appearance, JsonObject extra) {
        public Local { appearance=appearance.deepCopy(); extra=extra.deepCopy(); }
    }
    public static final class Remote {
        public final UUID owner, generation, offerId;
        public final String hash, kind;
        public final int bytes;
        public JsonObject appearance, extra;
        public long sequence, eventSequence=-1, lastLease;
        public boolean ready, active;
        private byte[] bundle;
        private long decodeRevision;
        private Download download;
        private Remote(UUID owner,UUID generation,UUID offerId,String hash,String kind,int bytes,
                       JsonObject appearance,JsonObject extra,long sequence) {
            this.owner=owner;this.generation=generation;this.offerId=offerId;this.hash=hash;this.kind=kind;this.bytes=bytes;
            this.appearance=appearance;this.extra=extra;this.sequence=sequence;
        }
    }
    private record Archive(byte[] bytes,String kind) { }
    private final Host host;
    private final Map<UUID,Remote> remotes=new LinkedHashMap<>();
    private long epoch,publishRevision,lastHello,lastReceived,lastHeartbeat,lastState,lastEvent,uploadStarted,retryAfter,clockNow;
    private boolean acknowledged,allowedUpload,allowedView,building,committed;
    private int maxPayload=16000,maxBundleBytes=AssetTransfer.MAX_RAW,leaseTicks=100,chunkBytes=8192,uploadIndex;
    private UUID ownOwner,ownGeneration,uploadId;
    private String sourceId="",ownHash="",kind="",status="未协商私人同步",sentState="";
    private byte[] outgoing;
    public PrivateModelSyncClient(Host host) { this.host=Objects.requireNonNull(host); }
    public void reset() {
        epoch++;publishRevision++;remotes.values().forEach(r->host.remove(r.owner,r.generation));remotes.clear();
        acknowledged=allowedUpload=allowedView=building=committed=false;
        sourceId=ownHash=kind=sentState="";outgoing=null;ownOwner=ownGeneration=uploadId=null;uploadIndex=0;
        lastHello=lastReceived=lastHeartbeat=lastState=lastEvent=uploadStarted=retryAfter=0;
        ownEventSequence=-1;status="未协商私人同步";
    }
    public boolean available() { return acknowledged&&allowedUpload&&host.channelAvailable(); }
    public boolean committed() { return available()&&committed; }
    public String status() { return status; }
    public Collection<Remote> remotes() { return List.copyOf(remotes.values()); }
    public Remote remote(UUID owner) { return remotes.get(owner); }
    /** Reject the exact prepared instance; a delayed render failure cannot remove a newer generation. */
    public boolean rejectRemote(UUID owner,UUID generation){
        Remote remote=remotes.get(owner);if(remote==null||!remote.generation.equals(generation))return false;
        status(remote.offerId,remote.hash,"rejected");remove(remote);return true;
    }
    public void stopPublishing() {
        if(acknowledged&&ownGeneration!=null)send(envelope("clear"));
        publishRevision++;building=committed=false;ownOwner=ownGeneration=uploadId=null;sourceId=ownHash=sentState="";outgoing=null;ownEventSequence=-1;
        status=acknowledged?"私人模型仅自己可见":"未协商私人同步";
    }
    public void tick(long now) {
        clockNow=now;
        if(!host.channelAvailable()) { if(acknowledged||ownGeneration!=null||!remotes.isEmpty())reset();return; }
        if(acknowledged&&now-lastReceived>leaseTicks*50_000_000L)reset();
        if(!acknowledged&&now-lastHello>3*SECOND) {
            JsonObject hello=envelope("hello");JsonArray caps=new JsonArray();caps.add("private_models_v1");hello.add("capabilities",caps);
            if(send(hello))lastHello=now;
        }
        if(!acknowledged)return;
        for(Remote remote:List.copyOf(remotes.values())) {
            long timeout=remote.ready?leaseTicks*50_000_000L:remote.download!=null?15*SECOND:60*SECOND;
            if(now-remote.lastLease>timeout)remove(remote);
        }
        Local local=host.local();
        if(local==null||!allowedUpload) { if(ownGeneration!=null)stopPublishing(); }
        else if(!sourceId.equals(local.modelId())&&!building&&now>=retryAfter)beginPublish(local,now);
        if(outgoing!=null&&uploadId!=null&&!committed) {
            int from=uploadIndex*chunkBytes;
            if(from<outgoing.length) {
                JsonObject part=envelope("upload_chunk");part.addProperty("uploadId",uploadId.toString());part.addProperty("index",uploadIndex);
                part.addProperty("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(outgoing,from,Math.min(outgoing.length,from+chunkBytes))));
                if(send(part))uploadIndex++;
            } else {
                JsonObject end=envelope("upload_end");end.addProperty("uploadId",uploadId.toString());
                if(send(end))uploadId=null;
            }
        }
        if(ownGeneration!=null&&!committed&&now-uploadStarted>90*SECOND) {
            stopPublishing();retryAfter=now+10*SECOND;status="私人模型同步超时";
        }
        if(committed&&local!=null&&now-lastState>=SECOND/8) {
            String signature=local.appearance().toString()+local.extra();
            if(!signature.equals(sentState)) {
                JsonObject state=identity("private_state");state.add("appearance",local.appearance().deepCopy());state.add("extra",local.extra().deepCopy());
                if(send(state)){sentState=signature;lastState=now;}
            }
        }
        if(now-lastHeartbeat>=SECOND) {
            JsonObject heartbeat=envelope("private_heartbeat");JsonArray values=new JsonArray();heartbeat.add("bindings",values);
            if(committed&&local!=null)values.add(binding(local.owner(),ownGeneration,ownHash));
            for(Remote remote:remotes.values())if(remote.ready)values.add(binding(remote.owner,remote.generation,remote.hash));
            if(send(heartbeat))lastHeartbeat=now;
        }
    }
    private boolean send(JsonObject packet) {
        byte[] bytes=packet.toString().getBytes(StandardCharsets.UTF_8);
        return bytes.length <= (acknowledged ? maxPayload : ActionPayload.MAX_BYTES) && host.send(packet);
    }
    private void beginPublish(Local local,long now) {
        stopPublishing();building=true;sourceId=local.modelId();ownOwner=local.owner();ownGeneration=UUID.randomUUID();uploadStarted=now;
        long revision=publishRevision;UUID generation=ownGeneration;
        host.bundle(local.modelId()).thenApply(bytes -> {
            try{return new Archive(bytes,NativeModelBundle.validate(bytes).kind());}
            catch(IOException invalid){throw new CompletionException(invalid);}
        }).whenComplete((archive,error)->host.dispatch(()->{
            if(revision!=publishRevision||!generation.equals(ownGeneration))return;
            building=false;
            Local current=host.local();
            if(error!=null||current==null||!current.modelId().equals(sourceId)) { stopPublishing();retryAfter=clockNow+10*SECOND;status="私人模型归档不可用";return; }
            try {
                byte[] bundle=archive.bytes();
                if(bundle.length<1||bundle.length>maxBundleBytes)throw new IOException("Bundle size");
                kind=archive.kind();ownHash=AssetTransfer.hash(bundle);outgoing=bundle;uploadIndex=0;
                JsonObject offer=identity("upload_offer");offer.addProperty("bytes",bundle.length);offer.addProperty("kind",kind);
                offer.add("appearance",current.appearance().deepCopy());
                if(!send(offer))throw new IOException("Channel unavailable");
                status="正在同步私人模型";
            }catch(Exception invalid){stopPublishing();retryAfter=clockNow+10*SECOND;status="私人模型校验失败";}
        }));
    }
    public void event(List<Double> args,long now) {
        if(!committed()||args==null||args.size()>16||now-lastEvent<SECOND/8)return;
        JsonArray values=new JsonArray();for(Double value:args){if(value==null||!Double.isFinite(value))return;values.add(value);}
        JsonObject event=identity("private_event");event.add("args",values);
        if(send(event))lastEvent=now;
    }
    public void receive(byte[] bytes,long now) {
        clockNow=now;JsonObject packet=null;
        try {
            packet=WireJson.decode(bytes,PROTOCOL);String type=WireJson.string(packet,"type",32);
            if(type.equals("hello_ack")) {
                JsonArray caps=packet.getAsJsonArray("capabilities");
                if(caps==null||caps.size()>16||caps.asList().stream().noneMatch(v->v.isJsonPrimitive()&&v.getAsString().equals("private_models_v1")))throw new IOException("Private capability");
                boolean upload=WireJson.bool(packet,"allowedUpload"),view=WireJson.bool(packet,"allowedView");
                int payload=(int)WireJson.integer(packet,"maxPayload",1024,ActionPayload.MAX_BYTES);
                int bundle=(int)WireJson.integer(packet,"maxBundleBytes",1,AssetTransfer.MAX_RAW);
                int lease=(int)WireJson.integer(packet,"leaseTicks",20,1200);
                if(acknowledged)reset();acknowledged=true;allowedUpload=upload;allowedView=view;maxPayload=payload;maxBundleBytes=bundle;leaseTicks=lease;
                status=upload?"私人同步可用，默认仅自己可见":"服务器未开放私人模型同步";
            } else if(!acknowledged)return;
            else switch(type) {
                case "upload_accept" -> {
                    if(!matchesOwn(packet)||outgoing==null)break;
                    UUID id=uuid(packet,"uploadId");int size=(int)WireJson.integer(packet,"chunkBytes",1,8192);
                    if(size*4L/3+512>maxPayload)throw new IOException("Upload chunk budget");
                    uploadId=id;chunkBytes=size;uploadIndex=0;
                }
                case "upload_committed" -> {if(matchesOwn(packet)){committed=true;outgoing=null;uploadId=null;status="私人模型已同步";}}
                case "private_offer" -> offer(packet,now);
                case "asset_begin" -> {
                    Remote remote=offer(packet);if(remote==null||remote.bundle!=null)break;
                    int size=(int)WireJson.integer(packet,"bytes",1,maxBundleBytes),count=(int)WireJson.integer(packet,"chunks",1,16384);
                    if(size!=remote.bytes||count>size)throw new IOException("Offer size");
                    if(remotes.values().stream().filter(r->r.download!=null).count()>=4)throw new IOException("Private transfers busy");
                    remote.download=new Download(size,count);remote.lastLease=now;
                }
                case "asset_chunk" -> {
                    Remote remote=offer(packet);if(remote==null||remote.download==null)break;
                    remote.download.put((int)WireJson.integer(packet,"index",0,16383),WireJson.string(packet,"data",12000));
                    remote.lastLease=now;
                }
                case "asset_end" -> {
                    Remote remote=offer(packet);if(remote==null||remote.download==null)break;
                    byte[] bundle=remote.download.finish(remote.hash);remote.download=null;
                    remote.bundle=bundle;remote.lastLease=now;decode(remote);
                }
                case "private_ack" -> {
                    Remote remote=matching(packet);if(remote!=null&&remote.ready){remote.active=true;remote.lastLease=now;host.state(remote);}
                }
                case "private_remove" -> {
                    UUID owner=uuid(packet,"owner"),generation=uuid(packet,"generation");String hash=WireJson.hash(packet,"hash");
                    if(owner.equals(ownOwner)&&generation.equals(ownGeneration)&&hash.equals(ownHash)){
                        stopPublishing();retryAfter=now+10*SECOND;status="私人发布已由服务器撤销";
                    }
                    Remote remote=remotes.get(owner);
                    if(remote!=null&&remote.generation.equals(generation)&&remote.hash.equals(hash))remove(remote);
                }
                case "private_state" -> {
                    Remote remote=matching(packet);if(remote==null)break;
                    long sequence=WireJson.integer(packet,"sequence",0,9_007_199_254_740_991L);if(sequence<=remote.sequence)break;
                    JsonObject appearance=appearance(packet),extra=extra(packet);String oldTexture=remote.appearance.get("textureId").getAsString();
                    remote.sequence=sequence;remote.appearance=appearance;remote.extra=extra;
                    if(!oldTexture.equals(appearance.get("textureId").getAsString())&&remote.bundle!=null){remote.active=remote.ready=false;remote.lastLease=now;decode(remote);}
                    else host.state(remote);
                }
                case "private_event" -> authorEvent(packet);
                case "heartbeat" -> {
                    JsonArray values=packet.getAsJsonArray("bindings");if(values==null||values.size()>128)throw new IOException("Private heartbeat");
                    for(JsonElement value:values){Remote remote=matching(value.getAsJsonObject());if(remote!=null&&remote.ready&&remote.active)remote.lastLease=now;}
                }
                case "error" -> {
                    String code=WireJson.string(packet,"code",128);
                    boolean publicationFailure=code.startsWith("upload")||code.startsWith("private_upload")
                            ||Set.of("not_allowed","server_model_priority","private_generation_reused","private_storage_busy",
                                "private_bundle_invalid","private_sync_disabled","private_state_not_authorized","private_event_not_authorized").contains(code)
                            ||code.equals("private_appearance_size")&&!committed;
                    if(publicationFailure&&ownGeneration!=null){stopPublishing();retryAfter=now+10*SECOND;}
                    status="私人同步："+code;
                }
                default -> throw new IOException("Unknown private message");
            }
            lastReceived=now;
        }catch(Exception invalid){
            status="私人同步数据已拒绝";
            if(packet!=null)try {
                String type=WireJson.string(packet,"type",32);
                if(type.startsWith("asset_")){Remote failed=offer(packet);if(failed!=null){status(failed.offerId,failed.hash,"rejected");remove(failed);}}
            }catch(Exception ignored){ }
        }
    }
    private void offer(JsonObject packet,long now) throws IOException {
        if(!allowedView)return;
        UUID owner=uuid(packet,"owner"),generation=uuid(packet,"generation"),id=uuid(packet,"offerId");
        String hash=WireJson.hash(packet,"hash"),kind=WireJson.string(packet,"kind",16);
        if(!Set.of("ysm","bbmodel").contains(kind))throw new IOException("Private kind");
        int bytes=(int)WireJson.integer(packet,"bytes",1,maxBundleBytes);
        JsonObject appearance=appearance(packet),extra=extra(packet);long sequence=WireJson.integer(packet,"sequence",0,9_007_199_254_740_991L);
        Remote previous=remotes.get(owner);
        if(previous!=null) {
            if(previous.offerId.equals(id)&&previous.generation.equals(generation)&&previous.hash.equals(hash))return;
            remove(previous);
        }
        if(remotes.size()>=MAX_REMOTES) {status(id,hash,"rejected");return;}
        Remote remote=new Remote(owner,generation,id,hash,kind,bytes,appearance,extra,sequence);remote.lastLease=now;remotes.put(owner,remote);
        long revision=epoch;
        host.cached(hash).whenComplete((bundle,error)->host.dispatch(()->{
            if(!current(remote,revision))return;
            if(error==null&&bundle!=null&&bundle.length==bytes&&hash.equals(AssetTransfer.hash(bundle))) {
                remote.bundle=bundle;status(id,hash,"cached");decode(remote);
            } else status(id,hash,"missing");
        }));
    }
    private void decode(Remote remote) {
        long revision=epoch,decodeRevision=++remote.decodeRevision;
        host.decode(remote.bundle,remote.appearance.get("textureId").getAsString()).whenComplete((loaded,error)->host.dispatch(()->{
            if(!current(remote,revision)||decodeRevision!=remote.decodeRevision)return;
            if(error!=null||loaded==null||!host.prepare(remote,loaded)){status(remote.offerId,remote.hash,"rejected");remove(remote);return;}
            remote.ready=true;remote.lastLease=clockNow;host.state(remote);JsonObject ready=binding(remote.owner,remote.generation,remote.hash);ready.addProperty("protocol",PROTOCOL);ready.addProperty("type","private_ready");send(ready);
            host.cache(remote.hash,remote.bundle);
        }));
    }
    private void authorEvent(JsonObject packet) throws IOException {
        UUID owner=uuid(packet,"owner"),generation=uuid(packet,"generation");String hash=WireJson.hash(packet,"hash");
        long sequence=WireJson.integer(packet,"sequence",0,9_007_199_254_740_991L);
        JsonArray args=packet.getAsJsonArray("args");if(args==null||args.size()>16)throw new IOException("Private event args");
        List<Double> values=new ArrayList<>();for(JsonElement value:args){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!Double.isFinite(value.getAsDouble()))throw new IOException("Private event value");values.add(value.getAsDouble());}
        Local local=host.local();
        if(local!=null&&local.owner().equals(owner)&&committed&&generation.equals(ownGeneration)&&hash.equals(ownHash)) {
            // Server-authoritative sequence is shared with state; keep a separate event cursor.
            if(sequence<=ownEventSequence)return;ownEventSequence=sequence;host.event(owner,generation,List.copyOf(values));return;
        }
        Remote remote=matching(packet);if(remote==null||!remote.active||sequence<=remote.eventSequence)return;
        remote.eventSequence=sequence;host.event(owner,generation,List.copyOf(values));
    }
    private long ownEventSequence=-1;
    private boolean current(Remote remote,long revision){return revision==epoch&&remotes.get(remote.owner)==remote;}
    private Remote matching(JsonObject packet) {
        Remote remote=remotes.get(uuid(packet,"owner"));return remote!=null&&remote.generation.equals(uuid(packet,"generation"))&&remote.hash.equals(WireJson.hash(packet,"hash"))?remote:null;
    }
    private Remote offer(JsonObject packet) {
        UUID id=uuid(packet,"offerId");String hash=WireJson.hash(packet,"hash");
        return remotes.values().stream().filter(r->r.offerId.equals(id)&&r.hash.equals(hash)).findFirst().orElse(null);
    }
    private void remove(Remote remote){if(remotes.remove(remote.owner,remote)){remote.decodeRevision++;remote.download=null;remote.bundle=null;host.remove(remote.owner,remote.generation);}}
    private boolean matchesOwn(JsonObject packet){return ownGeneration!=null&&ownGeneration.equals(uuid(packet,"generation"))&&ownHash.equals(WireJson.hash(packet,"hash"));}
    private void status(UUID offer,String hash,String state){JsonObject value=envelope("private_status");value.addProperty("offerId",offer.toString());value.addProperty("hash",hash);value.addProperty("status",state);send(value);}
    private JsonObject identity(String type){JsonObject packet=envelope(type);packet.addProperty("generation",ownGeneration.toString());packet.addProperty("hash",ownHash);return packet;}
    public static JsonObject envelope(String type){JsonObject packet=new JsonObject();packet.addProperty("protocol",PROTOCOL);packet.addProperty("type",type);return packet;}
    private static JsonObject binding(UUID owner,UUID generation,String hash){JsonObject value=new JsonObject();value.addProperty("owner",owner.toString());value.addProperty("generation",generation.toString());value.addProperty("hash",hash);return value;}
    private static UUID uuid(JsonObject packet,String key){return UUID.fromString(WireJson.string(packet,key,36));}
    private static JsonObject appearance(JsonObject packet) {
        JsonObject value=packet.getAsJsonObject("appearance");if(value==null)throw new IllegalArgumentException("Private appearance");
        WireJson.number(value,"scale",.05,8);for(String key:List.of("offsetX","offsetY","offsetZ"))WireJson.number(value,key,-32,32);
        WireJson.string(value,"textureId",128);
        JsonObject vars=value.getAsJsonObject("variables"),radios=value.getAsJsonObject("radioSelections");
        if(vars==null||radios==null||vars.size()>128||radios.size()>128)throw new IllegalArgumentException("Private variables");
        int roaming=0;
        for(String key:vars.keySet()){
            if(!key.equals(ClientOptions.normalizeModelVariable(key)))throw new IllegalArgumentException("Private variable name");
            if(key.startsWith("variable.roaming.")&&(!ClientOptions.isRoamingVariable(key)||++roaming>ClientOptions.MAX_ROAMING_VARIABLES))
                throw new IllegalArgumentException("Private roaming variable");
            WireJson.number(vars,key,-1_000_000,1_000_000);
        }
        for(String key:radios.keySet()){if(key.isBlank()||key.length()>128||key.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Private radio");WireJson.integer(radios,key,0,255);}
        return value.deepCopy();
    }
    private static JsonObject extra(JsonObject packet) {
        if(!packet.has("extra")){JsonObject value=new JsonObject();value.addProperty("id","");value.addProperty("loop","ONCE");value.addProperty("locked",false);value.addProperty("sequence",0);return value;}
        JsonObject value=packet.getAsJsonObject("extra");String id=WireJson.string(value,"id",128),loop=WireJson.string(value,"loop",8);
        if(!id.isEmpty()&&!id.matches("[a-zA-Z0-9_.:/-]{1,128}")||!Set.of("ONCE","LOOP","HOLD").contains(loop))throw new IllegalArgumentException("Private extra");
        WireJson.bool(value,"locked");WireJson.integer(value,"sequence",0,9_007_199_254_740_991L);return value.deepCopy();
    }
    private static final class Download {
        final int expected;final byte[][] chunks;int received;
        Download(int bytes,int count){expected=bytes;chunks=new byte[count][];}
        void put(int index,String encoded)throws IOException{
            if(index<0||index>=chunks.length||encoded.length()>12000)throw new IOException("Private chunk");
            byte[] value=Base64.getDecoder().decode(encoded);if(value.length<1||value.length>9000)throw new IOException("Private chunk size");
            if(chunks[index]!=null){if(!Arrays.equals(chunks[index],value))throw new IOException("Conflicting private chunk");return;}
            if(received+value.length>expected)throw new IOException("Private overflow");chunks[index]=value;received+=value.length;
        }
        byte[] finish(String hash)throws IOException{
            if(received!=expected||Arrays.stream(chunks).anyMatch(Objects::isNull))throw new IOException("Incomplete private bundle");
            ByteArrayOutputStream out=new ByteArrayOutputStream(expected);for(byte[] part:chunks)out.write(part);byte[] result=out.toByteArray();
            if(!hash.equals(AssetTransfer.hash(result)))throw new IOException("Private bundle hash");return result;
        }
    }
}
