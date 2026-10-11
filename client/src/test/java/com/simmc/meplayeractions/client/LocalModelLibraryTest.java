package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.model.BuiltinYsmModels;
import com.simmc.meplayeractions.client.model.YsmFolderFixtures;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.simmc.meplayeractions.client.network.AssetTransfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LocalModelLibraryTest
{
    @TempDir
    Path temporary;

    @Test
    void modelPickerIncludesBundledAndSafeFilesAndLoadsTheSelectedFile() throws Exception
    {
        Path directory = this.temporary.resolve("models");
        LocalModelLibrary library = new LocalModelLibrary(directory);
        assertEquals(BuiltinYsmModels.ids(), library.models().stream().map(LocalModelLibrary.Entry::id).toList());
        assertNull(LocalModelLibrary.class.getResourceAsStream("/assets/meplayeractions/models/ysm_01_jk.bbmodel"));
        assertNull(LocalModelLibrary.class.getResourceAsStream("/assets/meplayeractions/models/ysm_02_jk.bbmodel"));
        byte[] model = YsmFolderModel.bundledDefault();
        Files.write(directory.resolve("我的模型.bbmodel"), model);
        Files.writeString(directory.resolve("notes.txt"), "not a model");
        Files.createDirectory(directory.resolve("nested.bbmodel"));
        assertEquals(Stream.concat(BuiltinYsmModels.ids().stream(), Stream.of("local:我的模型.bbmodel")).toList(),
                library.models().stream().map(LocalModelLibrary.Entry::id).toList());
        LocalModelLibrary.Loaded loaded = library.load("local:我的模型.bbmodel");
        assertEquals(AssetTransfer.hash(model), loaded.hash());
        assertTrue(loaded.model().cubeCount() > 0);
        assertEquals(loaded.hash(), library.load("openysm_default").hash());
    }

    @Test
    void selectionCannotReadAnExternalPathOrADirectoryAsAModel() throws Exception
    {
        Path directory = Files.createDirectory(this.temporary.resolve("models"));
        Files.writeString(this.temporary.resolve("outside.bbmodel"), "outside the model library");
        Files.createDirectory(directory.resolve("folder.bbmodel"));
        LocalModelLibrary library = new LocalModelLibrary(directory);
        for (String id : List.of("local:../outside.bbmodel", "local:..\\outside.bbmodel",
                "local:" + this.temporary.resolve("outside.bbmodel"), "local:missing.bbmodel", "local:folder.bbmodel",
                "unknown", "ysm_01_jk", "ysm_02_jk", "ysm:../outside", "ysm:folder.bbmodel"))
        {
            assertThrows(IOException.class, () -> library.load(id), id);
        }
    }

    @Test
    void pickerSupportsSafeYsmFoldersAndTheirRealPlayerAnimations() throws Exception
    {
        Path directory = Files.createDirectory(this.temporary.resolve("models"));
        Path folder = directory.resolve("我的 YSM 模型");
        YsmFolderFixtures.copyDefault(folder);
        LocalModelLibrary library = new LocalModelLibrary(directory);
        assertEquals(Stream.concat(BuiltinYsmModels.ids().stream(), Stream.of("ysm:我的 YSM 模型")).toList(),
                library.models().stream().map(LocalModelLibrary.Entry::id).toList());
        LocalModelLibrary.Loaded bundled = library.load("openysm_default");
        LocalModelLibrary.Loaded local = library.load("ysm:我的 YSM 模型");
        assertEquals(bundled.hash(), local.hash());
        assertEquals(172, local.model().cubeCount());
        assertTrue(local.model().animations().containsAll(List.of("idle", "walk", "run", "swim", "extra1", "extra7")));
    }

    @Test
    void alternateDefaultSkinChangesPngWithoutChangingThePlayerGeometryOrAnimations() throws Exception
    {
        LocalModelLibrary library = new LocalModelLibrary(Files.createDirectory(this.temporary.resolve("models")));
        LocalModelLibrary.Loaded original = library.load("openysm_default");
        LocalModelLibrary.Loaded blue = library.load("openysm_default", true);
        assertNotEquals(original.hash(), blue.hash());
        assertEquals(original.model().cubeCount(), blue.model().cubeCount());
        assertEquals(original.model().animations(), blue.model().animations());
        assertEquals(original.model().sample(7, List.of()), blue.model().sample(7, List.of()));
        assertFalse(
                Arrays.equals(original.model().textures().getFirst().png(), blue.model().textures().getFirst().png()));
    }

    @Test
    void arbitraryYsmSkinIsSelectedByItsOwnProfileRatherThanTheLegacyDefaultToggle() throws Exception
    {
        Path directory = Files.createDirectory(this.temporary.resolve("models"));
        YsmFolderFixtures.copyDefault(directory.resolve("author"));
        LocalModelLibrary library = new LocalModelLibrary(directory);
        LocalModelLibrary.Loaded original = library.load("ysm:author");
        LocalModelLibrary.Loaded blue = library.load("ysm:author", "blue");
        assertTrue(original.profile().isYsm());
        assertEquals("default", original.profile().selectedTexture());
        assertEquals("blue", blue.profile().selectedTexture());
        assertNotEquals(original.hash(), blue.hash());
        assertEquals(List.of("default", "blue"),
                blue.profile().textures().stream().map(texture -> texture.id()).toList());
        assertThrows(IOException.class, () -> library.load("ysm:author", "../outside.png"));
        assertThrows(IOException.class, () -> library.load("ysm:author", "unknown"));
        assertEquals(blue.hash(), library.load("openysm_default", true).hash());
    }
}
