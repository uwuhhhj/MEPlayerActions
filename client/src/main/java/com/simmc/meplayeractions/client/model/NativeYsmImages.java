package com.simmc.meplayeractions.client.model;

import org.glavo.webp.WebPImageReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Mature YSM image inputs, normalized for the Minecraft renderer after metadata is bounded. */
public final class NativeYsmImages {
    private static final int MAX_BYTES = LocalModelBudget.MAX_BYTES;
    private NativeYsmImages() { }
    public static boolean supportedPath(String path) { return path.matches("(?i).+\\.(png|bmp|jpg|jpeg|webp)"); }

    public static byte[] png(byte[] data, int format, int declaredWidth, int declaredHeight) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) throw new IOException("YSM 图像大小无效");
        BufferedImage image;
        if (format == -1) {
            dimensions(declaredWidth, declaredHeight);
            if (data.length != (long) declaredWidth * declaredHeight * 4) throw new IOException("YSM RGBA 贴图长度错误");
            image = new BufferedImage(declaredWidth, declaredHeight, BufferedImage.TYPE_INT_ARGB); int[] pixels = new int[declaredWidth * declaredHeight];
            for (int i = 0; i < pixels.length; i++) pixels[i] = (data[i * 4 + 3] & 255) << 24 | (data[i * 4] & 255) << 16 | (data[i * 4 + 1] & 255) << 8 | data[i * 4 + 2] & 255;
            image.setRGB(0, 0, declaredWidth, declaredHeight, pixels, 0, declaredWidth);
        } else if (webp(data)) {
            int[] size = webpDimensions(data); declaredSize(size[0], size[1], declaredWidth, declaredHeight);
            try (WebPImageReader reader = WebPImageReader.open(new ByteArrayInputStream(data))) {
                declaredSize(reader.getWidth(), reader.getHeight(), size[0], size[1]);
                var frame = reader.readNextFrame(); if (frame == null) throw new IOException("WebP 未包含图像帧");
                image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, frame.getWidth(), frame.getHeight(), frame.getArgbArray(), 0, frame.getScanlineStride());
            } catch (org.glavo.webp.WebPException invalid) { throw new IOException("YSM WebP 解码失败", invalid); }
        } else {
            if (format == 4 || format == 5) throw new IOException("原生 YSM 图像编码尚未接入或文件标识错误: " + format);
            boolean png = data.length >= 8 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
            boolean bmp = data.length >= 2 && data[0] == 'B' && data[1] == 'M';
            boolean jpeg = data.length >= 3 && data[0] == (byte) 0xff && data[1] == (byte) 0xd8 && data[2] == (byte) 0xff;
            if (!png && !bmp && !jpeg) throw new IOException("YSM 图像不是 PNG、BMP、JPEG 或 WebP");
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(stream); if (!readers.hasNext()) throw new IOException("YSM 图像编码无效");
                var reader = readers.next();
                try {
                    reader.setInput(stream); int width = reader.getWidth(0), height = reader.getHeight(0); declaredSize(width, height, declaredWidth, declaredHeight);
                    if (png) {
                        BbModel.validatePng(data, 16_777_216); return data.clone();
                    }
                    image = reader.read(0);
                } finally { reader.dispose(); }
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (image == null || !ImageIO.write(image, "png", output) || output.size() > MAX_BYTES) throw new IOException("YSM 图像转换失败");
        return output.toByteArray();
    }

    public static int[] webpDimensions(byte[] data) throws IOException {
        if (!webp(data) || unsignedInt(data, 4) != data.length - 8L) throw new IOException("WebP RIFF 长度无效");
        int[] canvas = {0, 0}; inspectChunks(data, 12, data.length, canvas, 0);
        if (canvas[0] == 0) throw new IOException("WebP 缺少有效图像尺寸"); return canvas;
    }
    private static void inspectChunks(byte[] data, int offset, int end, int[] canvas, int depth) throws IOException {
        if (depth > 1) throw new IOException("WebP 图像块层级无效");
        while (offset < end) {
            if (end - offset < 8) throw new IOException("WebP 图像块头不完整");
            long size = unsignedInt(data, offset + 4); int start = offset + 8;
            if (size > end - start) throw new IOException("WebP 图像块过大"); int count = (int) size; int width = 0, height = 0;
            if (tag(data, offset, "VP8X")) {
                if (count < 10) throw new IOException("WebP VP8X 头不完整"); width = uint24(data, start + 4) + 1; height = uint24(data, start + 7) + 1;
            } else if (tag(data, offset, "VP8L")) {
                if (count < 5 || data[start] != 0x2f) throw new IOException("WebP VP8L 头无效"); long bits = unsignedInt(data, start + 1);
                width = (int) (bits & 0x3fff) + 1; height = (int) (bits >>> 14 & 0x3fff) + 1;
            } else if (tag(data, offset, "VP8 ")) {
                if (count < 10 || data[start + 3] != (byte) 0x9d || data[start + 4] != 1 || data[start + 5] != 0x2a) throw new IOException("WebP VP8 头无效");
                width = ((data[start + 6] & 255) | (data[start + 7] & 255) << 8) & 0x3fff;
                height = ((data[start + 8] & 255) | (data[start + 9] & 255) << 8) & 0x3fff;
            } else if (tag(data, offset, "ANMF")) {
                if (count < 16) throw new IOException("WebP 动画帧头不完整"); width = uint24(data, start + 6) + 1; height = uint24(data, start + 9) + 1;
                dimensions(width, height); inspectChunks(data, start + 16, start + count, new int[]{0, 0}, depth + 1);
            }
            if (width > 0 || height > 0) { dimensions(width, height); if (canvas[0] == 0) { canvas[0] = width; canvas[1] = height; } }
            long next = start + size + (size & 1); if (next > end) throw new IOException("WebP 图像块对齐不完整"); offset = (int) next;
        }
    }
    private static boolean webp(byte[] bytes) { return bytes.length >= 12 && tag(bytes, 0, "RIFF") && tag(bytes, 8, "WEBP"); }
    private static boolean tag(byte[] bytes, int index, String value) { for (int i = 0; i < 4; i++) if (bytes[index + i] != value.charAt(i)) return false; return true; }
    private static long unsignedInt(byte[] bytes, int index) { return (bytes[index] & 255L) | (bytes[index + 1] & 255L) << 8 | (bytes[index + 2] & 255L) << 16 | (bytes[index + 3] & 255L) << 24; }
    private static int uint24(byte[] bytes, int index) { return (bytes[index] & 255) | (bytes[index + 1] & 255) << 8 | (bytes[index + 2] & 255) << 16; }
    private static void declaredSize(int width, int height, int declaredWidth, int declaredHeight) throws IOException {
        dimensions(width, height);
        if (declaredWidth > 0 && width != declaredWidth || declaredHeight > 0 && height != declaredHeight) throw new IOException("YSM 图像尺寸不匹配");
    }
    private static void dimensions(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 16_777_216) throw new IOException("YSM 图像尺寸超出限制");
    }
}
