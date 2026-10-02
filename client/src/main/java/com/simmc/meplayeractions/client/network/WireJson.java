package com.simmc.meplayeractions.client.network;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Bounded strict JSON shared by the networking boundary and its tests. */
public final class WireJson {
    private WireJson() {}
    public static JsonObject decode(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 32_766) throw new IOException("Payload size");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = read(reader, 0);
            if (!root.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Envelope");
            JsonObject object = root.getAsJsonObject();
            if (integer(object, "protocol", 2, 2) != 2) throw new IOException("Protocol");
            string(object, "type", 32);
            return object;
        } catch (IllegalStateException | NumberFormatException exception) {
            throw new IOException("Invalid JSON", exception);
        }
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 8) throw new IOException("JSON nesting");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject result = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.length() > 64 || result.has(key) || result.size() >= 64) throw new IOException("Object fields");
                    result.add(key, read(reader, depth + 1));
                }
                reader.endObject(); yield result;
            }
            case BEGIN_ARRAY -> {
                JsonArray result = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) {
                    if (result.size() >= 512) throw new IOException("Array length");
                    result.add(read(reader, depth + 1));
                }
                reader.endArray(); yield result;
            }
            case STRING -> {
                String value = reader.nextString();
                if (value.length() > 16_000) throw new IOException("String length");
                yield new JsonPrimitive(value);
            }
            case NUMBER -> {
                String value = reader.nextString();
                if (value.length() > 48) throw new IOException("Number length");
                yield new JsonPrimitive(new java.math.BigDecimal(value));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            default -> throw new IOException("Unsupported JSON value");
        };
    }
    public static JsonObject envelope(String type) {
        JsonObject json = new JsonObject(); json.addProperty("protocol", 2); json.addProperty("type", type); return json;
    }
    public static String string(JsonObject object, String key, int max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected string: " + key);
        String result = value.getAsString();
        if (result.length() > max || result.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid string: " + key);
        return result;
    }
    public static long integer(JsonObject object, String key, long min, long max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected integer: " + key);
        long result = value.getAsBigDecimal().longValueExact();
        if (result < min || result > max) throw new IllegalArgumentException("Integer range: " + key);
        return result;
    }
    public static double number(JsonObject object, String key, double min, double max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected number: " + key);
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || result < min || result > max) throw new IllegalArgumentException("Number range: " + key);
        return result;
    }
    public static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Expected boolean: " + key);
        return value.getAsBoolean();
    }
    public static String hash(JsonObject object, String key) {
        String result = string(object, key, 64);
        if (!result.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Asset hash");
        return result;
    }
    public static String modelId(JsonObject object) {
        String result = string(object, "modelId", 64);
        if (!result.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("Model ID");
        return result;
    }
}
