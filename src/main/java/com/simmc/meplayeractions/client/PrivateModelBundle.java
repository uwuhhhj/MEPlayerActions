package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Pure, bounded validation. Never extracts a client path, executes scripts, or resolves a server asset. */
public final class PrivateModelBundle {
    public static final int MAX_BYTES = 8 * 1024 * 1024, MAX_ENTRIES = 256;
    private static final Set<String> EXTENSIONS = Set.of("json", "bbmodel", "png", "bmp", "jpg", "jpeg", "webp", "ogg", "molang");
    private PrivateModelBundle() {}
    public record Validated(String kind, String entry, int expandedBytes) {}

    public static Validated validate(byte[] raw, String expectedKind) throws IOException {
        if (raw == null || raw.length < 22 || raw.length > MAX_BYTES) throw new IOException("bundle_size");
        validateDirectory(raw);
        Map<String,byte[]> files = new LinkedHashMap<>(); Set<String> names = new HashSet<>();
        int expanded = 0, scanned = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++scanned > MAX_ENTRIES) throw new IOException("bundle_entries");
                String name = safePath(entry.getName(), entry.isDirectory());
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("bundle_duplicate");
                if (entry.isDirectory()) { readBounded(zip, 0); zip.closeEntry(); continue; }
                String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (!EXTENSIONS.contains(extension)) throw new IOException("bundle_file_type");
                int limit = name.equals("manifest.json") ? 4096 : MAX_BYTES - expanded;
                byte[] bytes = readBounded(zip, limit); expanded += bytes.length;
                if (expanded > MAX_BYTES) throw new IOException("bundle_expanded_size");
                files.put(name, bytes); zip.closeEntry();
            }
        } catch (IllegalArgumentException malformed) { throw new IOException("bundle_zip", malformed); }
        if (files.isEmpty() || !files.containsKey("manifest.json")) throw new IOException("bundle_manifest");
        JsonObject manifest = object(parseJson(files.get("manifest.json"), 4096));
        requireKeys(manifest, Set.of("format", "kind", "entry"));
        if (integer(manifest, "format", 1, 1) != 1) throw new IOException("bundle_manifest_version");
        String kind = string(manifest, "kind", 16), path = safePath(string(manifest, "entry", 240), false);
        if (!Set.of("bbmodel", "ysm").contains(kind) || !kind.equals(expectedKind)) throw new IOException("bundle_kind");
        if (!files.containsKey(path) || path.equals("manifest.json")) throw new IOException("bundle_entry");
        if (kind.equals("bbmodel") && !path.equals("model.bbmodel")
                || kind.equals("ysm") && !path.equals("ysm.json")) throw new IOException("bundle_entry");
        long texturePixels=0;
        for (var file : files.entrySet()) {
            String name = file.getKey().toLowerCase(Locale.ROOT); byte[] bytes = file.getValue();
            if (name.endsWith(".json") || name.endsWith(".bbmodel")) {
                JsonElement document = parseJson(bytes, MAX_BYTES);
                if (!document.isJsonObject() && !document.isJsonArray()) throw new IOException("bundle_json_root");
                rejectExternalReferences(document);
            } else if (List.of(".png",".bmp",".jpg",".jpeg",".webp").stream().anyMatch(name::endsWith)) {
                texturePixels+=PrivateTextureHeaders.pixels(name,bytes);
                if(texturePixels>16_777_216)throw new IOException("bundle_texture_budget");
            }
            else if (name.endsWith(".molang")) utf8(bytes);
            else if (name.endsWith(".ogg") && (bytes.length < 4 || bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S'))
                throw new IOException("bundle_audio");
        }
        if (kind.equals("bbmodel")) {
            JsonObject model = object(parseJson(files.get(path), MAX_BYTES));
            if (!model.has("elements") || !model.get("elements").isJsonArray() || model.getAsJsonArray("elements").size() > 4096
                    || !model.has("textures") || !model.get("textures").isJsonArray() || model.getAsJsonArray("textures").isEmpty()
                    || model.getAsJsonArray("textures").size() > 16 || !model.has("outliner") || !model.get("outliner").isJsonArray())
                throw new IOException("bundle_bbmodel");
            long pixels=0;
            for(JsonElement texture:model.getAsJsonArray("textures")) {
                String source=string(object(texture),"source",MAX_BYTES);
                String prefix="data:image/png;base64,";
                if(!source.startsWith(prefix))throw new IOException("bundle_bbmodel_texture");
                try{pixels+=PrivateTextureHeaders.pixels("texture.png",Base64.getDecoder().decode(source.substring(prefix.length())));}
                catch(IllegalArgumentException malformed){throw new IOException("bundle_bbmodel_texture",malformed);}
                if(pixels>16_777_216)throw new IOException("bundle_texture_budget");
            }
        } else object(parseJson(files.get(path), MAX_BYTES));
        return new Validated(kind, path, expanded);
    }

    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String safePath(String name, boolean directory) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 240 || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || name.codePoints().anyMatch(Character::isISOControl)) throw new IOException("bundle_path");
        String path = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.length() > 128)
            throw new IOException("bundle_path");
        return path;
    }
    private static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (count > limit - out.size()) throw new IOException("bundle_expanded_size");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    /** Central directory flags also reject symlinks, encryption, split archives and ZIP64. */
    private static void validateDirectory(byte[] data) throws IOException {
        int end = -1;
        for (int at = data.length - 22; at >= Math.max(0, data.length - 65557); at--)
            if (u32(data, at) == 0x06054b50L && at + 22 + u16(data, at + 20) == data.length) { end = at; break; }
        if (end < 0 || u16(data,end+4) != 0 || u16(data,end+6) != 0 || u16(data,end+8) != u16(data,end+10)) throw new IOException("bundle_zip");
        int count = u16(data,end+10); long size = u32(data,end+12), start = u32(data,end+16);
        if (count < 1 || count > MAX_ENTRIES || start + size != end || start > data.length) throw new IOException("bundle_zip");
        int at = (int)start;
        for (int i=0;i<count;i++) {
            if (at + 46 > end || u32(data,at) != 0x02014b50L || (u16(data,at+8)&1) != 0
                    || u16(data,at+34) != 0 || u32(data,at+20) == 0xffffffffL || u32(data,at+24) > MAX_BYTES
                    || ((u32(data,at+38) >>> 16) & 0xf000) == 0xa000) throw new IOException("bundle_zip");
            at += 46 + u16(data,at+28) + u16(data,at+30) + u16(data,at+32);
            if (at > end) throw new IOException("bundle_zip");
        }
        if (at != end) throw new IOException("bundle_zip");
    }
    private static int u16(byte[] bytes,int at) { return (bytes[at]&255)|((bytes[at+1]&255)<<8); }
    private static long u32(byte[] bytes,int at) { return Integer.toUnsignedLong((bytes[at]&255)|((bytes[at+1]&255)<<8)|((bytes[at+2]&255)<<16)|((bytes[at+3]&255)<<24)); }

    static String utf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException malformed) { throw new IOException("invalid_utf8", malformed); }
    }
    static JsonElement parseJson(byte[] bytes, int maxBytes) throws IOException {
        if (bytes.length == 0 || bytes.length > maxBytes) throw new IOException("json_size");
        try (JsonReader reader = new JsonReader(new StringReader(utf8(bytes)))) {
            reader.setStrictness(Strictness.STRICT); int[] nodes={0}; JsonElement value=readJson(reader,0,nodes);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("json_trailing_content");
            return value;
        } catch (IllegalStateException | NumberFormatException malformed) { throw new IOException("invalid_json",malformed); }
    }
    private static JsonElement readJson(JsonReader reader,int depth,int[] nodes) throws IOException {
        if (depth > 64 || ++nodes[0] > 200_000) throw new IOException("json_structure_limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) { String key=reader.nextName(); if (key.length()>1024 || object.has(key)) throw new IOException("json_duplicate_key");
                    object.add(key,readJson(reader,depth+1,nodes)); }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array=new JsonArray();reader.beginArray();while(reader.hasNext()) array.add(readJson(reader,depth+1,nodes));reader.endArray();yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String text=reader.nextString(); if(text.length()>128) throw new IOException("json_number");
                java.math.BigDecimal number=new java.math.BigDecimal(text); if(!Double.isFinite(number.doubleValue())) throw new IOException("json_number");yield new JsonPrimitive(number);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("invalid_json");
        };
    }
    static JsonObject object(JsonElement value) throws IOException { if(value == null || !value.isJsonObject()) throw new IOException("expected_object");return value.getAsJsonObject(); }
    static void requireKeys(JsonObject object,Set<String> keys) throws IOException { if(!object.keySet().equals(keys)) throw new IOException("invalid_fields"); }
    static String string(JsonObject object,String key,int max) throws IOException {
        JsonElement value=object.get(key);if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IOException("expected_string");
        String text=value.getAsString();if(text.length()>max || text.codePoints().anyMatch(Character::isISOControl))throw new IOException("string_limit");return text;
    }
    static long integer(JsonObject object,String key,long min,long max) throws IOException {
        JsonElement value=object.get(key);if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IOException("expected_integer");
        try {long result=value.getAsBigDecimal().longValueExact();if(result<min || result>max)throw new IOException("integer_range");return result;}
        catch(ArithmeticException invalid){throw new IOException("expected_integer",invalid);}
    }
    private static void rejectExternalReferences(JsonElement value) throws IOException {
        if(value.isJsonObject()) for(var field:value.getAsJsonObject().entrySet()) {
            if(Set.of("source","uri","file","texture","model").contains(field.getKey()) && field.getValue().isJsonPrimitive()
                    && field.getValue().getAsJsonPrimitive().isString()) {
                String reference=field.getValue().getAsString().toLowerCase(Locale.ROOT);
                if(reference.startsWith("http:") || reference.startsWith("https:") || reference.startsWith("file:") || reference.startsWith("\\\\"))
                    throw new IOException("bundle_external_reference");
            }
            rejectExternalReferences(field.getValue());
        } else if(value.isJsonArray()) for(JsonElement child:value.getAsJsonArray())rejectExternalReferences(child);
    }
}
