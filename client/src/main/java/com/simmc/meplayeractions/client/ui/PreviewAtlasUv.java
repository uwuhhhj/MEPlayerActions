package com.simmc.meplayeractions.client.ui;

/** Preserve source pixel boundaries: a whole-image half-pixel inset distorts tiny eye UVs. */
record PreviewAtlasUv(int x, int y, int width, int height, int atlasWidth, int atlasHeight) {
    float u(float value) {
        return (this.x + Math.clamp(value, 0f, 1f) * this.width) / this.atlasWidth;
    }

    float v(float value) {
        return (this.y + Math.clamp(value, 0f, 1f) * this.height) / this.atlasHeight;
    }
}
