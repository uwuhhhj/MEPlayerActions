package com.simmc.meplayeractions.me;

import com.simmc.meplayeractions.action.DisguiseOptions;
import com.simmc.meplayeractions.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredModelsTest {
    @Test void modelNamesComeOnlyFromRegistryKeysWithNoMpaModelConfiguration() {
        Settings settings = Settings.load(new YamlConfiguration());
        List<String> registered = RegisteredModels.ids(List.of("dragon", "fox", "dragon", "ysm_02_jk"));
        assertEquals(List.of("dragon", "fox", "ysm_02_jk"), registered);
        assertEquals(List.of("dragon"), DisguiseOptions.suggestions(new String[]{"disguise", "dr"}, registered, List.of()));
        DisguiseOptions options = DisguiseOptions.parse(new String[]{"disguise", "dragon", "scale=0.8"}, settings);
        assertEquals("dragon", options.modelId());
        assertEquals(0.8, options.scale());
        assertEquals(settings.showSelf, options.showSelf());
    }
    @Test void legacyModelWhitelistDoesNotRestrictAnyRegisteredKey() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("models.default", "unregistered");
        config.set("models.allowed", List.of("unregistered"));
        Settings settings = Settings.load(config);
        assertEquals("registered_me_model", DisguiseOptions.parse(new String[]{"disguise", "registered_me_model"}, settings).modelId());
        assertEquals(List.of("registered_me_model"), RegisteredModels.ids(List.of("registered_me_model")));
    }
    @Test void modelArgumentIsStillRequiredAndExistingIdentifierSafetyIsKept() {
        Settings settings = Settings.load(new YamlConfiguration());
        assertThrows(IllegalArgumentException.class, () -> DisguiseOptions.parse(new String[]{"disguise"}, settings));
        assertThrows(IllegalArgumentException.class, () -> DisguiseOptions.parse(new String[]{"disguise", "scale=0.8"}, settings));
        assertThrows(IllegalArgumentException.class, () -> DisguiseOptions.parse(new String[]{"disguise", "../model"}, settings));
        assertEquals(List.of("valid_model"), RegisteredModels.ids(List.of("Invalid Model", "../model", "fox.v1", "valid_model")));
    }
    @Test void omittedAttachIdUsesOnlyTheActualUniqueAttachedModel() {
        assertEquals("dragon", RegisteredModels.uniqueAttached(List.of("dragon")));
        assertThrows(IllegalStateException.class, () -> RegisteredModels.uniqueAttached(List.of()));
        var multiple = assertThrows(IllegalStateException.class, () -> RegisteredModels.uniqueAttached(List.of("dragon", "fox")));
        assertTrue(multiple.getMessage().contains("attach <模型名>"));
        assertThrows(IllegalStateException.class, () -> RegisteredModels.uniqueAttached(List.of("dragon", "Invalid Model")));
        assertThrows(IllegalArgumentException.class, () -> RegisteredModels.uniqueAttached(List.of("Invalid Model")));
    }
}
