package com.simmc.meplayeractions.preferences;

import com.simmc.meplayeractions.protection.ResourceError;
import com.simmc.meplayeractions.protection.ResourceProtection;
import com.simmc.meplayeractions.protection.ResourceRejectedException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

public final class PlayerPreferences {
    private static final long FLUSH_DELAY = TimeUnit.SECONDS.toNanos(1);
    private static final long SHUTDOWN_WAIT = TimeUnit.MILLISECONDS.toNanos(250);
    private final Map<String, Boolean> values;
    private final Set<String> present;
    private final Map<String, Change> pending = new LinkedHashMap<>();
    private final Storage storage;
    private final LongSupplier clock;
    private final Logger logger;
    private final long sourceBytes;
    private ResourceProtection resources;
    private ResourceProtection.TaskHandle<Void> running;
    private Batch batch;
    private BukkitTask maintenance;
    private long sequence, firstDirty = -1, retryAt, failures, writes;
    private int consecutiveFailures;
    private boolean closed, retryScheduled;

    record Change(String path, Boolean value, long sequence, long changedAt) {
    }

    interface Storage {
        void save(List<Change> changes, ResourceProtection resources) throws IOException;
    }

    private static final class Batch {
        final List<Change> changes;
        final long sequence;
        volatile boolean saved;

        Batch(List<Change> changes) {
            this.changes = changes;
            this.sequence = changes.stream().mapToLong(Change::sequence).max().orElse(0);
        }
    }

    public PlayerPreferences(Path file, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        this.values = new HashMap<>();
        this.present = new HashSet<>(yaml.getKeys(true));
        for (Map.Entry<String, Object> entry : yaml.getValues(true).entrySet()) {
            if (entry.getValue() instanceof Boolean value) {
                this.values.put(entry.getKey(), value);
            }
        }
        this.storage = new YamlStorage(file.toAbsolutePath(), yaml);
        this.clock = System::nanoTime;
        this.logger = Objects.requireNonNull(logger);
        try {
            this.sourceBytes = Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取玩家同步偏好", failure);
        }
    }

    PlayerPreferences(Map<String, Boolean> initial, long sourceBytes, Storage storage, LongSupplier clock,
            Logger logger) {
        this.values = new HashMap<>(initial);
        this.present = new HashSet<>(initial.keySet());
        this.sourceBytes = sourceBytes;
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
        this.logger = Objects.requireNonNull(logger);
    }

    public void start(JavaPlugin plugin) {
        if (this.maintenance == null && !this.closed) {
            this.maintenance = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    public void runtime(ResourceProtection resources) {
        this.resources = Objects.requireNonNull(resources);
        resources.gauge("preferences.pending", () -> this.pending.size());
        resources.gauge("preferences.writing", () -> this.running != null && !this.running.workerExited() ? 1 : 0);
        resources.gauge("preferences.failures", () -> this.failures);
        resources.gauge("preferences.writes", () -> this.writes);
        resources.gauge("preferences.retryMillis", () -> this.retryScheduled
                ? Math.max(0, this.retryAt - this.clock.getAsLong()) / 1_000_000 : 0);
    }

    public boolean enabled(UUID player, String feature) {
        return this.values.getOrDefault(path(player, feature), true);
    }

    public boolean set(UUID player, String feature, Boolean value) {
        if (this.closed) {
            throw new IllegalStateException("玩家同步偏好服务已关闭");
        }
        String path = path(player, feature);
        if (value == null ? !this.present.contains(path) : Objects.equals(this.values.get(path), value)) {
            return false;
        }
        if (value == null) {
            this.values.remove(path);
            this.present.remove(path);
        } else {
            this.values.put(path, value);
            this.present.add(path);
        }
        if (this.pending.isEmpty()) {
            this.firstDirty = this.clock.getAsLong();
        }
        Change previous = this.pending.get(path);
        long changedAt = previous != null && (this.batch == null || previous.sequence() > this.batch.sequence)
                ? previous.changedAt() : this.clock.getAsLong();
        this.pending.put(path, new Change(path, value, ++this.sequence, changedAt));
        return true;
    }

    private static String path(UUID player, String feature) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(feature);
        if (feature.isBlank() || feature.indexOf('.') >= 0) {
            throw new IllegalArgumentException("无效玩家同步偏好字段");
        }
        return player + "." + feature;
    }

    public void tick() {
        if (this.closed) {
            return;
        }
        finish();
        long now = this.clock.getAsLong();
        if (this.running == null && !this.pending.isEmpty() && this.resources != null
                && now - this.firstDirty >= FLUSH_DELAY && (!this.retryScheduled || now - this.retryAt >= 0)) {
            flush();
        }
    }

    private boolean flush() {
        if (this.running != null || this.pending.isEmpty() || this.resources == null) {
            return false;
        }
        Batch next = new Batch(List.copyOf(this.pending.values()));
        ResourceProtection current = this.resources;
        long bytes = Math.max(4096, Math.min(Long.MAX_VALUE / 8, this.sourceBytes) * 8
                + (long) this.values.size() * 192 + (long) next.changes.size() * 128);
        ResourceProtection.TaskHandle<Void> task = current.submitTracked(null, bytes, "cleanup_preferences", () -> {
            this.storage.save(next.changes, current);
            next.saved = true;
            return null;
        }, ignored -> {
        }, ignored -> {
        });
        if (!task.accepted()) {
            failed(task.result().failure());
            return false;
        }
        this.batch = next;
        this.running = task;
        return true;
    }

    private void finish() {
        if (this.running == null || !this.running.workerExited()) {
            return;
        }
        ResourceProtection.TaskResult<Void> result = this.running.result();
        Batch finished = this.batch;
        this.running = null;
        this.batch = null;
        // A reload may cancel a callback after the atomic save has committed. Observe the actual
        // writer exit before admitting its successor, and retire only versions that really saved.
        if (finished.saved) {
            for (Change change : finished.changes) {
                this.pending.remove(change.path(), change);
            }
            this.writes++;
            this.consecutiveFailures = 0;
            this.retryAt = 0;
            this.retryScheduled = false;
            this.firstDirty = this.pending.values().stream().mapToLong(Change::changedAt).min().orElse(-1);
        } else if (result.canceled()) {
            this.retryAt = 0;
            this.retryScheduled = false;
        } else {
            failed(result.failure());
        }
    }

    private void failed(Throwable failure) {
        this.failures++;
        this.consecutiveFailures++;
        long seconds = Math.min(30, 5L << Math.min(3, this.consecutiveFailures - 1));
        this.retryAt = this.clock.getAsLong() + TimeUnit.SECONDS.toNanos(seconds);
        this.retryScheduled = true;
        String reason = failure instanceof ResourceRejectedException rejected ? rejected.error().code()
                : (failure == null ? "unknown" : failure.getClass().getSimpleName());
        this.logger.warning("玩家同步偏好尚未落盘，待保存=" + this.pending.size() + "，" + seconds + " 秒后重试，原因=" + reason);
    }

    public void shutdown() {
        if (this.closed) {
            return;
        }
        if (this.maintenance != null) {
            this.maintenance.cancel();
            this.maintenance = null;
        }
        long deadline = System.nanoTime() + SHUTDOWN_WAIT;
        finish();
        if (this.running == null && !this.pending.isEmpty()) {
            flush();
        }
        while (this.running != null && System.nanoTime() < deadline) {
            try {
                this.running.exited().toCompletableFuture().get(Math.max(1, deadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException failure) {
                break;
            }
            finish();
            if (this.running == null && !this.pending.isEmpty() && !this.retryScheduled
                    && System.nanoTime() < deadline) {
                flush();
            }
        }
        finish();
        this.closed = true;
        if (!this.pending.isEmpty()) {
            this.logger.warning("停服保存玩家同步偏好未在 250 毫秒内完成，最后 " + this.pending.size() + " 项变更可能未保存");
        }
        if (this.running != null) {
            this.running.cancel();
        }
        this.pending.clear();
    }

    private static final class YamlStorage implements Storage {
        private final Path file;
        private final YamlConfiguration yaml;
        private final UUID diskOwner = UUID.randomUUID();

        YamlStorage(Path file, YamlConfiguration yaml) {
            this.file = file;
            this.yaml = yaml;
        }

        public void save(List<Change> changes, ResourceProtection resources) throws IOException {
            ResourceProtection.checkCancelled();
            for (Change change : changes) {
                this.yaml.set(change.path(), change.value());
            }
            byte[] content = this.yaml.saveToString().getBytes(StandardCharsets.UTF_8);
            ResourceProtection.progress();
            ResourceError reservation = resources.reserveTemporary(this.diskOwner, content.length,
                    this.file.getParent());
            if (reservation != null) {
                throw new ResourceRejectedException(reservation);
            }
            Path temporary = null;
            try {
                Files.createDirectories(this.file.getParent());
                temporary = Files.createTempFile(this.file.getParent(), "players-", ".tmp");
                Files.write(temporary, content);
                ResourceProtection.progress();
                try {
                    Files.move(temporary, this.file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    if (temporary != null) {
                        Files.deleteIfExists(temporary);
                    }
                } finally {
                    resources.releaseTemporary(this.diskOwner, content.length);
                }
            }
        }
    }
}
