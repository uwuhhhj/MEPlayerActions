package com.simmc.meplayeractions.client;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;

public final class ClientOptions {
    public boolean enabled = true, showSelf = true, followServerTimeline = false;
    public int interpolationTicks = 2;
    private final Path path;
    public ClientOptions(Path path) {
        this.path = path;
        try {
            if (Files.exists(path) && Files.size(path) < 4096) {
                JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
                if (json.has("showSelf")) showSelf = json.get("showSelf").getAsBoolean();
                if (json.has("followServerTimeline")) followServerTimeline = json.get("followServerTimeline").getAsBoolean();
                if (json.has("interpolationTicks")) interpolationTicks = Math.max(0,Math.min(6,json.get("interpolationTicks").getAsInt()));
            }
        } catch (IOException | RuntimeException exception) {
            MEPlayerActionsClient.LOGGER.warn("Could not read client options; using defaults",exception);
        }
    }
    public void save() {
        JsonObject json = new JsonObject(); json.addProperty("enabled",enabled);
        json.addProperty("showSelf",showSelf); json.addProperty("interpolationTicks",interpolationTicks);
        json.addProperty("followServerTimeline",followServerTimeline);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch(IOException exception) { MEPlayerActionsClient.LOGGER.warn("Could not save client options",exception); }
    }
}
