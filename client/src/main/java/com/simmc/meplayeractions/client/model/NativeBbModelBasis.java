package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel;

/** Blockbench editor rest transforms at the host boundary of the fixed Sparkle BBModel converter. */
final class NativeBbModelBasis {
    private NativeBbModelBasis() { }

    /**
     * Run exactly once after BBToRawConverter, never for YSM binary/folder/runtime assets.
     * The fixed converter negates initial bone and locator X/Y, while its geometry keeps editor coordinates.
     * Blockbench 4.10 uses the original degree angles in ZYX order:
     * https://github.com/JannisX11/blockbench/blob/v4.10.0/js/preview/canvas.js#L944-L966
     * Blockbench 5 keeps those rest angles; its pre-5 compatibility migration changes animation channels only:
     * https://github.com/JannisX11/blockbench/blob/v5.0.0/js/io/formats/bbmodel.js#L67-L93
     */
    static void restoreEditorRotations(RawYsmModel raw) {
        restore(raw.mainEntity.mainModel);
        restore(raw.mainEntity.armModel);
    }

    private static void restore(RawYsmModel.RawGeometry geometry) {
        if (geometry == null) return;
        for (var bone : geometry.bones) {
            bone.rotation[0] = -bone.rotation[0];
            bone.rotation[1] = -bone.rotation[1];
        }
    }
}
