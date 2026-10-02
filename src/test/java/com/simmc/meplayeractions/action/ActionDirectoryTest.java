package com.simmc.meplayeractions.action;

import com.simmc.meplayeractions.client.ClientSyncService.AnimationInfo;
import com.simmc.meplayeractions.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionDirectoryTest {
    private YamlConfiguration config() {
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        config.set("custom-actions", null);
        return config;
    }

    @Test void rawPlaybackDisabledPublishesOnlyAvailableCustomAliases() {
        var config = config(); config.set("manual.allow-raw-animation", false);
        config.set("custom-actions.greeting.animation", "wave"); config.set("custom-actions.greeting.label", "招手问好");
        config.set("custom-actions.missing.animation", "missing_clip");
        assertEquals(List.of(new AnimationInfo("greeting", "招手问好")),
                ActionDirectory.build(Settings.load(config), List.of("idle", "wave")));
    }

    @Test void customAliasUsesTheActionIdAndLabelInsteadOfItsAnimationName() {
        var config = config(); config.set("manual.allow-raw-animation", true);
        config.set("custom-actions.greeting.animation", "wave"); config.set("custom-actions.greeting.label", "问候");
        config.set("menu.animation-labels.wave", "原始挥手");
        assertEquals(List.of(new AnimationInfo("greeting", "问候"), new AnimationInfo("wave", "原始挥手")),
                ActionDirectory.build(Settings.load(config), List.of("wave")));
    }

    @Test void rawClipsAreDeduplicatedSortedAndRestrictedToExecutableCommandIds() {
        var config = config(); config.set("manual.allow-raw-animation", true);
        config.set("menu.animation-labels.walk", "走路"); config.set("menu.animation-labels.idle", "待机");
        assertEquals(List.of(new AnimationInfo("idle", "待机"), new AnimationInfo("walk", "走路")),
                ActionDirectory.build(Settings.load(config), List.of("walk", "idle", "walk", "Wave", "pack:wave", "wave.anim", "动画", "a".repeat(65))));
    }

    @Test void customIdTakesPriorityAndNeverFallsThroughToAnUnplayableRawClip() {
        var config = config(); config.set("manual.allow-raw-animation", true);
        config.set("custom-actions.wave.animation", "idle"); config.set("custom-actions.wave.label", "自定义问候");
        config.set("menu.animation-labels.wave", "原始挥手"); config.set("menu.animation-labels.idle", "待机");
        assertEquals(List.of(new AnimationInfo("idle", "待机"), new AnimationInfo("wave", "自定义问候")),
                ActionDirectory.build(Settings.load(config), List.of("wave", "idle")));
        config.set("custom-actions.wave.animation", "missing_clip");
        assertEquals(List.of(new AnimationInfo("idle", "待机")),
                ActionDirectory.build(Settings.load(config), List.of("wave", "idle")));
    }
}
