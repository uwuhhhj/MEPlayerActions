package com.simmc.meplayeractions.client;

/**
 * Port of OpenYSM's AnimationFormatValidator at revision
 * 0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85 (MIT).
 * Version is the internal YSM file version, not the folder manifest's spec.
 */
public final class AnimationFormatValidator {
    private AnimationFormatValidator() { }

    public static boolean validate(int version, boolean fromPrimaryAssembly) {
        if (version >= 19) return true;
        return fromPrimaryAssembly;
    }
}
