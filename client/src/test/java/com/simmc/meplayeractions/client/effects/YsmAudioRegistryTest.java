package com.simmc.meplayeractions.client.effects;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Container fixtures test validation and route authority; native Vorbis decoding is verified in the real client. */
class YsmAudioRegistryTest {
    @Test void validatesVorbisIdentificationPageCrcAndEos() {
        byte[] bytes = container(1); assertDoesNotThrow(() -> YsmAudioRegistry.validateOgg(bytes));
        byte[] corrupt = bytes.clone(); corrupt[44] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> YsmAudioRegistry.validateOgg(corrupt));
        byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length - 1);
        assertThrows(IllegalArgumentException.class, () -> YsmAudioRegistry.validateOgg(truncated));
        byte[] noEos = bytes.clone(); noEos[5] = 2; crc(noEos);
        assertThrows(IllegalArgumentException.class, () -> YsmAudioRegistry.validateOgg(noEos));
    }
    @Test void rejectsUnsupportedCodecChannelsRateAndChainedData() {
        byte[] bytes = container(2); bytes[39] = 3; crc(bytes);
        assertThrows(IllegalArgumentException.class, () -> YsmAudioRegistry.validateOgg(bytes));
        byte[] codec = container(2); codec[29] = 'x'; crc(codec);
        assertThrows(IllegalArgumentException.class, () -> YsmAudioRegistry.validateOgg(codec));
        byte[] appended = java.util.Arrays.copyOf(container(2), 116); System.arraycopy(container(3), 0, appended, 58, 58);
        assertThrows(IllegalArgumentException.class, () -> YsmAudioRegistry.validateOgg(appended));
        assertThrows(IllegalArgumentException.class, () -> YsmAudioRegistry.register(new byte[YsmAudioRegistry.MAX_FILE_BYTES + 1]));
    }
    @Test void eachBindingHasDistinctRevocableRouteWhileCompressedBytesAreShared() {
        byte[] bytes = container(4);
        var first = YsmAudioRegistry.register(bytes); var second = YsmAudioRegistry.register(bytes);
        try {
            assertNotEquals(first.soundId(), second.soundId());
            assertEquals(1, YsmAudioRegistry.diagnostics().get("assets").intValue());
            assertEquals(2, YsmAudioRegistry.diagnostics().get("routes").intValue());
            assertEquals(bytes.length, YsmAudioRegistry.diagnostics().get("compressedBytes").intValue());
            Identifier retired = Identifier.of(YsmAudioRegistry.NAMESPACE, "sounds/" + first.soundId().getPath() + ".ogg");
            first.close(); first.close();
            assertTrue(YsmAudioRegistry.openStream(retired, false).isCompletedExceptionally());
            assertEquals(1, YsmAudioRegistry.diagnostics().get("routes").intValue());
        } finally { first.close(); second.close(); }
        assertEquals(0, YsmAudioRegistry.diagnostics().get("assets").intValue());
        assertEquals(0, YsmAudioRegistry.diagnostics().get("compressedBytes").intValue());
    }
    @Test void stalePrivateIdsFailClosedAndNeverUseAResourcePack() {
        Identifier id = Identifier.of(YsmAudioRegistry.NAMESPACE, "sounds/retired.ogg");
        assertTrue(YsmAudioRegistry.isPrivate(id)); assertTrue(YsmAudioRegistry.openStream(id, true).isCompletedExceptionally());
        assertFalse(YsmAudioRegistry.isPrivate(Identifier.of("minecraft", "sounds/entity/player/hurt.ogg")));
    }
    @Test void routeCountIsBoundedEvenWhenEveryRouteUsesTheSameTinyAsset() {
        List<YsmAudioRegistry.Lease> leases = new ArrayList<>();
        try {
            for (int i = 0; i < YsmAudioRegistry.MAX_ASSETS; i++) leases.add(YsmAudioRegistry.register(container(5)));
            assertThrows(IllegalArgumentException.class, () -> YsmAudioRegistry.register(container(5)));
            assertEquals(YsmAudioRegistry.MAX_ASSETS, YsmAudioRegistry.diagnostics().get("routes").intValue());
        } finally { leases.forEach(YsmAudioRegistry.Lease::close); }
    }
    private static byte[] container(int serial) {
        byte[] bytes = new byte[58];
        bytes[0] = 'O'; bytes[1] = 'g'; bytes[2] = 'g'; bytes[3] = 'S'; bytes[5] = 6;
        bytes[14] = (byte) serial; bytes[26] = 1; bytes[27] = 30; bytes[28] = 1;
        byte[] codec = "vorbis".getBytes(java.nio.charset.StandardCharsets.US_ASCII); System.arraycopy(codec, 0, bytes, 29, codec.length);
        bytes[39] = 1; bytes[40] = 0x44; bytes[41] = (byte) 0xac; bytes[56] = 0x66; bytes[57] = 1;
        crc(bytes); return bytes;
    }
    private static void crc(byte[] bytes) {
        int crc = 0;
        for (int index = 0; index < bytes.length; index++) {
            crc ^= (index >= 22 && index < 26 ? 0 : bytes[index] & 255) << 24;
            for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
        }
        for (int index = 0; index < 4; index++) bytes[22 + index] = (byte) (crc >>> (index * 8));
    }
}
