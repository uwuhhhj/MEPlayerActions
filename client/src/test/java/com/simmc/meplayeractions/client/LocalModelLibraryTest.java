package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.network.AssetTransfer;
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
        assertEquals(List.of("ysm_01_jk","ysm_02_jk"),library.models().stream().map(LocalModelLibrary.Entry::id).toList());
        byte[] model;
        try(var input=LocalModelLibrary.class.getResourceAsStream("/assets/meplayeractions/models/ysm_01_jk.bbmodel")) {
            assertNotNull(input,"Bundled model must be available to the independent model picker");model=input.readAllBytes();
        }
        Files.write(directory.resolve("我的模型.bbmodel"),model);
        Files.writeString(directory.resolve("notes.txt"),"not a model");
        Files.createDirectory(directory.resolve("nested.bbmodel"));
        assertEquals(List.of("ysm_01_jk","ysm_02_jk","local:我的模型.bbmodel"),library.models().stream().map(LocalModelLibrary.Entry::id).toList());
        var loaded=library.load("local:我的模型.bbmodel");
        assertEquals(AssetTransfer.hash(model),loaded.hash());assertTrue(loaded.model().cubeCount()>0);
        assertEquals(loaded.hash(),library.load("ysm_01_jk").hash());
    }

    @Test void selectionCannotReadAnExternalPathOrADirectoryAsAModel() throws Exception {
        Path directory=Files.createDirectory(temporary.resolve("models"));
        Files.writeString(temporary.resolve("outside.bbmodel"),"outside the model library");
        Files.createDirectory(directory.resolve("folder.bbmodel"));var library=new LocalModelLibrary(directory);
        for(String id:List.of("local:../outside.bbmodel","local:..\\outside.bbmodel","local:"+temporary.resolve("outside.bbmodel"),
                "local:missing.bbmodel","local:folder.bbmodel","unknown")) {
            assertThrows(IOException.class,()->library.load(id),id);
        }
    }
}
