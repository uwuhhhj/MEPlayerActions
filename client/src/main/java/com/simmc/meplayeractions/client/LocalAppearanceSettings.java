package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.model.BuiltinYsmModels;

/** A private viewer-side appearance; none of these values are sent to the server. */
public record LocalAppearanceSettings(boolean enabled, String modelId, float scale,
                                      double offsetX, double offsetY, double offsetZ) {
    public static final float MIN_SCALE = .05f, MAX_SCALE = 8f;
    public static final double MAX_OFFSET = 32;

    public LocalAppearanceSettings {
        if (!isValidModelId(modelId)) throw new IllegalArgumentException("请选择内置模型、本地 .bbmodel 文件或 YSM 模型文件夹");
        if (!Float.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE)
            throw new IllegalArgumentException("缩放范围为 0.05 到 8");
        if (!validOffset(offsetX) || !validOffset(offsetY) || !validOffset(offsetZ))
            throw new IllegalArgumentException("每个位置偏移范围为 -32 到 32");
    }

    public static LocalAppearanceSettings defaults() {
        return new LocalAppearanceSettings(false, "openysm_default", 1, 0, 0, 0);
    }

    public static boolean isValidModelId(String id) {
        if (BuiltinYsmModels.contains(id)) return true;
        if (id == null || id.length() > 128) return false;
        if (id.startsWith("local:")) {
            String filename = id.substring(6);
            return filename.length() > 8 && filename.endsWith(".bbmodel") && safeName(filename);
        }
        return id.startsWith("ysm:") && safeName(id.substring(4));
    }

    private static boolean safeName(String name) {
        return !name.isBlank() && !name.startsWith(".") && !name.contains("..")
                && name.equals(name.strip()) && name.chars().noneMatch(Character::isISOControl)
                && !name.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*");
    }

    private static boolean validOffset(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_OFFSET;
    }
}
