package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import com.simmc.meplayeractions.expression.Molang;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the bounded player subset of a YSM folder into the existing cube renderer's format. */
public final class YsmFolderModel {
    public static final String DEFAULT_ID = "openysm_default";
    private static final String DEFAULT_ROOT = "/assets/meplayeractions/builtin/openysm_default/";
    private static final int MAX_BONES = 2047, MAX_CUBES = 4096, MAX_FRAMES = 200_000;
    private static final Pattern NULL_DEFAULT = Pattern.compile("((?:v|variable)\\.[A-Za-z0-9_.]+)\\s*\\?\\?\\s*([+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+))");
    private YsmFolderModel() { }

    public static byte[] bundledDefault() throws IOException {
        return bundledDefault(false);
    }

    public static byte[] bundledDefault(boolean alternateTexture) throws IOException {
        return convert(new Assets(name -> {
            try (InputStream input = YsmFolderModel.class.getResourceAsStream(DEFAULT_ROOT + name)) {
                if (input == null) throw new IOException("默认 YSM 模型资源缺失: " + name);
                return boundedRead(input);
            }
        }), alternateTexture ? "textures/blue.png" : null);
    }

    public static byte[] read(Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        checkOrdinaryDirectories(root);
        return convert(new Assets(name -> {
            Path file = root.resolve(name).normalize();
            if (!file.startsWith(root) || file.equals(root)) throw new IOException("YSM 资源不能离开模型文件夹");
            checkOrdinaryDirectories(file.getParent());
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("YSM 资源必须是普通文件: " + name);
            if (Files.size(file) > AssetTransfer.MAX_RAW) throw new IOException("YSM 资源超过 8 MiB");
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { return boundedRead(input); }
        }), null);
    }

    private static void checkOrdinaryDirectories(Path directory) throws IOException {
        for (Path ancestor = directory; ancestor != null; ancestor = ancestor.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("YSM 文件夹及资源路径不能包含链接");
        }
    }

    @FunctionalInterface private interface Reader { byte[] read(String name) throws IOException; }
    private static final class Assets {
        final Reader reader; int bytes;
        Assets(Reader reader) { this.reader = reader; }
        byte[] get(String name, String suffix) throws IOException {
            if (!safeRelativePath(name) || !name.toLowerCase(Locale.ROOT).endsWith(suffix))
                throw new IOException("YSM 资源路径或扩展名无效: " + name);
            byte[] result = reader.read(name);
            if (result.length == 0 || (bytes += result.length) > AssetTransfer.MAX_RAW)
                throw new IOException("YSM 主模型、动画和贴图总大小不能超过 8 MiB");
            return result;
        }
        JsonObject json(String name) throws IOException { return parseJson(get(name, ".json")); }
    }

    static boolean safeRelativePath(String name) {
        if (name == null || name.isBlank() || name.length() > 256 || !name.equals(name.strip())
                || name.matches(".*[<>:\"\\\\|?*\\p{Cntrl}].*") || name.startsWith("/")) return false;
        for (String segment : name.split("/", -1))
            if (segment.isBlank() || segment.startsWith(".") || segment.contains("..") || !segment.equals(segment.strip())) return false;
        return true;
    }

    private static byte[] boundedRead(InputStream input) throws IOException {
        byte[] result = input.readNBytes(AssetTransfer.MAX_RAW + 1);
        if (result.length == 0 || result.length > AssetTransfer.MAX_RAW) throw new IOException("YSM 资源大小超出限制");
        return result;
    }

    private static JsonObject parseJson(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            int depth = 0; boolean quoted = false, escaped = false;
            for (char c : text.toCharArray()) {
                if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
                else if (c == '"') quoted = true;
                else if (c == '{' || c == '[') { if (++depth > 96) throw new IOException("YSM JSON 嵌套过深"); }
                else if (c == '}' || c == ']') { if (--depth < 0) throw new IOException("YSM JSON 不完整"); }
            }
            if (depth != 0 || quoted) throw new IOException("YSM JSON 不完整");
            return object(JsonParser.parseString(text));
        } catch (RuntimeException | java.nio.charset.CharacterCodingException error) { throw new IOException("无效的 UTF-8 YSM JSON", error); }
    }

    private static byte[] convert(Assets assets, String textureOverride) throws IOException {
        try {
            JsonObject manifest = assets.json("ysm.json");
            if (number(manifest, "spec", 2) != 2) throw new IOException("目前支持 YSM spec 2 主模型文件夹");
            JsonObject player = object(object(manifest.get("files")).get("player"));
            JsonObject modelFiles = object(player.get("model")), animationFiles = object(player.get("animation"));
            JsonArray textures = array(player.get("texture"));
            if (textures.isEmpty() || textures.size() > 16) throw new IOException("YSM 玩家贴图列表无效");
            String texture = textures.get(0).getAsString();
            JsonObject properties = manifest.has("properties") ? object(manifest.get("properties")) : new JsonObject();
            String preferred = string(properties, "default_texture", "");
            for (JsonElement candidate : textures) {
                String path = candidate.getAsString();
                String stem = path.substring(path.lastIndexOf('/') + 1).replaceFirst("(?i)\\.png$", "");
                if (!preferred.isEmpty() && stem.equals(preferred)) { texture = path; break; }
            }
            if (textureOverride != null) texture = textureOverride;
            JsonObject geometry = assets.json(string(modelFiles, "main", ""));
            JsonObject main = assets.json(string(animationFiles, "main", ""));
            JsonObject extra = animationFiles.has("extra") ? assets.json(string(animationFiles, "extra", "")) : null;
            byte[] png = assets.get(texture, ".png");
            Converter converter = new Converter(geometry, png);
            converter.animations(main);
            if (extra != null) converter.animations(extra);
            converter.installPhysics();
            byte[] result = converter.output.toString().getBytes(StandardCharsets.UTF_8);
            if (result.length > AssetTransfer.MAX_RAW) throw new IOException("转换后的模型超过 8 MiB");
            // Validate the same geometry, PNG, expression and keyframe limits used at render time.
            BbModel.parse(result);
            return result;
        } catch (RuntimeException error) { throw new IOException("YSM 主模型或动画无法解析: " + error.getMessage(), error); }
    }

    private static final class Converter {
        final JsonObject output = new JsonObject();
        final JsonArray elements = new JsonArray(), clips = new JsonArray();
        final Map<String, JsonObject> nodes = new LinkedHashMap<>();
        final Map<String, String> parents = new LinkedHashMap<>();
        final Map<String, JsonObject> namedClips = new LinkedHashMap<>();
        final Map<String, Spring> springs = new LinkedHashMap<>();
        final Map<String, String> defaults = new LinkedHashMap<>();
        int frames;
        Converter(JsonObject source, byte[] png) {
            JsonArray geometries = array(source.get("minecraft:geometry"));
            if (geometries.size() != 1) throw invalid("主文件必须包含一个 minecraft:geometry");
            JsonObject geometry = object(geometries.get(0)), description = object(geometry.get("description"));
            double width = number(description, "texture_width", 16), height = number(description, "texture_height", 16);
            if (width < 1 || width > 8192 || height < 1 || height > 8192) throw invalid("贴图分辨率无效");
            JsonObject meta = new JsonObject(); meta.addProperty("format_version", "4.10"); meta.addProperty("model_format", "bedrock"); output.add("meta", meta);
            JsonObject resolution = new JsonObject(); resolution.addProperty("width", width); resolution.addProperty("height", height); output.add("resolution", resolution);
            JsonObject texture = new JsonObject(); texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            texture.addProperty("uv_width", width); texture.addProperty("uv_height", height);
            JsonArray textures = new JsonArray(); textures.add(texture); output.add("textures", textures);
            output.add("elements", elements); output.add("animations", clips);
            JsonArray bones = array(geometry.get("bones"));
            if (bones.isEmpty() || bones.size() > MAX_BONES) throw invalid("YSM 骨骼数量超出限制");
            for (JsonElement value : bones) {
                JsonObject bone = object(value); String name = string(bone, "name", "");
                if (name.isBlank() || name.length() > 128 || nodes.containsKey(name)) throw invalid("YSM 骨骼名称缺失或重复");
                JsonObject node = new JsonObject(); node.addProperty("uuid", id("bone:" + name)); node.addProperty("name", name);
                node.add("origin", numericVector(bone.get("pivot"), true, false, 0));
                node.add("rotation", numericVector(bone.get("rotation"), true, true, 0)); node.add("children", new JsonArray());
                if (bone.has("binding") || bone.has("poly_mesh") || bone.has("locators")) {
                    // Locators do not produce cube geometry; expression bindings and meshes need a different renderer.
                    if (bone.has("binding") || bone.has("poly_mesh")) throw invalid("YSM 骨骼绑定和网格暂不支持");
                }
                nodes.put(name, node); parents.put(name, string(bone, "parent", ""));
                JsonArray cubes = bone.has("cubes") ? array(bone.get("cubes")) : new JsonArray();
                for (JsonElement cube : cubes) cube(object(cube), bone, node);
            }
            JsonArray roots = new JsonArray();
            for (var entry : nodes.entrySet()) {
                String parent = parents.get(entry.getKey());
                if (parent.isEmpty()) roots.add(entry.getValue());
                else {
                    JsonObject parentNode = nodes.get(parent);
                    if (parentNode == null) throw invalid("YSM 骨骼父级不存在: " + parent);
                    parentNode.getAsJsonArray("children").add(entry.getValue());
                }
            }
            Set<String> visited = new HashSet<>();
            for (JsonElement root : roots) validateTree(object(root), visited, 1);
            if (visited.size() != nodes.size()) throw invalid("YSM 骨骼存在循环引用");
            output.add("outliner", roots);
        }

        void validateTree(JsonObject node, Set<String> visited, int depth) {
            if (depth > 63 || !visited.add(node.get("uuid").getAsString())) throw invalid("YSM 骨骼层级无效或过深");
            for (JsonElement child : node.getAsJsonArray("children")) if (child.isJsonObject()) validateTree(object(child), visited, depth + 1);
        }

        void cube(JsonObject source, JsonObject bone, JsonObject node) {
            if (elements.size() >= MAX_CUBES) throw invalid("YSM 方块数量超出限制");
            double[] origin = vector(source.get("origin"), 0), size = vector(source.get("size"), 0);
            for (double axis : size) if (axis < 0) throw invalid("YSM 方块大小不能为负");
            JsonObject cube = new JsonObject(); String uuid = id("cube:" + elements.size()); cube.addProperty("uuid", uuid);
            cube.add("from", numbers(-origin[0] - size[0], origin[1], origin[2]));
            cube.add("to", numbers(-origin[0], origin[1] + size[1], origin[2] + size[2]));
            cube.add("origin", numericVector(source.get("pivot"), true, false, 0));
            cube.add("rotation", numericVector(source.get("rotation"), true, true, 0));
            cube.addProperty("inflate", number(source, "inflate", number(bone, "inflate", 0)));
            boolean mirror = source.has("mirror") ? source.get("mirror").getAsBoolean() : bone.has("mirror") && bone.get("mirror").getAsBoolean();
            JsonElement uv = source.get("uv"); JsonObject uvFaces;
            if (uv != null && uv.isJsonArray()) {
                double[] start = vector2(uv), dimensions = Arrays.stream(size).map(Math::floor).toArray();
                double u = start[0], v = start[1], x = dimensions[0], y = dimensions[1], z = dimensions[2];
                uvFaces = new JsonObject();
                uvFaces.add("north", uv(u + z, v + z, x, y)); uvFaces.add("south", uv(u + z + x + z, v + z, x, y));
                uvFaces.add("east", uv(u, v + z, z, y)); uvFaces.add("west", uv(u + z + x, v + z, z, y));
                uvFaces.add("up", uv(u + z, v, x, z)); uvFaces.add("down", uv(u + z + x, v + z, x, -z));
            } else uvFaces = object(uv);
            JsonObject faces = new JsonObject();
            for (String side : List.of("north", "south", "east", "west", "up", "down")) {
                String selected = mirror && side.equals("east") ? "west" : mirror && side.equals("west") ? "east" : side;
                if (!uvFaces.has(selected)) continue;
                JsonObject faceSource = object(uvFaces.get(selected)); double[] start = vector2(faceSource.get("uv")), extent = vector2(faceSource.get("uv_size"));
                double u0 = start[0], v0 = start[1], u1 = u0 + extent[0], v1 = v0 + extent[1];
                if (mirror) { double saved = u0; u0 = u1; u1 = saved; }
                if (side.equals("up") || side.equals("down")) { double saved = u0; u0 = u1; u1 = saved; saved = v0; v0 = v1; v1 = saved; }
                JsonObject face = new JsonObject(); face.add("uv", numbers(u0, v0, u1, v1)); face.addProperty("texture", 0);
                face.addProperty("rotation", number(faceSource, "uv_rotation", 0)); faces.add(side, face);
            }
            cube.add("faces", faces); elements.add(cube); node.getAsJsonArray("children").add(uuid);
        }

        void animations(JsonObject source) {
            JsonObject animations = object(source.get("animations"));
            if (namedClips.size() + animations.size() > 120) throw invalid("YSM 动作数量超出限制");
            for (var entry : animations.entrySet()) {
                String name = entry.getKey(); JsonObject authored = object(entry.getValue());
                if (name.isBlank() || name.length() > 128 || namedClips.containsKey(name)) throw invalid("YSM 动作名缺失或重复");
                for (String unsupported : List.of("animation_time_update", "blend_weight", "start_delay", "loop_delay", "particle_effects", "sound_effects"))
                    if (authored.has(unsupported)) throw invalid("YSM 动作字段暂不支持: " + unsupported);
                JsonObject clip = new JsonObject(); clip.addProperty("name", name); JsonObject animators = new JsonObject(); clip.add("animators", animators);
                double length = number(authored, "animation_length", 0);
                JsonObject bones = authored.has("bones") ? object(authored.get("bones")) : new JsonObject();
                for (var bone : bones.entrySet()) {
                    JsonObject node = nodes.get(bone.getKey());
                    if (node == null) continue; // Reference packs contain tracks for optional bones absent from their main geometry.
                    JsonObject animator = new JsonObject(); animator.addProperty("name", bone.getKey()); animator.addProperty("type", "bone");
                    JsonArray keys = new JsonArray(); animator.add("keyframes", keys);
                    JsonObject channels = object(bone.getValue());
                    for (String channel : List.of("position", "rotation", "scale")) if (channels.has(channel)) {
                        JsonElement data = channels.get(channel);
                        if (data.isJsonObject()) for (var key : object(data).entrySet()) {
                            double time = Double.parseDouble(key.getKey()); length = Math.max(length, time);
                            keys.add(keyframe(channel, time, key.getValue()));
                        } else keys.add(keyframe(channel, 0, data));
                    }
                    if (!keys.isEmpty()) animators.add(node.get("uuid").getAsString(), animator);
                }
                if (length < 0 || !Double.isFinite(length) || length > 3600) throw invalid("YSM 动作时长无效");
                clip.addProperty("length", length); namedClips.put(name, clip); clips.add(clip);
                if (name.matches("parallel[1-7]") && animators.size() > 0) {
                    JsonObject automatic = clip.deepCopy(); String alias = "pre_parallel_import_" + name;
                    automatic.addProperty("name", alias); namedClips.put(alias, automatic); clips.add(automatic);
                }
            }
        }

        JsonObject keyframe(String channel, double time, JsonElement source) {
            if (++frames > MAX_FRAMES || time < 0 || !Double.isFinite(time) || time > 3600) throw invalid("YSM 关键帧超出限制");
            JsonObject frame = new JsonObject(); frame.addProperty("channel", channel); frame.addProperty("time", time); frame.addProperty("interpolation", "linear");
            JsonArray points = new JsonArray();
            if (source.isJsonObject()) {
                JsonObject key = object(source); JsonElement post = key.get("post");
                if (post == null) throw invalid("YSM 关键帧缺少 post");
                if (key.has("pre")) points.add(point(channel, key.get("pre")));
                points.add(point(channel, post)); frame.addProperty("interpolation", string(key, "lerp_mode", "linear"));
            } else points.add(point(channel, source));
            frame.add("data_points", points); return frame;
        }

        JsonObject point(String channel, JsonElement data) {
            JsonArray axes;
            if (data.isJsonArray()) { axes = array(data); if (axes.size() != 3) throw invalid("YSM 动画需要三维向量"); }
            else { axes = new JsonArray(); for (int i = 0; i < 3; i++) axes.add(data.deepCopy()); }
            JsonObject point = new JsonObject();
            for (int i = 0; i < 3; i++) {
                JsonElement axis = axes.get(i);
                if (!axis.isJsonPrimitive() || axis.getAsJsonPrimitive().isBoolean()) throw invalid("YSM 动画坐标无效");
                String expression = normalize(axis.getAsString()); Molang.compile(expression);
                point.addProperty(List.of("x", "y", "z").get(i), expression);
            }
            return point;
        }

        String normalize(String expression) {
            if (expression.length() > 8192) throw invalid("YSM 表达式过长");
            Matcher fallback = NULL_DEFAULT.matcher(expression); StringBuffer result = new StringBuffer();
            while (fallback.find()) { defaults.putIfAbsent(fallback.group(1), fallback.group(2)); fallback.appendReplacement(result, Matcher.quoteReplacement(fallback.group(1))); }
            fallback.appendTail(result); expression = result.toString();
            for (int at = expression.indexOf("ysm.second_order("); at >= 0; at = expression.indexOf("ysm.second_order(")) {
                int begin = at + "ysm.second_order(".length(), end = matchingClose(expression, begin);
                List<String> args = arguments(expression.substring(begin, end));
                if (args.size() < 2 || args.size() > 5 || !args.get(0).matches("(['\"]).*\\1")) throw invalid("YSM second_order 参数无效");
                String input = normalize(args.get(1)), frequency = args.size() > 2 ? normalize(args.get(2)) : "1";
                String damping = args.size() > 3 ? normalize(args.get(3)) : "1", response = args.size() > 4 ? normalize(args.get(4)) : "1";
                String signature = args.get(0) + "|" + input + "|" + frequency + "|" + damping + "|" + response;
                if (!springs.containsKey(signature)) {
                    if (springs.size() >= 64) throw invalid("YSM 二阶物理数量超出限制");
                    springs.put(signature, new Spring("variable.ysm_import_s" + springs.size(), input, frequency, damping, response));
                }
                expression = expression.substring(0, at) + springs.get(signature).prefix + ".y" + expression.substring(end + 1);
            }
            return completeTernaries(expression);
        }

        void installPhysics() {
            if (springs.isEmpty() && defaults.isEmpty()) return;
            List<String> init = new ArrayList<>(), step = new ArrayList<>();
            defaults.forEach((name, value) -> init.add(name + "=" + value + ";"));
            for (Spring spring : springs.values()) {
                String p = spring.prefix;
                init.add(p + ".y=(" + spring.input + ");" + p + ".xp=" + p + ".y;" + p + ".yd=0;");
                // Same damped second-order equation as OpenYSM, using the renderer's fixed 10 ms step.
                step.add(p + ".x=(" + spring.input + ");" + p + ".f=math.clamp((" + spring.frequency + "),0.01,5);"
                        + p + ".z=math.clamp((" + spring.damping + "),0,1);" + p + ".r=math.clamp((" + spring.response + "),-64,64);"
                        + p + ".k1=" + p + ".z/(math.pi*" + p + ".f);" + p + ".k2=1/math.pow(2*math.pi*" + p + ".f,2);"
                        + p + ".k3=" + p + ".r*" + p + ".z/(2*math.pi*" + p + ".f);" + p + ".xd=(" + p + ".x-" + p + ".xp)/0.01;"
                        + p + ".xp=" + p + ".x;" + p + ".y=" + p + ".y+0.01*" + p + ".yd;"
                        + p + ".yd=" + p + ".yd+0.01*(" + p + ".k3*" + p + ".xd+" + p + ".x-" + p + ".y-" + p + ".k1*" + p + ".yd)/" + p + ".k2;");
            }
            scripts("parallel1", init); scripts("parallel2", step);
        }

        void scripts(String name, List<String> programs) {
            JsonObject clip = namedClips.get(name);
            if (clip == null) { clip = new JsonObject(); clip.addProperty("name", name); clip.addProperty("length", 0); clip.add("animators", new JsonObject()); namedClips.put(name, clip); clips.add(clip); }
            JsonObject animator = new JsonObject(); animator.addProperty("type", "effect"); JsonArray keys = new JsonArray(); animator.add("keyframes", keys);
            for (String program : programs) {
                Molang.compile(program); JsonObject key = new JsonObject(); key.addProperty("channel", "timeline"); key.addProperty("time", 0);
                JsonObject point = new JsonObject(); point.addProperty("script", program); JsonArray points = new JsonArray(); points.add(point); key.add("data_points", points); keys.add(key);
            }
            clip.getAsJsonObject("animators").add("ysm_import_physics", animator);
        }
    }

    private record Spring(String prefix, String input, String frequency, String damping, String response) { }
    private static int matchingClose(String expression, int begin) {
        int depth = 1; char quote = 0;
        for (int i = begin; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; else if (c == '\\') i++; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        throw invalid("YSM second_order 缺少右括号");
    }
    private static List<String> arguments(String expression) {
        List<String> result = new ArrayList<>(); int begin = 0, depth = 0; char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; else if (c == '\\') i++; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) { result.add(expression.substring(begin, i).strip()); begin = i + 1; }
        }
        result.add(expression.substring(begin).strip()); return result;
    }
    private static String completeTernaries(String expression) {
        StringBuilder result = new StringBuilder(); Deque<Integer> pending = new ArrayDeque<>(); int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == ')' || c == ',' || c == ';') while (!pending.isEmpty() && pending.peek() == depth) { result.append(":0"); pending.pop(); }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '?') pending.push(depth);
            else if (c == ':' && !pending.isEmpty() && pending.peek() == depth) pending.pop();
            result.append(c);
        }
        while (!pending.isEmpty()) { result.append(":0"); pending.pop(); }
        return result.toString();
    }
    private static JsonObject object(JsonElement value) { if (value == null || !value.isJsonObject()) throw invalid("需要 JSON 对象"); return value.getAsJsonObject(); }
    private static JsonArray array(JsonElement value) { if (value == null || !value.isJsonArray()) throw invalid("需要 JSON 数组"); return value.getAsJsonArray(); }
    private static String string(JsonObject value, String key, String fallback) { if (!value.has(key)) return fallback; JsonElement item = value.get(key); if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw invalid("需要文本: " + key); return item.getAsString(); }
    private static double number(JsonObject value, String key, double fallback) { if (!value.has(key)) return fallback; double result = value.get(key).getAsDouble(); if (!Double.isFinite(result)) throw invalid("无效数值: " + key); return result; }
    private static double[] vector(JsonElement value, double fallback) { if (value == null) return new double[]{fallback, fallback, fallback}; JsonArray axes = array(value); if (axes.size() != 3) throw invalid("需要三维坐标"); double[] result = new double[3]; for (int i = 0; i < 3; i++) { result[i] = axes.get(i).getAsDouble(); if (!Double.isFinite(result[i])) throw invalid("坐标必须有限"); } return result; }
    private static double[] vector2(JsonElement value) { JsonArray axes = array(value); if (axes.size() != 2) throw invalid("需要二维 UV"); double[] result = {axes.get(0).getAsDouble(), axes.get(1).getAsDouble()}; for (double axis : result) if (!Double.isFinite(axis)) throw invalid("UV 必须有限"); return result; }
    private static JsonArray numericVector(JsonElement value, boolean invertX, boolean invertY, double fallback) { double[] axes = vector(value, fallback); return numbers(invertX ? -axes[0] : axes[0], invertY ? -axes[1] : axes[1], axes[2]); }
    private static JsonArray numbers(double... values) { JsonArray result = new JsonArray(); for (double value : values) result.add(value); return result; }
    private static JsonObject uv(double u, double v, double w, double h) { JsonObject result = new JsonObject(); result.add("uv", numbers(u, v)); result.add("uv_size", numbers(w, h)); return result; }
    private static String id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
