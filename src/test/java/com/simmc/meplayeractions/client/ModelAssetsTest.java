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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

class ModelAssetsTest
{
    @TempDir
    Path temporary;

    @Test
    void shippedGeometryTexturesAndAnimationsRoundTripThroughBoundedOrderedChunks() throws Exception
    {
        for (String id : List.of("ysm_01_jk", "ysm_02_jk"))
        {
            byte[] raw = Files.readAllBytes(Path.of("examples/blueprints", id + ".bbmodel"));
            ModelAssets.Asset asset = ModelAssets.pack(id, raw);
            assertEquals(raw.length, asset.rawBytes());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)), asset.hash());
            assertTrue(asset.compressed().length <= ModelAssets.MAX_COMPRESSED_BYTES);
            ByteArrayOutputStream assembled = new ByteArrayOutputStream();
            for (int i = 0; i < asset.chunks(9000); i++)
            {
                byte[] chunk = asset.chunk(i, 9000);
                assertTrue(chunk.length <= 9000);
                assembled.write(Base64.getDecoder().decode(Base64.getEncoder().encodeToString(chunk)));
            }
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(assembled.toByteArray())))
            {
                assertArrayEquals(raw, gzip.readAllBytes());
            }
            assertThrows(IllegalArgumentException.class, () -> asset.chunk(asset.chunks(9000), 9000));
        }
    }

    @Test
    void invalidAndOversizedAssetsCannotEnterTransferCache()
    {
        assertThrows(IOException.class, () -> ModelAssets.pack("x", new byte[0]));
        assertThrows(IOException.class, () -> ModelAssets.pack("x", new byte[ModelAssets.MAX_RAW_BYTES + 1]));
        assertThrows(IOException.class, () -> ModelAssets.pack("x", "{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ModelAssets.pack("x", "[".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void diagnosticReadsDoNotStartPreparationAndInvalidIdsNeverBecomeFilePaths() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("read-only"));
        assertEquals("pending", fixture.assets.status("demo").state());
        assertTrue(fixture.assets.status("demo").reason().contains("尚未请求"));
        for (String id : new String[]
        { null, "../outside", "Demo", "", "x".repeat(65) })
        {
            assertEquals("invalid", fixture.assets.status(id).state());
            assertTrue(fixture.assets.get(id).isEmpty());
        }
        assertTrue(fixture.work.isEmpty());
        assertEquals(0, fixture.resourceReads.get());
        assertTrue(fixture.warnings.isEmpty());
    }

    @Test
    void asynchronousPreparationOnlyOffersExplicitOwnModelsAndNeverReadsJarOrEngine() throws Exception
    {
        ModelAssetsTest.Fixture own = new Fixture(this.temporary.resolve("own"));
        byte[] ownRaw = raw("demo", "OWN");
        Files.write(own.own.resolve("demo.bbmodel"), ownRaw);
        own.resources.put("models/demo.bbmodel", raw("demo", "JAR"));
        Files.write(own.engine.resolve("demo.bbmodel"), raw("demo", "ME"));
        prepare(own, "demo");
        assertEquals("own", own.assets.status("demo").source());
        assertEquals(0, own.resourceReads.get());
        assertEquals(ModelAssets.pack("demo", ownRaw).hash(), own.assets.get("demo").orElseThrow().hash());
        assertArrayEquals(ownRaw, Files.readAllBytes(own.own.resolve("demo.bbmodel")));
        ModelAssetsTest.Fixture jar = new Fixture(this.temporary.resolve("jar"));
        byte[] jarRaw = raw("demo", "JAR");
        jar.resources.put("models/demo.bbmodel", jarRaw);
        Files.write(jar.engine.resolve("demo.bbmodel"), raw("demo", "ME"));
        prepare(jar, "demo");
        assertEquals("own", jar.assets.status("demo").source());
        assertEquals("missing", jar.assets.status("demo").state());
        assertTrue(jar.assets.get("demo").isEmpty());
        assertEquals(0, jar.resourceReads.get());
        assertFalse(Files.exists(jar.own.resolve("demo.bbmodel")),
                "A read must not silently extract/overwrite a model");
        ModelAssetsTest.Fixture engine = new Fixture(this.temporary.resolve("engine"));
        byte[] engineRaw = raw("demo", "ME");
        Files.write(engine.engine.resolve("demo.bbmodel"), engineRaw);
        prepare(engine, "demo");
        assertEquals("own", engine.assets.status("demo").source());
        assertEquals("missing", engine.assets.status("demo").state());
        assertTrue(engine.assets.get("demo").isEmpty());
        assertEquals(0, engine.resourceReads.get());
        assertFalse(Files.exists(engine.own.resolve("demo.bbmodel")));
    }

    @Test
    void missingAndInvalidAreTerminalUntilInvalidationAndWarnOnlyOnce() throws Exception
    {
        ModelAssetsTest.Fixture missing = new Fixture(this.temporary.resolve("missing"));
        prepare(missing, "demo");
        assertEquals("missing", missing.assets.status("demo").state());
        assertEquals("own", missing.assets.status("demo").source());
        assertEquals(1, missing.warnings.size());
        for (int i = 0; i < 20; i++)
        {
            assertTrue(missing.assets.get("demo").isEmpty());
            assertEquals("missing", missing.assets.status("demo").state());
        }
        assertTrue(missing.work.isEmpty());
        assertEquals(1, missing.warnings.size());
        Files.write(missing.own.resolve("demo.bbmodel"), raw("demo", "new"));
        assertTrue(missing.assets.get("demo").isEmpty());
        missing.assets.invalidate();
        prepare(missing, "demo");
        assertEquals("ready", missing.assets.status("demo").state());
        assertEquals(1, missing.warnings.size());
        ModelAssetsTest.Fixture invalid = new Fixture(this.temporary.resolve("invalid"));
        Files.writeString(invalid.own.resolve("demo.bbmodel"), "{}");
        Files.write(Files.createDirectories(invalid.own.resolve("nested")).resolve("demo.bbmodel"),
                raw("demo", "valid nested model"));
        invalid.resources.put("models/demo.bbmodel", raw("demo", "valid fallback"));
        prepare(invalid, "demo");
        assertEquals("invalid", invalid.assets.status("demo").state());
        assertEquals("own", invalid.assets.status("demo").source());
        assertEquals(0, invalid.resourceReads.get());
        for (int i = 0; i < 20; i++)
        {
            assertTrue(invalid.assets.get("demo").isEmpty());
        }

        assertEquals(1, invalid.warnings.size());
        assertTrue(invalid.work.isEmpty());
    }

    @Test
    void explicitRootFileWinsOverNestedDuplicatesWithoutReadingUnrelatedFiles() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("root-priority"));
        Files.write(Files.createDirectories(fixture.own.resolve("a")).resolve("demo.bbmodel"), raw("demo", "nested a"));
        Files.write(Files.createDirectories(fixture.own.resolve("b")).resolve("demo.bbmodel"), raw("demo", "nested b"));
        Files.writeString(fixture.own.resolve("unrelated.bbmodel"), "{broken");
        byte[] root = raw("different_id", "explicit filename winner");
        Files.write(fixture.own.resolve("demo.bbmodel"), root);
        prepare(fixture, "demo");
        assertEquals("own", fixture.assets.status("demo").source());
        assertEquals(ModelAssets.pack("demo", root).hash(), fixture.assets.get("demo").orElseThrow().hash());
    }

    @Test
    void uniqueNestedModelUsesFilenameButNeverGrantsJsonIdentifierAliases() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("nested"));
        Path nested = Files.createDirectories(fixture.own.resolve("folder/deeper"));
        byte[] filename = raw("other_alias", "explicit filename");
        Files.write(nested.resolve("demo.bbmodel"), filename);
        Files.write(fixture.own.resolve("unrelated.bbmodel"), raw("wanted", "unregistered alias"));
        prepare(fixture, "demo");
        assertEquals(ModelAssets.pack("demo", filename).hash(), fixture.assets.get("demo").orElseThrow().hash());
        prepare(fixture, "other_alias");
        assertEquals("missing", fixture.assets.status("other_alias").state());
        prepare(fixture, "wanted");
        assertEquals("missing", fixture.assets.status("wanted").state());
        assertEquals(0, fixture.resourceReads.get());
    }

    @Test
    void duplicateNestedFilenamesAreAmbiguousUntilExplicitRootSelectionAndReload() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("ambiguous"));
        Files.write(Files.createDirectories(fixture.own.resolve("a")).resolve("demo.bbmodel"), raw("demo", "a"));
        Files.write(Files.createDirectories(fixture.own.resolve("b")).resolve("demo.bbmodel"), raw("demo", "b"));
        prepare(fixture, "demo");
        assertEquals("ambiguous", fixture.assets.status("demo").state());
        assertEquals("own", fixture.assets.status("demo").source());
        assertTrue(fixture.assets.get("demo").isEmpty());
        assertTrue(fixture.assets.status("demo").reason().contains("多个子文件夹"));
        assertEquals(0, fixture.resourceReads.get());
        byte[] selected = raw("demo", "selected root");
        Files.write(fixture.own.resolve("demo.bbmodel"), selected);
        assertTrue(fixture.assets.get("demo").isEmpty());
        fixture.assets.invalidate();
        prepare(fixture, "demo");
        assertEquals(ModelAssets.pack("demo", selected).hash(), fixture.assets.get("demo").orElseThrow().hash());
    }

    @Test
    void concurrentGetsQueueOnePreparationAndNeverAdvertiseAPendingHash() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("concurrent"));
        Files.write(fixture.own.resolve("demo.bbmodel"), raw("demo", "concurrent"));
        try (ExecutorService callers = Executors.newFixedThreadPool(8))
        {
            List<Callable<Optional<ModelAssets.Asset>>> work = new ArrayList<>();
            for (int i = 0; i < 32; i++)
            {
                work.add(() -> fixture.assets.get("demo"));
            }

            for (Future<Optional<ModelAssets.Asset>> result : callers.invokeAll(work))
            {
                assertTrue(result.get().isEmpty());
            }

        }
        assertEquals(1, fixture.work.size());
        assertEquals("pending", fixture.assets.status("demo").state());
        fixture.run();
        assertEquals("ready", fixture.assets.status("demo").state());
        assertEquals(64, fixture.assets.get("demo").orElseThrow().hash().length());
        assertTrue(fixture.warnings.isEmpty());
    }

    @Test
    void oldWorkersCannotRepopulateOrFailAReplacementPreparationAfterReload() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("reload"));
        Files.writeString(fixture.own.resolve("demo.bbmodel"), "{}");
        fixture.assets.get("demo");
        Runnable previous = fixture.work.remove();
        fixture.assets.invalidate();
        fixture.assets.get("demo");
        previous.run();
        assertEquals("pending", fixture.assets.status("demo").state());
        assertTrue(fixture.warnings.isEmpty());
        Files.write(fixture.own.resolve("demo.bbmodel"), raw("demo", "replacement"));
        fixture.run();
        assertEquals("ready", fixture.assets.status("demo").state());
        fixture.assets.get("other");
        Runnable canceled = fixture.work.remove();
        fixture.assets.invalidate();
        canceled.run();
        assertEquals("pending", fixture.assets.status("other").state());
        assertTrue(fixture.assets.status("other").reason().contains("尚未请求"));
        assertTrue(fixture.warnings.isEmpty());
    }

    @Test
    void removedPublishedFileCannotBeOfferedFromTheOldReadyCache() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("revoked-ready"));
        Files.write(fixture.own.resolve("demo.bbmodel"), raw("demo", "published"));
        prepare(fixture, "demo");
        assertTrue(fixture.assets.get("demo").isPresent());
        Files.delete(fixture.own.resolve("demo.bbmodel"));
        fixture.assets.revokeUnpublished(id -> id.equals("demo"));
        assertEquals(0, fixture.assets.metrics().uniqueHashes());
        assertEquals(0, fixture.assets.metrics().bytes());
        assertTrue(fixture.assets.get("demo").isEmpty());
        fixture.run();
        assertEquals("missing", fixture.assets.status("demo").state());
        assertTrue(fixture.assets.get("demo").isEmpty());
        ModelAssets.Status diagnosis = fixture.assets.status("demo");
        fixture.assets.revokeUnpublished(id -> true);
        assertSame(diagnosis, fixture.assets.status("demo"), "Negative diagnostics do not need invalidation");
        assertTrue(fixture.work.isEmpty());
    }

    @Test
    void unknownOrStillPublishedSnapshotKeepsTheSameReadyAsset() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("retained-ready"));
        Files.write(fixture.own.resolve("demo.bbmodel"), raw("demo", "published"));
        prepare(fixture, "demo");
        ModelAssets.Asset asset = fixture.assets.get("demo").orElseThrow();
        long bytes = fixture.assets.metrics().bytes();
        fixture.assets.revokeUnpublished(id -> false);
        assertSame(asset, fixture.assets.get("demo").orElseThrow());
        assertEquals(bytes, fixture.assets.metrics().bytes());
        assertEquals("ready", fixture.assets.status("demo").state());
        assertTrue(fixture.work.isEmpty());
    }

    @Test
    void revokedPendingWorkerCannotPublishIntoAReplacementRequest() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("revoked-pending"));
        Files.write(fixture.own.resolve("demo.bbmodel"), raw("demo", "previous"));
        fixture.assets.get("demo");
        Runnable previous = fixture.work.remove();
        fixture.assets.revokeUnpublished(id -> id.equals("demo"));
        fixture.assets.get("demo");
        assertEquals(1, fixture.work.size());
        previous.run();
        assertEquals("pending", fixture.assets.status("demo").state());
        assertEquals(0, fixture.assets.metrics().uniqueHashes());
        assertTrue(fixture.warnings.isEmpty());
        Files.write(fixture.own.resolve("demo.bbmodel"), raw("demo", "replacement"));
        fixture.run();
        assertEquals("ready", fixture.assets.status("demo").state());
        assertEquals(ModelAssets.pack("demo", raw("demo", "replacement")).hash(),
                fixture.assets.get("demo").orElseThrow().hash());
    }

    @Test
    void revocationPreservesTransferPinsUntilTheLastTransferReleases() throws Exception
    {
        ModelAssetsTest.Fixture fixture = new Fixture(this.temporary.resolve("revoked-pinned"));
        Files.write(fixture.own.resolve("demo.bbmodel"), raw("demo", "published"));
        prepare(fixture, "demo");
        ModelAssets.Asset asset = fixture.assets.get("demo").orElseThrow();
        assertTrue(fixture.assets.retain(asset));
        assertTrue(fixture.assets.retain(asset));
        long bytes = fixture.assets.metrics().bytes();
        assertTrue(bytes > 0);
        fixture.assets.revokeUnpublished(id -> id.equals("demo"));
        assertEquals(0, fixture.assets.metrics().models());
        assertEquals(1, fixture.assets.metrics().uniqueHashes());
        assertEquals(bytes, fixture.assets.metrics().bytes());
        fixture.assets.release(asset);
        assertEquals(1, fixture.assets.metrics().uniqueHashes());
        assertEquals(bytes, fixture.assets.metrics().bytes());
        fixture.assets.release(asset);
        assertEquals(0, fixture.assets.metrics().uniqueHashes());
        assertEquals(0, fixture.assets.metrics().bytes());
    }

    @Test
    void rejectedAsyncSchedulingIsVisibleInvalidAndDoesNotRemainPending() throws Exception
    {
        ArrayList<String> warnings = new ArrayList<String>();
        ModelAssets assets = new ModelAssets(this.temporary.resolve("own"), null, name -> null, task ->
        {
            throw new RejectedExecutionException("plugin stopped");
        }, warnings::add);
        assertTrue(assets.get("demo").isEmpty());
        assertEquals("invalid", assets.status("demo").state());
        assertEquals("lookup", assets.status("demo").source());
        assertTrue(assets.status("demo").reason().contains("无法安排"));
        assertTrue(assets.get("demo").isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void ioAndSchedulingDiagnosticsNeverExposePrivatePathsOrExceptionControlCharacters() throws Exception
    {
        String privateMessage = "C:\\private-server\\accounts\\credentials.json\r\npassword=private-secret";
        ArrayDeque<Runnable> work = new ArrayDeque<Runnable>();
        ArrayList<String> ioWarnings = new ArrayList<String>();
        Path privateRoot = Files.createDirectories(this.temporary.resolve("private-server/accounts"));
        Files.writeString(privateRoot.resolve("demo.bbmodel"), "{}");
        ModelAssets assets = new ModelAssets(privateRoot, null, name ->
        {
            throw new IOException(privateMessage);
        }, work::add, ioWarnings::add);
        assets.get("demo");
        work.remove().run();
        ModelAssets.Status status = assets.status("demo");
        assertEquals("invalid", status.state());
        assertEquals("own", status.source());
        assertFalse(status.reason().contains("private"));
        assertFalse(status.reason().contains("credentials"));
        assertFalse(status.reason().codePoints().anyMatch(Character::isISOControl));
        assertEquals(1, ioWarnings.size());
        assertFalse(ioWarnings.getFirst().contains("private"));
        assertFalse(ioWarnings.getFirst().codePoints().anyMatch(Character::isISOControl));
        ArrayList<String> warnings = new ArrayList<String>();
        ModelAssets rejected = new ModelAssets(this.temporary.resolve("absent-own"), null, name -> null, task ->
        {
            throw new RejectedExecutionException(privateMessage);
        }, warnings::add);
        rejected.get("demo");
        assertTrue(rejected.status("demo").reason().contains("RejectedExecutionException"));
        assertFalse(rejected.status("demo").reason().contains("private"));
        assertFalse(warnings.getFirst().contains("private"));
    }

    @Test
    void encodedChunksAreImmutableAndHashSharingDoesNotMergeModelIdentity() throws Exception
    {
        ArrayDeque<Runnable> work = new ArrayDeque<Runnable>();
        byte[] raw = raw("source", "same immutable asset");
        Path own = Files.createDirectories(this.temporary.resolve("shared"));
        Files.write(own.resolve("first.bbmodel"), raw);
        Files.write(own.resolve("second.bbmodel"), raw);
        ModelAssets assets = new ModelAssets(own, null, name ->
        {
            throw new AssertionError("JAR resources are not delivery authorization");
        }, work::add, ignored ->
        {
        }, ModelComplexityLimits.defaults(), 4, 1_000_000);
        assets.get("first");
        work.remove().run();
        ModelAssets.Asset first = assets.get("first").orElseThrow();
        assets.get("second");
        work.remove().run();
        ModelAssets.Asset second = assets.get("second").orElseThrow();
        assertEquals("first", first.modelId());
        assertEquals("second", second.modelId());
        assertEquals(first.hash(), second.hash());
        assertEquals(1, assets.metrics().uniqueHashes());
        long retained = assets.metrics().bytes();
        assertTrue(retained > first.compressedBytes());
        assertTrue(assets.retain(first));
        assertTrue(assets.retain(second));
        byte[] copied = first.compressed();
        Arrays.fill(copied, (byte) 0);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        for (int i = 0; i < first.chunks(9000); i++)
        {
            packed.write(Base64.getDecoder().decode(first.base64Chunk(i, 9000)));
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed.toByteArray())))
        {
            assertArrayEquals(raw, gzip.readAllBytes());
        }
        assets.invalidate();
        assertEquals(retained, assets.metrics().bytes());
        assertEquals(1, assets.metrics().uniqueHashes());
        assets.release(first);
        assertEquals(retained, assets.metrics().bytes());
        assets.release(second);
        assertEquals(0, assets.metrics().bytes());
        assertEquals(0, assets.metrics().uniqueHashes());
        assertThrows(IllegalArgumentException.class, () -> first.base64Chunk(-1, 9000));
    }

    @Test
    void pendingWorkAndPreparedAssetsRespectCacheCountAndMemoryBudgets() throws Exception
    {
        ArrayDeque<Runnable> work = new ArrayDeque<Runnable>();
        ArrayList<String> warnings = new ArrayList<String>();
        Path own = Files.createDirectories(this.temporary.resolve("budget"));
        for (String id : List.of("first", "second", "third"))
        {
            Files.write(own.resolve(id + ".bbmodel"), raw("source", id));
        }

        ModelAssets assets = new ModelAssets(own, null, name ->
        {
            throw new AssertionError("JAR resources are not delivery authorization");
        }, work::add, warnings::add, ModelComplexityLimits.defaults(), 2, 1_000_000);
        assets.get("first");
        assets.get("second");
        assertTrue(assets.get("third").isEmpty());
        assertEquals(2, work.size());
        assertEquals("asset_queue_full", assets.status("third").state());
        work.remove().run();
        work.remove().run();
        assertEquals(2, assets.metrics().models());
        assets.get("third");
        assertEquals(1, work.size());
        work.remove().run();
        assertEquals(2, assets.metrics().models());
        Path tinyRoot = Files.createDirectories(this.temporary.resolve("tiny"));
        Files.write(tinyRoot.resolve("demo.bbmodel"), raw("source", "tiny"));
        ModelAssets tiny = new ModelAssets(tinyRoot, null, name ->
        {
            throw new AssertionError("JAR resources are not delivery authorization");
        }, work::add, warnings::add, ModelComplexityLimits.defaults(), 2, 1);
        tiny.get("demo");
        work.remove().run();
        assertEquals("asset_too_large", tiny.status("demo").state());
        assertEquals(0, tiny.metrics().bytes());
        assertTrue(tiny.get("demo").isEmpty());
        assertTrue(work.isEmpty());
    }

    @Test
    void tightenedComplexityBudgetRejectsBeforeAdvertisingAssetHash() throws Exception
    {
        ArrayDeque<Runnable> work = new ArrayDeque<Runnable>();
        byte[] excessive = "{\"elements\":[],\"outliner\":[{\"name\":\"root\",\"children\":[{\"name\":\"child\",\"children\":[]}]}],\"textures\":[],\"animations\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        ModelComplexityLimits limits = new ModelComplexityLimits(1, 1024, 65536, 32768, 2097152, 200000, 256, 8388608,
                16777216);
        Path own = Files.createDirectories(this.temporary.resolve("complexity"));
        Files.write(own.resolve("demo.bbmodel"), excessive);
        ModelAssets assets = new ModelAssets(own, null, name ->
        {
            throw new AssertionError("JAR resources are not delivery authorization");
        }, work::add, ignored ->
        {
        }, limits, 2, 1_000_000);
        assets.get("demo");
        work.remove().run();
        assertEquals("model_complexity", assets.status("demo").state());
        assertTrue(assets.get("demo").isEmpty());
        assertEquals(0, assets.metrics().bytes());
    }

    private static byte[] raw(String identifier, String marker)
    {
        return ("{\"model_identifier\":\"" + identifier + "\",\"marker\":\"" + marker
                + "\",\"elements\":[],\"outliner\":[],\"textures\":[],\"animations\":[]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void prepare(Fixture fixture, String id)
    {
        assertTrue(fixture.assets.get(id).isEmpty());
        assertEquals("pending", fixture.assets.status(id).state());
        fixture.run();
    }

    private static final class Fixture
    {
        final Path own, engine;
        final ModelAssets assets;
        final Map<String, byte[]> resources = new ConcurrentHashMap<>();
        final AtomicInteger resourceReads = new AtomicInteger();
        final Queue<Runnable> work = new ConcurrentLinkedQueue<>();
        final Queue<String> warnings = new ConcurrentLinkedQueue<>();

        Fixture(Path root) throws IOException
        {
            this.own = Files.createDirectories(root.resolve("own"));
            this.engine = Files.createDirectories(root.resolve("engine"));
            this.assets = new ModelAssets(this.own, this.engine, name ->
            {
                this.resourceReads.incrementAndGet();
                byte[] raw = this.resources.get(name);
                return raw == null ? null : new ByteArrayInputStream(raw);
            }, this.work::add, this.warnings::add);
        }

        void run()
        {
            this.work.remove().run();
        }
    }
}
