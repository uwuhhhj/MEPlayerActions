package com.simmc.meplayeractions.client;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientOptions {
    private static final Logger LOGGER = LoggerFactory.getLogger("MEPlayerActions/Options");
    public boolean enabled = true, showSelf = true, followServerTimeline = false;
    public int interpolationTicks = 2;
    public boolean showModelIds;
    public boolean defaultHeaddress = true;
    public boolean defaultBlueTexture;
    public boolean localActionLocked;
    public static final int MAX_MODEL_PROFILES = 32, MAX_MODEL_VARIABLES = 128, MAX_RADIO_SELECTIONS = 64;
    private final Map<String, ModelProfile> modelProfiles = new LinkedHashMap<>();
    private final Set<String> favorites = new LinkedHashSet<>();
    private final Path path;
    private LocalAppearanceSettings localAppearance = LocalAppearanceSettings.defaults();
    public ClientOptions(Path path) {
        this.path = Objects.requireNonNull(path);
        try {
            JsonObject json = read();
            if (json != null) {
                if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
                if (json.has("showSelf")) showSelf = json.get("showSelf").getAsBoolean();
                if (json.has("followServerTimeline")) followServerTimeline = json.get("followServerTimeline").getAsBoolean();
                if (json.has("interpolationTicks")) interpolationTicks = Math.max(0,Math.min(6,json.get("interpolationTicks").getAsInt()));
                localAppearance = readAppearance(json);
                if (json.has("showModelIds")) showModelIds = json.get("showModelIds").getAsBoolean();
                if (json.has("defaultHeaddress")) defaultHeaddress = json.get("defaultHeaddress").getAsBoolean();
                if (json.has("defaultBlueTexture")) defaultBlueTexture = json.get("defaultBlueTexture").getAsBoolean();
                if (json.has("localActionLocked")) localActionLocked = json.get("localActionLocked").getAsBoolean();
                readProfiles(json);
                if (json.has("favorites") && json.get("favorites").isJsonArray()) {
                    for (JsonElement value : json.getAsJsonArray("favorites")) {
                        if (favorites.size() >= 64) break;
                        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                                && LocalAppearanceSettings.isValidModelId(value.getAsString())) favorites.add(value.getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not read client options; using defaults",exception);
        }
    }

    public LocalAppearanceSettings localAppearance() { return localAppearance; }
    public void setLocalAppearance(LocalAppearanceSettings settings) { localAppearance = Objects.requireNonNull(settings); }
    public Path path() { return path; }
    public boolean isFavorite(String id) { return favorites.contains(id); }
    public void toggleFavorite(String id) {
        if (!LocalAppearanceSettings.isValidModelId(id)) return;
        if (!favorites.remove(id) && favorites.size() < 64) favorites.add(id);
        save();
    }

    /** Reload only the private appearance without changing live server-render options. */
    public void reloadLocalAppearance() {
        localAppearance = LocalAppearanceSettings.defaults();
        try {
            JsonObject json = read();
            if (json != null) localAppearance = readAppearance(json);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not reload local appearance; using defaults", exception);
        }
    }

    private JsonObject read() throws IOException {
        if (!Files.exists(path)) return null;
        if (Files.size(path) >= 65_536) throw new IOException("Client options exceed size limit");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static LocalAppearanceSettings readAppearance(JsonObject json) {
        if (!json.has("localAppearance")) return LocalAppearanceSettings.defaults();
        try {
            JsonObject value = json.getAsJsonObject("localAppearance");
            JsonPrimitive enabled = value.getAsJsonPrimitive("enabled"), model = value.getAsJsonPrimitive("modelId");
            if (!enabled.isBoolean() || !model.isString()) throw new IllegalArgumentException("Appearance field type");
            for (String field : new String[]{"scale", "offsetX", "offsetY", "offsetZ"})
                if (!value.getAsJsonPrimitive(field).isNumber()) throw new IllegalArgumentException("Appearance number type");
            // Removed bundled server models must not silently become another enabled appearance.
            if (model.getAsString().equals("ysm_01_jk") || model.getAsString().equals("ysm_02_jk"))
                return LocalAppearanceSettings.defaults();
            return new LocalAppearanceSettings(enabled.getAsBoolean(), model.getAsString(), value.get("scale").getAsFloat(),
                    value.get("offsetX").getAsDouble(), value.get("offsetY").getAsDouble(), value.get("offsetZ").getAsDouble());
        } catch (RuntimeException invalid) {
            LOGGER.warn("Invalid local appearance; leaving it disabled: {}", invalid.toString());
            return LocalAppearanceSettings.defaults();
        }
    }

    /** Only authored configuration variables belong here; physics/query state is never persisted. */
    public record ModelProfile(String textureId, Map<String, Double> variables, Map<String, Integer> radioSelections) {
        public ModelProfile {
            textureId = textureId == null ? "" : textureId;
            if (textureId.length() > 128 || textureId.contains("/") || textureId.contains("\\") || textureId.contains("..")
                    || textureId.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Texture id");
            Objects.requireNonNull(variables); Objects.requireNonNull(radioSelections);
            if (variables.size() > MAX_MODEL_VARIABLES || radioSelections.size() > MAX_RADIO_SELECTIONS)
                throw new IllegalArgumentException("Model profile count");
            Map<String, Double> copied = new LinkedHashMap<>();
            variables.forEach((key, value) -> {
                String normalized = normalizeModelVariable(key);
                if (value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000)
                    throw new IllegalArgumentException("Model variable value");
                if (copied.put(normalized, value) != null) throw new IllegalArgumentException("Duplicate model variable");
            });
            Map<String, Integer> radios = new LinkedHashMap<>();
            radioSelections.forEach((key, value) -> {
                if (key == null || key.isBlank() || key.length() > 192 || key.chars().anyMatch(Character::isISOControl)
                        || value == null || value < 0 || value >= 64) throw new IllegalArgumentException("Radio selection");
                radios.put(key, value);
            });
            variables = Collections.unmodifiableMap(copied); radioSelections = Collections.unmodifiableMap(radios);
        }
        public static ModelProfile defaults() { return new ModelProfile("", Map.of(), Map.of()); }
    }
    public static String normalizeModelVariable(String key) {
        if (key == null) throw new IllegalArgumentException("Model variable name");
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("v.")) normalized = "variable." + normalized.substring(2);
        if (normalized.length() > 128 || !normalized.matches("variable\\.[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*"))
            throw new IllegalArgumentException("Model variable name");
        return normalized;
    }
    public ModelProfile modelProfile(String id) { return modelProfiles.getOrDefault(id, ModelProfile.defaults()); }
    /** Roll back both memory and disk on validation, budget or IO failure. */
    public boolean updateModelProfile(String id, ModelProfile profile) {
        if (!LocalAppearanceSettings.isValidModelId(id) || profile == null) return false;
        ModelProfile previous = modelProfiles.get(id);
        if (previous == null && modelProfiles.size() >= MAX_MODEL_PROFILES) return false;
        modelProfiles.put(id, profile);
        if (write()) return true;
        if (previous == null) modelProfiles.remove(id); else modelProfiles.put(id, previous);
        return false;
    }
    public boolean resetModelProfile(String id) {
        ModelProfile previous = modelProfiles.remove(id);
        if (previous == null) return true;
        if (write()) return true;
        modelProfiles.put(id, previous); return false;
    }
    private void readProfiles(JsonObject json) {
        if (!json.has("modelProfiles")) return;
        try {
            JsonObject entries = json.getAsJsonObject("modelProfiles");
            if (entries.size() > MAX_MODEL_PROFILES) throw new IllegalArgumentException("Profile count");
            Map<String, ModelProfile> parsed = new LinkedHashMap<>();
            for (var entry : entries.entrySet()) {
                if (!LocalAppearanceSettings.isValidModelId(entry.getKey())) throw new IllegalArgumentException("Profile model id");
                JsonObject value = entry.getValue().getAsJsonObject();
                String texture = "";
                if (value.has("textureId")) {
                    if (!value.getAsJsonPrimitive("textureId").isString()) throw new IllegalArgumentException("Texture type");
                    texture = value.get("textureId").getAsString();
                }
                Map<String, Double> variables = new LinkedHashMap<>();
                if (value.has("variables")) for (var variable : value.getAsJsonObject("variables").entrySet()) {
                    if (!variable.getValue().isJsonPrimitive() || !variable.getValue().getAsJsonPrimitive().isNumber())
                        throw new IllegalArgumentException("Variable type");
                    variables.put(variable.getKey(), variable.getValue().getAsDouble());
                }
                Map<String, Integer> radios = new LinkedHashMap<>();
                if (value.has("radioSelections")) for (var radio : value.getAsJsonObject("radioSelections").entrySet()) {
                    if (!radio.getValue().isJsonPrimitive() || !radio.getValue().getAsJsonPrimitive().isNumber()
                            || radio.getValue().getAsDouble() != radio.getValue().getAsInt()) throw new IllegalArgumentException("Radio type");
                    radios.put(radio.getKey(), radio.getValue().getAsInt());
                }
                parsed.put(entry.getKey(), new ModelProfile(texture, variables, radios));
            }
            modelProfiles.putAll(parsed);
        } catch (RuntimeException invalid) {
            modelProfiles.clear(); LOGGER.warn("Invalid model profiles; ignoring private configuration: {}", invalid.toString());
        }
    }
    private JsonObject json() {
        JsonObject json = new JsonObject(); json.addProperty("enabled",enabled);
        json.addProperty("showSelf",showSelf); json.addProperty("interpolationTicks",interpolationTicks);
        json.addProperty("followServerTimeline",followServerTimeline);
        json.addProperty("showModelIds", showModelIds);
        json.addProperty("defaultHeaddress", defaultHeaddress);
        json.addProperty("defaultBlueTexture", defaultBlueTexture);
        json.addProperty("localActionLocked", localActionLocked);
        JsonArray favoriteJson = new JsonArray(); favorites.forEach(favoriteJson::add); json.add("favorites", favoriteJson);
        JsonObject appearance = new JsonObject();
        appearance.addProperty("enabled",localAppearance.enabled()); appearance.addProperty("modelId",localAppearance.modelId());
        appearance.addProperty("scale",localAppearance.scale()); appearance.addProperty("offsetX",localAppearance.offsetX());
        appearance.addProperty("offsetY",localAppearance.offsetY()); appearance.addProperty("offsetZ",localAppearance.offsetZ());
        json.add("localAppearance",appearance);
        JsonObject profiles = new JsonObject();
        modelProfiles.forEach((id, profile) -> {
            JsonObject data = new JsonObject(), variables = new JsonObject(), radios = new JsonObject();
            data.addProperty("textureId", profile.textureId());
            profile.variables().forEach(variables::addProperty); profile.radioSelections().forEach(radios::addProperty);
            data.add("variables", variables); data.add("radioSelections", radios); profiles.add(id, data);
        });
        json.add("modelProfiles", profiles); return json;
    }
    public void save() { write(); }
    private boolean write() {
        Path temporary = null;
        try {
            byte[] data = new GsonBuilder().setPrettyPrinting().create().toJson(json()).getBytes(StandardCharsets.UTF_8);
            if (data.length >= 65_536) throw new IOException("Client options exceed size limit");
            Path absolute = path.toAbsolutePath(); Files.createDirectories(absolute.getParent());
            temporary = Files.createTempFile(absolute.getParent(), "mpa-options-", ".tmp");
            Files.write(temporary, data);
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch(IOException | RuntimeException exception) { LOGGER.warn("Could not save client options",exception); return false; }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }
}
