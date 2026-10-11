package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.client.model.nativebbmodel.BBModelFile;
import com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel;

/** Author BBModel channels retain their editor axes through the existing native runtime. */
final class NativeBbModelAnimations {
    private NativeBbModelAnimations() { }

    /**
     * Mark authored clips after editor-mode conversion, before action aliases and presets are assembled.
     * Version 4 rotates bone X/Y and translates position X negatively; version 5 uses positive channels.
     * Both use ZYX, so numerical and Molang values need no Euler decomposition or script rewriting.
     * The per-clip marker makes the existing evaluator apply signs after the complete expression returns.
     * https://github.com/JannisX11/blockbench/blob/v5.0.0/js/io/formats/bbmodel.js#L67-L93
     * https://github.com/JannisX11/blockbench/blob/v4.10.0/js/animations/timeline_animators.js#L324-L353
     * https://github.com/JannisX11/blockbench/blob/v5.0.0/js/io/format.ts#L632
     */
    static void retainEditorAxes(BBModelFile source, RawYsmModel raw) {
        String version = source.meta.format_version;
        int separator = version.indexOf('.');
        int major = Integer.parseInt(separator < 0 ? version : version.substring(0, separator));
        var file = raw.mainEntity.animationFiles.get("animation-main");
        if (file == null) return;
        for (var authored : source.animations) {
            var animation = file.animations.get(authored.name);
            if (animation != null) animation.bbEditorAxes = major < 5 ? 4 : 5;
        }
    }
}
