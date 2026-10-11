package com.simmc.meplayeractions.client;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ServerModelFolderMetadataTest {
    @TempDir Path temporary;

    @Test void explicitMpaFilesTakePriorityWhileMeDirectoryOnlyClassifiesRemainingModels() throws Exception {
        Path own = temporary.resolve("MPA/models"), engine = temporary.resolve("ME/blueprints");
        file(own, "ysm_01_jk.bbmodel"); file(own, "动物/尾巴/fox.bbmodel");
        file(engine, "boss/ysm_01_jk.bbmodel"); file(engine, "boss/fox.bbmodel");
        file(engine, "酒/azriel.bbmodel"); file(engine, "root_model.bbmodel");
        var metadata = ServerModelFolders.scan(own, engine);
        assertTrue(metadata.ownComplete());
        assertEquals(new ServerModelCatalog.Metadata("own", "", true), metadata.forModel("ysm_01_jk"));
        assertEquals(new ServerModelCatalog.Metadata("own", "动物/尾巴", true), metadata.forModel("fox"));
        assertEquals(new ServerModelCatalog.Metadata("modelengine", "酒", false), metadata.forModel("azriel"));
        assertEquals(new ServerModelCatalog.Metadata("modelengine", "", false), metadata.forModel("root_model"));
        assertEquals(new ServerModelCatalog.Metadata("unknown", "", false), metadata.forModel("registered_without_file"));
    }

    @Test void rootExplicitFileWinsButAmbiguousChildFilesDoNotGuessOrFallBackToMePublication() throws Exception {
        Path own = temporary.resolve("own"), engine = temporary.resolve("engine");
        file(own, "a/same.bbmodel"); file(own, "b/same.bbmodel"); file(engine, "sure/same.bbmodel");
        var ambiguous = ServerModelFolders.scan(own, engine);
        assertEquals(new ServerModelCatalog.Metadata("unknown", "", false), ambiguous.forModel("same"));
        file(own, "same.bbmodel");
        assertEquals(new ServerModelCatalog.Metadata("own", "", true), ServerModelFolders.scan(own, engine).forModel("same"));
    }

    @Test void missingOwnDirectoryKeepsMeFoldersAndExplicitlySaysNoClientResource() throws Exception {
        Path engine = temporary.resolve("engine"); file(engine, "boss/dragon.bbmodel");
        var metadata = ServerModelFolders.scan(temporary.resolve("not-created"), engine);
        assertTrue(metadata.ownComplete());
        assertEquals(new ServerModelCatalog.Metadata("modelengine", "boss", false), metadata.forModel("dragon"));
    }

    @Test void failedOwnScanCannotClaimAbsenceOrInferPriorityFromAnotherSource() {
        var own = new ExplicitModelFiles.Catalog(List.of(), "model_complexity", "budget");
        var engine = new ExplicitModelFiles.Catalog(List.of(new ExplicitModelFiles.Entry("boss", temporary.resolve("boss.bbmodel"), "bosses")), "ready", "done");
        var metadata = ServerModelFolders.classify(own, engine);
        assertFalse(metadata.ownComplete());
        assertEquals(new ServerModelCatalog.Metadata("unknown", "", null), metadata.forModel("boss"));
        assertEquals(new ServerModelCatalog.Metadata("unknown", "", null), metadata.forModel("absent"));
    }

    @Test void invalidEngineDirectoryDoesNotEraseConfirmedOwnFiles() throws Exception {
        Path own = temporary.resolve("own"), engine = temporary.resolve("not-a-folder");
        file(own, "fox.bbmodel"); Files.writeString(engine, "not a directory");
        var metadata = ServerModelFolders.scan(own, engine);
        assertEquals(new ServerModelCatalog.Metadata("own", "", true), metadata.forModel("fox"));
        assertEquals(new ServerModelCatalog.Metadata("unknown", "", false), metadata.forModel("me_unknown"));
    }

    @Test void directoryWorkIsSharedQueuedAndDoesNotInspectFilesOnTheViewerThread() throws Exception {
        Path own = temporary.resolve("own"); var tasks = new ArrayDeque<Job>();
        var folders = new ServerModelFolders(own, null, (work, success, failure) -> tasks.add(new Job(work, success, failure)));
        folders.refresh(100);
        for (int viewer = 0; viewer < 1000; viewer++) folders.refresh(200);
        assertEquals(1, tasks.size()); assertFalse(folders.snapshot().ownComplete());
        file(own, "created_after_scheduling.bbmodel"); tasks.remove().complete();
        assertEquals(Boolean.TRUE, folders.snapshot().forModel("created_after_scheduling").clientResource());
        folders.refresh(200); assertEquals(1, tasks.size()); tasks.remove().complete();
        var stable = folders.snapshot(); folders.refresh(299); assertTrue(tasks.isEmpty()); assertSame(stable, folders.snapshot());
    }

    @Test void failedRefreshClearsStalePublicationAndCompletionAfterCloseCannotReplaceSnapshot() throws Exception {
        Path own = temporary.resolve("own"); file(own, "fox.bbmodel"); var tasks = new ArrayDeque<Job>();
        var folders = new ServerModelFolders(own, null, (work, success, failure) -> tasks.add(new Job(work, success, failure)));
        folders.refresh(100); tasks.remove().complete(); assertEquals(Boolean.TRUE, folders.snapshot().forModel("fox").clientResource());
        folders.refresh(200); tasks.remove().failure.run();
        assertNull(folders.snapshot().forModel("fox").clientResource());
        folders.refresh(300); var beforeClose = folders.snapshot(); folders.close(); tasks.remove().complete();
        assertSame(beforeClose, folders.snapshot()); folders.refresh(400); assertTrue(tasks.isEmpty());
    }

    @Test void completeChangedSnapshotNotifiesPublicationWithdrawalOnlyOnceAndPreservesExplicitFiles() throws Exception {
        Path own = temporary.resolve("own"), engine = temporary.resolve("engine");
        file(own, "kept.bbmodel"); file(own, "removed.bbmodel"); file(engine, "boss/removed.bbmodel");
        var tasks = new ArrayDeque<Job>(); var changes = new ArrayList<ServerModelCatalog.MetadataSnapshot>();
        var folders = new ServerModelFolders(own, engine,
                (work, success, failure) -> tasks.add(new Job(work, success, failure)), changes::add);
        folders.refresh(100); tasks.remove().complete(); assertEquals(1, changes.size());
        assertEquals(Boolean.TRUE, changes.getFirst().forModel("removed").clientResource());
        Files.delete(own.resolve("removed.bbmodel"));
        folders.refresh(200); assertEquals(1, changes.size()); tasks.remove().complete();
        assertEquals(2, changes.size());
        var withdrawal = changes.getLast(); assertTrue(withdrawal.ownComplete());
        assertEquals(Boolean.FALSE, withdrawal.forModel("removed").clientResource());
        assertEquals("modelengine", withdrawal.forModel("removed").source());
        assertEquals(Boolean.TRUE, withdrawal.forModel("kept").clientResource());
        folders.refresh(300); tasks.remove().complete(); assertEquals(2, changes.size());
    }

    @Test void failedOrIncompleteSnapshotCannotNotifyPublicationWithdrawal() throws Exception {
        Path own = temporary.resolve("own"); file(own, "kept.bbmodel");
        var tasks = new ArrayDeque<Job>(); AtomicInteger changes = new AtomicInteger();
        var folders = new ServerModelFolders(own, null,
                (work, success, failure) -> tasks.add(new Job(work, success, failure)), ignored -> changes.incrementAndGet());
        folders.refresh(100); tasks.remove().complete(); assertEquals(1, changes.get());
        folders.refresh(200); tasks.remove().failure.run(); assertEquals(1, changes.get());
        assertNull(folders.snapshot().forModel("kept").clientResource());
        Files.delete(own.resolve("kept.bbmodel")); Files.delete(own); Files.writeString(own, "not a directory");
        folders.refresh(300); tasks.remove().complete(); assertEquals(1, changes.get());
        assertFalse(folders.snapshot().ownComplete());
    }

    @Test void closedFolderIndexCannotNotifyPublicationWithdrawalFromAnOutstandingCompletion() throws Exception {
        var tasks = new ArrayDeque<Job>(); AtomicInteger changes = new AtomicInteger();
        var folders = new ServerModelFolders(temporary.resolve("missing"), null,
                (work, success, failure) -> tasks.add(new Job(work, success, failure)), ignored -> changes.incrementAndGet());
        folders.refresh(100); folders.close(); tasks.remove().complete();
        assertEquals(0, changes.get()); assertFalse(folders.snapshot().ownComplete());
    }

    @Test void rootModelsRemainInCatalogWithOptionalPresentationMetadataAndOwnSortedFirst() {
        var metadata = new ServerModelCatalog.MetadataSnapshot(Map.of(
                "own_root", new ServerModelCatalog.Metadata("own", "", true),
                "own_child", new ServerModelCatalog.Metadata("own", "custom", true),
                "me_child", new ServerModelCatalog.Metadata("modelengine", "boss", false),
                "me_root", new ServerModelCatalog.Metadata("modelengine", "", false)), true);
        var catalog = new ServerModelCatalog(() -> List.of("unknown", "me_root", "me_child", "own_child", "own_root"), () -> metadata);
        catalog.refresh(100); List<JsonObject> entries = entries(catalog.plan(true, 1024));
        assertEquals(List.of("own_root", "own_child", "me_root", "me_child", "unknown"), entries.stream().map(entry -> entry.get("id").getAsString()).toList());
        assertEquals("", entries.getFirst().get("folder").getAsString());
        assertTrue(entries.getFirst().get("clientResource").getAsBoolean());
        assertFalse(entries.getLast().get("clientResource").getAsBoolean());
        assertEquals(Set.of("id", "label", "source", "folder", "clientResource"), entries.getFirst().keySet());
        assertTrue(entries(catalog.plan(false, 1024)).isEmpty());
    }

    @Test void aNewMetadataSnapshotUpdatesSharedPlanWithoutAddingUnregisteredFiles() {
        var snapshot = new ServerModelCatalog.MetadataSnapshot[]{new ServerModelCatalog.MetadataSnapshot(Map.of(), false)};
        AtomicInteger reads = new AtomicInteger();
        var catalog = new ServerModelCatalog(() -> { reads.incrementAndGet(); return List.of("one"); }, () -> snapshot[0]);
        catalog.refresh(100); var before = catalog.plan(true, 1024);
        assertFalse(entries(before).getFirst().has("clientResource"));
        snapshot[0] = new ServerModelCatalog.MetadataSnapshot(Map.of("one", new ServerModelCatalog.Metadata("own", "private", true),
                "not_registered", new ServerModelCatalog.Metadata("own", "", true)), true);
        catalog.refresh(101); var updated = catalog.plan(true, 1024); assertNotSame(before, updated);
        for (int viewer = 0; viewer < 1000; viewer++) catalog.refresh(101);
        assertEquals(2, reads.get()); assertEquals(1, entries(updated).size());
        assertEquals("one", entries(updated).getFirst().get("id").getAsString());
    }

    @Test void catalogLimitRetainsOwnPublishedModelsBeforeAlphabeticalMeModels() {
        var names = new ArrayList<>(IntStream.range(0, 4100).mapToObj(id -> "a" + String.format("%04d", id)).toList()); names.add("z_owned");
        var metadata = new ServerModelCatalog.MetadataSnapshot(Map.of("z_owned", new ServerModelCatalog.Metadata("own", "", true)), true);
        var catalog = new ServerModelCatalog(() -> names, () -> metadata); catalog.refresh(100);
        var entries = entries(catalog.plan(true, 1024)); assertEquals(4096, entries.size());
        assertEquals("z_owned", entries.getFirst().get("id").getAsString());
    }

    @Test void unsafeFolderValuesNeverRevealAbsolutePathsAndLongUnicodeFoldersFitLegacyBudget() {
        for (String invalid : Arrays.asList(null, "../outside", "C:/secrets", "/absolute", "a\\b", "a//b", "a/", "a\nsecret", "a§b", "a/../b", "a/./b", "a".repeat(257), "a/".repeat(16) + "a")) {
            var metadata = new ServerModelCatalog.Metadata("own", invalid, true);
            assertEquals("unknown", metadata.source()); assertEquals("", metadata.folder());
        }
        String id = "x".repeat(64), folder = "模".repeat(256);
        var catalog = new ServerModelCatalog(() -> List.of(id), () -> new ServerModelCatalog.MetadataSnapshot(Map.of(id,
                new ServerModelCatalog.Metadata("own", folder, true)), true));
        catalog.refresh(100); var plan = catalog.plan(true, 1024); assertTrue(plan.packet(0, Long.MAX_VALUE).length <= 1024);
        JsonObject entry = entries(plan).getFirst(); assertEquals("unknown", entry.get("source").getAsString());
        assertEquals("", entry.get("folder").getAsString()); assertTrue(entry.get("clientResource").getAsBoolean());
    }

    private static void file(Path root, String relative) throws Exception {
        Path file = root.resolve(relative); Files.createDirectories(file.getParent());
        // Deliberately not model JSON: metadata must only examine filenames and parent directories.
        Files.writeString(file, "not JSON and never parsed for folder classification");
    }
    private static List<JsonObject> entries(ServerModelCatalog.Plan plan) {
        var entries = new ArrayList<JsonObject>();
        for (int index = 0; index < plan.count(); index++) {
            JsonObject message = JsonParser.parseString(new String(plan.packet(index, 1), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement entry : message.getAsJsonArray("models")) entries.add(entry.getAsJsonObject());
        }
        return entries;
    }
    private record Job(Callable<ServerModelCatalog.MetadataSnapshot> work, Consumer<ServerModelCatalog.MetadataSnapshot> success, Runnable failure) {
        void complete() throws Exception { success.accept(work.call()); }
    }
}
