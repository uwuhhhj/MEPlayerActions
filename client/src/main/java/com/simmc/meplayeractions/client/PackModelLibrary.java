package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Reads only installed Minecraft resources; never starts a download or chooses a URL. */
public final class PackModelLibrary {
    public static final String INDEX = "meplayeractions:models/index.json";
    @FunctionalInterface public interface Resources { byte[] read(String resource, int maximum) throws IOException; }
    private static final Pattern SOURCE = Pattern.compile("(\"source\"\\s*:\\s*)\"([^\"\\\\]*)\"");
    private PackModelLibrary() { }

    public static LocalModelLibrary.Loaded load(Resources resources, String modelId, String expectedHash) throws IOException {
        if (modelId == null || !modelId.matches("[a-z0-9_-]{1,64}")) throw new IOException("Invalid pack model ID");
        if (expectedHash != null && !expectedHash.matches("[0-9a-f]{64}")) throw new IOException("Invalid pack model hash");
        try {
            JsonObject index = JsonParser.parseString(utf8(read(resources, INDEX, 65_536))).getAsJsonObject();
            if (!index.keySet().equals(Set.of("version", "models")) || !index.get("version").isJsonPrimitive()
                    || !index.getAsJsonPrimitive("version").isNumber() || !index.get("version").getAsString().equals("1"))
                throw new IOException("Unsupported model resource index");
            JsonArray entries = index.getAsJsonArray("models");
            if (entries.size() > 128) throw new IOException("Too many pack models");
            Set<String> ids = new HashSet<>(); JsonObject selected = null;
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                if (!entry.keySet().equals(Set.of("modelId", "hashSha256", "modelResource"))) throw new IOException("Invalid pack model entry");
                String id = entry.get("modelId").getAsString(), hash = entry.get("hashSha256").getAsString();
                if (!id.matches("[a-z0-9_-]{1,64}") || !hash.matches("[0-9a-f]{64}") || !ids.add(id))
                    throw new IOException("Invalid or duplicate pack model identity");
                resource(entry.get("modelResource").getAsString());
                if (id.equals(modelId)) selected = entry;
            }
            if (selected == null) throw new IOException("资源包未包含模型 " + modelId);
            String hash = selected.get("hashSha256").getAsString();
            if (expectedHash != null && !hash.equals(expectedHash)) throw new IOException("资源包模型版本与服务器不一致");
            String text = utf8(read(resources, resource(selected.get("modelResource").getAsString()), AssetTransfer.MAX_RAW));
            JsonObject definition = JsonParser.parseString(text).getAsJsonObject();
            JsonArray textures = definition.getAsJsonArray("textures");
            if (textures == null || textures.isEmpty() || textures.size() > 16) throw new IOException("Invalid pack textures");
            Map<String, String> replacements = new HashMap<>(); long total = 0;
            for (JsonElement element : textures) {
                String source = element.getAsJsonObject().get("source").getAsString();
                if (source.startsWith("data:")) throw new IOException("Pack textures must be shared PNG resources");
                resource(source);
                if (!source.endsWith(".png")) throw new IOException("Pack texture must be PNG");
                if (replacements.containsKey(source)) continue;
                byte[] png = read(resources, source, AssetTransfer.MAX_RAW);
                total += png.length;
                if (total > AssetTransfer.MAX_RAW) throw new IOException("Pack texture byte budget exceeded");
                replacements.put(source, "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            }
            Matcher matcher = SOURCE.matcher(text); StringBuilder restored = new StringBuilder(); int matches = 0;
            while (matcher.find()) {
                String data = replacements.get(matcher.group(2));
                if (data != null) { matcher.appendReplacement(restored, Matcher.quoteReplacement(matcher.group(1) + "\"" + data + "\"")); matches++; }
            }
            matcher.appendTail(restored);
            if (matches != textures.size()) throw new IOException("Invalid pack texture token encoding");
            byte[] raw = restored.toString().getBytes(StandardCharsets.UTF_8);
            if (raw.length > AssetTransfer.MAX_RAW || !AssetTransfer.hash(raw).equals(hash)) throw new IOException("资源包模型完整性校验失败");
            return new LocalModelLibrary.Loaded(hash, BbModel.parse(raw));
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            throw new IOException("Invalid model resource pack", e);
        }
    }

    public static String resource(String value) throws IOException {
        if (value == null || value.length() > 256 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || value.contains("..") || value.contains("//") || value.split(":", 2)[1].startsWith("/"))
            throw new IOException("Invalid model resource path");
        return value;
    }
    private static byte[] read(Resources resources, String id, int maximum) throws IOException {
        byte[] data = resources.read(id, maximum);
        if (data == null || data.length == 0 || data.length > maximum) throw new IOException("Resource byte limit exceeded");
        return data;
    }
    private static String utf8(byte[] data) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(data)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IOException("Invalid UTF-8 resource", e); }
    }
}
