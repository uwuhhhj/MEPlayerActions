/*
 * Migrated from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT; see assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * BuiltinBbmodelActionPreset and ModelAssemblyFactory action/semantic rules,
 * adapted to the common bounded RawYsmModel before the native runtime import.
 */
package com.simmc.meplayeractions.client.model.nativebbmodel;

import com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public final class NativeBbmodelActions {
    private static final String BASE = "/assets/meplayeractions/builtin/bbmodel/animations/";
    private static volatile Map<String, RawYsmModel.RawAnimationFile> cached;
    private NativeBbmodelActions() { }

    public static void apply(RawYsmModel raw) throws IOException {
        SemanticSkeleton skeleton = buildSemanticSkeleton(raw.mainEntity.mainModel);
        Map<String, RawYsmModel.RawAnimation> body = new LinkedHashMap<>(), arms = new LinkedHashMap<>();
        for (var entry : raw.mainEntity.animationFiles.entrySet()) {
            String family = entry.getKey().replaceFirst("^animation-", "");
            (family.equals("fp_arm") || family.equals("fp.arm") ? arms : body).putAll(entry.getValue().animations);
        }
        for (var entry : presets().entrySet()) {
            var target = entry.getKey().equals("fp_arm") ? arms : body;
            for (var clip : entry.getValue().animations.entrySet())
                target.computeIfAbsent(clip.getKey(), name -> remap(clip.getValue(), skeleton));
        }
        aliases(body); aliases(arms);
        for (String animation : List.of("hold_mainhand:lance", "hold_offhand:lance", "swing:lance", "use_mainhand:lance", "use_offhand:lance",
                "lance_stand", "lance_jab", "lance_lunge", "lance_charge", "lance_riding_idle", "lance_riding_charge", "lance_fall_flying_charge")) {
            RawYsmModel.RawAnimation existing = arms.get(animation);
            if (existing != null && !existing.boneAnimations.isEmpty()) continue;
            RawYsmModel.RawAnimation derived = firstPerson(animation, body.get(animation));
            if (derived != null) arms.put(animation, derived);
        }
        raw.mainEntity.animationFiles.clear();
        RawYsmModel.RawAnimationFile main = new RawYsmModel.RawAnimationFile(); main.animType = 1; main.animations.putAll(body);
        RawYsmModel.RawAnimationFile fp = new RawYsmModel.RawAnimationFile(); fp.animType = 11; fp.animations.putAll(arms);
        raw.mainEntity.animationFiles.put("main", main); raw.mainEntity.animationFiles.put("fp_arm", fp);
        // ClientModelBundleAssembler uses the main mesh when no separate arm mesh exists.
        if (raw.mainEntity.armModel == null) raw.mainEntity.armModel = raw.mainEntity.mainModel;
    }

    private static Map<String, RawYsmModel.RawAnimationFile> presets() throws IOException {
        Map<String, RawYsmModel.RawAnimationFile> result = cached;
        if (result != null) return result;
        synchronized (NativeBbmodelActions.class) {
            result = cached;
            if (result == null) {
                Map<String, RawYsmModel.RawAnimationFile> loaded = new LinkedHashMap<>();
                for (String family : List.of("main", "extra", "fp_arm")) {
                    String file = family.equals("fp_arm") ? "fp.arm.animation.json" : family + ".animation.json";
                    try (InputStream stream = NativeBbmodelActions.class.getResourceAsStream(BASE + file)) {
                        if (stream == null) {
                            BbImportHost.LOGGER.warn("[MPA] Missing builtin bbmodel action preset file {}", file);
                            continue;
                        }
                        var animations = YsmAnimationParsing.parseAnimationFile(stream.readAllBytes());
                        BbRotationCompat.convertRawAnimationFile(animations);
                        animations.animType = YsmAnimationParsing.getAnimTypeFromKey(family); loaded.put(family, animations);
                    } catch (Exception error) {
                        BbImportHost.LOGGER.warn("[MPA] Failed to load builtin bbmodel action preset file {}", file, error);
                    }
                }
                result = Collections.unmodifiableMap(loaded); cached = result;
            }
        }
        return result;
    }

    private static RawYsmModel.RawAnimation remap(RawYsmModel.RawAnimation source, SemanticSkeleton skeleton) {
        RawYsmModel.RawAnimation derived = copy(source, source.name);
        derived.boneAnimations = new ArrayList<>();
        for (var animation : source.boneAnimations) {
            String semantic = PRESET_BONE_SEMANTICS.get(normalizeBoneName(animation.boneName));
            String actual = semantic == null ? null : skeleton.getTarget(semantic);
            if (actual == null || actual.equals(animation.boneName)) derived.boneAnimations.add(animation);
            else {
                var bone = new RawYsmModel.RawBoneAnimation(); bone.boneName = actual;
                bone.rotation = animation.rotation; bone.position = animation.position; bone.scale = animation.scale;
                derived.boneAnimations.add(bone);
            }
        }
        return derived;
    }

    private static RawYsmModel.RawAnimation copy(RawYsmModel.RawAnimation source, String name) {
        var result = new RawYsmModel.RawAnimation(); result.name = name; result.length = source.length;
        result.loopMode = source.loopMode; result.blendWeight = source.blendWeight;
        result.unkInt1 = source.unkInt1; result.unkInt2 = source.unkInt2; result.unkInt4 = source.unkInt4;
        result.boneAnimations = source.boneAnimations; result.timelineEvents = source.timelineEvents; result.soundEffects = source.soundEffects;
        return result;
    }

    private static void aliases(Map<String, RawYsmModel.RawAnimation> clips) {
        for (String[] pair : new String[][]{
                {"hold_mainhand:lance","hold_mainhand:spear"}, {"hold_offhand:lance","hold_offhand:spear"}, {"swing:lance","swing:spear"},
                {"use_mainhand:lance","use_mainhand:spear"}, {"use_offhand:lance","use_offhand:spear"},
                {"lance_stand","hold_mainhand:lance"}, {"lance_jab","swing:lance"}, {"lance_lunge","swing:lance"},
                {"lance_charge","use_mainhand:lance"}, {"lance_riding_idle","hold_mainhand:lance"},
                {"lance_riding_charge","use_mainhand:lance"}, {"lance_fall_flying_charge","use_mainhand:lance"},
                {"hold_mainhand:mace","hold_mainhand$minecraft:mace"}, {"hold_offhand:mace","hold_offhand$minecraft:mace"}, {"swing:mace","swing$minecraft:mace"}}) {
            var original = clips.get(pair[1]); if (original != null) clips.putIfAbsent(pair[0], copy(original, pair[0]));
        }
    }

    private static RawYsmModel.RawAnimation firstPerson(String name, RawYsmModel.RawAnimation source) {
        if (source == null || source.boneAnimations.isEmpty()) return null;
        Map<String, RawYsmModel.RawBoneAnimation> bones = new LinkedHashMap<>();
        for (var bone : source.boneAnimations) {
            String target = firstPersonArmTarget(bone.boneName);
            if (target == null) continue;
            var derived = new RawYsmModel.RawBoneAnimation(); derived.boneName = target;
            derived.rotation = bone.rotation; derived.position = bone.position; derived.scale = bone.scale;
            if (target.equals(bone.boneName)) bones.put(target, derived); else bones.putIfAbsent(target, derived);
        }
        if (bones.isEmpty()) return null;
        var result = copy(source, name); result.boneAnimations = new ArrayList<>(bones.values());
        result.timelineEvents = new ArrayList<>(); result.soundEffects = new ArrayList<>(); return result;
    }

    private static String firstPersonArmTarget(String name) {
        return switch (normalizeBoneName(name)) {
            case "leftarm","leftupperarm","leftuparm","leftshoulder","leftbicep" -> "LeftArm";
            case "leftforearm","leftlowerarm","leftelbow" -> "LeftForeArm";
            case "lefthand","leftwrist","leftpalm" -> "LeftHand";
            case "lefthandlocator","leftitem" -> "LeftHandLocator";
            case "rightarm","rightupperarm","rightuparm","rightshoulder","rightbicep" -> "RightArm";
            case "rightforearm","rightlowerarm","rightelbow" -> "RightForeArm";
            case "righthand","rightwrist","rightpalm" -> "RightHand";
            case "righthandlocator","rightitem" -> "RightHandLocator";
            default -> null;
        };
    }
    private static final Map<String, String> PRESET_BONE_SEMANTICS = Map.ofEntries(
            Map.entry("head", "HEAD"),
            Map.entry("allhead", "HEAD"),
            Map.entry("body", "BODY"),
            Map.entry("waist", "BODY"),
            Map.entry("torso", "BODY"),
            Map.entry("upperbody", "BODY"),
            Map.entry("chest", "BODY"),
            Map.entry("leftarm", "LEFT_UPPER_ARM"),
            Map.entry("rightarm", "RIGHT_UPPER_ARM"),
            Map.entry("armleft", "LEFT_UPPER_ARM"),
            Map.entry("armright", "RIGHT_UPPER_ARM"),
            Map.entry("leftforearm", "LEFT_FOREARM"),
            Map.entry("rightforearm", "RIGHT_FOREARM"),
            Map.entry("lefthand", "LEFT_HAND"),
            Map.entry("righthand", "RIGHT_HAND"),
            Map.entry("leftleg", "LEFT_UPPER_LEG"),
            Map.entry("rightleg", "RIGHT_UPPER_LEG"),
            Map.entry("legleft", "LEFT_UPPER_LEG"),
            Map.entry("legright", "RIGHT_UPPER_LEG"),
            Map.entry("leftlowerleg", "LEFT_LOWER_LEG"),
            Map.entry("rightlowerleg", "RIGHT_LOWER_LEG"),
            Map.entry("leftfoot", "LEFT_FOOT"),
            Map.entry("rightfoot", "RIGHT_FOOT"),
            Map.entry("leftitem", "LEFT_HAND_LOCATOR"),
            Map.entry("rightitem", "RIGHT_HAND_LOCATOR"),
            Map.entry("lefthandlocator", "LEFT_HAND_LOCATOR"),
            Map.entry("righthandlocator", "RIGHT_HAND_LOCATOR")
    );

    private static SemanticSkeleton buildSemanticSkeleton(RawYsmModel.RawGeometry mainModel) {
        LinkedHashMap<String, String> normalizedToActual = new LinkedHashMap<>();
        if (mainModel != null && mainModel.bones != null) {
            for (RawYsmModel.RawBone bone : mainModel.bones) {
                String actual = bone.name;
                if (actual == null || actual.isEmpty()) {
                    continue;
                }
                String normalized = normalizeBoneName(actual);
                if (!normalized.isEmpty()) {
                    normalizedToActual.putIfAbsent(normalized, actual);
                }
            }
        }
        LinkedHashMap<String, String> bones = new LinkedHashMap<>();
        registerSemanticBone(normalizedToActual, bones, "HEAD", "head", "allhead", "vanillahead");
        registerSemanticBone(normalizedToActual, bones, "BODY", "body", "torso", "chest", "upperbody", "vanillabody", "waist");
        registerSemanticBone(normalizedToActual, bones, "LEFT_UPPER_ARM", "leftarm", "leftupperarm", "leftshoulder", "vanillaleftarm", "armleft");
        registerSemanticBone(normalizedToActual, bones, "RIGHT_UPPER_ARM", "rightarm", "rightupperarm", "rightshoulder", "vanillarightarm", "armright");
        registerSemanticBone(normalizedToActual, bones, "LEFT_FOREARM", "leftforearm", "leftlowerarm", "leftelbow");
        registerSemanticBone(normalizedToActual, bones, "RIGHT_FOREARM", "rightforearm", "rightlowerarm", "rightelbow");
        registerSemanticBone(normalizedToActual, bones, "LEFT_HAND", "lefthand", "leftwrist", "leftpalm");
        registerSemanticBone(normalizedToActual, bones, "RIGHT_HAND", "righthand", "rightwrist", "rightpalm");
        registerSemanticBone(normalizedToActual, bones, "LEFT_UPPER_LEG", "leftleg", "leftupperleg", "leftthigh", "vanillaleftleg", "legleft");
        registerSemanticBone(normalizedToActual, bones, "RIGHT_UPPER_LEG", "rightleg", "rightupperleg", "rightthigh", "vanillarightleg", "legright");
        registerSemanticBone(normalizedToActual, bones, "LEFT_LOWER_LEG", "leftlowerleg", "leftshin", "leftcalf");
        registerSemanticBone(normalizedToActual, bones, "RIGHT_LOWER_LEG", "rightlowerleg", "rightshin", "rightcalf");
        registerSemanticBone(normalizedToActual, bones, "LEFT_FOOT", "leftfoot", "leftboot");
        registerSemanticBone(normalizedToActual, bones, "RIGHT_FOOT", "rightfoot", "rightboot");
        registerSemanticBone(normalizedToActual, bones, "LEFT_HAND_LOCATOR", "lefthandlocator", "leftitem");
        registerSemanticBone(normalizedToActual, bones, "RIGHT_HAND_LOCATOR", "righthandlocator", "rightitem");
        return bones.isEmpty() ? SemanticSkeleton.EMPTY : new SemanticSkeleton(bones);
    }

    private static void registerSemanticBone(Map<String, String> normalizedToActual, Map<String, String> out, String semanticName, String... candidates) {
        for (String candidate : candidates) {
            String actual = normalizedToActual.get(candidate);
            if (actual != null) {
                out.putIfAbsent(semanticName, actual);
                return;
            }
        }
    }

    private static String normalizeBoneName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
