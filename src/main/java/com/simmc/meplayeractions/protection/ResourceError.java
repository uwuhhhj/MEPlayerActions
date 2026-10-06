package com.simmc.meplayeractions.protection;

import com.google.gson.JsonObject;

/** Stable public errors deliberately omit filesystem paths and exception messages. */
public record ResourceError(String code, String stage, boolean retryable, int retryAfterTicks) {
    public ResourceError {
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid resource error code");
        if (stage == null || !stage.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid resource error stage");
        if (retryAfterTicks < 0 || retryAfterTicks > 72000) throw new IllegalArgumentException("Invalid retry delay");
    }
    public int retryAfterSeconds() { return (retryAfterTicks + 19) / 20; }
    public JsonObject json() {
        var result = new JsonObject(); result.addProperty("code", code); result.addProperty("stage", stage);
        result.addProperty("retryable", retryable); result.addProperty("retryAfter", retryAfterSeconds());
        return result;
    }
    public static ResourceError busy(String stage) { return new ResourceError("asset_queue_full", stage, true, 100); }
    public static ResourceError protectedState(String stage) { return new ResourceError("tps_protection", stage, true, 600); }
}
