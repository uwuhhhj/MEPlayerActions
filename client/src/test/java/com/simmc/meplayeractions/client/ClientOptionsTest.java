package com.simmc.meplayeractions.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class ClientOptionsTest {
    @TempDir Path directory;

    @Test void removedServerBuiltinDoesNotSilentlyEnableDefaultAndFavoritesPersist() throws Exception {
        Path path=directory.resolve("options.json");
        Files.writeString(path,"{\"localAppearance\":{\"enabled\":true,\"modelId\":\"ysm_02_jk\",\"scale\":1,\"offsetX\":0,\"offsetY\":0,\"offsetZ\":0}}");
        var options=new ClientOptions(path);
        assertEquals(LocalAppearanceSettings.defaults(),options.localAppearance());
        options.showModelIds=true; options.toggleFavorite("openysm_default"); options.toggleFavorite("local:sample.bbmodel");
        options.toggleFavorite("local:../escape.bbmodel"); options.save();
        var loaded=new ClientOptions(path); assertTrue(loaded.showModelIds);
        assertTrue(loaded.isFavorite("openysm_default")); assertTrue(loaded.isFavorite("local:sample.bbmodel"));
        assertFalse(loaded.isFavorite("local:../escape.bbmodel"));
    }

    @Test void missingAndLegacyFilesDoNotEnableIndependentAppearance() throws Exception {
        Path path=directory.resolve("options.json");
        assertEquals(LocalAppearanceSettings.defaults(),new ClientOptions(path).localAppearance());
        Files.writeString(path,"{\"enabled\":false,\"showSelf\":false,\"followServerTimeline\":true,\"interpolationTicks\":4}");
        var old=new ClientOptions(path);
        assertFalse(old.enabled);assertTrue(old.showSelf);assertTrue(old.hideVanillaPlayer);assertTrue(old.hideVanillaEquipment);
        assertTrue(old.followServerTimeline);assertEquals(4,old.interpolationTicks);
        assertEquals(LocalAppearanceSettings.defaults(),old.localAppearance());
        old.save();
        assertEquals(LocalAppearanceSettings.defaults(),new ClientOptions(path).localAppearance());
    }

    @Test void saveAndFreshLoadRetainAllAppearanceChoicesAndOtherOptions() throws Exception {
        Path path=directory.resolve("config").resolve("options.json");
        var options=new ClientOptions(path);
        options.enabled=false;options.showSelf=false;options.interpolationTicks=5;options.followServerTimeline=true;
        for(String model:List.of("openysm_default","ysm:sample","local:sample.bbmodel","local:我的模型.bbmodel")) {
            var appearance=new LocalAppearanceSettings(true,model,1.375f,-.75,1.25,.5);
            options.setLocalAppearance(appearance);options.save();
            var reloaded=new ClientOptions(path);
            assertEquals(appearance,reloaded.localAppearance());
            assertFalse(reloaded.enabled);assertFalse(reloaded.showSelf);assertTrue(reloaded.followServerTimeline);assertEquals(5,reloaded.interpolationTicks);
            // The saved file is usable JSON and stores the user's selections as values.
            JsonObject saved=JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("localAppearance");
            assertTrue(saved.get("enabled").getAsBoolean());assertEquals(model,saved.get("modelId").getAsString());
            assertEquals(1.375f,saved.get("scale").getAsFloat());
        }
    }

    @Test void disablingAppearancePersistsItsSelectedModelAndTransform() {
        Path path=directory.resolve("options.json");var options=new ClientOptions(path);
        var disabled=new LocalAppearanceSettings(false,"local:sample.bbmodel",.5f,2,-1,3);
        options.setLocalAppearance(disabled);options.save();
        assertEquals(disabled,new ClientOptions(path).localAppearance());
    }

    @Test void aReloadReadsChangesMadeByAnotherOptionsInstance() {
        Path path=directory.resolve("options.json");var initial=new ClientOptions(path);
        initial.enabled=false;initial.showSelf=false;initial.followServerTimeline=true;initial.interpolationTicks=4;
        initial.setLocalAppearance(new LocalAppearanceSettings(true,"openysm_default",1,0,0,0));initial.save();
        var changed=new ClientOptions(path);var desired=new LocalAppearanceSettings(false,"ysm:sample",2,.25,-.5,.75);
        changed.enabled=true;changed.showSelf=true;changed.followServerTimeline=false;changed.interpolationTicks=1;
        changed.setLocalAppearance(desired);changed.save();
        initial.reloadLocalAppearance();
        assertEquals(desired,initial.localAppearance());
        assertFalse(initial.enabled);assertFalse(initial.showSelf);assertTrue(initial.followServerTimeline);assertEquals(4,initial.interpolationTicks);
        assertEquals(desired,new ClientOptions(path).localAppearance());
    }

    @Test void reloadingAfterFileDeletionOrCorruptionDisablesThePreviousAppearance() throws Exception {
        Path path=directory.resolve("options.json");var options=new ClientOptions(path);
        options.setLocalAppearance(new LocalAppearanceSettings(true,"openysm_default",1,0,0,0));options.save();
        Files.writeString(path,"invalid JSON");options.reloadLocalAppearance();
        assertEquals(LocalAppearanceSettings.defaults(),options.localAppearance());
        options.setLocalAppearance(new LocalAppearanceSettings(true,"openysm_default",1,0,0,0));
        Files.delete(path);options.reloadLocalAppearance();
        assertEquals(LocalAppearanceSettings.defaults(),options.localAppearance());
    }

    @Test void malformedOrUnsafeAppearanceDoesNotAccidentallyEnableIt() throws Exception {
        Path path=directory.resolve("options.json");
        String valid="{\"enabled\":true,\"modelId\":\"openysm_default\",\"scale\":1,\"offsetX\":0,\"offsetY\":0,\"offsetZ\":0}";
        for(String bad:List.of(valid.replace("\"scale\":1","\"scale\":0"),valid.replace("\"scale\":1","\"scale\":1e300"),
                valid.replace("\"offsetX\":0","\"offsetX\":33"),valid.replace("\"offsetY\":0","\"offsetY\":-33"),
                valid.replace("\"offsetZ\":0","\"offsetZ\":\"NaN\""),valid.replace("openysm_default","local:../escape.bbmodel"),
                valid.replace("\"enabled\":true","\"enabled\":\"true\""),valid.replace("\"scale\":1","\"scale\":\"1\""),
                valid.replace(",\"offsetZ\":0",""),
                "null","[]","\"invalid\"")) {
            Files.writeString(path,"{\"enabled\":false,\"localAppearance\":"+bad+"}");
            var loaded=new ClientOptions(path);
            assertEquals(LocalAppearanceSettings.defaults(),loaded.localAppearance(),bad);
            assertFalse(loaded.enabled,"Existing local animation choice survives invalid appearance");
        }
    }
    @Test void modelProfilesPreserveAuthoredVariablesSkinsAndRadiosWithoutEnablingModel() {
        Path path = directory.resolve("options.json"); var options = new ClientOptions(path);
        var profile = new ClientOptions.ModelProfile("blue", Map.of("v.player_size", 3d, "variable.player_eye", -100d,
                "v.roaming.red_bow_headdress", 1d, "v.眼睛.位置٢", -3d), Map.of("eye_config:0", 2));
        assertTrue(options.updateModelProfile("ysm:sample", profile)); options.localActionLocked = true; options.save();
        var loaded = new ClientOptions(path);
        assertEquals(profile, loaded.modelProfile("ysm:sample")); assertTrue(loaded.localActionLocked);
        assertFalse(loaded.localAppearance().enabled());
        assertEquals(Map.of(), loaded.modelProfile("openysm_default").variables());
        assertThrows(UnsupportedOperationException.class, () -> loaded.modelProfile("ysm:sample").variables().put("variable.x", 1d));
        assertTrue(loaded.resetModelProfile("ysm:sample"));
        assertEquals(ClientOptions.ModelProfile.defaults(), new ClientOptions(path).modelProfile("ysm:sample"));
    }

    @Test void profileValidationRejectsNonNumericAndUnsafeState() {
        for (Map<String, Double> variables : List.of(Map.of("query.x", 1d), Map.of("variable.x", Double.NaN),
                Map.of("variable.x", 1_000_001d), Map.of("temp.x", 1d)))
            assertThrows(IllegalArgumentException.class, () -> new ClientOptions.ModelProfile("", variables, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClientOptions.ModelProfile("../other", Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClientOptions.ModelProfile("", Map.of(), Map.of("x", 64)));
    }

    @Test void tooManyProfilesAndTotalByteBudgetPreserveLastSavedFile() throws Exception {
        Path path = directory.resolve("options.json"); var options = new ClientOptions(path);
        for (int i = 0; i < ClientOptions.MAX_MODEL_PROFILES; i++)
            assertTrue(options.updateModelProfile("ysm:model" + i, ClientOptions.ModelProfile.defaults()));
        byte[] last = Files.readAllBytes(path);
        assertFalse(options.updateModelProfile("ysm:excess", ClientOptions.ModelProfile.defaults()));
        assertArrayEquals(last, Files.readAllBytes(path));
        Map<String, Double> large = new LinkedHashMap<>();
        for (int i = 0; i < 128; i++) large.put("variable." + "a".repeat(105) + i, 123_456.123456d);
        boolean rejected = false;
        for (int i = 0; i < 32; i++) {
            byte[] before = Files.readAllBytes(path);
            var old = options.modelProfile("ysm:model" + i);
            if (!options.updateModelProfile("ysm:model" + i, new ClientOptions.ModelProfile("", large, Map.of()))) {
                assertEquals(old, options.modelProfile("ysm:model" + i));
                assertArrayEquals(before, Files.readAllBytes(path)); rejected = true; break;
            }
        }
        assertTrue(rejected); assertTrue(Files.size(path) < 65_536);
    }

    @Test void invalidProfileSectionFailsClosedWithoutChangingRenderOptions() throws Exception {
        Path path = directory.resolve("options.json");
        Files.writeString(path, "{\"enabled\":false,\"modelProfiles\":{\"ysm:sample\":{\"textureId\":\"blue\",\"variables\":{\"query.x\":1}}}}");
        var loaded = new ClientOptions(path); assertFalse(loaded.enabled);
        assertEquals(ClientOptions.ModelProfile.defaults(), loaded.modelProfile("ysm:sample"));
    }

    @Test void ioFailureRollsBackProfileInMemory() throws Exception {
        Path blocked = directory.resolve("folder"); Files.createDirectory(blocked);
        var options = new ClientOptions(blocked);
        assertFalse(options.updateModelProfile("ysm:sample", new ClientOptions.ModelProfile("blue", Map.of("v.x", 2d), Map.of())));
        assertEquals(ClientOptions.ModelProfile.defaults(), options.modelProfile("ysm:sample"));
    }

    @Test void legacyOptionsGainWheelDefaultsWithoutChangingPrivateModelChoices() throws Exception {
        Path path = directory.resolve("options.json");
        var saved = new ClientOptions(path);
        var appearance = new LocalAppearanceSettings(true, "local:我的模型.bbmodel", 1.5f, 1, 2, 3);
        var profile = new ClientOptions.ModelProfile("blue", Map.of("v.player_size", 1.5d), Map.of());
        saved.setLocalAppearance(appearance); assertTrue(saved.updateModelProfile(appearance.modelId(), profile));
        saved.enabled = false; saved.localActionLocked = true; saved.save();
        JsonObject legacy = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        legacy.remove("wheelPreferences"); Files.writeString(path, legacy.toString());
        var loaded = new ClientOptions(path);
        assertEquals(WheelPreferences.defaults(), loaded.wheelPreferences());
        assertEquals(appearance, loaded.localAppearance()); assertEquals(profile, loaded.modelProfile(appearance.modelId()));
        assertFalse(loaded.enabled); assertTrue(loaded.localActionLocked);
        loaded.save();
        assertEquals(WheelPreferences.defaults(), new ClientOptions(path).wheelPreferences());
        assertEquals(WheelPreferences.defaults(), new ClientOptions(directory.resolve("missing.json")).wheelPreferences());
    }

    @Test void wheelSourcesPagesAndKeepOpenRoundTripIndependentlyOfActionLock() throws Exception {
        Path path = directory.resolve("options.json"); var options = new ClientOptions(path);
        var remembered = new WheelPreferences(WheelPreferences.Source.SERVER, 12, 127, true);
        assertTrue(options.updateWheelPreferences(remembered));
        var loaded = new ClientOptions(path);
        assertEquals(remembered, loaded.wheelPreferences()); assertFalse(loaded.localActionLocked);
        loaded.localActionLocked = true;
        assertTrue(loaded.updateWheelPreferences(loaded.wheelPreferences().withSource(WheelPreferences.Source.CLIENT)
                .withPage(WheelPreferences.Source.CLIENT, 4).withKeepOpen(false)));
        var restored = new ClientOptions(path);
        assertEquals(new WheelPreferences(WheelPreferences.Source.CLIENT, 4, 127, false), restored.wheelPreferences());
        assertTrue(restored.localActionLocked);
        JsonObject wheel = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("wheelPreferences");
        assertEquals("client", wheel.get("source").getAsString()); assertFalse(wheel.get("keepOpen").getAsBoolean());
    }

    @Test void invalidAndFutureWheelFieldsFallBackIndividuallyWithoutErasingValidSiblings() throws Exception {
        Path path = directory.resolve("options.json");
        for (String invalid : List.of("null", "[]", "{}", "true", "\"7\"", "1.5", "-1", "128", "1e300")) {
            Files.writeString(path, "{\"enabled\":false,\"wheelPreferences\":{\"source\":\"server\",\"clientPage\":"
                    + invalid + ",\"serverPage\":9,\"keepOpen\":true}}");
            var loaded = new ClientOptions(path);
            assertEquals(new WheelPreferences(WheelPreferences.Source.SERVER, 0, 9, true), loaded.wheelPreferences(), invalid);
            assertFalse(loaded.enabled);
        }
        Files.writeString(path, "{\"futureVersion\":99,\"wheelPreferences\":{\"source\":\"future-source\","
                + "\"clientPage\":3,\"serverPage\":5,\"keepOpen\":true,\"futureOption\":{\"x\":1}}}");
        assertEquals(new WheelPreferences(WheelPreferences.Source.CLIENT, 3, 5, true), new ClientOptions(path).wheelPreferences());
        for (String invalid : List.of("null", "[]", "{}", "1", "\"true\"")) {
            Files.writeString(path, "{\"wheelPreferences\":{\"source\":\"server\",\"clientPage\":3,\"keepOpen\":" + invalid + "}}");
            assertEquals(new WheelPreferences(WheelPreferences.Source.SERVER, 3, 0, false), new ClientOptions(path).wheelPreferences(), invalid);
        }
    }

    @Test void malformedWheelSectionDoesNotPreventRestoringPrivateAppearanceAndProfiles() throws Exception {
        Path path = directory.resolve("options.json"); var saved = new ClientOptions(path);
        var appearance = new LocalAppearanceSettings(true, "ysm:sample", 2, .5, -.5, 1);
        var profile = new ClientOptions.ModelProfile("blue", Map.of("v.player_size", 2d), Map.of());
        saved.setLocalAppearance(appearance); assertTrue(saved.updateModelProfile(appearance.modelId(), profile));
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        for (String invalid : List.of("null", "[]", "\"invalid\"", "1")) {
            json.add("wheelPreferences", JsonParser.parseString(invalid)); Files.writeString(path, json.toString());
            var loaded = new ClientOptions(path);
            assertEquals(WheelPreferences.defaults(), loaded.wheelPreferences());
            assertEquals(appearance, loaded.localAppearance()); assertEquals(profile, loaded.modelProfile(appearance.modelId()));
        }
    }

    @Test void ioFailureRollsBackWheelMemoryAndLeavesTheBlockingDirectoryUntouched() throws Exception {
        Path path = directory.resolve("options.json"); var options = new ClientOptions(path);
        var previous = new WheelPreferences(WheelPreferences.Source.SERVER, 2, 5, true);
        assertTrue(options.updateWheelPreferences(previous));
        Files.delete(path); Files.createDirectory(path);
        Path sentinel = path.resolve("keep.txt"); Files.writeString(sentinel, "preserve");
        assertFalse(options.updateWheelPreferences(previous.withSource(WheelPreferences.Source.CLIENT).withPage(WheelPreferences.Source.CLIENT, 9)));
        assertEquals(previous, options.wheelPreferences()); assertEquals("preserve", Files.readString(sentinel));
        try (var children = Files.list(directory)) { assertEquals(List.of(path), children.toList()); }
    }
}
