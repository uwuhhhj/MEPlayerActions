package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Immutable, bounded BBModel cube reader and frame sampler. Coordinates retain
 * Blockbench's editor axes; no Minecraft player pose rotation is applied here.
 * Model and animation units are converted to blocks exactly once during baking.
 * This implementation is independent of OpenYSM/GeckoLib.
 */
public final class BbModel {
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BONES = 2048, MAX_CUBES = 4096, MAX_TEXTURES = 16;
    private static final long MAX_TEXTURE_PIXELS = 16_777_216;
    private static final int MAX_ANIMATIONS = 128, MAX_KEYFRAMES = 200_000, MAX_DEPTH = 64;
    private static final float UNIT = 1 / 16f;
    private static final double DEG = Math.PI / 180;
    private final List<Texture> textures;
    private final List<Bone> bones;
    private final List<BakedFace> faces;
    private final Map<String, Clip> clips;
    private final int cubeCount;
    private final int[] headBones;

    public record Texture(int index, byte[] png) {
        public Texture { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
    }
    public record Vertex(float x, float y, float z, float u, float v,
                         float nx, float ny, float nz, int texture) { }
    public record Layer(String layer, String animation, long startedAtTick,
                        double speed, String loop, int inTicks, int outTicks) {
        public Layer {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(animation, "animation");
            Objects.requireNonNull(loop, "loop");
            if (layer.length() > 64 || animation.length() > 128 || !Double.isFinite(speed)
                    || speed < .01 || speed > 20 || inTicks < 0 || outTicks < 0
                    || inTicks > 200 || outTicks > 200) {
                throw new IllegalArgumentException("Invalid animation layer");
            }
            loop = loop.toUpperCase(Locale.ROOT);
            if (!Set.of("LOOP", "ONCE", "HOLD").contains(loop)) {
                throw new IllegalArgumentException("Unsupported loop: " + loop);
            }
        }
    }

    private record Bone(String id, String name, int parent, Vector3f pivot, Vector3f rotation, boolean visible) { }
    private record BakedFace(int bone, int texture, Vector3f[] corners, float[] uv, Vector3f normal) { }
    private record Key(double time, Vector3f pre, Vector3f post, String interpolation) { }
    private record Track(Key[] keys) {
        Vector3f sample(double time, boolean looping) {
            if (time <= keys[0].time) return new Vector3f(keys[0].pre);
            if (time > keys[keys.length - 1].time) return new Vector3f(keys[keys.length - 1].post);
            int lo = 0, hi = keys.length - 1;
            while (lo + 1 < hi) {
                int mid = (lo + hi) >>> 1;
                if (keys[mid].time < time) lo = mid; else hi = mid;
            }
            Key a = keys[lo], b = keys[hi];
            if (time == b.time) return new Vector3f(b.pre);
            if (a.interpolation.equals("step")) return new Vector3f(a.post);
            float t = (float) ((time - a.time) / (b.time - a.time));
            if (a.interpolation.equals("catmullrom") || b.interpolation.equals("catmullrom")) {
                Vector3f previous = a.pre.equals(a.post) && lo > 0 ? keys[lo - 1].post : a.post;
                Vector3f next = b.pre.equals(b.post) && hi + 1 < keys.length ? keys[hi + 1].pre : b.pre;
                if (looping && keys.length >= 3) {
                    if (lo == 0 && a.pre.equals(a.post)) previous = keys[keys.length - 2].post;
                    if (hi == keys.length - 1 && b.pre.equals(b.post)) next = keys[1].pre;
                }
                return catmull(previous, a.post, b.pre, next, t);
            }
            return new Vector3f(a.post).lerp(b.pre, t);
        }
    }
    private record Clip(double length, Map<Integer, Track[]> tracks) { }
    /** Nullable channel values mean this layer does not own that channel. */
    static final class Pose {
        final Vector3f[][] channels;
        final Vector3f[] look;
        Pose(int count) { channels = new Vector3f[count][3]; look = new Vector3f[count]; }
        Pose copy() {
            Pose copy = new Pose(channels.length);
            for (int i = 0; i < channels.length; i++) for (int c = 0; c < 3; c++)
                if (channels[i][c] != null) copy.channels[i][c] = new Vector3f(channels[i][c]);
            for (int i = 0; i < look.length; i++) if (look[i] != null) copy.look[i] = new Vector3f(look[i]);
            return copy;
        }
    }
    record Evaluated(Pose pose, double weight) { }

    private BbModel(List<Texture> textures, List<Bone> bones, List<BakedFace> faces,
                    Map<String, Clip> clips, int cubeCount) {
        this.textures = List.copyOf(textures);
        this.bones = List.copyOf(bones);
        this.faces = List.copyOf(faces);
        this.clips = Collections.unmodifiableMap(new LinkedHashMap<>(clips));
        this.cubeCount = cubeCount;
        headBones = findHeadBones(bones);
    }

    public static BbModel parse(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8");
        if (utf8.length == 0 || utf8.length > MAX_JSON_BYTES) throw invalid("Model byte limit exceeded");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException e) { throw invalid("Model is not UTF-8"); }
        checkJsonDepth(text);
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            return new Reader(root).read();
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new IllegalArgumentException("Invalid BBModel JSON", e);
        }
    }

    public List<Texture> textures() { return textures; }
    public Set<String> animations() { return clips.keySet(); }
    public int cubeCount() { return cubeCount; }
    public double animationLengthTicks(String animation) {
        Clip clip = clips.get(animation);
        if (clip == null) throw invalid("Unknown animation: " + animation);
        return clip.length * 20;
    }

    /** Stateless evaluation; use AnimationPlayer for continuity across layer changes. */
    public List<Vertex> sample(double serverTick, List<Layer> layers) {
        return sample(serverTick, layers, 0, 0);
    }

    /** Adds the player's look only to designated head bones, after authored poses. */
    public List<Vertex> sample(double serverTick, List<Layer> layers, float relativeHeadYaw, float headPitch) {
        checkedTick(serverTick);
        Pose pose = new Pose(bones.size());
        double[] headWeights = defaultHeadWeights();
        for (Layer layer : ordered(layers)) {
            Evaluated result = evaluate(serverTick, layer, true);
            overlay(pose, result.pose, result.weight, additive(layer.layer));
            if (layer.layer.equalsIgnoreCase("manual")) suppressManualLook(headWeights, result.pose, result.weight);
        }
        applyLook(pose, relativeHeadYaw, headPitch, headWeights);
        return vertices(pose);
    }

    static void checkedTick(double serverTick) {
        if (!Double.isFinite(serverTick) || Math.abs(serverTick) > 1e14) throw invalid("Invalid render tick");
    }
    static List<Layer> ordered(List<Layer> layers) {
        Objects.requireNonNull(layers, "layers");
        if (layers.size() > 32) throw invalid("Too many animation layers");
        Set<String> names = new HashSet<>();
        List<Layer> copy = new ArrayList<>(layers);
        for (Layer layer : copy) if (layer == null || !names.add(layer.layer)) throw invalid("Duplicate/null layer");
        copy.sort(Comparator.comparingInt(layer -> priority(layer.layer)));
        return copy;
    }
    static int priority(String layer) {
        return switch (layer.toLowerCase(Locale.ROOT)) {
            case "environment", "ambient", "blink", "ribbon", "tail" -> 0;
            case "posture", "movement", "main" -> 10;
            case "interaction", "arms", "swing", "use" -> 20;
            case "manual" -> 30;
            default -> 5;
        };
    }
    static boolean additive(String layer) {
        return Set.of("interaction", "arms", "swing", "use").contains(layer.toLowerCase(Locale.ROOT));
    }
    Pose emptyPose() { return new Pose(bones.size()); }
    double[] defaultHeadWeights() {
        double[] weights = new double[bones.size()]; Arrays.fill(weights, 1); return weights;
    }
    void suppressManualLook(double[] weights, Pose manual, double influence) {
        for (int head : headBones) if (manual.channels[head][1] != null) weights[head] *= 1 - clamp(influence);
    }
    void applyLook(Pose pose, float relativeHeadYaw, float headPitch, double[] weights) {
        if (!Float.isFinite(relativeHeadYaw) || !Float.isFinite(headPitch)) throw invalid("Invalid head angle");
        float yaw = (relativeHeadYaw % 360 + 540) % 360 - 180;
        yaw = Math.max(-85, Math.min(85, yaw));
        float pitch = Math.max(-90, Math.min(90, headPitch));
        for (int head : headBones) {
            // Postmultiply the delta after the authored rotation. Adding to
            // Euler Y before the crawl head's -90 X compensation rotates the
            // wrong axis and can leave the nose pointing straight ahead.
            pose.look[head] = new Vector3f((float) (-pitch * weights[head]), (float) (-yaw * weights[head]), 0);
        }
    }
    private static int[] findHeadBones(List<Bone> bones) {
        boolean prefixed = bones.stream().anyMatch(bone -> headPrefix(bone.name));
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < bones.size(); i++) {
            String name = bones.get(i).name;
            boolean candidate = prefixed ? headPrefix(name) : name.equalsIgnoreCase("head");
            if (!candidate) continue;
            int parent = bones.get(i).parent;
            boolean inherited = false;
            while (parent >= 0) {
                if (result.contains(parent)) { inherited = true; break; }
                parent = bones.get(parent).parent;
            }
            if (!inherited) result.add(i);
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }
    private static boolean headPrefix(String name) {
        name = name.toLowerCase(Locale.ROOT);
        return name.startsWith("h_") || name.startsWith("hi_");
    }

    Evaluated evaluate(double serverTick, Layer layer, boolean fadeIn) {
        Clip clip = clips.get(layer.animation);
        if (clip == null) throw invalid("Unknown animation: " + layer.animation);
        Pose pose = emptyPose();
        double elapsed = serverTick - layer.startedAtTick;
        if (elapsed < 0) return new Evaluated(pose, 0);
        double duration = clip.length * 20 / layer.speed;
        double weight = fadeIn && layer.inTicks > 0 ? clamp(elapsed / layer.inTicks) : 1;
        if (layer.loop.equals("ONCE") && elapsed > duration) {
            weight *= layer.outTicks == 0 ? 0 : clamp(1 - (elapsed - duration) / layer.outTicks);
        }
        if (weight <= 0) return new Evaluated(pose, 0);
        double seconds = elapsed * layer.speed / 20;
        boolean looping = layer.loop.equals("LOOP") && clip.length > 0;
        seconds = looping ? seconds % clip.length : (seconds > clip.length ? Math.nextUp(clip.length) : seconds);
        for (var entry : clip.tracks.entrySet()) for (int c = 0; c < 3; c++) {
            Track track = entry.getValue()[c];
            if (track != null) pose.channels[entry.getKey()][c] = track.sample(seconds, looping);
        }
        return new Evaluated(pose, weight);
    }

    static void overlay(Pose below, Pose above, double weight, boolean additive) {
        if (weight <= 0) return;
        float t = (float) clamp(weight);
        for (int i = 0; i < below.channels.length; i++) for (int c = 0; c < 3; c++) {
            Vector3f top = above.channels[i][c];
            if (top == null) continue;
            Vector3f base = below.channels[i][c];
            if (base == null) base = defaultValue(c);
            below.channels[i][c] = combine(base, top, c, t, additive);
        }
    }
    static Vector3f combine(Vector3f base, Vector3f top, int channel, float weight, boolean additive) {
        if (!additive) return new Vector3f(base).lerp(top, weight);
        // Interaction clips are deltas over the authored posture. A zero
        // attack root rotation must preserve the crawl root's 90-degree turn.
        return channel == 2 ? new Vector3f(base).mul(new Vector3f(1).lerp(top, weight))
                : new Vector3f(base).add(new Vector3f(top).mul(weight));
    }
    static Vector3f defaultValue(int channel) { return channel == 2 ? new Vector3f(1) : new Vector3f(); }
    static double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    List<Vertex> vertices(Pose pose) {
        Matrix4f[] transforms = new Matrix4f[bones.size()];
        Matrix3f[] normals = new Matrix3f[bones.size()];
        boolean[] visible = new boolean[bones.size()];
        for (int i = 0; i < bones.size(); i++) {
            Bone bone = bones.get(i);
            Vector3f parentPivot = bone.parent >= 0 ? bones.get(bone.parent).pivot : new Vector3f();
            Vector3f position = pose.channels[i][0], rotation = pose.channels[i][1], scale = pose.channels[i][2];
            if (position == null) position = new Vector3f();
            if (rotation == null) rotation = new Vector3f();
            if (scale == null) scale = new Vector3f(1);
            Matrix4f transform = bone.parent >= 0 ? new Matrix4f(transforms[bone.parent]) : new Matrix4f();
            transform.translate((bone.pivot.x - parentPivot.x + position.x) * UNIT,
                    (bone.pivot.y - parentPivot.y + position.y) * UNIT,
                    (bone.pivot.z - parentPivot.z + position.z) * UNIT);
            transform.rotateZ((float) ((bone.rotation.z + rotation.z) * DEG))
                    .rotateY((float) ((bone.rotation.y + rotation.y) * DEG))
                    .rotateX((float) ((bone.rotation.x + rotation.x) * DEG));
            Vector3f look = pose.look[i];
            if (look != null) transform.rotateY((float) (look.y * DEG)).rotateX((float) (look.x * DEG));
            transform.scale(scale);
            transforms[i] = transform;
            float determinant = transform.determinant3x3();
            if (!Float.isFinite(determinant)) throw invalid("Sampled bone transform exceeded limits");
            visible[i] = bone.visible && (bone.parent < 0 || visible[bone.parent])
                    && Math.abs(determinant) > 1e-12;
            if (visible[i]) normals[i] = transform.normal(new Matrix3f());
        }
        List<Vertex> result = new ArrayList<>(faces.size() * 4);
        Vector3f point = new Vector3f(), normal = new Vector3f();
        for (BakedFace face : faces) {
            if (!visible[face.bone]) continue;
            Matrix4f transform = transforms[face.bone];
            normals[face.bone].transform(face.normal, normal).normalize();
            if (!normal.isFinite()) throw invalid("Non-finite sampled normal");
            boolean mirrored = transform.determinant3x3() < 0;
            for (int v = 0; v < 4; v++) {
                int index = mirrored ? 3 - v : v;
                transform.transformPosition(face.corners[index], point);
                if (!point.isFinite() || point.lengthSquared() > 1e12f) throw invalid("Sampled model bounds exceeded");
                result.add(new Vertex(point.x, point.y, point.z, face.uv[index * 2], face.uv[index * 2 + 1],
                        normal.x, normal.y, normal.z, face.texture));
            }
        }
        return List.copyOf(result);
    }

    private static Vector3f catmull(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return new Vector3f(
                curve(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
                curve(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
                curve(p0.z, p1.z, p2.z, p3.z, t, t2, t3));
    }
    private static float curve(float a, float b, float c, float d, float t, float t2, float t3) {
        return .5f * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2
                + (-a + 3 * b - 3 * c + d) * t3);
    }

    private static final class Reader {
        final JsonObject root;
        final boolean legacyAnimationAxes;
        final List<Texture> textures = new ArrayList<>();
        final List<Bone> bones = new ArrayList<>();
        final List<BakedFace> faces = new ArrayList<>();
        final Map<String, JsonObject> cubes = new LinkedHashMap<>();
        final Map<String, Integer> boneIds = new HashMap<>();
        final Set<String> ids = new HashSet<>(), assigned = new HashSet<>();
        final Map<String, Clip> clips = new LinkedHashMap<>();
        float[] uvWidth, uvHeight;
        int keys;
        Reader(JsonObject root) {
            this.root = root;
            JsonObject meta = object(root.get("meta"));
            String version = string(meta, "format_version", "");
            if (!version.matches("[345]\\.(0|[1-9][0-9]{0,2})")) throw invalid("Unsupported BBModel format version: " + version);
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]);
            if (major == 3 && minor < 2 || major == 5 && minor > 0)
                throw invalid("Unsupported BBModel format version: " + version);
            // Blockbench's 5.0 project codec explicitly migrates pre-5.0
            // keyframes: position X and rotation X/Y change sign. Geometry
            // pivots and rest/cube rotations do not use this migration.
            legacyAnimationAxes = major < 5;
            if (root.has("groups") && !array(root, "groups", true).isEmpty())
                throw invalid("Separate group tables are unsupported; export an inline outliner BBModel");
        }
        BbModel read() {
            // Editor-only slider placeholders are inert metadata; only numeric
            // runtime keyframes are accepted below, so none are evaluated.
            readTextures();
            JsonArray elements = array(root, "elements", true);
            if (elements.size() > MAX_CUBES) throw invalid("Cube limit exceeded");
            for (JsonElement element : elements) {
                JsonObject cube = object(element);
                if (!string(cube, "type", "cube").equals("cube")) throw invalid("Only cube geometry is supported");
                String uuid = id(cube);
                if (cubes.put(uuid, cube) != null) throw invalid("Duplicate cube UUID");
                if (bool(cube, "rescale", false)) throw invalid("Cube rescale is unsupported");
                if (bool(cube, "box_uv", false)) throw invalid("Box UV must be converted to face UV");
            }
            // Identity root lets ungrouped cubes use the same matrix path as grouped cubes.
            bones.add(new Bone("", "", -1, new Vector3f(), new Vector3f(), true));
            for (JsonElement node : array(root, "outliner", false)) readNode(node, 0, 1);
            for (var entry : cubes.entrySet()) if (!assigned.contains(entry.getKey())) bakeCube(entry.getValue(), 0);
            readAnimations();
            if (faces.isEmpty()) throw invalid("Model has no textured cube faces");
            return new BbModel(textures, bones, faces, clips, cubes.size());
        }
        void readTextures() {
            JsonArray items = array(root, "textures", true);
            if (items.isEmpty() || items.size() > MAX_TEXTURES) throw invalid("Texture count outside limits");
            uvWidth = new float[items.size()]; uvHeight = new float[items.size()];
            JsonObject resolution = root.has("resolution") ? object(root.get("resolution")) : new JsonObject();
            float defaultW = (float) number(resolution, "width", 16, 1, 8192);
            float defaultH = (float) number(resolution, "height", 16, 1, 8192);
            long pixels = 0;
            for (int i = 0; i < items.size(); i++) {
                JsonObject texture = object(items.get(i));
                String source = string(texture, "source", "");
                if (!source.startsWith("data:image/png;base64,") || source.length() > 8 * 1024 * 1024)
                    throw invalid("Textures must contain bounded embedded PNGs; external paths are disabled");
                if (bool(texture, "layers_enabled", false)) throw invalid("Texture layers are unsupported");
                byte[] png;
                try { png = Base64.getDecoder().decode(source.substring(22)); }
                catch (IllegalArgumentException e) { throw invalid("Invalid embedded PNG base64"); }
                pixels += validatePng(png, MAX_TEXTURE_PIXELS - pixels);
                textures.add(new Texture(i, png));
                uvWidth[i] = (float) number(texture, "uv_width", defaultW, 1, 8192);
                uvHeight[i] = (float) number(texture, "uv_height", defaultH, 1, 8192);
            }
        }
        String id(JsonObject node) {
            String uuid = string(node, "uuid", "");
            if (uuid.isEmpty() || uuid.length() > 128 || !ids.add(uuid)) throw invalid("Missing/duplicate model UUID");
            return uuid;
        }
        void readNode(JsonElement element, int parent, int depth) {
            if (depth > MAX_DEPTH) throw invalid("Bone nesting limit exceeded");
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String uuid = element.getAsString();
                JsonObject cube = cubes.get(uuid);
                if (cube == null || !assigned.add(uuid)) throw invalid("Unknown/duplicate outliner cube: " + uuid);
                bakeCube(cube, parent);
                return;
            }
            JsonObject node = object(element);
            if (!string(node, "type", "group").equals("group")) throw invalid("Unsupported outliner node");
            if (bones.size() >= MAX_BONES) throw invalid("Bone count limit exceeded");
            String uuid = id(node);
            int index = bones.size();
            boneIds.put(uuid, index);
            String name = string(node, "name", "");
            if (name.length() > 128) throw invalid("Bone name exceeds limits");
            bones.add(new Bone(uuid, name, parent, vector(node, "origin", new Vector3f(), 4096),
                    vector(node, "rotation", new Vector3f(), 36_000),
                    bool(node, "visibility", true) && bool(node, "export", true)));
            if (!string(node, "bedrock_binding", "").isBlank()) throw invalid("Bone binding expressions are unsupported");
            for (JsonElement child : array(node, "children", false)) readNode(child, index, depth + 1);
        }
        void bakeCube(JsonObject cube, int boneIndex) {
            Vector3f from = vector(cube, "from", null, 4096), to = vector(cube, "to", null, 4096);
            if (from.x > to.x || from.y > to.y || from.z > to.z) throw invalid("Inverted cube bounds");
            Vector3f origin = vector(cube, "origin", new Vector3f(), 4096);
            Vector3f rotation = vector(cube, "rotation", new Vector3f(), 36_000);
            float inflate = (float) number(cube, "inflate", 0, -1024, 1024);
            from.sub(inflate, inflate, inflate); to.add(inflate, inflate, inflate);
            if (from.x > to.x || from.y > to.y || from.z > to.z) throw invalid("Inflate inverted cube bounds");
            if (!bool(cube, "visibility", true) || !bool(cube, "export", true)) return;
            Vector3f pivot = bones.get(boneIndex).pivot;
            Matrix4f cubeTransform = new Matrix4f().translation(new Vector3f(origin).sub(pivot).mul(UNIT))
                    .rotateZ((float) (rotation.z * DEG)).rotateY((float) (rotation.y * DEG)).rotateX((float) (rotation.x * DEG))
                    .translate(new Vector3f(origin).negate().mul(UNIT));
            Matrix3f normalTransform = cubeTransform.normal(new Matrix3f());
            JsonObject faceObjects = object(cube.get("faces"));
            for (var entry : faceObjects.entrySet()) {
                String side = entry.getKey();
                JsonObject face = object(entry.getValue());
                JsonElement textureElement = face.get("texture");
                if (textureElement == null || textureElement.isJsonNull()) continue;
                int texture = textureIndex(textureElement);
                if (texture < 0) continue;
                float[][] corners = corners(side, from, to);
                Vector3f normal = normal(side);
                normalTransform.transform(normal).normalize();
                Vector3f[] positions = new Vector3f[4];
                for (int i = 0; i < 4; i++) positions[i] = cubeTransform.transformPosition(new Vector3f(corners[i]).mul(UNIT));
                JsonArray uv = array(face, "uv", true);
                if (uv.size() != 4) throw invalid("A face UV must contain four coordinates");
                float u0 = (float) finite(uv.get(0), -65_536, 65_536) / uvWidth[texture];
                float v0 = (float) finite(uv.get(1), -65_536, 65_536) / uvHeight[texture];
                float u1 = (float) finite(uv.get(2), -65_536, 65_536) / uvWidth[texture];
                float v1 = (float) finite(uv.get(3), -65_536, 65_536) / uvHeight[texture];
                int angle = integer(face, "rotation", 0, -360, 360);
                if (angle % 90 != 0) throw invalid("Face UV rotation must be a multiple of 90");
                float[] base = {u0, v0, u0, v1, u1, v1, u1, v0}, rotated = new float[8];
                int shift = Math.floorMod(angle / 90, 4);
                for (int i = 0; i < 4; i++) {
                    int j = (i + shift) % 4;
                    rotated[i * 2] = base[j * 2]; rotated[i * 2 + 1] = base[j * 2 + 1];
                }
                faces.add(new BakedFace(boneIndex, texture, positions, rotated, normal));
            }
        }
        int textureIndex(JsonElement value) {
            if (!value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) throw invalid("Invalid texture index");
            String number = value.getAsString();
            if (number.startsWith("#")) number = number.substring(1);
            try {
                int index = Integer.parseInt(number);
                if (index < -1 || index >= textures.size()) throw invalid("Unknown face texture");
                return index;
            } catch (NumberFormatException e) { throw invalid("Invalid texture index"); }
        }
        void readAnimations() {
            JsonArray animations = array(root, "animations", false);
            if (animations.size() > MAX_ANIMATIONS) throw invalid("Animation count limit exceeded");
            for (JsonElement value : animations) {
                JsonObject animation = object(value);
                String name = string(animation, "name", "");
                if (name.isBlank() || name.length() > 128 || clips.containsKey(name)) throw invalid("Missing/duplicate animation name");
                rejectScript(animation, "anim_time_update", "blend_weight", "start_delay", "loop_delay");
                double length = number(animation, "length", 0, 0, 3600);
                Map<Integer, Track[]> tracks = new LinkedHashMap<>();
                JsonObject animators = animation.has("animators") ? object(animation.get("animators")) : new JsonObject();
                if (animators.size() > MAX_BONES) throw invalid("Animator count limit exceeded");
                for (var entry : animators.entrySet()) {
                    JsonObject animator = object(entry.getValue());
                    if (!string(animator, "type", "bone").equals("bone")) throw invalid("Effect/script animators are unsupported");
                    if (bool(animator, "rotation_global", false) || bool(animator, "quaternion_interpolation", false))
                        throw invalid("Global/quaternion animator rotations are unsupported");
                    Integer index = boneIds.get(entry.getKey());
                    if (index == null) throw invalid("Animator references unknown bone");
                    @SuppressWarnings("unchecked") List<Key>[] channels = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
                    for (JsonElement keyValue : array(animator, "keyframes", false)) {
                        if (++keys > MAX_KEYFRAMES) throw invalid("Keyframe limit exceeded");
                        JsonObject frame = object(keyValue);
                        int channel = switch (string(frame, "channel", "")) {
                            case "position" -> 0; case "rotation" -> 1; case "scale" -> 2;
                            default -> throw invalid("Effect/script keyframe channels are unsupported");
                        };
                        double time = number(frame, "time", 0, 0, length + 1e-5);
                        String interpolation = string(frame, "interpolation", "linear");
                        if (!Set.of("linear", "step", "catmullrom").contains(interpolation))
                            throw invalid("Unsupported keyframe interpolation: " + interpolation);
                        JsonArray points = array(frame, "data_points", true);
                        if (points.isEmpty() || points.size() > 2) throw invalid("Unsupported keyframe data point count");
                        Vector3f pre = point(object(points.get(0)), channel);
                        Vector3f post = points.size() == 2 ? point(object(points.get(1)), channel) : pre;
                        channels[channel].add(new Key(time, pre, post, interpolation));
                    }
                    Track[] boneTracks = new Track[3];
                    for (int c = 0; c < 3; c++) if (!channels[c].isEmpty()) {
                        channels[c].sort(Comparator.comparingDouble(Key::time));
                        for (int k = 1; k < channels[c].size(); k++)
                            if (channels[c].get(k - 1).time == channels[c].get(k).time) throw invalid("Duplicate channel keyframe time");
                        boneTracks[c] = new Track(channels[c].toArray(Key[]::new));
                    }
                    tracks.put(index, boneTracks);
                }
                clips.put(name, new Clip(length, Map.copyOf(tracks)));
            }
        }
        Vector3f point(JsonObject point, int channel) {
            if (point.has("script") || point.has("effect") || point.has("file")) throw invalid("Script/external keyframe data is unsupported");
            float fallback = channel == 2 ? 1 : 0;
            double bound = channel == 2 ? 64 : channel == 1 ? 36_000 : 4096;
            Vector3f value = new Vector3f((float) number(point, "x", fallback, -bound, bound),
                    (float) number(point, "y", fallback, -bound, bound),
                    (float) number(point, "z", fallback, -bound, bound));
            if (legacyAnimationAxes) {
                if (channel == 0 || channel == 1) value.x = -value.x;
                if (channel == 1) value.y = -value.y;
            }
            return value;
        }
    }

    private static float[][] corners(String side, Vector3f a, Vector3f b) {
        float x0 = a.x, y0 = a.y, z0 = a.z, x1 = b.x, y1 = b.y, z1 = b.z;
        return switch (side) {
            case "north" -> new float[][]{{x1,y1,z0},{x1,y0,z0},{x0,y0,z0},{x0,y1,z0}};
            case "east" -> new float[][]{{x1,y1,z1},{x1,y0,z1},{x1,y0,z0},{x1,y1,z0}};
            case "south" -> new float[][]{{x0,y1,z1},{x0,y0,z1},{x1,y0,z1},{x1,y1,z1}};
            case "west" -> new float[][]{{x0,y1,z0},{x0,y0,z0},{x0,y0,z1},{x0,y1,z1}};
            case "up" -> new float[][]{{x0,y1,z0},{x0,y1,z1},{x1,y1,z1},{x1,y1,z0}};
            case "down" -> new float[][]{{x0,y0,z1},{x0,y0,z0},{x1,y0,z0},{x1,y0,z1}};
            default -> throw invalid("Unknown cube face: " + side);
        };
    }
    private static Vector3f normal(String side) {
        return switch (side) {
            case "north" -> new Vector3f(0,0,-1); case "east" -> new Vector3f(1,0,0);
            case "south" -> new Vector3f(0,0,1); case "west" -> new Vector3f(-1,0,0);
            case "up" -> new Vector3f(0,1,0); case "down" -> new Vector3f(0,-1,0);
            default -> throw invalid("Unknown cube face");
        };
    }
    private static JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) throw invalid("Expected object");
        return element.getAsJsonObject();
    }
    private static JsonArray array(JsonObject object, String name, boolean required) {
        JsonElement element = object.get(name);
        if (element == null && !required) return new JsonArray();
        if (element == null || !element.isJsonArray()) throw invalid("Expected array: " + name);
        return element.getAsJsonArray();
    }
    private static String string(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("Expected string: " + name);
        return value.getAsString();
    }
    private static boolean bool(JsonObject object, String name, boolean fallback) {
        JsonElement value = object.get(name);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid("Expected boolean: " + name);
        return value.getAsBoolean();
    }
    private static double number(JsonObject object, String name, double fallback, double min, double max) {
        JsonElement value = object.get(name);
        return value == null ? fallback : finite(value, min, max);
    }
    private static int integer(JsonObject object, String name, int fallback, int min, int max) {
        double value = number(object, name, fallback, min, max);
        if (value != Math.rint(value)) throw invalid("Expected integer: " + name);
        return (int) value;
    }
    private static double finite(JsonElement value, double min, double max) {
        if (!value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) throw invalid("Expected numeric literal");
        try {
            String text = value.getAsString();
            if (text.length() > 64) throw invalid("Numeric literal too long");
            double number = Double.parseDouble(text);
            if (!Double.isFinite(number) || number < min || number > max) throw invalid("Numeric value outside limits");
            return number;
        } catch (NumberFormatException e) { throw invalid("Expressions are unsupported; bake numeric keyframes first"); }
    }
    private static Vector3f vector(JsonObject object, String name, Vector3f fallback, double bound) {
        if (!object.has(name) && fallback != null) return new Vector3f(fallback);
        JsonArray array = array(object, name, true);
        if (array.size() != 3) throw invalid("Expected three-vector: " + name);
        return new Vector3f((float) finite(array.get(0), -bound, bound),
                (float) finite(array.get(1), -bound, bound), (float) finite(array.get(2), -bound, bound));
    }
    private static void rejectScript(JsonObject object, String... fields) {
        for (String field : fields) if (object.has(field) && !string(object, field, "").isBlank())
            throw invalid("Unsupported script/expression field: " + field);
    }
    private static void checkJsonDepth(String text) {
        int depth = 0; boolean string = false, escape = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (string) {
                if (escape) escape = false; else if (ch == '\\') escape = true; else if (ch == '"') string = false;
            } else if (ch == '"') string = true;
            else if (ch == '{' || ch == '[') { if (++depth > 144) throw invalid("JSON nesting limit exceeded"); }
            else if (ch == '}' || ch == ']') { if (--depth < 0) throw invalid("Unbalanced JSON"); }
        }
        if (depth != 0 || string) throw invalid("Unbalanced JSON");
    }
    private static long validatePng(byte[] png, long remainingPixels) {
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (png.length < 33 || png.length > 6 * 1024 * 1024
                || !Arrays.equals(Arrays.copyOf(png, 8), signature)) throw invalid("Invalid PNG signature/size");
        validatePngChunks(png);
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid("Cannot decode embedded PNG");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || pixels > remainingPixels)
                    throw invalid("PNG dimensions exceed limits");
                if (reader.read(0) == null) throw invalid("Cannot decode embedded PNG");
                return pixels;
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid embedded PNG", e);
        }
    }
    private static void validatePngChunks(byte[] png) {
        ByteBuffer buffer = ByteBuffer.wrap(png);
        buffer.position(8);
        boolean ihdr = false, idat = false, iend = false;
        int chunks = 0;
        while (buffer.remaining() >= 12) {
            if (++chunks > 8192) throw invalid("PNG chunk count exceeded");
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining() - 8) throw invalid("Truncated PNG chunk");
            int start = buffer.position();
            int type = buffer.getInt();
            if (!ihdr && (type != 0x49484452 || length != 13)) throw invalid("PNG must begin with IHDR");
            if (type == 0x49484452) { if (ihdr) throw invalid("Duplicate PNG IHDR"); ihdr = true; }
            if (type == 0x6163544c) throw invalid("Animated PNGs are unsupported");
            if (type == 0x49444154) idat = true;
            buffer.position(buffer.position() + length);
            long expected = Integer.toUnsignedLong(buffer.getInt());
            CRC32 crc = new CRC32(); crc.update(png, start, length + 4);
            if (crc.getValue() != expected) throw invalid("Invalid PNG chunk checksum");
            if (type == 0x49454e44) {
                if (length != 0 || buffer.hasRemaining()) throw invalid("Invalid PNG IEND");
                iend = true; break;
            }
        }
        if (!ihdr || !idat || !iend) throw invalid("Incomplete PNG");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
