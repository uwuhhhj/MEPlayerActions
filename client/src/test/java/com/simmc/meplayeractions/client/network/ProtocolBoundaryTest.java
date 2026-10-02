package com.simmc.meplayeractions.client.network;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolBoundaryTest {
    @Test void reassemblesReorderedChunksWithoutDuplicateGrowth() throws Exception {
        byte[] raw=new byte[32_000];new Random(1234).nextBytes(raw);
        byte[] gzip=gzip(raw);int count=(gzip.length+8999)/9000;
        AssetTransfer transfer=new AssetTransfer("test",AssetTransfer.hash(raw),raw.length,gzip.length,count);
        for(int i=count-1;i>=0;i--) {
            String chunk=Base64.getEncoder().encodeToString(Arrays.copyOfRange(gzip,i*9000,Math.min(gzip.length,(i+1)*9000)));
            transfer.put(i,chunk);transfer.put(i,chunk);
        }
        assertArrayEquals(raw,transfer.finish());
    }
    @Test void rejectsCorruptionMissingChunksAndFalseDeclaredLength() throws Exception {
        byte[] raw="geometry".getBytes(StandardCharsets.UTF_8),gzip=gzip(raw);
        AssetTransfer incomplete=new AssetTransfer("test",AssetTransfer.hash(raw),raw.length,gzip.length,1);
        assertThrows(IOException.class,incomplete::finish);
        AssetTransfer wrongHash=new AssetTransfer("test","0".repeat(64),raw.length,gzip.length,1);
        wrongHash.put(0,Base64.getEncoder().encodeToString(gzip));assertThrows(IOException.class,wrongHash::finish);
        AssetTransfer size=new AssetTransfer("test",AssetTransfer.hash(raw),2,gzip.length,1);
        size.put(0,Base64.getEncoder().encodeToString(gzip));assertThrows(IOException.class,size::finish);
    }
    @Test void supportsSmallServerPayloadWithMoreThan512Chunks() throws Exception {
        byte[] raw=new byte[600_000];new Random(42).nextBytes(raw);byte[] gzip=gzip(raw);
        int size=384,count=(gzip.length+size-1)/size;assertTrue(count>512);
        AssetTransfer transfer=new AssetTransfer("test",AssetTransfer.hash(raw),raw.length,gzip.length,count);
        for(int i=0;i<count;i++)transfer.put(i,Base64.getEncoder().encodeToString(
                Arrays.copyOfRange(gzip,i*size,Math.min(gzip.length,(i+1)*size))));
        assertArrayEquals(raw,transfer.finish());
    }
    @Test void boundsMemoryAndRejectsPathsAndConflictingDuplicate() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new AssetTransfer("../secret","0".repeat(64),10,10,1));
        assertThrows(IllegalArgumentException.class,()->new AssetTransfer("test","0".repeat(64),AssetTransfer.MAX_RAW+1,10,1));
        AssetTransfer transfer=new AssetTransfer("test","0".repeat(64),10,10,1);
        transfer.put(0,"AQ==");assertThrows(IllegalArgumentException.class,()->transfer.put(0,"Ag=="));
        assertThrows(IllegalArgumentException.class,()->transfer.put(1,"AQ=="));
    }
    @Test void strictJsonRejectsAmbiguousAndMalformedEnvelopes() {
        for(String json:List.of("{\"protocol\":2,\"protocol\":2,\"type\":\"hello\"}",
                "{\"protocol\":\"2\",\"type\":\"hello\"}","{\"protocol\":2.5,\"type\":\"hello\"}",
                "{\"protocol\":1,\"type\":\"hello\"}","{\"protocol\":2,\"type\":null}",
                "{\"protocol\":2,\"type\":\"hello\"}true","{protocol:2,type:'hello'}"))
            assertThrows(Exception.class,()->WireJson.decode(json.getBytes(StandardCharsets.UTF_8)),json);
        assertThrows(IOException.class,()->WireJson.decode(new byte[]{(byte)0xc0,(byte)0xaf}));
        assertThrows(IOException.class,()->WireJson.decode(new byte[32_767]));
    }
    @Test void decodesUnicodeLabelsAndFiniteExactNumbers() throws Exception {
        var json=WireJson.decode("{\"protocol\":2,\"type\":\"state\",\"label\":\"趴下／爬行\",\"tick\":4294967295}".getBytes(StandardCharsets.UTF_8));
        assertEquals("趴下／爬行",WireJson.string(json,"label",64));
        assertEquals(0xffff_ffffL,WireJson.integer(json,"tick",0,0xffff_ffffL));
        assertThrows(IllegalArgumentException.class,()->WireJson.number(json,"label",0,1));
    }
    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(out)){gzip.write(raw);}return out.toByteArray();
    }
}
