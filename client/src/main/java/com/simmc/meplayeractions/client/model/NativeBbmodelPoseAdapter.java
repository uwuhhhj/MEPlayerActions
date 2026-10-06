/*
 * Migrated from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT; see assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * ImportedVanillaPoseController PartTransformProvider / FallbackTransformProvider,
 * adapted to the native degree-based channels after authored rotation ownership.
 */
package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.client.model.nativebbmodel.ImportedVanillaPoseController;
import net.minecraft.entity.player.PlayerEntity;
import org.joml.Vector3f;

import java.util.Map;
import java.util.WeakHashMap;

/** Source imported humanoid fallback. It never changes the player's presented position. */
public final class NativeBbmodelPoseAdapter {
    private static final String[][] NAMES = {
        {"body", "torso", "chest", "upperbody", "vanillabody"},
        {"leftarm", "leftupperarm", "leftshoulder", "vanillaleftarm"},
        {"rightarm", "rightupperarm", "rightshoulder", "vanillarightarm"},
        {"leftforearm", "leftlowerarm", "leftelbow"},
        {"rightforearm", "rightlowerarm", "rightelbow"},
        {"lefthand", "leftwrist", "leftpalm"},
        {"righthand", "rightwrist", "rightpalm"},
        {"leftleg", "leftupperleg", "leftthigh", "vanillaleftleg"},
        {"rightleg", "rightupperleg", "rightthigh", "vanillarightleg"},
        {"leftlowerleg", "leftshin", "leftcalf"},
        {"rightlowerleg", "rightshin", "rightcalf"},
        {"leftfoot", "leftboot"},
        {"rightfoot", "rightboot"}
    };
    // Values retain indices only: unloading a model releases its alias mapping as well.
    private static final Map<BbModel, int[]> BINDINGS = new WeakHashMap<>();
    private NativeBbmodelPoseAdapter() { }

    public static void install(AnimationPlayer playback, BbModel model, PlayerEntity owner,
                               ImportedVanillaPoseController.Frame frame) {
        int[] indices = bindings(model);
        playback.configureNativeFallback((pose, context) -> {
            var sample = ImportedVanillaPoseController.sample(owner, frame);
            if (sample != null) apply(indices, pose, sample);
        });
    }

    static void apply(BbModel model, BbModel.Pose pose, ImportedVanillaPoseController.PoseValues sample) {
        apply(bindings(model), pose, sample);
    }

    private static void apply(int[] indices, BbModel.Pose pose, ImportedVanillaPoseController.PoseValues value) {
        // fallbackOnly in ModelAssemblyFactory deliberately excludes the head provider.
        put(pose, indices[0], value.bodyX, value.bodyY, value.bodyZ);
        put(pose, indices[1], value.leftArmX, value.leftArmY, value.leftArmZ);
        put(pose, indices[2], value.rightArmX, value.rightArmY, value.rightArmZ);
        put(pose, indices[3], value.leftForeArmX, value.leftForeArmY, value.leftForeArmZ);
        put(pose, indices[4], value.rightForeArmX, value.rightForeArmY, value.rightForeArmZ);
        put(pose, indices[5], value.leftHandX, value.leftHandY, value.leftHandZ);
        put(pose, indices[6], value.rightHandX, value.rightHandY, value.rightHandZ);
        put(pose, indices[7], value.leftLegX, value.leftLegY, value.leftLegZ);
        put(pose, indices[8], value.rightLegX, value.rightLegY, value.rightLegZ);
        put(pose, indices[9], value.leftLowerLegX, value.leftLowerLegY, value.leftLowerLegZ);
        put(pose, indices[10], value.rightLowerLegX, value.rightLowerLegY, value.rightLowerLegZ);
        put(pose, indices[11], value.leftFootX, value.leftFootY, value.leftFootZ);
        put(pose, indices[12], value.rightFootX, value.rightFootY, value.rightFootZ);
    }

    private static void put(BbModel.Pose pose, int index, float x, float y, float z) {
        if (index >= 0 && pose.channels[index][1] == null)
            pose.channels[index][1] = new Vector3f(x, y, z).mul((float) (180 / Math.PI));
    }

    private static synchronized int[] bindings(BbModel model) {
        return BINDINGS.computeIfAbsent(model, ignored -> {
            int[] result = new int[NAMES.length];
            java.util.Arrays.fill(result, -1);
            for (int i = 0; i < NAMES.length; i++) {
                search: for (String candidate : NAMES[i]) for (String bone : model.boneNames()) {
                    if (bone.equals(candidate) || normalize(bone).equals(candidate)) {
                        result[i] = model.boneIndex(bone); break search;
                    }
                }
            }
            return result;
        });
    }

    private static String normalize(String name) {
        if (name == null || name.isEmpty()) return "";
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }
}
