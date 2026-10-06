package com.simmc.meplayeractions.protection;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ResourceSettingsTest {
    @Test void shippedConfigurationMatchesRuntimeDefaults() throws Exception {
        var config=new YamlConfiguration();config.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        assertEquals(ResourceSettings.defaults(),ResourceSettings.fromConfiguration(config));
    }
    @Test void olderConfigurationsReceiveBoundedDefaults() {
        assertEquals(ResourceSettings.defaults(),ResourceSettings.fromConfiguration(new YamlConfiguration()));
        assertEquals(2,ResourceSettings.defaults().tasks().workerThreads());
        assertEquals(600,ResourceSettings.defaults().protection().recoveryTicks());
    }
    @Test void overridesApplyWithoutEditingConfiguration() {
        var config=new YamlConfiguration();config.set("resource-protection.tasks.worker-threads",3);
        config.set("resource-protection.network.global-transfers",20);config.set("resource-protection.models.max-relations",1000);
        config.set("resource-protection.protection.main-budget-millis",1.5);config.set("resource-protection.disk.min-free-bytes",0);
        var settings=ResourceSettings.fromConfiguration(config);assertEquals(3,settings.tasks().workerThreads());
        assertEquals(20,settings.network().globalTransfers());assertEquals(1000,settings.models().maxRelations());
        assertEquals(1.5,settings.protection().mainBudgetMillis());assertEquals(0,settings.disk().minFreeBytes());
    }
    @Test void unsafeLimitsAndContradictoryThresholdsFailBeforeReload() {
        for(String key:new String[]{"tasks.worker-threads","tasks.queue-capacity","network.global-transfers","models.max-relations"}) {
            var config=new YamlConfiguration();config.set("resource-protection."+key,0);
            assertThrows(IllegalArgumentException.class,()->ResourceSettings.fromConfiguration(config),key);
        }
        var overflow=new YamlConfiguration();overflow.set("resource-protection.tasks.queue-capacity",Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->ResourceSettings.fromConfiguration(overflow));
        var threshold=new YamlConfiguration();threshold.set("resource-protection.protection.protected-tps",19);
        assertThrows(IllegalArgumentException.class,()->ResourceSettings.fromConfiguration(threshold));
        threshold.set("resource-protection.protection.protected-tps",15);threshold.set("resource-protection.protection.main-budget-millis",Double.NaN);
        assertThrows(IllegalArgumentException.class,()->ResourceSettings.fromConfiguration(threshold));
    }
    @Test void playerAndGlobalMemoryBudgetsCannotContradict() {
        var config=new YamlConfiguration();config.set("resource-protection.tasks.max-reserved-bytes",1048576);
        assertThrows(IllegalArgumentException.class,()->ResourceSettings.fromConfiguration(config));
        config.set("resource-protection.tasks.per-player-reserved-bytes",1048576);
        assertEquals(1048576,ResourceSettings.fromConfiguration(config).tasks().maxReservedBytes());
    }
    @Test void errorsExposeStableFieldsWithoutArbitraryExceptionMessages() {
        var error=new ResourceError("memory_limit","upload_validate",true,21);
        assertEquals(2,error.retryAfterSeconds());assertEquals("memory_limit",error.json().get("code").getAsString());
        assertEquals(2,error.json().get("retryAfter").getAsInt());assertTrue(new ResourceRejectedException(error).getMessage().contains("memory_limit"));
        assertThrows(IllegalArgumentException.class,()->new ResourceError("../secret","upload",false,0));
        assertThrows(IllegalArgumentException.class,()->new ResourceError("memory_limit","a\npassword",false,0));
    }
}
