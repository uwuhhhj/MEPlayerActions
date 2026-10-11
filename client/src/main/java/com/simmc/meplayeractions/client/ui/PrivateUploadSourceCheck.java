package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.NativeModelBundle;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import com.simmc.meplayeractions.client.network.PrivateUploadCatalogSnapshot;
import java.io.IOException;
import java.nio.file.Path;

/** Fresh complete local source identity for an explicit saved-model action, independent of session caches. */
final class PrivateUploadSourceCheck
{
    static boolean matches(Path directory, PrivateUploadCatalogSnapshot.Model expected) throws IOException
    {
        byte[] bytes = new LocalModelLibrary(directory).sourceBundle(expected.modelId());
        return bytes.length == expected.bytes() && AssetTransfer.hash(bytes).equals(expected.hash())
                && NativeModelBundle.validate(bytes).kind().equals(expected.kind());
    }

    private PrivateUploadSourceCheck()
    {
    }
}
