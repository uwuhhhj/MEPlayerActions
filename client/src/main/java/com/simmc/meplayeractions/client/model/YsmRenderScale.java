package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;

/**
 * OpenYSM-Updated 0306e1fa's INITIAL player-body scale: YSMFolderDeserializer
 * defaults each source property to .7, and IGeoRenderer.renderEarly maps
 * height_scale to X/Z and width_scale to Y. It is separate from a user's scale.
 */
public record YsmRenderScale(float x, float y, float z) {
    public static final YsmRenderScale IDENTITY = new YsmRenderScale(1, 1, 1);
    public static final float PLAYER_BODY_Y_OFFSET = .01f;

    public YsmRenderScale {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            throw new IllegalArgumentException("YSM render scale must fit a finite float");
    }

    public static YsmRenderScale fromProperties(JsonObject properties) {
        float height = sourceFloat(properties, "height_scale");
        float width = sourceFloat(properties, "width_scale");
        return new YsmRenderScale(height, width, height);
    }

    public static YsmRenderScale forProfile(YsmModelProfile profile) {
        return profile != null && profile.isYsm() ? fromProperties(profile.properties()) : IDENTITY;
    }

    /**
     * Appends GeoReplacedEntityRenderer's T(0,.01,0), then renderEarly's S.
     * The caller's native player/user scale and rotation remain outside both.
     * Plain BB models and empty server profiles receive neither operation.
     */
    public Matrix4f applyInitialPlayerBody(Matrix4f parent, boolean nativeYsm) {
        if (nativeYsm) parent.translate(0, PLAYER_BODY_Y_OFFSET, 0).scale(x, y, z);
        return parent;
    }

    private static float sourceFloat(JsonObject properties, String key) {
        if (properties == null || !properties.has(key)) return .7f;
        JsonElement value = properties.get(key);
        try {
            // Keep the source getAsDouble -> float semantics, including numeric strings.
            // Zero and negative author values are valid; non-finite/overflow is rejected.
            float result = (float) value.getAsDouble();
            if (!Float.isFinite(result)) throw new IllegalArgumentException("Non-finite YSM " + key);
            return result;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid YSM " + key, failure);
        }
    }
}
