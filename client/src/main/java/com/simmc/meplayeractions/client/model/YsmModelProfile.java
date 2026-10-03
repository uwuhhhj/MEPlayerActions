package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simmc.meplayeractions.client.network.AssetTransfer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, bounded local YSM metadata. It grants no server/network asset authority. */
public final class YsmModelProfile {
    private static final YsmModelProfile EMPTY = new YsmModelProfile(new JsonObject(), new JsonObject(),
            new JsonObject(), Map.of(), List.of(), List.of(), "", "");
    private final JsonObject manifest, languages, controllers, events;
    private final Map<String, String> functions;
    private final Map<String, String> animationFiles, extraAnimations;
    private final List<TextureChoice> textures;
    private final List<Component> components;
    private final String defaultTexture, selectedTexture;
    private final Map<String, byte[]> pngResources, soundResources;

    public static YsmModelProfile empty() { return EMPTY; }

    YsmModelProfile(JsonObject manifest, JsonObject languages, JsonObject controllers,
                    Map<String, String> animationFiles, List<TextureChoice> textures,
                    List<Component> components, String defaultTexture, String selectedTexture) {
        this(manifest, languages, controllers, animationFiles, textures, components, defaultTexture, selectedTexture, Map.of());
    }

    YsmModelProfile(JsonObject manifest, JsonObject languages, JsonObject controllers,
                    Map<String, String> animationFiles, List<TextureChoice> textures,
                    List<Component> components, String defaultTexture, String selectedTexture,
                    Map<String, byte[]> pngResources) {
        this(manifest, languages, controllers, animationFiles, textures, components, defaultTexture, selectedTexture,
                pngResources, Map.of());
    }

    YsmModelProfile(JsonObject manifest, JsonObject languages, JsonObject controllers,
                    Map<String, String> animationFiles, List<TextureChoice> textures,
                    List<Component> components, String defaultTexture, String selectedTexture,
                    Map<String, byte[]> pngResources, Map<String, byte[]> soundResources) {
        this(manifest, languages, controllers, animationFiles, textures, components, defaultTexture, selectedTexture,
                pngResources, soundResources, new JsonObject(), new JsonObject());
    }

    YsmModelProfile(JsonObject manifest, JsonObject languages, JsonObject controllers,
                    Map<String, String> animationFiles, List<TextureChoice> textures,
                    List<Component> components, String defaultTexture, String selectedTexture,
                    Map<String, byte[]> pngResources, Map<String, byte[]> soundResources,
                    JsonObject functions, JsonObject events) {
        checkJson(manifest); checkJson(languages); checkJson(controllers);
        checkJson(functions); checkJson(events);
        if (functions.size() > 64 || events.size() > 64) throw new IllegalArgumentException("YSM function/event count");
        if (textures.size() > 16 || components.size() > 34 || animationFiles.size() > 32 || soundResources.size() > 32)
            throw new IllegalArgumentException("YSM metadata count");
        this.manifest = manifest.deepCopy(); this.languages = languages.deepCopy();
        this.controllers = controllers.deepCopy();
        Map<String, String> callable = new LinkedHashMap<>();
        functions.entrySet().forEach(entry -> {
            String name = entry.getKey(); JsonElement script = entry.getValue();
            if (name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)
                    || !script.isJsonPrimitive() || !script.getAsJsonPrimitive().isString() || script.getAsString().length() > 32_768)
                throw new IllegalArgumentException("YSM function name/script");
            callable.put(name, script.getAsString());
        });
        for (var event : events.entrySet()) {
            String name = event.getKey(); JsonElement scripts = event.getValue();
            if (name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)
                    || !scripts.isJsonArray() || scripts.getAsJsonArray().size() > 32)
                throw new IllegalArgumentException("YSM event name/script count");
            for (JsonElement script : scripts.getAsJsonArray())
                if (!script.isJsonPrimitive() || !script.getAsJsonPrimitive().isString() || script.getAsString().length() > 32_768)
                    throw new IllegalArgumentException("YSM event script");
        }
        this.events = events.deepCopy();
        this.functions = Collections.unmodifiableMap(callable);
        this.animationFiles = Collections.unmodifiableMap(new LinkedHashMap<>(animationFiles));
        this.textures = List.copyOf(textures); this.components = List.copyOf(components);
        this.defaultTexture = Objects.requireNonNull(defaultTexture); this.selectedTexture = Objects.requireNonNull(selectedTexture);
        Map<String, byte[]> resources = new LinkedHashMap<>();
        int bytes = 0;
        for (var entry : pngResources.entrySet()) {
            if ((bytes += entry.getValue().length) > AssetTransfer.MAX_RAW) throw new IllegalArgumentException("YSM resource budget");
            resources.put(entry.getKey(), entry.getValue().clone());
        }
        this.pngResources = Collections.unmodifiableMap(resources);
        Map<String, byte[]> sounds = new LinkedHashMap<>();
        for (var entry : soundResources.entrySet()) {
            String name = entry.getKey(); byte[] sound = entry.getValue();
            if (name.length() > 128 || name.contains("/") || !YsmFolderModel.safeRelativePath(name + ".ogg")
                    || sound.length == 0 || (bytes += sound.length) > AssetTransfer.MAX_RAW)
                throw new IllegalArgumentException("YSM sound resource budget/name");
            sounds.put(name, sound.clone());
        }
        this.soundResources = Collections.unmodifiableMap(sounds);
        Map<String, String> extras = new LinkedHashMap<>();
        JsonObject properties = properties();
        if (properties.has("extra_animation")) {
            JsonObject entries = properties.getAsJsonObject("extra_animation");
            if (entries.size() > 128) throw new IllegalArgumentException("YSM extra action count");
            entries.entrySet().forEach(entry -> {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("YSM extra action label");
                extras.put(entry.getKey(), entry.getValue().getAsString());
            });
        }
        extraAnimations = Collections.unmodifiableMap(extras);
        checkForms(properties);
    }

    public boolean isYsm() { return manifest.has("spec"); }
    public JsonObject sourceManifest() { return manifest.deepCopy(); }
    public JsonObject metadata() { return objectOrEmpty(manifest, "metadata"); }
    public JsonObject properties() { return objectOrEmpty(manifest, "properties"); }
    public JsonObject languages() { return languages.deepCopy(); }
    /** Original controller definitions, merged in file/author order, without the outer key. */
    public JsonObject animationControllers() { return controllers.deepCopy(); }
    /** Authored filename prefix before @ is the callable fn name; event-only filenames are excluded. */
    public Map<String, String> functions() { return functions; }
    public JsonObject events() { return events.deepCopy(); }
    public Map<String, String> animationFiles() { return animationFiles; }
    public Map<String, String> extraAnimations() { return extraAnimations; }
    public JsonArray extraAnimationButtons() { return arrayOrEmpty(properties(), "extra_animation_buttons"); }
    public JsonArray extraAnimationClassify() { return arrayOrEmpty(properties(), "extra_animation_classify"); }
    public List<TextureChoice> textures() { return textures; }
    public List<Component> components() { return components; }
    public String defaultTexture() { return defaultTexture; }
    public String selectedTexture() { return selectedTexture; }
    /** Validated local PNG lookup; no path is resolved and no external resource is fetched here. */
    public java.util.Optional<byte[]> resourcePng(String path) {
        byte[] png = pngResources.get(path); return png == null ? java.util.Optional.empty() : java.util.Optional.of(png.clone());
    }
    /** Already bounded local OGG bytes, keyed by author filename stem; the audio consumer validates the container. */
    public java.util.Optional<byte[]> soundResource(String name) {
        byte[] sound = soundResources.get(name);
        return sound == null ? java.util.Optional.empty() : java.util.Optional.of(sound.clone());
    }
    public Map<String, byte[]> soundResources() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        soundResources.forEach((name, sound) -> result.put(name, sound.clone()));
        return Collections.unmodifiableMap(result);
    }
    public String previewAnimation() { return text(properties(), "preview_animation", ""); }
    public String localized(String locale, String key, String fallback) {
        String requested = locale == null ? "en_us" : locale.toLowerCase(java.util.Locale.ROOT);
        for (String candidate : List.of(requested, "en_us")) {
            JsonObject strings = objectOrEmpty(languages, candidate);
            if (strings.has(key) && strings.get(key).isJsonPrimitive() && strings.get(key).getAsJsonPrimitive().isString())
                return strings.get(key).getAsString();
        }
        return fallback;
    }

    public static final class TextureChoice {
        private final String id, path, label;
        private final byte[] png;
        private final JsonObject material;
        TextureChoice(String id, String path, byte[] png, JsonObject material) {
            this.id = Objects.requireNonNull(id); this.path = Objects.requireNonNull(path); this.label = id;
            if (png.length == 0 || png.length > AssetTransfer.MAX_RAW) throw new IllegalArgumentException("YSM texture bytes");
            checkJson(material); this.png = png.clone(); this.material = material.deepCopy();
        }
        public String id() { return id; }
        public String path() { return path; }
        public String label() { return label; }
        public byte[] png() { return png.clone(); }
        /** Includes uv/emission/etc paths as authored; only the validated base PNG is rendered here. */
        public JsonObject material() { return material.deepCopy(); }
    }

    public static final class Component {
        private final String id, kind, hash, selectedTexture;
        private final List<String> matches;
        private final List<TextureChoice> textures;
        private final byte[] raw;
        private final BbModel model;
        private final JsonObject metadata;
        Component(String id, String kind, List<String> matches, List<TextureChoice> textures,
                  String selectedTexture, byte[] raw, JsonObject metadata) {
            if (matches.size() > 32 || textures.size() > 16 || raw.length > AssetTransfer.MAX_RAW)
                throw new IllegalArgumentException("YSM component budget");
            this.id = Objects.requireNonNull(id); this.kind = Objects.requireNonNull(kind);
            this.matches = List.copyOf(matches); this.textures = List.copyOf(textures);
            this.selectedTexture = Objects.requireNonNull(selectedTexture); this.raw = raw.clone();
            this.hash = AssetTransfer.hash(raw); this.model = BbModel.parse(raw);
            checkJson(metadata); this.metadata = metadata.deepCopy();
        }
        public String id() { return id; }
        public String kind() { return kind; }
        public List<String> matches() { return matches; }
        public List<TextureChoice> textures() { return textures; }
        public String selectedTexture() { return selectedTexture; }
        public byte[] raw() { return raw.clone(); }
        public String hash() { return hash; }
        public BbModel model() { return model; }
        public JsonObject metadata() { return metadata.deepCopy(); }
    }

    private static JsonObject objectOrEmpty(JsonObject parent, String key) {
        if (!parent.has(key)) return new JsonObject();
        if (!parent.get(key).isJsonObject()) throw new IllegalArgumentException("YSM metadata object: " + key);
        return parent.getAsJsonObject(key).deepCopy();
    }
    private static JsonArray arrayOrEmpty(JsonObject parent, String key) {
        if (!parent.has(key)) return new JsonArray();
        if (!parent.get(key).isJsonArray()) throw new IllegalArgumentException("YSM metadata array: " + key);
        return parent.getAsJsonArray(key).deepCopy();
    }
    private static String text(JsonObject parent, String key, String fallback) {
        if (!parent.has(key)) return fallback;
        if (!parent.get(key).isJsonPrimitive() || !parent.get(key).getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("YSM metadata text: " + key);
        return parent.get(key).getAsString();
    }
    private static void checkForms(JsonObject properties) {
        JsonArray buttons = arrayOrEmpty(properties, "extra_animation_buttons");
        if (buttons.size() > 32) throw new IllegalArgumentException("YSM button count");
        int forms = 0;
        for (JsonElement value : buttons) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("YSM button object");
            JsonArray entries = arrayOrEmpty(value.getAsJsonObject(), "config_forms");
            if ((forms += entries.size()) > 128) throw new IllegalArgumentException("YSM form count");
            for (JsonElement form : entries) {
                if (!form.isJsonObject()) throw new IllegalArgumentException("YSM form object");
                JsonObject config = form.getAsJsonObject();
                String type = text(config, "type", "");
                if (!List.of("checkbox", "range", "radio").contains(type))
                    throw new IllegalArgumentException("YSM form type");
                if (config.has("labels") && (!config.get("labels").isJsonObject() || config.getAsJsonObject("labels").size() > 64))
                    throw new IllegalArgumentException("YSM radio label count");
            }
        }
        if (arrayOrEmpty(properties, "extra_animation_classify").size() > 32)
            throw new IllegalArgumentException("YSM classification count");
    }
    private static void checkJson(JsonElement value) {
        int[] nodes = {0}, chars = {0}; visit(value, 0, nodes, chars);
    }
    private static void visit(JsonElement value, int depth, int[] nodes, int[] chars) {
        if (depth > 64 || ++nodes[0] > 65_536) throw new IllegalArgumentException("YSM metadata depth/count");
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry -> {
            chars[0] += entry.getKey().length(); visit(entry.getValue(), depth + 1, nodes, chars);
        });
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(child -> visit(child, depth + 1, nodes, chars));
        else if (value.isJsonPrimitive()) chars[0] += value.getAsString().length();
        if (chars[0] > 1_048_576) throw new IllegalArgumentException("YSM metadata string budget");
    }
}
