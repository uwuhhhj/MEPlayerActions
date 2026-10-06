package com.simmc.meplayeractions.client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.simmc.meplayeractions.config.ModelComplexityLimits;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ModelAssetsTest {
    @TempDir Path temporary;
    @Test void shippedGeometryTexturesAndAnimationsRoundTripThroughBoundedOrderedChunks() throws Exception {
        for (String id : List.of("ysm_01_jk", "ysm_02_jk")) {
            byte[] raw = Files.readAllBytes(Path.of("examples/blueprints", id + ".bbmodel"));
            var asset = ModelAssets.pack(id, raw); assertEquals(raw.length, asset.rawBytes());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)), asset.hash());
            assertTrue(asset.compressed().length <= ModelAssets.MAX_COMPRESSED_BYTES);
            ByteArrayOutputStream assembled = new ByteArrayOutputStream();
            for (int i = 0; i < asset.chunks(9000); i++) { byte[] chunk = asset.chunk(i, 9000);
                assertTrue(chunk.length <= 9000); assembled.write(Base64.getDecoder().decode(Base64.getEncoder().encodeToString(chunk))); }
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(assembled.toByteArray()))) { assertArrayEquals(raw, gzip.readAllBytes()); }
            assertThrows(IllegalArgumentException.class, () -> asset.chunk(asset.chunks(9000), 9000));
        }
    }
    @Test void invalidAndOversizedAssetsCannotEnterTransferCache() {
        assertThrows(IOException.class, () -> ModelAssets.pack("x", new byte[0]));
        assertThrows(IOException.class, () -> ModelAssets.pack("x", new byte[ModelAssets.MAX_RAW_BYTES + 1]));
        assertThrows(IOException.class, () -> ModelAssets.pack("x", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ModelAssets.pack("x", "[".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void diagnosticReadsDoNotStartPreparationAndInvalidIdsNeverBecomeFilePaths() throws Exception {
        var fixture=new Fixture(temporary.resolve("read-only"));
        assertEquals("pending",fixture.assets.status("demo").state());
        assertTrue(fixture.assets.status("demo").reason().contains("尚未请求"));
        for(String id:new String[]{null,"../outside","Demo","","x".repeat(65)}) {
            assertEquals("invalid",fixture.assets.status(id).state());assertTrue(fixture.assets.get(id).isEmpty());
        }
        assertTrue(fixture.work.isEmpty());assertEquals(0,fixture.resourceReads.get());assertTrue(fixture.warnings.isEmpty());
    }

    @Test void asynchronousPreparationSelectsOwnBeforeJarBeforeEngineWithoutWritingModels() throws Exception {
        var own=new Fixture(temporary.resolve("own"));byte[] ownRaw=raw("demo","OWN");
        Files.write(own.own.resolve("demo.bbmodel"),ownRaw);own.resources.put("models/demo.bbmodel",raw("demo","JAR"));
        Files.write(own.engine.resolve("demo.bbmodel"),raw("demo","ME"));
        prepare(own,"demo");assertEquals("own",own.assets.status("demo").source());assertEquals(0,own.resourceReads.get());
        assertEquals(ModelAssets.pack("demo",ownRaw).hash(),own.assets.get("demo").orElseThrow().hash());assertArrayEquals(ownRaw,Files.readAllBytes(own.own.resolve("demo.bbmodel")));
        var jar=new Fixture(temporary.resolve("jar"));byte[] jarRaw=raw("demo","JAR");
        jar.resources.put("models/demo.bbmodel",jarRaw);Files.write(jar.engine.resolve("demo.bbmodel"),raw("demo","ME"));
        prepare(jar,"demo");assertEquals("jar",jar.assets.status("demo").source());assertEquals(ModelAssets.pack("demo",jarRaw).hash(),jar.assets.get("demo").orElseThrow().hash());
        assertFalse(Files.exists(jar.own.resolve("demo.bbmodel")),"A read must not silently extract/overwrite a model");
        var engine=new Fixture(temporary.resolve("engine"));byte[] engineRaw=raw("demo","ME");Files.write(engine.engine.resolve("demo.bbmodel"),engineRaw);
        prepare(engine,"demo");assertEquals("modelengine",engine.assets.status("demo").source());assertEquals(ModelAssets.pack("demo",engineRaw).hash(),engine.assets.get("demo").orElseThrow().hash());
        assertFalse(Files.exists(engine.own.resolve("demo.bbmodel")));
    }

    @Test void missingAndInvalidAreTerminalUntilInvalidationAndWarnOnlyOnce() throws Exception {
        var missing=new Fixture(temporary.resolve("missing"));prepare(missing,"demo");assertEquals("missing",missing.assets.status("demo").state());
        assertEquals("none",missing.assets.status("demo").source());assertEquals(1,missing.warnings.size());
        for(int i=0;i<20;i++) { assertTrue(missing.assets.get("demo").isEmpty());assertEquals("missing",missing.assets.status("demo").state()); }
        assertTrue(missing.work.isEmpty());assertEquals(1,missing.warnings.size());
        Files.write(missing.own.resolve("demo.bbmodel"),raw("demo","new"));assertTrue(missing.assets.get("demo").isEmpty());
        missing.assets.invalidate();prepare(missing,"demo");assertEquals("ready",missing.assets.status("demo").state());assertEquals(1,missing.warnings.size());
        var invalid=new Fixture(temporary.resolve("invalid"));Files.writeString(invalid.own.resolve("demo.bbmodel"),"{}");
        invalid.resources.put("models/demo.bbmodel",raw("demo","valid fallback"));prepare(invalid,"demo");
        assertEquals("invalid",invalid.assets.status("demo").state());assertEquals("own",invalid.assets.status("demo").source());assertEquals(0,invalid.resourceReads.get());
        for(int i=0;i<20;i++) assertTrue(invalid.assets.get("demo").isEmpty());
        assertEquals(1,invalid.warnings.size());assertTrue(invalid.work.isEmpty());
    }

    @Test void engineLookupRetainsFilenamePriorityThenStableIdentifierFallback() throws Exception {
        var fixture=new Fixture(temporary.resolve("filename"));Path nested=Files.createDirectories(fixture.engine.resolve("nested"));
        byte[] filename=raw("different_id","file name winner");Files.write(nested.resolve("demo.bbmodel"),filename);
        Files.write(fixture.engine.resolve("a-other.bbmodel"),raw("demo","identifier fallback"));
        prepare(fixture,"demo");assertEquals(ModelAssets.pack("demo",filename).hash(),fixture.assets.get("demo").orElseThrow().hash());
        var fallback=new Fixture(temporary.resolve("identifier"));Files.writeString(fallback.engine.resolve("a-broken.bbmodel"),"{broken");
        byte[] identified=raw("demo","identifier fallback");Files.write(fallback.engine.resolve("z-any-name.bbmodel"),identified);
        prepare(fallback,"demo");assertEquals("ready",fallback.assets.status("demo").state());assertEquals(ModelAssets.pack("demo",identified).hash(),fallback.assets.get("demo").orElseThrow().hash());
    }

    @Test void concurrentGetsQueueOnePreparationAndNeverAdvertiseAPendingHash() throws Exception {
        var fixture=new Fixture(temporary.resolve("concurrent"));fixture.resources.put("models/demo.bbmodel",raw("demo","concurrent"));
        try(var callers=Executors.newFixedThreadPool(8)) {
            List<Callable<Optional<ModelAssets.Asset>>> work=new ArrayList<>();for(int i=0;i<32;i++)work.add(()->fixture.assets.get("demo"));
            for(var result:callers.invokeAll(work))assertTrue(result.get().isEmpty());
        }
        assertEquals(1,fixture.work.size());assertEquals("pending",fixture.assets.status("demo").state());
        fixture.run();assertEquals("ready",fixture.assets.status("demo").state());assertEquals(64,fixture.assets.get("demo").orElseThrow().hash().length());assertTrue(fixture.warnings.isEmpty());
    }

    @Test void oldWorkersCannotRepopulateOrFailAReplacementPreparationAfterReload() throws Exception {
        var fixture=new Fixture(temporary.resolve("reload"));Files.writeString(fixture.own.resolve("demo.bbmodel"),"{}");
        fixture.assets.get("demo");Runnable previous=fixture.work.remove();fixture.assets.invalidate();fixture.assets.get("demo");
        previous.run();assertEquals("pending",fixture.assets.status("demo").state());assertTrue(fixture.warnings.isEmpty());
        Files.write(fixture.own.resolve("demo.bbmodel"),raw("demo","replacement"));fixture.run();assertEquals("ready",fixture.assets.status("demo").state());
        fixture.assets.get("other");Runnable canceled=fixture.work.remove();fixture.assets.invalidate();canceled.run();
        assertEquals("pending",fixture.assets.status("other").state());assertTrue(fixture.assets.status("other").reason().contains("尚未请求"));assertTrue(fixture.warnings.isEmpty());
    }

    @Test void rejectedAsyncSchedulingIsVisibleInvalidAndDoesNotRemainPending() throws Exception {
        var warnings=new ArrayList<String>();var assets=new ModelAssets(temporary.resolve("own"),null,name->null,
                task->{throw new RejectedExecutionException("plugin stopped");},warnings::add);
        assertTrue(assets.get("demo").isEmpty());assertEquals("invalid",assets.status("demo").state());assertEquals("lookup",assets.status("demo").source());
        assertTrue(assets.status("demo").reason().contains("无法安排"));assertTrue(assets.get("demo").isEmpty());assertEquals(1,warnings.size());
    }

    @Test void ioAndSchedulingDiagnosticsNeverExposePrivatePathsOrExceptionControlCharacters() {
        String privateMessage="C:\\private-server\\accounts\\credentials.json\r\npassword=private-secret";
        for (IOException failure : List.of(new IOException(privateMessage),new AccessDeniedException(privateMessage))) {
            var work=new ArrayDeque<Runnable>();var warnings=new ArrayList<String>();
            var assets=new ModelAssets(temporary.resolve("absent-own"),null,name->{throw failure;},work::add,warnings::add);
            assets.get("demo");work.remove().run();var status=assets.status("demo");
            assertEquals("invalid",status.state());assertEquals("jar",status.source());
            assertTrue(status.reason().contains(failure.getClass().getSimpleName()));
            assertFalse(status.reason().contains("private"));assertFalse(status.reason().contains("credentials"));
            assertFalse(status.reason().codePoints().anyMatch(Character::isISOControl));
            assertEquals(1,warnings.size());assertFalse(warnings.getFirst().contains("private"));
            assertFalse(warnings.getFirst().codePoints().anyMatch(Character::isISOControl));
        }
        var warnings=new ArrayList<String>();var rejected=new ModelAssets(temporary.resolve("absent-own"),null,name->null,
                task->{throw new RejectedExecutionException(privateMessage);},warnings::add);
        rejected.get("demo");assertTrue(rejected.status("demo").reason().contains("RejectedExecutionException"));
        assertFalse(rejected.status("demo").reason().contains("private"));assertFalse(warnings.getFirst().contains("private"));
    }

    @Test void encodedChunksAreImmutableAndHashSharingDoesNotMergeModelIdentity() throws Exception {
        var work=new ArrayDeque<Runnable>();byte[] raw=raw("source","same immutable asset");
        var assets=new ModelAssets(temporary.resolve("absent"),null,name->new ByteArrayInputStream(raw),work::add,ignored->{},
                ModelComplexityLimits.defaults(),4,1_000_000);
        assets.get("first");work.remove().run();var first=assets.get("first").orElseThrow();
        assets.get("second");work.remove().run();var second=assets.get("second").orElseThrow();
        assertEquals("first",first.modelId());assertEquals("second",second.modelId());assertEquals(first.hash(),second.hash());
        assertEquals(1,assets.metrics().uniqueHashes());long retained=assets.metrics().bytes();assertTrue(retained>first.compressedBytes());
        assertTrue(assets.retain(first));assertTrue(assets.retain(second));
        byte[] copied=first.compressed();Arrays.fill(copied,(byte)0);
        ByteArrayOutputStream packed=new ByteArrayOutputStream();
        for(int i=0;i<first.chunks(9000);i++)packed.write(Base64.getDecoder().decode(first.base64Chunk(i,9000)));
        try(var gzip=new GZIPInputStream(new ByteArrayInputStream(packed.toByteArray()))){assertArrayEquals(raw,gzip.readAllBytes());}
        assets.invalidate();assertEquals(retained,assets.metrics().bytes());assertEquals(1,assets.metrics().uniqueHashes());
        assets.release(first);assertEquals(retained,assets.metrics().bytes());assets.release(second);
        assertEquals(0,assets.metrics().bytes());assertEquals(0,assets.metrics().uniqueHashes());
        assertThrows(IllegalArgumentException.class,()->first.base64Chunk(-1,9000));
    }

    @Test void pendingWorkAndPreparedAssetsRespectCacheCountAndMemoryBudgets() throws Exception {
        var work=new ArrayDeque<Runnable>();var warnings=new ArrayList<String>();
        var assets=new ModelAssets(temporary.resolve("absent"),null,name->new ByteArrayInputStream(raw("source",name)),work::add,warnings::add,
                ModelComplexityLimits.defaults(),2,1_000_000);
        assets.get("first");assets.get("second");assertTrue(assets.get("third").isEmpty());
        assertEquals(2,work.size());assertEquals("asset_queue_full",assets.status("third").state());
        work.remove().run();work.remove().run();assertEquals(2,assets.metrics().models());
        assets.get("third");assertEquals(1,work.size());work.remove().run();assertEquals(2,assets.metrics().models());
        var tiny=new ModelAssets(temporary.resolve("tiny"),null,name->new ByteArrayInputStream(raw("source",name)),work::add,warnings::add,
                ModelComplexityLimits.defaults(),2,1);
        tiny.get("demo");work.remove().run();assertEquals("asset_too_large",tiny.status("demo").state());
        assertEquals(0,tiny.metrics().bytes());assertTrue(tiny.get("demo").isEmpty());assertTrue(work.isEmpty());
    }

    @Test void tightenedComplexityBudgetRejectsBeforeAdvertisingAssetHash() throws Exception {
        var work=new ArrayDeque<Runnable>();
        byte[] excessive="{\"elements\":[],\"outliner\":[{\"name\":\"root\",\"children\":[{\"name\":\"child\",\"children\":[]}]}],\"textures\":[],\"animations\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var limits=new ModelComplexityLimits(1,1024,65536,32768,2097152,200000,256,8388608,16777216);
        var assets=new ModelAssets(temporary.resolve("absent"),null,name->new ByteArrayInputStream(excessive),work::add,ignored->{},limits,2,1_000_000);
        assets.get("demo");work.remove().run();assertEquals("model_complexity",assets.status("demo").state());
        assertTrue(assets.get("demo").isEmpty());assertEquals(0,assets.metrics().bytes());
    }

    private static byte[] raw(String identifier,String marker) {
        return ("{\"model_identifier\":\""+identifier+"\",\"marker\":\""+marker+"\",\"elements\":[],\"outliner\":[],\"textures\":[],\"animations\":[]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void prepare(Fixture fixture,String id) {
        assertTrue(fixture.assets.get(id).isEmpty());assertEquals("pending",fixture.assets.status(id).state());fixture.run();
    }
    private static final class Fixture {
        final Path own,engine;final ModelAssets assets;
        final Map<String,byte[]> resources=new ConcurrentHashMap<>();final AtomicInteger resourceReads=new AtomicInteger();
        final Queue<Runnable> work=new ConcurrentLinkedQueue<>();final Queue<String> warnings=new ConcurrentLinkedQueue<>();
        Fixture(Path root) throws IOException {
            own=Files.createDirectories(root.resolve("own"));engine=Files.createDirectories(root.resolve("engine"));
            assets=new ModelAssets(own,engine,name->{resourceReads.incrementAndGet();byte[] raw=resources.get(name);return raw==null?null:new ByteArrayInputStream(raw);},work::add,warnings::add);
        }
        void run() { work.remove().run(); }
    }
}
