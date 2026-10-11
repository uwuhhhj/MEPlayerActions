package com.simmc.meplayeractions.client.model;

/** OpenYSM 0306e1f AnimatableEntity's author angles, independent of the rendering path. */
final class YsmHeadQueries {
    private YsmHeadQueries() { }

    record Angles(double yaw, double pitch) { }

    static Angles angles(float rawYaw, float rawPitch, boolean upstream) {
        if (!upstream) return new Angles(rawYaw, rawPitch);
        float yaw = rawYaw % 360;
        if (yaw >= 180) yaw -= 360;
        if (yaw < -180) yaw += 360;
        return new Angles(-Math.max(-85, Math.min(85, yaw)), -rawPitch);
    }

    /**
     * Older MPA server exports appended the raw player look to otherwise authored Head tracks.
     * Restore those tracks before using the source's separate automatic look and author queries.
     * This migration is called only for explicitly marked mpa_runtime assets, never ordinary BBModel.
     */
    static String withoutInjectedLook(String expression, String axis) {
        String suffix = switch (axis) {
            case "x" -> "+ysm.head_pitch";
            case "y" -> "+ysm.head_yaw";
            default -> "";
        };
        if (suffix.isEmpty() || !expression.startsWith("(") || !expression.endsWith(")" + suffix))
            return expression;
        String authored = expression.substring(0, expression.length() - suffix.length());
        // prepare_models only appended this term when the original expression had no head query.
        return authored.contains("ysm.head_") ? expression : authored;
    }
}
