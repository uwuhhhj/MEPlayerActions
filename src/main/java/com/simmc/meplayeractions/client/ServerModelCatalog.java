package com.simmc.meplayeractions.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Passive, bounded model-choice metadata. A catalog never reads or authorizes an asset. */
final class ServerModelCatalog {
    static final String CAPABILITY = "server_model_catalog";
    static final int REFRESH_TICKS = 100, MAX_MODELS = 4096, MAX_MODELS_PER_CHUNK = 512;
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private final Supplier<List<String>> source;
    private final Supplier<MetadataSnapshot> metadata;
    private final Map<Integer, Plan> permittedPlans = new HashMap<>(), deniedPlans = new HashMap<>();
    private List<Entry> models = List.of();
    private MetadataSnapshot lastMetadata;
    private boolean initialized, truncated;
    private long lastRefresh;

    ServerModelCatalog(Supplier<List<String>> source) { this(source, () -> null); }
    ServerModelCatalog(Supplier<List<String>> source, Supplier<MetadataSnapshot> metadata) {
        this.source = Objects.requireNonNull(source); this.metadata = Objects.requireNonNull(metadata);
    }

    /** Shared five-second registry refresh; a newly completed directory snapshot also updates choices. */
    void refresh(long tick) {
        MetadataSnapshot folders = metadata.get();
        if (initialized && folders == lastMetadata && !ClientSyncCadence.due(tick, lastRefresh, REFRESH_TICKS)) return;
        var choices = new TreeSet<Entry>(Comparator.comparingInt((Entry entry) -> entry.metadata == null ? 2 : entry.metadata.order())
                .thenComparing(entry -> entry.metadata == null ? "" : entry.metadata.folder)
                .thenComparing(Entry::id));
        for (String id : source.get()) {
            if (id == null || !MODEL_ID.matcher(id).matches()) continue;
            choices.add(new Entry(id, folders == null ? null : folders.forModel(id)));
            // Keep the deterministic first choices without retaining an unbounded registry copy.
            if (choices.size() > MAX_MODELS + 1) choices.pollLast();
        }
        boolean nextTruncated = choices.size() > MAX_MODELS;
        if (nextTruncated) choices.pollLast();
        List<Entry> next = List.copyOf(choices);
        if (!next.equals(models) || truncated != nextTruncated) {
            models = next; truncated = nextTruncated; permittedPlans.clear();
        }
        initialized = true; lastRefresh = tick; lastMetadata = folders;
    }

    Plan plan(boolean permitted, int maxPayload) {
        Map<Integer, Plan> plans = permitted ? permittedPlans : deniedPlans;
        return plans.computeIfAbsent(maxPayload,
                limit -> Plan.create(permitted ? models : List.of(), permitted, permitted && truncated, limit));
    }

    String status() { return models.size() + " 个" + (truncated ? "（目录已达 4096 上限，有模型未列出）" : ""); }

    /** Optional presentation facts. These fields never constitute an asset offer or download permission. */
    record Metadata(String source, String folder, Boolean clientResource) {
        Metadata {
            if (!Set.of("own", "modelengine", "unknown").contains(source == null ? "" : source)) source = "unknown";
            if (!validFolder(folder)) { source = "unknown"; folder = ""; }
            if (source.equals("unknown")) folder = "";
        }
        int order() { return source.equals("own") ? 0 : source.equals("modelengine") ? 1 : 2; }
        static boolean validFolder(String folder) {
            if (folder == null || folder.length() > 256 || folder.indexOf('\\') >= 0 || folder.indexOf(':') >= 0) return false;
            if (folder.isEmpty()) return true;
            String[] components = folder.split("/", -1);
            if (components.length > 16) return false;
            for (String component : components) {
                if (component.isBlank() || component.equals(".") || component.equals("..")) return false;
                for (int offset = 0; offset < component.length();) {
                    int codePoint = component.codePointAt(offset); offset += Character.charCount(codePoint);
                    if (Character.isISOControl(codePoint) || codePoint == 0x00a7 || codePoint == 0x2028 || codePoint == 0x2029) return false;
                }
            }
            return true;
        }
    }
    record MetadataSnapshot(Map<String, Metadata> entries, boolean ownComplete) {
        MetadataSnapshot { entries = Map.copyOf(entries); }
        Metadata forModel(String id) {
            return entries.getOrDefault(id, new Metadata("unknown", "", ownComplete ? Boolean.FALSE : null));
        }
    }
    private record Entry(String id, Metadata metadata) { }

    /** Serialized content is shared; only the small, monotonically increasing session revision differs. */
    static final class Plan {
        private final List<byte[]> prefixes;
        final boolean canDisguise, truncated;
        private Plan(List<byte[]> prefixes, boolean canDisguise, boolean truncated) {
            this.prefixes = List.copyOf(prefixes); this.canDisguise = canDisguise; this.truncated = truncated;
        }
        int count() { return prefixes.size(); }
        byte[] packet(int index, long revision) {
            if (revision <= 0) throw new IllegalArgumentException("Catalog revision must be positive");
            byte[] prefix = prefixes.get(index), suffix = (revision + "}").getBytes(StandardCharsets.US_ASCII);
            byte[] packet = Arrays.copyOf(prefix, prefix.length + suffix.length);
            System.arraycopy(suffix, 0, packet, prefix.length, suffix.length); return packet;
        }
        private static Plan create(List<Entry> models, boolean permitted, boolean truncated, int maxPayload) {
            if (maxPayload < 1024 || maxPayload > 32766) throw new IllegalArgumentException("Invalid catalog payload budget");
            var chunks = new ArrayList<JsonArray>(); var entries = new JsonArray();
            int overhead = prefix(new JsonArray(), MAX_MODELS - 1, MAX_MODELS, permitted, truncated).length + 20;
            int size = overhead;
            for (Entry model : models) {
                JsonObject entry = new JsonObject(); entry.addProperty("id", model.id); entry.addProperty("label", model.id);
                if (model.metadata != null) {
                    entry.addProperty("source", model.metadata.source); entry.addProperty("folder", model.metadata.folder);
                    if (model.metadata.clientResource != null) entry.addProperty("clientResource", model.metadata.clientResource);
                }
                int bytes = entry.toString().getBytes(StandardCharsets.UTF_8).length + (entries.isEmpty() ? 0 : 1);
                // Even a valid 256-character Unicode directory can exceed a legacy 1 KiB packet.
                // Omit that classification instead of failing the entire catalog or truncating a path.
                if (overhead + bytes > maxPayload && model.metadata != null) {
                    entry.addProperty("source", "unknown"); entry.addProperty("folder", "");
                    bytes = entry.toString().getBytes(StandardCharsets.UTF_8).length + (entries.isEmpty() ? 0 : 1);
                }
                if (!entries.isEmpty() && (entries.size() >= MAX_MODELS_PER_CHUNK || size + bytes > maxPayload)) {
                    chunks.add(entries); entries = new JsonArray(); size = overhead; bytes--;
                }
                if (size + bytes > maxPayload) throw new IllegalArgumentException("Catalog entry exceeds payload budget");
                entries.add(entry); size += bytes;
            }
            chunks.add(entries);
            var prefixes = new ArrayList<byte[]>(chunks.size());
            for (int index = 0; index < chunks.size(); index++)
                prefixes.add(prefix(chunks.get(index), index, chunks.size(), permitted, truncated));
            return new Plan(prefixes, permitted, truncated);
        }
        private static byte[] prefix(JsonArray entries, int index, int count, boolean permitted, boolean truncated) {
            JsonObject message = new JsonObject(); message.addProperty("protocol", ClientSyncService.PROTOCOL);
            message.addProperty("type", CAPABILITY); message.addProperty("index", index); message.addProperty("count", count);
            message.addProperty("canDisguise", permitted); message.addProperty("truncated", truncated); message.add("models", entries);
            String json = message.toString();
            return (json.substring(0, json.length() - 1) + ",\"revision\":").getBytes(StandardCharsets.UTF_8);
        }
    }
}
