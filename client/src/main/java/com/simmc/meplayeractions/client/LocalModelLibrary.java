package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bundled models and single files in the dedicated model directory share the same bounded parser. */
public final class LocalModelLibrary {
    public record Entry(String id, String label) { }
    public record Loaded(String hash, BbModel model) { }
    private final Path directory;

    public LocalModelLibrary(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public Path directory() { return directory; }

    public List<Entry> models() throws IOException {
        List<Entry> models = new ArrayList<>();
        models.add(new Entry("ysm_01_jk", "银灰蓝眼 · 01"));
        models.add(new Entry("ysm_02_jk", "酒狐 · 02"));
        if (!Files.exists(directory)) Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("本地模型目录不能是符号链接");
        try (var files = Files.list(directory)) {
            files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> LocalAppearanceSettings.isValidModelId("local:" + name))
                    .sorted(Comparator.naturalOrder()).limit(128)
                    .forEach(name -> models.add(new Entry("local:" + name, "本地 · " + name.substring(0, name.length() - 8))));
        }
        return List.copyOf(models);
    }

    public Loaded load(String id) throws IOException {
        if (!LocalAppearanceSettings.isValidModelId(id)) throw new IOException("无效的本地模型选择");
        byte[] raw;
        if (id.startsWith("local:")) {
            if (Files.isSymbolicLink(directory)) throw new IOException("本地模型目录不能是符号链接");
            Path file = directory.resolve(id.substring(6)).normalize();
            if (!file.getParent().equals(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().getParent().equals(directory.toRealPath()))
                throw new IOException("模型必须是本地模型目录内的普通 .bbmodel 文件");
            if (Files.size(file) > AssetTransfer.MAX_RAW) throw new IOException("模型大小不能超过 8 MiB");
            try (InputStream input = Files.newInputStream(file)) { raw = read(input); }
        } else {
            try (InputStream input = LocalModelLibrary.class.getResourceAsStream("/assets/meplayeractions/models/" + id + ".bbmodel")) {
                if (input == null) throw new IOException("未找到内置模型");
                raw = read(input);
            }
        }
        return new Loaded(AssetTransfer.hash(raw), BbModel.parse(raw));
    }

    private static byte[] read(InputStream input) throws IOException {
        byte[] raw = input.readNBytes(AssetTransfer.MAX_RAW + 1);
        if (raw.length == 0 || raw.length > AssetTransfer.MAX_RAW) throw new IOException("模型大小必须在 1 字节到 8 MiB 之间");
        return raw;
    }
}
