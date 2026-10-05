package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.network.AssetTransfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Complete native assets, used by both local import and the passive private-model receiver. */
public final class NativeModelBundle {
    public static final int MAX_FILES = 256;
    private static final Set<String> EXTENSIONS = Set.of("json", "bbmodel", "png", "bmp", "jpg", "jpeg", "webp", "ogg", "molang");
    private NativeModelBundle() { }

    /** A valid local model may be too large to share; this failure must not block local rendering. */
    public static final class NetworkBudgetExceededException extends IOException {
        public NetworkBudgetExceededException() { super("完整模型超过私人同步 8 MiB 限制，仍可在本地使用"); }
    }

    public record Validated(String kind, String entry, Map<String, byte[]> files) {
        public Validated { files = immutableFiles(files); }
        @Override public Map<String, byte[]> files() { return immutableFiles(files); }
    }

    public static byte[] encode(String kind, String entry, Map<String, byte[]> source) throws IOException {
        Map<String, byte[]> files = checkedFiles(source);
        checkManifest(kind, entry, files);
        if (files.containsKey("manifest.json")) throw new IOException("原生模型不能占用 bundle manifest.json");
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", 1); manifest.addProperty("kind", kind); manifest.addProperty("entry", entry);
        Map<String, byte[]> bundled = new LinkedHashMap<>();
        bundled.put("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8)); bundled.putAll(files);
        checkedFiles(bundled);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : bundled.entrySet()) {
                ZipEntry item = new ZipEntry(file.getKey()); item.setTime(0);
                zip.putNextEntry(item); zip.write(file.getValue()); zip.closeEntry();
            }
        }
        if (bytes.size() > AssetTransfer.MAX_RAW) throw new NetworkBudgetExceededException();
        return bytes.toByteArray();
    }

    public static Validated validate(byte[] bundle) throws IOException {
        Map<String, byte[]> files = archiveFiles(bundle, false);
        byte[] descriptor = files.remove("manifest.json");
        if (descriptor == null || descriptor.length == 0 || descriptor.length > 4096)
            throw new IOException("原生模型 bundle 清单缺失或过大");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(descriptor)).toString();
            String kind = null, entry = null; Set<String> names = new java.util.HashSet<>();
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.setLenient(false); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new IOException("模型 bundle 清单字段重复");
                    switch (name) {
                        case "format" -> {
                            if (reader.peek() != JsonToken.NUMBER || !reader.nextString().equals("1"))
                                throw new IOException("原生模型 bundle 版本不支持");
                        }
                        case "kind" -> { if (reader.peek() != JsonToken.STRING) throw new IOException("模型 bundle 类型无效"); kind = reader.nextString(); }
                        case "entry" -> { if (reader.peek() != JsonToken.STRING) throw new IOException("模型 bundle 入口无效"); entry = reader.nextString(); }
                        default -> throw new IOException("模型 bundle 清单字段无效");
                    }
                }
                reader.endObject();
                if (names.size() != 3 || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("模型 bundle 清单无效");
            }
            checkManifest(kind, entry, files);
            return new Validated(kind, entry, files);
        } catch (RuntimeException invalid) { throw new IOException("无效原生模型 bundle 清单", invalid); }
    }

    public static LocalModelLibrary.Loaded decode(byte[] bundle, String textureId) throws IOException {
        Validated value = validate(bundle); Map<String, byte[]> files = value.files();
        if (value.kind().equals("bbmodel")) {
            if (textureId != null && !textureId.isEmpty()) throw new IOException("独立 BBModel 没有 YSM 皮肤列表");
            byte[] raw = files.get(value.entry());
            return new LocalModelLibrary.Loaded(AssetTransfer.hash(raw), BbModel.parse(raw));
        }
        YsmFolderModel.Imported imported = YsmFolderModel.readNetworkMemoryWithProfile(files, textureId);
        return new LocalModelLibrary.Loaded(AssetTransfer.hash(imported.raw()), BbModel.parse(imported.raw()),
                imported.previewAnimation(), imported.profile());
    }

    private static void checkManifest(String kind, String entry, Map<String, byte[]> files) throws IOException {
        boolean valid = "bbmodel".equals(kind) && "model.bbmodel".equals(entry)
                || "ysm".equals(kind) && "ysm.json".equals(entry);
        if (!valid || !files.containsKey(entry)) throw new IOException("原生模型 bundle 类型或入口无效");
    }

    public static boolean isZip(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 && bytes[3] == 4 || bytes[2] == 5 && bytes[3] == 6);
    }

    /** ZIP roots are detected for manual import; wire bundles always have one explicit root. */
    static Map<String, byte[]> archiveFiles(byte[] bytes, boolean detectRoot) throws IOException {
        return archiveFiles(bytes, detectRoot, AssetTransfer.MAX_RAW);
    }

    static Map<String, byte[]> localArchiveFiles(byte[] bytes) throws IOException {
        return archiveFiles(bytes, true, LocalModelBudget.MAX_BYTES);
    }

    private static Map<String, byte[]> archiveFiles(byte[] bytes, boolean detectRoot, int maximumBytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > maximumBytes || !isZip(bytes))
            throw new IOException("模型归档大小或格式无效");
        Map<String, byte[]> result = new LinkedHashMap<>(); Set<String> folded = new java.util.HashSet<>();
        int total = 0, scanned = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++scanned > MAX_FILES) throw new IOException("模型归档条目超过 256");
                String name = entry.getName();
                String checked = entry.isDirectory() && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                if (!YsmFolderModel.safeRelativePath(checked) || !folded.add(checked.toLowerCase(Locale.ROOT)))
                    throw new IOException("模型归档含逃逸路径或大小写重复条目");
                if (entry.isDirectory()) {
                    if (zip.read() != -1) throw new IOException("模型归档目录不能携带文件数据");
                    zip.closeEntry(); continue;
                }
                byte[] value = zip.readNBytes(maximumBytes - total + 1);
                total += value.length;
                if (total > maximumBytes) throw new IOException("模型归档展开后不能超过 " + maximumBytes / (1024 * 1024) + " MiB");
                if (allowed(name)) result.put(name, value);
                else if (!detectRoot) throw new IOException("模型归档含无关文件类型: " + name);
                // Manual native ZIPs may carry a license/readme; it is bounded but never extracted or executed.
                zip.closeEntry();
            }
        } catch (IllegalArgumentException invalid) { throw new IOException("模型归档文件名不是 UTF-8", invalid); }
        if (result.isEmpty()) throw new IOException("模型归档没有资产");
        if (detectRoot && !result.containsKey("ysm.json")) {
            String root = null;
            for (String path : result.keySet()) if (path.endsWith("/ysm.json")) {
                if (root != null) throw new IOException("模型归档包含多个 YSM 根目录");
                root = path.substring(0, path.lastIndexOf('/') + 1);
            }
            if (root == null) for (String path : result.keySet()) if (path.endsWith("/main.json")
                    && result.containsKey(path.substring(0, path.length() - "main.json".length()) + "arm.json")) {
                if (root != null) throw new IOException("模型归档包含多个旧 YSM 根目录");
                root = path.substring(0, path.lastIndexOf('/') + 1);
            }
            if (root == null && result.containsKey("main.json") && result.containsKey("arm.json")) return result;
            if (root == null) throw new IOException("模型归档未找到 YSM 根目录");
            Map<String, byte[]> rooted = new LinkedHashMap<>();
            for (var file : result.entrySet()) if (file.getKey().startsWith(root))
                rooted.put(file.getKey().substring(root.length()), file.getValue());
            result = rooted;
        }
        return result;
    }

    static Map<String, byte[]> checkedFiles(Map<String, byte[]> source) throws IOException {
        return checkedFiles(source, AssetTransfer.MAX_RAW);
    }

    static Map<String, byte[]> checkedLocalFiles(Map<String, byte[]> source) throws IOException {
        return checkedFiles(source, LocalModelBudget.MAX_BYTES);
    }

    private static Map<String, byte[]> checkedFiles(Map<String, byte[]> source, int maximumBytes) throws IOException {
        if (source == null || source.isEmpty() || source.size() > MAX_FILES) throw new IOException("模型资产数量无效");
        Map<String, byte[]> copy = new LinkedHashMap<>(); Set<String> folded = new java.util.HashSet<>(); long total = 0;
        for (var file : source.entrySet()) {
            String path = file.getKey(); byte[] bytes = file.getValue();
            if (!YsmFolderModel.safeRelativePath(path) || !allowed(path) || !folded.add(path.toLowerCase(Locale.ROOT)))
                throw new IOException("模型资产路径或类型无效: " + path);
            if (bytes == null) throw new IOException("模型资产内容缺失");
            if ((total += bytes.length) > maximumBytes) {
                if (maximumBytes == AssetTransfer.MAX_RAW) throw new NetworkBudgetExceededException();
                throw new IOException("本地完整模型资产不能超过 64 MiB");
            }
            copy.put(path, bytes.clone());
        }
        return copy;
    }

    private static boolean allowed(String name) {
        int dot = name.lastIndexOf('.'); return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
    private static Map<String, byte[]> immutableFiles(Map<String, byte[]> source) {
        Map<String, byte[]> copy = new LinkedHashMap<>(); source.forEach((name, bytes) -> copy.put(name, bytes.clone()));
        return Collections.unmodifiableMap(copy);
    }
}
