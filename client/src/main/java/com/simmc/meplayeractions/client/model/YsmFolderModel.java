package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import com.simmc.meplayeractions.expression.Molang;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Imports bounded local YSM assets and author metadata into the independent cube renderer. */
public final class YsmFolderModel {
    public static final String DEFAULT_ID = BuiltinYsmModels.DEFAULT_ID;
    private static final int MAX_BONES = 2047, MAX_CUBES = 4096, MAX_FRAMES = 200_000;
    // Original wine-fox parallel4 has 64 ordered programs; hold_mainhand:spear lasts 10,000 seconds.
    private static final int MAX_TIMELINE_BYTES = 32_768;
    private static final double MAX_ANIMATION_SECONDS = 10_000;
    private YsmFolderModel() { }

    /** Rendering bytes and the complete native source/profile retain separate hashes during private synchronization. */
    public record Imported(byte[] raw, String previewAnimation, YsmModelProfile profile, Map<String, byte[]> sourceFiles) {
        public Imported {
            raw = raw.clone();
            Map<String, byte[]> copy = new LinkedHashMap<>();
            sourceFiles.forEach((path, bytes) -> copy.put(path, bytes.clone()));
            sourceFiles = Collections.unmodifiableMap(copy);
        }
        public Imported(byte[] raw, String previewAnimation, YsmModelProfile profile) { this(raw, previewAnimation, profile, Map.of()); }
        public Imported(byte[] raw, String previewAnimation) { this(raw, previewAnimation, YsmModelProfile.empty()); }
        @Override public byte[] raw() { return raw.clone(); }
        @Override public Map<String, byte[]> sourceFiles() {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            sourceFiles.forEach((path, bytes) -> copy.put(path, bytes.clone()));
            return Collections.unmodifiableMap(copy);
        }
    }

    public static byte[] bundledDefault() throws IOException {
        return bundledDefault(false);
    }

    public static byte[] bundledDefault(boolean alternateTexture) throws IOException {
        return bundledDefaultWithPreview(alternateTexture).raw();
    }

    public static Imported bundledDefaultWithPreview(boolean alternateTexture) throws IOException {
        return bundledDefaultWithProfile(alternateTexture ? "blue" : null);
    }

    public static Imported bundledDefaultWithProfile(String textureId) throws IOException {
        return bundledWithProfile(DEFAULT_ID, textureId);
    }

    /** Loads a fixed built-in resource root without filesystem or server-pack authority. */
    public static Imported bundledWithProfile(String id, String textureId) throws IOException {
        BuiltinYsmModels.Model model = BuiltinYsmModels.find(id)
                .orElseThrow(() -> new IOException("未知的内置 YSM 模型: " + id));
        return convert(new Assets((name, maximum) -> {
            try (InputStream input = YsmFolderModel.class.getResourceAsStream(model.resourceRoot() + name)) {
                if (input == null) throw new java.nio.file.NoSuchFileException("内置 YSM 模型资源缺失: " + id + "/" + name);
                return boundedRead(input, maximum);
            }
        }, model.languages(), directory -> model.sounds().stream()
                .filter(file -> file.startsWith(directory + "/")).toList(), List.of()), textureId);
    }

    public static byte[] read(Path folder) throws IOException {
        return readWithPreview(folder).raw();
    }

    public static Imported readWithPreview(Path folder) throws IOException {
        return readWithProfile(folder, null);
    }

    public static Imported readWithProfile(Path folder, String textureId) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            checkOrdinaryDirectories(root.getParent());
            byte[] bytes;
            try (InputStream input = Files.newInputStream(root, LinkOption.NOFOLLOW_LINKS)) {
                bytes = boundedRead(input, LocalModelBudget.MAX_BYTES);
            }
            return readArchiveWithProfile(bytes, textureId);
        }
        checkOrdinaryDirectories(root);
        if (!Files.isRegularFile(root.resolve("ysm.json"), LinkOption.NOFOLLOW_LINKS)) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            collectLegacyFiles(root, root, files, new int[]{0}, 0);
            return readMemoryWithProfile(files, textureId);
        }
        return convert(new Assets((name, maximum) -> {
            Path file = root.resolve(name).normalize();
            if (!file.startsWith(root) || file.equals(root)) throw new IOException("YSM 资源不能离开模型文件夹");
            checkOrdinaryDirectories(file.getParent());
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("YSM 资源必须是普通文件: " + name);
            if (Files.size(file) > maximum) throw new IOException("YSM 资源超过大小限制");
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { return boundedRead(input, maximum); }
        }, languageFiles(root), directory -> soundFiles(root, directory),
                resourceFiles(root, "functions", ".molang", 64, 256)), textureId);
    }

    /** Reads a complete local archive without extracting author paths onto the filesystem. */
    public static Imported readArchiveWithProfile(byte[] bytes, String textureId) throws IOException {
        if (!NativeModelBundle.isZip(bytes)) return NativeYsmFile.read(bytes, textureId);
        return readMemoryWithProfile(NativeModelBundle.localArchiveFiles(bytes), textureId);
    }

    public static Imported readMemoryWithProfile(Map<String, byte[]> sourceFiles, String textureId) throws IOException {
        return readMemoryWithProfile(sourceFiles, textureId, 65535);
    }

    static Imported readMemoryWithProfile(Map<String, byte[]> sourceFiles, String textureId, int nativeFormat) throws IOException {
        return readMemoryWithProfile(sourceFiles, textureId, nativeFormat, LocalModelBudget.MAX_BYTES);
    }

    static Imported readNetworkMemoryWithProfile(Map<String, byte[]> sourceFiles, String textureId) throws IOException {
        return readMemoryWithProfile(sourceFiles, textureId, 65535, AssetTransfer.MAX_RAW);
    }

    private static Imported readMemoryWithProfile(Map<String, byte[]> sourceFiles, String textureId, int nativeFormat, int maximumBytes) throws IOException {
        Map<String, byte[]> files = maximumBytes == AssetTransfer.MAX_RAW
                ? NativeModelBundle.checkedFiles(sourceFiles) : NativeModelBundle.checkedLocalFiles(sourceFiles);
        if (!files.containsKey("ysm.json")) files.put("ysm.json", legacyManifest(files).toString().getBytes(StandardCharsets.UTF_8));
        Assets assets = new Assets((path, maximum) -> {
            byte[] value = files.get(path);
            if (value == null) throw new java.nio.file.NoSuchFileException("YSM 资源缺失: " + path);
            if (value.length > maximum) throw new IOException("YSM 资源超过大小限制: " + path);
            return value.clone();
        }, files.keySet().stream().filter(path -> path.matches("lang/[a-zA-Z0-9_-]{1,32}\\.json")).sorted().toList(),
                directory -> files.keySet().stream().filter(path -> path.startsWith(directory + "/") && path.endsWith(".ogg")).sorted().toList(),
                files.keySet().stream().filter(path -> path.startsWith("functions/") && path.endsWith(".molang")).sorted().toList(), maximumBytes);
        assets.nativeFormat = nativeFormat;
        return convert(assets, textureId);
    }

    private static void collectLegacyFiles(Path root, Path directory, Map<String, byte[]> result, int[] scanned, int depth) throws IOException {
        if (depth > 8) throw new IOException("旧 YSM 资源目录层级过深");
        checkOrdinaryDirectories(directory);
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path file : children) {
                if (++scanned[0] > 512) throw new IOException("旧 YSM 资源目录条目过多");
                var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("旧 YSM 资源不能包含链接");
                if (attributes.isDirectory()) collectLegacyFiles(root, file, result, scanned, depth + 1);
                else if (attributes.isRegularFile() && file.getFileName().toString().matches("(?i).+\\.(json|png|bmp|jpg|jpeg|webp|ogg|molang)")) {
                    if (result.size() >= 255) throw new IOException("旧 YSM 资源文件数量过多");
                    try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        result.put(root.relativize(file).toString().replace('\\', '/'), boundedRead(input, LocalModelBudget.MAX_BYTES));
                    }
                    NativeModelBundle.checkedLocalFiles(result);
                }
            }
        }
    }

    /** Mature legacy layout: main.json, arm.json, root PNGs and <family>.animation.json. */
    private static JsonObject legacyManifest(Map<String, byte[]> files) throws IOException {
        if (!files.containsKey("main.json") || !files.containsKey("arm.json")) throw new IOException("旧 YSM 模型需要 main.json 和 arm.json");
        JsonObject manifest = new JsonObject(), metadata = new JsonObject(), properties = new JsonObject(), declarations = new JsonObject();
        manifest.addProperty("spec", 2); manifest.add("metadata", metadata); manifest.add("properties", properties); manifest.add("files", declarations);
        JsonObject player = new JsonObject(), model = new JsonObject(), animations = new JsonObject();
        model.addProperty("main", "main.json"); model.addProperty("arm", "arm.json"); player.add("model", model);
        JsonArray textures = new JsonArray();
        for (String path : files.keySet()) if (!path.contains("/") && path.endsWith(".png") && !path.equals("arrow.png")) textures.add(path);
        if (textures.isEmpty()) throw new IOException("旧 YSM 模型至少需要一个主贴图");
        player.add("texture", textures);
        for (String family : List.of("main", "arm", "extra", "tac", "carryon", "slashblade", "tlm")) {
            String path = family + ".animation.json";
            if (files.containsKey(path)) animations.addProperty(family, path);
        }
        if (!animations.has("main")) {
            JsonObject empty = new JsonObject(); empty.add("animations", new JsonObject());
            files.put("main.animation.json", empty.toString().getBytes(StandardCharsets.UTF_8)); animations.addProperty("main", "main.animation.json");
        }
        player.add("animation", animations); declarations.add("player", player);
        JsonObject info = files.containsKey("info.json") ? parseJson(files.get("info.json")) : new JsonObject();
        JsonObject main = parseJson(files.get("main.json")); JsonArray geometries = array(main.get("minecraft:geometry"));
        if (!geometries.isEmpty()) {
            JsonObject description = object(object(geometries.get(0)).get("description"));
            if (description.has("ysm_extra_info")) {
                JsonObject inherited = object(description.get("ysm_extra_info")).deepCopy(); info.entrySet().forEach(entry -> inherited.add(entry.getKey(), entry.getValue())); info = inherited;
            }
        }
        for (String key : List.of("name", "tips")) if (info.has(key)) metadata.add(key, info.get(key));
        if (info.has("license")) { JsonObject license = new JsonObject(); license.add("desc", info.get("license")); metadata.add("license", license); }
        if (info.has("authors")) {
            JsonArray authors = new JsonArray(); for (JsonElement author : array(info.get("authors"))) { JsonObject value = new JsonObject(); value.add("name", author); authors.add(value); } metadata.add("authors", authors);
        }
        if (info.has("free")) properties.add("free", info.get("free"));
        JsonObject extra = new JsonObject();
        if (animations.has("extra")) for (String name : object(parseJson(files.get("extra.animation.json")).get("animations")).keySet()) extra.addProperty(name, name);
        if (info.has("extra_animation_names")) {
            extra = new JsonObject(); int index = 0; for (JsonElement label : array(info.get("extra_animation_names"))) extra.add("extra" + index++, label);
        }
        properties.add("extra_animation", extra);
        if (files.containsKey("arrow.json")) {
            if (!files.containsKey("arrow.png")) throw new IOException("旧 YSM arrow.json 缺少 arrow.png");
            JsonObject arrow = new JsonObject(); arrow.addProperty("model", "arrow.json"); arrow.addProperty("texture", "arrow.png"); arrow.addProperty("match", "minecraft:arrow");
            if (files.containsKey("arrow.animation.json")) arrow.addProperty("animation", "arrow.animation.json"); JsonArray arrows = new JsonArray(); arrows.add(arrow); declarations.add("projectiles", arrows);
        }
        return manifest;
    }

    private static List<String> languageFiles(Path root) throws IOException {
        Path directory = root.resolve("lang");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        checkOrdinaryDirectories(directory);
        List<String> result = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) if (file.getFileName().toString().matches("[a-zA-Z0-9_-]{1,32}\\.json")) {
                if (result.size() >= 16) throw new IOException("YSM 语言文件数量超过限制");
                result.add("lang/" + file.getFileName());
            }
        }
        result.sort(String::compareTo); return List.copyOf(result);
    }

    private static void checkOrdinaryDirectories(Path directory) throws IOException {
        for (Path ancestor = directory; ancestor != null; ancestor = ancestor.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                    || !ancestor.toRealPath().equals(ancestor.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("YSM 文件夹及资源路径不能包含链接");
        }
    }

    private static List<String> soundFiles(Path root, String directory) throws IOException {
        return resourceFiles(root, directory, ".ogg", 32, 128);
    }

    private static List<String> resourceFiles(Path root, String directory, String suffix, int maximum, int scannedMaximum) throws IOException {
        if (!safeRelativePath(directory)) throw new IOException("YSM 资源目录无效");
        Path sounds = root.resolve(directory).normalize();
        if (!sounds.startsWith(root) || sounds.equals(root)) throw new IOException("YSM 资源目录不能离开模型文件夹");
        if (!Files.exists(sounds, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<String> result = new ArrayList<>();
        collectResources(root, sounds, result, new int[]{0}, 0, suffix, maximum, scannedMaximum);
        result.sort(String::compareTo); return List.copyOf(result);
    }

    private static void collectResources(Path root, Path directory, List<String> result, int[] entries, int depth,
                                         String suffix, int maximum, int scannedMaximum) throws IOException {
        if (depth > 8) throw new IOException("YSM 资源目录层级过深");
        checkOrdinaryDirectories(directory);
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (++entries[0] > scannedMaximum) throw new IOException("YSM 资源目录条目超过限制");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("YSM 资源路径不能包含链接");
                if (attributes.isDirectory()) collectResources(root, file, result, entries, depth + 1, suffix, maximum, scannedMaximum);
                else if (!attributes.isRegularFile()) throw new IOException("YSM 资源必须是普通文件");
                else if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                    if (result.size() >= maximum) throw new IOException("YSM 资源文件数量超过限制");
                    result.add(root.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
    }

    @FunctionalInterface private interface Reader { byte[] read(String name, int maximum) throws IOException; }
    @FunctionalInterface private interface SoundLister { List<String> files(String directory) throws IOException; }
    private static final class Assets {
        final Reader reader;
        final List<String> languageFiles;
        final List<String> functionFiles;
        final SoundLister soundLister;
        final Map<String, byte[]> files = new LinkedHashMap<>(), pngFiles = new LinkedHashMap<>();
        final JsonObject functions = new JsonObject(), events = new JsonObject();
        int nativeFormat = 65535;
        boolean mergeMultilineExpressions, allCutout;
        final int maximumBytes;
        int bytes, outputBytes;
        long pixels;
        Assets(Reader reader, List<String> languageFiles, SoundLister soundLister, List<String> functionFiles) {
            this(reader, languageFiles, soundLister, functionFiles, LocalModelBudget.MAX_BYTES);
        }
        Assets(Reader reader, List<String> languageFiles, SoundLister soundLister, List<String> functionFiles, int maximumBytes) {
            this.reader = reader; this.languageFiles = languageFiles; this.soundLister = soundLister; this.functionFiles = functionFiles;
            this.maximumBytes = maximumBytes;
        }
        byte[] get(String name, String suffix) throws IOException {
            return get(name, suffix, maximumBytes);
        }
        byte[] get(String name, String suffix, int maximum) throws IOException {
            if (!safeRelativePath(name) || !name.toLowerCase(Locale.ROOT).endsWith(suffix))
                throw new IOException("YSM 资源路径或扩展名无效: " + name);
            byte[] cached = files.get(name);
            if (cached != null) {
                if (cached.length > maximum) throw new IOException("YSM 资源超过大小限制");
                return cached;
            }
            if (files.size() >= 255) throw new IOException("YSM 资源文件数量超过限制");
            byte[] result = reader.read(name, maximum);
            if ((result.length == 0 && !suffix.equals(".molang")) || (bytes += result.length) > maximumBytes)
                throw new IOException("YSM 模型、动画、元数据和贴图总大小不能超过 " + maximumBytes / (1024 * 1024) + " MiB");
            files.put(name, result);
            return result;
        }
        JsonObject json(String name) throws IOException { return parseJson(get(name, ".json")); }
        byte[] png(String name) throws IOException {
            if (!NativeYsmImages.supportedPath(name)) throw new IOException("YSM 图像文件类型未接入: " + name);
            byte[] original = get(name, name.substring(name.lastIndexOf('.')));
            byte[] result = NativeYsmImages.png(original, 0, 0, 0);
            if (!pngFiles.containsKey(name)) {
                pixels += BbModel.validatePng(result, 16_777_216 - pixels);
                pngFiles.put(name, result);
            }
            return result;
        }
        byte[] output(Converter converter, JsonObject controllers, String family) throws IOException {
            converter.output.add("ysm_animation_controllers", NativeYsmScriptArrays.controllerActions(controllers, mergeMultilineExpressions));
            converter.output.addProperty("ysm_merge_multiline_expr", mergeMultilineExpressions);
            // all_cutout is the source GPU cube forceCull policy, not a texture alpha mode.
            // Its CPU ModelRendererBridge does not consume cullable; preserve that CPU behavior.
            converter.output.addProperty("ysm_all_cutout", allCutout);
            converter.output.addProperty("ysm_controller_family", family);
            // OpenYSM's folder-deserializer internal format version, independent of manifest spec 2.
            converter.output.addProperty("ysm_format_version", nativeFormat);
            if (!functions.isEmpty()) converter.output.add("ysm_functions", functions.deepCopy());
            if (!events.isEmpty()) converter.output.add("ysm_events", events.deepCopy());
            byte[] result = converter.output.toString().getBytes(StandardCharsets.UTF_8);
            if (result.length > maximumBytes || (outputBytes += result.length) > maximumBytes)
                throw new IOException("YSM 转换后的模型总大小超过 " + maximumBytes / (1024 * 1024) + " MiB");
            if (maximumBytes == AssetTransfer.MAX_RAW) BbModel.parse(result); else BbModel.parseLocal(result);
            return result;
        }
    }

    static boolean safeRelativePath(String name) {
        if (name == null || name.isBlank() || name.length() > 256 || !name.equals(name.strip())
                || name.matches(".*[<>:\"\\\\|?*\\p{Cntrl}].*") || name.startsWith("/")) return false;
        for (String segment : name.split("/", -1))
            if (segment.isBlank() || segment.startsWith(".") || segment.contains("..") || !segment.equals(segment.strip())) return false;
        return true;
    }

    private static byte[] boundedRead(InputStream input, int maximum) throws IOException {
        byte[] result = input.readNBytes(maximum + 1);
        if (result.length > maximum) throw new IOException("YSM 资源大小超出限制");
        return result;
    }

    private static JsonObject parseJson(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            int depth = 0; boolean quoted = false, escaped = false;
            for (char c : text.toCharArray()) {
                if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
                else if (c == '"') quoted = true;
                else if (c == '{' || c == '[') { if (++depth > 96) throw new IOException("YSM JSON 嵌套过深"); }
                else if (c == '}' || c == ']') { if (--depth < 0) throw new IOException("YSM JSON 不完整"); }
            }
            if (depth != 0 || quoted) throw new IOException("YSM JSON 不完整");
            return object(JsonParser.parseString(text));
        } catch (RuntimeException | java.nio.charset.CharacterCodingException error) { throw new IOException("无效的 UTF-8 YSM JSON", error); }
    }

    private static Imported convert(Assets assets, String textureOverride) throws IOException {
        try {
            JsonObject manifest = assets.json("ysm.json");
            if (manifest.has("mpa_native_format")) {
                JsonElement version = manifest.get("mpa_native_format");
                if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) throw invalid("YSM 二进制动画格式无效");
                double value = version.getAsDouble(); int declaredFormat = (int) value;
                if (declaredFormat < 1 || declaredFormat > 32 || value != declaredFormat) throw invalid("YSM 二进制动画格式无效");
                assets.nativeFormat = declaredFormat;
            }
            if (number(manifest, "spec", 2) != 2) throw new IOException("目前支持 YSM spec 2 主模型文件夹");
            JsonObject player = object(object(manifest.get("files")).get("player"));
            JsonObject modelFiles = object(player.get("model")), animationFiles = object(player.get("animation"));
            JsonObject properties = manifest.has("properties") ? object(manifest.get("properties")) : new JsonObject();
            assets.mergeMultilineExpressions = bool(properties, "merge_multiline_expr", false);
            assets.allCutout = bool(properties, "all_cutout", false);
            bool(properties, "render_layers_first", false);
            if (manifest.has("metadata")) {
                JsonObject metadata = object(manifest.get("metadata"));
                if (metadata.has("authors")) for (JsonElement author : array(metadata.get("authors"))) {
                    JsonObject descriptor = object(author);
                    if (descriptor.has("avatar")) {
                        String path = string(descriptor, "avatar", "");
                        if (!path.isEmpty() && !safeRelativePath(path)) throw invalid("YSM 作者头像资源路径无效");
                        // Native metadata can reference an optional avatar in an unavailable image codec.
                        if (!path.isEmpty() && NativeYsmImages.supportedPath(path)) try { assets.png(path); }
                        catch (java.nio.file.NoSuchFileException optionalAvatar) { /* Mature author UI uses its placeholder when no avatar exists. */ }
                    }
                }
            }
            loadFunctions(assets, properties);
            List<YsmModelProfile.TextureChoice> textures = textureChoices(assets, player.get("texture"));
            YsmModelProfile.TextureChoice defaultTexture = chooseTexture(textures, string(properties, "default_texture", ""), false);
            YsmModelProfile.TextureChoice texture = chooseTexture(textures,
                    textureOverride == null || textureOverride.isEmpty() ? defaultTexture.id() : textureOverride, true);
            JsonObject geometry = assets.json(string(modelFiles, "main", ""));
            JsonObject controllers = controllers(assets, player.get("animation_controllers"));
            Converter converter = new Converter(geometry, texture.png(), assets.mergeMultilineExpressions);
            converter.animations(bodyAnimations(assets, animationFiles));
            byte[] result = assets.output(converter, controllers, "player");
            List<YsmModelProfile.Component> components = new ArrayList<>();
            if (modelFiles.has("arm")) {
                Converter arm = new Converter(assets.json(string(modelFiles, "arm", "")), texture.png(), assets.mergeMultilineExpressions);
                if (animationFiles.has("arm")) arm.animations(assets.json(string(animationFiles, "arm", "")));
                components.add(new YsmModelProfile.Component("arm", "arm", List.of(), textures, texture.id(),
                        assets.output(arm, new JsonObject(), "arm"), player, assets.maximumBytes));
            }
            if (modelFiles.has("fp_arm") || modelFiles.has("arm")) {
                String geometryFile = string(modelFiles, modelFiles.has("fp_arm") ? "fp_arm" : "arm", "");
                Converter arm = new Converter(assets.json(geometryFile), texture.png(), assets.mergeMultilineExpressions);
                if (animationFiles.has("fp_arm")) arm.animations(assets.json(string(animationFiles, "fp_arm", "")));
                else if (animationFiles.has("arm")) arm.animations(assets.json(string(animationFiles, "arm", "")));
                components.add(new YsmModelProfile.Component("fp_arm", "fp_arm", List.of(), textures, texture.id(),
                        assets.output(arm, controllers, "fp.arm"), player, assets.maximumBytes));
            }
            JsonObject files = object(manifest.get("files"));
            subEntities(assets, files.get("projectiles"), "projectile", components);
            if (files.has("arrow") && components.stream().noneMatch(component -> component.kind().equals("projectile")
                    && component.matches().contains("minecraft:arrow"))) {
                // OpenYSM legacy model type 3 is minecraft:arrow; it does not alias spectral arrows.
                JsonObject legacyArrow = new JsonObject(); legacyArrow.add("minecraft:arrow", files.get("arrow"));
                subEntities(assets, legacyArrow, "projectile", components);
            }
            subEntities(assets, files.get("vehicles"), "vehicle", components);
            JsonObject languages = new JsonObject();
            for (String file : assets.languageFiles) {
                JsonObject strings = assets.json(file);
                if (strings.size() > 2048) throw invalid("YSM 语言字符串数量超出限制");
                validateLanguage(strings, 0);
                String locale = stem(file).toLowerCase(Locale.ROOT);
                if (languages.has(locale)) throw invalid("YSM 语言名称重复");
                languages.add(locale, strings);
            }
            for (String property : List.of("gui_background", "gui_foreground")) if (properties.has(property)) {
                String resource = string(properties, property, ""); if (!resource.isEmpty()) assets.png(resource);
            }
            Map<String, String> families = new LinkedHashMap<>();
            for (var entry : animationFiles.entrySet()) {
                String path = entry.getValue().getAsString();
                if (!safeRelativePath(path) || !path.endsWith(".json")) throw invalid("YSM 动作资源路径无效");
                // Preserve every authored family in the full bundle, including other-mod optional animations.
                try { assets.get(path, ".json"); }
                catch (java.nio.file.NoSuchFileException optionalFamily) { /* Other-mod families do not make the vanilla player model unavailable. */ }
                families.put(entry.getKey(), path);
            }
            Map<String, byte[]> sounds = new LinkedHashMap<>();
            String soundPath = string(files, "sound_path", "sounds");
            if (!soundPath.isEmpty()) for (String file : assets.soundLister.files(soundPath)) {
                String name = stem(file);
                if (name.isBlank() || name.length() > 128 || sounds.containsKey(name)) throw invalid("YSM 音频名称重复或无效");
                sounds.put(name, assets.get(file, ".ogg"));
            }
            YsmModelProfile profile = new YsmModelProfile(manifest, languages, controllers, families,
                    textures, components, defaultTexture.id(), texture.id(), assets.pngFiles, sounds, assets.functions, assets.events, assets.maximumBytes);
            String previewAnimation = string(properties, "preview_animation", "");
            // Missing/obsolete optional UI clips fall back to idle without changing gameplay assets.
            if (!converter.namedClips.containsKey(previewAnimation)) previewAnimation = "";
            return new Imported(result, previewAnimation, profile, assets.files);
        } catch (RuntimeException error) { throw new IOException("YSM 主模型或动画无法解析: " + error.getMessage(), error); }
    }

    private static String stem(String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        int dot = filename.lastIndexOf('.'); return dot < 0 ? filename : filename.substring(0, dot);
    }

    /** ModelAssemblyFactory keeps fp_arm separate; body families use authored order and Map.putAll semantics. */
    private static JsonObject bodyAnimations(Assets assets, JsonObject animationFiles) throws IOException {
        if (!animationFiles.has("main")) throw invalid("YSM 主动画文件缺失");
        JsonObject result = new JsonObject(), merged = new JsonObject(); result.add("animations", merged);
        for (var family : animationFiles.entrySet()) {
            if (!Set.of("main", "arm", "extra").contains(family.getKey())) continue;
            JsonObject file = assets.json(family.getValue().getAsString());
            for (var animation : object(file.get("animations")).entrySet())
                merged.add(animation.getKey(), animation.getValue().deepCopy());
        }
        return result;
    }

    private static void validateLanguage(JsonElement value, int depth) {
        if (depth > 32) throw invalid("YSM 语言层级过深");
        if (value.isJsonObject()) for (var entry : value.getAsJsonObject().entrySet()) validateLanguage(entry.getValue(), depth + 1);
        else if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) validateLanguage(child, depth + 1);
        else if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("YSM 语言值必须是文本");
    }

    private static void loadFunctions(Assets assets, JsonObject properties) throws IOException {
        if (properties.has("events")) for (var event : object(properties.get("events")).entrySet()) {
            JsonArray scripts = event.getValue().isJsonArray() ? array(event.getValue()) : new JsonArray();
            if (!event.getValue().isJsonArray()) scripts.add(event.getValue());
            for (JsonElement script : scripts) {
                if (!script.isJsonPrimitive() || !script.getAsJsonPrimitive().isString()) throw invalid("YSM 事件脚本必须是文本");
                String text = script.getAsString(); validateScript(text);
                appendEvent(assets.events, event.getKey(), text);
            }
        }
        Set<String> stems = new HashSet<>(), callables = new HashSet<>();
        for (String file : assets.functionFiles) {
            String name = stem(file);
            if (name.isBlank() || name.length() > 128 || !stems.add(name.toLowerCase(Locale.ROOT)))
                throw invalid("YSM 函数文件名称重复或无效");
            byte[] bytes = assets.get(file, ".molang", 32_768);
            String script;
            try {
                script = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException invalid) { throw new IOException("YSM 函数必须是 UTF-8 文本", invalid); }
            if (script.startsWith("\ufeff")) script = script.substring(1);
            validateScript(script);
            int at = name.indexOf('@');
            if (at != 0) {
                String callable = at < 0 ? name : name.substring(0, at);
                if (callable.isBlank() || callable.chars().anyMatch(Character::isISOControl)
                        || !callables.add(callable.toLowerCase(Locale.ROOT))) throw invalid("YSM 可调用函数名称重复或无效");
                assets.functions.addProperty(callable, script);
            }
            if (at >= 0 && at + 1 < name.length()) appendEvent(assets.events, name.substring(at + 1), script);
        }
    }

    private static void validateScript(String script) {
        if (script.length() > 32_768) throw invalid("YSM 函数脚本超过 32 KiB 字符限制");
        // Compilation enforces token/depth limits, without running scripts or any external operation.
        Molang.compileNativeYsm(script);
    }

    private static void appendEvent(JsonObject events, String name, String script) {
        String canonical = name.toLowerCase(Locale.ROOT);
        if (canonical.isBlank() || canonical.length() > 128 || canonical.chars().anyMatch(Character::isISOControl))
            throw invalid("YSM 函数事件名称无效");
        JsonArray scripts;
        if (events.has(canonical)) scripts = events.getAsJsonArray(canonical);
        else {
            if (events.size() >= 64) throw invalid("YSM 函数事件数量超过限制");
            scripts = new JsonArray(); events.add(canonical, scripts);
        }
        if (scripts.size() >= 32) throw invalid("YSM 单事件函数数量超过限制");
        scripts.add(script);
    }

    private static List<YsmModelProfile.TextureChoice> textureChoices(Assets assets, JsonElement source) throws IOException {
        List<JsonElement> values = source != null && source.isJsonArray()
                ? new ArrayList<>(array(source).asList()) : List.of(Objects.requireNonNull(source));
        if (values.isEmpty() || values.size() > 16) throw invalid("YSM 贴图数量无效");
        List<YsmModelProfile.TextureChoice> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (JsonElement value : values) {
            JsonObject material = value.isJsonObject() ? object(value).deepCopy() : new JsonObject();
            String path = value.isJsonObject() ? string(material, "uv", "") : value.getAsString();
            if (!material.has("uv")) material.addProperty("uv", path);
            String id = stem(path); if (id.isBlank() || id.length() > 128 || !ids.add(id)) throw invalid("YSM 贴图名称重复或无效");
            byte[] png = assets.png(path);
            for (var entry : material.entrySet()) if (!entry.getKey().equals("uv") && entry.getValue().isJsonPrimitive()
                    && entry.getValue().getAsJsonPrimitive().isString() && NativeYsmImages.supportedPath(entry.getValue().getAsString()))
                assets.png(entry.getValue().getAsString());
            result.add(new YsmModelProfile.TextureChoice(id, path, png, material));
        }
        return List.copyOf(result);
    }

    private static YsmModelProfile.TextureChoice chooseTexture(List<YsmModelProfile.TextureChoice> textures,
                                                               String id, boolean strict) {
        for (var texture : textures) if (texture.id().equals(id) || texture.path().equals(id)) return texture;
        if (strict) throw invalid("YSM 皮肤不存在: " + id); return textures.getFirst();
    }

    private static JsonObject controllers(Assets assets, JsonElement paths) throws IOException {
        JsonObject result = new JsonObject(); if (paths == null) return result;
        JsonArray files = array(paths); if (files.size() > 32) throw invalid("YSM 控制器文件数量超出限制");
        for (JsonElement file : files) {
            JsonObject definitions = object(assets.json(file.getAsString()).get("animation_controllers"));
            for (var entry : definitions.entrySet()) {
                if (result.has(entry.getKey())) throw invalid("YSM 控制器名称重复");
                if (result.size() >= 64) throw invalid("YSM 控制器数量超出限制");
                result.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return result;
    }

    private static void subEntities(Assets assets, JsonElement source, String kind,
                                    List<YsmModelProfile.Component> target) throws IOException {
        if (source == null) return;
        List<Map.Entry<String, JsonObject>> entries = new ArrayList<>();
        if (source.isJsonArray()) {
            int index = 0; for (JsonElement value : array(source)) entries.add(Map.entry(kind + "_" + index++, object(value)));
        } else for (var entry : object(source).entrySet()) entries.add(Map.entry(entry.getKey(), object(entry.getValue())));
        if (entries.size() > 16) throw invalid("YSM 子模型数量超出限制");
        for (var entry : entries) {
            String id = entry.getKey(); JsonObject descriptor = entry.getValue();
            if (id.isBlank() || id.length() > 128) throw invalid("YSM 子模型名称无效");
            List<String> matches = new ArrayList<>(); JsonElement match = descriptor.get("match");
            if (match == null && source.isJsonObject()) matches.add(id);
            else if (match != null && match.isJsonArray()) array(match).forEach(value -> matches.add(value.getAsString()));
            else if (match != null) matches.add(match.getAsString());
            for (String identifier : matches) if (!identifier.matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw invalid("YSM 子模型实体标识无效");
            List<YsmModelProfile.TextureChoice> textures = textureChoices(assets, descriptor.get("texture"));
            var texture = textures.getFirst();
            Converter converter = new Converter(assets.json(string(descriptor, "model", "")), texture.png(), assets.mergeMultilineExpressions);
            if (descriptor.has("animation")) converter.animations(assets.json(string(descriptor, "animation", "")));
            JsonObject controllers = controllers(assets, descriptor.get("animation_controllers"));
            target.add(new YsmModelProfile.Component(id, kind, matches, textures, texture.id(),
                    assets.output(converter, controllers, kind), descriptor, assets.maximumBytes));
        }
    }

    private static final class Converter {
        final JsonObject output = new JsonObject();
        final JsonArray elements = new JsonArray(), clips = new JsonArray();
        final Map<String, JsonObject> nodes = new LinkedHashMap<>();
        final Map<String, String> parents = new LinkedHashMap<>();
        final Map<String, JsonObject> namedClips = new LinkedHashMap<>();
        final boolean mergeMultilineExpressions;
        int frames;
        Converter(JsonObject source, byte[] png, boolean mergeMultilineExpressions) {
            this.mergeMultilineExpressions = mergeMultilineExpressions;
            JsonArray geometries = array(source.get("minecraft:geometry"));
            if (geometries.isEmpty() || geometries.size() > 32) throw invalid("YSM 几何列表无效或过大");
            JsonObject geometry = object(geometries.get(0)), description = object(geometry.get("description"));
            double width = number(description, "texture_width", 16), height = number(description, "texture_height", 16);
            if (width < 1 || width > 8192 || height < 1 || height > 8192) throw invalid("贴图分辨率无效");
            JsonObject meta = new JsonObject(); meta.addProperty("format_version", "4.10"); meta.addProperty("model_format", "bedrock"); output.add("meta", meta);
            JsonObject resolution = new JsonObject(); resolution.addProperty("width", width); resolution.addProperty("height", height); output.add("resolution", resolution);
            JsonObject texture = new JsonObject(); texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            texture.addProperty("uv_width", width); texture.addProperty("uv_height", height);
            JsonArray textures = new JsonArray(); textures.add(texture); output.add("textures", textures);
            output.add("elements", elements); output.add("animations", clips);
            JsonArray bones = array(geometry.get("bones"));
            if (bones.isEmpty() || bones.size() > MAX_BONES) throw invalid("YSM 骨骼数量超出限制");
            for (JsonElement value : bones) {
                JsonObject bone = object(value); String name = string(bone, "name", "");
                if (name.isBlank() || name.length() > 128 || nodes.containsKey(name)) throw invalid("YSM 骨骼名称缺失或重复");
                JsonObject node = new JsonObject(); node.addProperty("uuid", id("bone:" + name)); node.addProperty("name", name);
                node.add("origin", numericVector(bone.get("pivot"), true, false, 0));
                node.add("rotation", numericVector(bone.get("rotation"), true, true, 0)); node.add("children", new JsonArray());
                if (bone.has("binding") || bone.has("poly_mesh") || bone.has("locators")) {
                    // Locators do not produce cube geometry; expression bindings and meshes need a different renderer.
                    if (bone.has("binding") || bone.has("poly_mesh")) throw invalid("YSM 骨骼绑定和网格暂不支持");
                }
                nodes.put(name, node); parents.put(name, string(bone, "parent", ""));
                JsonArray cubes = bone.has("cubes") ? array(bone.get("cubes")) : new JsonArray();
                for (JsonElement cube : cubes) cube(object(cube), bone, node);
                if (bone.has("ysm_baked_faces")) for (JsonElement face : array(bone.get("ysm_baked_faces"))) {
                    if (elements.size() >= MAX_CUBES * 6) throw invalid("YSM 烘焙面数量超出限制");
                    JsonObject baked = object(face).deepCopy(); String uuid = id("baked-face:" + elements.size());
                    baked.addProperty("uuid", uuid); baked.addProperty("type", "ysm_baked_face"); baked.addProperty("texture", 0);
                    elements.add(baked); node.getAsJsonArray("children").add(uuid);
                }
            }
            JsonArray roots = new JsonArray();
            for (var entry : nodes.entrySet()) {
                String parent = parents.get(entry.getKey());
                if (parent.isEmpty()) roots.add(entry.getValue());
                else {
                    JsonObject parentNode = nodes.get(parent);
                    if (parentNode == null) throw invalid("YSM 骨骼父级不存在: " + parent);
                    parentNode.getAsJsonArray("children").add(entry.getValue());
                }
            }
            Set<String> visited = new HashSet<>();
            for (JsonElement root : roots) validateTree(object(root), visited, 1);
            if (visited.size() != nodes.size()) throw invalid("YSM 骨骼存在循环引用");
            output.add("outliner", roots);
        }

        void validateTree(JsonObject node, Set<String> visited, int depth) {
            if (depth > 63 || !visited.add(node.get("uuid").getAsString())) throw invalid("YSM 骨骼层级无效或过深");
            for (JsonElement child : node.getAsJsonArray("children")) if (child.isJsonObject()) validateTree(object(child), visited, depth + 1);
        }

        void cube(JsonObject source, JsonObject bone, JsonObject node) {
            if (elements.size() >= MAX_CUBES) throw invalid("YSM 方块数量超出限制");
            double[] origin = vector(source.get("origin"), 0), size = vector(source.get("size"), 0);
            JsonObject cube = new JsonObject(); String uuid = id("cube:" + elements.size()); cube.addProperty("uuid", uuid);
            // OpenYSM bakes signed extents directly; sorting endpoints would change faces, UVs and winding.
            if (Arrays.stream(size).anyMatch(axis -> axis < 0)) cube.addProperty("ysm_signed_cube", true);
            cube.add("from", numbers(-origin[0] - size[0], origin[1], origin[2]));
            cube.add("to", numbers(-origin[0], origin[1] + size[1], origin[2] + size[2]));
            cube.add("origin", numericVector(source.get("pivot"), true, false, 0));
            cube.add("rotation", numericVector(source.get("rotation"), true, true, 0));
            cube.addProperty("inflate", number(source, "inflate", number(bone, "inflate", 0)));
            boolean mirror = source.has("mirror") ? source.get("mirror").getAsBoolean() : bone.has("mirror") && bone.get("mirror").getAsBoolean();
            JsonElement uv = source.get("uv"); JsonObject uvFaces;
            if (uv != null && uv.isJsonArray()) {
                double[] start = vector2(uv), dimensions = Arrays.stream(size).map(Math::floor).toArray();
                double u = start[0], v = start[1], x = dimensions[0], y = dimensions[1], z = dimensions[2];
                uvFaces = new JsonObject();
                uvFaces.add("north", uv(u + z, v + z, x, y)); uvFaces.add("south", uv(u + z + x + z, v + z, x, y));
                uvFaces.add("east", uv(u, v + z, z, y)); uvFaces.add("west", uv(u + z + x, v + z, z, y));
                uvFaces.add("up", uv(u + z, v, x, z)); uvFaces.add("down", uv(u + z + x, v + z, x, -z));
            } else uvFaces = object(uv);
            JsonObject faces = new JsonObject();
            for (String side : List.of("north", "south", "east", "west", "up", "down")) {
                String selected = mirror && side.equals("east") ? "west" : mirror && side.equals("west") ? "east" : side;
                if (!uvFaces.has(selected)) continue;
                JsonObject faceSource = object(uvFaces.get(selected)); double[] start = vector2(faceSource.get("uv")), extent = vector2(faceSource.get("uv_size"));
                double u0 = start[0], v0 = start[1], u1 = u0 + extent[0], v1 = v0 + extent[1];
                if (mirror) { double saved = u0; u0 = u1; u1 = saved; }
                if (side.equals("up") || side.equals("down")) { double saved = u0; u0 = u1; u1 = saved; saved = v0; v0 = v1; v1 = saved; }
                JsonObject face = new JsonObject(); face.add("uv", numbers(u0, v0, u1, v1)); face.addProperty("texture", 0);
                face.addProperty("rotation", number(faceSource, "uv_rotation", 0)); faces.add(side, face);
            }
            cube.add("faces", faces); elements.add(cube); node.getAsJsonArray("children").add(uuid);
        }

        void animations(JsonObject source) {
            JsonObject animations = object(source.get("animations"));
            if (namedClips.size() + animations.size() > 1024) throw invalid("YSM 动作数量超出限制");
            for (var entry : animations.entrySet()) {
                String name = entry.getKey(); JsonObject authored = object(entry.getValue());
                if (name.isBlank() || name.length() > 128 || namedClips.containsKey(name)) throw invalid("YSM 动作名缺失或重复");
                if (authored.has("anim_time_update") && authored.has("animation_time_update"))
                    throw invalid("YSM 动画时间更新标准字段与兼容别名不能同时定义");
                JsonObject clip = new JsonObject(); clip.addProperty("name", name); JsonObject animators = new JsonObject(); clip.add("animators", animators);
                clip.addProperty("ysm_primary", true);
                clip.addProperty("loop", animationLoop(authored.get("loop")));
                for (String field : List.of("anim_time_update", "animation_time_update", "start_delay", "loop_delay")) if (authored.has(field)) {
                    JsonElement value = authored.get(field);
                    if (!value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) throw invalid("YSM 动画时序必须是数值或表达式: " + field);
                    String expression = normalize(value.getAsString());
                    if (expression.isBlank()) throw invalid("YSM 动画时序表达式为空: " + field);
                    Molang.compileNativeYsm(expression);
                    clip.addProperty(field.equals("animation_time_update") ? "anim_time_update" : field, expression);
                }
                if (authored.has("blend_weight")) {
                    JsonElement value = authored.get("blend_weight");
                    if (!value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) throw invalid("YSM 动画权重必须是数值或表达式");
                    String expression = normalize(value.getAsString()); Molang.compileNativeYsm(expression); clip.addProperty("blend_weight", expression);
                }
                double length = number(authored, "animation_length", 0);
                JsonObject bones = authored.has("bones") ? object(authored.get("bones")) : new JsonObject();
                for (var bone : bones.entrySet()) {
                    JsonObject node = nodes.get(bone.getKey());
                    if (node == null) continue; // Reference packs contain tracks for optional bones absent from their main geometry.
                    JsonObject animator = new JsonObject(); animator.addProperty("name", bone.getKey()); animator.addProperty("type", "bone");
                    JsonArray keys = new JsonArray(); animator.add("keyframes", keys);
                    JsonObject channels = object(bone.getValue());
                    for (String channel : List.of("position", "rotation", "scale")) if (channels.has(channel)) {
                        JsonElement data = channels.get(channel);
                        if (data.isJsonObject()) for (var key : object(data).entrySet()) {
                            double time = Double.parseDouble(key.getKey());
                            keys.add(keyframe(channel, time, key.getValue()));
                        } else keys.add(keyframe(channel, 0, data));
                    }
                    if (!keys.isEmpty()) animators.add(node.get("uuid").getAsString(), animator);
                }
                if (authored.has("timeline")) {
                    JsonObject effect = new JsonObject(); effect.addProperty("type", "effect");
                    JsonArray keys = new JsonArray(); effect.add("keyframes", keys);
                    SortedMap<Double, JsonElement> ordered = new TreeMap<>();
                    for (var event : object(authored.get("timeline")).entrySet()) {
                        double time = Double.parseDouble(event.getKey());
                        if (!Double.isFinite(time) || time < 0 || time > MAX_ANIMATION_SECONDS || ordered.put(time, event.getValue()) != null)
                            throw invalid("YSM 时间轴时间无效或重复");
                    }
                    for (var event : ordered.entrySet()) {
                        if (++frames > MAX_FRAMES) throw invalid("YSM 关键帧数量超出限制");
                        JsonArray programs = event.getValue().isJsonArray() ? array(event.getValue()) : new JsonArray();
                        if (!event.getValue().isJsonArray()) programs.add(event.getValue());
                        if (programs.size() > BbModel.MAX_NATIVE_TIMELINE_PROGRAMS) throw invalid("YSM 时间轴脚本数量超出限制");
                        int programBytes = 0;
                        JsonObject key = new JsonObject(); key.addProperty("channel", "timeline"); key.addProperty("time", event.getKey());
                        JsonArray points = new JsonArray(); key.add("data_points", points);
                        // Validate original lines before joining; multiline blocks cannot be compiled line by line.
                        for (JsonElement program : programs) {
                            if (!program.isJsonPrimitive() || !program.getAsJsonPrimitive().isString()) throw invalid("YSM 时间轴脚本必须是文本");
                            if (program.getAsString().length() > 8192) throw invalid("YSM 表达式过长");
                        }
                        if (mergeMultilineExpressions) programs = NativeYsmScriptArrays.merge(programs, BbModel.MAX_NATIVE_TIMELINE_PROGRAMS);
                        for (JsonElement program : programs) {
                            if (!program.isJsonPrimitive() || !program.getAsJsonPrimitive().isString()) throw invalid("YSM 时间轴脚本必须是文本");
                            String expression = mergeMultilineExpressions ? completeTernaries(program.getAsString()) : normalize(program.getAsString());
                            if ((programBytes += expression.getBytes(StandardCharsets.UTF_8).length) > MAX_TIMELINE_BYTES)
                                throw invalid("YSM 单时间轴脚本总大小超过 32 KiB");
                            Molang.compileNativeYsm(expression);
                            JsonObject point = new JsonObject(); point.addProperty("script", expression); points.add(point);
                        }
                        keys.add(key);
                    }
                    animators.add("ysm_import_timeline", effect);
                }
                for (String field : List.of("sound_effects", "particle_effects")) if (authored.has(field)) {
                    JsonObject effect = new JsonObject(); effect.addProperty("type", "effect");
                    JsonArray keys = new JsonArray(); effect.add("keyframes", keys);
                    SortedMap<Double, JsonElement> ordered = new TreeMap<>();
                    for (var event : object(authored.get(field)).entrySet()) {
                        double time = Double.parseDouble(event.getKey());
                        if (!Double.isFinite(time) || time < 0 || time > MAX_ANIMATION_SECONDS || ordered.put(time, event.getValue()) != null)
                            throw invalid("YSM 特效时间无效或重复");
                    }
                    for (var event : ordered.entrySet()) {
                        if (++frames > MAX_FRAMES) throw invalid("YSM 关键帧数量超出限制");
                        JsonArray values = event.getValue().isJsonArray() ? array(event.getValue()) : new JsonArray();
                        if (!event.getValue().isJsonArray()) values.add(event.getValue());
                        if (values.size() > 32) throw invalid("YSM 特效数量超出限制");
                        JsonObject key = new JsonObject(); key.addProperty("channel", field.equals("sound_effects") ? "sound" : "particle");
                        key.addProperty("time", event.getKey()); JsonArray points = new JsonArray(); key.add("data_points", points);
                        for (JsonElement value : values) {
                            JsonObject point = object(value).deepCopy();
                            String identifier = string(point, "effect", "");
                            if (identifier.isBlank() || identifier.length() > 256) throw invalid("YSM 特效名称无效");
                            points.add(point);
                        }
                        keys.add(key);
                    }
                    animators.add("ysm_import_" + field, effect);
                }
                if (length < 0 || !Double.isFinite(length) || length > MAX_ANIMATION_SECONDS) throw invalid("YSM 动作时长无效");
                // The upstream loader preserves explicit duration, even when interpolation/event keys extend beyond it.
                // A missing animation_length means infinite duration, independently of the final key's time.
                if (!authored.has("animation_length")) clip.addProperty("ysm_infinite", true);
                clip.addProperty("length", length); namedClips.put(name, clip); clips.add(clip);
            }
        }

        String animationLoop(JsonElement value) {
            if (value == null) return "ONCE";
            if (!value.isJsonPrimitive()) throw invalid("YSM 动画循环类型无效");
            if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean() ? "LOOP" : "ONCE";
            return switch (value.getAsString().toLowerCase(Locale.ROOT)) {
                case "hold_on_last_frame", "hold" -> "HOLD";
                case "true", "loop" -> "LOOP";
                case "false", "once" -> "ONCE";
                default -> throw invalid("YSM 动画循环类型无效");
            };
        }

        JsonObject keyframe(String channel, double time, JsonElement source) {
            if (++frames > MAX_FRAMES || time < 0 || !Double.isFinite(time) || time > MAX_ANIMATION_SECONDS) throw invalid("YSM 关键帧超出限制");
            JsonObject frame = new JsonObject(); frame.addProperty("channel", channel); frame.addProperty("time", time); frame.addProperty("interpolation", "linear");
            JsonArray points = new JsonArray();
            if (source.isJsonObject()) {
                JsonObject key = object(source); JsonElement post = key.get("post");
                if (post == null) post = key.get("pre");
                if (post == null) throw invalid("YSM 关键帧缺少 pre/post");
                if (key.has("pre")) points.add(point(channel, key.get("pre")));
                points.add(point(channel, post)); frame.addProperty("interpolation", string(key, "lerp_mode", "linear"));
            } else points.add(point(channel, source));
            frame.add("data_points", points); return frame;
        }

        JsonObject point(String channel, JsonElement data) {
            JsonArray axes;
            if (data.isJsonArray()) { axes = array(data); if (axes.size() != 3) throw invalid("YSM 动画需要三维向量"); }
            else { axes = new JsonArray(); for (int i = 0; i < 3; i++) axes.add(data.deepCopy()); }
            JsonObject point = new JsonObject();
            for (int i = 0; i < 3; i++) {
                JsonElement axis = axes.get(i);
                if (!axis.isJsonPrimitive() || axis.getAsJsonPrimitive().isBoolean()) throw invalid("YSM 动画坐标无效");
                String expression = normalize(axis.getAsString()); Molang.compileNativeYsm(expression);
                point.addProperty(List.of("x", "y", "z").get(i), expression);
            }
            return point;
        }

        String normalize(String expression) {
            if (expression.length() > 8192) throw invalid("YSM 表达式过长");
            // Native typed Molang/resolvers own null defaults and stateful first/second-order calls.
            return completeTernaries(expression);
        }

    }

    private static String completeTernaries(String expression) {
        StringBuilder result = new StringBuilder(); Deque<Integer> pending = new ArrayDeque<>(); int depth = 0;
        char quote = 0; boolean escaped = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) {
                result.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; result.append(c); continue; }
            if (c == '?' && i + 1 < expression.length() && expression.charAt(i + 1) == '?') {
                result.append("??"); i++; continue;
            }
            if (c == ')' || c == ',' || c == ';') while (!pending.isEmpty() && pending.peek() == depth) { result.append(":0"); pending.pop(); }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '?') pending.push(depth);
            else if (c == ':' && !pending.isEmpty() && pending.peek() == depth) pending.pop();
            result.append(c);
        }
        while (!pending.isEmpty()) { result.append(":0"); pending.pop(); }
        return result.toString();
    }
    private static JsonObject object(JsonElement value) { if (value == null || !value.isJsonObject()) throw invalid("需要 JSON 对象"); return value.getAsJsonObject(); }
    private static JsonArray array(JsonElement value) { if (value == null || !value.isJsonArray()) throw invalid("需要 JSON 数组"); return value.getAsJsonArray(); }
    private static String string(JsonObject value, String key, String fallback) { if (!value.has(key)) return fallback; JsonElement item = value.get(key); if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw invalid("需要文本: " + key); return item.getAsString(); }
    private static double number(JsonObject value, String key, double fallback) { if (!value.has(key)) return fallback; double result = value.get(key).getAsDouble(); if (!Double.isFinite(result)) throw invalid("无效数值: " + key); return result; }
    // YsmJsonSupport.getBool deliberately uses Gson's boolean coercion, including authored string values.
    private static boolean bool(JsonObject value, String key, boolean fallback) { return value.has(key) ? value.get(key).getAsBoolean() : fallback; }
    private static double[] vector(JsonElement value, double fallback) { if (value == null) return new double[]{fallback, fallback, fallback}; JsonArray axes = array(value); if (axes.size() != 3) throw invalid("需要三维坐标"); double[] result = new double[3]; for (int i = 0; i < 3; i++) { result[i] = axes.get(i).getAsDouble(); if (!Double.isFinite(result[i])) throw invalid("坐标必须有限"); } return result; }
    private static double[] vector2(JsonElement value) { JsonArray axes = array(value); if (axes.size() != 2) throw invalid("需要二维 UV"); double[] result = {axes.get(0).getAsDouble(), axes.get(1).getAsDouble()}; for (double axis : result) if (!Double.isFinite(axis)) throw invalid("UV 必须有限"); return result; }
    private static JsonArray numericVector(JsonElement value, boolean invertX, boolean invertY, double fallback) { double[] axes = vector(value, fallback); return numbers(invertX ? -axes[0] : axes[0], invertY ? -axes[1] : axes[1], axes[2]); }
    private static JsonArray numbers(double... values) { JsonArray result = new JsonArray(); for (double value : values) result.add(value); return result; }
    private static JsonObject uv(double u, double v, double w, double h) { JsonObject result = new JsonObject(); result.add("uv", numbers(u, v)); result.add("uv_size", numbers(w, h)); return result; }
    private static String id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
