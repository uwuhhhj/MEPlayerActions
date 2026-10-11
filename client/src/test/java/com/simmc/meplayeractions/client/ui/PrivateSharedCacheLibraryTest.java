package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.NativeModelBundle;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.simmc.meplayeractions.client.network.AssetTransfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PrivateSharedCacheLibraryTest
{
    @TempDir
    Path temporary;

    @Test
    void existingZipBundleUsesNativeSkinDecoderWithoutWritingOrRefreshingItsTimestamp() throws Exception
    {
        YsmFolderModel.Imported source = YsmFolderModel.bundledDefaultWithProfile(null);
        byte[] bytes = NativeModelBundle.encode("ysm", "ysm.json", source.sourceFiles());
        String hash = AssetTransfer.hash(bytes);
        Path directory = Files.createDirectory(this.temporary.resolve("private-models"));
        Path file = directory.resolve(hash + ".bbmodel");
        Files.write(file, bytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(123456));
        FileTime before = Files.getLastModifiedTime(file);
        try (PrivateSharedCacheLibrary library = new PrivateSharedCacheLibrary(directory))
        {
            List<PrivateSharedCacheLibrary.Entry> entries = library.scan().get(20, TimeUnit.SECONDS);
            assertEquals(List.of(hash), entries.stream().map(PrivateSharedCacheLibrary.Entry::hash).toList());
            LocalModelLibrary.Loaded preview = library.preview(hash, "blue").get(20, TimeUnit.SECONDS);
            assertEquals("blue", preview.profile().selectedTexture());
            assertTrue(preview.model().hasBone("Head"));
        }
        assertEquals(before, Files.getLastModifiedTime(file));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        try (Stream<Path> files = Files.list(directory))
        {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void corruptedAndNonBundleResourcesRemainOnDiskAndNeverBecomePreviewModels() throws Exception
    {
        Path directory = Files.createDirectory(this.temporary.resolve("private-models"));
        byte[] validNameSource = "expected original source".getBytes(StandardCharsets.UTF_8);
        String hash = AssetTransfer.hash(validNameSource);
        Path corrupted = Files.writeString(directory.resolve(hash + ".bbmodel"), "corrupt");
        byte[] json = "{\"elements\":[],\"outliner\":[]}".getBytes(StandardCharsets.UTF_8);
        String jsonHash = AssetTransfer.hash(json);
        Path ordinary = Files.write(directory.resolve(jsonHash + ".bbmodel"), json);
        try (PrivateSharedCacheLibrary library = new PrivateSharedCacheLibrary(directory))
        {
            assertThrows(ExecutionException.class, () -> library.preview(hash, "").get(20, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> library.preview(jsonHash, "").get(20, TimeUnit.SECONDS));
        }
        assertEquals("corrupt", Files.readString(corrupted));
        assertArrayEquals(json, Files.readAllBytes(ordinary));
    }

    @Test
    void missingCacheInspectionDoesNotCreateDirectories() throws Exception
    {
        Path directory = this.temporary.resolve("missing");
        try (PrivateSharedCacheLibrary library = new PrivateSharedCacheLibrary(directory))
        {
            assertTrue(library.scan().get(20, TimeUnit.SECONDS).isEmpty());
            assertThrows(ExecutionException.class, () -> library.preview("0".repeat(64), "").get(20, TimeUnit.SECONDS));
        }
        assertFalse(Files.exists(directory));
    }
}
