package com.simmc.meplayeractions.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerModelCatalogSnapshotTest {
    @Test
    void olderDirectoriesRetainUnknownLocationAndPublicationWithoutLosingCommandAuthority() {
        ServerModelCatalogSnapshot catalogue = authorized("legacy");
        ServerModelCatalogSnapshot.Model model = catalogue.models().getFirst();
        assertEquals(new ServerModelCatalogSnapshot.Model("legacy", "legacy"), model);
        assertEquals("unknown", model.source());
        assertEquals("", model.folder());
        assertNull(model.clientResource());
        assertEquals("meplayeractions disguise legacy", catalogue.command("legacy", true, 0).orElseThrow());
    }

    @Test
    void directoryMetadataIsPreservedWithoutBecomingACommandOrDownloadRequest() {
        JsonObject packet = chunk(1, 0, 1, true, false, "safe");
        JsonObject entry = packet.getAsJsonArray("models").get(0).getAsJsonObject();
        entry.addProperty("source", "own");
        entry.addProperty("folder", "服装/冬季");
        entry.addProperty("clientResource", true);
        ServerModelCatalogSnapshot catalogue = new ServerModelCatalogSnapshot();
        assertTrue(catalogue.accept(packet));
        assertEquals(new ServerModelCatalogSnapshot.Model("safe", "safe", "own", "服装/冬季", true),
                catalogue.models().getFirst());
        assertEquals(catalogue.models().getFirst(), catalogue.model("safe").orElseThrow());
        assertTrue(catalogue.model("/服装/冬季").isEmpty());
        assertTrue(catalogue.model(null).isEmpty());
        assertEquals("meplayeractions disguise safe", catalogue.command("safe", true, 0).orElseThrow());
    }

    @Test
    void anExplicitMissingClientResourceStillAllowsTheRegisteredModelEngineDisguise() {
        JsonObject packet = chunk(1, 0, 1, true, false, "me_only");
        JsonObject entry = packet.getAsJsonArray("models").get(0).getAsJsonObject();
        entry.addProperty("source", "modelengine");
        entry.addProperty("folder", "boss");
        entry.addProperty("clientResource", false);
        ServerModelCatalogSnapshot catalogue = new ServerModelCatalogSnapshot();
        assertTrue(catalogue.accept(packet));
        assertEquals(Boolean.FALSE, catalogue.models().getFirst().clientResource());
        assertEquals("meplayeractions disguise me_only", catalogue.command("me_only", true, 0).orElseThrow());
    }

    @Test
    void invalidDirectoryLocationsCannotReplaceAValidSnapshot() {
        for (String folder : List.of("/absolute", "C:/models", "a\\b", "a/../b", "a/./b", "a//b", "trailing/",
                "a\nmodel", "x".repeat(257), "a/".repeat(16) + "a")) {
            ServerModelCatalogSnapshot catalogue = authorized("old");
            JsonObject packet = chunk(2, 0, 1, true, false, "new");
            JsonObject entry = packet.getAsJsonArray("models").get(0).getAsJsonObject();
            entry.addProperty("source", "own");
            entry.addProperty("folder", folder);
            assertThrows(IllegalArgumentException.class, () -> catalogue.accept(packet), folder);
            assertTrue(catalogue.contains("old"));
        }
        assertTrue(ServerModelCatalogSnapshot.validFolder("a/".repeat(15) + "a"));
        assertTrue(ServerModelCatalogSnapshot.validFolder("folders/unclassified"));
    }

    @Test
    void invalidOptionalFieldTypesAndSourcesAreRejectedBeforePublication() {
        for (String field : List.of("source", "folder", "clientResource")) {
            JsonObject packet = chunk(1, 0, 1, true, false, "safe");
            JsonObject entry = packet.getAsJsonArray("models").get(0).getAsJsonObject();
            entry.addProperty(field, 123);
            assertThrows(IllegalArgumentException.class, () -> new ServerModelCatalogSnapshot().accept(packet), field);
        }
        JsonObject packet = chunk(1, 0, 1, true, false, "safe");
        packet.getAsJsonArray("models").get(0).getAsJsonObject().addProperty("source", "filesystem");
        assertThrows(IllegalArgumentException.class, () -> new ServerModelCatalogSnapshot().accept(packet));
    }

    @Test
    void repeatedFragmentsCannotChangeTheSourceFolderOrPublicationHint() {
        JsonObject packet = chunk(1, 0, 2, true, false, "first");
        JsonObject entry = packet.getAsJsonArray("models").get(0).getAsJsonObject();
        entry.addProperty("source", "own");
        entry.addProperty("folder", "one");
        entry.addProperty("clientResource", true);
        ServerModelCatalogSnapshot catalogue = new ServerModelCatalogSnapshot();
        assertFalse(catalogue.accept(packet));
        JsonObject changed = packet.deepCopy();
        changed.getAsJsonArray("models").get(0).getAsJsonObject().addProperty("folder", "two");
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(changed));
        assertTrue(catalogue.accept(chunk(1, 1, 2, true, false, "last")));
        assertEquals("one", catalogue.models().getFirst().folder());
    }

    @Test
    void fragmentsPublishInDirectoryOrderOnlyAfterTheWholeRevisionArrives() {
        ServerModelCatalogSnapshot catalogue = new ServerModelCatalogSnapshot();
        assertFalse(catalogue.accept(chunk(1, 1, 2, true, false, "ysm_02_jk")));
        assertTrue(catalogue.receiving());
        assertFalse(catalogue.ready());
        assertTrue(catalogue.models().isEmpty());
        assertFalse(catalogue.contains("ysm_02_jk"));
        assertTrue(catalogue.accept(chunk(1, 0, 2, true, false, "ysm_01_jk")));
        assertTrue(catalogue.ready());
        assertFalse(catalogue.receiving());
        assertEquals(1, catalogue.revision());
        assertEquals(List.of("ysm_01_jk", "ysm_02_jk"),
                catalogue.models().stream().map(ServerModelCatalogSnapshot.Model::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> catalogue.models().clear());
    }

    @Test
    void aReplacementSuspendsOldButtonsBeforeItsFinalFragmentCommits() {
        ServerModelCatalogSnapshot catalogue = authorized("old_model");
        long display = catalogue.displayRevision();
        assertTrue(catalogue.canRequest("old_model", true, 0));
        assertFalse(catalogue.accept(chunk(2, 0, 2, true, false, "new_first")));
        assertTrue(catalogue.displayRevision() > display);
        assertFalse(catalogue.canRequest("old_model", true, 0));
        assertTrue(catalogue.models().isEmpty());
        long receiving = catalogue.displayRevision();
        assertTrue(catalogue.accept(chunk(2, 1, 2, true, false, "new_last")));
        assertTrue(catalogue.displayRevision() > receiving);
        assertFalse(catalogue.contains("old_model"));
        assertTrue(catalogue.contains("new_last"));
    }

    @Test
    void aPermissionRevocationCanReplaceAnUnfinishedLargerCatalogueImmediately() {
        ServerModelCatalogSnapshot catalogue = authorized("ysm_01_jk");
        catalogue.accept(chunk(2, 0, 2, true, false, "ysm_02_jk"));
        assertTrue(catalogue.accept(chunk(3, 0, 1, false, false)));
        assertTrue(catalogue.ready());
        assertFalse(catalogue.canDisguise());
        assertTrue(catalogue.models().isEmpty());
        assertFalse(catalogue.accept(chunk(2, 1, 2, true, false, "ysm_03_jk")));
        assertFalse(catalogue.contains("ysm_03_jk"));
    }

    @Test
    void duplicateAndStalePacketsDoNotRestartOrAppendToThePublishedDirectory() {
        ServerModelCatalogSnapshot catalogue = new ServerModelCatalogSnapshot();
        JsonObject first = chunk(2, 0, 2, true, false, "first");
        catalogue.accept(first);
        long display = catalogue.displayRevision();
        assertFalse(catalogue.accept(first));
        assertEquals(display, catalogue.displayRevision());
        assertFalse(catalogue.accept(chunk(1, 0, 1, true, false, "stale")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(2, 0, 2, true, false, "changed")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(2, 1, 2, true, false, "first")));
        assertTrue(catalogue.accept(chunk(2, 1, 2, true, false, "second")));
        assertFalse(catalogue.accept(first));
        assertEquals(List.of("first", "second"),
                catalogue.models().stream().map(ServerModelCatalogSnapshot.Model::id).toList());
    }

    @Test
    void inconsistentFragmentsCannotModifyThePartialSnapshot() {
        ServerModelCatalogSnapshot catalogue = new ServerModelCatalogSnapshot();
        catalogue.accept(chunk(1, 0, 2, true, false, "first"));
        long display = catalogue.displayRevision();
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 1, 3, true, false, "third")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 1, 2, true, true, "second")));
        assertEquals(display, catalogue.displayRevision());
        assertTrue(catalogue.accept(chunk(1, 1, 2, true, false, "second")));
        assertFalse(catalogue.truncated());
    }

    @Test
    void malformedIdsAndUnauthorizedEntriesNeverPublishCommandArguments() {
        for (String id : List.of("", "../model", "foo bar", "foo;op", "ysm:test", "model\nhelp", "UPPER",
                "x".repeat(65))) {
            ServerModelCatalogSnapshot catalogue = authorized("safe");
            assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(2, 0, 1, true, false, id)), id);
            assertTrue(catalogue.contains("safe"), "Validation must finish before replacing the old directory");
        }
        ServerModelCatalogSnapshot catalogue = new ServerModelCatalogSnapshot();
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 0, 1, false, false, "safe")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 0, 2, true, false)));
        assertThrows(IllegalArgumentException.class,
                () -> catalogue.accept(chunk(1, 0, 1, true, false, "same", "same")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 0, 4097, true, false, "safe")));
        assertTrue(catalogue.models().isEmpty());
        assertFalse(catalogue.ready());
    }

    @Test
    void combinedModelBudgetIsEnforcedAcrossAllFragmentsAndCanBeSuperseded() {
        ServerModelCatalogSnapshot catalogue = new ServerModelCatalogSnapshot();
        for (int batch = 0; batch < 8; batch++) {
            String[] ids = new String[512];
            for (int index = 0; index < ids.length; index++) {
                ids[index] = "model_" + (batch * 512 + index);
            }

            assertFalse(catalogue.accept(chunk(1, batch, 9, true, true, ids)));
        }
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 8, 9, true, true, "too_many")));
        assertTrue(catalogue.models().isEmpty());
        assertTrue(catalogue.accept(chunk(2, 0, 1, true, true, "bounded")));
        assertTrue(catalogue.truncated());
        assertTrue(catalogue.contains("bounded"));
    }

    @Test
    void resetDiscardsOldAuthorityAndAllowsTheNextSessionsRevisionOne() {
        ServerModelCatalogSnapshot catalogue = authorized("old");
        catalogue.command("old", true, 100);
        catalogue.accept(chunk(2, 0, 2, true, false, "partial"));
        long display = catalogue.displayRevision();
        catalogue.reset();
        assertTrue(catalogue.displayRevision() > display);
        assertFalse(catalogue.ready());
        assertFalse(catalogue.canDisguise());
        assertFalse(catalogue.receiving());
        assertEquals(0, catalogue.revision());
        assertFalse(catalogue.contains("old"));
        assertTrue(catalogue.accept(chunk(1, 0, 1, true, false, "new")));
        assertEquals("meplayeractions disguise new", catalogue.command("new", true, 101).orElseThrow());
    }

    @Test
    void useBuildsOnlyTheFixedCommandForALiveAuthorizedSelectionWithOneSharedRateLimit() {
        ServerModelCatalogSnapshot catalogue = authorized("ysm_01_jk", "ysm_02_jk");
        assertTrue(catalogue.command("unknown", true, 0).isEmpty());
        assertTrue(catalogue.command("ysm_01_jk", false, 0).isEmpty());
        assertTrue(catalogue.command("ysm_01_jk extra", true, 0).isEmpty());
        assertEquals("meplayeractions disguise ysm_01_jk", catalogue.command("ysm_01_jk", true, 0).orElseThrow());
        assertFalse(catalogue.canRequest("ysm_02_jk", true, ServerModelCatalogSnapshot.COMMAND_INTERVAL - 1));
        assertTrue(catalogue.command("ysm_02_jk", true, 100).isEmpty());
        assertEquals("meplayeractions disguise ysm_02_jk",
                catalogue.command("ysm_02_jk", true, ServerModelCatalogSnapshot.COMMAND_INTERVAL).orElseThrow());
        catalogue.accept(chunk(2, 0, 1, false, false));
        assertTrue(catalogue.command("ysm_02_jk", true, 2 * ServerModelCatalogSnapshot.COMMAND_INTERVAL).isEmpty());
    }

    private static ServerModelCatalogSnapshot authorized(String... ids) {
        ServerModelCatalogSnapshot result = new ServerModelCatalogSnapshot();
        assertTrue(result.accept(chunk(1, 0, 1, true, false, ids)));
        return result;
    }

    private static JsonObject chunk(long revision, int index, int count, boolean canDisguise, boolean truncated,
            String... ids) {
        JsonObject result = WireJson.envelope("server_model_catalog");
        result.addProperty("revision", revision);
        result.addProperty("index", index);
        result.addProperty("count", count);
        result.addProperty("canDisguise", canDisguise);
        result.addProperty("truncated", truncated);
        JsonArray models = new JsonArray();
        for (String id : ids) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("label", id);
            models.add(entry);
        }
        result.add("models", models);
        return result;
    }
}
