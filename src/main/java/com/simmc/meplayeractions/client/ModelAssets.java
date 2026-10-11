package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.MEPlayerActionsPlugin;
import com.simmc.meplayeractions.config.ModelComplexityLimits;
import com.simmc.meplayeractions.protection.ResourceProtection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.*;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.RejectedExecutionException;

/** Server-owned raw Blockbench assets. Client input never becomes a filesystem path. */
final class ModelAssets {
    static final int MAX_RAW_BYTES = 8 * 1024 * 1024;
    static final int MAX_COMPRESSED_BYTES = 4 * 1024 * 1024;
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private final Path ownModels;
    private final Consumer<Runnable> prepare;
    private final Consumer<String> warning;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private final IdentityHashMap<Content, Integer> contentReferences = new IdentityHashMap<>();
    private final IdentityHashMap<Content, Integer> transferPins = new IdentityHashMap<>();
    private ResourceProtection protection;
    private ModelComplexityLimits complexity = ModelComplexityLimits.defaults();
    private int maxModels = 512, chunkBytes = 9000;
    private long maxBytes = 128L * 1024 * 1024, cachedBytes, hits, misses, peakBytes;
    private long lastWarning = Long.MIN_VALUE, suppressedWarnings;

    ModelAssets(JavaPlugin plugin) {
        this(plugin.getDataFolder().toPath().resolve("models"), task -> {
            throw new RejectedExecutionException("No bounded resource executor");
        }, plugin.getLogger()::warning);
        if (plugin instanceof MEPlayerActionsPlugin actions) {
            this.protection = actions.resources();
            this.complexity = this.protection.complexityLimits();
            this.maxModels = this.protection.settings().models().maxAssetCacheModels();
            this.maxBytes = this.protection.settings().models().maxAssetCacheBytes();
            this.chunkBytes = Math.min(9000,
                    (Math.max(1024, Math.min(32766, plugin.getConfig().getInt("client-sync.max-payload-bytes", 16000)))
                            - 512) * 3 / 4);
            this.protection.gauge("models.assetCacheBytes", () -> metrics().bytes());
            this.protection.gauge("models.assetCacheModels", () -> metrics().models());
            this.protection.gauge("models.assetCacheHits", () -> metrics().hits());
            this.protection.gauge("models.assetCacheMisses", () -> metrics().misses());
            this.protection.gauge("models.assetCachePeakBytes", () -> metrics().peakBytes());
            this.protection.gauge("models.assetCachePinnedContents", () -> transferPinsCount());
        }
    }

    /** Keeps the actual filesystem/packing path testable without constructing a Bukkit plugin. */
    ModelAssets(Path ownModels, Consumer<Runnable> prepare, Consumer<String> warning) {
        this.ownModels = Objects.requireNonNull(ownModels);
        this.prepare = Objects.requireNonNull(prepare);
        this.warning = Objects.requireNonNull(warning);
    }

    /** Legacy fixture signature. Neither JAR resources nor ME blueprints authorize delivery. */
    ModelAssets(Path ownModels, Path ignoredEngineBlueprints, Resources ignoredResources, Consumer<Runnable> prepare,
            Consumer<String> warning) {
        this(ownModels, prepare, warning);
    }

    ModelAssets(Path ownModels, Path engineBlueprints, Resources resources, Consumer<Runnable> prepare,
            Consumer<String> warning, ModelComplexityLimits complexity, int maxModels, long maxBytes) {
        this(ownModels, engineBlueprints, resources, prepare, warning);
        this.complexity = Objects.requireNonNull(complexity);
        if (maxModels < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("Invalid asset cache limit");
        }

        this.maxModels = maxModels;
        this.maxBytes = maxBytes;
    }

    Optional<Asset> get(String modelId) {
        if (!validId(modelId)) {
            return Optional.empty();
        }

        Cached pending;
        synchronized (this) {
            Cached known = this.cache.get(modelId);
            if (known != null && (known.retryAt == 0 || System.nanoTime() < known.retryAt)) {
                this.hits++;
                return Optional.ofNullable(known.asset);
            }
            if (known != null) {
                remove(modelId);
            }

            this.misses++;
            if (this.cache.size() >= this.maxModels && !evict(null)) {
                return Optional.empty();
            }

            pending = new Cached(new Status("pending", "正在检查 MEPlayerActions models/ 中明确提供的客户端资源", "own"), null);
            this.cache.put(modelId, pending);
        }
        if (this.protection != null) {
            // Reserve input, JSON objects, packing buffers and the result until the worker really exits.
            this.protection.submit(null, 48L * 1024 * 1024, "asset_prepare", () -> load(modelId),
                    result -> publish(modelId, pending, result), error -> {
                        Cached failed = unavailable(error.code(), error.stage(), "资产准备被限流或取消，请稍后重试");
                        if (error.retryable()) {
                            failed.retryAt = System.nanoTime() + error.retryAfterTicks() * 50_000_000L;
                        }
                        publish(modelId, pending, failed);
                    });
            return Optional.empty();
        }
        try {
            this.prepare.accept(() -> publish(modelId, pending, load(modelId)));
        } catch (RuntimeException stopped) {
            publish(modelId, pending, unavailable("invalid", "lookup", "无法安排异步资产准备：" + detail(stopped)));
        }
        // Pending preparation advertises no asset and leaves ME rendering active.
        return Optional.empty();
    }

    /** Diagnostic reads never request preparation work or touch disk. */
    synchronized Status status(String modelId) {
        if (!validId(modelId)) {
            return new Status("invalid", "模型 ID 格式无效", "none");
        }

        Cached current = this.cache.get(modelId);
        return current == null && this.cache.size() >= this.maxModels
                ? new Status("asset_queue_full", "客户端资产缓存已达到上限", "lookup")
                : (current == null ? new Status("pending", "尚未请求 MEPlayerActions models/ 客户端资产准备", "own")
                        : current.status);
    }

    /** An old worker's identity cannot replace a new preparation after reload. */
    synchronized void invalidate() {
        for (String id : List.copyOf(this.cache.keySet())) {
            remove(id);
        }
    }

    /** A complete directory snapshot can revoke published assets without discarding other diagnostics. */
    synchronized void revokeUnpublished(Predicate<String> unpublished) {
        Objects.requireNonNull(unpublished);
        List<String> revoked = this.cache.entrySet().stream()
                .filter(entry -> entry.getValue().status.state.equals("pending")
                        || entry.getValue().status.state.equals("ready") && entry.getValue().asset != null)
                .map(Map.Entry::getKey).filter(unpublished).toList();
        for (String id : revoked) {
            remove(id);
        }

    }

    record Metrics(int models, int uniqueHashes, long bytes, long hits, long misses, long peakBytes) {
    }

    synchronized Metrics metrics() {
        return new Metrics(this.cache.size(), this.contentReferences.size(), this.cachedBytes, this.hits, this.misses,
                this.peakBytes);
    }

    private synchronized int transferPinsCount() {
        return this.transferPins.size();
    }

    /** Cache eviction cannot stop accounting for a buffer still referenced by a transfer. */
    synchronized boolean retain(Asset asset) {
        Content content = Objects.requireNonNull(asset).content;
        if (!this.contentReferences.containsKey(content)) {
            long bytes = content.retainedBytes();
            while (bytes > this.maxBytes - this.cachedBytes && evict(null)) {
            }
            if (bytes > this.maxBytes - this.cachedBytes) {
                return false;
            }

            if (this.protection != null) {
                boolean accepted = this.protection.reserveCache("server_asset", content, bytes);
                while (!accepted && evict(null)) {
                    accepted = this.protection.reserveCache("server_asset", content, bytes);
                }

                if (!accepted) {
                    return false;
                }

            }
            this.contentReferences.put(content, 0);
            this.cachedBytes += bytes;
            this.peakBytes = Math.max(this.peakBytes, this.cachedBytes);
        }
        this.transferPins.merge(content, 1, Integer::sum);
        return true;
    }

    synchronized void release(Asset asset) {
        Content content = Objects.requireNonNull(asset).content;
        Integer pins = this.transferPins.get(content);
        if (pins == null) {
            return;
        }

        if (pins == 1) {
            this.transferPins.remove(content);
        } else {
            this.transferPins.put(content, pins - 1);
        }

        releaseUnused(content);
    }

    private void releaseUnused(Content content) {
        if (this.contentReferences.getOrDefault(content, 0) == 0 && !this.transferPins.containsKey(content)) {
            if (this.contentReferences.remove(content) != null) {
                this.cachedBytes -= content.retainedBytes();
                if (this.protection != null) {
                    this.protection.releaseCache("server_asset", content);
                }

            }
        }
    }

    private void publish(String id, Cached pending, Cached result) {
        long summarized = 0;
        boolean warn;
        synchronized (this) {
            if (this.cache.get(id) != pending) {
                return;
            }

            if (result.asset != null) {
                for (Content known : this.contentReferences.keySet()) {
                    if (known.hash.equals(result.asset.hash())) {
                        result = ready(result.status.source, new Asset(id, known));
                        break;
                    }
                }

                Content content = result.asset.content;
                long extra = this.contentReferences.containsKey(content) ? 0 : content.retainedBytes();
                while (extra > this.maxBytes - this.cachedBytes && evict(id)) {
                }
                if (extra > this.maxBytes - this.cachedBytes) {
                    boolean impossible = content.retainedBytes() > this.maxBytes;
                    result = unavailable(impossible ? "asset_too_large" : "memory_limit", result.status.source,
                            "客户端资产超过缓存内存预算");
                    if (!impossible) {
                        result.retryAt = System.nanoTime() + 5_000_000_000L;
                    }

                } else {
                    boolean accepted = true;
                    if (this.protection != null && extra > 0) {
                        accepted = this.protection.reserveCache("server_asset", content, extra);
                        while (!accepted && evict(id)) {
                            accepted = this.protection.reserveCache("server_asset", content, extra);
                        }

                    }
                    if (!accepted) {
                        result = unavailable("memory_limit", result.status.source, "服务器共享资产与动画模板缓存预算不足");
                        result.retryAt = System.nanoTime() + 5_000_000_000L;
                    } else {
                        this.cachedBytes += extra;
                        this.contentReferences.merge(content, 1, Integer::sum);
                        this.peakBytes = Math.max(this.peakBytes, this.cachedBytes);
                    }
                }
            }
            this.cache.put(id, result);
            warn = !result.status.state.equals("ready");
            if (warn && this.protection != null) {
                boolean transientFailure = !Set.of("missing", "invalid", "ambiguous", "model_complexity")
                        .contains(result.status.state);
                long now = System.nanoTime();
                if (transientFailure
                        || this.lastWarning != Long.MIN_VALUE && now - this.lastWarning < 30_000_000_000L) {
                    this.suppressedWarnings++;
                    warn = false;
                } else {
                    this.lastWarning = now;
                    summarized = this.suppressedWarnings;
                    this.suppressedWarnings = 0;
                }
            }
        }
        if (this.protection != null && !result.status.state.equals("ready")) {
            this.protection.reject(result.status.state);
        }

        if (warn) {
            this.warning.accept("客户端模型资产 " + result.status.state + "：" + id + "；来源 " + result.status.source + "；"
                    + result.status.reason + "；保持 ModelEngine 渲染"
                    + (summarized > 0 ? "；此前已合并 " + summarized + " 条资源诊断" : ""));
        }

    }

    private boolean evict(String preserve) {
        String candidate = null;
        for (Map.Entry<String, ModelAssets.Cached> entry : this.cache.entrySet()) {
            if (!entry.getKey().equals(preserve) && !entry.getValue().status.state.equals("pending")) {
                candidate = entry.getKey();
                break;
            }
        }

        if (candidate == null) {
            return false;
        }
        remove(candidate);
        return true;
    }

    private void remove(String id) {
        Cached removed = this.cache.remove(id);
        if (removed != null && removed.asset != null) {
            Content content = removed.asset.content;
            Integer count = this.contentReferences.get(content);
            if (count != null) {
                this.contentReferences.put(content, count - 1);
            }

            releaseUnused(content);
        }
    }

    private Cached load(String id) {
        try {
            interrupted();
            ExplicitModelFiles.Selection selected = ExplicitModelFiles.select(this.ownModels, id);
            return selected.found() ? ready("own", read(id, selected.path()))
                    : unavailable(selected.state(), "own", selected.reason());
        } catch (IOException | RuntimeException exception) {
            String code = exception.getMessage() != null && exception.getMessage().startsWith("model_complexity:")
                    ? "model_complexity"
                    : "invalid";
            return unavailable(code, "own", "MEPlayerActions models/ 中的模型资产无效或无法读取：" + detail(exception));
        }
    }

    private static boolean validId(String modelId) {
        return modelId != null && MODEL_ID.matcher(modelId).matches();
    }

    private static Cached ready(String source, Asset asset) {
        return new Cached(new Status("ready", "客户端资产已准备完成，可提供 hash 与有界模型数据", source), asset);
    }

    private static Cached unavailable(String state, String source, String reason) {
        return new Cached(new Status(state, reason, source), null);
    }

    private static String detail(Throwable failure) {
        String message = failure.getMessage();
        // These messages are written by our bounded packer. Underlying filesystem/resource exceptions
        // can include private absolute paths; viewers receive only their exception type.
        if (message != null && (message.matches("model_complexity:[a-z_]+") || Set
                .of("Invalid bbmodel size", "bbmodel exceeds 8 MiB", "Compressed bbmodel exceeds 4 MiB",
                        "Incomplete bbmodel geometry/texture/animation asset", "Malformed bbmodel JSON")
                .contains(message))) {
            return message;
        }

        String type = failure.getClass().getSimpleName();
        return type.isBlank() ? "Asset preparation failure" : type;
    }

    @FunctionalInterface
    interface Resources {
        InputStream open(String name) throws IOException;
    }

    record Status(String state, String reason, String source) {
    }

    // Deliberately uses identity equality: invalidate followed by get creates a distinct worker token.
    private static final class Cached {
        final Status status;
        final Asset asset;
        long retryAt;

        Cached(Status status, Asset asset) {
            this.status = status;
            this.asset = asset;
        }
    }

    private Asset read(String id, Path path) throws IOException {
        ExplicitModelFiles.validateSelected(this.ownModels, path);
        if (Files.size(path) > MAX_RAW_BYTES) {
            throw new IOException("bbmodel exceeds 8 MiB");
        }

        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return prepared(id, readBounded(input));
        }
    }

    private Asset prepared(String id, byte[] raw) throws IOException {
        String hash = PrivateModelBundle.hash(raw);
        synchronized (this) {
            for (Content known : this.contentReferences.keySet()) {
                if (known.hash.equals(hash)) {
                    return new Asset(id, known);
                }

            }
        }
        return pack(id, raw, this.complexity, this.chunkBytes);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            interrupted();
            if (count > MAX_RAW_BYTES - output.size()) {
                throw new IOException("Invalid bbmodel size");
            }
            output.write(buffer, 0, count);
        }
        if (output.size() == 0) {
            throw new IOException("Invalid bbmodel size");
        }
        return output.toByteArray();
    }

    static Asset pack(String id, byte[] raw) throws IOException {
        return pack(id, raw, ModelComplexityLimits.defaults(), 9000);
    }

    private static Asset pack(String id, byte[] raw, ModelComplexityLimits limits, int chunkBytes) throws IOException {
        if (raw.length == 0 || raw.length > MAX_RAW_BYTES) {
            throw new IOException("Invalid bbmodel size");
        }

        try {
            JsonObject object = PrivateModelBundle.parseJson(raw, MAX_RAW_BYTES).getAsJsonObject();
            if (!object.has("elements") || !object.has("outliner") || !object.has("textures")
                    || !object.has("animations")) {
                throw new IOException("Incomplete bbmodel geometry/texture/animation asset");
            }

            new ModelComplexity(limits).document(object);
            long pixels = 0;
            if (object.get("textures").isJsonArray()) {
                for (JsonElement value : object.getAsJsonArray("textures")) {
                    interrupted();
                    if (!value.isJsonObject()) {
                        continue;
                    }
                    JsonElement source = value.getAsJsonObject().get("source");
                    if (source != null && source.isJsonPrimitive() && source.getAsJsonPrimitive().isString()) {
                        String text = source.getAsString(), prefix = "data:image/png;base64,";
                        if (text.startsWith(prefix)) {
                            pixels += PrivateTextureHeaders.pixels("texture.png",
                                    Base64.getDecoder().decode(text.substring(prefix.length())));
                            if (pixels > limits.maxTexturePixels()) {
                                throw new IOException("model_complexity:texture_pixels");
                            }

                        }
                    }
                }
            }

        } catch (RuntimeException malformed) {
            throw new IOException("Malformed bbmodel JSON", malformed);
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            for (int at = 0; at < raw.length; at += 8192) {
                interrupted();
                gzip.write(raw, at, Math.min(8192, raw.length - at));
            }
        }
        byte[] compressed = output.toByteArray();
        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed bbmodel exceeds 4 MiB");
        }

        return new Asset(id, new Content(hash, raw.length, compressed, chunkBytes));
    }

    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("task_cancelled");
        }
    }

    private static final class Content {
        final String hash;
        final int rawBytes, chunkBytes;
        final byte[] compressed;
        final String[] encoded;

        Content(String hash, int rawBytes, byte[] compressed, int chunkBytes) throws IOException {
            this.hash = hash;
            this.rawBytes = rawBytes;
            this.compressed = compressed;
            this.chunkBytes = chunkBytes;
            this.encoded = new String[(compressed.length + chunkBytes - 1) / chunkBytes];
            for (int index = 0; index < this.encoded.length; index++) {
                interrupted();
                int start = index * chunkBytes;
                this.encoded[index] = Base64.getEncoder().encodeToString(
                        Arrays.copyOfRange(compressed, start, Math.min(compressed.length, start + chunkBytes)));
            }
        }

        long retainedBytes() {
            long bytes = this.compressed.length + 64L;
            for (String chunk : this.encoded) {
                bytes += chunk.length() * 2L + 48;
            }
            return bytes;
        }
    }

    static final class Asset {
        final String modelId;
        final Content content;

        Asset(String modelId, Content content) {
            this.modelId = modelId;
            this.content = content;
        }

        String modelId() {
            return this.modelId;
        }

        String hash() {
            return this.content.hash;
        }

        int rawBytes() {
            return this.content.rawBytes;
        }

        byte[] compressed() {
            return this.content.compressed.clone();
        }

        int compressedBytes() {
            return this.content.compressed.length;
        }

        int chunks(int chunkBytes) {
            if (chunkBytes <= 0) {
                throw new IllegalArgumentException("Invalid chunk size");
            }
            return (compressedBytes() + chunkBytes - 1) / chunkBytes;
        }

        byte[] chunk(int index, int chunkBytes) {
            int offset = Math.multiplyExact(index, chunkBytes);
            if (chunkBytes <= 0 || offset < 0 || offset >= compressedBytes()) {
                throw new IllegalArgumentException("Invalid asset chunk index");
            }

            return Arrays.copyOfRange(this.content.compressed, offset,
                    Math.min(compressedBytes(), offset + chunkBytes));
        }

        String base64Chunk(int index, int chunkBytes) {
            if (chunkBytes == this.content.chunkBytes) {
                if (index < 0 || index >= this.content.encoded.length) {
                    throw new IllegalArgumentException("Invalid asset chunk index");
                }
                return this.content.encoded[index];
            }
            return Base64.getEncoder().encodeToString(chunk(index, chunkBytes));
        }
    }
}
