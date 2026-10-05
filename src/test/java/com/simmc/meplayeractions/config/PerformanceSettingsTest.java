package com.simmc.meplayeractions.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceSettingsTest {
    @Test void olderConfigurationUsesBackgroundDefaultsWithoutChangingAnimation() throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("controller:\n  sample-interval-ticks: 1\n  animation-update-interval-ticks: 1\n");
        assertEquals(new PerformanceSettings(40, 20, 100), PerformanceSettings.fromConfiguration(config));
        assertEquals(1, config.getInt("controller.sample-interval-ticks"));
        assertEquals(1, config.getInt("controller.animation-update-interval-ticks"));
        assertFalse(config.contains("performance"));
    }

    @Test void independentCadencesAreLoaded() throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("performance:\n  audience-refresh-ticks: 100\n  validation-ticks: 5\n  adoption-scan-ticks: 60\n");
        assertEquals(new PerformanceSettings(100, 5, 60), PerformanceSettings.fromConfiguration(config));
    }

    @Test void rejectsCadencesOutsideDiscoveryAndLeaseMargins() {
        assertThrows(IllegalArgumentException.class, () -> new PerformanceSettings(19, 20, 100));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceSettings(101, 20, 100));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceSettings(40, 21, 100));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceSettings(40, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceSettings(40, 20, 101));
    }
}
