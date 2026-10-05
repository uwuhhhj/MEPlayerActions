/* Copyright (c) 2026 OpenYSM. MIT; adapted from Sparkle-Morpher b1230a4 YsmZstd. */
package com.simmc.meplayeractions.client.model.nativeysm;
import com.simmc.meplayeractions.client.model.LocalModelBudget;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
public final class YsmZstd {
    private YsmZstd() { }
    public static byte[] decompress(byte[] data, int offset, int length) throws IOException {
        return decompress(data, offset, length, 8 * 1024 * 1024);
    }
    public static byte[] decompress(byte[] data, int offset, int length, int maximumBytes) throws IOException {
        if (maximumBytes < 1 || maximumBytes > LocalModelBudget.MAX_BYTES) throw new IOException("Invalid YSM decompression budget");
        if (offset < 0 || length < 5 || offset > data.length - length) throw new IOException("Invalid YSM compressed slice");
        byte[] frame = Arrays.copyOfRange(data, offset, offset + length);
        try { return readFrame(frame, true, maximumBytes); }
        catch (IOException | RuntimeException strict) {
            frame = Arrays.copyOfRange(data, offset, offset + length);
            try { return readFrame(frame, false, maximumBytes); }
            catch (IOException | RuntimeException legacy) { strict.addSuppressed(legacy); throw new IOException("Invalid YSM Zstd stream", strict); }
        }
    }
    private static byte[] readFrame(byte[] frame, boolean checksum, int maximumBytes) throws IOException {
        washInPlace(frame, 0, frame.length, checksum);
        try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            byte[] clear = input.readNBytes(maximumBytes + 1);
            if (clear.length > maximumBytes) throw new IOException("YSM decompressed data exceeds " + maximumBytes / (1024 * 1024) + " MiB");
            return clear;
        }
    }
    private static void washInPlace(byte[] data, int base, int length, boolean preserveChecksumFlag) {
        if (data == null || length < 5) {
            throw new IllegalArgumentException("Invalid data length");
        }

        int magic = (data[base] & 0xFF)
                | ((data[base + 1] & 0xFF) << 8)
                | ((data[base + 2] & 0xFF) << 16)
                | ((data[base + 3] & 0xFF) << 24);
        if (magic != 0xFD2FB528) {
            throw new IllegalArgumentException("Not a standard ZSTD Magic Number. May be skippable frame or unknown.");
        }

        byte fhd = data[base + 4];
        if (!preserveChecksumFlag) {
            data[base + 4] = (byte) (fhd & 0xFB);
        }

        int frameHeaderSize = calculateFrameHeaderSize(fhd);
        int offset = base + 4 + frameHeaderSize;
        int end = base + length;

        while (offset + 3 <= end) {
            int b0 = data[offset] & 0xFF;
            int b1 = data[offset + 1] & 0xFF;
            int b2 = data[offset + 2] & 0xFF;
            int lastBlock = (b0 >> 7) & 1;
            int blockTypeYSM = (b0 >> 5) & 3;

            int rawSize = ((b0 & 0x1F) << 16) | b1 | (b2 << 8);
            int cSize = rawSize ^ 0xD4E9;
            int blockTypeStd = switch (blockTypeYSM) {
                case 0 -> 2;
                case 1 -> 1;
                case 2 -> 3;
                case 3 -> 0;
                default -> throw new IllegalStateException("Unknown block type");
            };

            int stdHeader = lastBlock | (blockTypeStd << 1) | (cSize << 3);

            data[offset] = (byte) (stdHeader & 0xFF);
            data[offset + 1] = (byte) ((stdHeader >> 8) & 0xFF);
            data[offset + 2] = (byte) ((stdHeader >> 16) & 0xFF);

            int blockDataSize = (blockTypeStd == 1) ? 1 : cSize;
            if (blockDataSize < 0 || blockDataSize > end - offset - 3)
                throw new IllegalArgumentException("Truncated YSM Zstd block");
            offset += 3 + blockDataSize;

            if (lastBlock == 1) {
                break;
            }
        }
    }

    private static int calculateFrameHeaderSize(byte fhd) {
        int size = 1;
        int fcsFieldSize = fhd & 3;
        boolean singleSegment = ((fhd >> 5) & 1) == 1;
        int dictIdFlag = (fhd >> 0) & 3;

        int dictIdSize = 0;
        int dictIdBits = fhd & 3;
        if (dictIdBits == 1) dictIdSize = 1;
        else if (dictIdBits == 2) dictIdSize = 2;
        else if (dictIdBits == 3) dictIdSize = 4;

        int fcsSize = 0;
        int fcsBits = (fhd >> 6) & 3;
        if (fcsBits == 0) fcsSize = singleSegment ? 1 : 0;
        else if (fcsBits == 1) fcsSize = 2;
        else if (fcsBits == 2) fcsSize = 4;
        else if (fcsBits == 3) fcsSize = 8;

        int windowDescSize = singleSegment ? 0 : 1;

        return size + windowDescSize + dictIdSize + fcsSize;
    }
}
