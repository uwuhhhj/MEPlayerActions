package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.BuiltinYsmModels;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.model.NativeModelBundle;
import com.simmc.meplayeractions.client.model.NativeBbModel;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Private models use one bounded renderer; server-distributed models are not bundled client choices. */
public final class LocalModelLibrary {
    public record Entry(String id, String label) { }
    public record Loaded(String hash, BbModel model, String previewAnimation, YsmModelProfile profile) {
        public Loaded(String hash, BbModel model) { this(hash, model, "", YsmModelProfile.empty()); }
        public Loaded(String hash, BbModel model, String previewAnimation) { this(hash, model, previewAnimation, YsmModelProfile.empty()); }
    }
    private final Path directory;
    private final Map<String, byte[]> sourceBundles = new LinkedHashMap<>();
    private final Map<String, String> sourceBundleErrors = new LinkedHashMap<>();

    public LocalModelLibrary(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public Path directory() { return directory; }

    public List<Entry> models() throws IOException {
        List<Entry> models = new ArrayList<>();
        for (var builtin : BuiltinYsmModels.models()) models.add(new Entry(builtin.id(), builtin.label()));
        if (!Files.exists(directory)) Files.createDirectories(directory);
        checkDirectory();
        collectModels(directory, models, new int[]{0}, 0);
        return List.copyOf(models);
    }

    private void collectModels(Path folder, List<Entry> models, int[] scanned, int depth) throws IOException {
        if (depth > 8 || models.size() >= BuiltinYsmModels.models().size() + 128 || scanned[0] >= 512) return;
        try (var children = Files.list(folder)) {
            for (Path file : children.limit(513 - scanned[0]).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                if (++scanned[0] > 512 || models.size() >= BuiltinYsmModels.models().size() + 128) return;
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) continue;
                String relative = directory.relativize(file).toString().replace('\\', '/'), name = file.getFileName().toString();
                if (attributes.isRegularFile() && depth == 0 && LocalAppearanceSettings.isValidModelId("local:" + name))
                    models.add(new Entry("local:" + name, "本地 · " + name.substring(0, name.length() - 8)));
                else if (attributes.isDirectory() && LocalAppearanceSettings.isValidModelId("ysm:" + relative)) {
                    boolean model = Files.isRegularFile(file.resolve("ysm.json"), LinkOption.NOFOLLOW_LINKS)
                            || Files.isRegularFile(file.resolve("main.json"), LinkOption.NOFOLLOW_LINKS)
                            && Files.isRegularFile(file.resolve("arm.json"), LinkOption.NOFOLLOW_LINKS);
                    if (model) models.add(new Entry("ysm:" + relative, "YSM · " + name));
                    else collectModels(file, models, scanned, depth + 1);
                } else if (attributes.isRegularFile() && LocalAppearanceSettings.isValidModelId("ysm:" + relative)
                        && (name.toLowerCase(java.util.Locale.ROOT).endsWith(".ysm") || name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")))
                    models.add(new Entry("ysm:" + relative, "YSM · " + name));
            }
        }
    }

    public Loaded load(String id) throws IOException {
        return load(id, false);
    }

    public Loaded load(String id, boolean alternateDefaultTexture) throws IOException {
        return load(id, alternateDefaultTexture && "openysm_default".equals(id) ? "blue" : null);
    }

    public Loaded load(String id, String textureId) throws IOException {
        if (!LocalAppearanceSettings.isValidModelId(id)) throw new IOException("无效的本地模型选择");
        byte[] raw;
        String previewAnimation = "";
        YsmModelProfile profile = YsmModelProfile.empty();
        if (id.startsWith("local:")) {
            checkDirectory();
            Path file = directory.resolve(id.substring(6)).normalize();
            if (!file.getParent().equals(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().getParent().equals(directory.toRealPath()))
                throw new IOException("模型必须是本地模型目录内的普通 .bbmodel 文件");
            if (Files.size(file) > AssetTransfer.MAX_RAW) throw new IOException("模型大小不能超过 8 MiB");
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { raw = read(input); }
            Map<String, byte[]> files = NativeBbModel.localSourceFiles(file, raw);
            var imported = NativeBbModel.read(raw, files, textureId);
            if (!hasSource(id)) rememberFiles(id, "bbmodel", "model.bbmodel", files);
            return new Loaded(AssetTransfer.hash(imported.raw()), BbModel.parseLocal(imported.raw()),
                    imported.previewAnimation(), imported.profile());
        } else if (id.startsWith("ysm:")) {
            checkDirectory();
            var imported = YsmFolderModel.readWithProfile(directory.resolve(id.substring(4)), textureId);
            raw = imported.raw(); previewAnimation = imported.previewAnimation(); profile = imported.profile();
            rememberImported(id, imported);
        } else {
            var imported = YsmFolderModel.bundledWithProfile(id, textureId);
            raw = imported.raw(); previewAnimation = imported.previewAnimation(); profile = imported.profile();
            rememberImported(id, imported);
        }
        return new Loaded(AssetTransfer.hash(raw), BbModel.parseLocal(raw), previewAnimation, profile);
    }

    /** Skin and variable snapshots stay outside this stable full-asset identity. */
    public synchronized byte[] sourceBundle(String id) throws IOException {
        byte[] cached = sourceBundles.get(id);
        if (cached == null && sourceBundleErrors.containsKey(id)) throw new IOException(sourceBundleErrors.get(id));
        if (cached == null) { load(id); cached = sourceBundles.get(id); }
        if (cached == null && sourceBundleErrors.containsKey(id)) throw new IOException(sourceBundleErrors.get(id));
        if (cached == null) throw new IOException("模型完整原始资产不可用");
        return cached.clone();
    }

    /** Explicit library reload can invalidate source identity; selecting a skin does not. */
    public synchronized void clearSourceBundleCache() { sourceBundles.clear(); sourceBundleErrors.clear(); }
    private synchronized boolean hasSource(String id) { return sourceBundles.containsKey(id) || sourceBundleErrors.containsKey(id); }
    private void rememberImported(String id, YsmFolderModel.Imported imported) throws IOException {
        if (!hasSource(id)) rememberFiles(id, "ysm", "ysm.json", imported.sourceFiles());
    }

    private void rememberFiles(String id, String kind, String entry, Map<String, byte[]> files) throws IOException {
        try { rememberSource(id, NativeModelBundle.encode(kind, entry, files)); }
        catch (NativeModelBundle.NetworkBudgetExceededException limit) {
            synchronized (this) {
                sourceBundleErrors.put(id, limit.getMessage());
                while (sourceBundleErrors.size() > 16) sourceBundleErrors.remove(sourceBundleErrors.keySet().iterator().next());
            }
        }
    }

    private synchronized void rememberSource(String id, byte[] bytes) {
        sourceBundleErrors.remove(id);
        // Cache transfer bytes across texture/profile changes; explicit reload is a separate operation.
        byte[] previous = sourceBundles.get(id);
        if (previous == null || !java.util.Arrays.equals(previous, bytes)) sourceBundles.put(id, bytes);
        while (sourceBundles.size() > 16) sourceBundles.remove(sourceBundles.keySet().iterator().next());
    }

    private void checkDirectory() throws IOException {
        for (Path ancestor = directory; ancestor != null; ancestor = ancestor.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                    || !ancestor.toRealPath().equals(ancestor.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("本地模型目录及其上级必须是普通目录，不能包含链接");
        }
    }

    private static byte[] read(InputStream input) throws IOException {
        byte[] raw = input.readNBytes(AssetTransfer.MAX_RAW + 1);
        if (raw.length == 0 || raw.length > AssetTransfer.MAX_RAW) throw new IOException("模型大小必须在 1 字节到 8 MiB 之间");
        return raw;
    }
}
