package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.config.PerformanceSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Opt-in private model relay. Contains no ModelEngine API, filesystem lookup, HTTP or script execution. */
public final class PrivateModelSyncService implements PluginMessageListener, AutoCloseable {
    public static final String CHANNEL = "meplayeractions:private", CAPABILITY = "private_models_v1";
    public static final int PROTOCOL = 1, HEARTBEAT_TICKS = 20, LEASE_TICKS = 100;
    private static final int CHUNK_BYTES = 8192, MAX_OFFERS = 64, TRANSFER_TIMEOUT = 1200;
    private static final long MAX_SEQUENCE = 9_007_199_254_740_991L;
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}"), NAME = Pattern.compile("[a-zA-Z0-9_.:/-]{1,128}");
    private static final Pattern MODEL_VARIABLE = Pattern.compile("variable\\.[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*");
    private static final Pattern ROAMING_NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");
    private static final String ROAMING_PREFIX = "variable.roaming.";
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final Plugin plugin;
    private final Supplier<Set<UUID>> serverDisguisedOwners;
    private final ConnectionLimits limits;
    private final Map<UUID,Session> sessions = new HashMap<>();
    private final Map<UUID,Publication> publications = new HashMap<>();
    private final Map<UUID,Rates> rates = new HashMap<>();
    private final Set<Upload> validations = new HashSet<>();
    private final PrivateAudienceCache audiences = new PrivateAudienceCache();
    private final LinkedHashSet<Offer> pendingTransfers = new LinkedHashSet<>(), activeTransfers = new LinkedHashSet<>();
    private Policy policy = Policy.disabled();
    private BukkitTask maintenance;
    private boolean running;
    private long storedBytes, reservedBytes;
    private int uploads;
    private int validationTicks=20;
    private long lastMaintenanceTick;
    private boolean maintenanceInitialized;

    /** Policy defaults never grant private asset publishing or viewing. Permissions are independent. */
    public record Policy(boolean enabled, int maxPayload, int maxBundleBytes, long maxStoredBytes,
                         double viewDistance, int maxViewers, String uploadPermission, String viewPermission) {
        public Policy {
            if (maxPayload < 1024 || maxPayload > 30000 || maxBundleBytes < 1024 || maxBundleBytes > PrivateModelBundle.MAX_BYTES
                    || maxStoredBytes < (long)maxBundleBytes + PrivateModelBundle.MAX_BYTES || maxStoredBytes > 256L * 1024 * 1024
                    || !Double.isFinite(viewDistance) || viewDistance < 1 || viewDistance > 256 || maxViewers < 0 || maxViewers > 1000
                    || uploadPermission == null || uploadPermission.isBlank() || viewPermission == null || viewPermission.isBlank())
                throw new IllegalArgumentException("Invalid private model relay policy");
        }
        public static Policy disabled() { return new Policy(false,16000,PrivateModelBundle.MAX_BYTES,32L*1024*1024,64,10,"mact.private.upload","mact.private.view"); }
        /** Configuration reader usable in a private-only server without loading ModelEngine Settings. */
        public static Policy fromConfiguration(ConfigurationSection config) {
            Objects.requireNonNull(config);
            ConfigurationSection section=config.getConfigurationSection("client-sync.private-models");
            Set<String> allowed=Set.of("enabled","max-bundle-bytes","max-stored-bytes","view-distance-blocks","max-viewers");
            if(section!=null)for(String field:section.getKeys(false))if(!allowed.contains(field) || section.isConfigurationSection(field))
                throw new IllegalArgumentException("Unknown client-sync.private-models field: "+field);
            return new Policy(config.getBoolean("client-sync.enabled",true) && config.getBoolean("client-sync.private-models.enabled",false),config.getInt("client-sync.max-payload-bytes",16000),
                    config.getInt("client-sync.private-models.max-bundle-bytes",PrivateModelBundle.MAX_BYTES),
                    config.getLong("client-sync.private-models.max-stored-bytes",32L*1024*1024),
                    config.getDouble("client-sync.private-models.view-distance-blocks",64),config.getInt("client-sync.private-models.max-viewers",10),
                    "mact.private.upload","mact.private.view");
        }
    }
    public PrivateModelSyncService(Plugin plugin, Supplier<Set<UUID>> serverDisguisedOwners) {
        this(plugin,serverDisguisedOwners,new ConnectionLimits());
    }
    PrivateModelSyncService(Plugin plugin,Supplier<Set<UUID>> serverDisguisedOwners,ConnectionLimits limits) {
        this.plugin=Objects.requireNonNull(plugin);this.serverDisguisedOwners=Objects.requireNonNull(serverDisguisedOwners);this.limits=Objects.requireNonNull(limits);
    }
    public void configure(Policy policy) {
        Objects.requireNonNull(policy);
        if (!this.policy.equals(policy)) clearSessions("policy_changed");
        this.policy=policy;
    }
    public void configurePerformance(PerformanceSettings performance) {
        Objects.requireNonNull(performance);audiences.configure(performance.audienceRefreshTicks());
        validationTicks=performance.validationTicks();maintenanceInitialized=false;
    }
    public void enable() {
        if(running)return;
        var messenger=plugin.getServer().getMessenger();messenger.registerOutgoingPluginChannel(plugin,CHANNEL);
        try {
            messenger.registerIncomingPluginChannel(plugin,CHANNEL,this);running=true;
            maintenance=plugin.getServer().getScheduler().runTaskTimer(plugin,this::maintain,1,1);
        } catch(RuntimeException failure) {
            running=false;messenger.unregisterIncomingPluginChannel(plugin,CHANNEL,this);messenger.unregisterOutgoingPluginChannel(plugin,CHANNEL);throw failure;
        }
    }
    @Override public void close() {
        if(maintenance!=null){maintenance.cancel();maintenance=null;}
        clearSessions("plugin_stopping");rates.clear();running=false;
        for(Upload upload:List.copyOf(validations))releaseUpload(upload);
        var messenger=plugin.getServer().getMessenger();messenger.unregisterIncomingPluginChannel(plugin,CHANNEL,this);messenger.unregisterOutgoingPluginChannel(plugin,CHANNEL);
    }
    public void forget(Player player) {
        UUID id=player.getUniqueId();Session session=sessions.remove(id);if(session!=null)endSession(id,session,"offline");rates.remove(id);limits.forget(id);
    }
    /** Teleport/world changes revoke only relationships involving this player; discovery remains phased. */
    public void observationChanged(Player player) {
        UUID id=player.getUniqueId();Session session=sessions.get(id);
        if(session!=null)for(Offer offer:List.copyOf(session.offers.values()))removeOffer(player,session,offer,"observation_changed");
        audiences.removeViewer(id);Publication publication=publications.get(id);
        if(publication!=null) {
            audiences.invalidate(id,publication.generation,tick());
            for(UUID viewer:List.copyOf(publication.offeredViewers)) {
                Session watching=sessions.get(viewer);if(watching==null)continue;
                Offer offer=watching.offers.get(id);
                if(offer!=null && offer.publication==publication)removeOffer(Bukkit.getPlayer(viewer),watching,offer,"observation_changed");
            }
        }
    }
    public String status(Player player) {
        if(!policy.enabled())return "私人多人同步关闭";
        Session session=sessions.get(player.getUniqueId());Publication publication=publications.get(player.getUniqueId());
        long ready=session==null?0:session.offers.values().stream().filter(offer->offer.status==Status.READY).count();
        return "私人多人同步 " + (session==null?"未协商":"v1") + "；发布许可 " + canUpload(player) + "；观看许可 " + canView(player)
                + "；本人 " + (publication==null?"未发布":publication.kind) + "；已确认观看 " + ready
                + "；共享入站 48 包/秒、256 KiB/秒；资产出站 512 KiB/秒";
    }
    @Override public void onPluginMessageReceived(String channel,Player player,byte[] bytes) {
        if(!running || !CHANNEL.equals(channel) || bytes==null || bytes.length==0 || bytes.length>policy.maxPayload())return;
        if(!Bukkit.isPrimaryThread()) {
            byte[] copy=bytes.clone();if(plugin.isEnabled())plugin.getServer().getScheduler().runTask(plugin,()->onPluginMessageReceived(channel,player,copy));return;
        }
        if(!player.isOnline() || !limits.allowInbound(player.getUniqueId(),bytes.length,System.nanoTime()))return;
        JsonObject packet;
        try {packet=decode(bytes);} catch(IOException | RuntimeException malformed) {error(player,"invalid_private_payload");return;}
        String type=packet.get("type").getAsString();UUID owner=player.getUniqueId();long tick=tick();
        if(type.equals("hello")) {
            if(!rates.computeIfAbsent(owner,unused->new Rates()).hello.take(System.nanoTime()))return;
            Session previous=sessions.remove(owner);if(previous!=null)endSession(owner,previous,"new_handshake");
            Session session=new Session(owner);sessions.put(owner,session);
            JsonObject ack=envelope("hello_ack");JsonArray capabilities=new JsonArray();capabilities.add(CAPABILITY);ack.add("capabilities",capabilities);
            ack.addProperty("allowedUpload",canUpload(player));ack.addProperty("allowedView",canView(player));
            ack.addProperty("maxPayload",policy.maxPayload());ack.addProperty("maxBundleBytes",effectiveBundleLimit(policy));
            ack.addProperty("heartbeatTicks",HEARTBEAT_TICKS);ack.addProperty("leaseTicks",LEASE_TICKS);
            if(!send(player,ack,false))sessions.remove(owner);return;
        }
        Session session=sessions.get(owner);if(session==null)return;
        if(type.equals("clear")){cancelUpload(session);removePublication(owner,"owner_cleared");return;}
        if(!policy.enabled()){error(player,"private_sync_disabled");return;}
        try {
            switch(type) {
                case "upload_offer" -> offerUpload(player,session,packet,tick);
                case "upload_chunk" -> uploadChunk(player,session,packet,tick);
                case "upload_end" -> finishUpload(player,session,packet);
                case "private_status" -> feedback(player,session,packet,tick);
                case "private_ready" -> ready(player,session,packet,tick);
                case "private_heartbeat" -> heartbeat(player,session,packet,tick);
                case "private_state" -> updateState(player,packet,tick);
                case "private_event" -> event(player,packet,tick);
                default -> throw new IOException("unknown_private_message");
            }
        } catch(IOException | RuntimeException malformed) {error(player,"invalid_private_payload");}
    }
    private boolean canUpload(Player owner) {return policy.enabled() && owner.hasPermission(policy.uploadPermission());}
    private boolean canView(Player viewer) {return policy.enabled() && viewer.hasPermission(policy.viewPermission());}
    private Set<UUID> serverDisguised() {
        Set<UUID> owners=serverDisguisedOwners.get();return owners==null?Set.of():Set.copyOf(owners);
    }
    private boolean isServerDisguised(UUID owner) {
        try{Set<UUID> owners=serverDisguisedOwners.get();return owners!=null && owners.contains(owner);}
        catch(RuntimeException unavailable){return true;}
    }
    private void offerUpload(Player player,Session session,JsonObject packet,long tick) throws IOException {
        if(!canUpload(player)){error(player,"private_upload_denied");return;}
        if(serverDisguised().contains(player.getUniqueId())){error(player,"server_model_priority");return;}
        if(session.upload!=null || uploads>=2){error(player,"private_upload_busy");return;}
        int bytes=(int)PrivateModelBundle.integer(packet,"bytes",1,effectiveBundleLimit(policy));
        String hash=PrivateModelBundle.string(packet,"hash",64),kind=PrivateModelBundle.string(packet,"kind",16);
        UUID generation=uuid(packet,"generation");Publication current=publications.get(player.getUniqueId());
        if(current!=null && current.generation.equals(generation)) {error(player,"private_generation_reused");return;}
        long reservation=bytes+(long)PrivateModelBundle.MAX_BYTES;
        if(storedBytes+reservedBytes+reservation>policy.maxStoredBytes()){error(player,"private_storage_busy");return;}
        if(!limits.allowRequest(player.getUniqueId(),"private_upload","",tick,200) || !limits.allowAsset(player.getUniqueId(),hash,tick))
            {error(player,"private_upload_cooldown");return;}
        if(!limits.reserveTransfer(player.getUniqueId())){error(player,"private_upload_busy");return;}
        JsonObject appearance=appearance(packet.get("appearance"));
        if(!fitsAppearance(appearance,new JsonObject())){limits.releaseTransfer(player.getUniqueId());error(player,"private_appearance_size");return;}
        Upload upload=new Upload(player.getUniqueId(),UUID.randomUUID(),generation,hash,kind,bytes,appearance,tick,reservation);
        session.upload=upload;reservedBytes+=reservation;uploads++;
        JsonObject accepted=envelope("upload_accept");accepted.addProperty("uploadId",upload.id.toString());accepted.addProperty("generation",generation.toString());
        accepted.addProperty("hash",hash);accepted.addProperty("chunkBytes",chunkBytes());
        if(!send(player,accepted,false))cancelUpload(session);
    }
    private int chunkBytes() {return negotiatedChunkBytes(policy.maxPayload());}
    static int negotiatedChunkBytes(int maxPayload) {return Math.min(CHUNK_BYTES,Math.max(384,(maxPayload-512)*3/4));}
    static int effectiveBundleLimit(Policy policy) {return Math.min(policy.maxBundleBytes(),negotiatedChunkBytes(policy.maxPayload())*1100);}
    private void uploadChunk(Player player,Session session,JsonObject packet,long tick) throws IOException {
        Upload upload=session.upload;
        if(upload==null || !upload.id.equals(uuid(packet,"uploadId")) || upload.validating)return;
        if(!canUpload(player)){cancelUpload(session);error(player,"private_upload_denied");return;}
        int index=(int)PrivateModelBundle.integer(packet,"index",0,16383);
        String text=PrivateModelBundle.string(packet,"data",16000);byte[] chunk;
        try {chunk=Base64.getDecoder().decode(text);}catch(IllegalArgumentException invalid){cancelUpload(session);throw new IOException("base64",invalid);}
        if(index!=upload.index || chunk.length==0 || chunk.length>chunkBytes() || chunk.length>upload.bytes.length-upload.offset
                || !Base64.getEncoder().encodeToString(chunk).equals(text)){cancelUpload(session);throw new IOException("upload_chunk");}
        System.arraycopy(chunk,0,upload.bytes,upload.offset,chunk.length);upload.offset+=chunk.length;upload.index++;upload.lastProgress=tick;
    }
    private void finishUpload(Player player,Session session,JsonObject packet) throws IOException {
        Upload upload=session.upload;
        if(upload==null || !upload.id.equals(uuid(packet,"uploadId")) || upload.validating)return;
        if(!canUpload(player) || upload.offset!=upload.bytes.length) {
            cancelUpload(session);error(player,"private_upload_integrity");return;
        }
        upload.validating=true;validations.add(upload);
        try {plugin.getServer().getScheduler().runTaskAsynchronously(plugin,()->{
            String failure=null;
            try {
                if(!PrivateModelBundle.hash(upload.bytes).equals(upload.hash))failure="private_upload_integrity";
                else PrivateModelBundle.validate(upload.bytes,upload.kind);
            }catch(IOException | RuntimeException invalid){failure="private_bundle_invalid";}
            String result=failure;
            if(plugin.isEnabled())plugin.getServer().getScheduler().runTask(plugin,()->commitUpload(player,session,upload,result));
        });}catch(RuntimeException stopped){upload.validating=false;cancelUpload(session);error(player,"private_upload_unavailable");}
    }
    private void commitUpload(Player player,Session session,Upload upload,String failure) {
        boolean current=running && sessions.get(player.getUniqueId())==session && session.upload==upload;
        if(current)session.upload=null;
        releaseUpload(upload);
        if(!current)return;
        if(failure!=null || !player.isOnline() || !canUpload(player)){error(player,failure==null?"private_upload_denied":failure);return;}
        if(isServerDisguised(player.getUniqueId())){error(player,"server_model_priority");return;}
        removePublication(player.getUniqueId(),"model_changed");
        Publication publication=new Publication(player.getUniqueId(),upload.generation,upload.hash,upload.kind,upload.bytes,upload.appearance,tick());
        publications.put(player.getUniqueId(),publication);storedBytes+=upload.bytes.length;
        JsonObject committed=envelope("upload_committed");committed.addProperty("generation",upload.generation.toString());committed.addProperty("hash",upload.hash);
        if(!send(player,committed,false))removePublication(player.getUniqueId(),"upload_ack_failed");
    }
    private void cancelUpload(Session session) {
        Upload upload=session.upload;if(upload==null)return;session.upload=null;
        if(upload.transferReserved){limits.releaseTransfer(session.owner);upload.transferReserved=false;}
        // A cancelled asynchronous validator still holds raw/expanded bytes. Preserve its global memory
        // reservation until that exact worker returns; a new hello cannot open unlimited validators.
        if(!upload.validating)releaseUpload(upload);
    }
    private void releaseUpload(Upload upload) {
        if(upload.released)return;upload.released=true;validations.remove(upload);reservedBytes-=upload.reservation;uploads--;
        if(upload.transferReserved){limits.releaseTransfer(upload.owner);upload.transferReserved=false;}
    }
    private void feedback(Player player,Session session,JsonObject packet,long tick) throws IOException {
        Offer offer=findOffer(session,uuid(packet,"offerId"),PrivateModelBundle.string(packet,"hash",64));
        if(offer==null || !authorized(player,offer.publication,serverDisguised()))return;
        String status=PrivateModelBundle.string(packet,"status",16);
        if(status.equals("rejected")) {
            // A renderer can fail after caching, downloading or acknowledging readiness. Retain this
            // owner/generation for the session; render_ready's shared attempt reset must not reoffer it.
            session.rejected.put(offer.publication.owner,offer.publication.generation);
            removeOffer(player,session,offer,"asset_rejected");return;
        }
        if(offer.status!=Status.OFFERED)return;
        switch(status) {
            case "cached" -> {offer.status=Status.CACHED;offer.lastProgress=tick;}
            case "missing" -> {offer.status=Status.QUEUED;offer.lastProgress=tick;pendingTransfers.add(offer);}
            default -> throw new IOException("status");
        }
    }
    private void ready(Player player,Session session,JsonObject packet,long tick) throws IOException {
        UUID owner=uuid(packet,"owner"),generation=uuid(packet,"generation");String hash=PrivateModelBundle.string(packet,"hash",64);
        Offer offer=session.offers.get(owner);
        if(offer==null || !matches(offer.publication,generation,hash) || !Set.of(Status.CACHED,Status.DELIVERED,Status.READY).contains(offer.status)
                || !authorized(player,offer.publication,serverDisguised())){error(player,"private_render_not_authorized");return;}
        JsonObject ack=binding("private_ack",offer.publication);
        if(send(player,ack,false)){offer.status=Status.READY;offer.lease=tick;limits.pushRenderReady(player.getUniqueId(),hash);sendState(player,offer.publication);}
    }
    private void heartbeat(Player player,Session session,JsonObject packet,long tick) throws IOException {
        JsonArray bindings=packet.getAsJsonArray("bindings");Set<UUID> owners=new HashSet<>();Set<UUID> disguised=serverDisguised();
        JsonArray renewed=new JsonArray();
        for(JsonElement element:bindings) {
            JsonObject entry=PrivateModelBundle.object(element);UUID owner=uuid(entry,"owner"),generation=uuid(entry,"generation");String hash=PrivateModelBundle.string(entry,"hash",64);
            if(!owners.add(owner))throw new IOException("duplicate_binding");
            if(owner.equals(player.getUniqueId())) {
                Publication own=publications.get(owner);if(canUpload(player) && matches(own,generation,hash)){own.lease=tick;renewed.add(entry.deepCopy());}
            } else {
                Offer offer=session.offers.get(owner);
                if(offer!=null && offer.status==Status.READY && matches(offer.publication,generation,hash) && authorized(player,offer.publication,disguised)){offer.lease=tick;renewed.add(entry.deepCopy());}
            }
        }
        JsonObject confirmation=envelope("heartbeat");confirmation.add("bindings",renewed);send(player,confirmation,false);
    }
    private Publication owned(Player player,JsonObject packet) throws IOException {
        Publication publication=publications.get(player.getUniqueId());
        return canUpload(player) && matches(publication,uuid(packet,"generation"),PrivateModelBundle.string(packet,"hash",64))?publication:null;
    }
    private void updateState(Player player,JsonObject packet,long tick) throws IOException {
        Publication publication=owned(player,packet);if(publication==null){error(player,"private_state_not_authorized");return;}
        if(!rates.computeIfAbsent(player.getUniqueId(),unused->new Rates()).state.take(System.nanoTime()))return;
        JsonObject extra=extra(packet.get("extra"));long sequence=PrivateModelBundle.integer(extra,"sequence",0,MAX_SEQUENCE);
        if(sequence<publication.extra.get("sequence").getAsLong())return;
        JsonObject appearance=appearance(packet.get("appearance"));
        if(!fitsAppearance(appearance,extra)){error(player,"private_appearance_size");return;}
        publication.appearance=appearance;publication.extra=extra;publication.sequence++;publication.lease=tick;
        Set<UUID> disguised=serverDisguised();
        for(UUID viewerId:audiences.viewers(publication.owner,publication.generation)) {
            Session session=sessions.get(viewerId);if(session==null)continue;
            Offer offer=session.offers.get(publication.owner);Player viewer=Bukkit.getPlayer(viewerId);
            if(offer!=null && offer.status==Status.READY && offer.publication==publication && authorized(viewer,publication,disguised))sendState(viewer,publication);
        }
    }
    private void event(Player player,JsonObject packet,long tick) throws IOException {
        Publication publication=owned(player,packet);if(publication==null || serverDisguised().contains(player.getUniqueId())){error(player,"private_event_not_authorized");return;}
        if(!rates.computeIfAbsent(player.getUniqueId(),unused->new Rates()).event.take(System.nanoTime()))return;
        publication.lease=tick;JsonObject event=binding("private_event",publication);event.addProperty("sequence",++publication.eventSequence);event.add("args",packet.get("args").deepCopy());
        // Mature ysm.sync is relay-only: the owner also receives exactly one authoritative echo.
        send(player,event,false);Set<UUID> disguised=serverDisguised();
        for(UUID viewerId:audiences.viewers(publication.owner,publication.generation)) {
            Session session=sessions.get(viewerId);if(session==null)continue;
            Offer offer=session.offers.get(publication.owner);Player viewer=Bukkit.getPlayer(viewerId);
            if(offer!=null && offer.status==Status.READY && offer.publication==publication && authorized(viewer,publication,disguised))send(viewer,event,false);
        }
    }
    private void maintain() {
        if(!running || !policy.enabled() && sessions.isEmpty() && publications.isEmpty() && uploads==0)return;
        long tick=tick();boolean validate=!maintenanceInitialized || distance(tick,lastMaintenanceTick)>=validationTicks;
        if(validate) {
            maintenanceInitialized=true;lastMaintenanceTick=tick;
            limits.pruneOffline(id->{Player player=Bukkit.getPlayer(id);return player!=null && player.isOnline();});
            for(Publication publication:List.copyOf(publications.values())) {
                Player owner=Bukkit.getPlayer(publication.owner);
                if(owner==null || !owner.isOnline() || !canUpload(owner) || distance(tick,publication.lease)>=LEASE_TICKS)
                    removePublication(publication.owner,"publisher_expired");
            }
            for(Session session:List.copyOf(sessions.values())) {
                Player viewer=Bukkit.getPlayer(session.owner);
                if(viewer==null || !viewer.isOnline()){sessions.remove(session.owner);endSession(session.owner,session,"offline");rates.remove(session.owner);continue;}
                Upload upload=session.upload;
                if(upload!=null && (distance(tick,upload.created)>=TRANSFER_TIMEOUT || distance(tick,upload.lastProgress)>=300 || !canUpload(viewer))) {
                    cancelUpload(session);error(viewer,"private_upload_expired");
                }
            }
        }
        // A disabled/private-idle relay never asks the ModelEngine backend for its owners.
        if(!policy.enabled() || publications.isEmpty())return;
        for(Publication publication:publications.values()) {
            if(audiences.refreshIfDue(publication.owner,publication.generation,tick,()->discoverAudience(publication)))
                for(UUID viewer:audiences.viewers(publication.owner,publication.generation))offer(Bukkit.getPlayer(viewer),publication,tick);
        }
        if(validate) {
            Set<UUID> disguised;
            try{disguised=serverDisguised();}catch(RuntimeException unavailable){disguised=Set.copyOf(publications.keySet());}
            for(Session session:sessions.values()) {
                Player viewer=Bukkit.getPlayer(session.owner);
                for(Offer offer:List.copyOf(session.offers.values())) {
                    if(!authorized(viewer,offer.publication,disguised)){removeOffer(viewer,session,offer,disguised.contains(offer.publication.owner)?"server_model_priority":"out_of_range");continue;}
                    if(offer.status==Status.READY) {
                        if(distance(tick,offer.lease)>=LEASE_TICKS)removeOffer(viewer,session,offer,"lease_expired");
                        continue;
                    }
                    int idle=offer.status==Status.OFFERED?100:offer.status==Status.CACHED || offer.status==Status.DELIVERED?200:300;
                    if(distance(tick,offer.created)>=TRANSFER_TIMEOUT || distance(tick,offer.lastProgress)>=idle)removeOffer(viewer,session,offer,"offer_expired");
                }
            }
        }
        pumpTransfers(tick);
    }
    private Set<UUID> discoverAudience(Publication publication) {
        Player owner=Bukkit.getPlayer(publication.owner);
        if(owner==null || !owner.isOnline() || !canUpload(owner) || isServerDisguised(publication.owner) || policy.maxViewers()==0)return Set.of();
        var origin=owner.getLocation();double range=policy.viewDistance()*policy.viewDistance();
        return owner.getTrackedBy().stream().filter(Player::isOnline).filter(candidate->!candidate.getUniqueId().equals(owner.getUniqueId()))
                .filter(candidate->sessions.containsKey(candidate.getUniqueId()) && canView(candidate) && candidate.getWorld().equals(owner.getWorld()) && candidate.canSee(owner))
                .map(candidate->new AudienceCandidate(candidate.getUniqueId(),candidate.getLocation().distanceSquared(origin)))
                .filter(candidate->candidate.distance<range)
                .sorted(Comparator.comparingDouble(AudienceCandidate::distance).thenComparing(AudienceCandidate::viewer))
                .limit(policy.maxViewers()).map(AudienceCandidate::viewer)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
    private void offer(Player viewer,Publication publication,long tick) {
        if(viewer==null)return;Session session=sessions.get(viewer.getUniqueId());if(session==null)return;
        if(session.offers.size()>=MAX_OFFERS || session.offers.values().stream().filter(offer->offer.status!=Status.READY).count()>=2)return;
        UUID rejected=session.rejected.get(publication.owner);
        if(rejected!=null) {
            if(rejected.equals(publication.generation))return;
            session.rejected.remove(publication.owner);
        }
        // Live offers and rejection records share the existing 64-entry memory budget.
        if(session.offers.size()+session.rejected.size()>=MAX_OFFERS || session.offers.containsKey(publication.owner) || !authorized(viewer,publication)
                || !limits.canPushOffer(session.owner,publication.hash,Set.of(publication.generation),tick))return;
        Offer offer=new Offer(session.owner,publication,tick);JsonObject packet=binding("private_offer",publication);
        packet.addProperty("offerId",offer.id.toString());packet.addProperty("kind",publication.kind);packet.addProperty("bytes",publication.bytes.length);
        addAppearance(packet,publication);
        if(send(viewer,packet,false)){session.offers.put(publication.owner,offer);publication.offeredViewers.add(session.owner);limits.pushOfferSent(session.owner,publication.hash,Set.of(publication.generation),tick);}
    }
    private boolean authorized(Player viewer,Publication publication,Set<UUID> disguised) {
        return liveAuthorized(viewer,publication,disguised.contains(publication.owner));
    }
    private boolean authorized(Player viewer,Publication publication) {
        return liveAuthorized(viewer,publication,isServerDisguised(publication.owner));
    }
    private boolean liveAuthorized(Player viewer,Publication publication,boolean disguised) {
        if(viewer==null || !viewer.isOnline() || !canView(viewer) || viewer.getUniqueId().equals(publication.owner)
                || publications.get(publication.owner)!=publication || disguised || !audiences.contains(publication.owner,publication.generation,viewer.getUniqueId()))return false;
        Player owner=Bukkit.getPlayer(publication.owner);
        if(owner==null || !owner.isOnline() || !canUpload(owner) || !owner.getWorld().equals(viewer.getWorld()) || !viewer.canSee(owner)
                || !owner.getTrackedBy().contains(viewer) || owner.getLocation().distanceSquared(viewer.getLocation())>=policy.viewDistance()*policy.viewDistance())return false;
        return true;
    }
    private void pumpTransfers(long tick) {
        List<Offer> sending=List.copyOf(activeTransfers);
        for(Offer offer:sending)pumpOffer(offer,tick);
        // Rotate the first active transfer so global byte exhaustion does not always favor one viewer.
        if(!sending.isEmpty() && activeTransfers.remove(sending.get(0)))activeTransfers.add(sending.get(0));
        List<Offer> waiting=new ArrayList<>();var iterator=pendingTransfers.iterator();
        for(int count=0;count<ConnectionLimits.GLOBAL_TRANSFERS && iterator.hasNext();count++){waiting.add(iterator.next());iterator.remove();}
        for(Offer offer:waiting){pumpOffer(offer,tick);if(offer.status==Status.QUEUED && currentOffer(offer))pendingTransfers.add(offer);}
    }
    private boolean currentOffer(Offer offer) {
        Session session=sessions.get(offer.viewer);return session!=null && session.offers.get(offer.publication.owner)==offer;
    }
    private void pumpOffer(Offer offer,long tick) {
        Session session=sessions.get(offer.viewer);Player viewer=Bukkit.getPlayer(offer.viewer);
        if(session==null || session.offers.get(offer.publication.owner)!=offer){activeTransfers.remove(offer);return;}
        if(!authorized(viewer,offer.publication)){removeOffer(viewer,session,offer,"out_of_range");return;}
        pump(viewer,session,offer,tick);
    }
    private void pump(Player viewer,Session session,Offer offer,long tick) {
        if(offer.status==Status.QUEUED) {
            if(!authorized(viewer,offer.publication)){removeOffer(viewer,session,offer,"out_of_range");return;}
            if(!limits.reserveTransfer(session.owner))return;offer.reserved=true;
            JsonObject begin=envelope("asset_begin");begin.addProperty("offerId",offer.id.toString());begin.addProperty("hash",offer.publication.hash);
            begin.addProperty("bytes",offer.publication.bytes.length);begin.addProperty("chunks",(offer.publication.bytes.length+chunkBytes()-1)/chunkBytes());
            if(!send(viewer,begin,true)){limits.releaseTransfer(session.owner);offer.reserved=false;return;}
            offer.status=Status.SENDING;offer.lastProgress=tick;activeTransfers.add(offer);
        }
        if(offer.status!=Status.SENDING)return;
        for(int count=0;count<2 && offer.offset<offer.publication.bytes.length;count++) {
            // No nearest-viewer sorting here: recheck current permission/tracking/generation for every chunk.
            if(!authorized(viewer,offer.publication)){removeOffer(viewer,session,offer,"out_of_range");return;}
            int end=Math.min(offer.offset+chunkBytes(),offer.publication.bytes.length);
            JsonObject chunk=envelope("asset_chunk");chunk.addProperty("offerId",offer.id.toString());chunk.addProperty("hash",offer.publication.hash);
            chunk.addProperty("index",offer.index);chunk.addProperty("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(offer.publication.bytes,offer.offset,end)));
            if(!send(viewer,chunk,true))return;offer.offset=end;offer.index++;offer.lastProgress=tick;
        }
        if(offer.offset==offer.publication.bytes.length) {
            if(!authorized(viewer,offer.publication)){removeOffer(viewer,session,offer,"out_of_range");return;}
            JsonObject end=envelope("asset_end");end.addProperty("offerId",offer.id.toString());end.addProperty("hash",offer.publication.hash);
            if(send(viewer,end,true)){offer.status=Status.DELIVERED;offer.lastProgress=tick;limits.releaseTransfer(session.owner);offer.reserved=false;activeTransfers.remove(offer);}
        }
    }
    private void removePublication(UUID owner,String reason) {
        Publication publication=publications.remove(owner);if(publication==null)return;storedBytes-=publication.bytes.length;audiences.remove(owner);
        Player publisher=Bukkit.getPlayer(owner);
        if(publisher!=null && publisher.isOnline()){JsonObject removed=binding("private_remove",publication);removed.addProperty("reason",reason);send(publisher,removed,false);}
        for(UUID viewer:List.copyOf(publication.offeredViewers)) {
            Session session=sessions.get(viewer);if(session==null)continue;
            Offer offer=session.offers.get(owner);if(offer!=null && offer.publication==publication)removeOffer(Bukkit.getPlayer(session.owner),session,offer,reason);
        }
    }
    private void removeOffer(Player viewer,Session session,Offer offer,String reason) {
        if(!session.offers.remove(offer.publication.owner,offer))return;
        pendingTransfers.remove(offer);activeTransfers.remove(offer);offer.publication.offeredViewers.remove(session.owner);
        if(offer.reserved){limits.releaseTransfer(session.owner);offer.reserved=false;}
        if(viewer!=null && viewer.isOnline()){JsonObject remove=binding("private_remove",offer.publication);remove.addProperty("reason",reason);send(viewer,remove,false);}
    }
    private void endSession(UUID owner,Session session,String reason) {
        cancelUpload(session);removePublication(owner,reason);
        for(Offer offer:List.copyOf(session.offers.values()))removeOffer(Bukkit.getPlayer(owner),session,offer,reason);
        audiences.removeViewer(owner);
    }
    private void clearSessions(String reason) {
        for(var entry:List.copyOf(sessions.entrySet()))endSession(entry.getKey(),entry.getValue(),reason);
        sessions.clear();publications.clear();storedBytes=0;audiences.clear();pendingTransfers.clear();activeTransfers.clear();maintenanceInitialized=false;
    }
    private void sendState(Player player,Publication publication) {JsonObject state=binding("private_state",publication);addAppearance(state,publication);send(player,state,false);}
    private boolean fitsAppearance(JsonObject appearance,JsonObject extra) {
        return GSON.toJson(appearance).getBytes(StandardCharsets.UTF_8).length+GSON.toJson(extra).getBytes(StandardCharsets.UTF_8).length<=policy.maxPayload()-768;
    }
    private static void addAppearance(JsonObject packet,Publication publication) {
        packet.addProperty("sequence",publication.sequence);packet.add("appearance",publication.appearance.deepCopy());packet.add("extra",publication.extra.deepCopy());
    }
    private boolean send(Player viewer,JsonObject packet,boolean asset) {
        if(viewer==null || !viewer.isOnline())return false;byte[] bytes=GSON.toJson(packet).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>policy.maxPayload() || !limits.allowOutbound(viewer.getUniqueId(),bytes.length,asset,System.nanoTime(),tick()))return false;
        try{viewer.sendPluginMessage(plugin,CHANNEL,bytes);return true;}catch(RuntimeException disconnected){return false;}
    }
    private void error(Player player,String code){JsonObject packet=envelope("error");packet.addProperty("code",code);send(player,packet,false);}
    private static JsonObject envelope(String type){JsonObject packet=new JsonObject();packet.addProperty("protocol",PROTOCOL);packet.addProperty("type",type);return packet;}
    private static JsonObject binding(String type,Publication publication){JsonObject packet=envelope(type);packet.addProperty("owner",publication.owner.toString());packet.addProperty("generation",publication.generation.toString());packet.addProperty("hash",publication.hash);return packet;}
    private static boolean matches(Publication publication,UUID generation,String hash){return publication!=null && publication.generation.equals(generation) && publication.hash.equals(hash);}
    private static Offer findOffer(Session session,UUID id,String hash){return session.offers.values().stream().filter(offer->offer.id.equals(id) && offer.publication.hash.equals(hash)).findFirst().orElse(null);}
    private static long tick(){return Integer.toUnsignedLong(Bukkit.getCurrentTick());}
    private static long distance(long tick,long previous){return(tick-previous)&0xffffffffL;}

    static JsonObject decode(byte[] bytes) throws IOException {
        JsonObject packet=PrivateModelBundle.object(PrivateModelBundle.parseJson(bytes,30000));
        if(PrivateModelBundle.integer(packet,"protocol",PROTOCOL,PROTOCOL)!=PROTOCOL)throw new IOException("protocol");
        String type=PrivateModelBundle.string(packet,"type",32);Set<String> keys=new HashSet<>(Set.of("protocol","type"));
        switch(type) {
            case "hello" -> {
                keys.add("capabilities");JsonElement element=packet.get("capabilities");
                if(element==null || !element.isJsonArray() || element.getAsJsonArray().size()!=1 || !element.getAsJsonArray().get(0).isJsonPrimitive()
                        || !element.getAsJsonArray().get(0).getAsJsonPrimitive().isString() || !CAPABILITY.equals(element.getAsJsonArray().get(0).getAsString()))throw new IOException("capabilities");
            }
            case "upload_offer" -> {
                keys.addAll(Set.of("generation","hash","bytes","kind","appearance"));uuid(packet,"generation");hash(packet);PrivateModelBundle.integer(packet,"bytes",1,PrivateModelBundle.MAX_BYTES);
                if(!Set.of("bbmodel","ysm").contains(PrivateModelBundle.string(packet,"kind",16)))throw new IOException("kind");appearance(packet.get("appearance"));
            }
            case "upload_chunk" -> {keys.addAll(Set.of("uploadId","index","data"));uuid(packet,"uploadId");PrivateModelBundle.integer(packet,"index",0,16383);PrivateModelBundle.string(packet,"data",16000);}
            case "upload_end" -> {keys.add("uploadId");uuid(packet,"uploadId");}
            case "private_status" -> {keys.addAll(Set.of("offerId","hash","status"));uuid(packet,"offerId");hash(packet);if(!Set.of("cached","missing","rejected").contains(PrivateModelBundle.string(packet,"status",16)))throw new IOException("status");}
            case "private_ready" -> {keys.addAll(Set.of("owner","generation","hash"));uuid(packet,"owner");uuid(packet,"generation");hash(packet);}
            case "private_heartbeat" -> {
                keys.add("bindings");JsonElement value=packet.get("bindings");if(value==null || !value.isJsonArray() || value.getAsJsonArray().size()>64)throw new IOException("bindings");
                Set<UUID> owners=new HashSet<>();for(JsonElement item:value.getAsJsonArray()){JsonObject binding=PrivateModelBundle.object(item);PrivateModelBundle.requireKeys(binding,Set.of("owner","generation","hash"));
                    if(!owners.add(uuid(binding,"owner")))throw new IOException("duplicate_binding");uuid(binding,"generation");hash(binding);}
            }
            case "private_state" -> {keys.addAll(Set.of("generation","hash","appearance","extra"));uuid(packet,"generation");hash(packet);appearance(packet.get("appearance"));extra(packet.get("extra"));}
            case "private_event" -> {
                keys.addAll(Set.of("generation","hash","args"));uuid(packet,"generation");hash(packet);JsonElement value=packet.get("args");
                if(value==null || !value.isJsonArray() || value.getAsJsonArray().size()>16)throw new IOException("event_args");
                for(JsonElement arg:value.getAsJsonArray())number(arg,-Double.MAX_VALUE,Double.MAX_VALUE);
            }
            case "clear" -> {}
            default -> throw new IOException("type");
        }
        PrivateModelBundle.requireKeys(packet,keys);return packet;
    }
    static JsonObject appearance(JsonElement element) throws IOException {
        JsonObject source=PrivateModelBundle.object(element),result=new JsonObject();
        Set<String> allowed=Set.of("scale","offsetX","offsetY","offsetZ","textureId","variables","radioSelections");
        if(!allowed.containsAll(source.keySet()))throw new IOException("appearance_fields");
        result.addProperty("scale",source.has("scale")?number(source.get("scale"),.05,8):1);
        for(String key:List.of("offsetX","offsetY","offsetZ"))result.addProperty(key,source.has(key)?number(source.get(key),-32,32):0);
        result.addProperty("textureId",source.has("textureId")?PrivateModelBundle.string(source,"textureId",128):"");
        for(String field:List.of("variables","radioSelections")) {
            JsonObject values=source.has(field)?PrivateModelBundle.object(source.get(field)):new JsonObject(),safe=new JsonObject();
            if(values.size()>128)throw new IOException("appearance_variable_limit");
            int roamingCount=0;
            for(var entry:values.entrySet()) {
                String name=entry.getKey().toLowerCase(Locale.ROOT);
                if(name.isBlank() || name.length()>128 || name.codePoints().anyMatch(Character::isISOControl) || safe.has(name)
                        || field.equals("variables") && (!name.equals(entry.getKey()) || !MODEL_VARIABLE.matcher(name).matches()))throw new IOException("appearance_variable_name");
                if(field.equals("variables") && name.startsWith(ROAMING_PREFIX)
                        && (name.length()-ROAMING_PREFIX.length()>32 || !ROAMING_NAME.matcher(name.substring(ROAMING_PREFIX.length())).matches()
                        || ++roamingCount>64))throw new IOException("appearance_roaming_limit");
                double value=number(entry.getValue(),field.equals("variables")?-1_000_000:0,field.equals("variables")?1_000_000:255);
                if(field.equals("radioSelections") && value!=(int)value)throw new IOException("appearance_radio_integer");
                safe.addProperty(name,value);
            }
            result.add(field,safe);
        }
        return result;
    }
    static JsonObject extra(JsonElement element) throws IOException {
        JsonObject source=PrivateModelBundle.object(element);PrivateModelBundle.requireKeys(source,Set.of("id","loop","locked","sequence"));
        String id=PrivateModelBundle.string(source,"id",128);if(!id.isEmpty() && !NAME.matcher(id).matches())throw new IOException("animation_id");
        if(!Set.of("ONCE","LOOP","HOLD").contains(PrivateModelBundle.string(source,"loop",8)))throw new IOException("animation_loop");
        if(!source.get("locked").isJsonPrimitive() || !source.get("locked").getAsJsonPrimitive().isBoolean())throw new IOException("animation_locked");
        PrivateModelBundle.integer(source,"sequence",0,MAX_SEQUENCE);return source.deepCopy();
    }
    private static double number(JsonElement value,double min,double max) throws IOException {
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IOException("expected_number");
        double number=value.getAsDouble();if(!Double.isFinite(number) || number<min || number>max)throw new IOException("number_range");return number;
    }
    private static UUID uuid(JsonObject packet,String key) throws IOException {
        String value=PrivateModelBundle.string(packet,key,36);
        try{UUID uuid=UUID.fromString(value);if(!uuid.toString().equals(value))throw new IOException("uuid");return uuid;}catch(IllegalArgumentException invalid){throw new IOException("uuid",invalid);}
    }
    private static String hash(JsonObject packet) throws IOException {String hash=PrivateModelBundle.string(packet,"hash",64);if(!HASH.matcher(hash).matches())throw new IOException("hash");return hash;}
    private enum Status {OFFERED,CACHED,QUEUED,SENDING,DELIVERED,READY}
    private static final class Session {
        final UUID owner;final Map<UUID,Offer> offers=new LinkedHashMap<>();final Map<UUID,UUID> rejected=new LinkedHashMap<>();Upload upload;
        Session(UUID owner){this.owner=owner;}
    }
    private static final class Upload {
        final UUID owner,id,generation;final String hash,kind;final byte[] bytes;final JsonObject appearance;final long created,reservation;
        int offset,index;long lastProgress;boolean validating,released,transferReserved=true;
        Upload(UUID owner,UUID id,UUID generation,String hash,String kind,int bytes,JsonObject appearance,long tick,long reservation){this.owner=owner;this.id=id;this.generation=generation;this.hash=hash;this.kind=kind;this.bytes=new byte[bytes];this.appearance=appearance;created=lastProgress=tick;this.reservation=reservation;}
    }
    private static final class Publication {
        final UUID owner,generation;final String hash,kind;final byte[] bytes;JsonObject appearance,extra;
        final Set<UUID> offeredViewers=new HashSet<>();
        long sequence,eventSequence,lease;
        Publication(UUID owner,UUID generation,String hash,String kind,byte[] bytes,JsonObject appearance,long tick){this.owner=owner;this.generation=generation;this.hash=hash;this.kind=kind;this.bytes=bytes;this.appearance=appearance;lease=tick;
            extra=new JsonObject();extra.addProperty("id","");extra.addProperty("loop","ONCE");extra.addProperty("locked",false);extra.addProperty("sequence",0);}
    }
    private static final class Offer {
        final UUID id=UUID.randomUUID(),viewer;final Publication publication;final long created;
        Status status=Status.OFFERED;long lastProgress,lease;int offset,index;boolean reserved;
        Offer(UUID viewer,Publication publication,long tick){this.viewer=viewer;this.publication=publication;created=lastProgress=tick;}
    }
    private record AudienceCandidate(UUID viewer,double distance) {}
    private static final class Rates {final Rate state=new Rate(),event=new Rate();final HelloRate hello=new HelloRate();}
    private static final class HelloRate {
        long last;boolean initialized;
        boolean take(long now){if(initialized && now-last<1_000_000_000L)return false;initialized=true;last=now;return true;}
    }
    static final class Rate {
        long start;int count;boolean initialized;
        boolean take(long now){if(!initialized || now-start>=1_000_000_000L){initialized=true;start=now;count=0;}if(count>=8)return false;count++;return true;}
    }
}
