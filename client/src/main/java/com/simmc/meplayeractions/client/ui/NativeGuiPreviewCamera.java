package com.simmc.meplayeractions.client.ui;

import com.google.gson.JsonObject;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.model.YsmRenderScale;

/**
 * Fixed native GUI camera adapted from OpenYSM-Updated 0306e1f:
 * PlayerModelScreen.renderModelPreview / ModelPreviewRenderer.submitLivingEntityPreview,
 * and Minecraft 1.21.11 InventoryScreen / EntityGuiElementRenderer.
 * Model bounds are deliberately absent: the native viewport clips authored stages.
 */
final class NativeGuiPreviewCamera {
    static final float GEO_Y_OFFSET = .01f;

    record Position(float x, float y, float depth) { }

    record Camera(String source, float displaySize, float pixelsPerBlock, float nativeHeight,
                  float entityScale, float translationY, float yaw, float pitch, boolean rotationDisabled,
                  YsmRenderScale modelScale) {
        Camera withModelScale(JsonObject properties) {
            return new Camera(source, displaySize, pixelsPerBlock, nativeHeight, entityScale,
                    translationY, yaw, pitch, rotationDisabled, YsmRenderScale.fromProperties(properties));
        }

        Position rotate(float x, float y, float z) {
            double a = Math.toRadians(yaw % 360), b = Math.toRadians(pitch % 360);
            double cy = Math.cos(a), sy = Math.sin(a), cp = Math.cos(b), sp = Math.sin(b);
            double rx = cy * x + sy * z, rz = -sy * x + cy * z;
            return new Position((float) rx, (float) (cp * y - sp * rz), (float) (sp * y + cp * rz));
        }

        Position rotateNormal(float x, float y, float z) {
            return rotate(modelScale.x() == 0 ? 0 : x / modelScale.x(),
                    modelScale.y() == 0 ? 0 : y / modelScale.y(),
                    modelScale.z() == 0 ? 0 : z / modelScale.z());
        }

        Position project(float x, float y, float z, int left, int top, int width, int height) {
            // GeoReplacedEntityRenderer's T(0,.01,0) precedes IGeoRenderer's author S.
            // Applied to a vertex, that is author scaling first, then the unscaled offset.
            Position rotated = rotate(x * modelScale.x(), y * modelScale.y() + GEO_Y_OFFSET, z * modelScale.z());
            // Native-height anchor and display size are independent of the author's model scale.
            return new Position(left + width * .5f - pixelsPerBlock * rotated.x,
                    top + height * .5f + pixelsPerBlock * (translationY - rotated.y), rotated.depth);
        }
    }

    private NativeGuiPreviewCamera() { }

    static boolean appliesTo(YsmModelProfile profile) { return profile != null && profile.isYsm(); }

    /** PlayerPreviewEntity constructs a fresh standing DummyPlayer; it never inherits its owner's attributes/pose. */
    static Camera select(boolean dummyCard, float nativeHeight, float entityScale,
                         float yaw, float pitch, boolean disableCardRotation) {
        return dummyCard ? card(1.8f, 1, disableCardRotation) : owner(nativeHeight, entityScale, yaw, pitch);
    }

    /** InventoryScreen first normalizes height/baseScale, then submits the source's fixed size 70. */
    static Camera owner(float nativeHeight, float entityScale, float yaw, float pitch) {
        validate(nativeHeight, entityScale, yaw, pitch);
        return new Camera("openysm-owner-inventory", 70, 70, nativeHeight, entityScale,
                nativeHeight / entityScale * .5f + .0625f, yaw, pitch, false, YsmRenderScale.IDENTITY);
    }

    /** The dummy card has fixed size 30, bodyYaw 180/200, and the source's 5.5-pixel offset. */
    static Camera card(float nativeHeight, float entityScale, boolean disableRotation) {
        validate(nativeHeight, entityScale, 0, 0);
        float displaySize = 30;
        return new Camera("openysm-dummy-card", displaySize, displaySize / entityScale, nativeHeight, entityScale,
                nativeHeight * .5f + (disableRotation ? 5.5f : 0) * entityScale / displaySize,
                disableRotation ? 0 : -20, disableRotation ? 0 : -10, disableRotation, YsmRenderScale.IDENTITY);
    }

    private static void validate(float height, float scale, float yaw, float pitch) {
        if (!Float.isFinite(height) || height <= 0 || !Float.isFinite(scale) || scale <= 0
                || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IllegalArgumentException("Native GUI camera requires a finite living-entity height/scale");
    }
}
