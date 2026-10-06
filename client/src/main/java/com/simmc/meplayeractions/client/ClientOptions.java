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
    /** Local rendering toggle; server action networking and catalogues remain connected. */
    public boolean enabled = true, showSelf = true, followServerTimeline = false;
    /** Native self body, native equipment and the selected disguise have independent visibility. */
    public boolean hideVanillaPlayer = true, hideVanillaEquipment = true;
    public int interpolationTicks = 2;
    public boolean showModelIds;
    public boolean defaultHeaddress = true;
    public boolean defaultBlueTexture;
    public boolean localActionLocked;
    /** Explicit opt-in; old configurations remain local until a server negotiates private uploads. */
    public boolean privateSyncEnabled;
    /** A server disguise suspends saved private choices until the player explicitly uses one again. */
    public boolean privateAppearancePaused;
    public static final int MAX_MODEL_PROFILES = 32, MAX_MODEL_VARIABLES = 128, MAX_RADIO_SELECTIONS = 64;
    public static final int MAX_ROAMING_VARIABLES = 64, MAX_ROAMING_VARIABLE_NAME_LENGTH = 32;
    private static final String ROAMING_PREFIX = "variable.roaming.";
    private final Map<String, ModelProfile> modelProfiles = new LinkedHashMap<>();
    public static final int MAX_SERVER_DISGUISE_PROFILES = 32;
    private final Map<String, ServerDisguisePreferences> serverDisguiseProfiles = new LinkedHashMap<>();
    private final Set<String> favorites = new LinkedHashSet<>();
    private final Path path;
    private LocalAppearanceSettings localAppearance = LocalAppearanceSettings.defaults();
    private WheelPreferences wheelPreferences = WheelPreferences.defaults();
    private boolean roamingVariablesDirty;
    public ClientOptions(Path path) {
        this.path = Objects.requireNonNull(path);
        try {
            JsonObject json = read();
            if (json != null) {
                if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
                if (json.has("showSelf")) showSelf = json.get("showSelf").getAsBoolean();
                if (json.has("hideVanillaPlayer")) hideVanillaPlayer = json.get("hideVanillaPlayer").getAsBoolean();
                if (json.has("hideVanillaEquipment")) hideVanillaEquipment = json.get("hideVanillaEquipment").getAsBoolean();
                // The old ambiguous "self" toggle hid the disguise; the new GUI names all three layers.
                if (!json.has("hideVanillaPlayer") && !json.has("hideVanillaEquipment")) showSelf = true;
                if (json.has("followServerTimeline")) followServerTimeline = json.get("followServerTimeline").getAsBoolean();
                if (json.has("interpolationTicks")) interpolationTicks = Math.max(0,Math.min(6,json.get("interpolationTicks").getAsInt()));
                localAppearance = readAppearance(json);
                if (json.has("showModelIds")) showModelIds = json.get("showModelIds").getAsBoolean();
                if (json.has("defaultHeaddress")) defaultHeaddress = json.get("defaultHeaddress").getAsBoolean();
                if (json.has("defaultBlueTexture")) defaultBlueTexture = json.get("defaultBlueTexture").getAsBoolean();
                if (json.has("localActionLocked")) localActionLocked = json.get("localActionLocked").getAsBoolean();
                JsonElement privateSync = json.get("privateSyncEnabled");
                privateSyncEnabled = privateSync != null && privateSync.isJsonPrimitive()
                        && privateSync.getAsJsonPrimitive().isBoolean() && privateSync.getAsBoolean();
                JsonElement paused = json.get("privateAppearancePaused");
                privateAppearancePaused = paused != null && paused.isJsonPrimitive()
                        && paused.getAsJsonPrimitive().isBoolean() && paused.getAsBoolean();
                wheelPreferences = readWheelPreferences(json);
                readProfiles(json);
                readServerDisguiseProfiles(json);
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
    public boolean updatePrivateAppearancePaused(boolean paused) {
        if (privateAppearancePaused == paused) return true;
        boolean previous = privateAppearancePaused; privateAppearancePaused = paused;
        if (write()) return true;
        privateAppearancePaused = previous; return false;
    }
    public ServerDisguisePreferences serverDisguisePreferences(String id) {
        return serverDisguiseProfiles.getOrDefault(id, ServerDisguisePreferences.defaults());
    }
    public boolean updateServerDisguisePreferences(String id, ServerDisguisePreferences preferences) {
        if (id == null || !id.matches("[a-z0-9_-]{1,64}") || preferences == null) return false;
        try { preferences.command(id); } catch (IllegalArgumentException invalid) { return false; }
        ServerDisguisePreferences previous = serverDisguiseProfiles.get(id);
        if (!preferences.isDefault() && previous == null && serverDisguiseProfiles.size() >= MAX_SERVER_DISGUISE_PROFILES) return false;
        if (preferences.isDefault()) serverDisguiseProfiles.remove(id); else serverDisguiseProfiles.put(id, preferences);
        if (write()) return true;
        if (previous == null) serverDisguiseProfiles.remove(id); else serverDisguiseProfiles.put(id, previous);
        return false;
    }
    public boolean resetServerDisguisePreferences(String id) { return updateServerDisguisePreferences(id, ServerDisguisePreferences.defaults()); }
    private void readServerDisguiseProfiles(JsonObject json) {
        JsonElement entries = json.get("serverDisguiseProfiles");
        if (entries == null || !entries.isJsonObject()) return;
        for (var entry : entries.getAsJsonObject().entrySet()) {
            if (serverDisguiseProfiles.size() >= MAX_SERVER_DISGUISE_PROFILES) break;
            if (!entry.getKey().matches("[a-z0-9_-]{1,64}") || !entry.getValue().isJsonObject()) continue;
            try {
                var preferences = ServerDisguisePreferences.fromJson(entry.getValue().getAsJsonObject());
                preferences.command(entry.getKey());
                if (!preferences.isDefault()) serverDisguiseProfiles.put(entry.getKey(), preferences);
            } catch (RuntimeException invalid) { LOGGER.warn("Invalid server disguise preferences for {}", entry.getKey()); }
        }
    }
    public WheelPreferences wheelPreferences() { return wheelPreferences; }
    /** Persist wheel memory atomically, retaining the previous memory on budget or IO failure. */
    public boolean updateWheelPreferences(WheelPreferences preferences) {
        WheelPreferences previous = wheelPreferences;
        wheelPreferences = Objects.requireNonNull(preferences);
        if (write()) return true;
        wheelPreferences = previous; return false;
    }
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

    private static WheelPreferences readWheelPreferences(JsonObject json) {
        if (!json.has("wheelPreferences")) return WheelPreferences.defaults();
        try {
            JsonObject value = json.getAsJsonObject("wheelPreferences");
            return new WheelPreferences(readWheelSource(value), readWheelPage(value, "clientPage"), readWheelPage(value, "serverPage"),
                    readWheelKeepOpen(value));
        } catch (RuntimeException invalid) {
            LOGGER.warn("Invalid wheel preferences; using wheel defaults: {}", invalid.toString());
            return WheelPreferences.defaults();
        }
    }

    private static WheelPreferences.Source readWheelSource(JsonObject value) {
        JsonElement selected = value.get("source");
        if (selected != null && selected.isJsonPrimitive() && selected.getAsJsonPrimitive().isString()
                && selected.getAsString().equals("server")) return WheelPreferences.Source.SERVER;
        return WheelPreferences.Source.CLIENT;
    }

    private static boolean readWheelKeepOpen(JsonObject value) {
        JsonElement selected = value.get("keepOpen");
        return selected != null && selected.isJsonPrimitive() && selected.getAsJsonPrimitive().isBoolean() && selected.getAsBoolean();
    }

    private static int readWheelPage(JsonObject value, String key) {
        try {
            JsonElement page = value.get(key);
            if (page == null || !page.isJsonPrimitive() || !page.getAsJsonPrimitive().isNumber()) return 0;
            int integer = page.getAsBigDecimal().intValueExact();
            return integer >= 0 && integer <= WheelPreferences.MAX_PAGE ? integer : 0;
        } catch (RuntimeException invalid) { return 0; }
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
                if (normalized.equals("variable.roaming") || normalized.startsWith(ROAMING_PREFIX) && !isRoamingVariable(normalized))
                    throw new IllegalArgumentException("Roaming variable name");
                if (value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000)
                    throw new IllegalArgumentException("Model variable value");
                if (copied.put(normalized, value) != null) throw new IllegalArgumentException("Duplicate model variable");
            });
            if (copied.keySet().stream().filter(ClientOptions::isRoamingVariable).count() > MAX_ROAMING_VARIABLES)
                throw new IllegalArgumentException("Roaming variable count");
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
    /** A canonical numeric roaming field, matching the native parser's single identifier syntax. */
    public static boolean isRoamingVariable(String key) {
        if (key == null || !key.startsWith(ROAMING_PREFIX)) return false;
        String name = key.substring(ROAMING_PREFIX.length());
        if (name.isEmpty() || name.length() > MAX_ROAMING_VARIABLE_NAME_LENGTH
                || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')) return false;
        for (int index = 1; index < name.length(); index++)
            if (!(Character.isLetterOrDigit(name.charAt(index)) || name.charAt(index) == '_')) return false;
        return true;
    }
    /**
     * Merge a validated author dirty batch in memory. True means values changed; false is a no-op or rejected batch.
     * Ordinary form variables, skins and radio selections are retained. Rendering never performs disk IO here.
     */
    public boolean updateRoamingVariables(String id, Map<String, Double> values) {
        if (!LocalAppearanceSettings.isValidModelId(id) || values == null || values.isEmpty()
                || values.size() > MAX_ROAMING_VARIABLES) return false;
        ModelProfile previous = modelProfiles.get(id);
        if (previous == null && modelProfiles.size() >= MAX_MODEL_PROFILES) return false;
        try {
            Map<String, Double> normalized = new LinkedHashMap<>();
            for (var entry : values.entrySet()) {
                String name = normalizeModelVariable(entry.getKey());
                Double value = entry.getValue();
                if (!isRoamingVariable(name) || value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000
                        || normalized.put(name, value) != null) return false;
            }
            ModelProfile base = previous == null ? ModelProfile.defaults() : previous;
            Map<String, Double> merged = new LinkedHashMap<>(base.variables());
            merged.putAll(normalized);
            ModelProfile next = new ModelProfile(base.textureId(), merged, base.radioSelections());
            if (next.equals(base)) return false;
            modelProfiles.put(id, next);
            roamingVariablesDirty = true;
            return true;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    public boolean hasPendingRoamingVariables() { return roamingVariablesDirty; }
    /** One atomic options write for all pending models; a failed write retains their memory and dirty flag. */
    public boolean flushRoamingVariables() { return !roamingVariablesDirty || write(); }
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
        json.addProperty("showSelf",showSelf); json.addProperty("hideVanillaPlayer",hideVanillaPlayer);
        json.addProperty("hideVanillaEquipment",hideVanillaEquipment);
        json.addProperty("interpolationTicks",interpolationTicks);
        json.addProperty("followServerTimeline",followServerTimeline);
        json.addProperty("showModelIds", showModelIds);
        json.addProperty("defaultHeaddress", defaultHeaddress);
        json.addProperty("defaultBlueTexture", defaultBlueTexture);
        json.addProperty("localActionLocked", localActionLocked);
        json.addProperty("privateSyncEnabled", privateSyncEnabled);
        json.addProperty("privateAppearancePaused", privateAppearancePaused);
        JsonObject wheel = new JsonObject();
        wheel.addProperty("source", wheelPreferences.source().name().toLowerCase(Locale.ROOT));
        wheel.addProperty("clientPage", wheelPreferences.clientPage()); wheel.addProperty("serverPage", wheelPreferences.serverPage());
        wheel.addProperty("keepOpen", wheelPreferences.keepOpen());
        json.add("wheelPreferences", wheel);
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
        json.add("modelProfiles", profiles);
        JsonObject serverProfiles = new JsonObject();
        serverDisguiseProfiles.forEach((id, preferences) -> serverProfiles.add(id, preferences.toJson()));
        json.add("serverDisguiseProfiles", serverProfiles); return json;
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
            roamingVariablesDirty = false;
            return true;
        } catch(IOException | RuntimeException exception) { LOGGER.warn("Could not save client options",exception); return false; }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }
}
