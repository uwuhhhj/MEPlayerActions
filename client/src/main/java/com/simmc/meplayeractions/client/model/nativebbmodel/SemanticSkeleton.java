/*
 * Migrated from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT; see assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * Source semantics are retained; package/type adapters target MPA.
 */
package com.simmc.meplayeractions.client.model.nativebbmodel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SemanticSkeleton {

    public static final SemanticSkeleton EMPTY = new SemanticSkeleton(Collections.emptyMap());

    private final Map<String, String> boneTargets;

    public SemanticSkeleton(Map<String, String> boneTargets) {
        this.boneTargets = Collections.unmodifiableMap(new LinkedHashMap<>(boneTargets));
    }

    public Map<String, String> getBoneTargets() {
        return this.boneTargets;
    }

    public String getTarget(String semanticBone) {
        return this.boneTargets.get(semanticBone);
    }
}
