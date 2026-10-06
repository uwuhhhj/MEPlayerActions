/*
 * Migrated from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT; see assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * Format and conversion code are retained; package/type/logging adapters target MPA.
 */
package com.simmc.meplayeractions.client.model.nativebbmodel;

public class MathHelper {
    /**
     * 将角度减小到 -180 到 +180 之间的角度，并进行 360 度检查
     */
    public static float wrapDegrees(float value) {
        value = value % 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    /**
     * 将角度减小到 -180 到 +180 之间的角度，并进行 360 度检查
     */
    public static double wrapDegrees(double value) {
        value = value % 360.0D;
        if (value >= 180.0D) {
            value -= 360.0D;
        }
        if (value < -180.0D) {
            value += 360.0D;
        }
        return value;
    }

    /**
     * 调整角度，使其值在 [-180, 180]
     */
    public static int wrapDegrees(int angle) {
        angle = angle % 360;
        if (angle >= 180) {
            angle -= 360;
        }
        if (angle < -180) {
            angle += 360;
        }
        return angle;
    }
}