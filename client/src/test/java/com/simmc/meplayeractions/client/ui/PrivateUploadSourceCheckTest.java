package com.simmc.meplayeractions.client.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.NativeModelBundle;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import com.simmc.meplayeractions.client.network.PrivateUploadCatalogSnapshot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrivateUploadSourceCheckTest {
    @TempDir Path directory;

    @Test void changedDiskModelCannotRestoreAnOldArchiveDespiteTheSessionSourceCache() throws Exception {
        JsonObject source=source();var texture=source.getAsJsonArray("textures").get(0).getAsJsonObject();
        texture.addProperty("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(png(0xff224466)));
        Path file=directory.resolve("author.bbmodel");Files.writeString(file,source.toString());
        var session=new LocalModelLibrary(directory);byte[] original=session.sourceBundle("local:author.bbmodel");
        var expected=archive("local:author.bbmodel",original);
        assertTrue(PrivateUploadSourceCheck.matches(directory,expected));
        source.addProperty("name","changed author");Files.writeString(file,source.toString());
        assertArrayEquals(original,session.sourceBundle(expected.modelId()),"A session source cache can legitimately still hold the saved bytes");
        assertFalse(PrivateUploadSourceCheck.matches(directory,expected),"Explicit restore must inspect the current physical source");
        Files.delete(file);
        assertThrows(IOException.class,()->PrivateUploadSourceCheck.matches(directory,expected));
    }

    @Test void unchangedModelWithChangedCompanionTextureCannotRestoreTheSavedBundle() throws Exception {
        JsonObject source=source();var texture=source.getAsJsonArray("textures").get(0).getAsJsonObject();
        texture.addProperty("source","");texture.addProperty("relative_path","textures/author.png");
        Path textures=Files.createDirectory(directory.resolve("textures")),png=textures.resolve("author.png");
        Files.write(png,png(0xff224466));Files.writeString(directory.resolve("author.bbmodel"),source.toString());
        var session=new LocalModelLibrary(directory);byte[] original=session.sourceBundle("local:author.bbmodel");
        var expected=archive("local:author.bbmodel",original);
        assertTrue(PrivateUploadSourceCheck.matches(directory,expected));
        Files.write(png,png(0xff662244));
        assertArrayEquals(original,session.sourceBundle(expected.modelId()));
        assertFalse(PrivateUploadSourceCheck.matches(directory,expected),"All companion source files belong to the saved identity");
    }

    @Test void builtinRestoreUsesItsCompleteSourceAndRejectsAnIncorrectFormatReceipt() throws Exception {
        byte[] original=new LocalModelLibrary(directory).sourceBundle("openysm_default");
        var expected=archive("openysm_default",original);
        assertTrue(PrivateUploadSourceCheck.matches(directory,expected));
        assertFalse(PrivateUploadSourceCheck.matches(directory,new PrivateUploadCatalogSnapshot.Model(expected.modelId(),expected.hash(),"bbmodel",expected.bytes())));
    }

    private static PrivateUploadCatalogSnapshot.Model archive(String id,byte[] bytes) throws IOException {
        return new PrivateUploadCatalogSnapshot.Model(id,AssetTransfer.hash(bytes),NativeModelBundle.validate(bytes).kind(),bytes.length);
    }
    private static JsonObject source() {
        return JsonParser.parseString("""
                {"name":"author","resolution":{"width":1,"height":1},
                 "elements":[{"uuid":"cube","type":"cube","from":[0,0,0],"to":[1,1,1],
                  "faces":{"north":{"uv":[0,0,1,1],"texture":0}}}],
                 "groups":[{"uuid":"body","name":"Body","origin":[0,0,0]}],
                 "outliner":[{"uuid":"body","children":["cube"]}],
                 "textures":[{"uuid":"texture","name":"author.png","width":1,"height":1}]}
                """).getAsJsonObject();
    }
    private static byte[] png(int color) throws IOException {
        var pixels=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);pixels.setRGB(0,0,color);
        var bytes=new ByteArrayOutputStream();ImageIO.write(pixels,"png",bytes);return bytes.toByteArray();
    }
}
