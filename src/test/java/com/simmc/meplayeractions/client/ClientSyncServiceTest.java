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

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"protocol\":1,\"type\":\"hello\"}",
            "{\"protocol\":1,\"type\":\"hello\",\"clientVersion\":\"0.1.0\",\"capabilities\":[\"state_sync\"]}",
            "{\"capabilities\":[],\"type\":\"hello\",\"protocol\":1}"
    })
    void acceptsHelloWithOptionalStateSyncCapability(String json) throws IOException {
        Object decoded = decode(json);
        assertEquals(1, field(decoded, "protocol"));
        assertEquals("hello", field(decoded, "type"));
    }

    @ParameterizedTest
    @CsvSource(value = {"play|crouch_walk", "play|pack:wave-01/test.anim", "stop|", "sit|", "crawl|", "reset|"},
            delimiter = '|', nullValues = "")
    void acceptsOnlySupportedSelfActionRequests(String action, String argument) throws IOException {
        String expectedArgument = argument == null ? "" : argument;
        String json = "{\"protocol\":1,\"type\":\"request\",\"action\":\"" + action + "\""
                + (argument == null ? "" : ",\"argument\":\"" + argument + "\"") + "}";
        Object decoded = decode(json);
        assertEquals("request", field(decoded, "type"));
        assertEquals(action, field(decoded, "action"));
        assertEquals(expectedArgument, field(decoded, "argument"));
    }

    @Test
    void acceptsSnapshotRequestWithoutInventingAnAction() throws IOException {
        Object decoded = decode("{\"protocol\":1,\"type\":\"snapshot_request\"}");
        assertEquals("snapshot_request", field(decoded, "type"));
        assertEquals("", field(decoded, "action"));
        assertEquals("", field(decoded, "argument"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"protocol\":1,\"type\":\"hello\",\"protocol\":1}",
            "{\"protocol\":1,\"type\":\"hello\",\"type\":\"request\"}",
            "{\"protocol\":1,\"type\":\"request\",\"action\":\"sit\",\"action\":\"crawl\"}"
    })
    void rejectsDuplicateFieldsEvenWhenValuesAgree(String json) {
        assertRejected(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"protocol\":1,\"type\":\"request\",\"action\":\"sit\",\"owner\":\"00000000-0000-0000-0000-000000000001\"}",
            "{\"protocol\":1,\"type\":\"request\",\"action\":\"sit\",\"target\":\"00000000-0000-0000-0000-000000000001\"}",
            "{\"protocol\":1,\"type\":\"request\",\"action\":\"op\",\"argument\":\"Player\"}",
            "{\"protocol\":1,\"type\":\"request\",\"action\":\"fly\"}",
            "{\"protocol\":1,\"type\":\"render_ready\"}",
            "{\"protocol\":1,\"type\":\"hello\",\"capabilities\":[\"render_ready\"]}",
            "{\"protocol\":1,\"type\":\"hello\",\"capabilities\":[\"state_sync\",\"state_sync\"]}",
            "{\"protocol\":1,\"type\":\"hello\",\"argument\":\"wave\"}",
            "{\"protocol\":1,\"type\":\"snapshot_request\",\"action\":\"stop\"}",
            "{\"protocol\":1,\"type\":\"hello\",\"command\":\"op Player\"}"
    })
    void rejectsTargetsUnknownActionsCapabilitiesAndFields(String json) {
        assertRejected(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"protocol\":1,\"type\":\"hello\",}",
            "{protocol:1,type:'hello'}",
            "{\"protocol\":1,\"type\":'hello'}",
            "{\"protocol\":1,/*comment*/\"type\":\"hello\"}",
            "{\"protocol\":1,\"type\":\"hello\"} {}",
            "{\"protocol\":1,\"type\":\"hello\"} garbage",
            "{\"protocol\":01,\"type\":\"hello\"}"
    })
    void rejectsLenientOrTrailingJson(String json) {
        assertRejected(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "\"hello\"", "{\"type\":\"hello\"}",
            "{\"protocol\":1,\"type\":null}",
            "{\"protocol\":1,\"type\":\"hello\",\"clientVersion\":{\"name\":\"client\"}}",
            "{\"protocol\":1,\"type\":\"hello\",\"clientVersion\":[[\"client\"]]}",
            "{\"protocol\":1,\"type\":\"hello\",\"capabilities\":[[\"state_sync\"]]}",
            "{\"protocol\":1,\"type\":\"hello\",\"capabilities\":{\"state_sync\":true}}",
            "{\"protocol\":1,\"type\":\"request\",\"action\":[\"sit\"]}",
            "{\"protocol\":1,\"type\":\"request\",\"action\":\"play\",\"argument\":12}",
            "{\"protocol\":1,\"type\":\"request\",\"action\":\"play\",\"argument\":null}"
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
                "{\"protocol\":1,\"type\":\"request\",\"action\":\"play\"}",
                "{\"protocol\":1,\"type\":\"request\",\"action\":\"play\",\"argument\":\"\"}",
                "{\"protocol\":1,\"type\":\"request\",\"action\":\"play\",\"argument\":\"wave;op Player\"}",
                "{\"protocol\":1,\"type\":\"request\",\"action\":\"play\",\"argument\":\"wave\\u0000\"}",
                "{\"protocol\":1,\"type\":\"request\",\"action\":\"play\",\"argument\":\"" + "a".repeat(129) + "\"}",
                "{\"protocol\":1,\"type\":\"request\",\"action\":\"sit\",\"argument\":\"Player\"}",
                "{\"protocol\":1,\"type\":\"hello\",\"clientVersion\":\"" + "v".repeat(65) + "\"}"
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
