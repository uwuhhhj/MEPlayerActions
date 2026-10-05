package com.simmc.meplayeractions.config;

import org.bukkit.configuration.ConfigurationSection;

/** Background discovery cadence, independent of animation, physics and lease heartbeats. */
public record PerformanceSettings(int audienceRefreshTicks, int validationTicks, int adoptionScanTicks) {
    public PerformanceSettings {
        bounded("performance.audience-refresh-ticks", audienceRefreshTicks, 20, 100);
        bounded("performance.validation-ticks", validationTicks, 1, 20);
        bounded("performance.adoption-scan-ticks", adoptionScanTicks, 20, 100);
    }

    public static PerformanceSettings defaults() { return new PerformanceSettings(40, 20, 100); }

    public static PerformanceSettings fromConfiguration(ConfigurationSection config) {
        return new PerformanceSettings(config.getInt("performance.audience-refresh-ticks", 40),
                config.getInt("performance.validation-ticks", 20),
                config.getInt("performance.adoption-scan-ticks", 100));
    }

    private static void bounded(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " 必须在 " + minimum + "–" + maximum + " tick 范围内");
    }
}
