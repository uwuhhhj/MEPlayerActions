package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.model.nativeysm.PublicYsmCodec;
import com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel;
import com.simmc.meplayeractions.client.model.nativeysm.YSMBinaryDeserializer;
import com.simmc.meplayeractions.client.network.AssetTransfer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Adapts the mature Sparkle public binary model into the same native YSM runtime as a folder. */
public final class NativeYsmFile {
    private NativeYsmFile() { }

    public static YsmFolderModel.Imported read(byte[] encoded, String textureId) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > LocalModelBudget.MAX_BYTES)
            throw new IOException("原生 .ysm 文件大小无效");
        try {
            byte[] clear = PublicYsmCodec.decode(encoded);
            YsmFolderModel.Imported parsed = readDecompressed(clear, textureId);
            return parsed;
        } catch (Exception invalid) { throw new IOException("公开 .ysm 模型无法导入: " + invalid.getMessage(), invalid); }
    }

    /** Public decompressed model format; no key lookup, cache access, network or filesystem operation. */
    public static YsmFolderModel.Imported readDecompressed(byte[] clear, String textureId) throws IOException {
        if (clear == null || clear.length < 4 || clear.length > LocalModelBudget.MAX_BYTES) throw new IOException("YSM 二进制大小无效");
        int format = ByteBuffer.wrap(clear).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (format < 1 || format > 32) throw new IOException("不支持的公开 YSM 二进制格式: " + format);
        try (YSMBinaryDeserializer decoder = new YSMBinaryDeserializer(clear)) {
            RawYsmModel model = decoder.deserializeKeepOpen();
            return importModel(model, textureId);
        } catch (Exception invalid) { throw new IOException("YSM 二进制资源无法解析: " + invalid.getMessage(), invalid); }
    }

    static YsmFolderModel.Imported importModel(RawYsmModel model, String textureId) throws IOException {
        if (model.mainEntity.mainModel == null || model.mainEntity.textures.isEmpty()) throw new IOException("YSM 主模型或贴图缺失");
        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonObject manifest = new JsonObject(); manifest.addProperty("spec", 2);
        // Sparkle's BB converter uses the folder profile (65535), not a public binary version.
        if (model.formatVersion != 65535) manifest.addProperty("mpa_native_format", model.formatVersion);
        if ("sparkle_morpher:bbmodel_import".equals(model.footer.extra))
            manifest.addProperty("mpa_source_format", "bbmodel");
        manifest.add("metadata", metadata(model.metadata, files)); manifest.add("properties", properties(model.properties, files));
        JsonObject declarations = new JsonObject(), player = new JsonObject(), geometry = new JsonObject(), animationFiles = new JsonObject();
        declarations.add("player", player); manifest.add("files", declarations);
        geometry.addProperty("main", "models/main.json"); putJson(files, "models/main.json", geometry(model.mainEntity.mainModel));
        if (model.mainEntity.armModel != null) {
            geometry.addProperty("arm", "models/arm.json"); putJson(files, "models/arm.json", geometry(model.mainEntity.armModel));
        }
        player.add("model", geometry);
        for (var entry : model.mainEntity.animationFiles.entrySet()) {
            // BBToRawConverter stores animation-main; binary files already use main/arm/extra.
            String family = entry.getKey().startsWith("animation-") ? entry.getKey().substring(10) : entry.getKey();
            String path = "animations/" + family + ".json";
            if (!YsmFolderModel.safeRelativePath(path)) throw new IOException("YSM 动画族名称无效");
            if (animationFiles.has(family)) throw new IOException("YSM 动画族名称重复");
            putJson(files, path, animations(entry.getValue())); animationFiles.addProperty(family, path);
        }
        if (!animationFiles.has("main")) {
            JsonObject empty = new JsonObject(); empty.add("animations", new JsonObject());
            putJson(files, "animations/main.json", empty); animationFiles.addProperty("main", "animations/main.json");
        }
        player.add("animation", animationFiles); player.add("texture", textures(model.mainEntity.textures, "textures", files));
        player.add("animation_controllers", controllers(model.mainEntity.animationControllerFiles, "controllers/player.json", files));
        declarations.add("vehicles", subEntities(model.vehicles, "vehicles", files));
        declarations.add("projectiles", subEntities(model.projectiles, "projectiles", files));
        declarations.addProperty("sound_path", "sounds");
        for (var sound : model.soundFiles.entrySet()) files.put("sounds/" + leaf(sound.getKey(), ".ogg"), sound.getValue().data);
        for (var function : model.functionFiles.entrySet()) files.put("functions/" + leaf(function.getKey(), ".molang"), function.getValue().data);
        for (var locale : model.languageFiles.entrySet()) {
            JsonObject strings = new JsonObject(); locale.getValue().data.forEach(strings::addProperty);
            putJson(files, "lang/" + leaf(locale.getKey(), ".json"), strings);
        }
        putJson(files, "ysm.json", manifest);
        return YsmFolderModel.readMemoryWithProfile(files, textureId, model.formatVersion);
    }

    private static JsonObject geometry(RawYsmModel.RawGeometry source) throws IOException {
        JsonObject body = new JsonObject(), description = new JsonObject(), result = new JsonObject();
        description.addProperty("identifier", source.identifier); description.addProperty("texture_width", source.textureWidth);
        description.addProperty("texture_height", source.textureHeight); description.addProperty("visible_bounds_width", source.visibleBoundsWidth);
        description.addProperty("visible_bounds_height", source.visibleBoundsHeight); body.add("description", description);
        JsonArray bones = new JsonArray(); body.add("bones", bones);
        if (source.bones.size() > 2047) throw new IOException("YSM 骨骼数量超出限制");
        int faceCount = 0;
        for (RawYsmModel.RawBone bone : source.bones) {
            JsonObject entry = new JsonObject(); entry.addProperty("name", bone.name); entry.addProperty("parent", bone.parentName);
            entry.add("pivot", array(-bone.pivot[0], bone.pivot[1], bone.pivot[2]));
            entry.add("rotation", array(-Math.toDegrees(bone.rotation[0]), -Math.toDegrees(bone.rotation[1]), Math.toDegrees(bone.rotation[2])));
            JsonArray faces = new JsonArray(); entry.add("ysm_baked_faces", faces);
            for (var cube : bone.cubes) for (var face : cube.faces) {
                if (++faceCount > 24_576) throw new IOException("YSM 烘焙面数量超出限制");
                JsonObject converted = new JsonObject(); JsonArray positions = new JsonArray(), uv = new JsonArray();
                for (int i = 0; i < 4; i++) {
                    // Raw binary positions are already cube-baked block coordinates, and UVs are already normalized.
                    positions.add(array(face.positions[i][0] * 16, face.positions[i][1] * 16, face.positions[i][2] * 16));
                    uv.add(face.u[i]); uv.add(face.v[i]);
                }
                converted.add("positions", positions); converted.add("uv", uv); converted.add("normal", array(face.normal[0], face.normal[1], face.normal[2]));
                faces.add(converted);
            }
            bones.add(entry);
        }
        JsonArray geometries = new JsonArray(); geometries.add(body); result.add("minecraft:geometry", geometries); return result;
    }

    private static JsonObject animations(RawYsmModel.RawAnimationFile file) {
        JsonObject result = new JsonObject(), animations = new JsonObject(); result.add("animations", animations);
        for (var animation : file.animations.values()) {
            JsonObject value = new JsonObject(), bones = new JsonObject();
            value.addProperty("loop", animation.loopMode == 1 ? "loop" : animation.loopMode == 3 ? "hold_on_last_frame" : "once");
            if (Float.isFinite(animation.length)) value.addProperty("animation_length", animation.length);
            if (animation.bbEditorAxes != 0) value.addProperty("mpa_bb_editor_axes", animation.bbEditorAxes);
            if (animation.blendWeight != null) value.add("blend_weight", scalar(animation.blendWeight));
            for (var bone : animation.boneAnimations) {
                JsonObject channels = new JsonObject();
                channel(channels, "rotation", bone.rotation); channel(channels, "position", bone.position); channel(channels, "scale", bone.scale);
                bones.add(bone.boneName, channels);
            }
            value.add("bones", bones);
            JsonObject timeline = new JsonObject();
            for (var event : animation.timelineEvents) {
                String time = Float.toString(event.timestamp);
                JsonArray programs = timeline.has(time) ? timeline.getAsJsonArray(time) : new JsonArray();
                event.events.forEach(programs::add); timeline.add(time, programs);
            }
            if (!timeline.isEmpty()) value.add("timeline", timeline);
            JsonObject sounds = new JsonObject();
            for (var effect : animation.soundEffects) {
                String time = Float.toString(effect.timestamp); JsonObject sound = new JsonObject(); sound.addProperty("effect", effect.effectName);
                JsonArray events = sounds.has(time) ? sounds.getAsJsonArray(time) : new JsonArray(); events.add(sound); sounds.add(time, events);
            }
            if (!sounds.isEmpty()) value.add("sound_effects", sounds);
            animations.add(animation.name, value);
        }
        return result;
    }

    private static void channel(JsonObject target, String name, List<RawYsmModel.RawKeyframe> source) {
        if (source.isEmpty()) return;
        JsonObject frames = new JsonObject();
        for (var key : source) {
            JsonObject value = new JsonObject();
            value.addProperty("lerp_mode", key.interpolationMode == 1 ? "step" : key.interpolationMode == 2 ? "catmullrom" : "linear");
            value.add("post", expressions(key.postData));
            if (key.hasPreData) value.add("pre", expressions(key.preData));
            if (key.bbSynthetic) value.addProperty("mpa_bb_synthetic", true);
            frames.add(Float.toString(key.timestamp), value);
        }
        target.add(name, frames);
    }

    private static JsonArray controllers(List<RawYsmModel.RawAnimationControllerFile> source, String path, Map<String, byte[]> files) {
        JsonArray paths = new JsonArray(); if (source.isEmpty()) return paths;
        JsonObject root = new JsonObject(), definitions = new JsonObject(); root.add("animation_controllers", definitions);
        for (var file : source) for (var controller : file.controllers.values()) {
            JsonObject entry = new JsonObject(), states = new JsonObject(); entry.addProperty("initial_state", controller.initialState); entry.add("states", states);
            for (var state : controller.states) {
                JsonObject definition = new JsonObject(); JsonArray animations = new JsonArray(), transitions = new JsonArray();
                state.animations.forEach((name, weight) -> { JsonObject pair = new JsonObject(); pair.addProperty(name, weight); animations.add(pair); });
                state.transitions.forEach((name, condition) -> { JsonObject pair = new JsonObject(); pair.addProperty(name, condition); transitions.add(pair); });
                definition.add("animations", animations); definition.add("transitions", transitions);
                definition.add("on_entry", texts(state.onEntry)); definition.add("on_exit", texts(state.onExit));
                if (state.blendTransitions.isEmpty()) definition.addProperty("blend_transition", state.blendTransitionValue);
                else {
                    JsonObject curve = new JsonObject(); state.blendTransitions.forEach((time, weight) -> curve.addProperty(Float.toString(time), weight));
                    definition.add("blend_transition", curve);
                }
                definition.addProperty("blend_via_shortest_path", state.blendViaShortestPath);
                if (!state.soundEffects.isEmpty()) { JsonArray sounds = new JsonArray(); state.soundEffects.forEach(name -> { JsonObject sound = new JsonObject(); sound.addProperty("effect", name); sounds.add(sound); }); definition.add("sound_effects", sounds); }
                states.add(state.name, definition);
            }
            definitions.add(controller.animationName, entry);
        }
        putJson(files, path, root); paths.add(path); return paths;
    }

    private static JsonArray textures(Map<String, RawYsmModel.RawTexture> source, String folder, Map<String, byte[]> files) throws IOException {
        JsonArray values = new JsonArray();
        for (var texture : source.values()) {
            String path = folder + "/" + imageLeaf(texture.name); JsonObject material = new JsonObject();
            files.put(path, png(texture.data, texture.imageFormat, texture.width, texture.height)); material.addProperty("uv", path);
            for (var sub : texture.subTextures) {
                String type = sub.specularType == 1 ? "normal" : sub.specularType == 2 ? "specular" : null;
                if (type == null) throw new IOException("YSM 未知材质贴图类型: " + sub.specularType);
                String subPath = folder + "/" + imageLeaf(texture.name) + "." + type + ".png";
                files.put(subPath, png(sub.data, sub.imageFormat, sub.width, sub.height)); material.addProperty(type, subPath);
            }
            values.add(material);
        }
        return values;
    }

    private static JsonArray subEntities(Map<String, RawYsmModel.RawSubEntity> source, String kind, Map<String, byte[]> files) throws IOException {
        JsonArray result = new JsonArray(); int index = 0;
        for (var entity : source.values()) {
            if (entity.model == null || entity.textures.isEmpty()) continue;
            String folder = kind + "/" + index++; JsonObject declaration = new JsonObject();
            declaration.add("match", texts(entity.matchIds == null ? List.of(entity.identifier) : Arrays.asList(entity.matchIds)));
            declaration.addProperty("model", folder + "/model.json"); putJson(files, folder + "/model.json", geometry(entity.model));
            JsonObject merged = new JsonObject(), animations = new JsonObject(); merged.add("animations", animations);
            for (var animationFile : entity.animationFiles.values()) animations(animationFile).getAsJsonObject("animations").entrySet().forEach(entry -> animations.add(entry.getKey(), entry.getValue()));
            putJson(files, folder + "/animations.json", merged); declaration.addProperty("animation", folder + "/animations.json");
            declaration.add("texture", textures(entity.textures, folder + "/textures", files));
            declaration.add("animation_controllers", controllers(entity.animationControllerFiles, folder + "/controllers.json", files)); result.add(declaration);
        }
        return result;
    }

    private static JsonObject metadata(RawYsmModel.RawMetadata source, Map<String, byte[]> files) throws IOException {
        JsonObject result = new JsonObject(), license = new JsonObject(); result.addProperty("name", source.name); result.addProperty("tips", source.tips);
        license.addProperty("type", source.licenseType); license.addProperty("desc", source.licenseDescription); result.add("license", license);
        JsonArray authors = new JsonArray(); int index = 0;
        for (var author : source.authors) {
            JsonObject entry = new JsonObject(); entry.addProperty("name", author.name); entry.addProperty("role", author.role); entry.addProperty("comment", author.comment);
            JsonObject contacts = new JsonObject(); author.contacts.forEach(contacts::addProperty); entry.add("contact", contacts);
            if (author.avatarImage != null && author.avatarImage.format != 5) {
                String path = "avatar/" + index + ".png"; var image = author.avatarImage;
                files.put(path, png(image.data, image.format, image.width, image.height)); entry.addProperty("avatar", path);
            }
            authors.add(entry); index++;
        }
        result.add("authors", authors); JsonObject links = new JsonObject(); source.links.forEach(links::addProperty); result.add("link", links); return result;
    }

    private static JsonObject properties(RawYsmModel.RawProperties source, Map<String, byte[]> files) throws IOException {
        JsonObject result = new JsonObject(); result.addProperty("height_scale", source.heightScale); result.addProperty("width_scale", source.widthScale);
        result.addProperty("default_texture", stem(source.defaultTexture)); result.addProperty("preview_animation", source.previewAnimation);
        result.addProperty("free", source.isFree); result.addProperty("render_layers_first", source.renderLayersFirst); result.addProperty("all_cutout", source.allCutout);
        result.addProperty("merge_multiline_expr", source.mergeMultilineExpr);
        result.addProperty("disable_preview_rotation", source.disablePreviewRotation); result.addProperty("gui_no_lighting", source.guiNoLighting);
        JsonObject extra = new JsonObject(); source.extraAnimations.forEach(extra::addProperty); result.add("extra_animation", extra);
        JsonArray groups = new JsonArray();
        for (var group : source.extraAnimationClassifies) { JsonObject entry = new JsonObject(), items = new JsonObject(); entry.addProperty("id", group.id); group.extras.forEach(items::addProperty); entry.add("extra_animation", items); groups.add(entry); }
        result.add("extra_animation_classify", groups); JsonArray buttons = new JsonArray();
        for (var button : source.extraAnimationButtons) {
            JsonObject entry = new JsonObject(); entry.addProperty("id", button.id); entry.addProperty("name", button.name); entry.addProperty("description", button.description);
            JsonArray forms = new JsonArray();
            for (var form : button.forms) {
                JsonObject value = new JsonObject(), labels = new JsonObject(); value.addProperty("type", form.type); value.addProperty("title", form.title);
                value.addProperty("description", form.description); value.addProperty("value", form.defaultValue);
                value.addProperty("step", form.step); value.addProperty("min", form.min); value.addProperty("max", form.max);
                form.labels.forEach(labels::addProperty); value.add("labels", labels); forms.add(value);
            }
            entry.add("config_forms", forms); buttons.add(entry);
        }
        result.add("extra_animation_buttons", buttons);
        for (var image : source.backgroundImages) {
            String path = "backgrounds/" + imageLeaf(image.name); files.put(path, png(image.data, image.format, image.width, image.height));
            if (image.name.equals(source.guiBackground)) result.addProperty("gui_background", path);
            if (image.name.equals(source.guiForeground)) result.addProperty("gui_foreground", path);
        }
        return result;
    }

    private static byte[] png(byte[] data, int format, int width, int height) throws IOException {
        return NativeYsmImages.png(data, format, width, height);
    }

    private static String leaf(String name, String suffix) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("YSM 资源名称缺失");
        String value = name.endsWith(suffix) ? name : name + suffix;
        if (value.contains("/") || !YsmFolderModel.safeRelativePath(value)) throw new IOException("YSM 资源名称无效: " + name);
        return value;
    }
    private static String imageLeaf(String name) throws IOException {
        if ("/ARROW\\".equals(name)) return "arrow.png"; // Mature LegacyV15 reserved texture identifier, not an author path.
        return leaf(name != null && name.matches("(?i).+\\.(png|bmp|jpg|jpeg|webp|avif)") ? stem(name) : name, ".png");
    }
    private static String stem(String value) { int dot = value.lastIndexOf('.'); return dot < 0 ? value : value.substring(0, dot); }
    private static void putJson(Map<String, byte[]> files, String path, JsonObject json) { files.put(path, json.toString().getBytes(StandardCharsets.UTF_8)); }
    private static JsonArray texts(Collection<String> values) { JsonArray result = new JsonArray(); values.forEach(result::add); return result; }
    private static JsonArray expressions(Object[] values) { JsonArray result = new JsonArray(); for (Object value : values) result.add(scalar(value == null ? 0 : value)); return result; }
    private static JsonElement scalar(Object value) { return value instanceof Number number ? new JsonPrimitive(number) : new JsonPrimitive(value.toString()); }
    private static JsonArray array(double... values) { JsonArray result = new JsonArray(); for (double value : values) result.add(value); return result; }
}
