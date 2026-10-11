package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.model.NativeModelBundle;
import com.simmc.meplayeractions.client.network.ServerModelCache;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Screen-owned inspection of already received private bundles. Never downloads, writes or authorizes display. */
final class PrivateSharedCacheLibrary implements AutoCloseable {
    static final int MAX_ENTRIES = 40;
    record Entry(String hash, int bytes, long cachedAt) { }
    private final ServerModelCache cache;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(12), runnable -> {
                var thread = new Thread(runnable, "MPA-private-cache-preview");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    PrivateSharedCacheLibrary(Path directory) { cache = new ServerModelCache(directory); }

    CompletableFuture<List<Entry>> scan() {
        return CompletableFuture.supplyAsync(() -> {
            var entries = new LinkedHashMap<String, Entry>();
            for (var candidate : cache.galleryEntries()) {
                if (entries.size() >= MAX_ENTRIES) break;
                cache.fingerprint(candidate.hash()).ifPresent(file -> entries.putIfAbsent(candidate.hash(),
                        new Entry(candidate.hash(), (int) file.size(), candidate.cachedAt())));
            }
            return List.copyOf(entries.values());
        }, worker);
    }

    CompletableFuture<LocalModelLibrary.Loaded> preview(String hash, String texture) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = cache.inspectValidated(hash).orElseThrow(() -> new IOException("本机缓存缺失或校验失败"));
                return NativeModelBundle.decode(bytes, texture);
            } catch (IOException | RuntimeException failure) { throw new CompletionException(failure); }
        }, worker);
    }

    @Override public void close() { worker.shutdownNow(); }
}
