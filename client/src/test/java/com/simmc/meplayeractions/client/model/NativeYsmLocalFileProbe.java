package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.nativeysm.PublicYsmCodec;
import com.simmc.meplayeractions.client.model.nativeysm.YSMBinaryDeserializer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Opt-in offline diagnostic. No user asset is embedded, extracted, modified or sent. */
public final class NativeYsmLocalFileProbe {
    private NativeYsmLocalFileProbe() { }
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Pass one existing local .ysm file path");
        Path file = Path.of(arguments[0]).toAbsolutePath().normalize();
        long size = Files.size(file);
        if (size < 1 || size > LocalModelBudget.MAX_BYTES) throw new IllegalArgumentException("Local source exceeds 64 MiB");
        byte[] encoded = Files.readAllBytes(file);
        System.out.println("sourceBytes=" + encoded.length);
        byte[] clear = PublicYsmCodec.decode(encoded);
        System.out.println("clearBytes=" + clear.length + "; format=" + ByteBuffer.wrap(clear).order(ByteOrder.LITTLE_ENDIAN).getInt());
        try (YSMBinaryDeserializer decoder = new YSMBinaryDeserializer(clear)) {
            var raw = decoder.deserializeKeepOpen();
            System.out.println("mainBones=" + raw.mainEntity.mainModel.bones.size() + "; animationFamilies=" + raw.mainEntity.animationFiles.size()
                    + "; textures=" + raw.mainEntity.textures.size() + "; sounds=" + raw.soundFiles.size());
            raw.mainEntity.textures.forEach((id, image) -> System.out.println("textureFormat=" + image.imageFormat + "; imageBytes=" + image.data.length
                    + "; imageDimensions=" + image.width + "x" + image.height));
        }
        var library = new LocalModelLibrary(file.getParent());
        String id = "ysm:" + file.getFileName();
        var loaded = library.load(id);
        System.out.println("localLoad=OK; cubes=" + loaded.model().cubeCount() + "; animations=" + loaded.model().animations().size()
                + "; components=" + loaded.profile().components().size() + "; vertices=" + loaded.model().sample(0, List.of()).size());
        try { System.out.println("privateBundleBytes=" + library.sourceBundle(id).length); }
        catch (java.io.IOException unavailable) { System.out.println("privateBundle=" + unavailable.getMessage()); }
    }
}
