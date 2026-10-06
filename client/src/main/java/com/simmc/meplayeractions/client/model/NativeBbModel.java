package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBModelFile;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBModelParser;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBTexture;
import com.simmc.meplayeractions.client.model.nativebbmodel.BBToRawConverter;
import com.simmc.meplayeractions.client.model.nativebbmodel.NativeBbmodelActions;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Host boundary around the migrated Sparkle parser/converter; server blueprints keep BbModel.parse. */
public final class NativeBbModel {
    private NativeBbModel() { }

    /** Both local import and a passive private receiver run the same source conversion chain. */
    public static YsmFolderModel.Imported read(byte[] bytes, Map<String, byte[]> sourceFiles, String textureId) throws IOException {
        JsonObject document = document(bytes);
        // Earlier MPA exports carry already converted runtime data, not author Blockbench input.
        if (document.has("ysm_format_version")) {
            if (textureId != null && !textureId.isEmpty()) throw new IOException("旧转换模型没有皮肤列表");
            BbModel.parseLocal(bytes);
            return new YsmFolderModel.Imported(bytes, "", YsmModelProfile.empty(), sourceFiles);
        }
        try {
            BBModelFile model = BBModelParser.parse(document.toString());
            validateModel(model);
            Map<String, byte[]> textures = sideTextures(sourceFiles);
            var raw = BBToRawConverter.convert(model, textures);
            NativeBbmodelActions.apply(raw);
            return NativeYsmFile.importModel(raw, textureId);
        } catch (RuntimeException invalid) {
            throw new IOException("Blockbench 原生导入失败: " + invalid.getMessage(), invalid);
        }
    }

    /** Read only declared PNG companions beneath the selected model directory, without following links. */
    public static Map<String, byte[]> localSourceFiles(Path modelPath, byte[] bytes) throws IOException {
        JsonObject document = document(bytes);
        Map<String, byte[]> files = new LinkedHashMap<>(); files.put("model.bbmodel", bytes);
        if (document.has("ysm_format_version")) return files;
        BBModelFile model;
        try { model = BBModelParser.parse(document.toString()); validateModel(model); }
        catch (RuntimeException invalid) { throw new IOException("Blockbench 模型结构无效", invalid); }
        Path root = modelPath.toAbsolutePath().normalize().getParent();
        for (BBTexture texture : model.textures) {
            // Embedded textures retain upstream embedded source semantics; no filesystem lookup is needed.
            if (texture.isEmbedded()) continue;
            String declared = texture.relative_path;
            if (declared == null || declared.isBlank()) declared = texture.name;
            String relative = declared == null ? "" : declared.replace('\\', '/');
            if (!YsmFolderModel.safeRelativePath(relative) || !relative.toLowerCase(Locale.ROOT).endsWith(".png"))
                throw new IOException("Blockbench 外部贴图必须是模型目录内的 PNG");
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(root)) throw new IOException("Blockbench 贴图不能离开模型目录");
            for (Path ancestor = target.getParent(); ancestor != null && ancestor.startsWith(root); ancestor = ancestor.getParent())
                if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(ancestor)
                        || !ancestor.toRealPath().equals(ancestor.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Blockbench 贴图目录不能包含链接");
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)
                    || !target.toRealPath().equals(target.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Blockbench 贴图必须是普通文件");
            try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] data = input.readNBytes(LocalModelBudget.MAX_BYTES + 1);
                if (data.length == 0 || data.length > LocalModelBudget.MAX_BYTES) throw new IOException("Blockbench 贴图大小无效");
                files.put(relative, data);
            }
            NativeModelBundle.checkedLocalFiles(files);
        }
        return NativeModelBundle.checkedLocalFiles(files);
    }

    private static Map<String, byte[]> sideTextures(Map<String, byte[]> files) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (var entry : files.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".png")) continue;
            String leaf = name.substring(name.lastIndexOf('/') + 1);
            if (result.putIfAbsent(leaf, entry.getValue()) != null) throw new IOException("Blockbench 伴随贴图文件名重复");
        }
        return result;
    }

    /** Resource budgets are applied before upstream allocates meshes or bakes curves. */
    private static void validateModel(BBModelFile model) throws IOException {
        if (model.elements.size() > 4096 || model.groups.size() > 2047 || model.textures.isEmpty()
                || model.textures.size() > 16 || model.animations.size() > 128 || model.animation_controllers.size() > 64)
            throw new IOException("Blockbench 结构数量超出限制");
        int faces = 0, vertices = 0, frames = 0;
        if (outlinerBones(model.outliner) > 2047) throw new IOException("Blockbench 骨骼数量超出限制");
        for (var element : model.elements) {
            faces += element.cube_faces.size(); vertices += element.vertices.size();
            // The source converter triangulates N-gons into N-2 baked faces, with quads kept whole.
            for (var face : element.faces.values())
                faces += face.vertices.length == 4 ? 1 : Math.max(0, face.vertices.length - 2);
            if (faces > 24_576 || vertices > 98_304) throw new IOException("Blockbench 几何展开数量超出限制");
        }
        for (var animation : model.animations) for (var animator : animation.animators.values()) {
            // Source numeric Bezier baking emits at most 24 samples for one segment.
            // Bound that expansion before allocating its RawYSM keyframes.
            for (var keyframe : animator.keyframes)
                frames += "bezier".equalsIgnoreCase(keyframe.interpolation) ? 24 : 1;
            if (frames > 200_000) throw new IOException("Blockbench 关键帧展开数量超出限制");
        }
    }

    private static int outlinerBones(java.util.List<com.simmc.meplayeractions.client.model.nativebbmodel.BBOutlinerNode> nodes) {
        int count = 0;
        for (var node : nodes) {
            if (!node.isGroup()) continue;
            count += 1 + outlinerBones(node.children);
            if (count > 2047) return count;
        }
        return count;
    }

    private static JsonObject document(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > LocalModelBudget.MAX_BYTES) throw new IOException("Blockbench 文件大小无效");
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT); int[] count = {0};
            JsonElement parsed = json(reader, 0, count);
            if (!parsed.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Blockbench JSON 根对象无效");
            return parsed.getAsJsonObject();
        } catch (IllegalStateException | NumberFormatException invalid) { throw new IOException("Blockbench JSON 无效", invalid); }
    }

    private static JsonElement json(JsonReader reader, int depth, int[] count) throws IOException {
        if (depth > 64 || ++count[0] > 200_000) throw new IOException("Blockbench JSON 结构超出限制");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > 1024 || object.has(name)) throw new IOException("Blockbench JSON 字段重复或过长");
                    object.add(name, json(reader, depth + 1, count));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(json(reader, depth + 1, count));
                reader.endArray(); yield array;
            }
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 48) throw new IOException("Blockbench JSON 数值过长");
                float value = Float.parseFloat(number);
                if (!Float.isFinite(value)) throw new IOException("Blockbench JSON 数值不是有限值");
                yield new JsonPrimitive(new java.math.BigDecimal(number));
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Blockbench JSON 标记无效");
        };
    }
}
