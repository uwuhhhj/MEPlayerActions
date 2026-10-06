package com.simmc.meplayeractions.config;

/** Pure format budgets shared without linking either runtime to Bukkit. Configuration may tighten hard ceilings. */
public record ModelComplexityLimits(int maxBones, int maxAnimations, int maxKeyframes,
                                    int maxExpressions, int maxExpressionChars, int maxJsonNodes,
                                    int maxArchiveEntries, int maxExpandedBytes, long maxTexturePixels) {
    public ModelComplexityLimits {
        bounded("max-bones", maxBones, 4096);
        bounded("max-animations", maxAnimations, 1024);
        bounded("max-keyframes", maxKeyframes, 65_536);
        bounded("max-expressions", maxExpressions, 32_768);
        bounded("max-expression-chars", maxExpressionChars, 2_097_152);
        bounded("max-json-nodes", maxJsonNodes, 200_000);
        bounded("max-archive-entries", maxArchiveEntries, 256);
        bounded("max-expanded-bytes", maxExpandedBytes, 8_388_608);
        bounded("max-texture-pixels", maxTexturePixels, 16_777_216);
    }
    private static void bounded(String name, long value, long maximum) {
        if (value < 1 || value > maximum) throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
    }
    public static ModelComplexityLimits defaults() {
        return new ModelComplexityLimits(4096, 1024, 65_536, 32_768, 2_097_152,
                200_000, 256, 8_388_608, 16_777_216);
    }
}
