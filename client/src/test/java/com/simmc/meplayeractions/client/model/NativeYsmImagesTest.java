package com.simmc.meplayeractions.client.model;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class NativeYsmImagesTest {
    // Independently generated lossless WebP: two pixels, opaque red and half-transparent blue.
    private static final byte[] WEBP = Base64.getDecoder().decode("UklGRiAAAABXRUJQVlA4TBMAAAAvAQAAEA8Q+z//D/yPChWI6H8AAA==");

    @Test void pureJavaWebpAndRgbaRetainPixelColorAndAlpha() throws Exception {
        BufferedImage webp = ImageIO.read(new ByteArrayInputStream(NativeYsmImages.png(WEBP, 4, 2, 1)));
        assertEquals(2, webp.getWidth()); assertEquals(1, webp.getHeight());
        assertEquals(0xffff0000, webp.getRGB(0,0)); assertEquals(0x800000ff, webp.getRGB(1,0));
        BufferedImage rgba = ImageIO.read(new ByteArrayInputStream(NativeYsmImages.png(new byte[]{(byte)255,0,0,(byte)255,0,0,(byte)255,(byte)128}, -1, 2, 1)));
        assertEquals(webp.getRGB(0,0), rgba.getRGB(0,0)); assertEquals(webp.getRGB(1,0), rgba.getRGB(1,0));
    }

    @Test void bmpAndJpegAreConvertedAfterDimensionsAreChecked() throws Exception {
        BufferedImage rgb = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB); rgb.setRGB(0,0,0xff21a9d5);
        for (String format : java.util.List.of("bmp", "jpg")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); assertTrue(ImageIO.write(rgb, format, bytes));
            byte[] raw = bytes.toByteArray(); byte[] png = NativeYsmImages.png(raw, 0, 3, 2);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png)); assertEquals(3, image.getWidth()); assertEquals(2, image.getHeight());
            if (format.equals("bmp")) assertEquals(rgb.getRGB(0,0), image.getRGB(0,0));
            assertThrows(IOException.class, () -> NativeYsmImages.png(raw, 0, 4, 2));
        }
    }

    @Test void malformedAndOversizeWebpAndUnexpectedImageTypesAreRejected() {
        byte[] wrongLength = WEBP.clone(); wrongLength[4]++;
        assertThrows(IOException.class, () -> NativeYsmImages.png(wrongLength, 4, 0, 0));
        byte[] oversize = WEBP.clone(); oversize[22] = (byte)255; oversize[23] = (byte)255;
        assertThrows(IOException.class, () -> NativeYsmImages.png(oversize, 4, 0, 0));
        assertThrows(IOException.class, () -> NativeYsmImages.png(new byte[]{1,2,3,4}, -1, 8193, 1));
        assertThrows(IOException.class, () -> NativeYsmImages.png(new byte[]{1,2,3,4}, 5, 1, 1));
        assertThrows(IOException.class, () -> NativeYsmImages.png("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, 0, 0));
    }
}
