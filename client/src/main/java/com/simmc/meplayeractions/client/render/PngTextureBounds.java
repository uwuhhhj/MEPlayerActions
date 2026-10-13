package com.simmc.meplayeractions.client.render;

import java.nio.ByteBuffer;

public final class PngTextureBounds {
    private PngTextureBounds() {
    }

    public static long pixels(byte[] png) {
        if (png == null || png.length < 24 || png.length > 8 * 1024 * 1024
                || ByteBuffer.wrap(png).getLong() != 0x89504e470d0a1a0aL) {
            throw new IllegalArgumentException("Texture must be a bounded PNG");
        }
        int width = ByteBuffer.wrap(png, 16, 8).getInt();
        int height = ByteBuffer.wrap(png, 20, 4).getInt();
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Texture dimensions exceed 4096 pixels");
        }
        return (long) width * height;
    }
}
