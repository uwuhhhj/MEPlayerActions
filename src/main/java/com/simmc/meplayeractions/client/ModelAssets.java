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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/** Server-owned raw Blockbench assets. Client input never becomes a filesystem path. */
final class ModelAssets {
    static final int MAX_RAW_BYTES = 8 * 1024 * 1024;
    static final int MAX_COMPRESSED_BYTES = 4 * 1024 * 1024;
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private final JavaPlugin plugin;
    private final Path ownModels, engineBlueprints;
    private final Map<String, Optional<Asset>> cache = new ConcurrentHashMap<>();
    private final Set<String> preparing = ConcurrentHashMap.newKeySet();

    ModelAssets(JavaPlugin plugin) {
        this.plugin = plugin;
        ownModels = plugin.getDataFolder().toPath().resolve("models");
        Plugin engine = plugin.getServer().getPluginManager().getPlugin("ModelEngine");
        engineBlueprints = engine == null ? null : engine.getDataFolder().toPath().resolve("blueprints");
    }

    Optional<Asset> get(String modelId) {
        if (!MODEL_ID.matcher(modelId).matches()) return Optional.empty();
        Optional<Asset> existing = cache.get(modelId);
        if (existing != null) return existing;
        if (preparing.add(modelId)) {
            try {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try { cache.put(modelId, load(modelId)); }
                    finally { preparing.remove(modelId); }
                });
            } catch (RuntimeException stopped) { preparing.remove(modelId); }
        }
        // Pending preparation advertises no asset and leaves ME rendering active.
        return Optional.empty();
    }

    private Optional<Asset> load(String id) {
        try {
            Path own = ownModels.resolve(id + ".bbmodel");
            if (Files.isRegularFile(own)) return Optional.of(read(id, own));
            try (InputStream resource = plugin.getResource("models/" + id + ".bbmodel")) {
                if (resource != null) return Optional.of(pack(id, readBounded(resource)));
            }
            if (engineBlueprints != null) {
                Path blueprints = engineBlueprints;
                if (Files.isDirectory(blueprints)) {
                    try (var paths = Files.walk(blueprints, 16)) {
                        // Stable paths make duplicate identifiers diagnosable and reproducible.
                        var candidates = paths.filter(p -> Files.isRegularFile(p)
                                && p.getFileName().toString().endsWith(".bbmodel")).sorted().toList();
                        for (Path path : candidates) {
                            if (path.getFileName().toString().equals(id + ".bbmodel")) return Optional.of(read(id, path));
                        }
                        for (Path path : candidates) {
                            if (Files.size(path) > MAX_RAW_BYTES) continue;
                            try {
                                byte[] raw = Files.readAllBytes(path);
                                var json = JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                                if (json.isJsonObject() && json.getAsJsonObject().has("model_identifier")
                                        && id.equals(json.getAsJsonObject().get("model_identifier").getAsString())) {
                                    return Optional.of(pack(id, raw));
                                }
                            } catch (RuntimeException malformedOtherModel) { /* Other malformed blueprints cannot disable this asset. */ }
                        }
                    }
                }
            }
            plugin.getLogger().warning("客户端模型资产未找到：" + id + "；保持 ModelEngine 渲染");
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("客户端模型资产不可用：" + id + "：" + exception.getMessage() + "；保持 ModelEngine 渲染");
        }
        return Optional.empty();
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
        var object = JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (!object.has("elements") || !object.has("outliner") || !object.has("textures") || !object.has("animations"))
            throw new IOException("Incomplete bbmodel geometry/texture/animation asset");
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
