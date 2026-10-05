package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalModelBudgetTest {
    @TempDir Path temporary;

    @Test void validLocalNativeAssetsAboveNetworkBudgetDoNotNeedAShareableBundle() throws Exception {
        var original = YsmFolderModel.bundledDefaultWithProfile(null); var files = new LinkedHashMap<>(original.sourceFiles());
        byte[] sound = new byte[AssetTransfer.MAX_RAW + 1]; sound[0]='O';sound[1]='g';sound[2]='g';sound[3]='S';
        files.put("sounds/local-only.ogg", sound);
        Path root = temporary.resolve("large-model");
        for (var asset : files.entrySet()) { Path target=root.resolve(asset.getKey());Files.createDirectories(target.getParent());Files.write(target,asset.getValue()); }
        var library = new LocalModelLibrary(temporary);
        assertFalse(library.load("ysm:large-model").model().animations().isEmpty());
        assertTrue(assertThrows(IOException.class,()->library.sourceBundle("ysm:large-model")).getMessage().contains("私人同步 8 MiB"));
        // The cached upload failure does not keep re-reading the author files or block local use.
        assertFalse(library.load("ysm:large-model", "blue").model().animations().isEmpty());
        assertThrows(NativeModelBundle.NetworkBudgetExceededException.class,()->NativeModelBundle.encode("ysm","ysm.json",files));
        byte[] archive=NativeModelBundleTest.zip(files);
        assertFalse(YsmFolderModel.readArchiveWithProfile(archive,null).profile().textures().isEmpty());
        assertThrows(IOException.class,()->NativeModelBundle.validate(archive),"Network archive still has an 8 MiB expanded bound");
    }

    @Test void explicitLocalJsonEntryRetainsTheNetworkJsonBoundAndFiniteLocalBound() throws Exception {
        byte[] original=NativeYsmFile.importModel(NativeYsmFileTest.model(),null).raw();
        byte[] padded=java.util.Arrays.copyOf(original,AssetTransfer.MAX_RAW+1);
        java.util.Arrays.fill(padded,original.length,padded.length,(byte)' ');
        assertThrows(IllegalArgumentException.class,()->BbModel.parse(padded));
        assertDoesNotThrow(()->BbModel.parseLocal(padded));
        assertEquals(64*1024*1024,LocalModelBudget.MAX_BYTES);
        assertThrows(IOException.class,()->NativeModelBundle.checkedLocalFiles(Map.of("large.json",new byte[LocalModelBudget.MAX_BYTES+1])));
    }
}
