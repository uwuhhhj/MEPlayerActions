package com.simmc.meplayeractions.me;

import org.joml.Vector3f;

/** Immutable world-space offset, safely published from Paper to the ME renderer. */
public record VisualOffset(double x, double y, double z) {
    public void apply(Vector3f modelPosition, float bodyYawDegrees) {
        // ME rotates model-space positions by -bodyYaw after BoneTransformReadEvent.
        modelPosition.add(new Vector3f((float) x, (float) y, (float) z)
                .rotateY((float) Math.toRadians(bodyYawDegrees)));
    }
}
