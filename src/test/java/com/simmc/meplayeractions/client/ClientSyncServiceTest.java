package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the real wire decoder without a Bukkit server or invented player permissions. */
class ClientSyncServiceTest {
    private static final Method DECODER = decoderMethod();
    @Test void longMenuLabelsAreBoundedWithoutSplittingSurrogatePairs() {
        assertEquals("中文", ClientSyncService.truncateLabel("中文"));
        String emoji = "🦊".repeat(65);
        assertEquals("🦊".repeat(64), ClientSyncService.truncateLabel(emoji));
        assertEquals(64, ClientSyncService.truncateLabel(emoji).codePointCount(0, ClientSyncService.truncateLabel(emoji).length()));
    }
    @Test void menuLabelsRemoveControlCharactersBeforeCountingUnicodeCodePoints() {
        String label = "\n\t趴下\r\u0000" + "🦊\t".repeat(65);
        String safe = ClientSyncService.truncateLabel(label);
        assertEquals("趴下" + "🦊".repeat(62), safe);
        assertEquals(64, safe.codePointCount(0, safe.length()));
        assertTrue(safe.codePoints().noneMatch(Character::isISOControl));
    }
    @Test void smallPayloadDropsMenuDirectoryButKeepsTransformAndLayersOrRejectsOversizedState() {
        var state = com.google.gson.JsonParser.parseString("{\"protocol\":2,\"type\":\"state\",\"owner\":\"00000000-0000-0000-0000-000000000001\",\"instance\":\"00000000-0000-0000-0000-000000000002\",\"modelId\":\"ysm_01_jk_player\",\"assetHash\":\"" + "a".repeat(64)
                + "\",\"sequence\":12,\"serverTick\":12345,\"world\":\"00000000-0000-0000-0000-000000000003\",\"x\":1,\"y\":64,\"z\":2,\"bodyYaw\":90,\"headYaw\":100,\"headPitch\":10,\"scale\":1.5,\"hidePlayer\":true,\"showSelf\":true,\"layers\":[{\"layer\":\"posture\",\"animation\":\"crawl_idle\",\"startedAtTick\":12340,\"speed\":1,\"loop\":\"LOOP\",\"inTicks\":2,\"outTicks\":2}],\"animations\":[]}").getAsJsonObject();
        var menu = new com.google.gson.JsonArray();
        for (int i = 0; i < 29; i++) { var entry = new com.google.gson.JsonObject(); entry.addProperty("id", "anim_" + i); entry.addProperty("label", "中文动作".repeat(16)); menu.add(entry); }
        state.add("animations", menu); assertTrue(ClientSyncService.fitStatePacket(state, 1024));
        assertEquals(0, state.getAsJsonArray("animations").size()); assertEquals(1, state.getAsJsonArray("layers").size());
        assertEquals(64, state.get("y").getAsInt());
        for (int i = 0; i < 8; i++) state.getAsJsonArray("layers").add(state.getAsJsonArray("layers").get(0).deepCopy());
        assertTrue(!ClientSyncService.fitStatePacket(state, 1024));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"protocol\":2,\"type\":\"hello\",\"capabilities\":[\"local_render\"]}",
            "{\"protocol\":2,\"type\":\"hello\",\"clientVersion\":\"0.1.0\",\"capabilities\":[\"local_render\"]}",
            "{\"capabilities\":[\"local_render\"],\"type\":\"hello\",\"protocol\":2}"
    })
    void acceptsHelloWithRequiredLocalRenderCapability(String json) throws IOException {
        Object decoded = decode(json);
        assertEquals(2, field(decoded, "protocol"));
        assertEquals("hello", field(decoded, "type"));
    }

    @ParameterizedTest
    @CsvSource(value = {"play|crouch_walk", "play|pack:wave-01/test.anim", "stop|", "sit|", "crawl|", "reset|"},
            delimiter = '|', nullValues = "")
    void acceptsOnlySupportedSelfActionRequests(String action, String argument) throws IOException {
        String expectedArgument = argument == null ? "" : argument;
        String json = "{\"protocol\":2,\"type\":\"request\",\"action\":\"" + action + "\""
                + (argument == null ? "" : ",\"argument\":\"" + argument + "\"") + "}";
        Object decoded = decode(json);
        assertEquals("request", field(decoded, "type"));
        assertEquals(action, field(decoded, "action"));
        assertEquals(expectedArgument, field(decoded, "argument"));
    }

    @Test
    void acceptsSnapshotRequestWithoutInventingAnAction() throws IOException {
        Object decoded = decode("{\"protocol\":2,\"type\":\"snapshot_request\"}");
        assertEquals("snapshot_request", field(decoded, "type"));
        assertEquals("", field(decoded, "action"));
        assertEquals("", field(decoded, "argument"));
    }
    @Test void acceptsExactAssetAndReadyKeysAndBoundedHeartbeatBindings() throws IOException {
        String hash = "a".repeat(64), owner = "00000000-0000-0000-0000-000000000001", instance = "00000000-0000-0000-0000-000000000002";
        Object asset = decode("{\"protocol\":2,\"type\":\"asset_request\",\"modelId\":\"ysm_01_jk_player\",\"hash\":\"" + hash + "\"}");
        assertEquals(hash, field(asset, "hash")); assertEquals("ysm_01_jk_player", field(asset, "modelId"));
        String binding = "\"owner\":\"" + owner + "\",\"instance\":\"" + instance + "\",\"hash\":\"" + hash + "\"";
        for (String type : new String[]{"render_ready", "render_failed"}) {
            Object ready = decode("{\"protocol\":2,\"type\":\"" + type + "\"," + binding + "}"); assertEquals(type, field(ready, "type"));
        }
        assertEquals("render_heartbeat", field(decode("{\"protocol\":2,\"type\":\"render_heartbeat\",\"bindings\":[{" + binding + "}]}"), "type"));
        assertEquals("render_heartbeat", field(decode("{\"protocol\":2,\"type\":\"render_heartbeat\",\"bindings\":[]}"), "type"));
    }
    @Test void rejectsAssetPathTraversalMalformedHashesAndExcessiveOrDuplicateReadyBindings() {
        String hash = "a".repeat(64), owner = "00000000-0000-0000-0000-000000000001", instance = "00000000-0000-0000-0000-000000000002";
        assertRejected("{\"protocol\":2,\"type\":\"asset_request\",\"modelId\":\"../private\",\"hash\":\"" + hash + "\"}");
        assertRejected("{\"protocol\":2,\"type\":\"asset_request\",\"modelId\":\"ysm_01_jk_player\",\"hash\":\"" + hash.toUpperCase() + "\"}");
        assertRejected("{\"protocol\":2,\"type\":\"hello\",\"capabilities\":[]}");
        String binding = "{\"owner\":\"" + owner + "\",\"instance\":\"" + instance + "\",\"hash\":\"" + hash + "\"}";
        assertRejected("{\"protocol\":2,\"type\":\"render_heartbeat\",\"bindings\":[" + binding + "," + binding + "]}");
        assertRejected("{\"protocol\":2,\"type\":\"render_ready\",\"owner\":\"0-0-0-0-1\",\"instance\":\"" + instance + "\",\"hash\":\"" + hash + "\"}");
        assertRejected("{\"protocol\":2,\"type\":\"render_ready\",\"owner\":\"" + owner + "\",\"instance\":\"" + instance + "\",\"hash\":\"" + hash + "\",\"action\":\"play\"}");
        StringBuilder many = new StringBuilder("{\"protocol\":2,\"type\":\"render_heartbeat\",\"bindings\":[");
        for (int i = 0; i < 65; i++) {
            if (i > 0) many.append(',');
            many.append(binding.replace(owner, new java.util.UUID(0, i + 1).toString()));
        }
        assertRejected(many.append("]}").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"protocol\":2,\"type\":\"hello\",\"protocol\":2}",
            "{\"protocol\":2,\"type\":\"hello\",\"type\":\"request\"}",
            "{\"protocol\":2,\"type\":\"request\",\"action\":\"sit\",\"action\":\"crawl\"}"
    })
    void rejectsDuplicateFieldsEvenWhenValuesAgree(String json) {
        assertRejected(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"protocol\":2,\"type\":\"request\",\"action\":\"sit\",\"owner\":\"00000000-0000-0000-0000-000000000001\"}",
            "{\"protocol\":2,\"type\":\"request\",\"action\":\"sit\",\"target\":\"00000000-0000-0000-0000-000000000001\"}",
            "{\"protocol\":2,\"type\":\"request\",\"action\":\"op\",\"argument\":\"Player\"}",
            "{\"protocol\":2,\"type\":\"request\",\"action\":\"fly\"}",
            "{\"protocol\":2,\"type\":\"render_ready\"}",
            "{\"protocol\":2,\"type\":\"hello\",\"capabilities\":[\"render_ready\"]}",
            "{\"protocol\":2,\"type\":\"hello\",\"capabilities\":[\"local_render\",\"local_render\"]}",
            "{\"protocol\":2,\"type\":\"hello\",\"argument\":\"wave\"}",
            "{\"protocol\":2,\"type\":\"snapshot_request\",\"action\":\"stop\"}",
            "{\"protocol\":2,\"type\":\"hello\",\"command\":\"op Player\"}"
    })
    void rejectsTargetsUnknownActionsCapabilitiesAndFields(String json) {
        assertRejected(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"protocol\":2,\"type\":\"hello\",}",
            "{protocol:1,type:'hello'}",
            "{\"protocol\":2,\"type\":'hello'}",
            "{\"protocol\":2,/*comment*/\"type\":\"hello\"}",
            "{\"protocol\":2,\"type\":\"hello\"} {}",
            "{\"protocol\":2,\"type\":\"hello\"} garbage",
            "{\"protocol\":01,\"type\":\"hello\"}"
    })
    void rejectsLenientOrTrailingJson(String json) {
        assertRejected(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "\"hello\"", "{\"type\":\"hello\"}",
            "{\"protocol\":2,\"type\":null}",
            "{\"protocol\":2,\"type\":\"hello\",\"clientVersion\":{\"name\":\"client\"}}",
            "{\"protocol\":2,\"type\":\"hello\",\"clientVersion\":[[\"client\"]]}",
            "{\"protocol\":2,\"type\":\"hello\",\"capabilities\":[[\"local_render\"]]}",
            "{\"protocol\":2,\"type\":\"hello\",\"capabilities\":{\"local_render\":true}}",
            "{\"protocol\":2,\"type\":\"request\",\"action\":[\"sit\"]}",
            "{\"protocol\":2,\"type\":\"request\",\"action\":\"play\",\"argument\":12}",
            "{\"protocol\":2,\"type\":\"request\",\"action\":\"play\",\"argument\":null}"
    })
    void rejectsMissingFieldsNullAndNestedOrCoercedTypes(String json) {
        assertRejected(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"1\"", "null", "1.1", "1e-1", "2147483648", "999999999999", "1000000000000"})
    void rejectsNonIntegerOrOverflowingProtocolValues(String protocol) {
        assertRejected("{\"protocol\":" + protocol + ",\"type\":\"hello\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidActionArguments")
    void rejectsMissingUnsafeOrOversizeAnimationArguments(String json) {
        assertRejected(json);
    }

    private static Stream<String> invalidActionArguments() {
        return Stream.of(
                "{\"protocol\":2,\"type\":\"request\",\"action\":\"play\"}",
                "{\"protocol\":2,\"type\":\"request\",\"action\":\"play\",\"argument\":\"\"}",
                "{\"protocol\":2,\"type\":\"request\",\"action\":\"play\",\"argument\":\"wave;op Player\"}",
                "{\"protocol\":2,\"type\":\"request\",\"action\":\"play\",\"argument\":\"wave\\u0000\"}",
                "{\"protocol\":2,\"type\":\"request\",\"action\":\"play\",\"argument\":\"" + "a".repeat(129) + "\"}",
                "{\"protocol\":2,\"type\":\"request\",\"action\":\"sit\",\"argument\":\"Player\"}",
                "{\"protocol\":2,\"type\":\"hello\",\"clientVersion\":\"" + "v".repeat(65) + "\"}"
        );
    }

    @ParameterizedTest
    @MethodSource("malformedUtf8")
    void rejectsMalformedUtf8InsteadOfReplacingBytes(byte[] payload) {
        assertThrows(IOException.class, () -> decode(payload));
    }

    private static Stream<byte[]> malformedUtf8() {
        return Stream.of(new byte[]{(byte) 0xc3, 0x28}, new byte[]{(byte) 0x80},
                new byte[]{(byte) 0xe2, (byte) 0x82});
    }

    private static void assertRejected(String json) {
        Exception exception = assertThrows(Exception.class, () -> decode(json));
        assertTrue(exception instanceof IOException || exception instanceof IllegalArgumentException
                || exception instanceof IllegalStateException, () -> "Unexpected failure: " + exception);
    }

    private static Object decode(String json) throws IOException {
        return decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Object decode(byte[] payload) throws IOException {
        try {
            return DECODER.invoke(null, (Object) payload);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError("Unexpected decoder failure", cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot invoke protocol decoder", exception);
        }
    }

    private static Method decoderMethod() {
        try {
            Method method = ClientSyncService.class.getDeclaredMethod("decode", byte[].class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Object field(Object decoded, String name) {
        try {
            Method accessor = decoded.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(decoded);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect decoded field " + name, exception);
        }
    }
}
