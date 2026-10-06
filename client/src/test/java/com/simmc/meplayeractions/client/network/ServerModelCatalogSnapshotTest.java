package com.simmc.meplayeractions.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerModelCatalogSnapshotTest {
    @Test void fragmentsPublishInDirectoryOrderOnlyAfterTheWholeRevisionArrives() {
        var catalogue = new ServerModelCatalogSnapshot();
        assertFalse(catalogue.accept(chunk(1, 1, 2, true, false, "ysm_02_jk")));
        assertTrue(catalogue.receiving()); assertFalse(catalogue.ready()); assertTrue(catalogue.models().isEmpty());
        assertFalse(catalogue.contains("ysm_02_jk"));
        assertTrue(catalogue.accept(chunk(1, 0, 2, true, false, "ysm_01_jk")));
        assertTrue(catalogue.ready()); assertFalse(catalogue.receiving()); assertEquals(1, catalogue.revision());
        assertEquals(List.of("ysm_01_jk", "ysm_02_jk"), catalogue.models().stream().map(ServerModelCatalogSnapshot.Model::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> catalogue.models().clear());
    }

    @Test void aReplacementSuspendsOldButtonsBeforeItsFinalFragmentCommits() {
        var catalogue = authorized("old_model");
        long display = catalogue.displayRevision();
        assertTrue(catalogue.canRequest("old_model", true, 0));
        assertFalse(catalogue.accept(chunk(2, 0, 2, true, false, "new_first")));
        assertTrue(catalogue.displayRevision() > display);
        assertFalse(catalogue.canRequest("old_model", true, 0)); assertTrue(catalogue.models().isEmpty());
        long receiving = catalogue.displayRevision();
        assertTrue(catalogue.accept(chunk(2, 1, 2, true, false, "new_last")));
        assertTrue(catalogue.displayRevision() > receiving);
        assertFalse(catalogue.contains("old_model")); assertTrue(catalogue.contains("new_last"));
    }

    @Test void aPermissionRevocationCanReplaceAnUnfinishedLargerCatalogueImmediately() {
        var catalogue = authorized("ysm_01_jk");
        catalogue.accept(chunk(2, 0, 2, true, false, "ysm_02_jk"));
        assertTrue(catalogue.accept(chunk(3, 0, 1, false, false)));
        assertTrue(catalogue.ready()); assertFalse(catalogue.canDisguise()); assertTrue(catalogue.models().isEmpty());
        assertFalse(catalogue.accept(chunk(2, 1, 2, true, false, "ysm_03_jk")));
        assertFalse(catalogue.contains("ysm_03_jk"));
    }

    @Test void duplicateAndStalePacketsDoNotRestartOrAppendToThePublishedDirectory() {
        var catalogue = new ServerModelCatalogSnapshot();
        JsonObject first = chunk(2, 0, 2, true, false, "first");
        catalogue.accept(first);
        long display = catalogue.displayRevision();
        assertFalse(catalogue.accept(first)); assertEquals(display, catalogue.displayRevision());
        assertFalse(catalogue.accept(chunk(1, 0, 1, true, false, "stale")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(2, 0, 2, true, false, "changed")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(2, 1, 2, true, false, "first")));
        assertTrue(catalogue.accept(chunk(2, 1, 2, true, false, "second")));
        assertFalse(catalogue.accept(first));
        assertEquals(List.of("first", "second"), catalogue.models().stream().map(ServerModelCatalogSnapshot.Model::id).toList());
    }

    @Test void inconsistentFragmentsCannotModifyThePartialSnapshot() {
        var catalogue = new ServerModelCatalogSnapshot();
        catalogue.accept(chunk(1, 0, 2, true, false, "first"));
        long display = catalogue.displayRevision();
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 1, 3, true, false, "third")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 1, 2, true, true, "second")));
        assertEquals(display, catalogue.displayRevision());
        assertTrue(catalogue.accept(chunk(1, 1, 2, true, false, "second")));
        assertFalse(catalogue.truncated());
    }

    @Test void malformedIdsAndUnauthorizedEntriesNeverPublishCommandArguments() {
        for (String id : List.of("", "../model", "foo bar", "foo;op", "ysm:test", "model\nhelp", "UPPER", "x".repeat(65))) {
            var catalogue = authorized("safe");
            assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(2, 0, 1, true, false, id)), id);
            assertTrue(catalogue.contains("safe"), "Validation must finish before replacing the old directory");
        }
        var catalogue = new ServerModelCatalogSnapshot();
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 0, 1, false, false, "safe")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 0, 2, true, false)));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 0, 1, true, false, "same", "same")));
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 0, 4097, true, false, "safe")));
        assertTrue(catalogue.models().isEmpty()); assertFalse(catalogue.ready());
    }

    @Test void combinedModelBudgetIsEnforcedAcrossAllFragmentsAndCanBeSuperseded() {
        var catalogue = new ServerModelCatalogSnapshot();
        for (int batch = 0; batch < 8; batch++) {
            String[] ids = new String[512];
            for (int index = 0; index < ids.length; index++) ids[index] = "model_" + (batch * 512 + index);
            assertFalse(catalogue.accept(chunk(1, batch, 9, true, true, ids)));
        }
        assertThrows(IllegalArgumentException.class, () -> catalogue.accept(chunk(1, 8, 9, true, true, "too_many")));
        assertTrue(catalogue.models().isEmpty());
        assertTrue(catalogue.accept(chunk(2, 0, 1, true, true, "bounded")));
        assertTrue(catalogue.truncated()); assertTrue(catalogue.contains("bounded"));
    }

    @Test void resetDiscardsOldAuthorityAndAllowsTheNextSessionsRevisionOne() {
        var catalogue = authorized("old");
        catalogue.command("old", true, 100);
        catalogue.accept(chunk(2, 0, 2, true, false, "partial"));
        long display = catalogue.displayRevision();
        catalogue.reset();
        assertTrue(catalogue.displayRevision() > display);
        assertFalse(catalogue.ready()); assertFalse(catalogue.canDisguise()); assertFalse(catalogue.receiving());
        assertEquals(0, catalogue.revision()); assertFalse(catalogue.contains("old"));
        assertTrue(catalogue.accept(chunk(1, 0, 1, true, false, "new")));
        assertEquals("meplayeractions disguise new", catalogue.command("new", true, 101).orElseThrow());
    }

    @Test void useBuildsOnlyTheFixedCommandForALiveAuthorizedSelectionWithOneSharedRateLimit() {
        var catalogue = authorized("ysm_01_jk", "ysm_02_jk");
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
        var result = new ServerModelCatalogSnapshot();
        assertTrue(result.accept(chunk(1, 0, 1, true, false, ids))); return result;
    }

    private static JsonObject chunk(long revision, int index, int count, boolean canDisguise, boolean truncated, String... ids) {
        JsonObject result = WireJson.envelope("server_model_catalog");
        result.addProperty("revision", revision); result.addProperty("index", index); result.addProperty("count", count);
        result.addProperty("canDisguise", canDisguise); result.addProperty("truncated", truncated);
        JsonArray models = new JsonArray();
        for (String id : ids) {
            JsonObject entry = new JsonObject(); entry.addProperty("id", id); entry.addProperty("label", id); models.add(entry);
        }
        result.add("models", models); return result;
    }
}
