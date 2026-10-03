package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.network.AssetTransfer;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalModelLibraryTest {
    @TempDir Path temporary;

    @Test void modelPickerIncludesBundledAndSafeFilesAndLoadsTheSelectedFile() throws Exception {
        Path directory=temporary.resolve("models");var library=new LocalModelLibrary(directory);
        assertEquals(List.of("openysm_default"),library.models().stream().map(LocalModelLibrary.Entry::id).toList());
        assertNull(LocalModelLibrary.class.getResourceAsStream("/assets/meplayeractions/models/ysm_01_jk.bbmodel"));
        assertNull(LocalModelLibrary.class.getResourceAsStream("/assets/meplayeractions/models/ysm_02_jk.bbmodel"));
        byte[] model=YsmFolderModel.bundledDefault();
        Files.write(directory.resolve("我的模型.bbmodel"),model);
        Files.writeString(directory.resolve("notes.txt"),"not a model");
        Files.createDirectory(directory.resolve("nested.bbmodel"));
        assertEquals(List.of("openysm_default","local:我的模型.bbmodel"),library.models().stream().map(LocalModelLibrary.Entry::id).toList());
        var loaded=library.load("local:我的模型.bbmodel");
        assertEquals(AssetTransfer.hash(model),loaded.hash());assertTrue(loaded.model().cubeCount()>0);
        assertEquals(loaded.hash(),library.load("openysm_default").hash());
    }

    @Test void selectionCannotReadAnExternalPathOrADirectoryAsAModel() throws Exception {
        Path directory=Files.createDirectory(temporary.resolve("models"));
        Files.writeString(temporary.resolve("outside.bbmodel"),"outside the model library");
        Files.createDirectory(directory.resolve("folder.bbmodel"));var library=new LocalModelLibrary(directory);
        for(String id:List.of("local:../outside.bbmodel","local:..\\outside.bbmodel","local:"+temporary.resolve("outside.bbmodel"),
                "local:missing.bbmodel","local:folder.bbmodel","unknown","ysm_01_jk","ysm_02_jk",
                "ysm:../outside","ysm:folder.bbmodel")) {
            assertThrows(IOException.class,()->library.load(id),id);
        }
    }

    @Test void pickerSupportsSafeYsmFoldersAndTheirRealPlayerAnimations() throws Exception {
        Path directory=Files.createDirectory(temporary.resolve("models")); Path folder=directory.resolve("我的 YSM 模型");
        for(String asset:List.of("ysm.json","models/main.json","animations/main.animation.json","animations/extra.animation.json","textures/default.png")) {
            Path target=folder.resolve(asset);Files.createDirectories(target.getParent());
            try(var input=YsmFolderModel.class.getResourceAsStream("/assets/meplayeractions/builtin/openysm_default/"+asset)) {
                assertNotNull(input);Files.write(target,input.readAllBytes());
            }
        }
        var library=new LocalModelLibrary(directory);
        assertEquals(List.of("openysm_default","ysm:我的 YSM 模型"),library.models().stream().map(LocalModelLibrary.Entry::id).toList());
        var bundled=library.load("openysm_default");var local=library.load("ysm:我的 YSM 模型");
        assertEquals(bundled.hash(),local.hash());assertEquals(172,local.model().cubeCount());
        assertTrue(local.model().animations().containsAll(List.of("idle","walk","run","swim","extra1","extra7")));
    }

    @Test void alternateDefaultSkinChangesPngWithoutChangingThePlayerGeometryOrAnimations() throws Exception {
        var library=new LocalModelLibrary(Files.createDirectory(temporary.resolve("models")));
        var original=library.load("openysm_default");var blue=library.load("openysm_default",true);
        assertNotEquals(original.hash(),blue.hash());assertEquals(original.model().cubeCount(),blue.model().cubeCount());
        assertEquals(original.model().animations(),blue.model().animations());
        assertEquals(original.model().sample(7,List.of()),blue.model().sample(7,List.of()));
        assertFalse(java.util.Arrays.equals(original.model().textures().getFirst().png(),blue.model().textures().getFirst().png()));
    }
}
