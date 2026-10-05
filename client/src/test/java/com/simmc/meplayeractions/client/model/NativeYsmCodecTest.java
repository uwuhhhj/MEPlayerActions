package com.simmc.meplayeractions.client.model;

import com.github.luben.zstd.Zstd;
import com.simmc.meplayeractions.client.model.nativeysm.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeYsmCodecTest {
    @Test void maturePublicCryptoThreeTextHeaderSupportsLargerLocalFilesWithoutChangingNetworkLimits() throws Exception {
        byte[] clear=new byte[com.simmc.meplayeractions.client.network.AssetTransfer.MAX_RAW+1];
        new java.util.Random(17).nextBytes(clear);
        byte[] file=publicFile(clear,"\uFEFFYSGP\r\n\r\n----------------------- [ Metadata ] -----------------------\r\n<name> Test model\r\n\0");
        assertTrue(file.length>com.simmc.meplayeractions.client.network.AssetTransfer.MAX_RAW);
        assertArrayEquals(clear,PublicYsmCodec.decode(file));
        assertEquals(8*1024*1024,com.simmc.meplayeractions.client.network.AssetTransfer.MAX_RAW);
    }
    @Test void independentlyWrittenLegacyPublicFileImportsItsRealGeometryAndTexture() throws Exception {
        byte[] clear = legacyBinary(); byte[] file = publicFile(clear);
        assertArrayEquals(clear, PublicYsmCodec.decode(file));
        var imported = NativeYsmFile.read(file, null); var model = BbModel.parse(imported.raw());
        assertEquals(1, model.animationFormatVersion()); assertEquals(4, model.sample(0, List.of()).size());
        assertEquals(1, imported.profile().textures().size()); assertEquals("default", imported.profile().defaultTexture());
        assertTrue(imported.sourceFiles().containsKey("models/main.json"));
        assertFalse(imported.sourceFiles().keySet().stream().anyMatch(path -> path.endsWith(".ysm")), "Opaque encrypted files never travel in the private wire bundle");
        file[12] ^= 1; assertThrows(Exception.class, () -> PublicYsmCodec.decode(file));
    }

    @Test void compressedBombTruncationAndOversizeBinaryCountsAreRejectedBeforeAllocation() throws Exception {
        byte[] bomb = obfuscate(Zstd.compress(new byte[8 * 1024 * 1024 + 1]));
        assertThrows(IOException.class, () -> YsmZstd.decompress(bomb, 0, bomb.length));
        byte[] good = obfuscate(Zstd.compress(legacyBinary()));
        assertThrows(IOException.class, () -> YsmZstd.decompress(good, 0, good.length - 1));
        assertThrows(IOException.class, () -> YsmZstd.decompress(good, -1, good.length));
        assertThrows(IllegalArgumentException.class, () -> new YSMByteBuf(new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,0x7f}).readByteArray());
        assertThrows(IllegalArgumentException.class, () -> new YSMByteBuf(new byte[]{2,(byte)0xc0,(byte)0xaf}).readString());
        assertThrows(IllegalArgumentException.class, () -> new YSMByteBuf(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array()).readFloat());
    }

    // Fixture writer follows the pinned Sparkle legacy format, independently of the production deserializer.
    private static byte[] legacyBinary() {
        Writer w = new Writer(); w.i32(1); w.var(0); w.var(1); w.var(1); w.var(1); // format, padding, one main model
        w.var(1); w.string(""); w.var(1); w.var(1); w.vec(0,0,1); // bone, parent, cube and face
        float[][] positions = {{0,0,0},{1,0,0},{1,1,0},{0,1,0}};
        for (int i=0;i<4;i++) { w.vec(positions[i]); w.f(i==1||i==2?1:0); w.f(i>=2?1:0); }
        w.var(0); w.var(0); w.var(0); w.string("root"); for(int i=0;i<5;i++)w.var(0); w.vec(0,0,0); w.vec(0,0,0);
        w.string("geometry.fixture"); w.f(64); w.f(64); w.f(2); w.f(2); w.var(0); w.f(0); w.f(0); w.var(0); w.var(0); w.var(0); w.var(0);
        w.var(0); w.var(1); w.string("default.png"); w.var(1); w.bytes(new byte[]{(byte)255,0,0,(byte)255}); w.var(1); w.var(1);
        w.var(0); w.var(0); w.var(0); w.string(""); return w.output.toByteArray();
    }

    // Only public self-contained-file keys are used. No network/server/cache format or secret lookup participates.
    private static byte[] publicFile(byte[] clear) throws Exception {
        return publicFile(clear,"Public fixture\0");
    }
    private static byte[] publicFile(byte[] clear,String header) throws Exception {
        byte[] compressed = obfuscate(Zstd.compress(clear)); byte[] payload = new byte[5+compressed.length]; payload[0]=3;
        System.arraycopy(compressed,0,payload,5,compressed.length); byte[] key = new byte[32], iv = new byte[24];
        for(int i=0;i<key.length;i++)key[i]=(byte)(i*7+1); for(int i=0;i<iv.length;i++)iv[i]=(byte)(i*11+3);
        byte[] keyIv = new byte[56]; System.arraycopy(key,0,keyIv,0,32);System.arraycopy(iv,0,keyIv,32,24);
        CityHash hash = new CityHash(); MT19937 mt = new MT19937(hash.hash64WithSeed(keyIv,0xD017CBBA7B5D3581L));
        for(int offset=0;offset<payload.length;) {long random=mt.extract_number();for(int i=0;i<8&&offset<payload.length;i++,offset++)payload[offset]^=(byte)(random>>>(i*8));}
        long derivation = hash.hash64WithSeed(keyIv,0xA62B1A2C43842BC3L); int size=(int)(((derivation&63)|64)<<6),rounds=(int)(10*Long.remainderUnsigned(derivation,3)+10);
        byte[] encrypted=new byte[payload.length]; XChaCha20 cipher=new XChaCha20(key,iv,rounds);
        for(int offset=0;offset<payload.length;) {int count=Math.min(size,payload.length-offset);cipher.processBytes(payload,offset,encrypted,offset,count);offset+=count;if(offset<payload.length)size=cipher.updateStateYSM(hash.hash64WithSeed(payload,offset-count,count,0xA62B1A2C43842BC3L));}
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(header.getBytes(StandardCharsets.UTF_8));out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array());out.write(encrypted);out.write(key);out.write(iv);
        byte[] prefix=out.toByteArray();out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(hash.hash64WithSeed(prefix,0,prefix.length,0x9E5599DB80C67C29L)).array());return out.toByteArray();
    }

    private static byte[] obfuscate(byte[] frame) {
        byte[] data=frame.clone();int fhd=data[4]&255,dict=fhd&3,fcs=fhd>>>6;
        int offset=5+((fhd&32)==0?1:0)+(dict==0?0:dict==1?1:dict==2?2:4)+(fcs==0?((fhd&32)==0?0:1):fcs==1?2:fcs==2?4:8);
        while(offset+3<=data.length) {int header=(data[offset]&255)|(data[offset+1]&255)<<8|(data[offset+2]&255)<<16;int last=header&1,type=header>>>1&3,size=header>>>3;
            int encoded=size^0xD4E9,ysmType=switch(type){case 2->0;case 1->1;case 3->2;default->3;};
            data[offset]=(byte)(last<<7|ysmType<<5|(encoded>>>16&31));data[offset+1]=(byte)encoded;data[offset+2]=(byte)(encoded>>>8);
            offset+=3+(type==1?1:size);if(last!=0)break;
        } return data;
    }

    private static final class Writer {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        void var(int value){do{int next=value&127;value>>>=7;output.write(next|(value==0?0:128));}while(value!=0);}
        void bytes(byte[] value){var(value.length);output.writeBytes(value);}void string(String value){bytes(value.getBytes(StandardCharsets.UTF_8));}
        void i32(int value){output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());}void f(float value){i32(Float.floatToIntBits(value));}void vec(float... values){for(float value:values)f(value);}
    }
}
