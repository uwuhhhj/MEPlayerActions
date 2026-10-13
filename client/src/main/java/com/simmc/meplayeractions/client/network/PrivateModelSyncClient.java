package com.simmc.meplayeractions.client.network;

import com.simmc.meplayeractions.client.ClientOptions;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.NativeModelBundle;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Optional server-mediated native model sharing. Offers authorize bytes, leases authorize display. */
public final class PrivateModelSyncClient {
    public static final int PROTOCOL = 1;
    public static final String UPLOAD_CREDIT = "private_upload_credit_v1";
    public static final String VIEW_STATE = "private_view_state_v1";
    private static final long SECOND = 1_000_000_000L;
    private static final long DELETE_TIMEOUT = 90 * SECOND;
    private static final int MAX_REMOTES = 16;

    public interface Host {
        boolean channelAvailable();

        /** Viewing can pause independently of the owner's publication and saved upload directory. */
        default boolean viewEnabled() {
            return true;
        }

        boolean send(JsonObject packet);

        Local local();

        CompletableFuture<byte[]> bundle(String modelId);

        /** Production hosts run both source reading and ZIP identity validation on their bounded worker. */
        default CompletableFuture<SourceIdentity> sourceIdentity(String modelId) {
            return bundle(modelId).thenApply(bytes -> {
                try {
                    return new SourceIdentity(AssetTransfer.hash(bytes), NativeModelBundle.validate(bytes).kind(),
                            bytes.length);
                } catch (IOException invalid) {
                    throw new CompletionException(invalid);
                }
            });
        }

        CompletableFuture<byte[]> cached(String hash);

        CompletableFuture<LocalModelLibrary.Loaded> decode(byte[] bundle, String texture);

        void cache(String hash, byte[] bundle);

        void dispatch(Runnable task);

        boolean prepare(Remote remote, LocalModelLibrary.Loaded loaded);

        default void prepareAsync(Remote remote, LocalModelLibrary.Loaded loaded, BooleanSupplier current,
                Consumer<Boolean> completion) {
            completion.accept(current.getAsBoolean() && prepare(remote, loaded));
        }

        void remove(UUID owner, UUID generation);

        void state(Remote remote);

        void event(UUID owner, UUID generation, List<Double> args);

        /** A confirmed deletion of the active publication stops sharing, while local source files remain. */
        default void savedModelDeleted(String modelId, String hash) {
        }
    }

    public record Local(UUID owner, String modelId, JsonObject appearance, JsonObject extra) {
        public Local {
            appearance = appearance.deepCopy();
            extra = extra.deepCopy();
        }
    }

    /** Exact confirmed publication identity; saved archives and pending uploads are independent. */
    public record PublicationIdentity(String modelId, UUID generation, String hash) {
    }

    public enum UploadPhase {
        NOT_UPLOADED, BUILDING, WAITING_APPROVAL, UPLOADING, VALIDATING, PUBLISHED, UPLOADED, FAILED
    }

    public enum ReceivingPhase {
        CHECKING_CACHE, WAITING_DOWNLOAD, DOWNLOADING, DECODING, WAITING_ACK, ACTIVE, FAILED
    }

    /** A live, viewer-authorized offer. Its metadata never permits a server disguise command. */
    public record ReceivedModel(UUID owner, UUID generation, String modelId, String hash, String kind, int bytes,
            int receivedBytes, boolean cacheHit, ReceivingPhase phase, boolean ready, boolean active, String textureId,
            String failure) {
    }

    /** Saved status requires the owner's current server directory and matching local source bytes. */
    public record UploadState(String modelId, UploadPhase phase, String hash, int totalBytes, int sentBytes,
            boolean published, String message) {
        public boolean uploaded() {
            return this.phase == UploadPhase.UPLOADED;
        }

        public boolean inProgress() {
            return switch (this.phase) {
            case BUILDING, WAITING_APPROVAL, UPLOADING, VALIDATING -> true;
            default -> false;
            };
        }
    }

    public static final class Remote {
        public final UUID owner, generation, offerId;
        public final String hash, kind;
        public final String modelId;
        public final int bytes;
        public JsonObject appearance, extra;
        public long sequence, eventSequence = -1, lastLease;
        public boolean ready, active, flying;
        private boolean cacheHit;
        private ReceivingPhase phase = ReceivingPhase.CHECKING_CACHE;
        private byte[] bundle;
        private long decodeRevision;
        private String pendingStatus = "";
        private boolean statusSent;
        private long lastStatusAttempt = -1, lastReadyAttempt = -1;
        private Download download;

        private Remote(UUID owner, UUID generation, UUID offerId, String hash, String kind, String modelId, int bytes,
                JsonObject appearance, JsonObject extra, long sequence, boolean flying) {
            this.owner = owner;
            this.generation = generation;
            this.offerId = offerId;
            this.hash = hash;
            this.kind = kind;
            this.modelId = modelId;
            this.bytes = bytes;
            this.appearance = appearance;
            this.extra = extra;
            this.sequence = sequence;
            this.flying = flying;
        }
    }

    private record Archive(byte[] bytes, String kind, String hash) {
    }

    public record SourceIdentity(String hash, String kind, int bytes) {
    }

    private final Host host;
    private final Map<UUID, Remote> remotes = new LinkedHashMap<>();
    private final Map<UUID, ReceivedModel> receiveFailures = new LinkedHashMap<>();
    private final Map<String, UploadState> sessionUploads = new LinkedHashMap<>();
    private final PrivateUploadCatalogSnapshot uploadCatalog = new PrivateUploadCatalogSnapshot();
    private final Map<String, SourceIdentity> sourceIdentities = new HashMap<>();
    private final ArrayDeque<String> sourceChecks = new ArrayDeque<>();
    private long sourceRevision;
    private boolean catalogueNegotiated, creditNegotiated, viewStateNegotiated, sourceChecking;
    private boolean observedViewEnabled, viewStateDirty;
    private long lastViewStateAttempt = -1;
    private int helloFallback;
    private JsonObject deleteRequest;
    private String deletingId = "", deletingHash = "";
    private UUID deletePublicationGeneration;
    private UUID uploadCatalogToken;
    private long deleteSequence, deleteStartedAt, deletePublicationSerial, deleteCatalogRevision, publicationSerial;
    private boolean deleteSent;
    private long lastDeleteAttempt = -1;
    private final Map<String, String> deleteStatuses = new LinkedHashMap<>();

    private record DeleteReceipt(String modelId, String hash, UUID publicationGeneration, long publicationSerial,
            long catalogRevision) {
    }

    private final Map<UUID, DeleteReceipt> lateDeletes = new LinkedHashMap<>();
    private long epoch, publishRevision, lastHello, lastReceived, lastHeartbeat, lastState, lastEvent, uploadStarted,
            retryAfter, clockNow;
    private boolean acknowledged, allowedUpload, allowedView, building, committed;
    private int maxPayload = 16000, maxBundleBytes = AssetTransfer.MAX_RAW, leaseTicks = 100, chunkBytes = 8192,
            uploadIndex;
    private UUID ownOwner, ownGeneration, uploadId;
    private String sourceId = "", ownHash = "", kind = "", status = "未协商私人同步", sentState = "";
    private byte[] outgoing;
    private int publicationBytes, uploadedBytes;
    private JsonObject outgoingOffer;
    private long lastUploadOfferAttempt = -1, lastUploadEndAttempt = -1;
    private boolean uploadEndSent;
    private int uploadCreditLimit, retryCount;
    private long uploadLastProgress, uploadTotalNanos = 90 * SECOND, uploadIdleNanos = 30 * SECOND,
            uploadValidationNanos = 90 * SECOND, uploadValidationStarted, retryStarted;
    private String retrySource = "", blockedSource = "";
    private JsonObject pendingUploadPart;
    private final ArrayDeque<Long> controlWindow = new ArrayDeque<>();
    private double controlTokens = 8;
    private long controlRefill = -1;
    private int controlRotation;

    public PrivateModelSyncClient(Host host) {
        this.host = Objects.requireNonNull(host);
        this.observedViewEnabled = host.viewEnabled();
    }

    public void reset() {
        this.epoch++;
        this.publishRevision++;
        this.remotes.values().forEach(r -> this.host.remove(r.owner, r.generation));
        this.remotes.clear();
        this.receiveFailures.clear();
        this.acknowledged = this.allowedUpload = this.allowedView = this.building = this.committed = false;
        this.sessionUploads.clear();
        this.catalogueNegotiated = this.creditNegotiated = this.viewStateNegotiated = this.sourceChecking = false;
        this.helloFallback = 0;
        this.sourceRevision++;
        this.uploadCatalog.reset();
        this.sourceIdentities.clear();
        this.sourceChecks.clear();
        this.observedViewEnabled = this.host.viewEnabled();
        this.viewStateDirty = false;
        this.lastViewStateAttempt = -1;
        clearDelete();
        this.uploadCatalogToken = null;
        this.deleteSequence = this.publicationSerial = 0;
        this.deleteStatuses.clear();
        this.lateDeletes.clear();
        this.sourceId = this.ownHash = this.kind = this.sentState = "";
        this.outgoing = null;
        this.ownOwner = this.ownGeneration = this.uploadId = null;
        this.uploadIndex = 0;
        this.publicationBytes = this.uploadedBytes = 0;
        this.outgoingOffer = null;
        this.lastUploadOfferAttempt = this.lastUploadEndAttempt = -1;
        this.uploadEndSent = false;
        this.pendingUploadPart = null;
        this.uploadCreditLimit = this.retryCount = 0;
        this.uploadLastProgress = this.retryStarted = 0;
        this.retrySource = this.blockedSource = "";
        this.uploadTotalNanos = this.uploadValidationNanos = 90 * SECOND;
        this.uploadIdleNanos = 30 * SECOND;
        this.uploadValidationStarted = 0;
        this.controlWindow.clear();
        this.controlTokens = 8;
        this.controlRefill = -1;
        this.controlRotation = 0;
        this.lastHello = this.lastReceived = this.lastHeartbeat = this.lastState = this.lastEvent = this.uploadStarted = this.retryAfter = 0;
        this.ownEventSequence = -1;
        this.status = "未协商私人同步";
    }

    public boolean available() {
        return this.acknowledged && this.allowedUpload && this.host.channelAvailable();
    }

    public boolean committed() {
        return available() && this.committed;
    }

    public PublicationIdentity publicationIdentity() {
        return this.ownGeneration == null || !committed() || !currentLocal() ? null
                : new PublicationIdentity(this.sourceId, this.ownGeneration, this.ownHash);
    }

    public boolean canView() {
        return this.acknowledged && this.allowedView && this.host.channelAvailable();
    }

    public String status() {
        if (this.outgoing != null && this.publicationBytes > 0 && this.uploadId != null && !this.uploadEndSent) {
            return "正在上传私人模型：" + (this.uploadedBytes * 100L / this.publicationBytes) + "% · "
                    + size(this.publicationBytes);
        }

        return this.status;
    }

    public int publicationBytes() {
        return this.publicationBytes;
    }

    public int uploadedBytes() {
        return this.uploadedBytes;
    }

    /** Read-only gallery status. Metadata verification is queued separately and never publishes. */
    public UploadState uploadState(String modelId) {
        String id = modelId == null ? "" : modelId;
        if (!this.acknowledged || !this.host.channelAvailable()) {
            return notUploaded(id);
        }

        if (this.ownGeneration != null && this.sourceId.equals(id)) {
            UploadPhase phase = this.committed
                    ? (savedIdentity(id) != null ? UploadPhase.UPLOADED : UploadPhase.PUBLISHED)
                    : (this.building ? UploadPhase.BUILDING
                            : (this.uploadId == null ? UploadPhase.WAITING_APPROVAL
                                    : (this.uploadEndSent ? UploadPhase.VALIDATING : UploadPhase.UPLOADING)));
            return new UploadState(id, phase, this.ownHash, this.publicationBytes, this.uploadedBytes,
                    this.committed && currentLocal(), status());
        }
        SourceIdentity saved = savedIdentity(id);
        if (saved != null) {
            return new UploadState(id, UploadPhase.UPLOADED, saved.hash(), saved.bytes(), 0, false, "服务器已保存 · 本机内容一致");
        }

        return this.sessionUploads.getOrDefault(id, notUploaded(id));
    }

    /** The server's owner-only directory does not confer permission to download or publish. */
    public Set<String> uploadedModelIds() {
        if (!this.acknowledged || !this.host.channelAvailable()) {
            return Set.of();
        }

        Set<String> ids = new LinkedHashSet<>();
        for (PrivateUploadCatalogSnapshot.Model entry : this.uploadCatalog.models()) {
            if (savedIdentity(entry.modelId()) != null) {
                ids.add(entry.modelId());
            }

        }

        return Collections.unmodifiableSet(ids);
    }

    /** Recheck the saved directory after the host clears its source cache; edited IDs cannot retain a badge. */
    public void invalidateSources() {
        stopPublishing();
        this.sessionUploads.clear();
        this.sourceRevision++;
        this.sourceChecking = false;
        this.sourceIdentities.clear();
        queueSourceChecks();
    }

    public String uploadedDirectoryStatus() {
        if (!this.acknowledged || !this.host.channelAvailable()) {
            return "等待服务器已上传目录";
        }

        if (!this.catalogueNegotiated) {
            return "当前服务器未提供已上传目录";
        }

        if (!this.allowedUpload) {
            return "没有私人模型上传权限";
        }

        if (!this.uploadCatalog.ready()) {
            return "正在同步服务器已上传目录";
        }

        if (!this.uploadCatalog.available()) {
            return "服务器未开放已上传目录（上传权限或资源缓存不可用）";
        }

        if (this.sourceChecking || !this.sourceChecks.isEmpty()) {
            return "正在核对本机模型与服务器保存内容";
        }

        return "服务器已保存 " + this.uploadCatalog.models().size() + " 个本人模型 · 本机匹配 " + uploadedModelIds().size() + " 个";
    }

    public boolean uploadCatalogReady() {
        return this.catalogueNegotiated && this.allowedUpload && this.host.channelAvailable()
                && this.uploadCatalog.available() && !this.sourceChecking && this.sourceChecks.isEmpty();
    }

    public String uploadCatalogStatus() {
        return uploadedDirectoryStatus();
    }

    /** Includes server-saved entries whose local file is missing or has since changed. */
    public List<PrivateUploadCatalogSnapshot.Model> uploadedModels() {
        return this.catalogueNegotiated && this.allowedUpload && this.host.channelAvailable()
                ? this.uploadCatalog.models()
                : List.of();
    }

    public boolean deletingUploadedModel(String id) {
        return this.deleteRequest != null && this.deletingId.equals(id);
    }

    public String uploadDeleteStatus(String id) {
        return deletingUploadedModel(id) ? "正在等待服务器确认删除" : this.deleteStatuses.getOrDefault(id, "");
    }

    public boolean requestDeleteUploadedModel(String id) {
        PrivateUploadCatalogSnapshot.Model entry = uploadedModels().stream().filter(model -> model.modelId().equals(id))
                .findFirst().orElse(null);
        return entry != null && requestDeleteUploadedModel(entry);
    }

    /** A confirmation targets the saved version originally displayed, rather than a newer same-ID replacement. */
    public boolean requestDeleteUploadedModel(PrivateUploadCatalogSnapshot.Model expected) {
        if (expected == null) {
            return false;
        }
        String id = expected.modelId();
        if (!this.acknowledged || !this.catalogueNegotiated || this.uploadCatalogToken == null || !this.allowedUpload
                || !this.uploadCatalog.available() || this.deleteRequest != null
                || this.deleteSequence >= 9_007_199_254_740_991L
                || this.ownGeneration != null && !this.committed && this.sourceId.equals(id)) {
            return false;
        }

        PrivateUploadCatalogSnapshot.Model entry = uploadedModels().stream().filter(model -> model.modelId().equals(id))
                .findFirst().orElse(null);
        if (entry == null) {
            return false;
        }

        if (!entry.equals(expected)) {
            rememberDeleteStatus(id, "服务器保存内容已变化，请重新确认删除");
            return false;
        }
        JsonObject request = envelope("upload_delete");
        request.addProperty("requestId", UUID.randomUUID().toString());
        request.addProperty("modelId", id);
        request.addProperty("hash", entry.hash());
        request.addProperty("catalogToken", this.uploadCatalogToken.toString());
        request.addProperty("requestSequence", ++this.deleteSequence);
        request.addProperty("kind", entry.kind());
        request.addProperty("bytes", entry.bytes());
        this.deleteRequest = request;
        this.deletingId = id;
        this.deletingHash = entry.hash();
        this.lastDeleteAttempt = -1;
        this.deletePublicationGeneration = this.committed && this.sourceId.equals(id) ? this.ownGeneration : null;
        this.deleteStartedAt = this.clockNow;
        this.deleteSent = false;
        this.deletePublicationSerial = this.publicationSerial;
        this.deleteCatalogRevision = this.uploadCatalog.revision();
        this.deleteStatuses.remove(id);
        return true;
    }

    private DeleteReceipt deleteReceipt() {
        return new DeleteReceipt(this.deletingId, this.deletingHash, this.deletePublicationGeneration,
                this.deletePublicationSerial, this.deleteCatalogRevision);
    }

    private void clearDelete() {
        this.deleteRequest = null;
        this.deletingId = this.deletingHash = "";
        this.deletePublicationGeneration = null;
        this.lastDeleteAttempt = -1;
        this.deleteStartedAt = this.deletePublicationSerial = this.deleteCatalogRevision = 0;
        this.deleteSent = false;
    }

    private void expireDelete() {
        if (this.deleteSent) {
            this.lateDeletes.put(UUID.fromString(this.deleteRequest.get("requestId").getAsString()), deleteReceipt());
            while (this.lateDeletes.size() > 16) {
                this.lateDeletes.remove(this.lateDeletes.keySet().iterator().next());
            }

        }
        rememberDeleteStatus(this.deletingId, "未确认删除，请刷新目录或重试");
        clearDelete();
    }

    private SourceIdentity savedIdentity(String id) {
        if (!this.catalogueNegotiated || !this.allowedUpload || !this.uploadCatalog.available()) {
            return null;
        }

        SourceIdentity local = this.sourceIdentities.get(id);
        if (local == null) {
            return null;
        }

        return this.uploadCatalog.models().stream()
                .anyMatch(entry -> entry.modelId().equals(id) && entry.hash().equals(local.hash())
                        && entry.kind().equals(local.kind()) && entry.bytes() == local.bytes()) ? local : null;
    }

    private void queueSourceChecks() {
        this.sourceChecks.clear();
        if (this.catalogueNegotiated && this.allowedUpload) {
            for (PrivateUploadCatalogSnapshot.Model entry : this.uploadCatalog.models()) {
                if (!this.sourceIdentities.containsKey(entry.modelId())) {
                    this.sourceChecks.addLast(entry.modelId());
                }

            }

        }

        checkNextSource();
    }

    private void checkNextSource() {
        if (this.sourceChecking || this.sourceChecks.isEmpty() || !this.acknowledged) {
            return;
        }

        String id = this.sourceChecks.removeFirst();
        this.sourceChecking = true;
        long revision = this.sourceRevision, connection = this.epoch;
        CompletableFuture<SourceIdentity> reading;
        try {
            reading = this.host.sourceIdentity(id);
        } catch (RuntimeException failed) {
            reading = CompletableFuture.failedFuture(failed);
        }
        reading.whenComplete((identity, error) -> this.host.dispatch(() -> {
            if (connection != this.epoch || revision != this.sourceRevision) {
                return;
            }

            this.sourceChecking = false;
            if (error == null) {
                rememberSource(id, identity);
            }
            checkNextSource();
        }));
    }

    private void rememberSource(String id, SourceIdentity identity) {
        this.sourceIdentities.put(id, identity);
        while (this.sourceIdentities.size() > PrivateUploadCatalogSnapshot.MAX_MODELS) {
            String stale = this.sourceIdentities.keySet().stream()
                    .filter(key -> !key.equals(id)
                            && this.uploadCatalog.models().stream().noneMatch(model -> model.modelId().equals(key)))
                    .findFirst().orElseGet(() -> this.sourceIdentities.keySet().stream().filter(key -> !key.equals(id))
                            .findFirst().orElse(id));
            this.sourceIdentities.remove(stale);
        }
    }

    private static UploadState notUploaded(String id) {
        return new UploadState(id, UploadPhase.NOT_UPLOADED, "", 0, 0, false, "尚未上传至当前服务器");
    }

    private void rememberUploadState(UploadState state) {
        this.sessionUploads.put(state.modelId(), state);
        while (this.sessionUploads.size() > 160) {
            this.sessionUploads.remove(this.sessionUploads.keySet().iterator().next());
        }

    }

    public Collection<Remote> remotes() {
        return List.copyOf(this.remotes.values());
    }

    public Remote remote(UUID owner) {
        return this.remotes.get(owner);
    }

    public List<ReceivedModel> receivedModels() {
        ArrayList<PrivateModelSyncClient.ReceivedModel> values = new ArrayList<ReceivedModel>();
        values.addAll(this.receiveFailures.values());
        this.remotes.values().forEach(remote -> values.add(receivedModel(remote, remote.phase, "")));
        return List.copyOf(values);
    }

    private static ReceivedModel receivedModel(Remote remote, ReceivingPhase phase, String failure) {
        return new ReceivedModel(remote.owner, remote.generation, remote.modelId, remote.hash, remote.kind,
                remote.bytes,
                remote.download == null ? (remote.bundle == null ? 0 : remote.bytes) : remote.download.received,
                remote.cacheHit, phase, phase == ReceivingPhase.FAILED ? false : remote.ready,
                phase == ReceivingPhase.FAILED ? false : remote.active,
                remote.appearance.get("textureId").getAsString(), failure);
    }

    private void receiveFailed(Remote remote, String reason) {
        String failure = reason == null || reason.isBlank() ? "客户端模型准备或绘制失败" : reason.replaceAll("[\\p{Cntrl}]", " ");
        if (failure.length() > 256) {
            failure = failure.substring(0, 256);
        }

        this.receiveFailures.put(remote.owner, receivedModel(remote, ReceivingPhase.FAILED, failure));
        while (this.receiveFailures.size() > MAX_REMOTES) {
            this.receiveFailures.remove(this.receiveFailures.keySet().iterator().next());
        }

    }

    public boolean viewingEnabled() {
        return this.host.viewEnabled();
    }

    public boolean viewerStateNegotiated() {
        return this.viewStateNegotiated;
    }

    /** Called after the host flips rendering. In-flight callbacks lose their exact remote identity. */
    public void viewerRenderingChanged() {
        boolean enabled = this.host.viewEnabled();
        if (this.observedViewEnabled != enabled) {
            this.observedViewEnabled = enabled;
            this.viewStateDirty = true;
            this.lastViewStateAttempt = -1;
        }
        if (!enabled) {
            for (Remote remote : List.copyOf(this.remotes.values())) {
                remove(remote);
            }

        }

        if (this.acknowledged && this.viewStateNegotiated && this.viewStateDirty) {
            flushViewerState(this.clockNow);
        }

    }

    /** Server-authoritative creative flight is not present in vanilla remote player abilities. */
    public boolean isFlying(UUID owner) {
        Remote remote = this.remotes.get(owner);
        return remote != null && remote.active && remote.flying;
    }

    /** Reject the exact prepared instance; a delayed render failure cannot remove a newer generation. */
    public boolean rejectRemote(UUID owner, UUID generation) {
        return rejectRemote(owner, generation, "客户端模型绘制失败，已恢复原版玩家");
    }

    public boolean rejectRemote(UUID owner, UUID generation, String reason) {
        Remote remote = this.remotes.get(owner);
        if (remote == null || !remote.generation.equals(generation)) {
            return false;
        }

        receiveFailed(remote, reason);
        status(remote.offerId, remote.hash, "rejected");
        remove(remote);
        return true;
    }

    public void stopPublishing() {
        stopPublishing(true);
    }

    /** Explicit user retry only. Normal ticks retain the terminal and bounded automatic retry policy. */
    public void restartPublishing() {
        // Retire the old intent before clearing its generation: a delayed deletion ACK can arrive
        // before the next tick has archived the newly requested publication.
        this.publicationSerial++;
        Set<String> failedSources = new HashSet<>(List.of(this.sourceId, this.retrySource, this.blockedSource));
        Local local = this.host.local();
        if (local != null) {
            failedSources.add(local.modelId());
        }

        stopPublishing(true);
        for (String id : failedSources) {
            UploadState previous = this.sessionUploads.get(id);
            if (previous != null && previous.phase() == UploadPhase.FAILED) {
                this.sessionUploads.remove(id);
            }

        }
        this.retryAfter = this.clockNow;
        this.status = "已请求重新分享，等待本机模型准备";
    }

    private void stopPublishing(boolean resetRetries) {
        if (this.ownGeneration != null) {
            this.sessionUploads.remove(this.sourceId);
        }
        if (this.acknowledged && this.ownGeneration != null) {
            send(envelope("clear"));
        }

        this.publishRevision++;
        this.building = this.committed = false;
        this.ownOwner = this.ownGeneration = this.uploadId = null;
        this.sourceId = this.ownHash = this.sentState = "";
        this.outgoing = null;
        this.ownEventSequence = -1;
        this.publicationBytes = this.uploadedBytes = 0;
        this.outgoingOffer = null;
        this.lastUploadOfferAttempt = this.lastUploadEndAttempt = -1;
        this.uploadEndSent = false;
        this.pendingUploadPart = null;
        this.uploadCreditLimit = 0;
        if (resetRetries) {
            this.retryCount = 0;
            this.retryStarted = 0;
            this.retrySource = this.blockedSource = "";
        }
        this.status = this.acknowledged ? "私人模型仅自己可见" : "未协商私人同步";
    }

    private void failPublishing(String message) {
        UploadState failure = new UploadState(this.sourceId, UploadPhase.FAILED, this.ownHash, this.publicationBytes,
                this.uploadedBytes, false, message);
        stopPublishing(false);
        if (!failure.modelId().isEmpty()) {
            rememberUploadState(failure);
        }
        this.status = message;
    }

    private void publicationFailure(String message, boolean retryable, long delay, long now) {
        String failedSource = this.sourceId;
        if (!failedSource.equals(this.retrySource)) {
            this.retrySource = failedSource;
            this.retryCount = 0;
            this.retryStarted = now;
        }
        this.retryCount++;
        failPublishing(message);
        this.retryAfter = now + Math.max(SECOND, delay);
        if (!retryable || this.retryCount >= 3 || now - this.retryStarted >= 5 * 60 * SECOND) {
            this.blockedSource = failedSource;
            this.status = message + "；请调整模型或重新开启分享后重试";
        }
    }

    public void tick(long now) {
        this.clockNow = now;
        if (!this.host.channelAvailable()) {
            if (this.acknowledged || this.ownGeneration != null || !this.remotes.isEmpty()) {
                reset();
            }
            return;
        }
        if (this.acknowledged && now - this.lastReceived > this.leaseTicks * 50_000_000L) {
            reset();
        }

        if (this.observedViewEnabled != this.host.viewEnabled()) {
            viewerRenderingChanged();
        }

        if (!this.acknowledged && now - this.lastHello > 3 * SECOND) {
            JsonObject hello = envelope("hello");
            JsonArray caps = new JsonArray();
            caps.add("private_models_v1");
            if (this.helloFallback < 3) {
                caps.add(PrivateUploadCatalogSnapshot.CAPABILITY);
            }

            if (this.helloFallback < 2) {
                caps.add(UPLOAD_CREDIT);
            }

            if (this.helloFallback == 0) {
                caps.add(VIEW_STATE);
                hello.addProperty("viewEnabled", this.host.viewEnabled());
            }
            hello.add("capabilities", caps);
            if (send(hello)) {
                this.lastHello = now;
            }

        }
        if (!this.acknowledged) {
            return;
        }

        if (this.viewStateNegotiated && this.viewStateDirty) {
            flushViewerState(now);
        }

        if (this.deleteRequest != null && now - this.deleteStartedAt >= DELETE_TIMEOUT) {
            expireDelete();
        }

        if (this.deleteRequest != null && this.allowedUpload
                && (this.lastDeleteAttempt < 0 || now - this.lastDeleteAttempt >= SECOND)) {
            Boolean sent = sendControl(this.deleteRequest, now);
            if (sent != null) {
                this.lastDeleteAttempt = now;
                this.deleteSent |= sent;
            }
        }
        for (Remote remote : List.copyOf(this.remotes.values())) {
            long timeout = remote.active ? this.leaseTicks * 50_000_000L
                    : (remote.download != null ? 15 * SECOND : 60 * SECOND);
            if (now - remote.lastLease > timeout) {
                remove(remote);
            }

        }
        Local local = this.host.local();
        if (local == null || !this.allowedUpload) {
            if (this.ownGeneration != null) {
                stopPublishing();
            }
            if (local == null) {
                this.retryCount = 0;
                this.retryStarted = 0;
                this.retrySource = this.blockedSource = "";
            }
        } else if (!this.sourceId.equals(local.modelId()) && !this.blockedSource.equals(local.modelId())
                && !this.building && now >= this.retryAfter && (!deletingUploadedModel(local.modelId()))) {
            beginPublish(local, now);
        }

        if (this.outgoing != null && !this.committed) {
            if (this.uploadId == null && this.outgoingOffer != null) {
                if (this.lastUploadOfferAttempt < 0 || now - this.lastUploadOfferAttempt >= SECOND) {
                    Boolean sent = sendControl(this.outgoingOffer, now);
                    if (sent != null) {
                        this.lastUploadOfferAttempt = now;
                    }
                }
            } else if (this.uploadId != null) {
                int from = this.uploadIndex * this.chunkBytes;
                if (from < this.outgoing.length
                        && (!this.creditNegotiated || this.uploadIndex < this.uploadCreditLimit)) {
                    if (this.pendingUploadPart == null) {
                        this.pendingUploadPart = envelope("upload_chunk");
                        this.pendingUploadPart.addProperty("uploadId", this.uploadId.toString());
                        this.pendingUploadPart.addProperty("index", this.uploadIndex);
                        this.pendingUploadPart.addProperty("data",
                                Base64.getEncoder().encodeToString(Arrays.copyOfRange(this.outgoing, from,
                                        Math.min(this.outgoing.length, from + this.chunkBytes))));
                    }
                    if (send(this.pendingUploadPart)) {
                        this.pendingUploadPart = null;
                        this.uploadIndex++;
                        this.uploadedBytes = Math.min(this.outgoing.length, this.uploadIndex * this.chunkBytes);
                        this.uploadLastProgress = now;
                    }
                } else if (from >= this.outgoing.length
                        && (this.lastUploadEndAttempt < 0 || now - this.lastUploadEndAttempt >= SECOND)) {
                    JsonObject end = envelope("upload_end");
                    end.addProperty("uploadId", this.uploadId.toString());
                    Boolean sent = sendControl(end, now);
                    if (sent != null) {
                        this.lastUploadEndAttempt = now;
                        if (sent) {
                            if (!this.uploadEndSent) {
                                this.uploadValidationStarted = now;
                            }
                            this.uploadEndSent = true;
                            this.status = "上传结束，等待服务器校验 · " + size(this.publicationBytes);
                        }
                    }
                }
            }
        }
        List<Remote> controls = List.copyOf(this.remotes.values());
        if (!controls.isEmpty()) {
            int start = this.controlRotation % controls.size();
            this.controlRotation = (start + 1) % controls.size();
            for (int i = 0; i < controls.size(); i++) {
                flushControl(controls.get((start + i) % controls.size()), now);
            }

        }
        if (this.ownGeneration != null && !this.committed
                && (now - this.uploadStarted > this.uploadTotalNanos
                        || this.uploadEndSent && now - this.uploadValidationStarted > this.uploadValidationNanos
                        || this.uploadId != null && !this.uploadEndSent
                                && now - this.uploadLastProgress > this.uploadIdleNanos)) {
            publicationFailure("私人模型同步超时", true, 10 * SECOND, now);
        }
        if (this.committed && local != null && now - this.lastState >= SECOND / 8) {
            String signature = local.appearance().toString() + local.extra();
            if (!signature.equals(this.sentState) || now - this.lastState >= 2 * SECOND) {
                JsonObject state = identity("private_state");
                state.add("appearance", local.appearance().deepCopy());
                state.add("extra", local.extra().deepCopy());
                if (send(state)) {
                    this.sentState = signature;
                    this.lastState = now;
                }
            }
        }
        if (now - this.lastHeartbeat >= SECOND) {
            if (send(heartbeatPacket())) {
                this.lastHeartbeat = now;
            }

        }
    }

    private JsonObject heartbeatPacket() {
        JsonObject heartbeat = envelope("private_heartbeat");
        JsonArray values = new JsonArray();
        heartbeat.add("bindings", values);
        if (this.viewStateNegotiated) {
            heartbeat.addProperty("viewEnabled", this.host.viewEnabled());
        }

        Local local = this.host.local();
        if (this.committed && local != null) {
            values.add(binding(local.owner(), this.ownGeneration, this.ownHash));
        }

        if (this.host.viewEnabled()) {
            for (Remote remote : this.remotes.values()) {
                if (remote.ready) {
                    values.add(binding(remote.owner, remote.generation, remote.hash));
                }

            }

        }

        return heartbeat;
    }

    private void flushViewerState(long now) {
        if (this.lastViewStateAttempt >= 0 && now - this.lastViewStateAttempt < SECOND) {
            return;
        }

        Boolean sent = sendControl(heartbeatPacket(), now);
        if (sent != null) {
            this.lastViewStateAttempt = now;
            if (sent) {
                this.lastHeartbeat = now;
            }
        }
    }

    private boolean send(JsonObject packet) {
        byte[] bytes = packet.toString().getBytes(StandardCharsets.UTF_8);
        return bytes.length <= (this.acknowledged ? this.maxPayload : ActionPayload.MAX_BYTES)
                && this.host.send(packet);
    }

    /** Null defers the packet without starting its retry clock; authorized chunks keep their own cadence. */
    private Boolean sendControl(JsonObject packet, long now) {
        if (this.controlRefill < 0) {
            this.controlRefill = now;
        }

        if (now > this.controlRefill) {
            this.controlTokens = Math.min(8, this.controlTokens + (now - this.controlRefill) * 16d / SECOND);
            this.controlRefill = now;
        }
        while (!this.controlWindow.isEmpty() && now - this.controlWindow.getFirst() >= SECOND) {
            this.controlWindow.removeFirst();
        }

        if (this.controlTokens < 1 || this.controlWindow.size() >= 16) {
            return null;
        }

        this.controlTokens--;
        this.controlWindow.addLast(now);
        return send(packet);
    }

    private void beginPublish(Local local, long now) {
        stopPublishing(false);
        this.publicationSerial++;
        this.building = true;
        this.sourceId = local.modelId();
        this.ownOwner = local.owner();
        this.ownGeneration = UUID.randomUUID();
        this.uploadStarted = this.uploadLastProgress = now;
        this.status = "正在归档私人模型";
        if (!this.retrySource.equals(this.sourceId)) {
            this.retrySource = this.sourceId;
            this.retryCount = 0;
            this.retryStarted = now;
            this.blockedSource = "";
        }
        this.sessionUploads.remove(this.sourceId);
        long revision = this.publishRevision;
        UUID generation = this.ownGeneration;
        this.host.bundle(local.modelId()).thenApply(bytes -> {
            try {
                return new Archive(bytes, NativeModelBundle.validate(bytes).kind(), AssetTransfer.hash(bytes));
            } catch (IOException invalid) {
                throw new CompletionException(invalid);
            }
        }).whenComplete((archive, error) -> this.host.dispatch(() -> {
            if (revision != this.publishRevision || !generation.equals(this.ownGeneration)) {
                return;
            }

            this.building = false;
            Local current = this.host.local();
            if (error != null || current == null || !current.modelId().equals(this.sourceId)) {
                publicationFailure("私人模型归档不可用", false, 10 * SECOND, this.clockNow);
                return;
            }
            try {
                byte[] bundle = archive.bytes();
                if (bundle.length < 1 || bundle.length > this.maxBundleBytes) {
                    throw new IOException("Bundle size");
                }

                this.kind = archive.kind();
                this.ownHash = archive.hash();
                this.outgoing = bundle;
                this.uploadIndex = 0;
                this.publicationBytes = bundle.length;
                this.uploadedBytes = 0;
                rememberSource(this.sourceId, new SourceIdentity(this.ownHash, this.kind, this.publicationBytes));
                JsonObject offer = identity("upload_offer");
                offer.addProperty("bytes", bundle.length);
                offer.addProperty("kind", this.kind);
                if (this.catalogueNegotiated) {
                    offer.addProperty("modelId", this.sourceId);
                }

                offer.add("appearance", current.appearance().deepCopy());
                if (offer.toString().getBytes(StandardCharsets.UTF_8).length > this.maxPayload) {
                    throw new IOException("Appearance payload size");
                }

                this.outgoingOffer = offer;
                Boolean sent = sendControl(offer, this.clockNow);
                if (sent != null) {
                    this.lastUploadOfferAttempt = this.clockNow;
                }

                this.status = "等待服务器授权上传 · " + size(this.publicationBytes);
            } catch (Exception invalid) {
                publicationFailure("私人模型不可上传：归档无效或模型、参数超过服务器限制", false, 10 * SECOND, this.clockNow);
            }
        }));
    }

    public void event(List<Double> args, long now) {
        if (!committed() || args == null || args.size() > 16 || now - this.lastEvent < SECOND / 8) {
            return;
        }

        JsonArray values = new JsonArray();
        for (Double value : args) {
            if (value == null || !Double.isFinite(value)) {
                return;
            }
            values.add(value);
        }
        JsonObject event = identity("private_event");
        event.add("args", values);
        if (send(event)) {
            this.lastEvent = now;
        }

    }

    public void receive(byte[] bytes, long now) {
        this.clockNow = now;
        JsonObject packet = null;
        try {
            packet = WireJson.decode(bytes, PROTOCOL);
            String type = WireJson.string(packet, "type", 32);
            if (type.equals("hello_ack")) {
                JsonArray caps = packet.getAsJsonArray("capabilities");
                if (caps == null || caps.size() > 16 || caps.asList().stream()
                        .noneMatch(v -> v.isJsonPrimitive() && v.getAsString().equals("private_models_v1"))) {
                    throw new IOException("Private capability");
                }

                boolean upload = WireJson.bool(packet, "allowedUpload"), view = WireJson.bool(packet, "allowedView");
                int payload = (int) WireJson.integer(packet, "maxPayload", 1024, ActionPayload.MAX_BYTES);
                int bundle = (int) WireJson.integer(packet, "maxBundleBytes", 1, AssetTransfer.MAX_RAW);
                int lease = (int) WireJson.integer(packet, "leaseTicks", 20, 1200);
                boolean catalogue = caps.asList().stream().anyMatch(
                        v -> v.isJsonPrimitive() && v.getAsString().equals(PrivateUploadCatalogSnapshot.CAPABILITY));
                boolean credit = caps.asList().stream()
                        .anyMatch(v -> v.isJsonPrimitive() && v.getAsString().equals(UPLOAD_CREDIT));
                boolean viewState = caps.asList().stream()
                        .anyMatch(v -> v.isJsonPrimitive() && v.getAsString().equals(VIEW_STATE));
                boolean confirmedView = viewState && packet.has("viewEnabled") && WireJson.bool(packet, "viewEnabled");
                UUID catalogToken = catalogue ? uuid(packet, "uploadCatalogToken") : null;
                if (this.acknowledged) {
                    reset();
                }
                this.acknowledged = true;
                this.allowedUpload = upload;
                this.allowedView = view;
                this.maxPayload = payload;
                this.maxBundleBytes = bundle;
                this.leaseTicks = lease;
                this.catalogueNegotiated = catalogue;
                this.uploadCatalogToken = catalogToken;
                this.creditNegotiated = credit;
                this.viewStateNegotiated = viewState;
                this.viewStateDirty = viewState && confirmedView != this.host.viewEnabled();
                if (packet.has("transferTotalTicks")) {
                    this.uploadTotalNanos = WireJson.integer(packet, "transferTotalTicks", 20, 72000) * 50_000_000L;
                }

                if (packet.has("transferIdleTicks")) {
                    this.uploadIdleNanos = WireJson.integer(packet, "transferIdleTicks", 20, 72000) * 50_000_000L;
                }

                if (packet.has("validationTimeoutTicks")) {
                    this.uploadValidationNanos = WireJson.integer(packet, "validationTimeoutTicks", 20, 72000)
                            * 50_000_000L;
                }

                this.status = upload ? "可分享私人模型；需要主动开启分享" : "没有私人模型上传权限";
            } else if (!this.acknowledged) {
                if (type.equals("error") && this.helloFallback < 3
                        && WireJson.string(packet, "code", 128).equals("invalid_private_payload")) {
                    this.helloFallback++;
                    this.lastHello = 0;
                }
                return;
            } else {
                switch (type) {
                case "upload_catalog" -> {
                    if (this.catalogueNegotiated && this.uploadCatalog.accept(packet)) {
                        queueSourceChecks();
                    }
                }
                case "upload_deleted" -> {
                    UUID request = uuid(packet, "requestId");
                    boolean current = this.deleteRequest != null
                            && request.toString().equals(this.deleteRequest.get("requestId").getAsString());
                    DeleteReceipt receipt = current ? deleteReceipt() : this.lateDeletes.get(request);
                    if (receipt == null || !WireJson.string(packet, "modelId", 128).equals(receipt.modelId())
                            || !WireJson.hash(packet, "hash").equals(receipt.hash())) {
                        break;
                    }

                    boolean active = receipt.publicationGeneration() != null
                            && receipt.publicationSerial() == this.publicationSerial && (this.ownGeneration == null
                                    || receipt.publicationGeneration().equals(this.ownGeneration));
                    if (this.uploadCatalog.revision() == receipt.catalogRevision()) {
                        this.uploadCatalog.deleted(receipt.modelId(), receipt.hash());
                    }

                    if (active) {
                        stopPublishing();
                        this.host.savedModelDeleted(receipt.modelId(), receipt.hash());
                    }
                    if (current) {
                        clearDelete();
                    } else {
                        this.lateDeletes.remove(request);
                    }

                    rememberDeleteStatus(receipt.modelId(), "服务器已删除保存的私人模型；本地文件保留");
                }
                case "upload_delete_failed" -> {
                    UUID request = uuid(packet, "requestId");
                    boolean current = this.deleteRequest != null
                            && request.toString().equals(this.deleteRequest.get("requestId").getAsString());
                    DeleteReceipt receipt = current ? deleteReceipt() : this.lateDeletes.get(request);
                    if (receipt == null) {
                        break;
                    }

                    String code = WireJson.string(packet, "code", 128);
                    rememberDeleteStatus(receipt.modelId(), switch (code) {
                    case "private_delete_denied" -> "服务器拒绝删除：权限或缓存不可用";
                    case "private_delete_busy" -> "资源正在上传或存档仍在校验，请稍后再删除";
                    case "private_delete_not_found" -> "服务器保存内容已变化，等待目录同步";
                    default -> "服务器未完成删除，请重试";
                    });
                    if (current) {
                        clearDelete();
                    } else {
                        this.lateDeletes.remove(request);
                    }

                }
                case "upload_accept" -> {
                    if (!matchesOwn(packet) || this.outgoing == null) {
                        break;
                    }

                    UUID id = uuid(packet, "uploadId");
                    int size = (int) WireJson.integer(packet, "chunkBytes", 1, 8192);
                    if (size * 4L / 3 + 512 > this.maxPayload) {
                        throw new IOException("Upload chunk budget");
                    }

                    if (this.uploadId != null) {
                        if (!this.uploadId.equals(id) || this.chunkBytes != size) {
                            throw new IOException("Conflicting upload accept");
                        }
                        break;
                    }
                    this.uploadId = id;
                    this.chunkBytes = size;
                    this.uploadIndex = this.uploadCreditLimit = 0;
                    this.uploadLastProgress = now;
                }
                case "upload_credit" -> {
                    if (!this.creditNegotiated || this.uploadId == null || this.outgoing == null
                            || !this.uploadId.equals(uuid(packet, "uploadId"))) {
                        break;
                    }

                    int next = (int) WireJson.integer(packet, "nextIndex", 0,
                            (this.outgoing.length + this.chunkBytes - 1) / this.chunkBytes);
                    if (next > this.uploadIndex + 2) {
                        throw new IOException("Upload credit window");
                    }

                    if (next > this.uploadCreditLimit) {
                        this.uploadCreditLimit = next;
                        this.uploadLastProgress = now;
                    }
                }
                case "upload_committed" -> {
                    if (matchesOwn(packet)) {
                        this.committed = true;
                        this.outgoing = null;
                        this.outgoingOffer = null;
                        this.uploadId = null;
                        this.status = "私人模型已分享 · " + (this.uploadedBytes == 0 ? "复用服务器缓存 · " : "")
                                + size(this.publicationBytes);
                        // A publication ACK is separate from a durable, owner-scoped directory entry.
                    }
                }
                case "private_offer" -> offer(packet, now);
                case "asset_begin" -> {
                    Remote remote = offer(packet);
                    if (remote == null || remote.bundle != null) {
                        break;
                    }

                    int size = (int) WireJson.integer(packet, "bytes", 1, this.maxBundleBytes),
                            count = (int) WireJson.integer(packet, "chunks", 1, 16384);
                    if (size != remote.bytes || count > size) {
                        throw new IOException("Offer size");
                    }

                    if (this.remotes.values().stream().filter(r -> r.download != null).count() >= 4) {
                        throw new IOException("Private transfers busy");
                    }

                    remote.download = new Download(size, count);
                    remote.lastLease = now;
                    remote.pendingStatus = "";
                    remote.statusSent = true;
                    remote.phase = ReceivingPhase.DOWNLOADING;
                }
                case "asset_chunk" -> {
                    Remote remote = offer(packet);
                    if (remote == null || remote.download == null) {
                        break;
                    }

                    remote.download.put((int) WireJson.integer(packet, "index", 0, 16383),
                            WireJson.string(packet, "data", 12000));
                    remote.lastLease = now;
                }
                case "asset_end" -> {
                    Remote remote = offer(packet);
                    if (remote == null || remote.download == null) {
                        break;
                    }

                    byte[] bundle = remote.download.finish(remote.hash);
                    remote.download = null;
                    remote.bundle = bundle;
                    remote.lastLease = now;
                    decode(remote);
                }
                case "private_ack" -> {
                    Remote remote = matching(packet);
                    if (remote != null && remote.ready && this.host.viewEnabled()) {
                        remote.active = true;
                        remote.phase = ReceivingPhase.ACTIVE;
                        remote.lastLease = now;
                        remote.pendingStatus = "";
                        this.host.state(remote);
                    }
                }
                case "private_remove" -> {
                    UUID owner = uuid(packet, "owner"), generation = uuid(packet, "generation");
                    String hash = WireJson.hash(packet, "hash");
                    if (owner.equals(this.ownOwner) && generation.equals(this.ownGeneration)
                            && hash.equals(this.ownHash)) {
                        String reason = packet.has("reason") ? WireJson.string(packet, "reason", 64) : "";
                        publicationFailure("私人发布已由服务器撤销",
                                !Set.of("private_upload_denied", "permission_revoked").contains(reason),
                                (reason.equals("tps_protection") ? 30 : 10) * SECOND, now);
                    }
                    Remote remote = this.remotes.get(owner);
                    if (remote != null && remote.generation.equals(generation) && remote.hash.equals(hash)) {
                        remove(remote);
                    }

                }
                case "private_state" -> {
                    Remote remote = matching(packet);
                    if (remote == null) {
                        break;
                    }

                    long sequence = WireJson.integer(packet, "sequence", 0, 9_007_199_254_740_991L);
                    if (sequence <= remote.sequence) {
                        break;
                    }

                    JsonObject appearance = appearance(packet), extra = extra(packet);
                    String oldTexture = remote.appearance.get("textureId").getAsString();
                    boolean flying = flying(packet);
                    remote.sequence = sequence;
                    remote.appearance = appearance;
                    remote.extra = extra;
                    remote.flying = flying;
                    if (!oldTexture.equals(appearance.get("textureId").getAsString()) && remote.bundle != null) {
                        remote.active = remote.ready = false;
                        remote.lastLease = now;
                        decode(remote);
                    } else {
                        this.host.state(remote);
                    }

                }
                case "private_event" -> authorEvent(packet);
                case "heartbeat" -> {
                    JsonArray values = packet.getAsJsonArray("bindings");
                    if (values == null || values.size() > 128) {
                        throw new IOException("Private heartbeat");
                    }

                    boolean upload = packet.has("allowedUpload") ? WireJson.bool(packet, "allowedUpload")
                            : this.allowedUpload;
                    boolean view = packet.has("allowedView") ? WireJson.bool(packet, "allowedView") : this.allowedView;
                    Boolean confirmedView = this.viewStateNegotiated && packet.has("viewEnabled")
                            ? WireJson.bool(packet, "viewEnabled")
                            : null;
                    permissions(upload, view);
                    if (confirmedView != null) {
                        this.viewStateDirty = confirmedView != this.host.viewEnabled();
                    }

                    for (JsonElement value : values) {
                        Remote remote = matching(value.getAsJsonObject());
                        if (remote != null && remote.ready && remote.active) {
                            remote.lastLease = now;
                        }
                    }
                }
                case "error" -> {
                    String code = WireJson.string(packet, "code", 128);
                    if (packet.has("generation")
                            && (this.ownGeneration == null || !this.ownGeneration.equals(uuid(packet, "generation")))) {
                        break;
                    }

                    if (packet.has("hash") && !this.ownHash.equals(WireJson.hash(packet, "hash"))) {
                        break;
                    }

                    boolean publicationFailure = code.startsWith("upload") || code.startsWith("private_upload")
                            || Set.of("not_allowed", "server_model_priority", "private_generation_reused",
                                    "private_storage_busy", "private_bundle_invalid", "private_sync_disabled",
                                    "private_state_not_authorized", "private_event_not_authorized").contains(code)
                            || code.equals("private_appearance_size") && !this.committed;
                    String stage = packet.has("stage") ? WireJson.string(packet, "stage", 64) : "";
                    publicationFailure |= Set.of("upload", "validation").contains(stage) || Set
                            .of("tps_protection", "asset_queue_full", "publication_limit", "memory_budget",
                                    "disk_budget", "disk_space", "model_complexity_limit")
                            .contains(code) && this.ownGeneration != null && !this.committed;
                    boolean retryable = packet.has("retryable") ? WireJson.bool(packet, "retryable")
                            : code.contains("busy") || code.contains("cooldown") || code.contains("expired")
                                    || code.contains("unavailable");
                    long wait = packet.has("retryAfter") ? WireJson.integer(packet, "retryAfter", 0, 3600) * SECOND
                            : 10 * SECOND;
                    if (publicationFailure && this.ownGeneration != null) {
                        publicationFailure(errorStatus(code), retryable, wait, now);
                    }

                    else {
                        this.status = errorStatus(code);
                    }

                }
                default -> throw new IOException("Unknown private message");
                }
            }

            this.lastReceived = now;
        } catch (Exception invalid) {
            this.status = "私人同步数据已拒绝";
            if (packet != null) {
                try {
                    String type = WireJson.string(packet, "type", 32);
                    if (type.startsWith("asset_")) {
                        Remote failed = offer(packet);
                        if (failed != null) {
                            receiveFailed(failed, "私人模型下载不完整或校验失败");
                            status(failed.offerId, failed.hash, "rejected");
                            remove(failed);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

        }
    }

    private void offer(JsonObject packet, long now) throws IOException {
        if (!this.allowedView || !this.host.viewEnabled()) {
            return;
        }

        UUID owner = uuid(packet, "owner"), generation = uuid(packet, "generation"), id = uuid(packet, "offerId");
        String hash = WireJson.hash(packet, "hash"), kind = WireJson.string(packet, "kind", 16);
        if (!Set.of("ysm", "bbmodel").contains(kind)) {
            throw new IOException("Private kind");
        }

        String modelId = packet.has("modelId") ? WireJson.string(packet, "modelId", 128) : "";
        if (!modelId.isEmpty() && !LocalAppearanceSettings.isValidModelId(modelId)) {
            throw new IOException("Private model ID");
        }

        int bytes = (int) WireJson.integer(packet, "bytes", 1, this.maxBundleBytes);
        JsonObject appearance = appearance(packet), extra = extra(packet);
        long sequence = WireJson.integer(packet, "sequence", 0, 9_007_199_254_740_991L);
        boolean flying = flying(packet);
        Remote previous = this.remotes.get(owner);
        if (previous != null) {
            if (previous.offerId.equals(id) && previous.generation.equals(generation) && previous.hash.equals(hash)) {
                flushControl(previous, now);
                return;
            }
            remove(previous);
        }
        if (this.remotes.size() >= MAX_REMOTES) {
            status(id, hash, "rejected");
            return;
        }
        this.receiveFailures.remove(owner);
        Remote remote = new Remote(owner, generation, id, hash, kind, modelId, bytes, appearance, extra, sequence,
                flying);
        remote.lastLease = now;
        this.remotes.put(owner, remote);
        long revision = this.epoch;
        this.host.cached(hash).whenComplete((bundle, error) -> this.host.dispatch(() -> {
            if (!current(remote, revision)) {
                return;
            }

            if (!this.host.viewEnabled()) {
                remove(remote);
                return;
            }
            if (error == null && bundle != null && bundle.length == bytes && hash.equals(AssetTransfer.hash(bundle))) {
                remote.bundle = bundle;
                remote.cacheHit = true;
                queueStatus(remote, "cached");
                decode(remote);
            } else {
                remote.phase = ReceivingPhase.WAITING_DOWNLOAD;
                queueStatus(remote, "missing");
            }
        }));
    }

    private void decode(Remote remote) {
        remote.phase = ReceivingPhase.DECODING;
        long revision = this.epoch, decodeRevision = ++remote.decodeRevision;
        this.host.decode(remote.bundle, remote.appearance.get("textureId").getAsString())
                .whenComplete((loaded, error) -> this.host.dispatch(() -> {
                    if (!current(remote, revision) || decodeRevision != remote.decodeRevision) {
                        return;
                    }

                    if (!this.host.viewEnabled()) {
                        remove(remote);
                        return;
                    }
                    if (error != null || loaded == null) {
                        receiveFailed(remote,
                                error == null ? "客户端无法准备模型贴图或几何" : "客户端模型解析失败：" + error.getClass().getSimpleName());
                        status(remote.offerId, remote.hash, "rejected");
                        remove(remote);
                        return;
                    }
                    this.host.prepareAsync(remote, loaded,
                            () -> current(remote, revision) && decodeRevision == remote.decodeRevision
                                    && this.host.viewEnabled(),
                            success -> this.host.dispatch(() -> {
                                if (!current(remote, revision) || decodeRevision != remote.decodeRevision
                                        || !this.host.viewEnabled()) {
                                    return;
                                }
                                if (!success) {
                                    receiveFailed(remote, "客户端无法准备模型贴图或几何");
                                    status(remote.offerId, remote.hash, "rejected");
                                    remove(remote);
                                    return;
                                }
                                remote.ready = true;
                                remote.phase = ReceivingPhase.WAITING_ACK;
                                remote.lastLease = this.clockNow;
                                remote.lastReadyAttempt = -1;
                                this.host.state(remote);
                                flushControl(remote, this.clockNow);
                                this.host.cache(remote.hash, remote.bundle);
                            }));
                }));
    }

    private void permissions(boolean upload, boolean view) {
        boolean uploadChanged = this.allowedUpload != upload;
        if (this.allowedUpload && !upload && this.ownGeneration != null) {
            stopPublishing();
        }

        if (this.allowedView && !view) {
            for (Remote remote : List.copyOf(this.remotes.values())) {
                remove(remote);
            }
            this.receiveFailures.clear();
        }
        this.allowedUpload = upload;
        this.allowedView = view;
        if (uploadChanged && !upload && this.deleteRequest != null) {
            if (this.deleteSent) {
                rememberDeleteStatus(this.deletingId, "上传权限已撤销，等待服务器删除结果");
            }

            else {
                rememberDeleteStatus(this.deletingId, "上传权限已撤销，删除请求未发送");
                clearDelete();
            }
        }
        if (uploadChanged) {
            this.status = upload ? "上传权限已开放；开启分享后上传私人模型" : "上传权限已撤销；私人模型仅自己可见";
        }

    }

    private void rememberDeleteStatus(String id, String status) {
        this.deleteStatuses.put(id, status);
        while (this.deleteStatuses.size() > 64) {
            this.deleteStatuses.remove(this.deleteStatuses.keySet().iterator().next());
        }

    }

    private void queueStatus(Remote remote, String value) {
        remote.pendingStatus = value;
        remote.statusSent = false;
        remote.lastStatusAttempt = -1;
        flushControl(remote, this.clockNow);
    }

    /** A prepared model remains invisible until the exact acknowledgement; congestion only retries control packets. */
    private void flushControl(Remote remote, long now) {
        if (!this.host.viewEnabled()) {
            return;
        }

        if (!remote.pendingStatus.isEmpty()) {
            if (remote.lastStatusAttempt < 0 || now - remote.lastStatusAttempt >= SECOND) {
                Boolean sent = sendControl(feedback(remote.offerId, remote.hash, remote.pendingStatus), now);
                if (sent != null) {
                    remote.lastStatusAttempt = now;
                    remote.statusSent = sent || remote.statusSent;
                }
            }
            if (!remote.statusSent) {
                return;
            }

        }
        if (!remote.ready || remote.active || remote.lastReadyAttempt >= 0 && now - remote.lastReadyAttempt < SECOND) {
            return;
        }

        JsonObject ready = binding(remote.owner, remote.generation, remote.hash);
        ready.addProperty("protocol", PROTOCOL);
        ready.addProperty("type", "private_ready");
        if (sendControl(ready, now) != null) {
            remote.lastReadyAttempt = now;
        }

    }

    private static String size(int bytes) {
        return bytes >= 1024 * 1024 ? String.format(Locale.ROOT, "%.2f MiB", bytes / (1024d * 1024))
                : Math.max(1, (bytes + 1023) / 1024) + " KiB";
    }

    private static String errorStatus(String code) {
        return switch (code) {
        case "private_upload_denied", "not_allowed" -> "没有私人模型上传权限";
        case "server_model_priority" -> "服务器伪装期间，私人模型仅自己可见";
        case "private_upload_busy", "private_storage_busy", "private_transfer_busy" -> "服务器模型传输繁忙，稍后重试";
        case "asset_queue_full", "server_busy", "publication_limit" -> "服务器资源任务已满，稍后重试";
        case "tps_protection" -> "服务器正在保护运行性能，模型传输暂缓";
        case "upload_timeout", "validation_timeout", "task_timeout" -> "上传或模型校验超时";
        case "model_complexity_limit" -> "模型复杂度超过服务器允许的范围";
        case "memory_budget", "memory_limit", "disk_budget", "disk_space", "disk_space_low", "disk_unavailable" ->
            "服务器资源空间不足，暂时无法保存模型";
        case "permission_revoked" -> "上传权限已撤销，私人模型仅自己可见";
        case "private_upload_cooldown" -> "上传过于频繁，等待后重试";
        case "private_upload_integrity", "private_bundle_invalid", "private_upload_invalid" -> "服务器拒绝模型：文件不完整或格式无效";
        case "private_upload_expired" -> "上传超时，稍后重新上传";
        case "private_upload_size", "private_bundle_size" -> "模型超过服务器允许的上传大小";
        case "private_sync_disabled" -> "服务器已关闭私人模型分享";
        case "private_view_denied", "private_render_not_authorized" -> "当前没有观看该私人模型的授权";
        case "private_appearance_size" -> "模型配置超过服务器允许的大小";
        case "private_generation_reused" -> "服务器已撤销这次分享，稍后重新上传";
        default -> "私人模型同步被服务器拒绝（" + code + "）";
        };
    }

    private void authorEvent(JsonObject packet) throws IOException {
        UUID owner = uuid(packet, "owner"), generation = uuid(packet, "generation");
        String hash = WireJson.hash(packet, "hash");
        long sequence = WireJson.integer(packet, "sequence", 0, 9_007_199_254_740_991L);
        JsonArray args = packet.getAsJsonArray("args");
        if (args == null || args.size() > 16) {
            throw new IOException("Private event args");
        }

        List<Double> values = new ArrayList<>();
        for (JsonElement value : args) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(value.getAsDouble())) {
                throw new IOException("Private event value");
            }
            values.add(value.getAsDouble());
        }
        Local local = this.host.local();
        if (local != null && local.owner().equals(owner) && this.committed && generation.equals(this.ownGeneration)
                && hash.equals(this.ownHash)) {
            // Server-authoritative sequence is shared with state; keep a separate event cursor.
            if (sequence <= this.ownEventSequence) {
                return;
            }
            this.ownEventSequence = sequence;
            this.host.event(owner, generation, List.copyOf(values));
            return;
        }
        Remote remote = matching(packet);
        if (remote == null || !remote.active || sequence <= remote.eventSequence) {
            return;
        }

        remote.eventSequence = sequence;
        this.host.event(owner, generation, List.copyOf(values));
    }

    private long ownEventSequence = -1;

    private boolean current(Remote remote, long revision) {
        return revision == this.epoch && this.remotes.get(remote.owner) == remote;
    }

    private Remote matching(JsonObject packet) {
        Remote remote = this.remotes.get(uuid(packet, "owner"));
        return remote != null && remote.generation.equals(uuid(packet, "generation"))
                && remote.hash.equals(WireJson.hash(packet, "hash")) ? remote : null;
    }

    private Remote offer(JsonObject packet) {
        UUID id = uuid(packet, "offerId");
        String hash = WireJson.hash(packet, "hash");
        return this.remotes.values().stream().filter(r -> r.offerId.equals(id) && r.hash.equals(hash)).findFirst()
                .orElse(null);
    }

    private void remove(Remote remote) {
        if (this.remotes.remove(remote.owner, remote)) {
            remote.decodeRevision++;
            remote.download = null;
            remote.bundle = null;
            this.host.remove(remote.owner, remote.generation);
        }
    }

    private boolean currentLocal() {
        Local local = this.host.local();
        return local != null && local.owner().equals(this.ownOwner) && local.modelId().equals(this.sourceId);
    }

    private boolean matchesOwn(JsonObject packet) {
        return this.ownGeneration != null && this.ownGeneration.equals(uuid(packet, "generation"))
                && this.ownHash.equals(WireJson.hash(packet, "hash")) && currentLocal();
    }

    private static JsonObject feedback(UUID offer, String hash, String state) {
        JsonObject value = envelope("private_status");
        value.addProperty("offerId", offer.toString());
        value.addProperty("hash", hash);
        value.addProperty("status", state);
        return value;
    }

    private boolean status(UUID offer, String hash, String state) {
        return Boolean.TRUE.equals(sendControl(feedback(offer, hash, state), this.clockNow));
    }

    private JsonObject identity(String type) {
        JsonObject packet = envelope(type);
        packet.addProperty("generation", this.ownGeneration.toString());
        packet.addProperty("hash", this.ownHash);
        return packet;
    }

    public static JsonObject envelope(String type) {
        JsonObject packet = new JsonObject();
        packet.addProperty("protocol", PROTOCOL);
        packet.addProperty("type", type);
        return packet;
    }

    private static JsonObject binding(UUID owner, UUID generation, String hash) {
        JsonObject value = new JsonObject();
        value.addProperty("owner", owner.toString());
        value.addProperty("generation", generation.toString());
        value.addProperty("hash", hash);
        return value;
    }

    private static UUID uuid(JsonObject packet, String key) {
        return UUID.fromString(WireJson.string(packet, key, 36));
    }

    /** Optional S2C extension: legacy servers omit it, model caches never supply entity state. */
    private static boolean flying(JsonObject packet) {
        if (!packet.has("state")) {
            return false;
        }

        JsonElement state = packet.get("state");
        if (!state.isJsonObject() || !Set.of("flying").containsAll(state.getAsJsonObject().keySet())) {
            throw new IllegalArgumentException("Private server state");
        }

        JsonObject values = state.getAsJsonObject();
        return values.has("flying") && WireJson.bool(values, "flying");
    }

    private static JsonObject appearance(JsonObject packet) {
        JsonObject value = packet.getAsJsonObject("appearance");
        if (value == null) {
            throw new IllegalArgumentException("Private appearance");
        }

        WireJson.number(value, "scale", .05, 8);
        for (String key : List.of("offsetX", "offsetY", "offsetZ")) {
            WireJson.number(value, key, -32, 32);
        }

        WireJson.string(value, "textureId", 128);
        JsonObject vars = value.getAsJsonObject("variables"), radios = value.getAsJsonObject("radioSelections");
        if (vars == null || radios == null || vars.size() > 128 || radios.size() > 128) {
            throw new IllegalArgumentException("Private variables");
        }

        int roaming = 0;
        for (String key : vars.keySet()) {
            if (!key.equals(ClientOptions.normalizeModelVariable(key))) {
                throw new IllegalArgumentException("Private variable name");
            }

            if (key.startsWith("variable.roaming.")
                    && (!ClientOptions.isRoamingVariable(key) || ++roaming > ClientOptions.MAX_ROAMING_VARIABLES)) {
                throw new IllegalArgumentException("Private roaming variable");
            }

            WireJson.number(vars, key, -1_000_000, 1_000_000);
        }
        for (String key : radios.keySet()) {
            if (key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Private radio");
            }
            WireJson.integer(radios, key, 0, 255);
        }
        return value.deepCopy();
    }

    private static JsonObject extra(JsonObject packet) {
        if (!packet.has("extra")) {
            JsonObject value = new JsonObject();
            value.addProperty("id", "");
            value.addProperty("loop", "ONCE");
            value.addProperty("locked", false);
            value.addProperty("sequence", 0);
            return value;
        }
        JsonObject value = packet.getAsJsonObject("extra");
        String id = WireJson.string(value, "id", 128), loop = WireJson.string(value, "loop", 8);
        if (!id.isEmpty() && !id.matches("[a-zA-Z0-9_.:/-]{1,128}") || !Set.of("ONCE", "LOOP", "HOLD").contains(loop)) {
            throw new IllegalArgumentException("Private extra");
        }

        WireJson.bool(value, "locked");
        WireJson.integer(value, "sequence", 0, 9_007_199_254_740_991L);
        return value.deepCopy();
    }

    private static final class Download {
        final int expected;
        final byte[][] chunks;
        int received;

        Download(int bytes, int count) {
            this.expected = bytes;
            this.chunks = new byte[count][];
        }

        void put(int index, String encoded) throws IOException {
            if (index < 0 || index >= this.chunks.length || encoded.length() > 12000) {
                throw new IOException("Private chunk");
            }

            byte[] value = Base64.getDecoder().decode(encoded);
            if (value.length < 1 || value.length > 9000) {
                throw new IOException("Private chunk size");
            }

            if (this.chunks[index] != null) {
                if (!Arrays.equals(this.chunks[index], value)) {
                    throw new IOException("Conflicting private chunk");
                }
                return;
            }
            if (this.received + value.length > this.expected) {
                throw new IOException("Private overflow");
            }
            this.chunks[index] = value;
            this.received += value.length;
        }

        byte[] finish(String hash) throws IOException {
            if (this.received != this.expected || Arrays.stream(this.chunks).anyMatch(Objects::isNull)) {
                throw new IOException("Incomplete private bundle");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(this.expected);
            for (byte[] part : this.chunks) {
                out.write(part);
            }
            byte[] result = out.toByteArray();
            if (!hash.equals(AssetTransfer.hash(result))) {
                throw new IOException("Private bundle hash");
            }
            return result;
        }
    }
}
