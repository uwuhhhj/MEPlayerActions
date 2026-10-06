package com.simmc.meplayeractions.client.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServerCachedModelCatalogTest {
    @TempDir Path temporary;

    @Test void oldHashOnlyAssetsRemainPreviewableWithoutGuessingServerIdentity() throws Exception {
        var cache = new ServerModelCache(temporary); byte[] raw = model("claimed_server_id"); String hash = AssetTransfer.hash(raw);
        cache.writeValidated(hash, raw); var models = ServerCachedModelCatalog.load(cache);
        assertEquals(1, models.size()); var entry = models.getFirst();
        assertEquals("cache:" + hash, entry.id()); assertEquals("缓存模型 · " + hash.substring(0,12), entry.label());
        assertFalse(entry.identified()); assertFalse(ServerModelCatalogSnapshot.validId(entry.id()));
        assertFalse(new ServerModelCatalogSnapshot().canRequest(entry.id(), true, System.nanoTime()));
    }

    @Test void authorizedIdentityPersistsAcrossRestartsButNeverBecomesCatalogAuthority() throws Exception {
        byte[] raw = model("asset"); String hash = AssetTransfer.hash(raw);
        new ServerModelCache(temporary).writeValidated("ysm_01_jk", hash, raw);
        var models = ServerCachedModelCatalog.load(new ServerModelCache(temporary));
        assertEquals(List.of(new ServerCachedModelCatalog.Model("ysm_01_jk", "ysm_01_jk", hash, true)), models);
        assertFalse(new ServerModelCatalogSnapshot().canRequest("ysm_01_jk", true, System.nanoTime()));
    }

    @Test void latestCachedVersionOwnsThePersistentIdAndOldVersionIsOnlyAnonymous() throws Exception {
        var cache = new ServerModelCache(temporary); byte[] older = model("older"), newer = model("newer");
        cache.writeValidated("model", AssetTransfer.hash(older), older); cache.writeValidated("model", AssetTransfer.hash(newer), newer);
        var models = ServerCachedModelCatalog.load(cache);
        assertEquals(1, models.stream().filter(ServerCachedModelCatalog.Model::identified).count());
        assertEquals(AssetTransfer.hash(newer), models.stream().filter(m -> m.id().equals("model")).findFirst().orElseThrow().hash());
        assertTrue(models.stream().anyMatch(m -> m.id().equals("cache:" + AssetTransfer.hash(older))));
    }

    @Test void identicalContentCanRetainSeveralAuthorizedModelNames() throws Exception {
        var cache = new ServerModelCache(temporary); byte[] raw = model("asset"); String hash = AssetTransfer.hash(raw);
        cache.writeValidated("one", hash, raw); cache.rememberModel("two", hash);
        assertEquals(2, ServerCachedModelCatalog.load(cache).size());
        assertTrue(ServerCachedModelCatalog.load(cache).stream().allMatch(m -> m.identified() && m.hash().equals(hash)));
    }

    @Test void fullHashAndModelParsingAreBothRequiredToExposeEntries() throws Exception {
        var cache = new ServerModelCache(temporary); byte[] invalid = "not a bbmodel".getBytes(StandardCharsets.UTF_8);
        cache.writeValidated("bad", AssetTransfer.hash(invalid), invalid); assertTrue(ServerCachedModelCatalog.load(cache).isEmpty());
        byte[] raw = model("asset"); String hash = AssetTransfer.hash(raw); cache.writeValidated("good", hash, raw);
        Files.writeString(temporary.resolve(hash + ".bbmodel"), "tampered"); assertTrue(ServerCachedModelCatalog.load(cache).isEmpty());
    }

    @Test void removedAssetsCannotSurviveThroughStaleIdentityMetadata() throws Exception {
        var cache = new ServerModelCache(temporary); byte[] raw = model("asset"); String hash = AssetTransfer.hash(raw);
        cache.writeValidated("model", hash, raw); Files.delete(temporary.resolve(hash + ".bbmodel"));
        assertTrue(ServerCachedModelCatalog.load(cache).isEmpty());
    }

    @Test void invalidIdentityIsRejectedBeforeWritingAnyAsset() throws Exception {
        Path directory = temporary.resolve("cache"); var cache = new ServerModelCache(directory); byte[] raw = model("asset");
        for(String id : new String[]{"../outside", "cache:hash", "name extra", "MODEL", "x".repeat(65)})
            assertThrows(IOException.class, () -> cache.writeValidated(id, AssetTransfer.hash(raw), raw));
        assertFalse(Files.exists(directory));
    }

    @Test void corruptUnsafeAndDeepMetadataFallBackToAnonymousValidatedAssets() throws Exception {
        var cache = new ServerModelCache(temporary); byte[] raw = model("asset"); String hash = AssetTransfer.hash(raw); cache.writeValidated(hash, raw);
        for(String text : List.of("not json", "{\"version\":1,\"models\":[{\"modelId\":\"../outside\",\"hash\":\"" + hash + "\",\"cachedAt\":1}]}",
                "{\"version\":" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + ",\"models\":[]}",
                "{\"version\":1,\"version\":1,\"models\":[]}")) {
            Files.writeString(temporary.resolve("server-models.json"), text);
            var result = ServerCachedModelCatalog.load(cache);
            assertEquals(1, result.size()); assertFalse(result.getFirst().identified());
        }
    }

    @Test void inspectionDoesNotMakeEveryBrowsedAssetRecentlyUsed() throws Exception {
        var cache = new ServerModelCache(temporary); byte[] raw = model("asset"); String hash = AssetTransfer.hash(raw); cache.writeValidated(hash, raw);
        Path file = temporary.resolve(hash + ".bbmodel"); FileTime old = FileTime.fromMillis(1_000_000); Files.setLastModifiedTime(file, old);
        assertFalse(ServerCachedModelCatalog.load(cache).isEmpty()); assertEquals(old.toMillis(), Files.getLastModifiedTime(file).toMillis());
    }

    @Test void modelAndIdentityInventoryRemainBounded() throws Exception {
        var cache = new ServerModelCache(temporary); byte[] raw = model("asset"); String hash = AssetTransfer.hash(raw); cache.writeValidated(hash, raw);
        for(int i = 0; i < ServerModelCache.MAX_FILES + 3; i++) cache.rememberModel("model_" + i, hash);
        assertEquals(ServerModelCache.MAX_FILES, ServerCachedModelCatalog.load(cache).size());
        assertTrue(Files.size(temporary.resolve("server-models.json")) <= 128 * 1024);
    }

    @Test void repeatedKnownIdentityDoesNotRewriteMetadataOrRescanTheGallery() throws Exception {
        var cache=new ServerModelCache(temporary);byte[] raw=model("asset");String hash=AssetTransfer.hash(raw);
        assertTrue(cache.writeValidated("model",hash,raw));Path index=temporary.resolve("server-models.json");
        byte[] saved=Files.readAllBytes(index);FileTime changed=Files.getLastModifiedTime(index);
        assertFalse(cache.rememberModel("model",hash));
        assertArrayEquals(saved,Files.readAllBytes(index));assertEquals(changed,Files.getLastModifiedTime(index));
    }

    @Test void unchangedFingerprintsReuseValidationAndModifiedOrEvictedFilesInvalidateIt() throws Exception {
        var cache=new ServerModelCache(temporary);byte[] raw=model("asset");String hash=AssetTransfer.hash(raw);cache.writeValidated(hash,raw);
        var parses=new java.util.concurrent.atomic.AtomicInteger();
        var catalog=new ServerCachedModelCatalog(cache,bytes->{parses.incrementAndGet();return true;});
        assertEquals(1,catalog.load().size());assertEquals(1,catalog.load().size());assertEquals(1,parses.get());
        Path file=temporary.resolve(hash+".bbmodel");
        Files.setLastModifiedTime(file,FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis()+2_000));
        assertEquals(1,catalog.load().size());assertEquals(2,parses.get());
        Files.delete(file);assertTrue(catalog.load().isEmpty());cache.writeValidated(hash,raw);
        assertEquals(1,catalog.load().size());assertEquals(3,parses.get());
    }

    @Test void rejectedModelOutcomesAreMemoizedUntilTheFileStatChanges() throws Exception {
        var cache=new ServerModelCache(temporary);byte[] raw=model("asset");String hash=AssetTransfer.hash(raw);cache.writeValidated(hash,raw);
        var parses=new java.util.concurrent.atomic.AtomicInteger();
        var catalog=new ServerCachedModelCatalog(cache,bytes->{parses.incrementAndGet();return false;});
        assertTrue(catalog.load().isEmpty());assertTrue(catalog.load().isEmpty());assertEquals(1,parses.get());
    }

    @Test void inspectionIsReadOnlyAndRespectsTheAggregateByteBudget() throws Exception {
        var cache=new ServerModelCache(temporary,100);byte[] first=new byte[70],second=new byte[70];first[0]=1;second[0]=2;
        String a=AssetTransfer.hash(first),b=AssetTransfer.hash(second);
        Files.write(temporary.resolve(a+".bbmodel"),first);Files.write(temporary.resolve(b+".bbmodel"),second);
        assertEquals(1,cache.galleryEntries().size());
        assertTrue(Files.exists(temporary.resolve(a+".bbmodel")));assertTrue(Files.exists(temporary.resolve(b+".bbmodel")));
        Files.writeString(temporary.resolve(a+".bbmodel"),"corrupt");
        assertTrue(cache.inspectValidated(a).isEmpty());assertTrue(Files.exists(temporary.resolve(a+".bbmodel")));
    }

    private static byte[] model(String name) throws Exception {
        JsonObject json = JsonParser.parseString("""
            {"meta":{"format_version":"5.0"},"resolution":{"width":16,"height":16},"textures":[],"animations":[],
             "elements":[{"uuid":"cube","type":"cube","from":[0,0,0],"to":[16,16,16],"origin":[0,0,0],
                          "faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
             "outliner":[{"uuid":"bone","name":"bone","origin":[0,0,0],"children":["cube"]}]}
            """).getAsJsonObject();
        json.addProperty("name", name); BufferedImage image = new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB); image.setRGB(0,0,0xffffffff);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes);
        JsonObject texture = new JsonObject(); texture.addProperty("source","data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        json.getAsJsonArray("textures").add(texture); return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
