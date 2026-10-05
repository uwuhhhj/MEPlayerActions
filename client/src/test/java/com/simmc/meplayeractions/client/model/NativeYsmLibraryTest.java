package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeYsmLibraryTest {
    @TempDir Path temporary;

    @Test void recursiveNativePacksAndArchivesKeepRealLogicalDirectoryIds() throws Exception {
        var source = YsmFolderModel.bundledDefaultWithProfile(null); Path folder = temporary.resolve("pack/sub/default");
        for (var file : source.sourceFiles().entrySet()) { Path path = folder.resolve(file.getKey()); Files.createDirectories(path.getParent()); Files.write(path, file.getValue()); }
        Path archive = temporary.resolve("pack/sub/archived.zip"); Files.write(archive, NativeModelBundleTest.zip(source.sourceFiles()));
        var library = new LocalModelLibrary(temporary); List<String> ids = library.models().stream().map(LocalModelLibrary.Entry::id).toList();
        assertTrue(ids.contains("ysm:pack/sub/default")); assertTrue(ids.contains("ysm:pack/sub/archived.zip"));
        assertFalse(ids.stream().anyMatch(id -> id.contains("/models/")), "Model asset directories are not separate library entries");
        assertEquals(library.load("ysm:pack/sub/default").model().cubeCount(), library.load("ysm:pack/sub/archived.zip").model().cubeCount());
        assertEquals("blue", NativeModelBundle.decode(library.sourceBundle("ysm:pack/sub/default"), "blue").profile().selectedTexture());
    }

    @Test void alexAndSteveUseExactOriginalCc0ResourcesAndTrustedLogicalIds() throws Exception {
        for (String id : List.of(BuiltinYsmModels.ALEX_ID, BuiltinYsmModels.STEVE_ID)) {
            var imported = YsmFolderModel.bundledWithProfile(id, null);
            String expectedManifestHash = id.equals(BuiltinYsmModels.ALEX_ID)
                    ? "3e7a6270c4a2973b900f0509f199444fe78052f414c27d2f6d4f33b1176b626d"
                    : "82140d02f22ad772cedea0caf273cf7b58d5643b74a7f22746fbef186303164d";
            assertEquals(expectedManifestHash, com.simmc.meplayeractions.client.network.AssetTransfer.hash(imported.sourceFiles().get("ysm.json")),
                    "The exact pinned OpenYSM CC0 manifest is retained without rewriting");
            assertEquals("CC 0", imported.profile().metadata().getAsJsonObject("license").get("type").getAsString());
            assertEquals(1, imported.profile().properties().get("height_scale").getAsDouble());
            assertFalse(imported.profile().components().isEmpty()); assertFalse(BbModel.parse(imported.raw()).animations().isEmpty());
            assertTrue(imported.profile().animationFiles().containsKey("tac"), "The original deferred animation file travels in the source bundle");
        }
        assertEquals("misc/1_alex", BuiltinYsmModels.logicalSourceId(BuiltinYsmModels.ALEX_ID));
        assertEquals("misc/2_steve", BuiltinYsmModels.logicalSourceId(BuiltinYsmModels.STEVE_ID));
        assertEquals("ysm:untrusted/1_alex", BuiltinYsmModels.logicalSourceId("ysm:untrusted/1_alex"));
    }

    @Test void nestedNativeLanguagesSupportArraysFlatOverridesAndLocaleFallback() throws Exception {
        var source = YsmFolderModel.bundledDefaultWithProfile(null); var files = new LinkedHashMap<>(source.sourceFiles());
        files.put("lang/zh_cn.json", "{\"metadata\":{\"name\":\"本地模型\"},\"properties\":{\"extra_animation_buttons\":{\"face\":{\"config_forms\":[{\"labels\":[\"关\",\"开\"]}]}}},\"metadata.name\":\"平铺优先\"}".getBytes(StandardCharsets.UTF_8));
        files.put("lang/en_us.json", "{\"metadata\":{\"tips\":\"English fallback\"}}".getBytes(StandardCharsets.UTF_8));
        var profile = YsmFolderModel.readMemoryWithProfile(files, null).profile();
        assertEquals("平铺优先", profile.localized("zh_cn", "metadata.name", "missing"));
        assertEquals("开", profile.localized("zh_cn", "properties.extra_animation_buttons.face.config_forms.0.labels.1", "missing"));
        assertEquals("English fallback", profile.localized("zh_cn", "metadata.tips", "missing"));
        assertEquals("missing", profile.localized("zh_cn", "properties.extra_animation_buttons.face.config_forms.9.labels.1", "missing"));
    }
}
