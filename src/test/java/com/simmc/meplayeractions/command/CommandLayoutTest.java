package com.simmc.meplayeractions.command;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandLayoutTest {
    @Test void descriptorRegistersOnlyTheNewRootWithoutLegacyAliases() {
        var descriptor = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        assertEquals(java.util.Set.of("meplayeractions"), descriptor.getConfigurationSection("commands").getKeys(false));
        assertTrue(descriptor.getStringList("commands.meplayeractions.aliases").isEmpty());
    }
    @Test void poseGroupDispatchesWithoutExposingFlatCommandsOrChangingArguments() {
        assertArrayEquals(new String[]{"sit"}, CommandLayout.normalize(new String[]{"pose", "sit"}));
        assertArrayEquals(new String[]{"crawl"}, CommandLayout.normalize(new String[]{"POSE", "CRAWL"}));
        assertArrayEquals(new String[]{"fly", "off"}, CommandLayout.normalize(new String[]{"pose", "fly", "off"}));
        String[] command = {"disguise", "ysm_01_jk_npc", "scale=1.5", "show-self=false"};
        assertArrayEquals(command, CommandLayout.normalize(command));
        assertArrayEquals(new String[]{"help"}, CommandLayout.normalize(new String[]{}));
        for (String root : List.of("sit", "crawl", "fly", "list", "debug", "action", "wave"))
            assertThrows(IllegalArgumentException.class, () -> CommandLayout.normalize(new String[]{root}));
    }
    @Test void malformedPoseAndUnexpectedTrailingArgumentsAreRejected() {
        for (String[] command : List.of(new String[]{"pose"}, new String[]{"pose", "sleep"},
                new String[]{"pose", "sit", "off"}, new String[]{"pose", "fly", "on", "extra"},
                new String[]{"animations", "extra"}, new String[]{"reload", "extra"}, new String[]{"play"}))
            assertThrows(IllegalArgumentException.class, () -> CommandLayout.normalize(command));
    }
    @Test void protocolPostureRequestsUseTheOrganizedPublicCommands() {
        assertArrayEquals(new String[]{"pose", "sit"}, CommandLayout.clientAction("sit", ""));
        assertArrayEquals(new String[]{"pose", "crawl"}, CommandLayout.clientAction("crawl", ""));
        assertArrayEquals(new String[]{"play", "wave"}, CommandLayout.clientAction("play", "wave"));
        assertArrayEquals(new String[]{"stop"}, CommandLayout.clientAction("stop", ""));
    }
}
