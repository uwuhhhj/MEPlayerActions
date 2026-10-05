package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.client.LocalModelLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class NativeModelBundleTest {
    @TempDir Path temporary;

    @Test void fullNativeBundleRetainsFormsSkinsComponentsAndLanguagesAcrossPrivateSync() throws Exception {
        var source = YsmFolderModel.bundledDefaultWithProfile(null);
        byte[] bundle = NativeModelBundle.encode("ysm", "ysm.json", source.sourceFiles());
        var decoded = NativeModelBundle.decode(bundle, "blue");
        assertEquals("blue", decoded.profile().selectedTexture());
        assertEquals(source.profile().extraAnimations(), decoded.profile().extraAnimations());
        assertEquals(source.profile().extraAnimationButtons(), decoded.profile().extraAnimationButtons());
        assertEquals(source.profile().languages(), decoded.profile().languages());
        assertEquals(source.profile().functions(), decoded.profile().functions());
        assertEquals(source.profile().components().size(), decoded.profile().components().size());
        assertEquals(source.profile().animationFiles(), decoded.profile().animationFiles());
        assertEquals(source.profile().soundResources().keySet(), decoded.profile().soundResources().keySet());
        assertNotEquals(com.simmc.meplayeractions.client.network.AssetTransfer.hash(source.raw()), decoded.hash());
        assertTrue(NativeModelBundle.validate(bundle).files().containsKey("textures/default.png"));
        assertArrayEquals(bundle, NativeModelBundle.encode("ysm", "ysm.json", source.sourceFiles()), "The wire hash is stable for the same complete source");
    }

    @Test void selectingSkinDoesNotChangeOrReencodeCachedSourceBundle() throws Exception {
        var library = new LocalModelLibrary(temporary);
        byte[] source = library.sourceBundle("openysm_default");
        var original = library.load("openysm_default", "default");
        var blue = library.load("openysm_default", "blue");
        assertNotEquals(original.hash(), blue.hash());
        assertArrayEquals(source, library.sourceBundle("openysm_default"));
        source[0]++;
        assertTrue(NativeModelBundle.isZip(library.sourceBundle("openysm_default")), "Caller mutation cannot poison the cached bundle");
    }

    @Test void manifestRequiresExactVersionUniqueKeysStringTypesAndStrictUtf8() throws Exception {
        for (String descriptor : java.util.List.of(
                "{\"format\":1.5,\"kind\":\"bbmodel\",\"entry\":\"model.bbmodel\"}",
                "{\"format\":\"1\",\"kind\":\"bbmodel\",\"entry\":\"model.bbmodel\"}",
                "{\"format\":1,\"kind\":\"ysm\",\"kind\":\"bbmodel\",\"entry\":\"model.bbmodel\"}",
                "{\"format\":1,\"kind\":false,\"entry\":\"model.bbmodel\"}",
                "{\"format\":1,\"kind\":\"bbmodel\",\"entry\":\"model.bbmodel\"}{}")) {
            assertThrows(IOException.class, () -> NativeModelBundle.validate(zip(Map.of("manifest.json", descriptor.getBytes(StandardCharsets.UTF_8), "model.bbmodel", new byte[]{1}))), descriptor);
        }
        assertThrows(IOException.class, () -> NativeModelBundle.validate(zip(Map.of("manifest.json", new byte[]{(byte)0xc0,(byte)0xaf}, "model.bbmodel", new byte[]{1}))));
    }

    @Test void pathCollisionsDirectoryPayloadOpaqueBinaryAndExpandedBombAreRejected() throws Exception {
        for (Map<String, byte[]> files : java.util.List.of(
                Map.of("../ysm.json", new byte[]{1}), Map.of("YSM.json", new byte[]{1}, "ysm.json", new byte[]{2}),
                Map.of("folder/", new byte[]{1}), Map.of("model.ysm", new byte[]{1}),
                Map.of("large.json", new byte[8 * 1024 * 1024 + 1]))) {
            assertThrows(IOException.class, () -> NativeModelBundle.archiveFiles(zip(files), true));
        }
    }

    @Test void manualZipRecognizesOneNestedModelRootWithoutChangingItsReferences() throws Exception {
        var source = YsmFolderModel.bundledDefaultWithProfile(null); Map<String, byte[]> files = new LinkedHashMap<>();
        source.sourceFiles().forEach((path, value) -> files.put("pack/model/" + path, value));
        var decoded = YsmFolderModel.readArchiveWithProfile(zip(files), "blue");
        assertEquals("blue", decoded.profile().selectedTexture());
        assertEquals(source.profile().components().size(), decoded.profile().components().size());
        assertTrue(decoded.sourceFiles().containsKey("ysm.json"));
    }

    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey()); entry.setTime(0); zip.putNextEntry(entry); zip.write(file.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
