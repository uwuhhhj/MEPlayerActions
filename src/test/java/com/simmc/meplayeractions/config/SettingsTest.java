package com.simmc.meplayeractions.config;

import com.simmc.meplayeractions.action.ActionState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test void bedAndGsitLayUseTheHorizontalClipButRetainIndependentMappings() throws IOException {
        var config = configuration(); var settings = Settings.load(config);
        for (var state : List.of(ActionState.BED_SLEEP, ActionState.SLEEP)) {
            assertEquals("bed_sleep", settings.animation("ysm_01_jk", state, List.of("sleep", "bed_sleep")));
            assertEquals("sleep", settings.animation("ysm_01_jk", state, List.of("sleep")));
        }
        config.set("animations.defaults.bed-sleep", List.of());
        var disabled = Settings.load(config);
        assertNull(disabled.animation("ysm_01_jk", ActionState.BED_SLEEP, List.of("bed_sleep")));
        assertEquals("bed_sleep", disabled.animation("ysm_01_jk", ActionState.SLEEP, List.of("bed_sleep")));
    }
    @Test void audienceDefaultsWorkWithOldConfigurationAndExplicitOverrides() throws IOException {
        var config = configuration();
        for (String key : List.of("show-self", "view-distance", "max-viewers")) config.set("disguise." + key, null);
        var old = Settings.load(config); assertTrue(old.showSelf); assertEquals(8, old.modelViewDistance); assertEquals(10, old.maxViewers);
        config.set("disguise.show-self", false); config.set("disguise.view-distance", 12.5); config.set("disguise.max-viewers", 0);
        var override = Settings.load(config); assertFalse(override.showSelf); assertEquals(12.5, override.modelViewDistance); assertEquals(0, override.maxViewers);
    }
    @Test void audienceConfigurationRejectsInvalidDistancesAndCaps() {
        for (Object distance : List.of(Double.NaN, Double.POSITIVE_INFINITY, 0, 256.1))
            assertThrows(IllegalArgumentException.class, () -> loadWith("disguise.view-distance", distance));
        for (int cap : List.of(-1, 1001)) assertThrows(IllegalArgumentException.class, () -> loadWith("disguise.max-viewers", cap));
    }
    @Test
    void animationCandidatesUseAvailableFallbackWithoutInventingMissingTracks() throws IOException {
        Settings settings = Settings.load(configuration());

        assertAll(
                () -> assertEquals("run", settings.animation("ysm_01_jk", ActionState.RUN,
                        List.of("walk", "run"))),
                () -> assertEquals("walk", settings.animation("ysm_01_jk", ActionState.RUN,
                        List.of("walk"))),
                () -> assertEquals("fly", settings.animation("ysm_01_jk", ActionState.ELYTRA,
                        List.of("fly"))),
                () -> assertNull(settings.animation("ysm_01_jk", ActionState.SIT, List.of("idle")))
        );
    }

    @Test
    void explicitNpcCrawlDisableOverridesDefaultsEvenIfSimilarTracksExist() throws IOException {
        var config = configuration();
        config.set("animations.models.ysm_02_jk.crawl-idle", List.of());
        config.set("animations.models.ysm_02_jk.crawl-walk", List.of());
        Settings settings = Settings.load(config);
        List<String> tracks = List.of("idle", "crawl_idle", "crawl_walk", "climb", "climb_idle");

        assertAll(
                () -> assertEquals("crawl_idle", settings.animation("ysm_01_jk", ActionState.CRAWL_IDLE, tracks)),
                () -> assertEquals("climb", settings.animation("ysm_01_jk", ActionState.CRAWL_WALK, tracks)),
                () -> assertNull(settings.animation("ysm_02_jk", ActionState.CRAWL_IDLE, tracks)),
                () -> assertNull(settings.animation("ysm_02_jk", ActionState.CRAWL_WALK, tracks)),
                () -> assertEquals("idle", settings.animation("ysm_02_jk", ActionState.IDLE, tracks))
        );
    }

    @Test void newNpcDefaultsResolveCrawlAndVisualTimingIsBounded() throws IOException {
        var settings = Settings.load(configuration());
        assertEquals("crawl_idle", settings.animation("ysm_02_jk", ActionState.CRAWL_IDLE, List.of("crawl_idle")));
        assertEquals("crawl_walk", settings.animation("ysm_02_jk", ActionState.CRAWL_WALK, List.of("crawl_walk")));
        assertEquals("swim", settings.animation("ysm_02_jk", ActionState.SWIM_PRONE_IDLE, List.of("swim_idle", "swim")));
        assertAll(
                () -> assertEquals(0, loadWith("visual-follow.delay-ticks", 0).visualDelayTicks),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("visual-follow.delay-ticks", 21)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("visual-follow.max-distance-blocks", Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("jump.minimum-play-ticks", -1)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("jump.landing-grace-ticks", 41))
        );
    }

    @Test
    void allowlistRequiresTheDefaultAndRejectsUnapprovedModels() throws IOException {
        Settings settings = Settings.load(configuration());

        assertAll(
                () -> assertDoesNotThrow(() -> settings.requireAllowedModel("ysm_01_jk")),
                () -> assertThrows(IllegalArgumentException.class, () -> settings.requireAllowedModel("another_model")),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("models.allowed", List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("models.allowed", List.of("another_model"))),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("models.allowed", List.of("ysm_01_jk", "Invalid Model")))
        );
    }

    @Test
    void nonFiniteConfigurationValuesAreRejectedBeforeSchedulingTheController() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("disguise.scale", Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.movement-threshold", Double.POSITIVE_INFINITY)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("client-sync.view-distance-blocks", Double.NaN))
        );
    }

    @Test
    void animationLayersRequireStrictPostureInteractionManualOrder() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.manual-priority", 100)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.manual-priority", 99)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.manual-priority", 150)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.interaction-priority", 100)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.posture-priority", 0)),
                () -> assertDoesNotThrow(() -> loadWith("controller.manual-priority", 152))
        );
    }

    @Test
    void manualSpeedBoundsApplyToCommandsAndConfiguredCustomActions() throws IOException {
        Settings settings = Settings.load(configuration());

        assertAll(
                () -> assertDoesNotThrow(() -> settings.validateSpeed(0.1)),
                () -> assertDoesNotThrow(() -> settings.validateSpeed(5.0)),
                () -> assertThrows(IllegalArgumentException.class, () -> settings.validateSpeed(0.09)),
                () -> assertThrows(IllegalArgumentException.class, () -> settings.validateSpeed(5.1)),
                () -> assertThrows(IllegalArgumentException.class, () -> settings.validateSpeed(Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> settings.validateSpeed(Double.POSITIVE_INFINITY)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("manual.max-speed", 0.05)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("custom-actions.wave.speed", Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("custom-actions.wave.speed", 5.1))
        );
    }

    @Test
    void flightSpeedMustStayInsideBukkitsSupportedRange() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("gameplay.flight-speed", 0.0)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("gameplay.flight-speed", 1.01)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("gameplay.flight-speed", Double.NaN)),
                () -> assertEquals(1.0, loadWith("gameplay.flight-speed", 1.0).flightSpeed)
        );
    }

    @Test
    void identifiersRejectDotsBeforeBukkitCanTreatThemAsPaths() {
        assertAll(
                () -> assertEquals("ysm_01_jk-player", Settings.id("ysm_01_jk-player")),
                () -> assertThrows(IllegalArgumentException.class, () -> Settings.id("fox.v1")),
                () -> assertThrows(IllegalArgumentException.class, () -> Settings.id("wave.happy")),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("models.default", "fox.v1")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> loadWith("models.allowed", List.of("ysm_01_jk", "fox.v1"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> loadWith("custom-actions.wave.animation", "wave.happy"))
        );
    }

    @Test
    void dottedYamlActionNameIsRejectedInsteadOfSilentlyBecomingAnotherAction() throws Exception {
        YamlConfiguration configuration = configuration();
        configuration.set("custom-actions", null);
        configuration.loadFromString(configuration.saveToString() + "\ncustom-actions:\n"
                + "  'wave.happy':\n    animation: wave\n");

        // Bukkit has already split this literal YAML key before Settings sees it.
        assertEquals(List.of("wave"), List.copyOf(configuration.getConfigurationSection("custom-actions").getKeys(false)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Settings.load(configuration));
        assertTrue(error.getMessage().contains("custom-actions.wave.happy"));
    }

    @Test
    void dottedYamlModelProfileIsRejectedInsteadOfSilentlyLosingItsStateMap() throws Exception {
        YamlConfiguration configuration = configuration();
        configuration.set("animations.models", null);
        YamlConfiguration dotted = new YamlConfiguration();
        dotted.loadFromString("animations:\n  models:\n    'fox.v1':\n      sit: [sit_alt]\n");
        configuration.set("animations.models", dotted.getConfigurationSection("animations.models"));

        assertEquals(List.of("fox"), List.copyOf(configuration.getConfigurationSection("animations.models").getKeys(false)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Settings.load(configuration));
        assertTrue(error.getMessage().contains("animations.models.fox.v1"));
    }

    @Test
    void unknownActionAndAnimationFieldsAreRejectedEvenForDisabledActions() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("custom-actions.wave.sped", 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> {
                    YamlConfiguration configuration = configuration();
                    configuration.set("custom-actions.wave.enabled", false);
                    configuration.set("custom-actions.wave.happy.animation", "wave");
                    Settings.load(configuration);
                }),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("custom-actions.wave.speed.extra", 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("animations.defaults.typo", List.of("idle"))),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("animations.models.ysm_01_jk.typo", List.of("idle")))
        );
    }

    @Test
    void clientPayloadLimitMatchesTheServiceMinimum() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("client-sync.max-payload-bytes", 512)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("client-sync.max-payload-bytes", 1023)),
                () -> assertEquals(1024, loadWith("client-sync.max-payload-bytes", 1024).clientMaxPayload)
        );
    }

    @Test
    void legacySamplingSettingSurvivesWithoutNewKeysAndExplicitNewKeyWins() throws IOException {
        YamlConfiguration config = configuration();
        config.set("controller.sample-interval-ticks", null);
        config.set("controller.interval-ticks", 4);
        assertEquals(4, Settings.load(config).interval);
        config.set("controller.sample-interval-ticks", 2);
        assertEquals(2, Settings.load(config).interval);
    }

    @Test
    void retainedLegacyConfigGetsNewMappingsButExplicitEmptyListsStillDisable() throws IOException {
        YamlConfiguration config = configuration();
        config.set("animations.defaults.sleep", null);
        assertEquals("sleep", Settings.load(config).animation("ysm_01_jk", ActionState.SLEEP, List.of("sleep")));
        config.set("animations.defaults.sleep", List.of());
        assertNull(Settings.load(config).animation("ysm_01_jk", ActionState.SLEEP, List.of("sleep")));
        config.set("animations.models.ysm_01_jk.mining", List.of());
        assertNull(Settings.load(config).animation("ysm_01_jk", ActionState.MINING, List.of("attack")));
    }

    @Test
    void vehicleFallbackAndPartialArmTracksOnlyUseAvailableAnimations() throws IOException {
        Settings settings = Settings.load(configuration());
        assertAll(
                () -> assertEquals("sit", settings.animation("ysm_01_jk", ActionState.MINECART, List.of("sit"))),
                () -> assertEquals("boat", settings.animation("ysm_01_jk", ActionState.BOAT, List.of("sit", "boat"))),
                () -> assertEquals("attack", settings.animation("ysm_01_jk", ActionState.MINING, List.of("attack"))),
                () -> assertEquals("use_offhand", settings.animation("ysm_01_jk", ActionState.SWING_OFFHAND, List.of("attack", "use_offhand"))),
                () -> assertNull(settings.animation("ysm_01_jk", ActionState.SWING_MAINHAND, List.of("wave")))
        );
    }

    @Test
    void staticPosturesHoldWhileMiningLoopsAndSwingIsOneShot() throws IOException {
        Settings settings = Settings.load(configuration());
        assertEquals(com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode.HOLD, settings.playback(ActionState.SLEEP).loop());
        assertEquals(com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode.HOLD, settings.playback(ActionState.SIT).loop());
        assertEquals(com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode.LOOP, settings.playback(ActionState.MINING).loop());
        assertEquals(com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode.ONCE, settings.playback(ActionState.SWING_MAINHAND).loop());
    }

    @Test
    void statePlaybackOverridesAreValidatedAndInheritGlobalTransitions() throws IOException {
        YamlConfiguration config = configuration();
        config.set("animation-settings.boat.speed", 1.5);
        config.set("animation-settings.boat.transition-in-ticks", 0);
        var playback = Settings.load(config).playback(ActionState.BOAT);
        assertEquals(1.5, playback.speed());
        assertEquals(0, playback.inTicks());
        assertEquals(2, playback.outTicks());
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("animation-settings.sleep.speed", Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("animation-settings.sleep.loop", "BAD")),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("animation-settings.sleep.transition-out-ticks", -1)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("animation-settings.sleep.sped", 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("animation-settings.typo.speed", 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.sample-interval-ticks", 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.animation-update-interval-ticks", 21)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.swing-duration-ticks", 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> loadWith("controller.mining-timeout-ticks", 1))
        );
    }

    @Test void privatePublishingRemainsDisabledForBothNewAndRetainedConfigurations() throws IOException {
        var config=configuration();assertFalse(Settings.load(config).privateModels.enabled());
        config.set("client-sync.private-models",null);var retained=Settings.load(config);
        assertFalse(retained.privateModels.enabled());assertEquals("mact.private.upload",retained.privateModels.uploadPermission());
        assertEquals("mact.private.view",retained.privateModels.viewPermission());
        config.set("client-sync.private-models.enabled",true);assertTrue(Settings.load(config).privateModels.enabled());
        config.set("client-sync.enabled",false);assertFalse(Settings.load(config).privateModels.enabled());
        config.set("client-sync.private-models.max-stored-bytes",8388608);assertThrows(IllegalArgumentException.class,()->Settings.load(config));
    }

    private static Settings loadWith(String path, Object value) throws IOException {
        YamlConfiguration configuration = configuration();
        configuration.set(path, value);
        return Settings.load(configuration);
    }

    private static YamlConfiguration configuration() throws IOException {
        InputStream resource = SettingsTest.class.getResourceAsStream("/config.yml");
        assertNotNull(resource, "The plugin's packaged config.yml must be on the test classpath");
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
