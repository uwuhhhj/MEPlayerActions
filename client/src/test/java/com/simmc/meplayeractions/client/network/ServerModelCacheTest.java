package com.simmc.meplayeractions.client.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ServerModelCacheTest {
    @TempDir Path temporary;

    @Test void verifiedBytesPersistAndTheSameHashIsReusableAcrossConnections() throws Exception {
        Path directory = temporary.resolve("服务器 缓存");
        byte[] raw = bytes("complete server model"); String hash = AssetTransfer.hash(raw);
        var firstServer = new ServerModelCache(directory);
        firstServer.writeValidated(hash, raw);
        var laterServer = new ServerModelCache(directory);
        assertArrayEquals(raw, laterServer.readValidated(hash).orElseThrow());
        laterServer.writeValidated(hash, raw);
        assertEquals(List.of(hash + ".bbmodel"), names(directory));
        assertEquals(8 * 1024 * 1024, ServerModelCache.MAX_RAW);
        assertEquals(128L * 1024 * 1024, ServerModelCache.MAX_TOTAL_BYTES);
    }

    @Test void missingAndInvalidKeysDoNotCreateDirectoriesOrUseSuppliedPaths() throws Exception {
        Path directory = temporary.resolve("cache"); var cache = new ServerModelCache(directory);
        assertTrue(cache.readValidated(AssetTransfer.hash(bytes("missing"))).isEmpty());
        for (String hash : new String[]{null, "", "../outside", "..\\outside", "0".repeat(63), "A".repeat(64)}) {
            assertTrue(cache.readValidated(hash).isEmpty());
            assertThrows(IOException.class, () -> cache.writeValidated(hash, bytes("model")));
        }
        assertFalse(Files.exists(directory));
    }

    @Test void rejectsEmptyOversizedAndMismatchedWritesBeforeTouchingStorage() throws Exception {
        Path directory = temporary.resolve("cache"); var cache = new ServerModelCache(directory);
        byte[] oversized = new byte[ServerModelCache.MAX_RAW + 1];
        assertThrows(IOException.class, () -> cache.writeValidated("0".repeat(64), bytes("wrong hash")));
        assertThrows(IOException.class, () -> cache.writeValidated(AssetTransfer.hash(new byte[0]), new byte[0]));
        assertThrows(IOException.class, () -> cache.writeValidated(AssetTransfer.hash(oversized), oversized));
        assertThrows(IOException.class, () -> cache.writeValidated("0".repeat(64), null));
        assertFalse(Files.exists(directory));
    }

    @Test void acceptsTheExactRawByteLimit() throws Exception {
        byte[] raw = new byte[ServerModelCache.MAX_RAW]; Arrays.fill(raw, (byte) 7);
        String hash = AssetTransfer.hash(raw); var cache = new ServerModelCache(temporary.resolve("cache"));
        cache.writeValidated(hash, raw);
        assertArrayEquals(raw, cache.readValidated(hash).orElseThrow());
    }

    @Test void aHashFilenameIsNotProofAndCorruptionOnlyDeletesTheOwnedEntry() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("cache"));
        String hash = AssetTransfer.hash(bytes("expected")); Path file = directory.resolve(hash + ".bbmodel");
        Files.write(file, bytes("corrupt")); Files.writeString(directory.resolve("user-notes.txt"), "keep");
        Files.writeString(directory.resolve("custom.bbmodel"), "keep model");
        Files.writeString(directory.resolve(".mpa-cache-unrelated.tmp"), "keep temporary");
        Files.createDirectory(directory.resolve("f".repeat(64) + ".bbmodel"));
        assertTrue(new ServerModelCache(directory).readValidated(hash).isEmpty());
        assertFalse(Files.exists(file));
        assertEquals("keep", Files.readString(directory.resolve("user-notes.txt")));
        assertEquals("keep model", Files.readString(directory.resolve("custom.bbmodel")));
        assertEquals("keep temporary", Files.readString(directory.resolve(".mpa-cache-unrelated.tmp")));
        assertTrue(Files.isDirectory(directory.resolve("f".repeat(64) + ".bbmodel")));
    }

    @Test void oversizedFilesAreBoundedMissesAndDoNotReachTheCaller() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("cache"));
        String hash = AssetTransfer.hash(bytes("expected")); Path file = directory.resolve(hash + ".bbmodel");
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) { sparse.setLength(ServerModelCache.MAX_RAW + 1L); }
        assertTrue(new ServerModelCache(directory).readValidated(hash).isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test void emptyCachedFilesAreRemovedAsMisses() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("cache"));
        String hash = AssetTransfer.hash(bytes("expected")); Path file = Files.createFile(directory.resolve(hash + ".bbmodel"));
        assertTrue(new ServerModelCache(directory).readValidated(hash).isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test void readAccessKeepsAnOlderAssetWhileBudgetEvictsTheLeastRecentlyUsed() throws Exception {
        Path directory = temporary.resolve("cache"); var cache = new ServerModelCache(directory, 6 * 1024);
        byte[] a = payload(1, 2048), b = payload(2, 2048), c = payload(3, 2048), d = payload(4, 2048);
        for (byte[] raw : List.of(a, b, c)) cache.writeValidated(AssetTransfer.hash(raw), raw);
        timestamp(directory, a, 1000); timestamp(directory, b, 2000); timestamp(directory, c, 3000);
        assertArrayEquals(a, cache.readValidated(AssetTransfer.hash(a)).orElseThrow());
        cache.writeValidated(AssetTransfer.hash(d), d);
        assertTrue(cache.readValidated(AssetTransfer.hash(b)).isEmpty());
        for (byte[] kept : List.of(a, c, d)) assertArrayEquals(kept, cache.readValidated(AssetTransfer.hash(kept)).orElseThrow());
        assertEquals(6 * 1024, ownedBytes(directory));
        assertEquals(3, names(directory).size());
    }

    @Test void pruningOnlyManagesOrdinaryHashNamedFiles() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("cache"));
        byte[] older = payload(1, 2048), newer = payload(2, 2048), incoming = payload(3, 2048);
        Files.write(entry(directory, older), older); Files.write(entry(directory, newer), newer);
        timestamp(directory, older, 1000); timestamp(directory, newer, 2000);
        Path unrelated = directory.resolve("private-model.bbmodel"); Files.write(unrelated, new byte[12_000]);
        Path nested = Files.createDirectory(directory.resolve("f".repeat(64) + ".bbmodel"));
        Path child = Files.writeString(nested.resolve("owned-by-user.txt"), "keep");
        Path emptyOwned = Files.createFile(directory.resolve("e".repeat(64) + ".bbmodel"));
        var cache = new ServerModelCache(directory, 4096); cache.writeValidated(AssetTransfer.hash(incoming), incoming);
        assertFalse(Files.exists(entry(directory, older))); assertTrue(Files.exists(entry(directory, newer)));
        assertFalse(Files.exists(emptyOwned)); assertEquals(4096, ownedBytes(directory));
        assertEquals(12_000, Files.size(unrelated)); assertEquals("keep", Files.readString(child));
    }

    @Test void aSmallOldFileEnumeratedLastIsEvictedBeforeNewLargerFiles() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("cache"));
        byte[] newest = payload(1, 3072), middle = payload(2, 3072), oldest = payload(3, 1024);
        for (byte[] raw : List.of(newest, middle, oldest)) Files.write(entry(directory, raw), raw);
        timestamp(directory, newest, 3000); timestamp(directory, middle, 2000); timestamp(directory, oldest, 1000);
        List<Path> encounterOrder = List.of(entry(directory, newest), entry(directory, middle), entry(directory, oldest));
        var cache = new ServerModelCache(directory, 4096, ignored -> new DirectoryStream<>() {
            @Override public java.util.Iterator<Path> iterator() { return encounterOrder.iterator(); }
            @Override public void close() { }
        });
        assertArrayEquals(newest, cache.readValidated(AssetTransfer.hash(newest)).orElseThrow());
        assertFalse(Files.exists(entry(directory, oldest)), "Globally oldest must be evicted, even when encountered last");
        assertFalse(Files.exists(entry(directory, middle)), "After oldest eviction, bytes still exceed the budget");
        assertEquals(3072, ownedBytes(directory));
    }

    @Test void entryCountIsBoundedEvenWhenManyTinyFilesFitTheByteBudget() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("cache"));
        for (int i = 0; i <= ServerModelCache.MAX_FILES; i++) {
            byte[] raw = bytes("tiny asset " + i); Files.write(entry(directory, raw), raw);
            timestamp(directory, raw, i + 1000);
        }
        byte[] mostRecent = bytes("tiny asset " + ServerModelCache.MAX_FILES);
        assertArrayEquals(mostRecent, new ServerModelCache(directory).readValidated(AssetTransfer.hash(mostRecent)).orElseThrow());
        assertEquals(ServerModelCache.MAX_FILES, names(directory).size());
        assertFalse(Files.exists(entry(directory, bytes("tiny asset 0"))));
    }

    @Test void rejectsFilesAndDirectoriesInPlaceOfOrdinaryCacheEntriesOrAncestors() throws Exception {
        byte[] raw = bytes("model"); String hash = AssetTransfer.hash(raw);
        Path directory = Files.createDirectory(temporary.resolve("cache"));
        Path target = Files.createDirectory(directory.resolve(hash + ".bbmodel"));
        var cache = new ServerModelCache(directory);
        assertTrue(cache.readValidated(hash).isEmpty());
        assertThrows(IOException.class, () -> cache.writeValidated(hash, raw));
        assertTrue(Files.isDirectory(target)); assertEquals(List.of(hash + ".bbmodel"), names(directory));
        Path ancestor = Files.writeString(temporary.resolve("ordinary-file"), "keep");
        var nested = new ServerModelCache(ancestor.resolve("cache"));
        assertTrue(nested.readValidated(hash).isEmpty());
        assertThrows(IOException.class, () -> nested.writeValidated(hash, raw));
        assertEquals("keep", Files.readString(ancestor));
    }

    @Test void refusesSymbolicEntryWithoutReadingDeletingOrOverwritingItsTarget() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("cache"));
        byte[] raw = bytes("outside model"); String hash = AssetTransfer.hash(raw);
        Path outsideDirectory = Files.createDirectory(temporary.resolve("outside"));
        Path outside = Files.write(outsideDirectory.resolve("outside.bbmodel"), raw);
        Path link = directory.resolve(hash + ".bbmodel"); link(link, outside, outsideDirectory);
        try {
            var cache = new ServerModelCache(directory);
            assertTrue(cache.readValidated(hash).isEmpty());
            assertThrows(IOException.class, () -> cache.writeValidated(hash, raw));
            assertReparseLink(link); assertArrayEquals(raw, Files.readAllBytes(outside));
            byte[] another = bytes("another model"); cache.writeValidated(AssetTransfer.hash(another), another);
            assertReparseLink(link); assertArrayEquals(raw, Files.readAllBytes(outside));
        } finally { Files.delete(link); } // Delete the link itself, never recursively walk its target.
        assertArrayEquals(raw, Files.readAllBytes(outside));
    }

    @Test void refusesSymbolicCacheDirectoryAndLinkedAncestorsBeforeCreatingChildren() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path linked = temporary.resolve("linked"); link(linked, outside, outside);
        byte[] raw = bytes("model"); String hash = AssetTransfer.hash(raw);
        try {
            for (Path directory : List.of(linked, linked.resolve("new-cache"))) {
                var cache = new ServerModelCache(directory);
                assertTrue(cache.readValidated(hash).isEmpty());
                assertThrows(IOException.class, () -> cache.writeValidated(hash, raw));
            }
            assertEquals(List.of(), names(outside)); assertReparseLink(linked);
        } finally { Files.delete(linked); }
        assertTrue(Files.isDirectory(outside)); assertEquals(List.of(), names(outside));
    }

    @Test @Timeout(30) void independentReadersOnlyReceiveCompleteBytesDuringAtomicReplacementAttempts() throws Exception {
        Path directory = temporary.resolve("cache"); byte[] raw = payload(9, 64 * 1024); String hash = AssetTransfer.hash(raw);
        new ServerModelCache(directory).writeValidated(hash, raw);
        AtomicInteger hits = new AtomicInteger(); var workers = Executors.newFixedThreadPool(3);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            tasks.add(() -> {
                var writer = new ServerModelCache(directory);
                for (int i = 0; i < 20; i++) {
                    try { writer.writeValidated(hash, raw); }
                    catch (java.nio.file.AccessDeniedException openWindowsHandle) {
                        if (!System.getProperty("os.name").startsWith("Windows")) throw openWindowsHandle;
                        // Windows may deny replacing an open read handle. Failure must retain complete prior bytes.
                        assertArrayEquals(raw, writer.readValidated(hash).orElseThrow());
                    }
                }
                return null;
            });
            for (int reader = 0; reader < 2; reader++) tasks.add(() -> {
                var cache = new ServerModelCache(directory);
                for (int i = 0; i < 50; i++) cache.readValidated(hash).ifPresent(bytes -> { assertArrayEquals(raw, bytes); hits.incrementAndGet(); });
                return null;
            });
            for (var result : workers.invokeAll(tasks)) result.get();
        } finally { workers.shutdownNow(); }
        assertTrue(hits.get() > 0); assertEquals(List.of(hash + ".bbmodel"), names(directory));
        assertArrayEquals(raw, new ServerModelCache(directory).readValidated(hash).orElseThrow());
    }

    @Test @Timeout(15) void interruptedWritesLeaveNoPublishedHalfModelOrTemporaryFile() throws Exception {
        Path directory = temporary.resolve("cache"); byte[] raw = payload(5, 64 * 1024); String hash = AssetTransfer.hash(raw);
        var cache = new ServerModelCache(directory); var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                Thread.currentThread().interrupt();
                try { assertThrows(IOException.class, () -> cache.writeValidated(hash, raw)); }
                finally { Thread.interrupted(); }
            }).get();
        } finally { worker.shutdownNow(); }
        assertEquals(List.of(), names(directory));
        cache.writeValidated(hash, raw);
        assertArrayEquals(raw, cache.readValidated(hash).orElseThrow());
    }

    @Test void aTargetReplacedWithADirectoryDuringWriteIsPreservedAndTheTemporaryIsRemoved() throws Exception {
        Path directory = temporary.resolve("cache"); byte[] raw = bytes("complete model"); String hash = AssetTransfer.hash(raw);
        Path target = directory.resolve(hash + ".bbmodel"); AtomicInteger passes = new AtomicInteger();
        var cache = new ServerModelCache(directory, 4096, path -> {
            if (passes.getAndIncrement() == 0) Files.createDirectory(target);
            return Files.newDirectoryStream(path);
        });
        assertThrows(IOException.class, () -> cache.writeValidated(hash, raw));
        assertTrue(Files.isDirectory(target)); assertEquals(List.of(hash + ".bbmodel"), names(directory));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] payload(int value, int length) { byte[] raw = new byte[length]; Arrays.fill(raw, (byte) value); return raw; }
    private static Path entry(Path directory, byte[] raw) { return directory.resolve(AssetTransfer.hash(raw) + ".bbmodel"); }
    private static void timestamp(Path directory, byte[] raw, long time) throws IOException {
        Files.setLastModifiedTime(entry(directory, raw), FileTime.fromMillis(time));
    }
    private static List<String> names(Path directory) throws IOException {
        try (var entries = Files.list(directory)) { return entries.map(path -> path.getFileName().toString()).sorted().toList(); }
    }
    private static long ownedBytes(Path directory) throws IOException {
        long total = 0;
        try (var entries = Files.newDirectoryStream(directory, "*.bbmodel")) {
            for (Path entry : entries) if (entry.getFileName().toString().matches("[0-9a-f]{64}\\.bbmodel")
                    && Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) total += Files.size(entry);
        }
        return total;
    }
    private static void assertReparseLink(Path link) throws IOException {
        assertTrue(Files.isSymbolicLink(link) || !link.toRealPath().equals(link.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS)));
    }
    private static void link(Path link, Path target, Path junctionTarget) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
            // Windows junctions need no symlink privilege and exercise the same unsafe-path guard.
            String command = "New-Item -ItemType Junction -Path " + quote(link)
                    + " -Value " + quote(junctionTarget) + " -ErrorAction Stop | Out-Null";
            var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IOException("Junction creation timed out");
            }
            try (var output = process.getInputStream()) {
                assertEquals(0, process.exitValue(), new String(output.readNBytes(2048), StandardCharsets.UTF_8));
            }
            assertReparseLink(link);
        }
    }
    private static String quote(Path path) { return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'"; }
}
