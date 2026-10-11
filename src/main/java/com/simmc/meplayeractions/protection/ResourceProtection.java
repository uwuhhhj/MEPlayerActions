package com.simmc.meplayeractions.protection;

import com.simmc.meplayeractions.config.ModelComplexityLimits;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** One bounded cosmetic-resource runtime shared by both server rendering paths. */
public final class ResourceProtection implements AutoCloseable
{
    public enum State
    {
        NORMAL, THROTTLED, PROTECTED, EMERGENCY
    }

    private static final long TICK_NANOS = 50_000_000L;
    private static final ThreadLocal<Job<?>> CURRENT = new ThreadLocal<>();
    private final Object lock = new Object();
    private final JavaPlugin plugin;
    private final ResourceSettings settings;
    private final LongSupplier clock;
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private final Set<Job<?>> jobs = new LinkedHashSet<>();
    private final Deque<Completion> completions = new ArrayDeque<>();
    private final Map<UUID, Owner> owners = new HashMap<>();
    private final Map<String, Long> rejections = new LinkedHashMap<>();
    private final Map<String, LongSupplier> gauges = new LinkedHashMap<>();
    private final Map<RelationKey, LinkedHashSet<UUID>> relations = new HashMap<>();
    private final Map<UUID, Integer> viewerRelations = new HashMap<>();
    private final Map<UUID, Long> lastDisguise = new HashMap<>();
    private final Map<UUID, Long> temporaryOwners = new HashMap<>();
    private final Map<CacheKey, Long> cacheReservations = new HashMap<>();
    private long reservedBytes, completionReservedBytes, temporaryBytes, completed, timeouts, canceled;
    private int completionReservations, running, peakJobs, peakCompletions, peakRelations, totalRelations;
    private long peakReservedBytes, tickWorkNanos, healthySince = -1, lastWarning = Long.MIN_VALUE, disguiseWindow;
    private long cachedBytes, peakCachedBytes;
    private int tickOperations, disguiseCount;
    private long lastCooldownPrune;
    private volatile State state = State.NORMAL;
    private volatile String reason = "healthy";
    private volatile boolean closed;
    private double tps = 20, mspt, pluginMillis;
    private BukkitTask maintenance;

    public ResourceProtection(JavaPlugin plugin, ResourceSettings settings)
    {
        this(plugin, settings, System::nanoTime, plugin.getLogger());
    }

    ResourceProtection(ResourceSettings settings, LongSupplier clock)
    {
        this(null, settings, clock, Logger.getLogger("MEPlayerActions.ResourceProtection.Tests"));
    }

    private ResourceProtection(JavaPlugin plugin, ResourceSettings settings, LongSupplier clock, Logger logger)
    {
        this.plugin = plugin;
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.logger = logger;
        ThreadFactory factory = new ThreadFactory()
        {
            private int number;

            public Thread newThread(Runnable work)
            {
                Thread thread = new Thread(work, "MPA-resource-" + (++this.number));
                thread.setDaemon(true);
                return thread;
            }
        };
        this.executor = new ThreadPoolExecutor(settings.tasks().workerThreads(), settings.tasks().workerThreads(), 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.tasks().queueCapacity()), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void start()
    {
        if (this.plugin == null || this.maintenance != null || this.closed)
        {
            return;
        }

        this.maintenance = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, this::tick, 1, 1);
    }

    public ResourceSettings settings()
    {
        return this.settings;
    }

    public ModelComplexityLimits complexityLimits()
    {
        return this.settings.complexity();
    }

    public State state()
    {
        return this.state;
    }

    public boolean acceptingNewWork()
    {
        return !this.closed && this.state.ordinal() < State.PROTECTED.ordinal();
    }

    public boolean shouldStartTransfer()
    {
        return acceptingNewWork();
    }

    public double throttleFactor()
    {
        return this.state == State.NORMAL ? 1 : (this.state == State.THROTTLED ? .5 : 0);
    }

    public boolean tryWork()
    {
        return tryMainWork(1);
    }

    public boolean tryMainWork(int operations)
    {
        if (operations < 1)
        {
            throw new IllegalArgumentException("operations must be positive");
        }

        synchronized (this.lock)
        {
            int limit = this.settings.protection().mainWorkPerTick();
            if (this.state != State.NORMAL)
            {
                limit = Math.max(1, limit / (this.state == State.EMERGENCY ? 4 : 2));
            }

            if (this.closed || this.tickOperations > limit - operations
                    || this.tickWorkNanos >= this.settings.protection().mainBudgetMillis() * 1_000_000)
            {
                return false;
            }

            this.tickOperations += operations;
            return true;
        }
    }

    public void recordWork(long nanos)
    {
        if (nanos > 0)
        {
            synchronized (this.lock)
            {
                this.tickWorkNanos = Math.min(Long.MAX_VALUE - nanos, this.tickWorkNanos) + nanos;
            }
        }
    }

    /** A false return has already delivered its structured rejection on the caller thread. */
    public <T> boolean submit(UUID owner, long bytes, String stage, Callable<T> work, Consumer<T> success,
            Consumer<ResourceError> failure)
    {
        Objects.requireNonNull(work);
        Objects.requireNonNull(success);
        Objects.requireNonNull(failure);
        new ResourceError("server_busy", stage, true, 100);
        if (bytes < 0)
        {
            throw new IllegalArgumentException("negative reservation");
        }

        Job<T> job;
        ResourceError error;
        synchronized (this.lock)
        {
            error = admission(owner, bytes, stage);
            if (error == null)
            {
                job = new Job<>(owner, bytes, stage, work, success, failure, this.clock.getAsLong());
                this.jobs.add(job);
                this.reservedBytes += bytes;
                this.completionReservedBytes += bytes;
                this.completionReservations++;
                if (owner != null)
                {
                    Owner o = this.owners.computeIfAbsent(owner, ignored -> new Owner());
                    o.jobs++;
                    o.bytes += bytes;
                }
                this.peakJobs = Math.max(this.peakJobs, this.jobs.size());
                this.peakReservedBytes = Math.max(this.peakReservedBytes, this.reservedBytes);
                try
                {
                    this.executor.execute(job);
                    return true;
                }
                catch (RejectedExecutionException rejected)
                {
                    releaseWork(job);
                    releaseCompletion(job);
                    error = ResourceError.busy(stage);
                }
            }
            rejectLocked(error.code());
        }
        failure.accept(error);
        return false;
    }

    private ResourceError admission(UUID owner, long bytes, String stage)
    {
        ResourceSettings.Tasks t = this.settings.tasks();
        if (this.closed)
        {
            return new ResourceError("server_stopped", stage, false, 0);
        }

        if (!acceptingNewWork() && !cleanup(stage))
        {
            return ResourceError.protectedState(stage);
        }

        if (this.jobs.size() >= t.workerThreads() + t.queueCapacity()
                || this.completionReservations >= t.pendingCompletions())
        {
            return ResourceError.busy(stage);
        }

        if (bytes > t.maxReservedBytes() - this.reservedBytes
                || bytes > t.maxPendingCompletionBytes() - this.completionReservedBytes)

        {
            return new ResourceError("memory_limit", stage, true, 100);
        }

        Owner o = owner == null ? null : this.owners.get(owner);
        if (owner != null && (bytes > t.perPlayerReservedBytes() - (o == null ? 0 : o.bytes)
                || o != null && o.jobs >= t.jobsPerPlayer()))
        {
            return new ResourceError("player_task_limit", stage, true, 100);
        }

        return null;
    }

    private static boolean cleanup(String stage)
    {
        return stage.equals("cleanup") || stage.startsWith("cleanup_") || stage.equals("catalog_delete");
    }

    public static void checkCancelled()
    {
        Job<?> job = CURRENT.get();
        if (Thread.currentThread().isInterrupted() || job != null && job.canceled)
        {
            throw new CancellationException("Resource work canceled");
        }

    }

    public static void progress()
    {
        Job<?> job = CURRENT.get();
        if (job != null)
        {
            job.lastProgress = job.runtime.clock.getAsLong();
        }
        checkCancelled();
    }

    public void cancelOwner(UUID owner)
    {
        synchronized (this.lock)
        {
            for (Job<?> job : List.copyOf(this.jobs))
            {
                if (Objects.equals(owner, job.owner))
                {
                    cancel(job, new ResourceError("request_cancelled", job.stage, false, 0));
                }

            }

            // A finished worker may still have a retained result waiting for its main-thread callback.
            // Replace stale results with failure bookkeeping rather than dropping the callback:
            // callers may have reserved publication/delete slots independently of this runtime.
            Deque<Completion> replacement = new ArrayDeque<>(this.completions.size());
            for (Completion completion : this.completions)
            {
                if (Objects.equals(owner, completion.job.owner))
                {
                    Job<?> job = completion.job;
                    if (!job.canceled)
                    {
                        this.canceled++;
                    }
                    job.canceled = true;
                    ResourceError error = new ResourceError("request_cancelled", job.stage, false, 0);
                    replacement.addLast(new Completion(job, () -> job.failure.accept(error), false));
                }
                else
                {
                    replacement.addLast(completion);
                }

            }
            this.completions.clear();
            this.completions.addAll(replacement);
            this.lastDisguise.remove(owner);
        }
    }

    private void cancel(Job<?> job, ResourceError error)
    {
        if (job.canceled || job.workerFinished)
        {
            return;
        }

        job.canceled = true;
        this.canceled++;
        if (error.code().endsWith("timeout"))
        {
            this.timeouts++;
        }

        deliver(job, () -> job.failure.accept(error));
        if (this.executor.remove(job))
        {
            job.workerFinished = true;
            releaseWork(job);
        }
        else if (job.thread != null)
        {
            job.thread.interrupt();
        }

    }

    private void deliver(Job<?> job, Runnable callback)
    {
        deliver(job, callback, false);
    }

    private void deliver(Job<?> job, Runnable callback, boolean success)
    {
        if (job.delivered)
        {
            return;
        }

        job.delivered = true;
        if (this.closed)
        {
            releaseCompletion(job);
            return;
        }
        this.completions.addLast(new Completion(job, callback, success));
        this.peakCompletions = Math.max(this.peakCompletions, this.completions.size());
    }

    private void releaseWork(Job<?> job)
    {
        if (!this.jobs.remove(job))
        {
            return;
        }

        this.reservedBytes -= job.bytes;
        if (job.owner != null)
        {
            Owner o = this.owners.get(job.owner);
            if (o != null)
            {
                o.jobs--;
                o.bytes -= job.bytes;
                if (o.jobs == 0)
                {
                    this.owners.remove(job.owner);
                }
            }
        }
    }

    private void releaseCompletion(Job<?> job)
    {
        if (job.completionReleased)
        {
            return;
        }

        job.completionReleased = true;
        this.completionReservations--;
        this.completionReservedBytes -= job.bytes;
    }

    private final class Job<T> implements Runnable
    {
        final ResourceProtection runtime = ResourceProtection.this;
        final UUID owner;
        final long bytes;
        final String stage;
        final Callable<T> work;
        final Consumer<T> success;
        final Consumer<ResourceError> failure;
        final long queuedAt;
        volatile long startedAt, lastProgress;
        volatile Thread thread;
        volatile boolean canceled;
        boolean workerFinished, delivered, completionReleased;

        Job(UUID owner, long bytes, String stage, Callable<T> work, Consumer<T> success,
                Consumer<ResourceError> failure, long now)
        {
            this.owner = owner;
            this.bytes = bytes;
            this.stage = stage;
            this.work = work;
            this.success = success;
            this.failure = failure;
            this.queuedAt = now;
            this.lastProgress = now;
        }

        public void run()
        {
            synchronized (ResourceProtection.this.lock)
            {
                if (this.canceled || ResourceProtection.this.closed)
                {
                    this.workerFinished = true;
                    releaseWork(this);
                    return;
                }
                this.thread = Thread.currentThread();
                this.startedAt = ResourceProtection.this.clock.getAsLong();
                this.lastProgress = this.startedAt;
                ResourceProtection.this.running++;
            }
            CURRENT.set(this);
            T value = null;
            Throwable problem = null;
            try
            {
                checkCancelled();
                value = this.work.call();
                checkCancelled();
            }
            catch (Throwable failure)
            {
                problem = failure;
            }
            finally
            {
                CURRENT.remove();
            }
            final T result = value;
            final Throwable failed = problem;
            synchronized (ResourceProtection.this.lock)
            {
                ResourceProtection.this.running--;
                this.workerFinished = true;
                this.thread = null;
                releaseWork(this);
                if (!this.canceled && !ResourceProtection.this.closed)
                {
                    if (failed == null)
                    {
                        deliver(this, () ->
                        {
                            if (!this.canceled)
                            {
                                this.success.accept(result);
                            }
                        }, true);
                    }

                    else
                    {
                        ResourceError error = failed instanceof ResourceRejectedException rejected ? rejected.error()
                                : new ResourceError(failed instanceof CancellationException
                                        || failed instanceof InterruptedException ? "request_cancelled"
                                                : "validation_failed",
                                        this.stage, false, 0);
                        rejectLocked(error.code());
                        deliver(this, () -> this.failure.accept(error));
                    }
                }
                else if (!this.delivered)
                {
                    releaseCompletion(this);
                }

            }
        }
    }

    private record Completion(Job<?> job, Runnable callback, boolean success)
    {
    }

    private static final class Owner
    {
        int jobs;
        long bytes;
    }

    /** Main-thread cadence; resource deadlines use elapsed time even when server TPS is low. */
    public void tick()
    {
        long now = this.clock.getAsLong();
        synchronized (this.lock)
        {
            this.pluginMillis = this.pluginMillis * .8 + this.tickWorkNanos / 1_000_000d * .2;
            this.tickWorkNanos = 0;
            this.tickOperations = 0;
            if (now - this.lastCooldownPrune >= 1_000_000_000L)
            {
                this.lastCooldownPrune = now;
                long ttl = this.settings.models().disguiseCooldownTicks() * TICK_NANOS;
                this.lastDisguise.entrySet().removeIf(entry -> now - entry.getValue() >= ttl);
            }
            for (Job<?> job : List.copyOf(this.jobs))
            {
                if (job.canceled)
                {
                    continue;
                }

                if (job.thread == null && now - job.queuedAt >= this.settings.tasks().queueTimeoutTicks() * TICK_NANOS)

                {
                    cancel(job, new ResourceError("queue_timeout", job.stage, true, 100));
                }

                else if (job.thread != null
                        && (now - job.startedAt >= this.settings.tasks().taskTimeoutTicks() * TICK_NANOS
                                || now - job.lastProgress >= this.settings.tasks().taskIdleTicks() * TICK_NANOS))

                {
                    cancel(job, new ResourceError("validation_timeout", job.stage, true, 100));
                }

            }
        }
        if (this.plugin != null)
        {
            double[] recent = this.plugin.getServer().getTPS();
            sample(recent.length == 0 ? 20 : recent[0], this.plugin.getServer().getAverageTickTime(), now);
        }
        drainCompletions();
    }

    void drainCompletions()
    {
        long started = this.clock.getAsLong();
        int processed = 0;
        while (processed < this.settings.tasks().callbacksPerTick())
        {
            Completion c;
            synchronized (this.lock)
            {
                c = this.completions.pollFirst();
            }
            if (c == null)
            {
                break;
            }

            try
            {
                c.callback.run();
            }
            catch (RuntimeException failure)
            {
                ResourceError error = failure instanceof ResourceRejectedException rejected ? rejected.error()
                        : new ResourceError("commit_failed", c.job.stage, false, 0);
                reject(error.code());
                if (c.success)
                {
                    try
                    {
                        c.job.failure.accept(error);
                    }
                    catch (RuntimeException ignored)
                    {
                        reject("completion_failed");
                    }
                }

            }
            finally
            {
                synchronized (this.lock)
                {
                    this.completed++;
                    releaseCompletion(c.job);
                }
            }
            processed++;
            if (this.clock.getAsLong() - started >= this.settings.protection().mainBudgetMillis() * 1_000_000)
            {
                break;
            }

        }
        recordWork(Math.max(0, this.clock.getAsLong() - started));
    }

    /** Downgrades immediately; recovery advances one state after a continuously healthy interval. */
    public void sample(double currentTps, double currentMspt, long now)
    {
        synchronized (this.lock)
        {
            this.tps = Double.isFinite(currentTps) ? Math.max(0, Math.min(20, currentTps)) : 0;
            this.mspt = Double.isFinite(currentMspt) ? Math.max(0, currentMspt) : Double.MAX_VALUE;
            ResourceSettings.Protection p = this.settings.protection();
            if (!p.enabled())
            {
                change(State.NORMAL, "disabled", now);
                return;
            }
            State desired = State.NORMAL;
            String why = "healthy";
            if (this.tps <= p.emergencyTps() || this.mspt >= p.emergencyMspt()
                    || this.pluginMillis >= 6 * p.mainBudgetMillis())
            {
                desired = State.EMERGENCY;
                why = this.pluginMillis >= 6 * p.mainBudgetMillis() ? "plugin_work_emergency" : "server_tick_emergency";
            }
            else if (this.tps <= p.protectedTps() || this.mspt >= p.protectedMspt()
                    || this.pluginMillis >= 3 * p.mainBudgetMillis())
            {
                desired = State.PROTECTED;
                why = this.pluginMillis >= 3 * p.mainBudgetMillis() ? "plugin_work_protection"
                        : "server_tick_protection";
            }
            else if (this.tps <= p.throttledTps() || this.mspt >= p.throttledMspt())
            {
                desired = State.THROTTLED;
                why = "server_tick_throttled";
            }
            else if (this.pluginMillis >= p.mainBudgetMillis()
                    || this.reservedBytes >= this.settings.tasks().maxReservedBytes() * 9 / 10
                    || this.jobs.size() >= Math.max(1,
                            (this.settings.tasks().workerThreads() + this.settings.tasks().queueCapacity()) * 9 / 10))
            {
                desired = State.THROTTLED;
                why = "plugin_resource_pressure";
            }
            if (desired.ordinal() > this.state.ordinal())
            {
                this.healthySince = -1;
                change(desired, why, now);
            }
            else if (desired == State.NORMAL && this.tps >= p.recoveryTps() && this.mspt <= p.recoveryMspt())
            {
                if (this.state == State.NORMAL)
                {
                    this.healthySince = -1;
                }

                else if (this.healthySince < 0)
                {
                    this.healthySince = now;
                }

                else if (now - this.healthySince >= p.recoveryTicks() * TICK_NANOS)
                {
                    this.healthySince = now;
                    change(State.values()[this.state.ordinal() - 1], "healthy_recovery", now);
                }
            }
            else
            {
                this.healthySince = -1;
            }

        }
    }

    private void change(State next, String why, long now)
    {
        if (this.state == next)
        {
            this.reason = why;
            return;
        }
        this.state = next;
        this.reason = why;
        int workers = next == State.NORMAL ? this.settings.tasks().workerThreads()
                : Math.max(1, this.settings.tasks().workerThreads() / 2);
        if (workers < this.executor.getCorePoolSize())
        {
            this.executor.setCorePoolSize(workers);
            this.executor.setMaximumPoolSize(workers);
        }
        else
        {
            this.executor.setMaximumPoolSize(workers);
            this.executor.setCorePoolSize(workers);
        }
        if (next == State.EMERGENCY)
        {
            for (Job<?> job : List.copyOf(this.jobs))

            {
                if (job.thread == null && !cleanup(job.stage))
                {
                    cancel(job, ResourceError.protectedState(job.stage));
                }

            }

        }

        if (this.plugin != null && (this.lastWarning == Long.MIN_VALUE || now - this.lastWarning >= 30_000_000_000L))
        {
            this.logger.warning("资源保护状态=" + next + " 原因=" + why + "；新模型工作将按状态限流，已有授权与清理仍继续");
            this.lastWarning = now;
        }
    }

    public ResourceError tryDisguise(UUID owner)
    {
        Objects.requireNonNull(owner);
        synchronized (this.lock)
        {
            if (!acceptingNewWork())
            {
                rejectLocked("tps_protection");
                return ResourceError.protectedState("disguise");
            }
            long now = this.clock.getAsLong();
            Long previous = this.lastDisguise.get(owner);
            if (previous != null && now - previous < this.settings.models().disguiseCooldownTicks() * TICK_NANOS)
            {
                rejectLocked("disguise_cooldown");
                return new ResourceError("disguise_cooldown", "disguise", true,
                        this.settings.models().disguiseCooldownTicks());
            }
            if (now - this.disguiseWindow >= 1_000_000_000L)
            {
                this.disguiseWindow = now;
                this.disguiseCount = 0;
            }
            int max = this.state == State.THROTTLED ? Math.max(1, this.settings.models().newDisguisesPerSecond() / 2)
                    : this.settings.models().newDisguisesPerSecond();
            if (this.disguiseCount >= max)
            {
                rejectLocked("disguise_rate_limit");
                return new ResourceError("disguise_rate_limit", "disguise", true, 20);
            }
            if (this.lastDisguise.size() >= 65536 && !this.lastDisguise.containsKey(owner))
            {
                rejectLocked("disguise_rate_limit");
                return new ResourceError("disguise_rate_limit", "disguise", true, 20);
            }
            this.lastDisguise.put(owner, now);
            this.disguiseCount++;
            return null;
        }
    }

    public Set<UUID> reserveRelationships(String path, UUID owner, Collection<UUID> requested)
    {
        Objects.requireNonNull(path);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(requested);
        synchronized (this.lock)
        {
            RelationKey key = new RelationKey(path, owner);
            LinkedHashSet<UUID> old = this.relations.getOrDefault(key, new LinkedHashSet<>());
            LinkedHashSet<UUID> wanted = new LinkedHashSet<>(requested), granted = new LinkedHashSet<>();
            for (UUID viewer : old)
            {
                if (wanted.contains(viewer))
                {
                    granted.add(viewer);
                }
                else
                {
                    releaseRelation(viewer);
                }
            }
            if (acceptingNewWork())
            {
                for (UUID viewer : wanted)
                {
                    if (!granted.contains(viewer) && this.totalRelations < this.settings.models().maxRelations()
                            && this.viewerRelations.getOrDefault(viewer, 0) < this.settings.models()
                                    .maxModelsPerViewer())
                    {
                        granted.add(viewer);
                        this.viewerRelations.merge(viewer, 1, Integer::sum);
                        this.totalRelations++;
                    }
                }

            }

            if (granted.isEmpty())
            {
                this.relations.remove(key);
            }
            else
            {
                this.relations.put(key, granted);
            }

            this.peakRelations = Math.max(this.peakRelations, this.totalRelations);
            return Collections.unmodifiableSet(new LinkedHashSet<>(granted));
        }
    }

    public void releaseRelationships(String path, UUID owner)
    {
        synchronized (this.lock)
        {
            Set<UUID> removed = this.relations.remove(new RelationKey(path, owner));
            if (removed != null)
            {
                removed.forEach(this::releaseRelation);
            }
        }
    }

    public void releaseRelationship(String path, UUID owner, UUID viewer)
    {
        synchronized (this.lock)
        {
            RelationKey key = new RelationKey(path, owner);
            Set<UUID> current = this.relations.get(key);
            if (current != null && current.remove(viewer))
            {
                releaseRelation(viewer);
                if (current.isEmpty())
                {
                    this.relations.remove(key);
                }
            }
        }
    }

    private void releaseRelation(UUID viewer)
    {
        this.totalRelations--;
        this.viewerRelations.computeIfPresent(viewer, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    private record RelationKey(String path, UUID owner)
    {
    }

    /**
     * Combined immutable-cache budget. Identity is local to a cache namespace; duplicate retains
     * must keep their own reference count and call release only after their final live reference ends.
     */
    public boolean reserveCache(String kind, Object identity, long bytes)
    {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(identity);
        if (kind.isBlank() || kind.length() > 64 || bytes <= 0)
        {
            throw new IllegalArgumentException("Invalid immutable cache reservation");
        }

        synchronized (this.lock)
        {
            if (this.closed)
            {
                return false;
            }

            CacheKey key = new CacheKey(kind, identity);
            Long existing = this.cacheReservations.get(key);
            if (existing != null)
            {
                if (existing != bytes)
                {
                    throw new IllegalStateException("Immutable cache reservation size changed");
                }

                return true;
            }
            if (this.cacheReservations.size() >= this.settings.models().maxAssetCacheModels()
                    || bytes > this.settings.models().maxAssetCacheBytes() - this.cachedBytes)
            {
                rejectLocked("memory_limit");
                return false;
            }
            this.cacheReservations.put(key, bytes);
            this.cachedBytes += bytes;
            this.peakCachedBytes = Math.max(this.peakCachedBytes, this.cachedBytes);
            return true;
        }
    }

    public void releaseCache(String kind, Object identity)
    {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(identity);
        synchronized (this.lock)
        {
            Long bytes = this.cacheReservations.remove(new CacheKey(kind, identity));
            if (bytes != null)
            {
                this.cachedBytes -= bytes;
            }
        }
    }

    private record CacheKey(String kind, Object identity)
    {
    }

    /** Worker-only filesystem check. Release after stream closure and deletion of temporary files. */
    public ResourceError reserveTemporary(UUID owner, long bytes, Path directory)
    {
        if (bytes < 0)
        {
            throw new IllegalArgumentException("negative temporary reservation");
        }

        long usable;
        try
        {
            checkCancelled();
            Path existing = directory.toAbsolutePath();
            while (existing != null && !Files.exists(existing))
            {
                existing = existing.getParent();
            }

            if (existing == null)
            {
                reject("disk_space_low");
                return new ResourceError("disk_space_low", "storage", true, 600);
            }
            usable = Files.getFileStore(existing).getUsableSpace();
        }
        catch (IOException failure)
        {
            reject("disk_unavailable");
            return new ResourceError("disk_unavailable", "storage", true, 600);
        }
        synchronized (this.lock)
        {
            if (bytes > this.settings.disk().maxTemporaryBytes() - this.temporaryBytes)
            {
                rejectLocked("disk_budget");
                return new ResourceError("disk_budget", "storage", true, 100);
            }
            // Already reserved writers may not have created their files yet; reserve their disk headroom too.
            if (usable - bytes - this.temporaryBytes < this.settings.disk().minFreeBytes())
            {
                rejectLocked("disk_space_low");
                return new ResourceError("disk_space_low", "storage", true, 600);
            }
            this.temporaryBytes += bytes;
            this.temporaryOwners.merge(owner, bytes, Long::sum);
            return null;
        }
    }

    public void releaseTemporary(UUID owner, long bytes)
    {
        synchronized (this.lock)
        {
            long allocated = this.temporaryOwners.getOrDefault(owner, 0L),
                    released = Math.min(Math.max(0, bytes), allocated);
            this.temporaryBytes -= released;
            if (released == allocated)
            {
                this.temporaryOwners.remove(owner);
            }
            else
            {
                this.temporaryOwners.put(owner, allocated - released);
            }
        }
    }

    public void gauge(String name, LongSupplier gauge)
    {
        Objects.requireNonNull(name);
        Objects.requireNonNull(gauge);
        synchronized (this.lock)
        {
            if (this.gauges.containsKey(name) || this.gauges.size() < 128)
            {
                this.gauges.put(name, gauge);
            }
        }
    }

    public void reject(String code)
    {
        synchronized (this.lock)
        {
            rejectLocked(code);
        }
    }

    private void rejectLocked(String code)
    {
        if (this.rejections.containsKey(code) || this.rejections.size() < 64)
        {
            this.rejections.merge(code, 1L, Long::sum);
        }
        else
        {
            this.rejections.merge("other", 1L, Long::sum);
        }
    }

    public Map<String, Long> snapshot()
    {
        LinkedHashMap<String, Long> result = new LinkedHashMap<String, Long>();
        Map<String, LongSupplier> currentGauges;
        synchronized (this.lock)
        {
            result.put("tasks.running", (long) this.running);
            result.put("tasks.queued", (long) this.executor.getQueue().size());
            result.put("tasks.reservedBytes", this.reservedBytes);
            result.put("tasks.pendingCompletions", (long) this.completions.size());
            result.put("tasks.completionReservedBytes", this.completionReservedBytes);
            result.put("tasks.completed", this.completed);
            result.put("tasks.timeouts", this.timeouts);
            result.put("tasks.canceled", this.canceled);
            result.put("tasks.peakJobs", (long) this.peakJobs);
            result.put("tasks.peakReservedBytes", this.peakReservedBytes);
            result.put("tasks.peakCompletions", (long) this.peakCompletions);
            result.put("models.relations", (long) this.totalRelations);
            result.put("models.peakRelations", (long) this.peakRelations);
            result.put("disk.temporaryBytes", this.temporaryBytes);
            result.put("models.cachedBytes", this.cachedBytes);
            result.put("models.cachedEntries", (long) this.cacheReservations.size());
            result.put("models.cacheByteLimit", this.settings.models().maxAssetCacheBytes());
            result.put("models.cacheEntryLimit", (long) this.settings.models().maxAssetCacheModels());
            result.put("models.peakCachedBytes", this.peakCachedBytes);
            currentGauges = new LinkedHashMap<>(this.gauges);
            for (Map.Entry<String, Long> entry : this.rejections.entrySet())
            {
                result.put("rejected." + entry.getKey(), entry.getValue());
            }

        }
        for (Map.Entry<String, LongSupplier> entry : currentGauges.entrySet())
        {
            try
            {
                result.put(entry.getKey(), Math.max(0, entry.getValue().getAsLong()));
            }
            catch (RuntimeException ignored)
            {
                result.put(entry.getKey(), -1L);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    public List<String> statusLines(String section)
    {
        String selected = section == null ? "global" : section.toLowerCase(Locale.ROOT);
        Map<String, Long> values = snapshot();
        List<String> lines = new ArrayList<>();
        synchronized (this.lock)
        {
            lines.add(String.format(Locale.ROOT,
                    "§eMPA 资源状态 §f%s §7(%s) TPS %.2f / MSPT %.2f / 已计量 MPA 主线程区段 %.3f ms/tick", this.state, this.reason,
                    this.tps, this.mspt, this.pluginMillis));
            if (selected.equals("global") || selected.equals("protection"))
            {
                lines.add("§7非关键主线程预算：" + this.settings.protection().mainBudgetMillis() + " ms / "
                        + this.settings.protection().mainWorkPerTick() + " 项；健康恢复等待 "
                        + this.settings.protection().recoveryTicks() / 20 + " 秒/级");
            }

            if (selected.equals("global") || selected.equals("tasks"))
            {
                lines.add("§7任务运行/等待：" + this.running + "/" + this.executor.getQueue().size() + "，上限 "
                        + this.settings.tasks().workerThreads() + "/" + this.settings.tasks().queueCapacity() + "；内存 "
                        + this.reservedBytes + "/" + this.settings.tasks().maxReservedBytes() + " B；主线程结果 "
                        + this.completions.size() + "/" + this.settings.tasks().pendingCompletions());
            }

            if (selected.equals("global") || selected.equals("network"))
            {
                lines.add("§7全局上传/下载：" + this.settings.network().globalUploadBytesPerSecond() + "/"
                        + this.settings.network().globalDownloadBytesPerSecond() + " B/s；传输上限 "
                        + this.settings.network().globalTransfers() + "；包上限 "
                        + this.settings.network().globalPacketsPerSecond() + "/s");
            }

            if (selected.equals("global") || selected.equals("models"))
            {
                lines.add("§7观看关系：" + this.totalRelations + "/" + this.settings.models().maxRelations() + "，每观看者上限 "
                        + this.settings.models().maxModelsPerViewer() + "；服务器伪装/私人发布上限 "
                        + this.settings.models().maxDisguises() + "/" + this.settings.models().maxPublications());
            }

        }
        for (Map.Entry<String, Long> entry : values.entrySet())
        {
            if (selected.equals("global") || entry.getKey().startsWith(selected + ".")
                    || selected.equals("models")
                            && (entry.getKey().startsWith("assets.") || entry.getKey().startsWith("private."))
                    || entry.getKey().startsWith("rejected."))
            {
                lines.add("§8" + entry.getKey() + " = §7" + entry.getValue());
            }

        }

        return List.copyOf(lines);
    }

    public void close()
    {
        synchronized (this.lock)
        {
            if (this.closed)
            {
                return;
            }
            this.closed = true;
            if (this.maintenance != null)
            {
                this.maintenance.cancel();
                this.maintenance = null;
            }
            for (Job<?> job : List.copyOf(this.jobs))
            {
                cancel(job, new ResourceError("server_stopped", job.stage, false, 0));
            }

            for (Completion completion : this.completions)
            {
                releaseCompletion(completion.job);
            }
            this.completions.clear();
            this.relations.clear();
            this.viewerRelations.clear();
            this.totalRelations = 0;
            this.lastDisguise.clear();
            for (Runnable queued : this.executor.shutdownNow())
            {
                if (queued instanceof ResourceProtection.Job<?> job)
                {
                    job.workerFinished = true;
                    releaseWork(job);
                    releaseCompletion(job);
                }
            }

        }
    }
}
