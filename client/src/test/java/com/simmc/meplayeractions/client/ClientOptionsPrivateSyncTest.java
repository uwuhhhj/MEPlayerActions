package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ClientOptionsPrivateSyncTest {
    @TempDir Path directory;
    @Test void missingAndLegacyConfigurationsRemainPrivate() throws Exception {
        Path path=directory.resolve("options.json");
        assertFalse(new ClientOptions(path).privateSyncEnabled);
        Files.writeString(path,"{\"localActionLocked\":true}");
        assertFalse(new ClientOptions(path).privateSyncEnabled);
    }
    @Test void optInAndOptOutPersistWithoutChangingTheSelectedModel() {
        Path path=directory.resolve("options.json");var options=new ClientOptions(path);
        var appearance=new LocalAppearanceSettings(true,"ysm:sample",1.25f,.5,1,-.5);
        options.setLocalAppearance(appearance);options.privateSyncEnabled=true;options.save();
        var enabled=new ClientOptions(path);assertTrue(enabled.privateSyncEnabled);assertEquals(appearance,enabled.localAppearance());
        enabled.privateSyncEnabled=false;enabled.save();
        var disabled=new ClientOptions(path);assertFalse(disabled.privateSyncEnabled);assertEquals(appearance,disabled.localAppearance());
    }
    @Test void stringOrNumericValuesNeverOptIn() throws Exception {
        Path path=directory.resolve("options.json");
        for(String value:java.util.List.of("\"true\"","1","null","{}")) {
            Files.writeString(path,"{\"privateSyncEnabled\":"+value+"}");
            assertFalse(new ClientOptions(path).privateSyncEnabled,value);
        }
    }
}
