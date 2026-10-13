package com.simmc.meplayeractions.client.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class PngTextureBoundsTest {
    @Test
    void preflightsNativePixelBytesUsingLongArithmetic() {
        assertEquals(16L * 1024 * 1024, PngTextureBounds.pixels(header(4096, 4096)));
        assertEquals(64L * 1024 * 1024, PngTextureBounds.pixels(header(4096, 4096)) * 4);
        assertEquals(128L * 1024 * 1024,
                (PngTextureBounds.pixels(header(4096, 4096)) + 16L * 1024 * 1024) * 4);
        assertEquals(6, PngTextureBounds.pixels(header(2, 3)));
    }

    @Test
    void rejectsInvalidSignatureLengthAndDimensionsBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> PngTextureBounds.pixels(null));
        assertThrows(IllegalArgumentException.class, () -> PngTextureBounds.pixels(new byte[23]));
        assertThrows(IllegalArgumentException.class, () -> PngTextureBounds.pixels(new byte[24]));
        assertThrows(IllegalArgumentException.class, () -> PngTextureBounds.pixels(new byte[8 * 1024 * 1024 + 1]));
        for (int dimension : new int[] { 0, -1, 4097, Integer.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> PngTextureBounds.pixels(header(dimension, 1)));
            assertThrows(IllegalArgumentException.class, () -> PngTextureBounds.pixels(header(1, dimension)));
        }
    }

    private static byte[] header(int width, int height) {
        byte[] png = new byte[24];
        ByteBuffer.wrap(png).putLong(0x89504e470d0a1a0aL);
        ByteBuffer.wrap(png, 16, 8).putInt(width).putInt(height);
        return png;
    }
}
