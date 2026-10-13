package com.simmc.meplayeractions.client.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TexturePreparation implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("MEPlayerActions/TexturePreparation");
    public static final TexturePreparation SHARED = new TexturePreparation(8, 128L * 1024 * 1024);

    public interface Worker {
        Prepared prepare(Memory memory) throws Exception;
    }

    public interface Prepared extends AutoCloseable {
        Step upload() throws Exception;

        @Override
        void close();
    }

    public record Step(boolean uploaded, boolean complete) {
        public static final Step WAIT = new Step(false, false);
    }

    public interface Memory {
        void reserve(long bytes) throws InterruptedException;

        boolean cancelled();
    }

    private final int limit;
    private final long byteLimit;
    private final ThreadPoolExecutor executor;
    private final Map<Object, Job> jobs = new LinkedHashMap<>();
    private final Deque<Job> ready = new ArrayDeque<>();
    private long reservedBytes;
    private boolean closed;

    public TexturePreparation(int limit, long byteLimit) {
        this.limit = limit;
        this.byteLimit = byteLimit;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(limit),
                task -> {
                    Thread thread = new Thread(task, "MPA-texture-preparation");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized boolean submit(Object key, BooleanSupplier current, Worker worker,
            BiConsumer<Boolean, String> completion) {
        if (this.closed || this.jobs.containsKey(key) || this.jobs.size() >= this.limit) {
            return false;
        }
        Job job = new Job(key, current, worker, completion);
        this.jobs.put(key, job);
        try {
            this.executor.execute(job);
        } catch (RuntimeException rejected) {
            this.jobs.remove(key, job);
            return false;
        }
        return true;
    }

    public synchronized void cancel(Object key) {
        Job job = this.jobs.get(key);
        if (job == null) {
            return;
        }
        job.cancelled = true;
        if (this.executor.remove(job)) {
            this.ready.addLast(job);
        }
        notifyAll();
    }

    public void pump() {
        ArrayList<Job> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(this.jobs.values());
        }
        for (Job job : snapshot) {
            try {
                if (!job.cancelled && !job.current.getAsBoolean()) {
                    cancel(job.key);
                }
            } catch (RuntimeException failure) {
                cancel(job.key);
                LOGGER.warn("Texture identity check failed", failure);
            }
        }
        // A waiting asset does not consume the tick's single GPU submission allowance.
        long deadline = System.nanoTime() + 1_000_000L;
        int attempts;
        synchronized (this) {
            attempts = this.ready.size();
        }
        for (int attempt = 0; attempt < attempts; attempt++) {
            Job job;
            synchronized (this) {
                job = this.ready.pollFirst();
            }
            if (job == null) {
                break;
            }
            boolean done = job.cancelled || job.failure != null;
            boolean uploaded = false;
            if (!done) {
                try {
                    Step step = job.prepared.upload();
                    uploaded = step.uploaded();
                    done = step.complete();
                } catch (Throwable failure) {
                    uploaded = true;
                    job.failure = new IllegalStateException("upload: " + failure, failure);
                    done = true;
                }
            }
            if (done) {
                finish(job);
            } else {
                synchronized (this) {
                    this.ready.addLast(job);
                }
            }
            if (uploaded || System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    private void finish(Job job) {
        try {
            if (job.prepared != null) {
                job.prepared.close();
            }
        } catch (Throwable failure) {
            job.failure = failure;
        } finally {
            synchronized (this) {
                this.jobs.remove(job.key, job);
                this.reservedBytes -= job.bytes;
                job.bytes = 0;
                notifyAll();
            }
        }
        try {
            job.completion.accept(!job.cancelled && job.failure == null,
                    job.cancelled ? "cancelled" : (job.failure == null ? "" : job.failure.toString()));
        } catch (RuntimeException failure) {
            LOGGER.warn("Texture completion callback failed", failure);
        }
    }

    public synchronized Map<String, Object> diagnostics() {
        return Map.of("requests", this.jobs.size(), "ready", this.ready.size(), "reservedBytes", this.reservedBytes,
                "requestLimit", this.limit, "byteLimit", this.byteLimit);
    }

    @Override
    public void close() {
        ArrayList<Object> keys;
        synchronized (this) {
            this.closed = true;
            keys = new ArrayList<>(this.jobs.keySet());
        }
        keys.forEach(this::cancel);
        this.executor.shutdown();
        while (true) {
            Job job;
            synchronized (this) {
                job = this.ready.pollFirst();
            }
            if (job == null) {
                break;
            }
            finish(job);
        }
    }

    private final class Job implements Runnable, Memory {
        private final Object key;
        private final BooleanSupplier current;
        private final Worker worker;
        private final BiConsumer<Boolean, String> completion;
        private volatile boolean cancelled;
        private long bytes;
        private Prepared prepared;
        private Throwable failure;

        private Job(Object key, BooleanSupplier current, Worker worker, BiConsumer<Boolean, String> completion) {
            this.key = key;
            this.current = current;
            this.worker = worker;
            this.completion = completion;
        }

        @Override
        public void run() {
            try {
                if (!this.cancelled) {
                    this.prepared = this.worker.prepare(this);
                    if (this.prepared == null) {
                        throw new IllegalStateException("Texture worker returned no prepared result");
                    }
                }
            } catch (Throwable failure) {
                this.failure = new IllegalStateException((failure instanceof Error ? "resource: " : "decode: ")
                        + failure, failure);
            } finally {
                // A cancelled running task remains counted until the worker actually returns.
                boolean abandoned;
                synchronized (TexturePreparation.this) {
                    abandoned = TexturePreparation.this.closed;
                    if (!abandoned) {
                        TexturePreparation.this.ready.addLast(this);
                    }
                    TexturePreparation.this.notifyAll();
                }
                if (abandoned) {
                    try {
                        if (this.prepared != null) {
                            this.prepared.close();
                        }
                    } catch (Throwable failure) {
                        LOGGER.warn("Cannot release abandoned texture pixels", failure);
                    } finally {
                        synchronized (TexturePreparation.this) {
                            TexturePreparation.this.jobs.remove(this.key, this);
                            TexturePreparation.this.reservedBytes -= this.bytes;
                            this.bytes = 0;
                            TexturePreparation.this.notifyAll();
                        }
                    }
                }
            }
        }

        @Override
        public void reserve(long bytes) throws InterruptedException {
            synchronized (TexturePreparation.this) {
                if (bytes < 0 || bytes > TexturePreparation.this.byteLimit || this.bytes != 0) {
                    throw new IllegalArgumentException("Texture working memory exceeds budget");
                }
                while (!this.cancelled && TexturePreparation.this.reservedBytes + bytes > TexturePreparation.this.byteLimit) {
                    TexturePreparation.this.wait();
                }
                if (this.cancelled) {
                    throw new InterruptedException("Texture preparation cancelled");
                }
                this.bytes = bytes;
                TexturePreparation.this.reservedBytes += bytes;
            }
        }

        @Override
        public boolean cancelled() {
            return this.cancelled;
        }
    }
}
