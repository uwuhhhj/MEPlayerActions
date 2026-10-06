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
    private static final long DELETE_TIMEOUT = 90*SECOND;
    private static final int MAX_REMOTES = 16;
    public interface Host {
        boolean channelAvailable();
        boolean send(JsonObject packet);
        Local local();
        CompletableFuture<byte[]> bundle(String modelId);
        /** Production hosts run both source reading and ZIP identity validation on their bounded worker. */
        default CompletableFuture<SourceIdentity> sourceIdentity(String modelId) {
            return bundle(modelId).thenApply(bytes->{try{return new SourceIdentity(AssetTransfer.hash(bytes),NativeModelBundle.validate(bytes).kind(),bytes.length);}
                catch(IOException invalid){throw new CompletionException(invalid);}});
        }
        CompletableFuture<byte[]> cached(String hash);
        CompletableFuture<LocalModelLibrary.Loaded> decode(byte[] bundle, String texture);
        void cache(String hash, byte[] bundle);
        void dispatch(Runnable task);
        boolean prepare(Remote remote, LocalModelLibrary.Loaded loaded);
        void remove(UUID owner, UUID generation);
        void state(Remote remote);
        void event(UUID owner, UUID generation, List<Double> args);
        /** A confirmed deletion of the active publication stops sharing, while local source files remain. */
        default void savedModelDeleted(String modelId,String hash) { }
    }
    public record Local(UUID owner, String modelId, JsonObject appearance, JsonObject extra) {
        public Local { appearance=appearance.deepCopy(); extra=extra.deepCopy(); }
    }
    public enum UploadPhase { NOT_UPLOADED, BUILDING, WAITING_APPROVAL, UPLOADING, VALIDATING, PUBLISHED, UPLOADED, FAILED }
    /** Saved status requires the owner's current server directory and matching local source bytes. */
    public record UploadState(String modelId, UploadPhase phase, String hash, int totalBytes, int sentBytes,
                              boolean published, String message) {
        public boolean uploaded() { return phase==UploadPhase.UPLOADED; }
        public boolean inProgress() { return switch(phase) {
            case BUILDING, WAITING_APPROVAL, UPLOADING, VALIDATING -> true;
            default -> false;
        }; }
    }
    public static final class Remote {
        public final UUID owner, generation, offerId;
        public final String hash, kind;
        public final int bytes;
        public JsonObject appearance, extra;
        public long sequence, eventSequence=-1, lastLease;
        public boolean ready, active, flying;
        private byte[] bundle;
        private long decodeRevision;
        private String pendingStatus="";
        private boolean statusSent;
        private long lastStatusAttempt=-1,lastReadyAttempt=-1;
        private Download download;
        private Remote(UUID owner,UUID generation,UUID offerId,String hash,String kind,int bytes,
                       JsonObject appearance,JsonObject extra,long sequence,boolean flying) {
            this.owner=owner;this.generation=generation;this.offerId=offerId;this.hash=hash;this.kind=kind;this.bytes=bytes;
            this.appearance=appearance;this.extra=extra;this.sequence=sequence;this.flying=flying;
        }
    }
    private record Archive(byte[] bytes,String kind) { }
    public record SourceIdentity(String hash,String kind,int bytes) { }
    private final Host host;
    private final Map<UUID,Remote> remotes=new LinkedHashMap<>();
    private final Map<String,UploadState> sessionUploads=new LinkedHashMap<>();
    private final PrivateUploadCatalogSnapshot uploadCatalog=new PrivateUploadCatalogSnapshot();
    private final Map<String,SourceIdentity> sourceIdentities=new HashMap<>();
    private final ArrayDeque<String> sourceChecks=new ArrayDeque<>();
    private long sourceRevision;
    private boolean catalogueNegotiated,legacyHello,sourceChecking;
    private JsonObject deleteRequest;
    private String deletingId="",deletingHash="";
    private UUID deletePublicationGeneration;
    private UUID uploadCatalogToken;
    private long deleteSequence,deleteStartedAt,deletePublicationSerial,deleteCatalogRevision,publicationSerial;
    private boolean deleteSent;
    private long lastDeleteAttempt=-1;
    private final Map<String,String> deleteStatuses=new LinkedHashMap<>();
    private record DeleteReceipt(String modelId,String hash,UUID publicationGeneration,long publicationSerial,long catalogRevision) { }
    private final Map<UUID,DeleteReceipt> lateDeletes=new LinkedHashMap<>();
    private long epoch,publishRevision,lastHello,lastReceived,lastHeartbeat,lastState,lastEvent,uploadStarted,retryAfter,clockNow;
    private boolean acknowledged,allowedUpload,allowedView,building,committed;
    private int maxPayload=16000,maxBundleBytes=AssetTransfer.MAX_RAW,leaseTicks=100,chunkBytes=8192,uploadIndex;
    private UUID ownOwner,ownGeneration,uploadId;
    private String sourceId="",ownHash="",kind="",status="未协商私人同步",sentState="";
    private byte[] outgoing;
    private int publicationBytes,uploadedBytes;
    private JsonObject outgoingOffer;
    private long lastUploadOfferAttempt=-1,lastUploadEndAttempt=-1;
    private boolean uploadEndSent;
    private final ArrayDeque<Long> controlWindow=new ArrayDeque<>();
    private double controlTokens=8;
    private long controlRefill=-1;
    private int controlRotation;
    public PrivateModelSyncClient(Host host) { this.host=Objects.requireNonNull(host); }
    public void reset() {
        epoch++;publishRevision++;remotes.values().forEach(r->host.remove(r.owner,r.generation));remotes.clear();
        acknowledged=allowedUpload=allowedView=building=committed=false;sessionUploads.clear();
        catalogueNegotiated=legacyHello=sourceChecking=false;sourceRevision++;uploadCatalog.reset();sourceIdentities.clear();sourceChecks.clear();
        clearDelete();uploadCatalogToken=null;deleteSequence=publicationSerial=0;deleteStatuses.clear();lateDeletes.clear();
        sourceId=ownHash=kind=sentState="";outgoing=null;ownOwner=ownGeneration=uploadId=null;uploadIndex=0;
        publicationBytes=uploadedBytes=0;
        outgoingOffer=null;lastUploadOfferAttempt=lastUploadEndAttempt=-1;uploadEndSent=false;
        controlWindow.clear();controlTokens=8;controlRefill=-1;controlRotation=0;
        lastHello=lastReceived=lastHeartbeat=lastState=lastEvent=uploadStarted=retryAfter=0;
        ownEventSequence=-1;status="未协商私人同步";
    }
    public boolean available() { return acknowledged&&allowedUpload&&host.channelAvailable(); }
    public boolean committed() { return available()&&committed; }
    public boolean canView() { return acknowledged&&allowedView&&host.channelAvailable(); }
    public String status() {
        if(outgoing!=null&&publicationBytes>0&&uploadId!=null&&!uploadEndSent)
            return "正在上传私人模型："+(uploadedBytes*100L/publicationBytes)+"% · "+size(publicationBytes);
        return status;
    }
    public int publicationBytes() { return publicationBytes; }
    public int uploadedBytes() { return uploadedBytes; }
    /** Read-only gallery status. Metadata verification is queued separately and never publishes. */
    public UploadState uploadState(String modelId) {
        String id=modelId==null?"":modelId;
        if(!acknowledged||!host.channelAvailable())return notUploaded(id);
        if(ownGeneration!=null&&sourceId.equals(id)) {
            UploadPhase phase=committed?(savedIdentity(id)!=null?UploadPhase.UPLOADED:UploadPhase.PUBLISHED):building?UploadPhase.BUILDING
                    :uploadId==null?UploadPhase.WAITING_APPROVAL:uploadEndSent?UploadPhase.VALIDATING:UploadPhase.UPLOADING;
            return new UploadState(id,phase,ownHash,publicationBytes,uploadedBytes,committed&&currentLocal(),status());
        }
        SourceIdentity saved=savedIdentity(id);
        if(saved!=null)return new UploadState(id,UploadPhase.UPLOADED,saved.hash(),saved.bytes(),0,false,"服务器已保存 · 本机内容一致");
        return sessionUploads.getOrDefault(id,notUploaded(id));
    }
    /** The server's owner-only directory does not confer permission to download or publish. */
    public Set<String> uploadedModelIds() {
        if(!acknowledged||!host.channelAvailable())return Set.of();
        Set<String> ids=new LinkedHashSet<>();
        for(var entry:uploadCatalog.models())if(savedIdentity(entry.modelId())!=null)ids.add(entry.modelId());
        return Collections.unmodifiableSet(ids);
    }
    /** Recheck the saved directory after the host clears its source cache; edited IDs cannot retain a badge. */
    public void invalidateSources() { stopPublishing();sessionUploads.clear();sourceRevision++;sourceChecking=false;sourceIdentities.clear();queueSourceChecks(); }
    public String uploadedDirectoryStatus() {
        if(!acknowledged||!host.channelAvailable())return "等待服务器已上传目录";
        if(!catalogueNegotiated)return "当前服务器未提供已上传目录";
        if(!allowedUpload)return "没有私人模型上传权限";
        if(!uploadCatalog.ready())return "正在同步服务器已上传目录";
        if(!uploadCatalog.available())return "服务器未开放已上传目录（上传权限或资源缓存不可用）";
        if(sourceChecking||!sourceChecks.isEmpty())return "正在核对本机模型与服务器保存内容";
        return "服务器已保存 "+uploadCatalog.models().size()+" 个本人模型 · 本机匹配 "+uploadedModelIds().size()+" 个";
    }
    public boolean uploadCatalogReady(){return catalogueNegotiated&&allowedUpload&&host.channelAvailable()&&uploadCatalog.available()&&!sourceChecking&&sourceChecks.isEmpty();}
    public String uploadCatalogStatus(){return uploadedDirectoryStatus();}
    /** Includes server-saved entries whose local file is missing or has since changed. */
    public List<PrivateUploadCatalogSnapshot.Model> uploadedModels(){return catalogueNegotiated&&allowedUpload&&host.channelAvailable()?uploadCatalog.models():List.of();}
    public boolean deletingUploadedModel(String id){return deleteRequest!=null&&deletingId.equals(id);}
    public String uploadDeleteStatus(String id){return deletingUploadedModel(id)?"正在等待服务器确认删除":deleteStatuses.getOrDefault(id,"");}
    public boolean requestDeleteUploadedModel(String id) {
        var entry=uploadedModels().stream().filter(model->model.modelId().equals(id)).findFirst().orElse(null);
        return entry!=null&&requestDeleteUploadedModel(entry);
    }
    /** A confirmation targets the saved version originally displayed, rather than a newer same-ID replacement. */
    public boolean requestDeleteUploadedModel(PrivateUploadCatalogSnapshot.Model expected) {
        if(expected==null)return false;String id=expected.modelId();
        if(!acknowledged||!catalogueNegotiated||uploadCatalogToken==null||!allowedUpload||!uploadCatalog.available()||deleteRequest!=null||deleteSequence>=9_007_199_254_740_991L
                ||ownGeneration!=null&&!committed&&sourceId.equals(id))return false;
        var entry=uploadedModels().stream().filter(model->model.modelId().equals(id)).findFirst().orElse(null);if(entry==null)return false;
        if(!entry.equals(expected)){rememberDeleteStatus(id,"服务器保存内容已变化，请重新确认删除");return false;}
        JsonObject request=envelope("upload_delete");request.addProperty("requestId",UUID.randomUUID().toString());request.addProperty("modelId",id);request.addProperty("hash",entry.hash());
        request.addProperty("catalogToken",uploadCatalogToken.toString());request.addProperty("requestSequence",++deleteSequence);
        request.addProperty("kind",entry.kind());request.addProperty("bytes",entry.bytes());deleteRequest=request;deletingId=id;deletingHash=entry.hash();lastDeleteAttempt=-1;
        deletePublicationGeneration=committed&&sourceId.equals(id)?ownGeneration:null;
        deleteStartedAt=clockNow;deleteSent=false;deletePublicationSerial=publicationSerial;deleteCatalogRevision=uploadCatalog.revision();
        deleteStatuses.remove(id);return true;
    }
    private DeleteReceipt deleteReceipt(){return new DeleteReceipt(deletingId,deletingHash,deletePublicationGeneration,deletePublicationSerial,deleteCatalogRevision);}
    private void clearDelete(){deleteRequest=null;deletingId=deletingHash="";deletePublicationGeneration=null;lastDeleteAttempt=-1;deleteStartedAt=deletePublicationSerial=deleteCatalogRevision=0;deleteSent=false;}
    private void expireDelete() {
        if(deleteSent) {
            lateDeletes.put(UUID.fromString(deleteRequest.get("requestId").getAsString()),deleteReceipt());
            while(lateDeletes.size()>16)lateDeletes.remove(lateDeletes.keySet().iterator().next());
        }
        rememberDeleteStatus(deletingId,"未确认删除，请刷新目录或重试");clearDelete();
    }
    private SourceIdentity savedIdentity(String id) {
        if(!catalogueNegotiated||!allowedUpload||!uploadCatalog.available())return null;
        SourceIdentity local=sourceIdentities.get(id);if(local==null)return null;
        return uploadCatalog.models().stream().anyMatch(entry->entry.modelId().equals(id)&&entry.hash().equals(local.hash())
                &&entry.kind().equals(local.kind())&&entry.bytes()==local.bytes())?local:null;
    }
    private void queueSourceChecks() {
        sourceChecks.clear();
        if(catalogueNegotiated&&allowedUpload)for(var entry:uploadCatalog.models())if(!sourceIdentities.containsKey(entry.modelId()))sourceChecks.addLast(entry.modelId());
        checkNextSource();
    }
    private void checkNextSource() {
        if(sourceChecking||sourceChecks.isEmpty()||!acknowledged)return;
        String id=sourceChecks.removeFirst();sourceChecking=true;long revision=sourceRevision,connection=epoch;
        CompletableFuture<SourceIdentity> reading;
        try {reading=host.sourceIdentity(id);}catch(RuntimeException failed){reading=CompletableFuture.failedFuture(failed);}
        reading.whenComplete((identity,error)->host.dispatch(()->{
                if(connection!=epoch||revision!=sourceRevision)return;
                sourceChecking=false;if(error==null)rememberSource(id,identity);checkNextSource();
            }));
    }
    private void rememberSource(String id,SourceIdentity identity) {
        sourceIdentities.put(id,identity);
        while(sourceIdentities.size()>PrivateUploadCatalogSnapshot.MAX_MODELS) {
            String stale=sourceIdentities.keySet().stream().filter(key->!key.equals(id)&&uploadCatalog.models().stream().noneMatch(model->model.modelId().equals(key))).findFirst()
                    .orElseGet(()->sourceIdentities.keySet().stream().filter(key->!key.equals(id)).findFirst().orElse(id));
            sourceIdentities.remove(stale);
        }
    }
    private static UploadState notUploaded(String id) { return new UploadState(id,UploadPhase.NOT_UPLOADED,"",0,0,false,"尚未上传至当前服务器"); }
    private void rememberUploadState(UploadState state) {
        sessionUploads.put(state.modelId(),state);
        while(sessionUploads.size()>160)sessionUploads.remove(sessionUploads.keySet().iterator().next());
    }
    public Collection<Remote> remotes() { return List.copyOf(remotes.values()); }
    public Remote remote(UUID owner) { return remotes.get(owner); }
    /** Server-authoritative creative flight is not present in vanilla remote player abilities. */
    public boolean isFlying(UUID owner) {Remote remote=remotes.get(owner);return remote!=null && remote.active && remote.flying;}
    /** Reject the exact prepared instance; a delayed render failure cannot remove a newer generation. */
    public boolean rejectRemote(UUID owner,UUID generation){
        Remote remote=remotes.get(owner);if(remote==null||!remote.generation.equals(generation))return false;
        status(remote.offerId,remote.hash,"rejected");remove(remote);return true;
    }
    public void stopPublishing() {
        if(ownGeneration!=null) {
            sessionUploads.remove(sourceId);
        }
        if(acknowledged&&ownGeneration!=null)send(envelope("clear"));
        publishRevision++;building=committed=false;ownOwner=ownGeneration=uploadId=null;sourceId=ownHash=sentState="";outgoing=null;ownEventSequence=-1;
        publicationBytes=uploadedBytes=0;
        outgoingOffer=null;lastUploadOfferAttempt=lastUploadEndAttempt=-1;uploadEndSent=false;
        status=acknowledged?"私人模型仅自己可见":"未协商私人同步";
    }
    private void failPublishing(String message) {
        UploadState failure=new UploadState(sourceId,UploadPhase.FAILED,ownHash,publicationBytes,uploadedBytes,false,message);
        stopPublishing();if(!failure.modelId().isEmpty())rememberUploadState(failure);status=message;
    }
    public void tick(long now) {
        clockNow=now;
        if(!host.channelAvailable()) { if(acknowledged||ownGeneration!=null||!remotes.isEmpty())reset();return; }
        if(acknowledged&&now-lastReceived>leaseTicks*50_000_000L)reset();
        if(!acknowledged&&now-lastHello>3*SECOND) {
            JsonObject hello=envelope("hello");JsonArray caps=new JsonArray();caps.add("private_models_v1");if(!legacyHello)caps.add(PrivateUploadCatalogSnapshot.CAPABILITY);hello.add("capabilities",caps);
            if(send(hello))lastHello=now;
        }
        if(!acknowledged)return;
        if(deleteRequest!=null&&now-deleteStartedAt>=DELETE_TIMEOUT)expireDelete();
        if(deleteRequest!=null&&allowedUpload&&(lastDeleteAttempt<0||now-lastDeleteAttempt>=SECOND)) {
            Boolean sent=sendControl(deleteRequest,now);if(sent!=null){lastDeleteAttempt=now;deleteSent|=sent;}
        }
        for(Remote remote:List.copyOf(remotes.values())) {
            long timeout=remote.active?leaseTicks*50_000_000L:remote.download!=null?15*SECOND:60*SECOND;
            if(now-remote.lastLease>timeout)remove(remote);
        }
        Local local=host.local();
        if(local==null||!allowedUpload) { if(ownGeneration!=null)stopPublishing(); }
        else if(!sourceId.equals(local.modelId())&&!building&&now>=retryAfter&&(!deletingUploadedModel(local.modelId())))beginPublish(local,now);
        if(outgoing!=null&&!committed) {
            if(uploadId==null&&outgoingOffer!=null) {
                if(lastUploadOfferAttempt<0||now-lastUploadOfferAttempt>=SECOND){Boolean sent=sendControl(outgoingOffer,now);if(sent!=null)lastUploadOfferAttempt=now;}
            } else if(uploadId!=null) {
                int from=uploadIndex*chunkBytes;
                if(from<outgoing.length) {
                    JsonObject part=envelope("upload_chunk");part.addProperty("uploadId",uploadId.toString());part.addProperty("index",uploadIndex);
                    part.addProperty("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(outgoing,from,Math.min(outgoing.length,from+chunkBytes))));
                    if(send(part)){uploadIndex++;uploadedBytes=Math.min(outgoing.length,uploadIndex*chunkBytes);}
                } else if(lastUploadEndAttempt<0||now-lastUploadEndAttempt>=SECOND) {
                    JsonObject end=envelope("upload_end");end.addProperty("uploadId",uploadId.toString());
                    Boolean sent=sendControl(end,now);
                    if(sent!=null){lastUploadEndAttempt=now;if(sent){uploadEndSent=true;status="上传结束，等待服务器校验 · "+size(publicationBytes);}}
                }
            }
        }
        List<Remote> controls=List.copyOf(remotes.values());
        if(!controls.isEmpty()) {
            int start=controlRotation%controls.size();controlRotation=(start+1)%controls.size();
            for(int i=0;i<controls.size();i++)flushControl(controls.get((start+i)%controls.size()),now);
        }
        if(ownGeneration!=null&&!committed&&now-uploadStarted>90*SECOND) {
            failPublishing("私人模型同步超时");retryAfter=now+10*SECOND;
        }
        if(committed&&local!=null&&now-lastState>=SECOND/8) {
            String signature=local.appearance().toString()+local.extra();
            if(!signature.equals(sentState)||now-lastState>=2*SECOND) {
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
    /** Null defers the packet without starting its retry clock; authorized chunks keep their own cadence. */
    private Boolean sendControl(JsonObject packet,long now) {
        if(controlRefill<0)controlRefill=now;
        if(now>controlRefill){controlTokens=Math.min(8,controlTokens+(now-controlRefill)*16d/SECOND);controlRefill=now;}
        while(!controlWindow.isEmpty()&&now-controlWindow.getFirst()>=SECOND)controlWindow.removeFirst();
        if(controlTokens<1||controlWindow.size()>=16)return null;
        controlTokens--;controlWindow.addLast(now);return send(packet);
    }
    private void beginPublish(Local local,long now) {
        stopPublishing();publicationSerial++;building=true;sourceId=local.modelId();ownOwner=local.owner();ownGeneration=UUID.randomUUID();uploadStarted=now;status="正在归档私人模型";
        sessionUploads.remove(sourceId);
        long revision=publishRevision;UUID generation=ownGeneration;
        host.bundle(local.modelId()).thenApply(bytes -> {
            try{return new Archive(bytes,NativeModelBundle.validate(bytes).kind());}
            catch(IOException invalid){throw new CompletionException(invalid);}
        }).whenComplete((archive,error)->host.dispatch(()->{
            if(revision!=publishRevision||!generation.equals(ownGeneration))return;
            building=false;
            Local current=host.local();
            if(error!=null||current==null||!current.modelId().equals(sourceId)) { failPublishing("私人模型归档不可用");retryAfter=clockNow+10*SECOND;return; }
            try {
                byte[] bundle=archive.bytes();
                if(bundle.length<1||bundle.length>maxBundleBytes)throw new IOException("Bundle size");
                kind=archive.kind();ownHash=AssetTransfer.hash(bundle);outgoing=bundle;uploadIndex=0;publicationBytes=bundle.length;uploadedBytes=0;
                rememberSource(sourceId,new SourceIdentity(ownHash,kind,publicationBytes));
                JsonObject offer=identity("upload_offer");offer.addProperty("bytes",bundle.length);offer.addProperty("kind",kind);
                if(catalogueNegotiated)offer.addProperty("modelId",sourceId);
                offer.add("appearance",current.appearance().deepCopy());
                if(offer.toString().getBytes(StandardCharsets.UTF_8).length>maxPayload)throw new IOException("Appearance payload size");
                outgoingOffer=offer;Boolean sent=sendControl(offer,clockNow);if(sent!=null)lastUploadOfferAttempt=clockNow;
                status="等待服务器授权上传 · "+size(publicationBytes);
            }catch(Exception invalid){failPublishing("私人模型不可上传：归档无效或模型、参数超过服务器限制");retryAfter=clockNow+10*SECOND;}
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
                boolean catalogue=caps.asList().stream().anyMatch(v->v.isJsonPrimitive()&&v.getAsString().equals(PrivateUploadCatalogSnapshot.CAPABILITY));
                UUID catalogToken=catalogue?uuid(packet,"uploadCatalogToken"):null;
                if(acknowledged)reset();acknowledged=true;allowedUpload=upload;allowedView=view;maxPayload=payload;maxBundleBytes=bundle;leaseTicks=lease;catalogueNegotiated=catalogue;uploadCatalogToken=catalogToken;
                status=upload?"可分享私人模型；需要主动开启分享":"没有私人模型上传权限";
            } else if(!acknowledged) {
                if(type.equals("error")&&!legacyHello&&WireJson.string(packet,"code",128).equals("invalid_private_payload")){legacyHello=true;lastHello=0;}
                return;
            }
            else switch(type) {
                case "upload_catalog" -> {if(catalogueNegotiated&&uploadCatalog.accept(packet))queueSourceChecks();}
                case "upload_deleted" -> {
                    UUID request=uuid(packet,"requestId");boolean current=deleteRequest!=null&&request.toString().equals(deleteRequest.get("requestId").getAsString());
                    DeleteReceipt receipt=current?deleteReceipt():lateDeletes.get(request);
                    if(receipt==null||!WireJson.string(packet,"modelId",128).equals(receipt.modelId())||!WireJson.hash(packet,"hash").equals(receipt.hash()))break;
                    boolean active=receipt.publicationGeneration()!=null&&receipt.publicationSerial()==publicationSerial
                            &&(ownGeneration==null||receipt.publicationGeneration().equals(ownGeneration));
                    if(uploadCatalog.revision()==receipt.catalogRevision())uploadCatalog.deleted(receipt.modelId(),receipt.hash());
                    if(active){stopPublishing();host.savedModelDeleted(receipt.modelId(),receipt.hash());}
                    if(current)clearDelete();else lateDeletes.remove(request);
                    rememberDeleteStatus(receipt.modelId(),"服务器已删除保存的私人模型；本地文件保留");
                }
                case "upload_delete_failed" -> {
                    UUID request=uuid(packet,"requestId");boolean current=deleteRequest!=null&&request.toString().equals(deleteRequest.get("requestId").getAsString());
                    DeleteReceipt receipt=current?deleteReceipt():lateDeletes.get(request);if(receipt==null)break;
                    String code=WireJson.string(packet,"code",128);rememberDeleteStatus(receipt.modelId(),switch(code){
                        case "private_delete_denied"->"服务器拒绝删除：权限或缓存不可用";
                        case "private_delete_busy"->"资源正在上传或存档仍在校验，请稍后再删除";
                        case "private_delete_not_found"->"服务器保存内容已变化，等待目录同步";
                        default->"服务器未完成删除，请重试";
                    });if(current)clearDelete();else lateDeletes.remove(request);
                }
                case "upload_accept" -> {
                    if(!matchesOwn(packet)||outgoing==null)break;
                    UUID id=uuid(packet,"uploadId");int size=(int)WireJson.integer(packet,"chunkBytes",1,8192);
                    if(size*4L/3+512>maxPayload)throw new IOException("Upload chunk budget");
                    if(uploadId!=null){if(!uploadId.equals(id)||chunkBytes!=size)throw new IOException("Conflicting upload accept");break;}
                    uploadId=id;chunkBytes=size;uploadIndex=0;
                }
                case "upload_committed" -> {if(matchesOwn(packet)){committed=true;outgoing=null;outgoingOffer=null;uploadId=null;status="私人模型已分享 · "+(uploadedBytes==0?"复用服务器缓存 · ":"")+size(publicationBytes);
                    // A publication ACK is separate from a durable, owner-scoped directory entry.
                }}
                case "private_offer" -> offer(packet,now);
                case "asset_begin" -> {
                    Remote remote=offer(packet);if(remote==null||remote.bundle!=null)break;
                    int size=(int)WireJson.integer(packet,"bytes",1,maxBundleBytes),count=(int)WireJson.integer(packet,"chunks",1,16384);
                    if(size!=remote.bytes||count>size)throw new IOException("Offer size");
                    if(remotes.values().stream().filter(r->r.download!=null).count()>=4)throw new IOException("Private transfers busy");
                    remote.download=new Download(size,count);remote.lastLease=now;remote.pendingStatus="";remote.statusSent=true;
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
                    Remote remote=matching(packet);if(remote!=null&&remote.ready){remote.active=true;remote.lastLease=now;remote.pendingStatus="";host.state(remote);}
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
                    boolean flying=flying(packet);
                    remote.sequence=sequence;remote.appearance=appearance;remote.extra=extra;remote.flying=flying;
                    if(!oldTexture.equals(appearance.get("textureId").getAsString())&&remote.bundle!=null){remote.active=remote.ready=false;remote.lastLease=now;decode(remote);}
                    else host.state(remote);
                }
                case "private_event" -> authorEvent(packet);
                case "heartbeat" -> {
                    JsonArray values=packet.getAsJsonArray("bindings");if(values==null||values.size()>128)throw new IOException("Private heartbeat");
                    boolean upload=packet.has("allowedUpload")?WireJson.bool(packet,"allowedUpload"):allowedUpload;
                    boolean view=packet.has("allowedView")?WireJson.bool(packet,"allowedView"):allowedView;
                    permissions(upload,view);
                    for(JsonElement value:values){Remote remote=matching(value.getAsJsonObject());if(remote!=null&&remote.ready&&remote.active)remote.lastLease=now;}
                }
                case "error" -> {
                    String code=WireJson.string(packet,"code",128);
                    boolean publicationFailure=code.startsWith("upload")||code.startsWith("private_upload")
                            ||Set.of("not_allowed","server_model_priority","private_generation_reused","private_storage_busy",
                                "private_bundle_invalid","private_sync_disabled","private_state_not_authorized","private_event_not_authorized").contains(code)
                            ||code.equals("private_appearance_size")&&!committed;
                    if(publicationFailure&&ownGeneration!=null){failPublishing(errorStatus(code));retryAfter=now+10*SECOND;}
                    else status=errorStatus(code);
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
        JsonObject appearance=appearance(packet),extra=extra(packet);long sequence=WireJson.integer(packet,"sequence",0,9_007_199_254_740_991L);boolean flying=flying(packet);
        Remote previous=remotes.get(owner);
        if(previous!=null) {
            if(previous.offerId.equals(id)&&previous.generation.equals(generation)&&previous.hash.equals(hash)){
                flushControl(previous,now);return;
            }
            remove(previous);
        }
        if(remotes.size()>=MAX_REMOTES) {status(id,hash,"rejected");return;}
        Remote remote=new Remote(owner,generation,id,hash,kind,bytes,appearance,extra,sequence,flying);remote.lastLease=now;remotes.put(owner,remote);
        long revision=epoch;
        host.cached(hash).whenComplete((bundle,error)->host.dispatch(()->{
            if(!current(remote,revision))return;
            if(error==null&&bundle!=null&&bundle.length==bytes&&hash.equals(AssetTransfer.hash(bundle))) {
                remote.bundle=bundle;queueStatus(remote,"cached");decode(remote);
            } else queueStatus(remote,"missing");
        }));
    }
    private void decode(Remote remote) {
        long revision=epoch,decodeRevision=++remote.decodeRevision;
        host.decode(remote.bundle,remote.appearance.get("textureId").getAsString()).whenComplete((loaded,error)->host.dispatch(()->{
            if(!current(remote,revision)||decodeRevision!=remote.decodeRevision)return;
            if(error!=null||loaded==null||!host.prepare(remote,loaded)){status(remote.offerId,remote.hash,"rejected");remove(remote);return;}
            remote.ready=true;remote.lastLease=clockNow;remote.lastReadyAttempt=-1;host.state(remote);flushControl(remote,clockNow);
            host.cache(remote.hash,remote.bundle);
        }));
    }
    private void permissions(boolean upload,boolean view) {
        boolean uploadChanged=allowedUpload!=upload;
        if(allowedUpload&&!upload&&ownGeneration!=null)stopPublishing();
        if(allowedView&&!view)for(Remote remote:List.copyOf(remotes.values()))remove(remote);
        allowedUpload=upload;allowedView=view;
        if(uploadChanged&&!upload&&deleteRequest!=null) {
            if(deleteSent)rememberDeleteStatus(deletingId,"上传权限已撤销，等待服务器删除结果");
            else {rememberDeleteStatus(deletingId,"上传权限已撤销，删除请求未发送");clearDelete();}
        }
        if(uploadChanged)status=upload?"上传权限已开放；开启分享后上传私人模型":"上传权限已撤销；私人模型仅自己可见";
    }
    private void rememberDeleteStatus(String id,String status) {
        deleteStatuses.put(id,status);while(deleteStatuses.size()>64)deleteStatuses.remove(deleteStatuses.keySet().iterator().next());
    }
    private void queueStatus(Remote remote,String value) {
        remote.pendingStatus=value;remote.statusSent=false;remote.lastStatusAttempt=-1;flushControl(remote,clockNow);
    }
    /** A prepared model remains invisible until the exact acknowledgement; congestion only retries control packets. */
    private void flushControl(Remote remote,long now) {
        if(!remote.pendingStatus.isEmpty()) {
            if(remote.lastStatusAttempt<0||now-remote.lastStatusAttempt>=SECOND) {
                Boolean sent=sendControl(feedback(remote.offerId,remote.hash,remote.pendingStatus),now);
                if(sent!=null){remote.lastStatusAttempt=now;remote.statusSent=sent||remote.statusSent;}
            }
            if(!remote.statusSent)return;
        }
        if(!remote.ready||remote.active||remote.lastReadyAttempt>=0&&now-remote.lastReadyAttempt<SECOND)return;
        JsonObject ready=binding(remote.owner,remote.generation,remote.hash);ready.addProperty("protocol",PROTOCOL);ready.addProperty("type","private_ready");
        if(sendControl(ready,now)!=null)remote.lastReadyAttempt=now;
    }
    private static String size(int bytes) {return bytes>=1024*1024?String.format(Locale.ROOT,"%.2f MiB",bytes/(1024d*1024)):Math.max(1,(bytes+1023)/1024)+" KiB";}
    private static String errorStatus(String code) {
        return switch(code) {
            case "private_upload_denied","not_allowed" -> "没有私人模型上传权限";
            case "server_model_priority" -> "服务器伪装期间，私人模型仅自己可见";
            case "private_upload_busy","private_storage_busy","private_transfer_busy" -> "服务器模型传输繁忙，稍后重试";
            case "private_upload_cooldown" -> "上传过于频繁，等待后重试";
            case "private_upload_integrity","private_bundle_invalid","private_upload_invalid" -> "服务器拒绝模型：文件不完整或格式无效";
            case "private_upload_expired" -> "上传超时，稍后重新上传";
            case "private_upload_size","private_bundle_size" -> "模型超过服务器允许的上传大小";
            case "private_sync_disabled" -> "服务器已关闭私人模型分享";
            case "private_view_denied","private_render_not_authorized" -> "当前没有观看该私人模型的授权";
            case "private_appearance_size" -> "模型配置超过服务器允许的大小";
            case "private_generation_reused" -> "服务器已撤销这次分享，稍后重新上传";
            default -> "私人模型同步被服务器拒绝（"+code+"）";
        };
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
    private boolean currentLocal(){Local local=host.local();return local!=null&&local.owner().equals(ownOwner)&&local.modelId().equals(sourceId);}
    private boolean matchesOwn(JsonObject packet){return ownGeneration!=null&&ownGeneration.equals(uuid(packet,"generation"))&&ownHash.equals(WireJson.hash(packet,"hash"))&&currentLocal();}
    private static JsonObject feedback(UUID offer,String hash,String state){JsonObject value=envelope("private_status");value.addProperty("offerId",offer.toString());value.addProperty("hash",hash);value.addProperty("status",state);return value;}
    private boolean status(UUID offer,String hash,String state){return Boolean.TRUE.equals(sendControl(feedback(offer,hash,state),clockNow));}
    private JsonObject identity(String type){JsonObject packet=envelope(type);packet.addProperty("generation",ownGeneration.toString());packet.addProperty("hash",ownHash);return packet;}
    public static JsonObject envelope(String type){JsonObject packet=new JsonObject();packet.addProperty("protocol",PROTOCOL);packet.addProperty("type",type);return packet;}
    private static JsonObject binding(UUID owner,UUID generation,String hash){JsonObject value=new JsonObject();value.addProperty("owner",owner.toString());value.addProperty("generation",generation.toString());value.addProperty("hash",hash);return value;}
    private static UUID uuid(JsonObject packet,String key){return UUID.fromString(WireJson.string(packet,key,36));}
    /** Optional S2C extension: legacy servers omit it, model caches never supply entity state. */
    private static boolean flying(JsonObject packet) {
        if(!packet.has("state"))return false;
        JsonElement state=packet.get("state");
        if(!state.isJsonObject() || !Set.of("flying").containsAll(state.getAsJsonObject().keySet()))throw new IllegalArgumentException("Private server state");
        JsonObject values=state.getAsJsonObject();return values.has("flying") && WireJson.bool(values,"flying");
    }
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
