package com.simmc.meplayeractions.client.network;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** A transfer is tied to a content hash, never to a server-supplied file path. */
public final class AssetTransfer {
    public static final int MAX_RAW = 8 * 1024 * 1024, MAX_COMPRESSED = 4 * 1024 * 1024;
    public static final int MAX_CHUNKS = 16_384;
    private final String modelId, hash;
    private final int rawBytes, compressedBytes;
    private final byte[][] chunks;
    private int receivedBytes, receivedChunks;
    public AssetTransfer(String modelId, String hash, int rawBytes, int compressedBytes, int count) {
        if (!modelId.matches("[a-z0-9_-]{1,64}") || !hash.matches("[0-9a-f]{64}")
                || rawBytes < 1 || rawBytes > MAX_RAW || compressedBytes < 1 || compressedBytes > MAX_COMPRESSED
                || count < 1 || count > MAX_CHUNKS || count > compressedBytes)
            throw new IllegalArgumentException("Asset metadata");
        this.modelId = modelId; this.hash = hash; this.rawBytes = rawBytes; this.compressedBytes = compressedBytes;
        chunks = new byte[count][];
    }
    public void put(int index, String encoded) {
        if (index < 0 || index >= chunks.length || encoded.length() > 12_000) throw new IllegalArgumentException("Chunk");
        byte[] chunk = Base64.getDecoder().decode(encoded);
        if (chunk.length < 1 || chunk.length > 9_000) throw new IllegalArgumentException("Chunk size");
        if (chunks[index] != null) {
            if (!Arrays.equals(chunks[index], chunk)) throw new IllegalArgumentException("Conflicting chunk");
            return;
        }
        if (receivedBytes + chunk.length > compressedBytes) throw new IllegalArgumentException("Transfer overflow");
        chunks[index] = chunk; receivedBytes += chunk.length; receivedChunks++;
    }
    public byte[] finish() throws IOException {
        if (receivedChunks != chunks.length || receivedBytes != compressedBytes) throw new IOException("Incomplete asset");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(compressedBytes);
        for (byte[] chunk : chunks) compressed.write(chunk);
        byte[] raw;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            raw = gzip.readNBytes(rawBytes + 1);
            if (raw.length != rawBytes || gzip.read() != -1) throw new IOException("Asset decompression size");
        }
        if (!hash.equals(hash(raw))) throw new IOException("Asset SHA-256 mismatch");
        return raw;
    }
    public static String hash(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }
    public String hash() { return hash; }
    public String modelId() { return modelId; }
}
