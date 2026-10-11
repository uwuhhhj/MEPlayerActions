package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.PackModelLibrary;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import com.simmc.meplayeractions.client.network.ServerCachedModelCatalog;
import com.simmc.meplayeractions.client.network.ServerModelCache;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Resource-pack and offline delivery retain the same author interpretation as the asset reader. */
class SharedBbModelDeliveryTest
{
    @TempDir
    Path temporary;

    @Test
    void resourcePackRetainsAuthorProfileAndWireHashAfterNativeConversion() throws Exception
    {
        JsonObject source = author();
        byte[] raw = bytes(source);
        String hash = AssetTransfer.hash(raw);
        BbModelAsset.Decoded decoded = BbModelAsset.read(raw);
        Map<String, byte[]> resources = pack(source, hash);
        LocalModelLibrary.Loaded loaded = PackModelLibrary.load((id, maximum) ->
        {
            byte[] data = resources.get(id);
            if (data == null || data.length > maximum)
            {
                throw new IOException("Missing resource");
            }

            return data;
        }, "author", hash);

        assertEquals(hash, loaded.hash(), "The server/catalog identity belongs to the original bytes");
        assertTrue(loaded.profile().isImportedBbModel());
        assertEquals(decoded.profile().properties(), loaded.profile().properties());
        assertEquals(decoded.model().basisVertices(), loaded.model().basisVertices());
        assertEquals(decoded.model().animations(), loaded.model().animations());
        assertTrue(loaded.model().hasBone("RightHandLocator"));
        assertEquals(1f, YsmRenderScale.forProfile(loaded.profile()).x());
    }

    @Test
    void offlineCatalogUsesTheSameImporterWithoutRewritingOriginalCachedBytes() throws Exception
    {
        byte[] raw = bytes(author());
        String hash = AssetTransfer.hash(raw);
        ServerModelCache cache = new ServerModelCache(this.temporary);
        cache.writeValidated("author", hash, raw);

        List<ServerCachedModelCatalog.Model> models = ServerCachedModelCatalog.load(cache);
        assertEquals(1, models.size(), "Separate author groups accepted by the common reader remain browseable");
        assertEquals("author", models.getFirst().id());
        assertEquals(hash, models.getFirst().hash());
        assertTrue(models.getFirst().identified());
        assertArrayEquals(raw, Files.readAllBytes(this.temporary.resolve(hash + ".bbmodel")));
        BbModelAsset.Decoded decoded = BbModelAsset.read(cache.inspectValidated(hash).orElseThrow());
        assertTrue(decoded.profile().isImportedBbModel());
        assertTrue(decoded.model().hasBone("RightHandLocator"));
    }

    @Test
    void commonAssetReaderRetainsEditorFaceCornersThroughNativeRuntimeBaking() throws Exception
    {
        List<BbModel.Vertex> vertices = BbModelAsset.read(bytes(author())).model().basisVertices();
        // Official north face: texture TL, BL, BR, TR at (+X,+Y,-Z),
        // (+X,-Y,-Z), (-X,-Y,-Z), (-X,+Y,-Z). A nonzero bone pivot
        // must not change either the authored position or its texture corner.
        assertEquals(4, vertices.size(), "Missing faces must not acquire geometry during either conversion step");
        float[][] expected =
        {
                { 1, 2, 0, 0, 0 },
                { 1, 0, 0, 0, 1 },
                { 0, 0, 0, 1, 1 },
                { 0, 2, 0, 1, 0 } };
        for (int i = 0; i < expected.length; i++)
        {
            BbModel.Vertex vertex = vertices.get(i);
            assertArrayEquals(new float[]
            { expected[i][0] / 16, expected[i][1] / 16, expected[i][2] / 16, expected[i][3], expected[i][4], 0, 0, -1 },
                    new float[]
                    { vertex.x(), vertex.y(), vertex.z(), vertex.u(), vertex.v(), vertex.nx(), vertex.ny(),
                            vertex.nz() },
                    1e-6f);
        }
    }

    @Test
    void missingExternalTextureDoesNotBecomeAnOfflineUsableServerAsset() throws Exception
    {
        JsonObject source = author();
        JsonObject texture = source.getAsJsonArray("textures").get(0).getAsJsonObject();
        texture.addProperty("source", "");
        texture.addProperty("relative_path", "textures/private.png");
        byte[] raw = bytes(source);
        String hash = AssetTransfer.hash(raw);
        ServerModelCache cache = new ServerModelCache(this.temporary);
        cache.writeValidated("author", hash, raw);
        assertThrows(IOException.class, () -> BbModelAsset.read(raw));
        assertTrue(ServerCachedModelCatalog.load(cache).isEmpty());
        assertArrayEquals(raw, Files.readAllBytes(this.temporary.resolve(hash + ".bbmodel")));
    }

    private static JsonObject author() throws IOException
    {
        JsonObject source = JsonParser.parseString("""
                {"meta":{"format_version":"4.10"},"name":"author","resolution":{"width":1,"height":1},
                 "elements":[
                  {"uuid":"cube","type":"cube","from":[0,0,0],"to":[1,2,1],
                   "faces":{"north":{"uv":[0,0,1,1],"texture":0}}},
                  {"uuid":"hand","name":"RightHandLocator","type":"locator","position":[2,1,0]}],
                 "groups":[{"uuid":"body","name":"Body","origin":[0,2,0]}],
                 "outliner":[{"uuid":"body","children":["cube","hand"]}],
                 "textures":[{"uuid":"texture","name":"author.png","width":1,"height":1}],
                 "animations":[]}
                """).getAsJsonObject();
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffffffff);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        source.getAsJsonArray("textures").get(0).getAsJsonObject().addProperty("source",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()));
        return source;
    }

    private static Map<String, byte[]> pack(JsonObject source, String hash)
    {
        Map<String, byte[]> resources = new HashMap<>();
        JsonObject shared = source.deepCopy();
        JsonObject texture = shared.getAsJsonArray("textures").get(0).getAsJsonObject();
        String embedded = texture.get("source").getAsString();
        String png = "meplayeractions:textures/author.png";
        resources.put(png, Base64.getDecoder().decode(embedded.substring("data:image/png;base64,".length())));
        texture.addProperty("source", png);
        resources.put("meplayeractions:models/author.bbmodel", bytes(shared));
        resources.put(PackModelLibrary.INDEX,
                ("{\"version\":1,\"models\":[{\"modelId\":\"author\",\"hashSha256\":\"" + hash
                        + "\",\"modelResource\":\"meplayeractions:models/author.bbmodel\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
        return resources;
    }

    private static byte[] bytes(JsonObject source)
    {
        return source.toString().getBytes(StandardCharsets.UTF_8);
    }
}
