package com.simmc.meplayeractions.client;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientOptions {
    private static final Logger LOGGER = LoggerFactory.getLogger("MEPlayerActions/Options");
    public boolean enabled = true, showSelf = true, followServerTimeline = false;
    public int interpolationTicks = 2;
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
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not read client options; using defaults",exception);
        }
    }

    public LocalAppearanceSettings localAppearance() { return localAppearance; }
    public void setLocalAppearance(LocalAppearanceSettings settings) { localAppearance = Objects.requireNonNull(settings); }
    public Path path() { return path; }

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
        if (Files.size(path) >= 16_384) throw new IOException("Client options exceed size limit");
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
            return new LocalAppearanceSettings(enabled.getAsBoolean(), model.getAsString(), value.get("scale").getAsFloat(),
                    value.get("offsetX").getAsDouble(), value.get("offsetY").getAsDouble(), value.get("offsetZ").getAsDouble());
        } catch (RuntimeException invalid) {
            LOGGER.warn("Invalid local appearance; leaving it disabled: {}", invalid.toString());
            return LocalAppearanceSettings.defaults();
        }
    }

    public void save() {
        JsonObject json = new JsonObject(); json.addProperty("enabled",enabled);
        json.addProperty("showSelf",showSelf); json.addProperty("interpolationTicks",interpolationTicks);
        json.addProperty("followServerTimeline",followServerTimeline);
        JsonObject appearance = new JsonObject();
        appearance.addProperty("enabled",localAppearance.enabled()); appearance.addProperty("modelId",localAppearance.modelId());
        appearance.addProperty("scale",localAppearance.scale()); appearance.addProperty("offsetX",localAppearance.offsetX());
        appearance.addProperty("offsetY",localAppearance.offsetY()); appearance.addProperty("offsetZ",localAppearance.offsetZ());
        json.add("localAppearance",appearance);
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch(IOException exception) { LOGGER.warn("Could not save client options",exception); }
    }
}
