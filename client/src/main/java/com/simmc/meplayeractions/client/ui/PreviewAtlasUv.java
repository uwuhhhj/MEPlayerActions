package com.simmc.meplayeractions.client.ui;

/** Preserve source pixel boundaries: a whole-image half-pixel inset distorts tiny eye UVs. */
record PreviewAtlasUv(int x, int y, int width, int height, int atlasWidth, int atlasHeight) {
    float u(float value) { return (x + Math.clamp(value, 0f, 1f) * width) / atlasWidth; }
    float v(float value) { return (y + Math.clamp(value, 0f, 1f) * height) / atlasHeight; }
}
