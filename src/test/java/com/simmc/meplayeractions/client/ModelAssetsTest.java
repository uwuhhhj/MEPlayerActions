package com.simmc.meplayeractions.client;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;
class ModelAssetsTest {
    @Test void shippedGeometryTexturesAndAnimationsRoundTripThroughBoundedOrderedChunks() throws Exception {
        for (String id : List.of("ysm_01_jk_player", "ysm_01_jk_npc")) {
            byte[] raw = Files.readAllBytes(Path.of("examples/blueprints/npc", id + ".bbmodel"));
            var asset = ModelAssets.pack(id, raw); assertEquals(raw.length, asset.rawBytes());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)), asset.hash());
            assertTrue(asset.compressed().length <= ModelAssets.MAX_COMPRESSED_BYTES);
            ByteArrayOutputStream assembled = new ByteArrayOutputStream();
            for (int i = 0; i < asset.chunks(9000); i++) { byte[] chunk = asset.chunk(i, 9000);
                assertTrue(chunk.length <= 9000); assembled.write(Base64.getDecoder().decode(Base64.getEncoder().encodeToString(chunk))); }
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(assembled.toByteArray()))) { assertArrayEquals(raw, gzip.readAllBytes()); }
            assertThrows(IllegalArgumentException.class, () -> asset.chunk(asset.chunks(9000), 9000));
        }
    }
    @Test void invalidAndOversizedAssetsCannotEnterTransferCache() {
        assertThrows(IOException.class, () -> ModelAssets.pack("x", new byte[0]));
        assertThrows(IOException.class, () -> ModelAssets.pack("x", new byte[ModelAssets.MAX_RAW_BYTES + 1]));
        assertThrows(IOException.class, () -> ModelAssets.pack("x", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
