package com.simmc.meplayeractions.client.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simmc.meplayeractions.client.ServerDisguisePreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Session-scoped names for command selection; this directory never authorizes asset downloads. */
public final class ServerModelCatalogSnapshot {
    public static final String CAPABILITY = "server_model_catalog";
    public static final int MAX_MODELS = 4096;
    public static final long COMMAND_INTERVAL = 500_000_000L;
    /** Source and folder describe command choices only, never permission to fetch a resource. */
    public record Model(String id, String label, String source, String folder, Boolean clientResource) {
        public Model(String id, String label) { this(id, label, "unknown", "", null); }
        public Model(String id, String label, String source, String folder) { this(id, label, source, folder, null); }
        public Model {
            if (!validSource(source) || !validFolder(folder)) throw new IllegalArgumentException("Server model catalogue location");
        }
    }

    private record Chunk(long revision, int index, int count, boolean canDisguise,
                         boolean truncated, List<Model> models) { }
    private static final class Pending {
        final long revision;
        final int count;
        final boolean canDisguise, truncated;
        final Map<Integer, List<Model>> chunks = new LinkedHashMap<>();
        final Set<String> ids = new HashSet<>();
        Pending(Chunk chunk) {
            revision = chunk.revision(); count = chunk.count();
            canDisguise = chunk.canDisguise(); truncated = chunk.truncated();
        }
    }
    private List<Model> models = List.of();
    private Map<String,Model> locations = Map.of();
    private Set<String> ids = Set.of();
    private long revision, displayRevision, lastCommand;
    private boolean canDisguise, truncated, commandSent;
    private Pending pending;

    /** A new handshake/disconnect discards both published names and unfinished old-session fragments. */
    public void reset() {
        models = List.of(); locations=Map.of(); ids = Set.of(); revision = 0;
        canDisguise = false; truncated = false; pending = null;
        commandSent = false; lastCommand = 0; displayRevision++;
    }

    /** Validate a complete fragment before touching state, and publish only a complete revision. */
    public boolean accept(JsonObject packet) {
        Chunk chunk = read(packet);
        if (chunk.revision() <= revision || pending != null && chunk.revision() < pending.revision) return false;
        boolean replacement = pending == null || chunk.revision() > pending.revision;
        Pending next = replacement ? new Pending(chunk) : pending;
        if (chunk.count() != next.count || chunk.canDisguise() != next.canDisguise || chunk.truncated() != next.truncated)
            throw new IllegalArgumentException("Inconsistent server model catalogue revision");
        List<Model> previous = next.chunks.get(chunk.index());
        if (previous != null) {
            if (!previous.equals(chunk.models())) throw new IllegalArgumentException("Changed server model catalogue fragment");
            return false;
        }
        if (next.ids.size() + chunk.models().size() > MAX_MODELS)
            throw new IllegalArgumentException("Server model catalogue budget");
        for (Model model : chunk.models()) if (next.ids.contains(model.id()))
            throw new IllegalArgumentException("Repeated server model catalogue ID");
        next.chunks.put(chunk.index(), chunk.models());
        chunk.models().forEach(model -> next.ids.add(model.id()));
        pending = next;
        if (replacement) displayRevision++; // Stop using old selection authority immediately during replacement.
        if (next.chunks.size() != next.count) return false;
        var complete = new ArrayList<Model>(next.ids.size());
        for (int index = 0; index < next.count; index++) complete.addAll(next.chunks.get(index));
        var directory=new LinkedHashMap<String,Model>();complete.forEach(model->directory.put(model.id(),model));
        locations=Map.copyOf(directory);
        models = List.copyOf(complete); ids = Set.copyOf(next.ids); revision = next.revision;
        canDisguise = next.canDisguise; truncated = next.truncated; pending = null; displayRevision++;
        return true;
    }

    private static Chunk read(JsonObject packet) {
        long revision = WireJson.integer(packet, "revision", 1, Long.MAX_VALUE);
        int count = (int) WireJson.integer(packet, "count", 1, MAX_MODELS);
        int index = (int) WireJson.integer(packet, "index", 0, count - 1);
        boolean canDisguise = WireJson.bool(packet, "canDisguise"), truncated = WireJson.bool(packet, "truncated");
        JsonElement values = packet.get("models");
        if (values == null || !values.isJsonArray() || values.getAsJsonArray().size() > 512)
            throw new IllegalArgumentException("Server model catalogue fragment");
        var models = new ArrayList<Model>();
        var ids = new HashSet<String>();
        for (JsonElement value : values.getAsJsonArray()) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("Server model catalogue entry");
            JsonObject entry = value.getAsJsonObject();
            String id = WireJson.string(entry, "id", 64), label = WireJson.string(entry, "label", 256);
            if (!validId(id) || !ids.add(id)) throw new IllegalArgumentException("Server model catalogue ID");
            String source = entry.has("source") ? WireJson.string(entry, "source", 16) : "unknown";
            String folder = entry.has("folder") ? WireJson.string(entry, "folder", 256) : "";
            Boolean clientResource = entry.has("clientResource") ? WireJson.bool(entry, "clientResource") : null;
            models.add(new Model(id, label.isEmpty() ? id : label, source, folder, clientResource));
        }
        if ((!canDisguise || models.isEmpty()) && (count != 1 || !models.isEmpty()))
            throw new IllegalArgumentException("Empty or unauthorized server model catalogue");
        if (!canDisguise && truncated) throw new IllegalArgumentException("Unauthorized server model catalogue truncation");
        return new Chunk(revision, index, count, canDisguise, truncated, List.copyOf(models));
    }

    public static boolean validId(String id) { return id != null && id.matches("[a-z0-9_-]{1,64}"); }
    public static boolean validSource(String source) { return source != null && Set.of("own", "modelengine", "unknown").contains(source); }
    /** A relative directory description, never a filesystem path to resolve or a command argument. */
    public static boolean validFolder(String folder) {
        if (folder == null || folder.length() > 256 || folder.indexOf('\\') >= 0 || folder.indexOf(':') >= 0
                || folder.chars().anyMatch(Character::isISOControl)) return false;
        if (folder.isEmpty()) return true;
        String[] parts = folder.split("/", -1);
        if (parts.length > 16) return false;
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    public List<Model> models() { return ready() ? models : List.of(); }
    public Optional<Model> model(String id) {return ready()&&validId(id)?Optional.ofNullable(locations.get(id)):Optional.empty();}
    public long revision() { return revision; }
    /** Changes when reception starts or completes, so widgets cannot retain old authority while refreshing. */
    public long displayRevision() { return displayRevision; }
    public boolean ready() { return revision > 0 && pending == null; }
    public boolean receiving() { return pending != null; }
    public boolean canDisguise() { return ready() && canDisguise; }
    public boolean truncated() { return ready() && truncated; }
    public boolean contains(String id) { return validId(id) && canDisguise() && ids.contains(id); }

    public boolean canRequest(String id, boolean liveSession, long now) {
        return liveSession && contains(id) && (!commandSent || now - lastCommand >= COMMAND_INTERVAL);
    }
    /** Only builds the existing fixed server command, never arbitrary model/resource request packets. */
    public Optional<String> command(String id, boolean liveSession, long now) {
        return command(id, liveSession, now, ServerDisguisePreferences.defaults());
    }
    public Optional<String> command(String id, boolean liveSession, long now, ServerDisguisePreferences preferences) {
        if (!canRequest(id, liveSession, now)) return Optional.empty();
        String command = java.util.Objects.requireNonNull(preferences).command(id);
        commandSent = true; lastCommand = now;
        return Optional.of(command);
    }
}
