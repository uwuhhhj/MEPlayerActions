/*
 * Migrated from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT; see assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * Format and conversion code are retained; package/type/logging adapters target MPA.
 */
package com.simmc.meplayeractions.client.model.nativebbmodel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MPA logging bridge; the normalization method is copied from upstream GeometryBaker. */
final class BbImportHost {
    static final Logger LOGGER = LoggerFactory.getLogger("MEPlayerActions/BBModel");
    private BbImportHost() { }
    static boolean animationDebugLog() { return LOGGER.isDebugEnabled(); }
    public static String normalizeBoneName(String name) {
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
