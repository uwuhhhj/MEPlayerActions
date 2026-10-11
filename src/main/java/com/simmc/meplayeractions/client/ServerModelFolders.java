package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.protection.ResourceProtection;
import org.bukkit.plugin.Plugin;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Shared, filename-only directory facts. No model body is opened and no transfer is authorized here. */
final class ServerModelFolders implements AutoCloseable {
    // Two bounded 4096-file indexes, their paths and grouping maps, plus the immutable result.
    private static final long RESERVED_BYTES = 16L * 1024 * 1024;
    private final Path own, engine;
    private final Scheduler scheduler;
    private final Consumer<ServerModelCatalog.MetadataSnapshot> publicationChanged;
    private ServerModelCatalog.MetadataSnapshot snapshot = new ServerModelCatalog.MetadataSnapshot(Map.of(), false);
    private boolean pending, initialized, closed;
    private long lastRefresh;

    ServerModelFolders(Plugin plugin, ResourceProtection protection) {
        this(plugin, protection, ignored -> {});
    }

    ServerModelFolders(Plugin plugin, ResourceProtection protection, Consumer<ServerModelCatalog.MetadataSnapshot> publicationChanged) {
        this(plugin.getDataFolder().toPath().resolve("models"), engineRoot(plugin),
                (work, success, failure) -> protection.submit(null, RESERVED_BYTES, "server_model_folders", work,
                        success, ignored -> failure.run()), publicationChanged);
    }

    ServerModelFolders(Path own, Path engine, Scheduler scheduler) {
        this(own, engine, scheduler, ignored -> {});
    }

    ServerModelFolders(Path own, Path engine, Scheduler scheduler, Consumer<ServerModelCatalog.MetadataSnapshot> publicationChanged) {
        this.own = Objects.requireNonNull(own); this.engine = engine; this.scheduler = Objects.requireNonNull(scheduler);
        this.publicationChanged = Objects.requireNonNull(publicationChanged);
    }

    private static Path engineRoot(Plugin plugin) {
        Plugin engine = plugin.getServer().getPluginManager().getPlugin("ModelEngine");
        return engine == null ? null : engine.getDataFolder().toPath().resolve("blueprints");
    }

    /** Scheduling is cheap and shared across viewers; filesystem calls execute only inside the bounded worker. */
    void refresh(long tick) {
        if (closed || pending || initialized && !ClientSyncCadence.due(tick, lastRefresh, ServerModelCatalog.REFRESH_TICKS)) return;
        initialized = true; pending = true; lastRefresh = tick;
        try {
            scheduler.submit(() -> scan(own, engine), result -> {
                pending = false;
                if (closed || snapshot.equals(result)) return;
                snapshot = result;
                // ResourceProtection delivers this callback on the main thread. Only a complete
                // directory snapshot can withdraw publication; unknown or failed scans cannot.
                if (result.ownComplete()) publicationChanged.accept(result);
            }, () -> {
                pending = false;
                // A failed refresh cannot keep promising that old files are still published.
                if (!closed) snapshot = new ServerModelCatalog.MetadataSnapshot(Map.of(), false);
            });
        } catch (RuntimeException stopped) {
            pending = false;
            if (!closed) snapshot = new ServerModelCatalog.MetadataSnapshot(Map.of(), false);
        }
    }

    ServerModelCatalog.MetadataSnapshot snapshot() { return snapshot; }
    @Override public void close() { closed = true; }

    static ServerModelCatalog.MetadataSnapshot scan(Path own, Path engine) throws IOException {
        ResourceProtection.checkCancelled();
        ExplicitModelFiles.Catalog owned = readDirectory(own);
        ResourceProtection.progress();
        ExplicitModelFiles.Catalog blueprints = engine == null
                ? new ExplicitModelFiles.Catalog(List.of(), "ready", "No ModelEngine directory")
                : readDirectory(engine);
        ResourceProtection.progress();
        return classify(owned, blueprints);
    }

    private static ExplicitModelFiles.Catalog readDirectory(Path root) {
        try { return ExplicitModelFiles.scan(root); }
        catch (IOException failure) {
            ResourceProtection.checkCancelled();
            return new ExplicitModelFiles.Catalog(List.of(), "invalid", "模型目录暂时无法读取");
        }
    }

    static ServerModelCatalog.MetadataSnapshot classify(ExplicitModelFiles.Catalog owned, ExplicitModelFiles.Catalog blueprints) {
        Map<String, List<ExplicitModelFiles.Entry>> ownGroups = grouped(owned), engineGroups = grouped(blueprints);
        boolean ownComplete = owned.state().equals("ready");
        Set<String> ids = new TreeSet<>(ownGroups.keySet()); ids.addAll(engineGroups.keySet());
        var result = new LinkedHashMap<String, ServerModelCatalog.Metadata>();
        for (String id : ids) {
            ResourceProtection.checkCancelled();
            ExplicitModelFiles.Catalog ownMatches = new ExplicitModelFiles.Catalog(ownGroups.getOrDefault(id, List.of()), owned.state(), owned.reason());
            ExplicitModelFiles.Selection selected = ownMatches.select(id);
            if (selected.found()) {
                result.put(id, new ServerModelCatalog.Metadata("own", selectedFolder(ownMatches, selected), true));
            } else if (ownComplete && selected.state().equals("missing")) {
                ExplicitModelFiles.Catalog engineMatches = new ExplicitModelFiles.Catalog(engineGroups.getOrDefault(id, List.of()), blueprints.state(), blueprints.reason());
                ExplicitModelFiles.Selection engineFile = engineMatches.select(id);
                if (engineFile.found()) result.put(id,
                        new ServerModelCatalog.Metadata("modelengine", selectedFolder(engineMatches, engineFile), false));
            }
            // Missing/ambiguous/failed lookups use the snapshot's explicit-false or unknown default.
        }
        return new ServerModelCatalog.MetadataSnapshot(result, ownComplete);
    }

    private static Map<String, List<ExplicitModelFiles.Entry>> grouped(ExplicitModelFiles.Catalog catalog) {
        var result = new HashMap<String, List<ExplicitModelFiles.Entry>>();
        for (ExplicitModelFiles.Entry entry : catalog.entries()) result.computeIfAbsent(entry.modelId(), ignored -> new ArrayList<>()).add(entry);
        return result;
    }

    private static String selectedFolder(ExplicitModelFiles.Catalog catalog, ExplicitModelFiles.Selection selected) {
        return catalog.entries().stream().filter(entry -> entry.path().equals(selected.path())).findFirst().orElseThrow().folder();
    }

    interface Scheduler {
        void submit(Callable<ServerModelCatalog.MetadataSnapshot> work, Consumer<ServerModelCatalog.MetadataSnapshot> success, Runnable failure);
    }
}
