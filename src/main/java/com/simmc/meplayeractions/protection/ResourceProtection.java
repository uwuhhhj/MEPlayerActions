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
public final class ResourceProtection implements AutoCloseable {
    public enum State { NORMAL, THROTTLED, PROTECTED, EMERGENCY }
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

    public ResourceProtection(JavaPlugin plugin, ResourceSettings settings) {
        this(plugin, settings, System::nanoTime, plugin.getLogger());
    }
    ResourceProtection(ResourceSettings settings, LongSupplier clock) {
        this(null, settings, clock, Logger.getLogger("MEPlayerActions.ResourceProtection.Tests"));
    }
    private ResourceProtection(JavaPlugin plugin, ResourceSettings settings, LongSupplier clock, Logger logger) {
        this.plugin = plugin; this.settings = Objects.requireNonNull(settings); this.clock = Objects.requireNonNull(clock); this.logger = logger;
        ThreadFactory factory = new ThreadFactory() {
            private int number;
            public Thread newThread(Runnable work) { Thread thread = new Thread(work,"MPA-resource-"+(++number)); thread.setDaemon(true); return thread; }
        };
        executor = new ThreadPoolExecutor(settings.tasks().workerThreads(), settings.tasks().workerThreads(),
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.tasks().queueCapacity()), factory, new ThreadPoolExecutor.AbortPolicy());
    }
    public void start() {
        if (plugin == null || maintenance != null || closed) return;
        maintenance = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }
    public ResourceSettings settings() { return settings; }
    public ModelComplexityLimits complexityLimits() { return settings.complexity(); }
    public State state() { return state; }
    public boolean acceptingNewWork() { return !closed && state.ordinal() < State.PROTECTED.ordinal(); }
    public boolean shouldStartTransfer() { return acceptingNewWork(); }
    public double throttleFactor() { return state == State.NORMAL ? 1 : state == State.THROTTLED ? .5 : 0; }
    public boolean tryWork() { return tryMainWork(1); }
    public boolean tryMainWork(int operations) {
        if (operations < 1) throw new IllegalArgumentException("operations must be positive");
        synchronized (lock) {
            int limit = settings.protection().mainWorkPerTick();
            if (state != State.NORMAL) limit = Math.max(1, limit / (state==State.EMERGENCY?4:2));
            if (closed || tickOperations > limit - operations || tickWorkNanos >= settings.protection().mainBudgetMillis() * 1_000_000) return false;
            tickOperations += operations; return true;
        }
    }
    public void recordWork(long nanos) { if (nanos > 0) synchronized (lock) { tickWorkNanos = Math.min(Long.MAX_VALUE - nanos, tickWorkNanos) + nanos; } }

    /** A false return has already delivered its structured rejection on the caller thread. */
    public <T> boolean submit(UUID owner, long bytes, String stage, Callable<T> work,
                              Consumer<T> success, Consumer<ResourceError> failure) {
        Objects.requireNonNull(work); Objects.requireNonNull(success); Objects.requireNonNull(failure);
        new ResourceError("server_busy", stage, true, 100);
        if (bytes < 0) throw new IllegalArgumentException("negative reservation");
        Job<T> job; ResourceError error;
        synchronized (lock) {
            error = admission(owner, bytes, stage);
            if (error == null) {
                job = new Job<>(owner, bytes, stage, work, success, failure, clock.getAsLong());
                jobs.add(job); reservedBytes += bytes; completionReservedBytes += bytes; completionReservations++;
                if (owner != null) { Owner o = owners.computeIfAbsent(owner, ignored -> new Owner()); o.jobs++; o.bytes += bytes; }
                peakJobs = Math.max(peakJobs, jobs.size()); peakReservedBytes = Math.max(peakReservedBytes, reservedBytes);
                try { executor.execute(job); return true; }
                catch (RejectedExecutionException rejected) { releaseWork(job); releaseCompletion(job); error = ResourceError.busy(stage); }
            }
            rejectLocked(error.code());
        }
        failure.accept(error); return false;
    }
    private ResourceError admission(UUID owner, long bytes, String stage) {
        ResourceSettings.Tasks t = settings.tasks();
        if (closed) return new ResourceError("server_stopped", stage, false, 0);
        if (!acceptingNewWork() && !cleanup(stage)) return ResourceError.protectedState(stage);
        if (jobs.size() >= t.workerThreads() + t.queueCapacity() || completionReservations >= t.pendingCompletions()) return ResourceError.busy(stage);
        if (bytes > t.maxReservedBytes() - reservedBytes || bytes > t.maxPendingCompletionBytes() - completionReservedBytes)
            return new ResourceError("memory_limit", stage, true, 100);
        Owner o = owner == null ? null : owners.get(owner);
        if (owner != null && (bytes > t.perPlayerReservedBytes() - (o == null ? 0 : o.bytes)
                || o != null && o.jobs >= t.jobsPerPlayer())) return new ResourceError("player_task_limit", stage, true, 100);
        return null;
    }
    private static boolean cleanup(String stage) { return stage.equals("cleanup") || stage.startsWith("cleanup_") || stage.equals("catalog_delete"); }
    public static void checkCancelled() {
        Job<?> job = CURRENT.get();
        if (Thread.currentThread().isInterrupted() || job != null && job.canceled) throw new CancellationException("Resource work canceled");
    }
    public static void progress() { Job<?> job = CURRENT.get(); if (job != null) job.lastProgress = job.runtime.clock.getAsLong(); checkCancelled(); }
    public void cancelOwner(UUID owner) {
        synchronized (lock) {
            for (Job<?> job : List.copyOf(jobs)) if (Objects.equals(owner,job.owner)) cancel(job,new ResourceError("request_cancelled",job.stage,false,0));
            // A finished worker may still have a retained result waiting for its main-thread callback.
            // Replace stale results with failure bookkeeping rather than dropping the callback:
            // callers may have reserved publication/delete slots independently of this runtime.
            Deque<Completion> replacement = new ArrayDeque<>(completions.size());
            for (Completion completion : completions) {
                if (Objects.equals(owner,completion.job.owner)) {
                    Job<?> job = completion.job;if(!job.canceled)canceled++;job.canceled = true;
                    ResourceError error=new ResourceError("request_cancelled",job.stage,false,0);
                    replacement.addLast(new Completion(job,()->job.failure.accept(error),false));
                } else replacement.addLast(completion);
            }
            completions.clear();completions.addAll(replacement);
            lastDisguise.remove(owner);
        }
    }
    private void cancel(Job<?> job, ResourceError error) {
        if (job.canceled || job.workerFinished) return;
        job.canceled = true; canceled++;
        if (error.code().endsWith("timeout")) timeouts++;
        deliver(job, () -> job.failure.accept(error));
        if (executor.remove(job)) { job.workerFinished = true; releaseWork(job); }
        else if (job.thread != null) job.thread.interrupt();
    }
    private void deliver(Job<?> job, Runnable callback) {
        deliver(job,callback,false);
    }
    private void deliver(Job<?> job, Runnable callback,boolean success) {
        if (job.delivered) return;
        job.delivered = true;
        if (closed) { releaseCompletion(job); return; }
        completions.addLast(new Completion(job,callback,success)); peakCompletions = Math.max(peakCompletions,completions.size());
    }
    private void releaseWork(Job<?> job) {
        if (!jobs.remove(job)) return;
        reservedBytes -= job.bytes;
        if (job.owner != null) {
            Owner o = owners.get(job.owner); if(o != null) { o.jobs--; o.bytes -= job.bytes; if(o.jobs==0)owners.remove(job.owner); }
        }
    }
    private void releaseCompletion(Job<?> job) {
        if (job.completionReleased) return;
        job.completionReleased = true; completionReservations--; completionReservedBytes -= job.bytes;
    }
    private final class Job<T> implements Runnable {
        final ResourceProtection runtime = ResourceProtection.this;
        final UUID owner; final long bytes; final String stage; final Callable<T> work; final Consumer<T> success; final Consumer<ResourceError> failure;
        final long queuedAt; volatile long startedAt, lastProgress; volatile Thread thread; volatile boolean canceled;
        boolean workerFinished, delivered, completionReleased;
        Job(UUID owner,long bytes,String stage,Callable<T> work,Consumer<T> success,Consumer<ResourceError> failure,long now) {
            this.owner=owner;this.bytes=bytes;this.stage=stage;this.work=work;this.success=success;this.failure=failure;queuedAt=now;lastProgress=now;
        }
        public void run() {
            synchronized(lock) {
                if(canceled || closed) { workerFinished=true;releaseWork(this);return; }
                thread=Thread.currentThread();startedAt=clock.getAsLong();lastProgress=startedAt;running++;
            }
            CURRENT.set(this);
            T value = null; Throwable problem = null;
            try { checkCancelled(); value=work.call(); checkCancelled(); }
            catch(Throwable failure) { problem=failure; }
            finally { CURRENT.remove(); }
            final T result=value; final Throwable failed=problem;
            synchronized(lock) {
                running--;workerFinished=true;thread=null;releaseWork(this);
                if(!canceled&&!closed) {
                    if(failed==null)deliver(this,()->{ if(!canceled)success.accept(result); },true);
                    else {
                        ResourceError error=failed instanceof ResourceRejectedException rejected?rejected.error():new ResourceError(
                                failed instanceof CancellationException || failed instanceof InterruptedException ? "request_cancelled" : "validation_failed",stage,false,0);
                        rejectLocked(error.code());deliver(this,()->failure.accept(error));
                    }
                } else if(!delivered)releaseCompletion(this);
            }
        }
    }
    private record Completion(Job<?> job,Runnable callback,boolean success) { }
    private static final class Owner { int jobs; long bytes; }

    /** Main-thread cadence; resource deadlines use elapsed time even when server TPS is low. */
    public void tick() {
        long now=clock.getAsLong();
        synchronized(lock) {
            pluginMillis=pluginMillis*.8 + tickWorkNanos/1_000_000d*.2;
            tickWorkNanos=0;tickOperations=0;
            if(now-lastCooldownPrune>=1_000_000_000L) {
                lastCooldownPrune=now;long ttl=settings.models().disguiseCooldownTicks()*TICK_NANOS;
                lastDisguise.entrySet().removeIf(entry->now-entry.getValue()>=ttl);
            }
            for(Job<?> job:List.copyOf(jobs)) {
                if(job.canceled)continue;
                if(job.thread==null&&now-job.queuedAt>=settings.tasks().queueTimeoutTicks()*TICK_NANOS)
                    cancel(job,new ResourceError("queue_timeout",job.stage,true,100));
                else if(job.thread!=null&&(now-job.startedAt>=settings.tasks().taskTimeoutTicks()*TICK_NANOS
                        || now-job.lastProgress>=settings.tasks().taskIdleTicks()*TICK_NANOS))
                    cancel(job,new ResourceError("validation_timeout",job.stage,true,100));
            }
        }
        if(plugin != null) {
            double[] recent=plugin.getServer().getTPS();
            sample(recent.length==0?20:recent[0],plugin.getServer().getAverageTickTime(),now);
        }
        drainCompletions();
    }
    void drainCompletions() {
        long started=clock.getAsLong(); int processed=0;
        while(processed<settings.tasks().callbacksPerTick()) {
            Completion c;
            synchronized(lock) { c=completions.pollFirst(); }
            if(c==null)break;
            try { c.callback.run(); }
            catch(RuntimeException failure) {
                ResourceError error=failure instanceof ResourceRejectedException rejected?rejected.error():new ResourceError("commit_failed",c.job.stage,false,0);
                reject(error.code());
                if(c.success)try { c.job.failure.accept(error); }catch(RuntimeException ignored) { reject("completion_failed"); }
            }
            finally { synchronized(lock) { completed++;releaseCompletion(c.job); } }
            processed++;
            if(clock.getAsLong()-started>=settings.protection().mainBudgetMillis()*1_000_000)break;
        }
        recordWork(Math.max(0,clock.getAsLong()-started));
    }
    /** Downgrades immediately; recovery advances one state after a continuously healthy interval. */
    public void sample(double currentTps,double currentMspt,long now) {
        synchronized(lock) {
            tps=Double.isFinite(currentTps)?Math.max(0,Math.min(20,currentTps)):0;
            mspt=Double.isFinite(currentMspt)?Math.max(0,currentMspt):Double.MAX_VALUE;
            ResourceSettings.Protection p=settings.protection();
            if(!p.enabled()) { change(State.NORMAL,"disabled",now);return; }
            State desired=State.NORMAL; String why="healthy";
            if(tps<=p.emergencyTps()||mspt>=p.emergencyMspt()||pluginMillis>=6*p.mainBudgetMillis()) {
                desired=State.EMERGENCY;why=pluginMillis>=6*p.mainBudgetMillis()?"plugin_work_emergency":"server_tick_emergency";
            }
            else if(tps<=p.protectedTps()||mspt>=p.protectedMspt()||pluginMillis>=3*p.mainBudgetMillis()) {
                desired=State.PROTECTED;why=pluginMillis>=3*p.mainBudgetMillis()?"plugin_work_protection":"server_tick_protection";
            }
            else if(tps<=p.throttledTps()||mspt>=p.throttledMspt()) { desired=State.THROTTLED;why="server_tick_throttled"; }
            else if(pluginMillis>=p.mainBudgetMillis()||reservedBytes>=settings.tasks().maxReservedBytes()*9/10
                    || jobs.size()>=Math.max(1,(settings.tasks().workerThreads()+settings.tasks().queueCapacity())*9/10)) { desired=State.THROTTLED;why="plugin_resource_pressure"; }
            if(desired.ordinal()>state.ordinal()) { healthySince=-1;change(desired,why,now); }
            else if(desired==State.NORMAL&&tps>=p.recoveryTps()&&mspt<=p.recoveryMspt()) {
                if(state==State.NORMAL)healthySince=-1;
                else if(healthySince<0)healthySince=now;
                else if(now-healthySince>=p.recoveryTicks()*TICK_NANOS) {
                    healthySince=now;change(State.values()[state.ordinal()-1],"healthy_recovery",now);
                }
            } else healthySince=-1;
        }
    }
    private void change(State next,String why,long now) {
        if(state==next) { reason=why;return; }
        state=next;reason=why;
        int workers=next==State.NORMAL?settings.tasks().workerThreads():Math.max(1,settings.tasks().workerThreads()/2);
        if(workers<executor.getCorePoolSize()) { executor.setCorePoolSize(workers);executor.setMaximumPoolSize(workers); }
        else { executor.setMaximumPoolSize(workers);executor.setCorePoolSize(workers); }
        if(next==State.EMERGENCY) for(Job<?> job:List.copyOf(jobs))
            if(job.thread==null&&!cleanup(job.stage))cancel(job,ResourceError.protectedState(job.stage));
        if(plugin!=null&&(lastWarning==Long.MIN_VALUE||now-lastWarning>=30_000_000_000L)) {
            logger.warning("资源保护状态="+next+" 原因="+why+"；新模型工作将按状态限流，已有授权与清理仍继续");lastWarning=now;
        }
    }
    public ResourceError tryDisguise(UUID owner) {
        Objects.requireNonNull(owner);
        synchronized(lock) {
            if(!acceptingNewWork()) { rejectLocked("tps_protection");return ResourceError.protectedState("disguise"); }
            long now=clock.getAsLong();Long previous=lastDisguise.get(owner);
            if(previous!=null&&now-previous<settings.models().disguiseCooldownTicks()*TICK_NANOS) {
                rejectLocked("disguise_cooldown");return new ResourceError("disguise_cooldown","disguise",true,settings.models().disguiseCooldownTicks());
            }
            if(now-disguiseWindow>=1_000_000_000L) { disguiseWindow=now;disguiseCount=0; }
            int max=state==State.THROTTLED?Math.max(1,settings.models().newDisguisesPerSecond()/2):settings.models().newDisguisesPerSecond();
            if(disguiseCount>=max) { rejectLocked("disguise_rate_limit");return new ResourceError("disguise_rate_limit","disguise",true,20); }
            if(lastDisguise.size()>=65536&&!lastDisguise.containsKey(owner)) { rejectLocked("disguise_rate_limit");return new ResourceError("disguise_rate_limit","disguise",true,20); }
            lastDisguise.put(owner,now);disguiseCount++;return null;
        }
    }
    public Set<UUID> reserveRelationships(String path,UUID owner,Collection<UUID> requested) {
        Objects.requireNonNull(path);Objects.requireNonNull(owner);Objects.requireNonNull(requested);
        synchronized(lock) {
            RelationKey key=new RelationKey(path,owner);LinkedHashSet<UUID> old=relations.getOrDefault(key,new LinkedHashSet<>());
            LinkedHashSet<UUID> wanted=new LinkedHashSet<>(requested),granted=new LinkedHashSet<>();
            for(UUID viewer:old) { if(wanted.contains(viewer))granted.add(viewer);else releaseRelation(viewer); }
            if(acceptingNewWork())for(UUID viewer:wanted)if(!granted.contains(viewer)&&totalRelations<settings.models().maxRelations()
                    &&viewerRelations.getOrDefault(viewer,0)<settings.models().maxModelsPerViewer()) {
                granted.add(viewer);viewerRelations.merge(viewer,1,Integer::sum);totalRelations++;
            }
            if(granted.isEmpty())relations.remove(key);else relations.put(key,granted);
            peakRelations=Math.max(peakRelations,totalRelations);return Collections.unmodifiableSet(new LinkedHashSet<>(granted));
        }
    }
    public void releaseRelationships(String path,UUID owner) {
        synchronized(lock) { Set<UUID> removed=relations.remove(new RelationKey(path,owner));if(removed!=null)removed.forEach(this::releaseRelation); }
    }
    public void releaseRelationship(String path,UUID owner,UUID viewer) {
        synchronized(lock) {
            RelationKey key=new RelationKey(path,owner);Set<UUID> current=relations.get(key);
            if(current!=null&&current.remove(viewer)) { releaseRelation(viewer);if(current.isEmpty())relations.remove(key); }
        }
    }
    private void releaseRelation(UUID viewer) { totalRelations--;viewerRelations.computeIfPresent(viewer,(ignored,count)->count<=1?null:count-1); }
    private record RelationKey(String path,UUID owner) { }
    /**
     * Combined immutable-cache budget. Identity is local to a cache namespace; duplicate retains
     * must keep their own reference count and call release only after their final live reference ends.
     */
    public boolean reserveCache(String kind,Object identity,long bytes) {
        Objects.requireNonNull(kind);Objects.requireNonNull(identity);
        if(kind.isBlank()||kind.length()>64||bytes<=0)throw new IllegalArgumentException("Invalid immutable cache reservation");
        synchronized(lock) {
            if(closed)return false;
            CacheKey key=new CacheKey(kind,identity);Long existing=cacheReservations.get(key);
            if(existing!=null) {
                if(existing!=bytes)throw new IllegalStateException("Immutable cache reservation size changed");
                return true;
            }
            if(cacheReservations.size()>=settings.models().maxAssetCacheModels()
                    || bytes>settings.models().maxAssetCacheBytes()-cachedBytes) {
                rejectLocked("memory_limit");return false;
            }
            cacheReservations.put(key,bytes);cachedBytes+=bytes;peakCachedBytes=Math.max(peakCachedBytes,cachedBytes);return true;
        }
    }
    public void releaseCache(String kind,Object identity) {
        Objects.requireNonNull(kind);Objects.requireNonNull(identity);
        synchronized(lock) { Long bytes=cacheReservations.remove(new CacheKey(kind,identity));if(bytes!=null)cachedBytes-=bytes; }
    }
    private record CacheKey(String kind,Object identity) { }
    /** Worker-only filesystem check. Release after stream closure and deletion of temporary files. */
    public ResourceError reserveTemporary(UUID owner,long bytes,Path directory) {
        if(bytes<0)throw new IllegalArgumentException("negative temporary reservation");
        long usable;
        try {
            checkCancelled();Path existing=directory.toAbsolutePath();while(existing!=null&&!Files.exists(existing))existing=existing.getParent();
            if(existing==null) {
                reject("disk_space_low");return new ResourceError("disk_space_low","storage",true,600);
            }
            usable=Files.getFileStore(existing).getUsableSpace();
        } catch(IOException failure) { reject("disk_unavailable");return new ResourceError("disk_unavailable","storage",true,600); }
        synchronized(lock) {
            if(bytes>settings.disk().maxTemporaryBytes()-temporaryBytes) { rejectLocked("disk_budget");return new ResourceError("disk_budget","storage",true,100); }
            // Already reserved writers may not have created their files yet; reserve their disk headroom too.
            if(usable-bytes-temporaryBytes<settings.disk().minFreeBytes()) { rejectLocked("disk_space_low");return new ResourceError("disk_space_low","storage",true,600); }
            temporaryBytes+=bytes;temporaryOwners.merge(owner,bytes,Long::sum);return null;
        }
    }
    public void releaseTemporary(UUID owner,long bytes) {
        synchronized(lock) { long allocated=temporaryOwners.getOrDefault(owner,0L),released=Math.min(Math.max(0,bytes),allocated);temporaryBytes-=released;
            if(released==allocated)temporaryOwners.remove(owner);else temporaryOwners.put(owner,allocated-released); }
    }
    public void gauge(String name,LongSupplier gauge) { Objects.requireNonNull(name);Objects.requireNonNull(gauge);synchronized(lock) { if(gauges.containsKey(name)||gauges.size()<128)gauges.put(name,gauge); } }
    public void reject(String code) { synchronized(lock) { rejectLocked(code); } }
    private void rejectLocked(String code) { if(rejections.containsKey(code)||rejections.size()<64)rejections.merge(code,1L,Long::sum);else rejections.merge("other",1L,Long::sum); }
    public Map<String,Long> snapshot() {
        var result=new LinkedHashMap<String,Long>();Map<String,LongSupplier> currentGauges;
        synchronized(lock) {
            result.put("tasks.running",(long)running);result.put("tasks.queued",(long)executor.getQueue().size());
            result.put("tasks.reservedBytes",reservedBytes);result.put("tasks.pendingCompletions",(long)completions.size());result.put("tasks.completionReservedBytes",completionReservedBytes);
            result.put("tasks.completed",completed);result.put("tasks.timeouts",timeouts);result.put("tasks.canceled",canceled);result.put("tasks.peakJobs",(long)peakJobs);
            result.put("tasks.peakReservedBytes",peakReservedBytes);result.put("tasks.peakCompletions",(long)peakCompletions);result.put("models.relations",(long)totalRelations);
            result.put("models.peakRelations",(long)peakRelations);result.put("disk.temporaryBytes",temporaryBytes);
            result.put("models.cachedBytes",cachedBytes);result.put("models.cachedEntries",(long)cacheReservations.size());
            result.put("models.cacheByteLimit",settings.models().maxAssetCacheBytes());result.put("models.cacheEntryLimit",(long)settings.models().maxAssetCacheModels());
            result.put("models.peakCachedBytes",peakCachedBytes);
            currentGauges=new LinkedHashMap<>(gauges);
            for(var entry:rejections.entrySet())result.put("rejected."+entry.getKey(),entry.getValue());
        }
        for(var entry:currentGauges.entrySet())try { result.put(entry.getKey(),Math.max(0,entry.getValue().getAsLong())); }catch(RuntimeException ignored) { result.put(entry.getKey(),-1L); }
        return Collections.unmodifiableMap(result);
    }
    public List<String> statusLines(String section) {
        String selected=section==null?"global":section.toLowerCase(Locale.ROOT);Map<String,Long> values=snapshot();List<String> lines=new ArrayList<>();
        synchronized(lock) {
            lines.add(String.format(Locale.ROOT,"§eMPA 资源状态 §f%s §7(%s) TPS %.2f / MSPT %.2f / 已计量 MPA 主线程区段 %.3f ms/tick",state,reason,tps,mspt,pluginMillis));
            if(selected.equals("global")||selected.equals("protection"))lines.add("§7非关键主线程预算："+settings.protection().mainBudgetMillis()+" ms / "+settings.protection().mainWorkPerTick()+" 项；健康恢复等待 "+settings.protection().recoveryTicks()/20+" 秒/级");
            if(selected.equals("global")||selected.equals("tasks"))lines.add("§7任务运行/等待："+running+"/"+executor.getQueue().size()+"，上限 "+settings.tasks().workerThreads()+"/"+settings.tasks().queueCapacity()+"；内存 "+reservedBytes+"/"+settings.tasks().maxReservedBytes()+" B；主线程结果 "+completions.size()+"/"+settings.tasks().pendingCompletions());
            if(selected.equals("global")||selected.equals("network"))lines.add("§7全局上传/下载："+settings.network().globalUploadBytesPerSecond()+"/"+settings.network().globalDownloadBytesPerSecond()+" B/s；传输上限 "+settings.network().globalTransfers()+"；包上限 "+settings.network().globalPacketsPerSecond()+"/s");
            if(selected.equals("global")||selected.equals("models"))lines.add("§7观看关系："+totalRelations+"/"+settings.models().maxRelations()+"，每观看者上限 "+settings.models().maxModelsPerViewer()+"；服务器伪装/私人发布上限 "+settings.models().maxDisguises()+"/"+settings.models().maxPublications());
        }
        for(var entry:values.entrySet())if(selected.equals("global")||entry.getKey().startsWith(selected+".")
                || selected.equals("models")&&(entry.getKey().startsWith("assets.")||entry.getKey().startsWith("private."))
                || entry.getKey().startsWith("rejected."))lines.add("§8"+entry.getKey()+" = §7"+entry.getValue());
        return List.copyOf(lines);
    }
    public void close() {
        synchronized(lock) {
            if(closed)return;closed=true;
            if(maintenance!=null) { maintenance.cancel();maintenance=null; }
            for(Job<?> job:List.copyOf(jobs))cancel(job,new ResourceError("server_stopped",job.stage,false,0));
            for(Completion completion:completions)releaseCompletion(completion.job);completions.clear();
            relations.clear();viewerRelations.clear();totalRelations=0;lastDisguise.clear();
            for(Runnable queued:executor.shutdownNow())if(queued instanceof ResourceProtection.Job<?> job) { job.workerFinished=true;releaseWork(job);releaseCompletion(job); }
        }
    }
}
