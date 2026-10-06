package com.simmc.meplayeractions.client.network;

import com.simmc.meplayeractions.client.model.BbModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Local preview inventory, independent of current server command or resource permissions. */
public final class ServerCachedModelCatalog {
    public record Model(String id, String label, String hash, boolean identified) { }
    private record Validation(ServerModelCache.Fingerprint fingerprint, boolean accepted) { }
    private final ServerModelCache cache;
    private final Predicate<byte[]> parser;
    // Keep only stat identities and outcomes, never every model's parsed geometry or raw bytes.
    private final Map<String,Validation> validated = new LinkedHashMap<>();

    public ServerCachedModelCatalog(ServerModelCache cache) { this(cache, bytes -> {
        try { BbModel.parse(bytes); return true; } catch (Exception rejected) { return false; }
    }); }
    ServerCachedModelCatalog(ServerModelCache cache, Predicate<byte[]> parser) {
        this.cache=Objects.requireNonNull(cache);this.parser=Objects.requireNonNull(parser);
    }
    public static List<Model> load(ServerModelCache cache) { return new ServerCachedModelCatalog(cache).load(); }

    /** Caller runs on a bounded worker; unchanged files reuse a bounded fingerprint validation outcome. */
    public synchronized List<Model> load() {
        var models = new ArrayList<Model>(); var hashes = new HashSet<String>();
        for (var entry : cache.galleryEntries()) {
            hashes.add(entry.hash()); var fingerprint=cache.fingerprint(entry.hash()).orElse(null);
            if(fingerprint==null)continue;
            Validation previous=validated.get(entry.hash()); boolean accepted;
            if(previous!=null && previous.fingerprint().equals(fingerprint))accepted=previous.accepted();
            else {
                var bytes=cache.inspectValidated(entry.hash()); accepted=bytes.isPresent() && parser.test(bytes.get());
                if(!Objects.equals(fingerprint,cache.fingerprint(entry.hash()).orElse(null))) {
                    validated.remove(entry.hash());continue;
                }
                validated.put(entry.hash(),new Validation(fingerprint,accepted));
            }
            if (accepted) models.add(new Model(entry.id(), entry.identified() ? entry.id() : "缓存模型 · " + entry.hash().substring(0, 12),
                    entry.hash(), entry.identified()));
        }
        validated.keySet().retainAll(hashes);
        while(validated.size()>ServerModelCache.MAX_FILES)validated.remove(validated.keySet().iterator().next());
        return List.copyOf(models);
    }
}
