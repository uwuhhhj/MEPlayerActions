package com.simmc.meplayeractions.client;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class SourceBundleCache {
    @FunctionalInterface
    interface Loader {
        void load(long generation) throws IOException;
    }

    private final Map<String, byte[]> bundles = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private long generation;

    synchronized long generation() {
        return this.generation;
    }

    synchronized boolean contains(String id, long expected) {
        return expected == this.generation && (this.bundles.containsKey(id) || this.errors.containsKey(id));
    }

    byte[] bundle(String id, Loader loader) throws IOException {
        long expected;
        byte[] bytes;
        synchronized (this) {
            expected = this.generation;
            bytes = read(id, expected);
        }
        if (bytes == null) {
            loader.load(expected);
            synchronized (this) {
                bytes = read(id, expected);
            }
        }
        if (bytes == null) {
            throw new IOException("模型完整原始资产不可用");
        }
        // Published arrays are owned by the cache; copying never holds the refresh lock.
        byte[] copy = bytes.clone();
        synchronized (this) {
            if (expected != this.generation) {
                throw new IOException("模型源已刷新，请重试");
            }
        }
        return copy;
    }

    private byte[] read(String id, long expected) throws IOException {
        if (expected != this.generation) {
            throw new IOException("模型源已刷新，请重试");
        }
        byte[] bytes = this.bundles.get(id);
        if (bytes == null && this.errors.containsKey(id)) {
            throw new IOException(this.errors.get(id));
        }
        return bytes;
    }

    synchronized void remember(String id, byte[] bytes, long expected) {
        if (expected != this.generation || this.bundles.containsKey(id)) {
            return;
        }
        this.errors.remove(id);
        this.bundles.put(id, bytes);
        while (this.bundles.size() > 16) {
            this.bundles.remove(this.bundles.keySet().iterator().next());
        }
    }

    synchronized void error(String id, String error, long expected) {
        if (expected != this.generation || this.bundles.containsKey(id)) {
            return;
        }
        this.errors.put(id, error);
        while (this.errors.size() > 16) {
            this.errors.remove(this.errors.keySet().iterator().next());
        }
    }

    synchronized void clear() {
        this.generation++;
        this.bundles.clear();
        this.errors.clear();
    }
}
