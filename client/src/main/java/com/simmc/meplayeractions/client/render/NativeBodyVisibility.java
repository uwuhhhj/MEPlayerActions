package com.simmc.meplayeractions.client.render;

import com.google.gson.JsonObject;
import com.simmc.meplayeractions.client.network.WireJson;

/** Keep genuine native invisibility separate from ModelEngine's presentation-only hide flag. */
public final class NativeBodyVisibility {
    private NativeBodyVisibility() { }

    /** Older servers do not identify the source flag, so their native presentation remains authoritative. */
    public static Boolean readSourceInvisible(JsonObject state) {
        return state.has("sourceInvisible") ? WireJson.bool(state, "sourceInvisible") : null;
    }

    public record Body(boolean visible, boolean outlineOnly) { }

    /** A supplied source flag belongs only to an active server render binding. */
    public static Body resolve(boolean nativeYsm, boolean requested, boolean nativeInvisible,
                               Boolean sourceInvisible, int outlineColor) {
        boolean invisible = sourceInvisible == null ? nativeInvisible : sourceInvisible;
        boolean outlineOnly = nativeYsm && invisible && outlineColor != 0;
        return new Body(requested && (!nativeYsm || !invisible || outlineOnly), outlineOnly);
    }
}
