package com.simmc.meplayeractions.client;

/** A private viewer-side appearance; none of these values are sent to the server. */
public record LocalAppearanceSettings(boolean enabled, String modelId, float scale,
                                      double offsetX, double offsetY, double offsetZ) {
    public static final float MIN_SCALE = .05f, MAX_SCALE = 8f;
    public static final double MAX_OFFSET = 32;

    public LocalAppearanceSettings {
        if (!isValidModelId(modelId)) throw new IllegalArgumentException("请选择内置模型或本地 .bbmodel 文件");
        if (!Float.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE)
            throw new IllegalArgumentException("缩放范围为 0.05 到 8");
        if (!validOffset(offsetX) || !validOffset(offsetY) || !validOffset(offsetZ))
            throw new IllegalArgumentException("每个位置偏移范围为 -32 到 32");
    }

    public static LocalAppearanceSettings defaults() {
        return new LocalAppearanceSettings(false, "ysm_02_jk", 1, 0, 0, 0);
    }

    public static boolean isValidModelId(String id) {
        if ("ysm_01_jk".equals(id) || "ysm_02_jk".equals(id)) return true;
        if (id == null || !id.startsWith("local:") || id.length() > 128) return false;
        String filename = id.substring(6);
        return filename.length() > 8 && filename.endsWith(".bbmodel")
                && !filename.startsWith(".") && !filename.contains("..")
                && filename.equals(filename.strip())
                && filename.chars().noneMatch(Character::isISOControl)
                && !filename.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*");
    }

    private static boolean validOffset(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_OFFSET;
    }
}
