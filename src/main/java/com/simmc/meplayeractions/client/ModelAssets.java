package com.simmc.meplayeractions.client;

import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/** Server-owned raw Blockbench assets. Client input never becomes a filesystem path. */
final class ModelAssets {
    static final int MAX_RAW_BYTES = 8 * 1024 * 1024;
    static final int MAX_COMPRESSED_BYTES = 4 * 1024 * 1024;
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private final Path ownModels, engineBlueprints;
    private final Resources resources;
    private final Consumer<Runnable> prepare;
    private final Consumer<String> warning;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    ModelAssets(JavaPlugin plugin) {
        this(plugin.getDataFolder().toPath().resolve("models"), engineBlueprints(plugin), plugin::getResource,
                task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task), plugin.getLogger()::warning);
    }

    /** Keeps the actual filesystem/packing path testable without constructing a Bukkit plugin. */
    ModelAssets(Path ownModels, Path engineBlueprints, Resources resources, Consumer<Runnable> prepare,
                Consumer<String> warning) {
        this.ownModels = java.util.Objects.requireNonNull(ownModels);
        this.engineBlueprints = engineBlueprints;
        this.resources = java.util.Objects.requireNonNull(resources);
        this.prepare = java.util.Objects.requireNonNull(prepare);
        this.warning = java.util.Objects.requireNonNull(warning);
    }

    private static Path engineBlueprints(JavaPlugin plugin) {
        Plugin engine = plugin.getServer().getPluginManager().getPlugin("ModelEngine");
        return engine == null ? null : engine.getDataFolder().toPath().resolve("blueprints");
    }

    Optional<Asset> get(String modelId) {
        if (!validId(modelId)) return Optional.empty();
        Cached known = cache.get(modelId);
        if (known != null) return known.asset;
        Cached pending = new Cached(new Status("pending", "正在准备客户端资产（OWN → JAR → ME）", "lookup"), Optional.empty());
        Cached existing = cache.putIfAbsent(modelId, pending);
        if (existing != null) return existing.asset;
        try {
            prepare.accept(() -> publish(modelId, pending, load(modelId)));
        } catch (RuntimeException stopped) {
            publish(modelId, pending, unavailable("invalid", "lookup", "无法安排异步资产准备：" + detail(stopped)));
        }
        // Pending preparation advertises no asset and leaves ME rendering active.
        return Optional.empty();
    }

    /** Diagnostic reads never request work, touch disk, or change the cache. */
    Status status(String modelId) {
        if (!validId(modelId)) return new Status("invalid", "模型 ID 格式无效", "none");
        Cached current = cache.get(modelId);
        return current == null ? new Status("pending", "尚未请求客户端资产准备（OWN → JAR → ME）", "lookup") : current.status;
    }

    /** An old worker's identity cannot replace a new preparation after reload. */
    void invalidate() { cache.clear(); }

    private void publish(String id, Cached pending, Cached result) {
        if (cache.replace(id, pending, result) && !result.status.state.equals("ready"))
            warning.accept("客户端模型资产 " + result.status.state + "：" + id + "；来源 " + result.status.source
                    + "；" + result.status.reason + "；保持 ModelEngine 渲染");
    }

    private Cached load(String id) {
        String source = "own";
        try {
            Path own = ownModels.resolve(id + ".bbmodel");
            if (Files.isRegularFile(own)) return ready("own", read(id, own));
            source = "jar";
            try (InputStream resource = resources.open("models/" + id + ".bbmodel")) {
                if (resource != null) return ready("jar", pack(id, readBounded(resource)));
            }
            source = "modelengine";
            if (engineBlueprints != null) {
                Path blueprints = engineBlueprints;
                if (Files.isDirectory(blueprints)) {
                    try (var paths = Files.walk(blueprints, 16)) {
                        // Stable paths make duplicate identifiers diagnosable and reproducible.
                        var candidates = paths.filter(p -> Files.isRegularFile(p)
                                && p.getFileName().toString().endsWith(".bbmodel")).sorted().toList();
                        for (Path path : candidates) {
                            if (path.getFileName().toString().equals(id + ".bbmodel")) return ready("modelengine", read(id, path));
                        }
                        for (Path path : candidates) {
                            if (Files.size(path) > MAX_RAW_BYTES) continue;
                            try {
                                byte[] raw = Files.readAllBytes(path);
                                var json = JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                                if (json.isJsonObject() && json.getAsJsonObject().has("model_identifier")
                                        && id.equals(json.getAsJsonObject().get("model_identifier").getAsString())) {
                                    return ready("modelengine", pack(id, raw));
                                }
                            } catch (RuntimeException malformedOtherModel) { /* Other malformed blueprints cannot disable this asset. */ }
                        }
                    }
                }
            }
            return unavailable("missing", "none", "OWN 模型目录、JAR 内置资源及 ME blueprints 均未找到此模型");
        } catch (IOException | RuntimeException exception) {
            return unavailable("invalid", source, "选定来源的模型资产无效或无法读取：" + detail(exception));
        }
    }

    private static boolean validId(String modelId) { return modelId != null && MODEL_ID.matcher(modelId).matches(); }
    private static Cached ready(String source, Asset asset) {
        return new Cached(new Status("ready", "客户端资产已准备完成，可提供 hash 与有界模型数据", source), Optional.of(asset));
    }
    private static Cached unavailable(String state, String source, String reason) {
        return new Cached(new Status(state, reason, source), Optional.empty());
    }
    private static String detail(Throwable failure) {
        String message = failure.getMessage();
        // These messages are written by our bounded packer. Underlying filesystem/resource exceptions
        // can include private absolute paths; viewers receive only their exception type.
        if (message != null && java.util.Set.of("Invalid bbmodel size", "bbmodel exceeds 8 MiB",
                "Compressed bbmodel exceeds 4 MiB", "Incomplete bbmodel geometry/texture/animation asset",
                "Malformed bbmodel JSON").contains(message)) return message;
        String type = failure.getClass().getSimpleName();
        return type.isBlank() ? "Asset preparation failure" : type;
    }

    @FunctionalInterface interface Resources { InputStream open(String name) throws IOException; }
    record Status(String state, String reason, String source) { }
    // Deliberately uses identity equality: invalidate followed by get creates a distinct worker token.
    private static final class Cached {
        final Status status; final Optional<Asset> asset;
        Cached(Status status, Optional<Asset> asset) { this.status = status; this.asset = asset; }
    }

    private static Asset read(String id, Path path) throws IOException {
        if (Files.size(path) > MAX_RAW_BYTES) throw new IOException("bbmodel exceeds 8 MiB");
        try (InputStream input = Files.newInputStream(path)) { return pack(id, readBounded(input)); }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] raw = input.readNBytes(MAX_RAW_BYTES + 1);
        if (raw.length == 0 || raw.length > MAX_RAW_BYTES) throw new IOException("Invalid bbmodel size");
        return raw;
    }

    static Asset pack(String id, byte[] raw) throws IOException {
        if (raw.length == 0 || raw.length > MAX_RAW_BYTES) throw new IOException("Invalid bbmodel size");
        try {
            var object = JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!object.has("elements") || !object.has("outliner") || !object.has("textures") || !object.has("animations"))
                throw new IOException("Incomplete bbmodel geometry/texture/animation asset");
        } catch (RuntimeException malformed) { throw new IOException("Malformed bbmodel JSON", malformed); }
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(raw); }
        byte[] compressed = output.toByteArray();
        if (compressed.length > MAX_COMPRESSED_BYTES) throw new IOException("Compressed bbmodel exceeds 4 MiB");
        return new Asset(id, hash, raw.length, compressed);
    }

    record Asset(String modelId, String hash, int rawBytes, byte[] compressed) {
        Asset { compressed = compressed.clone(); }
        int chunks(int chunkBytes) { return (compressed.length + chunkBytes - 1) / chunkBytes; }
        byte[] chunk(int index, int chunkBytes) {
            int offset = Math.multiplyExact(index, chunkBytes);
            if (offset < 0 || offset >= compressed.length) throw new IllegalArgumentException("Invalid asset chunk index");
            return java.util.Arrays.copyOfRange(compressed, offset, Math.min(compressed.length, offset + chunkBytes));
        }
    }
}
