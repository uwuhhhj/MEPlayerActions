package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import com.simmc.meplayeractions.config.ModelComplexityLimits;
import com.google.gson.*;
import java.nio.ByteBuffer;
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
    private static JsonObject texture(String name,String source,String relative) {
        JsonObject value=new JsonObject();value.addProperty("name",name);value.addProperty("source",source);
        value.addProperty("relative_path",relative);return value;
    }
    private static Map<String,byte[]> withTextures(JsonObject... textures) {
        Map<String,byte[]> files=bb();JsonObject model=JsonParser.parseString(MODEL).getAsJsonObject();
        JsonArray values=new JsonArray();for(JsonObject value:textures)values.add(value);
        model.add("textures",values);files.put("model.bbmodel",bytes(model.toString()));return files;
    }
    @Test void bbmodelAcceptsIncludedPngByNameOptionalExtensionAndRelativeBasename() throws IOException {
        for(JsonObject texture:List.of(texture("skin.PNG","","unused.png"),texture("skin","","unused.png"),
                texture("author label","","textures\\skin.PNG"))) {
            // An editor-only absolute path remains inert; the host never opens it.
            texture.addProperty("path","C:\\Users\\author\\source.png");
            Map<String,byte[]> files=withTextures(texture);files.put("textures/skin.png",Base64.getDecoder().decode(PNG));
            assertEquals("bbmodel",PrivateModelBundle.validate(zip(files),"bbmodel").kind());
        }
        JsonObject absentSource=texture("skin","","textures/skin.png");absentSource.remove("source");
        Map<String,byte[]> files=withTextures(absentSource);files.put("skin.png",Base64.getDecoder().decode(PNG));
        assertEquals("model.bbmodel",PrivateModelBundle.validate(zip(files),"bbmodel").entry());
    }
    @Test void bbmodelNamePrecedenceDoesNotConsultUnusedEmbeddedEditorRelativePath() throws IOException {
        JsonObject texture=texture("skin","data:image/png;base64,"+PNG,"../unused.png");
        Map<String,byte[]> files=withTextures(texture);files.put("skin.png",Base64.getDecoder().decode(PNG));
        assertEquals("bbmodel",PrivateModelBundle.validate(zip(files),"bbmodel").kind());
        // Even without a matching side image, a valid embedded texture is self contained.
        files.remove("skin.png");assertEquals("bbmodel",PrivateModelBundle.validate(zip(files),"bbmodel").kind());
    }
    @Test void bbmodelRejectsMissingCompanionsUnsafeSelectedPathsAndExternalSources() throws IOException {
        Map<String,byte[]> missing=withTextures(texture("skin","","textures/skin.png"));
        assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(missing),"bbmodel"));
        // A header-valid JPEG remains a valid general asset, but cannot satisfy BB's PNG map.
        missing.put("skin.jpg",new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xc0,0,8,8,0,1,0,1,1});
        assertEquals("bundle_bbmodel_texture",assertThrows(IOException.class,
                ()->PrivateModelBundle.validate(zip(missing),"bbmodel")).getMessage());
        for(String reference:List.of("../skin.png","/skin.png","C:/skin.png","textures/../skin.png",
                "https://example.com/skin.png","file:/skin.png","\\\\host/skin.png")) {
            Map<String,byte[]> files=withTextures(texture("unmatched label","",reference));
            files.put("skin.png",Base64.getDecoder().decode(PNG));
            assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(files),"bbmodel"),reference);
        }
        for(String reference:List.of("https://example.com/skin.png","file:/skin.png","C:/skin.png","../skin.png")) {
            Map<String,byte[]> files=withTextures(texture("skin",reference,"skin.png"));
            files.put("skin.png",Base64.getDecoder().decode(PNG));
            assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(files),"bbmodel"),reference);
        }
    }
    @Test void bbmodelRejectsAmbiguousCompanionLeafNamesAndMalformedIncludedPng() throws IOException {
        Map<String,byte[]> files=withTextures(texture("skin","","skin.png"));
        files.put("a/skin.png",Base64.getDecoder().decode(PNG));files.put("b/SKIN.PNG",Base64.getDecoder().decode(PNG));
        IOException ambiguous=assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(files),"bbmodel"));
        assertEquals("bundle_bbmodel_texture_ambiguous",ambiguous.getMessage());
        files.remove("b/SKIN.PNG");files.put("a/skin.png",bytes("not a PNG"));
        assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(files),"bbmodel"));
    }
    @Test void bbmodelStillValidatesEmbeddedDataUrlsWhenSidePngOverrides() throws IOException {
        for(String source:List.of("data:image/jpeg;base64,"+PNG,"data:image/png;base64,%%%",
                "data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes("not a PNG")))) {
            Map<String,byte[]> files=withTextures(texture("skin",source,"skin.png"));
            files.put("skin.png",Base64.getDecoder().decode(PNG));
            assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(files),"bbmodel"),source);
        }
    }
    private static byte[] pngHeader(int width,int height) {
        byte[] data=Base64.getDecoder().decode(PNG);ByteBuffer buffer=ByteBuffer.wrap(data);
        buffer.putInt(16,width);buffer.putInt(20,height);return data;
    }
    @Test void bbmodelCompanionsAndEmbeddedTexturesShareExistingPixelAndExpansionBudgets() throws IOException {
        Map<String,byte[]> maximum=withTextures(texture("skin","","skin.png"));
        maximum.put("skin.png",pngHeader(4096,4096));
        assertEquals("bbmodel",PrivateModelBundle.validate(zip(maximum),"bbmodel").kind());
        Map<String,byte[]> combined=withTextures(texture("skin","data:image/png;base64,"+PNG,"skin.png"));
        combined.put("skin.png",pngHeader(4096,4096));
        assertEquals("bundle_texture_budget",assertThrows(IOException.class,
                ()->PrivateModelBundle.validate(zip(combined),"bbmodel")).getMessage());
        maximum.put("skin.png",pngHeader(8193,1));
        assertThrows(IOException.class,()->PrivateModelBundle.validate(zip(maximum),"bbmodel"));
        maximum.put("skin.png",new byte[PrivateModelBundle.MAX_BYTES]);
        assertEquals("bundle_expanded_size",assertThrows(IOException.class,
                ()->PrivateModelBundle.validate(zip(maximum),"bbmodel")).getMessage());
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
    @Test void configuredBundleLimitsApplyAcrossFilesAndKeepExistingFormatValidation() throws IOException {
        Map<String,byte[]> files=bb();files.put("first.molang",bytes("v.a=1;"));files.put("second.molang",bytes("v.b=1;"));
        byte[] expressionBundle=zip(files);
        var expressionLimits=new ModelComplexityLimits(4096,1024,65536,32768,9,200000,256,8388608,16777216);
        assertEquals("model_complexity:expression_chars",assertThrows(IOException.class,()->PrivateModelBundle.validate(expressionBundle,"bbmodel",expressionLimits)).getMessage());
        files=bb();files.put("side.png",pngHeader(2,1));byte[] pixelBundle=zip(files);
        var pixelLimits=new ModelComplexityLimits(4096,1024,65536,32768,2097152,200000,256,8388608,1);
        assertEquals("bundle_texture_budget",assertThrows(IOException.class,()->PrivateModelBundle.validate(pixelBundle,"bbmodel",pixelLimits)).getMessage());
        var entries=new ModelComplexityLimits(4096,1024,65536,32768,2097152,200000,1,8388608,16777216);
        byte[] normal=zip(bb());assertThrows(IOException.class,()->PrivateModelBundle.validate(normal,"bbmodel",entries));
        assertEquals("bbmodel",PrivateModelBundle.validate(normal,"bbmodel",ModelComplexityLimits.defaults()).kind());
    }
}
