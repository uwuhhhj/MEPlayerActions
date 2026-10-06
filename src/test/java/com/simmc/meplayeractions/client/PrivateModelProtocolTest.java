package com.simmc.meplayeractions.client;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual decoder and payload normalizer, without a server or invented permissions. */
class PrivateModelProtocolTest {
    private static final String GENERATION="00000000-0000-0000-0000-000000000011",HASH="a".repeat(64);
    private static JsonObject decode(String text) throws IOException {return PrivateModelSyncService.decode(text.getBytes(StandardCharsets.UTF_8));}
    @Test void uploadersCannotAdvertiseOrOverrideAuthoritativeFlightState() throws IOException {
        String state="{\"protocol\":1,\"type\":\"private_state\",\"generation\":\""+GENERATION+"\",\"hash\":\""+HASH+"\",\"appearance\":{},\"extra\":{\"id\":\"\",\"loop\":\"ONCE\",\"locked\":false,\"sequence\":0}}";
        assertEquals("private_state",decode(state).get("type").getAsString());
        assertThrows(IOException.class,()->decode(state.substring(0,state.length()-1)+",\"state\":{\"flying\":true}}"));
        assertThrows(IOException.class,()->decode(state.replace("\"appearance\":{}","\"appearance\":{\"flying\":true}")));
        String offer="{\"protocol\":1,\"type\":\"upload_offer\",\"generation\":\""+GENERATION+"\",\"hash\":\""+HASH+"\",\"bytes\":2048,\"kind\":\"ysm\",\"appearance\":{},\"state\":{\"flying\":true}}";
        assertThrows(IOException.class,()->decode(offer));
    }
    @Test void handshakeRequiresTheExactIndependentCapabilityAndNeverAllowsAssetSelection() throws IOException {
        assertEquals("hello",decode("{\"protocol\":1,\"type\":\"hello\",\"capabilities\":[\"private_models_v1\"]}").get("type").getAsString());
        assertEquals(2,decode("{\"protocol\":1,\"type\":\"hello\",\"capabilities\":[\"private_models_v1\",\"private_upload_catalog_v1\"]}").getAsJsonArray("capabilities").size());
        for(String invalid:List.of("{\"protocol\":1,\"type\":\"hello\",\"capabilities\":[]}",
                "{\"protocol\":1,\"type\":\"hello\",\"capabilities\":[\"private_models_v1\",\"private_models_v1\"]}",
                "{\"protocol\":1,\"type\":\"hello\",\"capabilities\":[\"private_upload_catalog_v1\"]}",
                "{\"protocol\":1,\"type\":\"hello\",\"capabilities\":[\"private_models_v1\",\"unknown\"]}",
                "{\"protocol\":1,\"type\":\"asset_request\",\"modelId\":\"server_secret\"}"))assertThrows(IOException.class,()->decode(invalid));
    }
    @Test void uploadOfferRequiresAppearanceAndAnExactTypedGenerationHashSizeAndKind() throws IOException {
        String offer="{\"protocol\":1,\"type\":\"upload_offer\",\"generation\":\""+GENERATION+"\",\"hash\":\""+HASH+"\",\"bytes\":2048,\"kind\":\"ysm\",\"appearance\":{}}";
        assertEquals("ysm",decode(offer).get("kind").getAsString());
        assertEquals("ysm:鲸鱼娘.ysm",decode(offer.replace("\"appearance\":{}","\"appearance\":{},\"modelId\":\"ysm:鲸鱼娘.ysm\"")).get("modelId").getAsString());
        for(String invalid:List.of(offer.replace("2048","2048.5"),offer.replace("2048","\"2048\""),offer.replace(GENERATION,"0-0-0-0-11"),
                offer.replace(HASH,HASH.toUpperCase(Locale.ROOT)),offer.replace("\"ysm\"","\"gltf\""),offer.replace("2048","8388609"),
                offer.replace("\"appearance\":{}","\"appearance\":{},\"modelId\":\"server_secret\"")))assertThrows(IOException.class,()->decode(invalid));
    }
    @Test void deletionRequiresExactSavedIdentityAndHandshakeReplayProtectionWithoutResourceSelection() throws IOException {
        String request="{\"protocol\":1,\"type\":\"upload_delete\",\"requestId\":\""+GENERATION+"\",\"catalogToken\":\""+GENERATION+"\",\"requestSequence\":1,\"modelId\":\"ysm:鲸鱼娘.ysm\",\"hash\":\""+HASH+"\",\"kind\":\"ysm\",\"bytes\":2048}";
        assertEquals("upload_delete",decode(request).get("type").getAsString());
        for(String invalid:List.of(request.replace("\"requestSequence\":1","\"requestSequence\":0"),
                request.replace("\"requestSequence\":1","\"requestSequence\":1.5"),
                request.replace("\"requestSequence\":1","\"requestSequence\":\"1\""),
                request.replace("\"requestSequence\":1","\"requestSequence\":9007199254740992"),
                request.replace("\"catalogToken\":\""+GENERATION+"\",",""),
                request.replace("ysm:鲸鱼娘.ysm","ysm:../other.ysm"),
                request.replace(HASH,HASH.toUpperCase(Locale.ROOT)),request.replace("2048","21"),
                request.replace("}",",\"owner\":\""+GENERATION+"\"}"),
                request.replace("}",",\"url\":\"https://example.com/model.zip\"}")))
            assertThrows(IOException.class,()->decode(invalid));
    }
    @Test void normalizesBoundedDefaultsAndRetainsAuthorRoamingConfiguration() throws IOException {
        JsonObject source=JsonParser.parseString("{\"variables\":{\"variable.服装\":1,\"variable.roaming.red_bow_headdress\":9},\"radioSelections\":{\"衣服\":2},\"offsetY\":-1}").getAsJsonObject();
        JsonObject normalized=PrivateModelSyncService.appearance(source);assertEquals(1,normalized.get("scale").getAsDouble());assertEquals(-1,normalized.get("offsetY").getAsDouble());
        assertEquals("",normalized.get("textureId").getAsString());assertEquals(2,normalized.getAsJsonObject("variables").size());assertTrue(normalized.getAsJsonObject("variables").has("variable.服装"));
        assertEquals(9,normalized.getAsJsonObject("variables").get("variable.roaming.red_bow_headdress").getAsDouble());
        assertEquals(2,normalized.getAsJsonObject("radioSelections").get("衣服").getAsInt());
        for(String json:List.of("{\"scale\":0}","{\"offsetX\":33}","{\"variables\":{\"context.secret\":1}}","{\"variables\":{\"variable.x\":1000001}}", "{\"radioSelections\":{\"x\":1.5}}","{\"radioSelections\":{\"x\":256}}", "{\"url\":\"https://example.com\"}"))
            assertThrows(IOException.class,()->PrivateModelSyncService.appearance(JsonParser.parseString(json)));
    }
    @Test void appearanceOffsetsMatchTheLocalThirtyTwoBlockBoundaryOnEveryAxis() throws IOException {
        for(String axis:List.of("offsetX","offsetY","offsetZ")) {
            for(int value:List.of(-32,32)) {
                JsonObject appearance=new JsonObject();appearance.addProperty(axis,value);
                assertEquals(value,PrivateModelSyncService.appearance(appearance).get(axis).getAsDouble());
            }
            for(int value:List.of(-33,33)) {
                JsonObject appearance=new JsonObject();appearance.addProperty(axis,value);
                assertThrows(IOException.class,()->PrivateModelSyncService.appearance(appearance));
            }
        }
    }
    @Test void authorVariablesUseTheLocalUnicodeIdentifierGrammarWithBoundedValuesAndCollections() throws IOException {
        JsonObject values=new JsonObject();
        values.addProperty("variable.服装_2.袖口",-1_000_000);values.addProperty("variable.ärger.δ_2",1_000_000);
        values.addProperty("variable.roaming.蝴蝶结",1);values.addProperty("variable."+"_".repeat(119),0);
        JsonObject source=new JsonObject();source.add("variables",values);
        JsonObject normalized=PrivateModelSyncService.appearance(source).getAsJsonObject("variables");
        assertEquals(4,normalized.size());assertEquals(1_000_000,normalized.get("variable.ärger.δ_2").getAsDouble());
        assertEquals(1,normalized.get("variable.roaming.蝴蝶结").getAsDouble());
        JsonObject packet=new JsonObject();packet.addProperty("protocol",1);packet.addProperty("type","upload_offer");
        packet.addProperty("generation",GENERATION);packet.addProperty("hash",HASH);packet.addProperty("bytes",2048);packet.addProperty("kind","ysm");packet.add("appearance",source);
        assertEquals(4,decode(packet.toString()).getAsJsonObject("appearance").getAsJsonObject("variables").size());
        for(String name:List.of("variable.123","variable.服装.2袖口","variable..服装","variable.服装.",
                "variable.服装-袖口","variable.服装/袖口","variable.服装[0]","variable.服装=1", "variable.服装;query.x",
                "variable.服装()","variable.服装\n袖口","variable.😀","VARIABLE.服装","variable.ÄRGER","variable.roaming.a.b","variable."+"_".repeat(120))) {
            JsonObject invalidValues=new JsonObject();invalidValues.addProperty(name,1);
            JsonObject invalid=new JsonObject();invalid.add("variables",invalidValues);
            assertThrows(IOException.class,()->PrivateModelSyncService.appearance(invalid),name);
        }
        JsonObject duplicated=new JsonObject();duplicated.addProperty("variable.ÄRGER",1);duplicated.addProperty("variable.ärger",2);
        source.add("variables",duplicated);assertThrows(IOException.class,()->PrivateModelSyncService.appearance(source));
        for(double number:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1_000_001d,1_000_001d)) {
            JsonObject invalidValues=new JsonObject();invalidValues.addProperty("variable.服装",number);source.add("variables",invalidValues);
            assertThrows(IOException.class,()->PrivateModelSyncService.appearance(source));
        }
        JsonObject many=new JsonObject();for(int i=0;i<128;i++)many.addProperty("variable.服装_"+i,i);
        source.add("variables",many);assertEquals(128,PrivateModelSyncService.appearance(source).getAsJsonObject("variables").size());
        many.addProperty("variable.服装_128",128);assertThrows(IOException.class,()->PrivateModelSyncService.appearance(source));
        JsonObject roaming=new JsonObject();roaming.addProperty("variable.roaming."+"_".repeat(32),1);
        source.add("variables",roaming);assertEquals(1,PrivateModelSyncService.appearance(source).getAsJsonObject("variables").size());
        roaming.addProperty("variable.roaming."+"_".repeat(33),1);assertThrows(IOException.class,()->PrivateModelSyncService.appearance(source));
        roaming=new JsonObject();for(int i=0;i<64;i++)roaming.addProperty("variable.roaming.作者_"+i,1);
        source.add("variables",roaming);assertEquals(64,PrivateModelSyncService.appearance(source).getAsJsonObject("variables").size());
        roaming.addProperty("variable.roaming.作者_64",1);assertThrows(IOException.class,()->PrivateModelSyncService.appearance(source));
    }
    @Test void controlMessagesRejectDuplicateFieldsUnknownFieldsCoercionAndOversizedCollections() {
        String ready="{\"protocol\":1,\"type\":\"private_ready\",\"owner\":\""+GENERATION+"\",\"generation\":\""+GENERATION+"\",\"hash\":\""+HASH+"\"}";
        for(String invalid:List.of(ready.replace("}",",\"owner\":\""+GENERATION+"\"}"),ready.replace("}",",\"offerId\":\""+GENERATION+"\"}"),
                "{\"protocol\":1,\"type\":\"private_heartbeat\",\"bindings\":[{}]}","{\"protocol\":1,\"type\":\"clear\",\"owner\":\""+GENERATION+"\"}",
                "{\"protocol\":1,\"type\":\"private_event\",\"generation\":\""+GENERATION+"\",\"hash\":\""+HASH+"\",\"args\":[\"1\"]}"))assertThrows(IOException.class,()->decode(invalid));
    }
    @Test void syncEventAcceptsFiniteNumbersOnlyAndKeepsTheSixteenArgumentBoundary() throws IOException {
        String prefix="{\"protocol\":1,\"type\":\"private_event\",\"generation\":\""+GENERATION+"\",\"hash\":\""+HASH+"\",\"args\":";
        assertEquals(2,decode(prefix+"[1,1e200]}").getAsJsonArray("args").size());
        String many=String.join(",",Collections.nCopies(17,"1"));assertThrows(IOException.class,()->decode(prefix+"["+many+"]}"));
        assertThrows(IOException.class,()->decode(prefix+"[1e999]}"));
    }
    @Test void extraActionSequenceAllowsReplayDetectionWithoutRestrictingAuthorsToCommandIds() throws IOException {
        JsonObject extra=PrivateModelSyncService.extra(JsonParser.parseString("{\"id\":\"animation.author.wave\",\"loop\":\"HOLD\",\"locked\":true,\"sequence\":9}"));
        assertEquals(9,extra.get("sequence").getAsLong());
        for(String invalid:List.of("{\"id\":\"wave\",\"loop\":\"once\",\"locked\":true,\"sequence\":1}",
                "{\"id\":\"wave\",\"loop\":\"ONCE\",\"locked\":1,\"sequence\":1}",
                "{\"id\":\"wave\",\"loop\":\"ONCE\",\"locked\":false,\"sequence\":9007199254740992}"))assertThrows(IOException.class,()->PrivateModelSyncService.extra(JsonParser.parseString(invalid)));
    }
    @Test void privateRelayDefaultsDenyAndRateWindowsRemainIndependentOfProtocolSessions() {
        assertFalse(PrivateModelSyncService.Policy.disabled().enabled());
        PrivateModelSyncService.Rate rate=new PrivateModelSyncService.Rate();for(int i=0;i<8;i++)assertTrue(rate.take(100+i));assertFalse(rate.take(108));
        assertFalse(rate.take(999_999_999));assertTrue(rate.take(1_000_000_100L));
        assertThrows(IllegalArgumentException.class,()->new PrivateModelSyncService.Policy(true,16000,8*1024*1024,8*1024*1024,64,10,"mact.private.upload","mact.private.view"));
    }
    @Test void smallPayloadNegotiationKeepsEveryUploadWithinTheFixedTimeAndChunkBudgets() {
        var defaults=PrivateModelSyncService.Policy.disabled();assertEquals(8*1024*1024,PrivateModelSyncService.effectiveBundleLimit(defaults));
        for(int payload:List.of(1024,2048,4096,12000,16000,30000)) {
            var policy=new PrivateModelSyncService.Policy(true,payload,8*1024*1024,32L*1024*1024,64,10,"mact.private.upload","mact.private.view");
            int chunk=PrivateModelSyncService.negotiatedChunkBytes(payload),bytes=PrivateModelSyncService.effectiveBundleLimit(policy);
            assertTrue(chunk<=8192);assertTrue((bytes+chunk-1)/chunk<=1100);assertTrue(bytes<=8*1024*1024);
            if(payload==1024){assertEquals(384,chunk);assertEquals(422400,bytes);}
        }
    }
    @Test void privateOnlyPolicyReaderHasNoModelEngineDependencyAndDeniesMissingFields() {
        var config=new org.bukkit.configuration.file.YamlConfiguration();
        assertFalse(PrivateModelSyncService.Policy.fromConfiguration(config).enabled());
        config.set("client-sync.private-models.enabled",true);config.set("client-sync.max-payload-bytes",1024);
        assertEquals(422400,PrivateModelSyncService.effectiveBundleLimit(PrivateModelSyncService.Policy.fromConfiguration(config)));
        assertTrue(PrivateModelSyncService.Policy.fromConfiguration(config).enabled());
        config.set("client-sync.enabled",false);assertFalse(PrivateModelSyncService.Policy.fromConfiguration(config).enabled());
        config.set("client-sync.private-models.url","https://example.com/private");
        assertThrows(IllegalArgumentException.class,()->PrivateModelSyncService.Policy.fromConfiguration(config));
    }
}
