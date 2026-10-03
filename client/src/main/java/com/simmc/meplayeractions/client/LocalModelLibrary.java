package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
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

/** Private models use one bounded renderer; server-distributed models are not bundled client choices. */
public final class LocalModelLibrary {
    public record Entry(String id, String label) { }
    public record Loaded(String hash, BbModel model) { }
    private final Path directory;

    public LocalModelLibrary(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public Path directory() { return directory; }

    public List<Entry> models() throws IOException {
        List<Entry> models = new ArrayList<>();
        models.add(new Entry("openysm_default", "默认模型 · OpenYSM"));
        if (!Files.exists(directory)) Files.createDirectories(directory);
        checkDirectory();
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted(Comparator.comparing(path -> path.getFileName().toString())).limit(128).toList()) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && LocalAppearanceSettings.isValidModelId("local:" + name))
                    models.add(new Entry("local:" + name, "本地 · " + name.substring(0, name.length() - 8)));
                else if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) && LocalAppearanceSettings.isValidModelId("ysm:" + name)
                        && Files.isRegularFile(file.resolve("ysm.json"), LinkOption.NOFOLLOW_LINKS))
                    models.add(new Entry("ysm:" + name, "YSM · " + name));
            }
        }
        return List.copyOf(models);
    }

    public Loaded load(String id) throws IOException {
        return load(id, false);
    }

    public Loaded load(String id, boolean alternateDefaultTexture) throws IOException {
        if (!LocalAppearanceSettings.isValidModelId(id)) throw new IOException("无效的本地模型选择");
        byte[] raw;
        if (id.startsWith("local:")) {
            checkDirectory();
            Path file = directory.resolve(id.substring(6)).normalize();
            if (!file.getParent().equals(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().getParent().equals(directory.toRealPath()))
                throw new IOException("模型必须是本地模型目录内的普通 .bbmodel 文件");
            if (Files.size(file) > AssetTransfer.MAX_RAW) throw new IOException("模型大小不能超过 8 MiB");
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { raw = read(input); }
        } else if (id.startsWith("ysm:")) {
            checkDirectory();
            raw = YsmFolderModel.read(directory.resolve(id.substring(4)));
        } else {
            raw = YsmFolderModel.bundledDefault(alternateDefaultTexture);
        }
        return new Loaded(AssetTransfer.hash(raw), BbModel.parse(raw));
    }

    private void checkDirectory() throws IOException {
        for (Path ancestor = directory; ancestor != null; ancestor = ancestor.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("本地模型目录及其上级必须是普通目录，不能包含链接");
        }
    }

    private static byte[] read(InputStream input) throws IOException {
        byte[] raw = input.readNBytes(AssetTransfer.MAX_RAW + 1);
        if (raw.length == 0 || raw.length > AssetTransfer.MAX_RAW) throw new IOException("模型大小必须在 1 字节到 8 MiB 之间");
        return raw;
    }
}
