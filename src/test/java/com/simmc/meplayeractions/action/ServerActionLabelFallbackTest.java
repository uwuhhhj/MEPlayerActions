package com.simmc.meplayeractions.action;

import com.simmc.meplayeractions.client.ClientSyncService.AnimationInfo;
import com.simmc.meplayeractions.config.AnimationLabels;
import com.simmc.meplayeractions.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Server-owned action names remain useful without loading any client model or enabling its renderer. */
class ServerActionLabelFallbackTest {
    @Test void unknownRawClipsPublishDistinctAuthorIdsForTheWheelAndPlaybackFeedback() {
        Settings settings = Settings.load(config());
        var directory = ActionDirectory.build(settings, List.of("mounted_ing", "idle_flight", "jumpattack"));
        assertEquals(List.of(new AnimationInfo("idle_flight", "idle_flight"),
                new AnimationInfo("jumpattack", "jumpattack"), new AnimationInfo("mounted_ing", "mounted_ing")), directory);
        for (AnimationInfo action : directory) {
            assertEquals(action.id(), settings.animationLabel(action.id()));
            assertEquals(action.label(), settings.actionLabel(action.id()));
        }
    }

    @Test void configuredNamesAreSharedByTheRawDirectoryAndPlaybackFeedback() {
        var config = config();
        config.set("menu.animation-labels.mounted_ing", "作者指定坐骑动作");
        Settings settings = Settings.load(config);
        assertEquals(List.of(new AnimationInfo("mounted_ing", "作者指定坐骑动作")),
                ActionDirectory.build(settings, List.of("mounted_ing")));
        assertEquals("作者指定坐骑动作", settings.actionLabel("mounted_ing"));
    }

    @Test void customAliasRetainsItsOwnConfiguredNameAheadOfTheRawClipName() {
        var config = config();
        config.set("menu.animation-labels.jumpattack", "作者指定攻击");
        config.set("custom-actions.special_attack.animation", "jumpattack");
        config.set("custom-actions.special_attack.label", "服务器专属动作");
        Settings settings = Settings.load(config);
        var directory = ActionDirectory.build(settings, List.of("jumpattack"));
        assertTrue(directory.contains(new AnimationInfo("special_attack", "服务器专属动作")));
        assertTrue(directory.contains(new AnimationInfo("jumpattack", "作者指定攻击")));
        assertEquals("服务器专属动作", settings.actionLabel("special_attack"));
    }

    @Test void unnamedCustomAliasUsesItsTargetsLabelOrOriginalIdWithoutCollapsingToGenericText() {
        var config = config();
        config.set("custom-actions.mounted.animation", "mounted_ing");
        config.set("custom-actions.flight.animation", "idle_flight");
        config.set("custom-actions.flight.label", "   ");
        config.set("menu.animation-labels.idle_flight", "作者指定飞行动作");
        Settings settings = Settings.load(config);
        assertEquals("mounted_ing", settings.actionLabel("mounted"));
        assertEquals("作者指定飞行动作", settings.actionLabel("flight"));
        assertEquals(List.of(new AnimationInfo("flight", "作者指定飞行动作"),
                        new AnimationInfo("idle_flight", "作者指定飞行动作"),
                        new AnimationInfo("mounted", "mounted_ing"), new AnimationInfo("mounted_ing", "mounted_ing")),
                ActionDirectory.build(settings, List.of("mounted_ing", "idle_flight")));
    }

    @Test void rawPlaybackPolicyStillLimitsThePublishedActionsRatherThanChangingTheirNames() {
        var config = config();
        config.set("manual.allow-raw-animation", false);
        config.set("custom-actions.mounted.animation", "mounted_ing");
        Settings settings = Settings.load(config);
        assertEquals(List.of(new AnimationInfo("mounted", "mounted_ing")),
                ActionDirectory.build(settings, List.of("mounted_ing", "idle_flight", "jumpattack")));
    }

    @Test void unnamedPlaybackAndAnimationListDescriptionsDoNotRepeatTheSameId() {
        Settings settings = Settings.load(config());
        for (String id : List.of("mounted_ing", "idle_flight", "jumpattack")) {
            assertEquals(id, AnimationLabels.displayWithId(id, settings.actionLabel(id)));
            assertEquals(id, AnimationLabels.displayWithId(id, settings.animationLabel(id)));
        }
    }

    @Test void namedPlaybackDescriptionRetainsTheNameAndExecutableAliasId() {
        var config = config();
        config.set("menu.animation-labels.jumpattack", "作者指定攻击");
        config.set("custom-actions.special_attack.animation", "jumpattack");
        config.set("custom-actions.special_attack.label", "服务器专属动作");
        Settings settings = Settings.load(config);
        assertEquals("作者指定攻击 (jumpattack)",
                AnimationLabels.displayWithId("jumpattack", settings.animationLabel("jumpattack")));
        assertEquals("服务器专属动作 (special_attack)",
                AnimationLabels.displayWithId("special_attack", settings.actionLabel("special_attack")));
    }

    private static YamlConfiguration config() {
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                ServerActionLabelFallbackTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        config.set("custom-actions", null);
        return config;
    }
}
