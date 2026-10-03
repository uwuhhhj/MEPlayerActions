package com.simmc.meplayeractions.client.ui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class PreviewAtlasUvTest {
    @Test void tinyDefaultWhiteEyeCannotSampleAdjacentTransparentOrSkinPixels() throws Exception {
        BufferedImage source;
        try (var input = getClass().getResourceAsStream("/assets/meplayeractions/builtin/openysm_default/textures/default.png")) {
            assertNotNull(input); source = ImageIO.read(input);
        }
        PreviewAtlasUv rect = new PreviewAtlasUv(131, 7, source.getWidth(), source.getHeight(), 512, 256);
        for (float pixelX : new float[]{23.05f, 23.95f, 24.05f, 24.95f})
            for (float pixelY : new float[]{42.05f, 42.95f, 43.05f, 43.95f}) {
                int sampledX = (int) Math.floor(rect.u(pixelX / source.getWidth()) * rect.atlasWidth() - rect.x());
                int sampledY = (int) Math.floor(rect.v(pixelY / source.getHeight()) * rect.atlasHeight() - rect.y());
                int pixel = source.getRGB(sampledX, sampledY);
                assertEquals(255, pixel >>> 24); assertTrue((pixel & 255) >= 242);
                assertEquals((int) pixelX, sampledX); assertEquals((int) pixelY, sampledY);
            }
        assertEquals(0, source.getRGB(25, 42) >>> 24, "The neighbouring source pixel is transparent");
        assertTrue((source.getRGB(23, 44) & 255) < 242, "The next row belongs to skin, not the eye");
    }

    @Test void everyAtlasRegionPreservesOriginalPixelBoundariesWithoutWholeImageInset() {
        for (PreviewAtlasUv rect : new PreviewAtlasUv[]{new PreviewAtlasUv(1, 1, 128, 128, 256, 256),
                new PreviewAtlasUv(257, 3, 64, 32, 512, 128), new PreviewAtlasUv(0, 0, 4096, 4096, 4096, 4096)}) {
            assertEquals(rect.x(), rect.u(0) * rect.atlasWidth(), .0001);
            assertEquals(rect.x() + rect.width(), rect.u(1) * rect.atlasWidth(), .0001);
            assertEquals(rect.y(), rect.v(0) * rect.atlasHeight(), .0001);
            assertEquals(rect.y() + rect.height(), rect.v(1) * rect.atlasHeight(), .0001);
            assertEquals(rect.x() + 23, rect.u(23f / rect.width()) * rect.atlasWidth(), .0001);
            assertEquals(rect.y() + 17, rect.v(17f / rect.height()) * rect.atlasHeight(), .0001);
            assertEquals(rect.u(0), rect.u(-1)); assertEquals(rect.v(1), rect.v(2));
        }
    }
}
