package com.simmc.meplayeractions.protection;

import com.simmc.meplayeractions.config.ModelComplexityLimits;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable limits: configuration mistakes fail reload before replacing the running backend. */
public record ResourceSettings(Tasks tasks, Network network, Protection protection, Models models,
                               Disk disk, ModelComplexityLimits complexity) {
    private static final long MIB = 1024L * 1024;
    private static final String P = "resource-protection.";
    public record Tasks(int workerThreads, int queueCapacity, int jobsPerPlayer, long maxReservedBytes,
                        long perPlayerReservedBytes, int pendingCompletions, long maxPendingCompletionBytes,
                        int callbacksPerTick, int queueTimeoutTicks, int taskIdleTicks, int taskTimeoutTicks) {
        public Tasks {
            bound("worker-threads", workerThreads, 1, 16); bound("queue-capacity", queueCapacity, 1, 1024);
            bound("jobs-per-player", jobsPerPlayer, 1, 64); bound("max-reserved-bytes", maxReservedBytes, MIB, 4L * 1024 * MIB);
            bound("per-player-reserved-bytes", perPlayerReservedBytes, MIB, maxReservedBytes);
            bound("pending-completions", pendingCompletions, 1, 2048);
            bound("max-pending-completion-bytes", maxPendingCompletionBytes, MIB, 4L * 1024 * MIB);
            bound("callbacks-per-tick", callbacksPerTick, 1, 256); bound("queue-timeout-ticks", queueTimeoutTicks, 20, 72000);
            bound("task-idle-ticks", taskIdleTicks, 20, 72000); bound("task-timeout-ticks", taskTimeoutTicks, taskIdleTicks, 72000);
        }
    }
    public record Network(long globalUploadBytesPerSecond, long globalDownloadBytesPerSecond,
                          long uploadBytesPerSecond, long downloadBytesPerSecond, long assetDownloadBytesPerSecond,
                          int globalPacketsPerSecond, int packetsPerSecond, long burstBytes, int bytesPerTick,
                          int assetBytesPerTick, int globalTransfers, int transfersPerPlayer, int waitingTransfers,
                          int transferQueueTicks, int transferIdleTicks, int transferTotalTicks, int readyTicks,
                          long maxQueuedOutgoingBytes) {
        public Network {
            bound("global-upload-bytes-per-second", globalUploadBytesPerSecond, 16384, 1024 * MIB);
            bound("global-download-bytes-per-second", globalDownloadBytesPerSecond, 16384, 1024 * MIB);
            bound("upload-bytes-per-second", uploadBytesPerSecond, 16384, globalUploadBytesPerSecond);
            bound("download-bytes-per-second", downloadBytesPerSecond, 16384, globalDownloadBytesPerSecond);
            bound("asset-download-bytes-per-second", assetDownloadBytesPerSecond, 16384, downloadBytesPerSecond);
            bound("global-packets-per-second", globalPacketsPerSecond, 16, 100000); bound("packets-per-second", packetsPerSecond, 4, globalPacketsPerSecond);
            bound("burst-bytes", burstBytes, 16384, 64 * MIB); bound("bytes-per-tick", bytesPerTick, 16384, 64 * MIB);
            bound("asset-bytes-per-tick", assetBytesPerTick, 16384, bytesPerTick); bound("global-transfers", globalTransfers, 1, 1024);
            bound("transfers-per-player", transfersPerPlayer, 1, globalTransfers); bound("waiting-transfers", waitingTransfers, 1, 4096);
            bound("transfer-queue-ticks", transferQueueTicks, 20, 72000); bound("transfer-idle-ticks", transferIdleTicks, 20, 72000);
            bound("transfer-total-ticks", transferTotalTicks, transferIdleTicks, 72000); bound("ready-ticks", readyTicks, 20, 72000);
            bound("max-queued-outgoing-bytes", maxQueuedOutgoingBytes, 16384, 1024 * MIB);
        }
    }
    public record Protection(boolean enabled, double throttledTps, double protectedTps, double emergencyTps,
                             double throttledMspt, double protectedMspt, double emergencyMspt,
                             double recoveryTps, double recoveryMspt, int recoveryTicks,
                             double mainBudgetMillis, int mainWorkPerTick) {
        public Protection {
            finite("throttled-tps", throttledTps, 1, 20); finite("protected-tps", protectedTps, 1, throttledTps);
            finite("emergency-tps", emergencyTps, 1, protectedTps); finite("throttled-mspt", throttledMspt, 1, 1000);
            finite("protected-mspt", protectedMspt, throttledMspt, 5000); finite("emergency-mspt", emergencyMspt, protectedMspt, 10000);
            finite("recovery-tps", recoveryTps, throttledTps, 20); finite("recovery-mspt", recoveryMspt, 1, throttledMspt);
            bound("recovery-ticks", recoveryTicks, 20, 72000); finite("main-budget-millis", mainBudgetMillis, .1, 50);
            bound("main-work-per-tick", mainWorkPerTick, 1, 10000);
        }
    }
    public record Models(int maxDisguises, int maxPublications, int maxRelations, int maxModelsPerViewer,
                         int newDisguisesPerSecond, int disguiseCooldownTicks, int emergencyRemovalsPerTick,
                         long maxAssetCacheBytes, int maxAssetCacheModels) {
        public Models {
            bound("max-disguises", maxDisguises, 1, 10000); bound("max-publications", maxPublications, 1, 10000);
            bound("max-relations", maxRelations, 1, 100000); bound("max-models-per-viewer", maxModelsPerViewer, 1, 1024);
            bound("new-disguises-per-second", newDisguisesPerSecond, 1, 1000); bound("disguise-cooldown-ticks", disguiseCooldownTicks, 1, 1200);
            bound("emergency-removals-per-tick", emergencyRemovalsPerTick, 0, 100);
            bound("max-asset-cache-bytes", maxAssetCacheBytes, MIB, 4L * 1024 * MIB); bound("max-asset-cache-models", maxAssetCacheModels, 1, 4096);
        }
    }
    public record Disk(long maxTemporaryBytes, long minFreeBytes) {
        public Disk { bound("max-temporary-bytes", maxTemporaryBytes, MIB, 64L * 1024 * MIB); bound("min-free-bytes", minFreeBytes, 0, 1024L * 1024 * MIB); }
    }
    public static ResourceSettings defaults() {
        return new ResourceSettings(new Tasks(2, 32, 4, 128 * MIB, 64 * MIB, 64, 128 * MIB, 8, 600, 600, 2400),
                new Network(4 * MIB, 8 * MIB, 256 * 1024, 2 * MIB, 512 * 1024, 4096, 48, 8 * MIB,
                        512 * 1024, 256 * 1024, 32, 2, 128, 600, 300, 2400, 300, 16 * MIB),
                new Protection(true, 18, 15, 10, 55, 75, 150, 19, 45, 600, 3, 64),
                new Models(256, 256, 4096, 64, 20, 20, 2, 128 * MIB, 512), new Disk(256 * MIB, 512 * MIB),
                ModelComplexityLimits.defaults());
    }
    public static ResourceSettings fromConfiguration(ConfigurationSection config) {
        ResourceSettings d = defaults(); Tasks t = d.tasks; Network n = d.network; Protection p = d.protection; Models m = d.models;
        return new ResourceSettings(new Tasks(i(config,"tasks.worker-threads",t.workerThreads), i(config,"tasks.queue-capacity",t.queueCapacity),
                i(config,"tasks.jobs-per-player",t.jobsPerPlayer), l(config,"tasks.max-reserved-bytes",t.maxReservedBytes),
                l(config,"tasks.per-player-reserved-bytes",t.perPlayerReservedBytes), i(config,"tasks.pending-completions",t.pendingCompletions),
                l(config,"tasks.max-pending-completion-bytes",t.maxPendingCompletionBytes), i(config,"tasks.callbacks-per-tick",t.callbacksPerTick),
                i(config,"tasks.queue-timeout-ticks",t.queueTimeoutTicks), i(config,"tasks.task-idle-ticks",t.taskIdleTicks), i(config,"tasks.task-timeout-ticks",t.taskTimeoutTicks)),
                new Network(l(config,"network.global-upload-bytes-per-second",n.globalUploadBytesPerSecond), l(config,"network.global-download-bytes-per-second",n.globalDownloadBytesPerSecond),
                        l(config,"network.upload-bytes-per-second",n.uploadBytesPerSecond),l(config,"network.download-bytes-per-second",n.downloadBytesPerSecond),
                        l(config,"network.asset-download-bytes-per-second",n.assetDownloadBytesPerSecond),i(config,"network.global-packets-per-second",n.globalPacketsPerSecond),
                        i(config,"network.packets-per-second",n.packetsPerSecond),l(config,"network.burst-bytes",n.burstBytes),i(config,"network.bytes-per-tick",n.bytesPerTick),
                        i(config,"network.asset-bytes-per-tick",n.assetBytesPerTick),i(config,"network.global-transfers",n.globalTransfers),i(config,"network.transfers-per-player",n.transfersPerPlayer),
                        i(config,"network.waiting-transfers",n.waitingTransfers),i(config,"network.transfer-queue-ticks",n.transferQueueTicks),i(config,"network.transfer-idle-ticks",n.transferIdleTicks),
                        i(config,"network.transfer-total-ticks",n.transferTotalTicks),i(config,"network.ready-ticks",n.readyTicks),l(config,"network.max-queued-outgoing-bytes",n.maxQueuedOutgoingBytes)),
                new Protection(config.getBoolean(P+"enabled",p.enabled),v(config,"throttled-tps",p.throttledTps),v(config,"protected-tps",p.protectedTps),v(config,"emergency-tps",p.emergencyTps),
                        v(config,"throttled-mspt",p.throttledMspt),v(config,"protected-mspt",p.protectedMspt),v(config,"emergency-mspt",p.emergencyMspt),v(config,"recovery-tps",p.recoveryTps),
                        v(config,"recovery-mspt",p.recoveryMspt),i(config,"protection.recovery-ticks",p.recoveryTicks),v(config,"main-budget-millis",p.mainBudgetMillis),i(config,"protection.main-work-per-tick",p.mainWorkPerTick)),
                new Models(i(config,"models.max-disguises",m.maxDisguises),i(config,"models.max-publications",m.maxPublications),i(config,"models.max-relations",m.maxRelations),
                        i(config,"models.max-models-per-viewer",m.maxModelsPerViewer),i(config,"models.new-disguises-per-second",m.newDisguisesPerSecond),i(config,"models.disguise-cooldown-ticks",m.disguiseCooldownTicks),
                        i(config,"models.emergency-removals-per-tick",m.emergencyRemovalsPerTick),l(config,"models.max-asset-cache-bytes",m.maxAssetCacheBytes),i(config,"models.max-asset-cache-models",m.maxAssetCacheModels)),
                new Disk(l(config,"disk.max-temporary-bytes",d.disk.maxTemporaryBytes),l(config,"disk.min-free-bytes",d.disk.minFreeBytes)),
                new ModelComplexityLimits(i(config,"complexity.max-bones",d.complexity.maxBones()),i(config,"complexity.max-animations",d.complexity.maxAnimations()),
                        i(config,"complexity.max-keyframes",d.complexity.maxKeyframes()),i(config,"complexity.max-expressions",d.complexity.maxExpressions()),i(config,"complexity.max-expression-chars",d.complexity.maxExpressionChars()),
                        i(config,"complexity.max-json-nodes",d.complexity.maxJsonNodes()),i(config,"complexity.max-archive-entries",d.complexity.maxArchiveEntries()),i(config,"complexity.max-expanded-bytes",d.complexity.maxExpandedBytes()),
                        l(config,"complexity.max-texture-pixels",d.complexity.maxTexturePixels())));
    }
    public int maxServerDisguises() { return models.maxDisguises; }
    public int disguiseCooldownTicks() { return models.disguiseCooldownTicks; }
    public int emergencyRemovalsPerTick() { return models.emergencyRemovalsPerTick; }
    private static int i(ConfigurationSection c,String key,int d) { long value=l(c,key,d); if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE) throw new IllegalArgumentException(P+key+" exceeds integer range"); return (int)value; }
    private static long l(ConfigurationSection c,String key,long d) { return c.getLong(P+key,d); }
    private static double v(ConfigurationSection c,String key,double d) { return c.getDouble(P+"protection."+key,d); }
    private static void bound(String key,long value,long min,long max) { if(value<min||value>max)throw new IllegalArgumentException(P+key+" must be "+min+"–"+max); }
    private static void finite(String key,double value,double min,double max) { if(!Double.isFinite(value)||value<min||value>max)throw new IllegalArgumentException(P+key+" must be "+min+"–"+max); }
}
