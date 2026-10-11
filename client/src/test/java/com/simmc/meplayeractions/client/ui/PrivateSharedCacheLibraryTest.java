package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.model.NativeModelBundle;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrivateSharedCacheLibraryTest {
    @TempDir Path temporary;

    @Test void existingZipBundleUsesNativeSkinDecoderWithoutWritingOrRefreshingItsTimestamp() throws Exception {
        var source=YsmFolderModel.bundledDefaultWithProfile(null);
        byte[] bytes=NativeModelBundle.encode("ysm","ysm.json",source.sourceFiles());String hash=AssetTransfer.hash(bytes);
        Path directory=Files.createDirectory(temporary.resolve("private-models"));Path file=directory.resolve(hash+".bbmodel");
        Files.write(file,bytes);Files.setLastModifiedTime(file,FileTime.fromMillis(123456));
        FileTime before=Files.getLastModifiedTime(file);
        try(var library=new PrivateSharedCacheLibrary(directory)) {
            var entries=library.scan().get(20,TimeUnit.SECONDS);
            assertEquals(List.of(hash),entries.stream().map(PrivateSharedCacheLibrary.Entry::hash).toList());
            var preview=library.preview(hash,"blue").get(20,TimeUnit.SECONDS);
            assertEquals("blue",preview.profile().selectedTexture());
            assertTrue(preview.model().hasBone("Head"));
        }
        assertEquals(before,Files.getLastModifiedTime(file));assertArrayEquals(bytes,Files.readAllBytes(file));
        try(var files=Files.list(directory)){assertEquals(List.of(file),files.toList());}
    }

    @Test void corruptedAndNonBundleResourcesRemainOnDiskAndNeverBecomePreviewModels() throws Exception {
        Path directory=Files.createDirectory(temporary.resolve("private-models"));
        byte[] validNameSource="expected original source".getBytes(StandardCharsets.UTF_8);String hash=AssetTransfer.hash(validNameSource);
        Path corrupted=Files.writeString(directory.resolve(hash+".bbmodel"),"corrupt");
        byte[] json="{\"elements\":[],\"outliner\":[]}".getBytes(StandardCharsets.UTF_8);String jsonHash=AssetTransfer.hash(json);
        Path ordinary=Files.write(directory.resolve(jsonHash+".bbmodel"),json);
        try(var library=new PrivateSharedCacheLibrary(directory)) {
            assertThrows(ExecutionException.class,()->library.preview(hash,"").get(20,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,()->library.preview(jsonHash,"").get(20,TimeUnit.SECONDS));
        }
        assertEquals("corrupt",Files.readString(corrupted));assertArrayEquals(json,Files.readAllBytes(ordinary));
    }

    @Test void missingCacheInspectionDoesNotCreateDirectories() throws Exception {
        Path directory=temporary.resolve("missing");
        try(var library=new PrivateSharedCacheLibrary(directory)) {
            assertTrue(library.scan().get(20,TimeUnit.SECONDS).isEmpty());
            assertThrows(ExecutionException.class,()->library.preview("0".repeat(64),"").get(20,TimeUnit.SECONDS));
        }
        assertFalse(Files.exists(directory));
    }
}
