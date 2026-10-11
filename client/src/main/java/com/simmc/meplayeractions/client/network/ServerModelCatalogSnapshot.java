package com.simmc.meplayeractions.client.network;

import com.simmc.meplayeractions.client.ServerDisguisePreferences;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Session-scoped names for command selection; this directory never authorizes asset downloads. */
public final class ServerModelCatalogSnapshot {
    public static final String CAPABILITY = "server_model_catalog";
    public static final int MAX_MODELS = 4096;
    public static final long COMMAND_INTERVAL = 500_000_000L;

    /** Source and folder describe command choices only, never permission to fetch a resource. */
    public record Model(String id, String label, String source, String folder, Boolean clientResource) {
        public Model(String id, String label) {
            this(id, label, "unknown", "", null);
        }

        public Model(String id, String label, String source, String folder) {
            this(id, label, source, folder, null);
        }

        public Model {
            if (!validSource(source) || !validFolder(folder)) {
                throw new IllegalArgumentException("Server model catalogue location");
            }

        }
    }

    private record Chunk(long revision, int index, int count, boolean canDisguise, boolean truncated,
            List<Model> models) {
    }

    private static final class Pending {
        final long revision;
        final int count;
        final boolean canDisguise, truncated;
        final Map<Integer, List<Model>> chunks = new LinkedHashMap<>();
        final Set<String> ids = new HashSet<>();

        Pending(Chunk chunk) {
            this.revision = chunk.revision();
            this.count = chunk.count();
            this.canDisguise = chunk.canDisguise();
            this.truncated = chunk.truncated();
        }
    }

    private List<Model> models = List.of();
    private Map<String, Model> locations = Map.of();
    private Set<String> ids = Set.of();
    private long revision, displayRevision, lastCommand;
    private boolean canDisguise, truncated, commandSent;
    private Pending pending;

    /** A new handshake/disconnect discards both published names and unfinished old-session fragments. */
    public void reset() {
        this.models = List.of();
        this.locations = Map.of();
        this.ids = Set.of();
        this.revision = 0;
        this.canDisguise = false;
        this.truncated = false;
        this.pending = null;
        this.commandSent = false;
        this.lastCommand = 0;
        this.displayRevision++;
    }

    /** Validate a complete fragment before touching state, and publish only a complete revision. */
    public boolean accept(JsonObject packet) {
        Chunk chunk = read(packet);
        if (chunk.revision() <= this.revision || this.pending != null && chunk.revision() < this.pending.revision) {
            return false;
        }

        boolean replacement = this.pending == null || chunk.revision() > this.pending.revision;
        Pending next = replacement ? new Pending(chunk) : this.pending;
        if (chunk.count() != next.count || chunk.canDisguise() != next.canDisguise
                || chunk.truncated() != next.truncated) {
            throw new IllegalArgumentException("Inconsistent server model catalogue revision");
        }

        List<Model> previous = next.chunks.get(chunk.index());
        if (previous != null) {
            if (!previous.equals(chunk.models())) {
                throw new IllegalArgumentException("Changed server model catalogue fragment");
            }

            return false;
        }
        if (next.ids.size() + chunk.models().size() > MAX_MODELS) {
            throw new IllegalArgumentException("Server model catalogue budget");
        }

        for (Model model : chunk.models()) {
            if (next.ids.contains(model.id())) {
                throw new IllegalArgumentException("Repeated server model catalogue ID");
            }

        }

        next.chunks.put(chunk.index(), chunk.models());
        chunk.models().forEach(model -> next.ids.add(model.id()));
        this.pending = next;
        if (replacement) {
            this.displayRevision++;
        }
        // Stop using old selection authority immediately during replacement.
        if (next.chunks.size() != next.count) {
            return false;
        }

        ArrayList<ServerModelCatalogSnapshot.Model> complete = new ArrayList<Model>(next.ids.size());
        for (int index = 0; index < next.count; index++) {
            complete.addAll(next.chunks.get(index));
        }

        LinkedHashMap<String, ServerModelCatalogSnapshot.Model> directory = new LinkedHashMap<String, Model>();
        complete.forEach(model -> directory.put(model.id(), model));
        this.locations = Map.copyOf(directory);
        this.models = List.copyOf(complete);
        this.ids = Set.copyOf(next.ids);
        this.revision = next.revision;
        this.canDisguise = next.canDisguise;
        this.truncated = next.truncated;
        this.pending = null;
        this.displayRevision++;
        return true;
    }

    private static Chunk read(JsonObject packet) {
        long revision = WireJson.integer(packet, "revision", 1, Long.MAX_VALUE);
        int count = (int) WireJson.integer(packet, "count", 1, MAX_MODELS);
        int index = (int) WireJson.integer(packet, "index", 0, count - 1);
        boolean canDisguise = WireJson.bool(packet, "canDisguise"), truncated = WireJson.bool(packet, "truncated");
        JsonElement values = packet.get("models");
        if (values == null || !values.isJsonArray() || values.getAsJsonArray().size() > 512) {
            throw new IllegalArgumentException("Server model catalogue fragment");
        }

        ArrayList<ServerModelCatalogSnapshot.Model> models = new ArrayList<Model>();
        HashSet<String> ids = new HashSet<String>();
        for (JsonElement value : values.getAsJsonArray()) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Server model catalogue entry");
            }

            JsonObject entry = value.getAsJsonObject();
            String id = WireJson.string(entry, "id", 64), label = WireJson.string(entry, "label", 256);
            if (!validId(id) || !ids.add(id)) {
                throw new IllegalArgumentException("Server model catalogue ID");
            }

            String source = entry.has("source") ? WireJson.string(entry, "source", 16) : "unknown";
            String folder = entry.has("folder") ? WireJson.string(entry, "folder", 256) : "";
            Boolean clientResource = entry.has("clientResource") ? WireJson.bool(entry, "clientResource") : null;
            models.add(new Model(id, label.isEmpty() ? id : label, source, folder, clientResource));
        }
        if ((!canDisguise || models.isEmpty()) && (count != 1 || !models.isEmpty())) {
            throw new IllegalArgumentException("Empty or unauthorized server model catalogue");
        }

        if (!canDisguise && truncated) {
            throw new IllegalArgumentException("Unauthorized server model catalogue truncation");
        }

        return new Chunk(revision, index, count, canDisguise, truncated, List.copyOf(models));
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9_-]{1,64}");
    }

    public static boolean validSource(String source) {
        return source != null && Set.of("own", "modelengine", "unknown").contains(source);
    }

    /** A relative directory description, never a filesystem path to resolve or a command argument. */
    public static boolean validFolder(String folder) {
        if (folder == null || folder.length() > 256 || folder.indexOf('\\') >= 0 || folder.indexOf(':') >= 0
                || folder.chars().anyMatch(Character::isISOControl)) {
            return false;
        }

        if (folder.isEmpty()) {
            return true;
        }

        String[] parts = folder.split("/", -1);
        if (parts.length > 16) {
            return false;
        }

        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }

        }

        return true;
    }

    public List<Model> models() {
        return ready() ? this.models : List.of();
    }

    public Optional<Model> model(String id) {
        return ready() && validId(id) ? Optional.ofNullable(this.locations.get(id)) : Optional.empty();
    }

    public long revision() {
        return this.revision;
    }

    /** Changes when reception starts or completes, so widgets cannot retain old authority while refreshing. */
    public long displayRevision() {
        return this.displayRevision;
    }

    public boolean ready() {
        return this.revision > 0 && this.pending == null;
    }

    public boolean receiving() {
        return this.pending != null;
    }

    public boolean canDisguise() {
        return ready() && this.canDisguise;
    }

    public boolean truncated() {
        return ready() && this.truncated;
    }

    public boolean contains(String id) {
        return validId(id) && canDisguise() && this.ids.contains(id);
    }

    public boolean canRequest(String id, boolean liveSession, long now) {
        return liveSession && contains(id) && (!this.commandSent || now - this.lastCommand >= COMMAND_INTERVAL);
    }

    /** Only builds the existing fixed server command, never arbitrary model/resource request packets. */
    public Optional<String> command(String id, boolean liveSession, long now) {
        return command(id, liveSession, now, ServerDisguisePreferences.defaults());
    }

    public Optional<String> command(String id, boolean liveSession, long now, ServerDisguisePreferences preferences) {
        if (!canRequest(id, liveSession, now)) {
            return Optional.empty();
        }

        String command = Objects.requireNonNull(preferences).command(id);
        this.commandSent = true;
        this.lastCommand = now;
        return Optional.of(command);
    }
}
