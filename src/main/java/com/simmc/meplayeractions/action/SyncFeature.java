package com.simmc.meplayeractions.action;

import java.util.Locale;

public enum SyncFeature {
    MOVEMENT, SPRINT, JUMP, SIT, SLEEP, RIDE, CRAWL, SNEAK, SWIM, FLIGHT, ELYTRA, SWING, MINING;

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static SyncFeature parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("未知同步类型：" + value); }
    }
}
