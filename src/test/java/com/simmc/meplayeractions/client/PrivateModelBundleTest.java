package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivateModelBundleTest {
    private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    private static final String MODEL="{\"elements\":[],\"outliner\":[],\"textures\":[{\"source\":\"data:image/png;base64,"+PNG+"\"}],\"animations\":[]}";
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static byte[] zip(Map<String,byte[]> entries) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out,StandardCharsets.UTF_8)) {
            for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
        return out.toByteArray();
    }
    private static Map<String,byte[]> bb(){Map<String,byte[]> files=new LinkedHashMap<>();files.put("manifest.json",bytes("{\"format\":1,\"kind\":\"bbmodel\",\"entry\":\"model.bbmodel\"}"));files.put("model.bbmodel",bytes(MODEL));return files;}
    @Test void preservesCompleteBundleIdentityAndValidatesEachNativeResource() throws IOException {
        Map<String,byte[]> files=new LinkedHashMap<>();files.put("manifest.json",bytes("{\"format\":1,\"kind\":\"ysm\",\"entry\":\"ysm.json\"}"));
        files.put("ysm.json",bytes("{\"name\":\"author native model\",\"player\":{\"main\":{\"texture\":\"textures/main.png\"}}}"));
        files.put("models/main.json",bytes("{\"minecraft:geometry\":[]}"));files.put("animations/player.animation.json",bytes("{\"animations\":{}}"));
        files.put("textures/main.png",Base64.getDecoder().decode(PNG));files.put("functions.molang",bytes("variable.counter=variable.counter+1;"));
        byte[] bundle=zip(files);PrivateModelBundle.Validated validated=PrivateModelBundle.validate(bundle,"ysm");
        assertEquals("ysm",validated.kind());assertEquals("ysm.json",validated.entry());assertEquals(files.values().stream().mapToInt(value->value.length).sum(),validated.expandedBytes());
        assertEquals(64,PrivateModelBundle.hash(bundle).length());files.put("functions.molang",bytes("variable.counter=0;"));assertNotEquals(PrivateModelBundle.hash(bundle),PrivateModelBundle.hash(zip(files)));
    }
    @Test void bbmodelHasOriginalEmbeddedTextureAndNativeFields() throws IOException {
        byte[] raw=zip(bb());assertEquals("model.bbmodel",PrivateModelBundle.validate(raw,"bbmodel").entry());
        assertThrows(IOException.class,()->PrivateModelBundle.validate(raw,"ysm"));
    }
    @Test void rejectsTraversalAliasesCasefoldDuplicatesExternalSourcesAndManifestExtras() throws IOException {
        for(String path:List.of("../secret.json","/absolute.json","C:/model.json","models\\main.json","models//main.json","models/./main.json","models/../main.json")) {
            Map<String,byte[]> files=bb();files.put(path,bytes("{}"));assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(files),"bbmodel"),path);
        }
        Map<String,byte[]> duplicate=bb();duplicate.put("MODEL.BBMODEL",bytes(MODEL));assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(duplicate),"bbmodel"));
        Map<String,byte[]> external=bb();external.put("model.bbmodel",bytes(MODEL.replace("data:image/png;base64,"+PNG,"https://private.example/secret.png")));
        assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(external),"bbmodel"));
        Map<String,byte[]> manifest=bb();manifest.put("manifest.json",bytes("{\"format\":1,\"kind\":\"bbmodel\",\"entry\":\"model.bbmodel\",\"url\":\"https://example.com/\"}"));
        assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(manifest),"bbmodel"));
    }
    @Test void rejectsDuplicateJsonFieldsDeepJsonNonFiniteNumbersAndInvalidUtf8() throws IOException {
        for(byte[] json:List.of(bytes("{\"x\":1,\"x\":2}"),bytes("[".repeat(66)+"0"+"]".repeat(66)),bytes("{\"x\":1e999}"),new byte[]{(byte)0xc3,(byte)0x28},bytes("{} {}")))
            assertThrows(IOException.class,()->PrivateModelBundle.parseJson(json,PrivateModelBundle.MAX_BYTES));
    }
    @Test void stopsHighlyCompressedExpansionAndCountsDirectoryEntriesToo() throws IOException {
        Map<String,byte[]> files=bb();files.put("large.molang",new byte[PrivateModelBundle.MAX_BYTES]);
        byte[] bomb=zip(files);assertTrue(bomb.length<100_000);assertThrows(IOException.class,()->PrivateModelBundle.validate(bomb,"bbmodel"));
        Map<String,byte[]> many=bb();for(int i=0;i<PrivateModelBundle.MAX_ENTRIES;i++)many.put("directories/"+i+"/",new byte[0]);
        assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(many),"bbmodel"));
        Map<String,byte[]> directoryData=bb();directoryData.put("ignored/",bytes("must not bypass the expanded budget"));
        assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(directoryData),"bbmodel"));
    }
    @Test void rejectsSymlinkMetadataAndDamagedArchiveWithoutExtractingAnything() throws IOException {
        byte[] raw=zip(bb()),symlink=raw.clone();
        for(int i=0;i<symlink.length-46;i++)if(symlink[i]==0x50 && symlink[i+1]==0x4b && symlink[i+2]==1 && symlink[i+3]==2){
            symlink[i+40]=(byte)0xff;symlink[i+41]=(byte)0xa1;break;
        }
        assertThrows(IOException.class,()->PrivateModelBundle.validate(symlink,"bbmodel"));
        assertThrows(IOException.class,()->PrivateModelBundle.validate(Arrays.copyOf(raw,raw.length-1),"bbmodel"));
    }
}
