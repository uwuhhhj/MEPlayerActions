package com.simmc.meplayeractions.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        assertFalse(old.enabled);assertFalse(old.showSelf);assertTrue(old.followServerTimeline);assertEquals(4,old.interpolationTicks);
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
}
