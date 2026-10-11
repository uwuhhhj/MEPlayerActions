package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.config.PerformanceSettings;
import com.simmc.meplayeractions.MEPlayerActionsPlugin;
import com.simmc.meplayeractions.protection.ResourceProtection;
import com.simmc.meplayeractions.protection.ResourceError;
import com.simmc.meplayeractions.server.ServerMessages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.plugin.messaging.Messenger;

/** Opt-in private model relay. Its private cache never resolves ME assets, HTTP or model scripts. */
public final class PrivateModelSyncService implements PluginMessageListener, AutoCloseable
{
    public static final String CHANNEL = "meplayeractions:private", CAPABILITY = "private_models_v1";
    public static final String UPLOAD_CREDIT = "private_upload_credit_v1";
    public static final String VIEW_STATE = "private_view_state_v1";
    public static final int PROTOCOL = 1, HEARTBEAT_TICKS = 20, LEASE_TICKS = 100;
    private static final int CHUNK_BYTES = 8192, MAX_OFFERS = 64, TRANSFER_TIMEOUT = 1200;
    private static final int DIAGNOSTIC_ENTRIES = 8, DIAGNOSTIC_TICKS = 1200;
    private static final long MAX_SEQUENCE = 9_007_199_254_740_991L;
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}"),
            NAME = Pattern.compile("[a-zA-Z0-9_.:/-]{1,128}");
    private static final Pattern MODEL_VARIABLE = Pattern
            .compile("variable\\.[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*");
    private static final Pattern ROAMING_NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");
    private static final String ROAMING_PREFIX = "variable.roaming.";
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final Plugin plugin;
    private final Supplier<Set<UUID>> serverDisguisedOwners;
    private final ConnectionLimits limits;
    private final ResourceProtection resources;
    private final LongSupplier nanoClock;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Publication> publications = new HashMap<>();
    private final Map<UUID, Rates> rates = new HashMap<>();
    private final Set<Upload> validations = new HashSet<>();
    private final PrivateAudienceCache audiences = new PrivateAudienceCache();
    private final LinkedHashSet<Offer> pendingTransfers = new LinkedHashSet<>(),
            activeTransfers = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> pendingControls = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> pendingCatalogs = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> pendingDeleteReplies = new LinkedHashSet<>();
    private final Set<OwnerHash> deleting = new HashSet<>();
    private final Set<OwnerSource> deletingSources = new HashSet<>();
    private Policy policy = Policy.disabled();
    private BukkitTask maintenance;
    private boolean running;
    private long storedBytes, reservedBytes;
    private int uploads;
    private int validationTicks = 20;
    private PrivateModelStore store;
    private boolean cacheWarning;
    private long lastMaintenanceTick;
    private boolean maintenanceInitialized;
    private boolean catalogRefreshInitialized;
    private volatile boolean catalogRefreshing;
    private long lastCatalogRefreshTick;

    /** Policy defaults never grant private asset publishing or viewing. Permissions are independent. */
    public record Policy(boolean enabled, int maxPayload, int maxBundleBytes, long maxStoredBytes, double viewDistance,
            int maxViewers, String uploadPermission, String viewPermission)
    {
        public Policy
        {
            if (maxPayload < 1024 || maxPayload > 30000 || maxBundleBytes < 1024
                    || maxBundleBytes > PrivateModelBundle.MAX_BYTES
                    || maxStoredBytes < (long) maxBundleBytes + PrivateModelBundle.MAX_BYTES
                    || maxStoredBytes > 256L * 1024 * 1024 || !Double.isFinite(viewDistance) || viewDistance < 1
                    || viewDistance > 256 || maxViewers < 0 || maxViewers > 1000 || uploadPermission == null
                    || uploadPermission.isBlank() || viewPermission == null || viewPermission.isBlank())

            {
                throw new IllegalArgumentException("Invalid private model relay policy");
            }

        }

        public static Policy disabled()
        {
            return new Policy(false, 16000, PrivateModelBundle.MAX_BYTES, 32L * 1024 * 1024, 64, 10,
                    "mact.private.upload", "mact.private.view");
        }

        /** Configuration reader usable in a private-only server without loading ModelEngine Settings. */
        public static Policy fromConfiguration(ConfigurationSection config)
        {
            Objects.requireNonNull(config);
            ConfigurationSection section = config.getConfigurationSection("client-sync.private-models");
            Set<String> allowed = Set.of("enabled", "max-bundle-bytes", "max-stored-bytes", "view-distance-blocks",
                    "max-viewers", "cache-enabled", "max-cache-bytes", "max-cache-models",
                    "max-cache-models-per-player");
            if (section != null)
            {
                for (String field : section.getKeys(false))
                {
                    if (!allowed.contains(field) || section.isConfigurationSection(field))

                    {
                        throw new IllegalArgumentException("Unknown client-sync.private-models field: " + field);
                    }

                }

            }

            PrivateModelStore.Settings.fromConfiguration(config);
            return new Policy(
                    config.getBoolean("client-sync.enabled", true)
                            && config.getBoolean("client-sync.private-models.enabled", false),
                    config.getInt("client-sync.max-payload-bytes", 16000),
                    config.getInt("client-sync.private-models.max-bundle-bytes", PrivateModelBundle.MAX_BYTES),
                    config.getLong("client-sync.private-models.max-stored-bytes", 32L * 1024 * 1024),
                    config.getDouble("client-sync.private-models.view-distance-blocks", 64),
                    config.getInt("client-sync.private-models.max-viewers", 10), "mact.private.upload",
                    "mact.private.view");
        }
    }

    public PrivateModelSyncService(Plugin plugin, Supplier<Set<UUID>> serverDisguisedOwners)
    {
        this(plugin, serverDisguisedOwners, new ConnectionLimits());
    }

    PrivateModelSyncService(Plugin plugin, Supplier<Set<UUID>> serverDisguisedOwners, ConnectionLimits limits)
    {
        this(plugin, serverDisguisedOwners, limits, System::nanoTime);
    }

    PrivateModelSyncService(Plugin plugin, Supplier<Set<UUID>> serverDisguisedOwners, ConnectionLimits limits,
            LongSupplier nanoClock)
    {
        this.plugin = Objects.requireNonNull(plugin);
        this.serverDisguisedOwners = Objects.requireNonNull(serverDisguisedOwners);
        this.limits = Objects.requireNonNull(limits);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.resources = plugin instanceof MEPlayerActionsPlugin actual ? actual.resources() : null;
        if (this.resources != null)
        {
            limits.configure(this.resources.settings().network());
            this.resources.gauge("private.publications", () -> this.publications.size());
            this.resources.gauge("private.relations", () -> this.publications.values().stream()
                    .mapToLong(publication -> publication.offeredViewers.size()).sum());
            this.resources.gauge("private.raw-bytes", () -> this.storedBytes);
            this.resources.gauge("private.reserved-bytes", () -> this.reservedBytes);
            this.resources.gauge("private.uploads", () -> this.uploads);
            this.resources.gauge("private.disk-bytes", () -> this.store == null ? 0 : this.store.statistics().bytes());
            this.resources.gauge("private.cache-models",
                    () -> this.store == null ? 0 : this.store.statistics().models());
            this.resources.gauge("private.cache-hits", () -> this.store == null ? 0 : this.store.statistics().hits());
            this.resources.gauge("private.cache-misses",
                    () -> this.store == null ? 0 : this.store.statistics().misses());
        }
    }

    /** Production work shares the plugin's bounded queue; test hosts retain deterministic scheduler hooks. */
    private <T> boolean submit(UUID owner, long bytes, String stage, Callable<T> work, Consumer<T> success,
            Consumer<ResourceError> failure)
    {
        if (this.resources != null)
        {
            return this.resources.submit(owner, bytes, stage, work, success, failure);
        }

        try
        {
            this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () ->
            {
                T result;
                try
                {
                    result = work.call();
                }
                catch (Exception invalid)
                {
                    this.plugin.getServer().getScheduler().runTask(this.plugin,
                            () -> failure.accept(new ResourceError("invalid_bundle", stage, false, 0)));
                    return;
                }
                this.plugin.getServer().getScheduler().runTask(this.plugin, () -> success.accept(result));
            });
            return true;
        }
        catch (RuntimeException stopped)
        {
            return false;
        }
    }

    private boolean acceptsNewWork()
    {
        return this.resources == null || this.resources.acceptingNewWork();
    }

    private boolean startsTransfer()
    {
        return this.resources == null || this.resources.shouldStartTransfer();
    }

    public void configure(Policy policy)
    {
        Objects.requireNonNull(policy);
        if (!this.policy.equals(policy))
        {
            clearSessions("policy_changed");
        }

        this.policy = policy;
    }

    public void configurePerformance(PerformanceSettings performance)
    {
        Objects.requireNonNull(performance);
        this.audiences.configure(performance.audienceRefreshTicks());
        this.validationTicks = performance.validationTicks();
        this.maintenanceInitialized = false;
    }

    public void enable()
    {
        if (this.running)
        {
            return;
        }

        this.store = new PrivateModelStore(this.plugin.getDataFolder().toPath().resolve("private-models"),
                PrivateModelStore.Settings.fromConfiguration(this.plugin.getConfig()), this.resources);
        Messenger messenger = this.plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(this.plugin, CHANNEL);
        try
        {
            messenger.registerIncomingPluginChannel(this.plugin, CHANNEL, this);
            this.running = true;
            this.maintenance = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, this::maintain, 1, 1);
        }
        catch (RuntimeException failure)
        {
            this.running = false;
            messenger.unregisterIncomingPluginChannel(this.plugin, CHANNEL, this);
            messenger.unregisterOutgoingPluginChannel(this.plugin, CHANNEL);
            throw failure;
        }
    }

    @Override
    public void close()
    {
        if (this.maintenance != null)
        {
            this.maintenance.cancel();
            this.maintenance = null;
        }
        clearSessions("plugin_stopping");
        this.rates.clear();
        this.running = false;
        for (Upload upload : List.copyOf(this.validations))
        {
            upload.cancelled = true;
            releaseUpload(upload);
        }
        Messenger messenger = this.plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(this.plugin, CHANNEL, this);
        messenger.unregisterOutgoingPluginChannel(this.plugin, CHANNEL);
    }

    public void forget(Player player)
    {
        UUID id = player.getUniqueId();
        Session session = this.sessions.remove(id);
        if (session != null)
        {
            endSession(id, session, "offline");
        }
        this.rates.remove(id);
        this.limits.forget(id);
    }

    /** Teleport/world changes revoke only relationships involving this player; discovery remains phased. */
    public void observationChanged(Player player)
    {
        UUID id = player.getUniqueId();
        Session session = this.sessions.get(id);
        if (session != null)
        {
            for (Offer offer : List.copyOf(session.offers.values()))
            {
                removeOffer(player, session, offer, "observation_changed");
            }

        }

        this.audiences.removeViewer(id);
        Publication publication = this.publications.get(id);
        if (this.resources != null)
        {
            for (Publication active : this.publications.values())
            {
                this.resources.releaseRelationship("private", active.owner, id);
            }

        }

        if (publication != null)
        {
            this.audiences.invalidate(id, publication.generation, tick());
            if (this.resources != null)
            {
                this.resources.releaseRelationships("private", id);
            }

            for (UUID viewer : List.copyOf(publication.offeredViewers))
            {
                Session watching = this.sessions.get(viewer);
                if (watching == null)
                {
                    continue;
                }

                Offer offer = watching.offers.get(id);
                if (offer != null && offer.publication == publication)
                {
                    removeOffer(Bukkit.getPlayer(viewer), watching, offer, "observation_changed");
                }

            }
        }
    }

    public String status(Player player)
    {
        if (!this.policy.enabled())
        {
            return "私人多人同步关闭";
        }

        Session session = this.sessions.get(player.getUniqueId());
        Publication publication = this.publications.get(player.getUniqueId());
        long ready = session == null ? 0
                : session.offers.values().stream().filter(offer -> offer.status == Status.READY).count();
        return "私人多人同步 " + (session == null ? "未协商" : "v1") + "；发布许可 " + canUpload(player) + "；观看许可 " + canView(player)
                + "；本人发布 " + (publication == null ? "未发布" : publication.kind) + "；接收他人模型就绪回执 " + ready + "；本人已验证资源缓存 "
                + (this.store != null && this.store.enabled() ? "开启" : "关闭") + "；上传窗口 "
                + (session != null && session.uploadCredit ? "已协商" : "传统限额");
    }

    /** Command-only diagnostics read bounded relay identities; no disk reads or audience discovery. */
    public List<String> statusLines(Player player)
    {
        if (!this.policy.enabled())
        {
            return List.of("§e私人模型分享：服务器已关闭此功能。");
        }

        UUID owner = player.getUniqueId();
        Session session = this.sessions.get(owner);
        Publication publication = this.publications.get(owner);
        long now = tick();
        List<String> lines = new ArrayList<>();
        lines.add("§7私人模型分享：协议 §f" + (session == null ? "未连接" : "v1 已连接") + "§7；发布权限 §f" + permission(canUpload(player))
                + "§7；观看权限 §f" + permission(canView(player)));
        lines.add("§7接收他人模型：" + (session == null ? "§e尚未连接"
                : (!session.viewState ? "§e旧客户端未报告渲染开关"
                        : (session.viewEnabled ? "§a客户端已开启" : "§e客户端已暂停接收（上传权限与存档不受此开关影响）"))));
        lines.add("§7服务器私人资源缓存：§f" + (this.store != null && this.store.enabled() ? "开启" : "关闭") + "§7；上传窗口：§f"
                + (session != null && session.uploadCredit ? "已协商" : "传统限额"));
        if (publication == null)
        {
            lines.add("§7本人发布：§f未发布§7；这不影响接收其他玩家的私人模型。");
        }

        else
        {
            lines.add("§7本人发布：§a已验证并发布§7；发布租期余 §f" + remaining(now, publication.lease) + " §7tick");
            addIdentity(lines, publication.sourceId, publication.kind, publication.hash, publication.generation);
            int ready = 0;
            List<String> viewers = new ArrayList<>();
            for (UUID viewer : publication.offeredViewers)
            {
                Session watching = this.sessions.get(viewer);
                Offer offer = watching == null ? null : watching.offers.get(owner);
                if (offer == null || offer.publication != publication)
                {
                    continue;
                }

                if (offer.status == Status.READY && remaining(now, offer.lease) > 0)
                {
                    ready++;
                }

                if (viewers.size() < DIAGNOSTIC_ENTRIES)
                {
                    viewers.add("§7  观众 §f" + playerName(viewer) + "§7：" + offerStage(offer, now));
                }

            }
            lines.add("§7本人模型观众：已发资源邀约 §f" + publication.offeredViewers.size() + "§7；就绪回执且租期有效 §f" + ready);
            lines.addAll(viewers);
            if (publication.offeredViewers.size() > viewers.size())
            {
                lines.add("§7  仅列出前 " + DIAGNOSTIC_ENTRIES + " 名观众。");
            }

        }
        if (session != null && session.upload != null)
        {
            Upload upload = session.upload;
            lines.add("§7本人上传：§e"
                    + (upload.validating ? "服务器正在校验" : "已接收 " + upload.offset + " / " + upload.expectedBytes + " 字节"));
            addIdentity(lines, upload.sourceId, upload.kind, upload.hash, upload.generation);
        }
        int incoming = session == null ? 0 : session.offers.size();
        lines.add("§7接收他人私人模型：§f" + incoming + " §7个资源关系（与本人发布独立）");
        int shown = 0;
        if (session != null)
        {
            for (Offer offer : session.offers.values())
            {
                if (shown++ >= DIAGNOSTIC_ENTRIES)
                {
                    break;
                }

                lines.add("§7  发布者 §f" + playerName(offer.publication.owner) + "§7：" + offerStage(offer, now));
                addIdentity(lines, offer.publication.sourceId, offer.publication.kind, offer.publication.hash,
                        offer.publication.generation);
            }
            if (incoming > DIAGNOSTIC_ENTRIES)
            {
                lines.add("§7  仅列出前 " + DIAGNOSTIC_ENTRIES + " 个资源关系。");
            }

            for (ObservationFailure failure : session.observationFailures.values())
            {
                if (distance(now, failure.tick) >= DIAGNOSTIC_TICKS)
                {
                    continue;
                }

                lines.add("§7最近停止接收 §f" + playerName(failure.owner) + "§7：§e" + failureReason(failure.reason)
                        + "§7（原阶段 " + failure.phase + "）");
                addIdentity(lines, failure.sourceId, failure.kind, failure.hash, failure.generation);
            }
        }
        lines.add("§7就绪回执只表示客户端报告资源可渲染，服务器已发送授权 ACK。");
        lines.add("§7客户端是否实际落盘、当前是否绘制模型：服务器无法直接确认。");
        return List.copyOf(lines);
    }

    private static String permission(boolean allowed)
    {
        return allowed ? "有" : "无";
    }

    private static long remaining(long now, long renewed)
    {
        return Math.max(0, LEASE_TICKS - distance(now, renewed));
    }

    private static String playerName(UUID id)
    {
        Player player = Bukkit.getPlayer(id);
        return player == null ? id.toString() : player.getName();
    }

    private static void addIdentity(List<String> lines, String sourceId, String kind, String hash, UUID generation)
    {
        lines.addAll(
                ServerMessages.wrapped("§7  模型：§f", (sourceId.isEmpty() ? "未提供模型 ID" : sourceId) + "（" + kind + "）"));
        lines.add("§7  资源 SHA-256：§f" + hash);
        lines.add("§7  发布代次：§f" + generation);
    }

    private static String offerStage(Offer offer, long now)
    {
        return switch (offer.status)
        {
        case OFFERED -> "§e资源邀约已发；等待客户端报告缓存情况";
        case CACHED -> "§e客户端报告已有缓存；等待可渲染回执";
        case QUEUED -> "§e客户端缺少缓存；资源传送排队中";
        case SENDING -> "§e正在发送资源 " + offer.offset + " / " + offer.publication.bytes.length + " 字节";
        case DELIVERED -> "§e资源已全部发送；等待校验和可渲染回执";
        case READY ->
            remaining(now, offer.lease) > 0 ? "§a收到可渲染回执，已发送授权 ACK；租期余 " + remaining(now, offer.lease) + " tick"
                    : "§e授权租期已到期，等待撤销";
        };
    }

    private static String failureReason(String reason)
    {
        return switch (reason)
        {
        case "asset_rejected" -> "客户端拒绝资源解析或渲染";
        case "offer_expired" -> "资源传送或可渲染回执超时";
        case "lease_expired" -> "渲染授权心跳超时";
        case "server_model_priority" -> "发布者改用服务器伪装，私人分享停止";
        case "owner_cleared" -> "发布者停止分享";
        case "upload_deleted" -> "发布者删除已上传资源";
        case "publisher_expired" -> "发布者离线、权限变化或发布心跳超时";
        case "out_of_range" -> "观察范围、追踪或观看权限不再满足";
        case "observation_changed" -> "传送或世界变化，等待重新授权";
        case "model_changed" -> "发布者切换模型";
        case "transfer_queue_full" -> "资源传送队列已满";
        case "tps_protection" -> "服务器负载保护暂停分享";
        case "viewer_paused" -> "观看者关闭客户端渲染，已暂停接收";
        default -> reason;
        };
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] bytes)
    {
        long started = this.resources != null && Bukkit.isPrimaryThread() ? System.nanoTime() : 0;
        try
        {
            receivePrivatePacket(channel, player, bytes);
        }
        finally
        {
            if (started != 0)
            {
                this.resources.recordWork(System.nanoTime() - started);
            }
        }
    }

    private void receivePrivatePacket(String channel, Player player, byte[] bytes)
    {
        if (!this.running || !CHANNEL.equals(channel) || bytes == null || bytes.length == 0
                || bytes.length > this.policy.maxPayload())
        {
            return;
        }

        if (!Bukkit.isPrimaryThread())
        {
            if (!this.plugin.isEnabled() || !this.limits.reserveInboundDispatch(bytes.length))
            {
                return;
            }

            byte[] copy = bytes.clone();
            try
            {
                this.plugin.getServer().getScheduler().runTask(this.plugin, () ->
                {
                    try
                    {
                        onPluginMessageReceived(channel, player, copy);
                    }
                    finally
                    {
                        this.limits.releaseInboundDispatch(copy.length);
                    }
                });
            }
            catch (RuntimeException stopped)
            {
                this.limits.releaseInboundDispatch(copy.length);
            }
            return;
        }
        if (!player.isOnline()
                || !this.limits.allowInboundDecode(player.getUniqueId(), bytes.length, this.nanoClock.getAsLong()))
        {
            return;
        }

        JsonObject packet;
        try
        {
            packet = decode(bytes);
        }
        catch (IOException | RuntimeException malformed)
        {
            if (this.limits.allowInbound(player.getUniqueId(), bytes.length, this.nanoClock.getAsLong()))
            {
                error(player, "invalid_private_payload");
            }
            return;
        }
        String type = packet.get("type").getAsString();
        UUID owner = player.getUniqueId();
        long tick = tick();
        Session inboundSession = this.sessions.get(owner);
        boolean credited = type.equals("upload_chunk") && inboundSession != null && inboundSession.uploadCredit
                && inboundSession.upload != null
                && inboundSession.upload.id.toString().equals(packet.get("uploadId").getAsString());
        if (!(credited ? this.limits.allowInboundCredited(owner, bytes.length, this.nanoClock.getAsLong())
                : this.limits.allowInbound(owner, bytes.length, this.nanoClock.getAsLong())))
        {
            return;
        }

        if (type.equals("hello"))
        {
            if (!this.rates.computeIfAbsent(owner, unused -> new Rates()).hello.take(this.nanoClock.getAsLong()))
            {
                return;
            }

            Session previous = this.sessions.remove(owner);
            if (previous != null)
            {
                endSession(owner, previous, "new_handshake");
            }

            Session session = new Session(owner);
            session.uploadCatalog = packet.getAsJsonArray("capabilities").asList().stream()
                    .anyMatch(value -> value.getAsString().equals(PrivateUploadCatalog.CAPABILITY));
            this.sessions.put(owner, session);
            session.uploadCredit = packet.getAsJsonArray("capabilities").asList().stream()
                    .anyMatch(value -> value.getAsString().equals(UPLOAD_CREDIT));
            session.viewState = packet.getAsJsonArray("capabilities").asList().stream()
                    .anyMatch(value -> value.getAsString().equals(VIEW_STATE));
            session.viewEnabled = !session.viewState
                    || packet.has("viewEnabled") && packet.get("viewEnabled").getAsBoolean();
            JsonObject ack = envelope("hello_ack");
            JsonArray capabilities = new JsonArray();
            capabilities.add(CAPABILITY);
            ack.add("capabilities", capabilities);
            if (session.uploadCatalog)
            {
                capabilities.add(PrivateUploadCatalog.CAPABILITY);
                ack.addProperty("uploadCatalogToken", session.uploadCatalogToken.toString());
            }
            if (session.uploadCredit)
            {
                capabilities.add(UPLOAD_CREDIT);
            }

            if (session.viewState)
            {
                capabilities.add(VIEW_STATE);
                ack.addProperty("viewEnabled", session.viewEnabled);
            }
            ack.addProperty("allowedUpload", canUpload(player));
            ack.addProperty("allowedView", canView(player));
            ack.addProperty("maxPayload", this.policy.maxPayload());
            ack.addProperty("maxBundleBytes", effectiveBundleLimit(this.policy));
            ack.addProperty("heartbeatTicks", HEARTBEAT_TICKS);
            ack.addProperty("leaseTicks", LEASE_TICKS);
            if (this.resources != null)
            {
                ack.addProperty("transferTotalTicks", this.resources.settings().network().transferTotalTicks());
                ack.addProperty("transferIdleTicks", this.resources.settings().network().transferIdleTicks());
                ack.addProperty("validationTimeoutTicks", this.resources.settings().tasks().taskTimeoutTicks());
            }
            if (!send(player, ack, false))
            {
                this.sessions.remove(owner);
            }

            else if (session.uploadCatalog)
            {
                updateCatalog(player, session);
                pumpCatalogs(tick);
            }
            return;
        }
        Session session = this.sessions.get(owner);
        if (session == null)
        {
            return;
        }

        if (type.equals("clear"))
        {
            cancelUpload(session);
            removePublication(owner, "owner_cleared");
            return;
        }
        if (!this.policy.enabled())
        {
            error(player, "private_sync_disabled");
            return;
        }
        try
        {
            switch (type)
            {
            case "upload_offer" -> offerUpload(player, session, packet, tick);
            case "upload_chunk" -> uploadChunk(player, session, packet, tick);
            case "upload_end" -> finishUpload(player, session, packet);
            case "private_status" -> feedback(player, session, packet, tick);
            case "private_ready" -> ready(player, session, packet, tick);
            case "private_heartbeat" -> heartbeat(player, session, packet, tick);
            case "private_state" -> updateState(player, packet, tick);
            case "private_event" -> event(player, packet, tick);
            case "upload_delete" -> deleteUpload(player, session, packet);
            default -> throw new IOException("unknown_private_message");
            }
        }
        catch (IOException | RuntimeException malformed)
        {
            error(player, "invalid_private_payload");
        }
    }

    private boolean canUpload(Player owner)
    {
        return this.policy.enabled() && owner.hasPermission(this.policy.uploadPermission());
    }

    private boolean canView(Player viewer)
    {
        return this.policy.enabled() && viewer.hasPermission(this.policy.viewPermission());
    }

    private boolean receivesModels(UUID viewer)
    {
        Session session = this.sessions.get(viewer);
        return session != null && session.viewEnabled;
    }

    private Set<UUID> serverDisguised()
    {
        Set<UUID> owners = this.serverDisguisedOwners.get();
        return owners == null ? Set.of() : Set.copyOf(owners);
    }

    private boolean isServerDisguised(UUID owner)
    {
        try
        {
            Set<UUID> owners = this.serverDisguisedOwners.get();
            return owners != null && owners.contains(owner);
        }
        catch (RuntimeException unavailable)
        {
            return true;
        }
    }

    private void offerUpload(Player player, Session session, JsonObject packet, long tick) throws IOException
    {
        if (!canUpload(player))
        {
            error(player, "private_upload_denied");
            return;
        }
        if (serverDisguised().contains(player.getUniqueId()))
        {
            error(player, "server_model_priority");
            return;
        }
        int bytes = (int) PrivateModelBundle.integer(packet, "bytes", 1, effectiveBundleLimit(this.policy));
        String hash = PrivateModelBundle.string(packet, "hash", 64),
                kind = PrivateModelBundle.string(packet, "kind", 16);
        String sourceId = packet.has("modelId") ? PrivateModelBundle.string(packet, "modelId", 128) : "";
        if (packet.has("modelId") && !session.uploadCatalog)
        {
            error(player, "invalid_private_payload");
            return;
        }
        UUID generation = uuid(packet, "generation");
        Publication current = this.publications.get(player.getUniqueId());
        if (current != null && current.generation.equals(generation))
        {
            if (current.hash.equals(hash) && current.kind.equals(kind) && current.bytes.length == bytes
                    && current.sourceId.equals(sourceId))
            {
                sendCommitted(player, session, current);
            }

            else
            {
                error(player, "private_generation_reused");
            }
            return;
        }
        if (session.upload != null)
        {
            Upload pending = session.upload;
            if (pending.generation.equals(generation) && pending.hash.equals(hash) && pending.kind.equals(kind)
                    && pending.expectedBytes == bytes && pending.sourceId.equals(sourceId))
            {
                if (!pending.validating)
                {
                    acceptUpload(player, session, pending);
                }
                return;
            }
            error(player, "private_upload_busy");
            return;
        }
        if (!acceptsNewWork())
        {
            resourceError(player, new ResourceError("tps_protection", "upload", true, 100));
            return;
        }
        if (this.resources != null && !this.publications.containsKey(session.owner)
                && this.publications.size() >= this.resources.settings().models().maxPublications())
        {
            resourceError(player, new ResourceError("publication_limit", "upload", true, 100));
            return;
        }
        if (this.uploads + this.deleting.size() >= 2
                || this.deleting.contains(new OwnerHash(player.getUniqueId(), hash))
                || this.deletingSources.contains(new OwnerSource(player.getUniqueId(), sourceId)))
        {
            error(player, "private_upload_busy");
            return;
        }
        long reservation = bytes + (long) PrivateModelBundle.MAX_BYTES;
        if (this.storedBytes + this.reservedBytes + reservation > this.policy.maxStoredBytes())
        {
            error(player, "private_storage_busy");
            return;
        }
        if (!this.limits.allowRequest(player.getUniqueId(), "private_upload", "", tick, 200)
                || !this.limits.allowAsset(player.getUniqueId(), hash, tick))
        {
            error(player, "private_upload_cooldown");
            return;
        }
        if (!this.limits.reserveTransfer(player.getUniqueId()))
        {
            error(player, "private_upload_busy");
            return;
        }
        JsonObject appearance = appearance(packet.get("appearance"));
        if (!fitsAppearance(appearance, new JsonObject()))
        {
            this.limits.releaseTransfer(player.getUniqueId());
            error(player, "private_appearance_size");
            return;
        }
        boolean probe = this.store != null && this.store.enabled();
        Upload upload = new Upload(player.getUniqueId(), UUID.randomUUID(), generation, hash, kind, bytes, appearance,
                tick, reservation, !probe);
        upload.sourceId = sourceId;
        session.upload = upload;
        this.reservedBytes += reservation;
        this.uploads++;
        if (probe)
        {
            probeCache(player, session, upload);
            return;
        }
        acceptUpload(player, session, upload);
    }

    private void acceptUpload(Player player, Session session, Upload upload)
    {
        JsonObject accepted = envelope("upload_accept");
        accepted.addProperty("uploadId", upload.id.toString());
        accepted.addProperty("generation", upload.generation.toString());
        accepted.addProperty("hash", upload.hash);
        accepted.addProperty("chunkBytes", chunkBytes());
        // The client may repeat this same authorized offer after a lost/budget-deferred ACK.
        // Repeating accept never reallocates bytes or resets the upload's offset or total deadline.
        send(player, accepted, false);
        if (session.uploadCredit)
        {
            grantUploadCredits(player, session, upload);
        }

    }

    /** Absolute index windows survive duplicate ACKs and grants; unused credits never reset traffic counters. */
    private void grantUploadCredits(Player player, Session session, Upload upload)
    {
        if (player == null || !player.isOnline() || upload.validating || upload.cancelled || !startsTransfer())
        {
            return;
        }

        int chunks = (upload.expectedBytes + chunkBytes() - 1) / chunkBytes();
        while (upload.creditLimit < Math.min(chunks, upload.index + 2)
                && this.limits.grantUploadCredit(session.owner, this.policy.maxPayload(), this.nanoClock.getAsLong()))
        {
            upload.creditLimit++;
        }

        if (upload.creditLimit <= upload.index)
        {
            return;
        }

        long tick = tick();
        if (upload.creditLimit == upload.sentCreditLimit && distance(tick, upload.lastCreditSend) < 20)
        {
            return;
        }

        JsonObject credit = envelope("upload_credit");
        credit.addProperty("uploadId", upload.id.toString());
        credit.addProperty("nextIndex", upload.creditLimit);
        if (send(player, credit, false))
        {
            upload.sentCreditLimit = upload.creditLimit;
            upload.lastCreditSend = tick;
        }
    }

    private void deleteUpload(Player player, Session session, JsonObject packet) throws IOException
    {
        UUID request = uuid(packet, "requestId");
        String source = PrivateModelBundle.string(packet, "modelId", 128),
                hash = PrivateModelBundle.string(packet, "hash", 64),
                kind = PrivateModelBundle.string(packet, "kind", 16);
        int bytes = (int) PrivateModelBundle.integer(packet, "bytes", 22, PrivateModelBundle.MAX_BYTES);
        long sequence = PrivateModelBundle.integer(packet, "requestSequence", 1, MAX_SEQUENCE);
        if (!session.uploadCatalog)
        {
            deleteFailed(player, session, new Delete(request, source, hash, kind, bytes, sequence),
                    "private_delete_denied");
            return;
        }
        if (!uuid(packet, "catalogToken").equals(session.uploadCatalogToken))
        {
            JsonObject failed = envelope("upload_delete_failed");
            failed.addProperty("requestId", request.toString());
            addErrorMetadata(failed, new ResourceError("private_delete_stale", "cleanup", false, 0));
            queueDeleteReply(player, session, failed);
            return;
        }
        Delete previous = session.deletes.get(request);
        if (previous != null)
        {
            if (!previous.sourceId.equals(source) || !previous.hash.equals(hash) || !previous.kind.equals(kind)
                    || previous.bytes != bytes || previous.sequence != sequence)
            {
                throw new IOException("delete_request_reused");
            }

            if (previous.reply != null)
            {
                queueDeleteReply(player, session, previous.reply);
            }
            return;
        }
        Delete operation = new Delete(request, source, hash, kind, bytes, sequence);
        if (sequence <= session.lastDeleteSequence)
        {
            deleteFailed(player, session, operation, "private_delete_stale");
            return;
        }
        session.lastDeleteSequence = sequence;
        if (!session.uploadCatalog || !canUpload(player) || this.store == null || !this.store.enabled())
        {
            deleteFailed(player, session, operation, "private_delete_denied");
            return;
        }
        boolean exact = this.store.listUploaded(session.owner).stream()
                .anyMatch(model -> model.sourceId().equals(source) && model.hash().equals(hash)
                        && model.kind().equals(kind) && model.bytes() == bytes);
        if (!exact)
        {
            deleteFailed(player, session, operation, "private_delete_not_found");
            return;
        }
        OwnerHash identity = new OwnerHash(session.owner, hash);
        OwnerSource sourceIdentity = new OwnerSource(session.owner, source);
        boolean receiving = this.sessions.values().stream()
                .anyMatch(active -> active.upload != null && active.upload.owner.equals(session.owner)
                        && (active.upload.hash.equals(hash) || active.upload.sourceId.equals(source)));
        boolean validating = this.validations.stream().anyMatch(active -> active.owner.equals(session.owner)
                && (active.hash.equals(hash) || active.sourceId.equals(source)));
        boolean full = session.deletes.size() >= 16 && session.deletes.entrySet().stream().noneMatch(
                entry -> entry.getValue().reply != null && !session.deleteReplies.containsKey(entry.getKey()));
        if (full || receiving || validating || this.uploads + this.deleting.size() >= 2
                || session.deletes.values().stream().anyMatch(active -> active.reply == null)
                || this.deleting.contains(identity) || this.deletingSources.contains(sourceIdentity))
        {
            deleteFailed(player, session, operation, "private_delete_busy");
            return;
        }
        this.deleting.add(identity);
        this.deletingSources.add(sourceIdentity);
        Publication deletingPublication = this.publications.get(session.owner);
        if (deletingPublication != null && !deletingPublication.sourceId.equals(source))
        {
            deletingPublication = null;
        }

        Publication publicationToRemove = deletingPublication;
        rememberDelete(session, operation);
        PrivateModelStore current = this.store;
        submit(session.owner, 4L * 1024 * 1024, "cleanup", () ->
        {
            boolean removed = false;
            String failure = null;
            ResourceProtection.checkCancelled();
            try
            {
                removed = current.deleteValidated(session.owner, source, hash, kind, bytes);
            }
            catch (IOException invalid)
            {
                failure = "cache_delete_pending_validation".equals(invalid.getMessage()) ? "private_delete_busy"
                        : "private_delete_failed";
                if (!failure.equals("private_delete_busy"))
                {
                    cacheFailure(invalid);
                }

            }
            catch (RuntimeException invalid)
            {
                failure = "private_delete_failed";
                cacheFailure(invalid);
            }
            return new DeleteResult(removed, failure);
        }, result ->
        {
            this.deleting.remove(identity);
            this.deletingSources.remove(sourceIdentity);
            if (!this.running)
            {
                return;
            }

            if (result.removed && publicationToRemove != null
                    && this.publications.get(session.owner) == publicationToRemove)
            {
                removePublication(session.owner, "upload_deleted");
            }

            Session active = this.sessions.get(session.owner);
            Player owner = Bukkit.getPlayer(session.owner);
            if (active != session || owner == null || !owner.isOnline())
            {
                return;
            }

            if (!result.removed)
            {
                deleteFailed(owner, session, operation,
                        result.failure == null ? "private_delete_not_found" : result.failure);
                return;
            }
            JsonObject reply = envelope("upload_deleted");
            reply.addProperty("requestId", request.toString());
            reply.addProperty("modelId", source);
            reply.addProperty("hash", hash);
            operation.reply = reply;
            queueDeleteReply(owner, session, reply);
            updateCatalog(owner, session);
        }, failure ->
        {
            this.deleting.remove(identity);
            this.deletingSources.remove(sourceIdentity);
            Player owner = Bukkit.getPlayer(session.owner);
            if (this.running && this.sessions.get(session.owner) == session && owner != null && owner.isOnline())
            {
                deleteFailed(owner, session, operation, failure.code());
            }

        });
    }

    private void rememberDelete(Session session, Delete operation)
    {
        while (session.deletes.size() >= 16)
        {
            UUID terminal = session.deletes.entrySet().stream()
                    .filter(entry -> entry.getValue().reply != null
                            && !session.deleteReplies.containsKey(entry.getKey()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (terminal == null)
            {
                break;
            }
            session.deletes.remove(terminal);
        }
        if (session.deletes.size() < 16)
        {
            session.deletes.put(operation.request, operation);
        }

    }

    private void deleteFailed(Player owner, Session session, Delete operation, String code)
    {
        JsonObject reply = envelope("upload_delete_failed");
        reply.addProperty("requestId", operation.request.toString());
        boolean retry = code.contains("busy") || code.endsWith("timeout")
                || Set.of("asset_queue_full", "memory_limit", "player_task_limit").contains(code);
        addErrorMetadata(reply, new ResourceError(code, "cleanup", retry, retry ? 100 : 0));
        operation.reply = reply;
        rememberDelete(session, operation);
        queueDeleteReply(owner, session, reply);
    }

    private void queueDeleteReply(Player owner, Session session, JsonObject reply)
    {
        UUID id = UUID.fromString(reply.get("requestId").getAsString());
        if (send(owner, reply, false))
        {
            session.deleteReplies.remove(id);
        }

        else if (session.deleteReplies.size() < 16 || session.deleteReplies.containsKey(id))
        {
            session.deleteReplies.put(id, reply);
            this.pendingDeleteReplies.add(session.owner);
        }
    }

    private void pumpDeleteReplies()
    {
        for (UUID id : List.copyOf(this.pendingDeleteReplies))
        {
            Session session = this.sessions.get(id);
            Player owner = Bukkit.getPlayer(id);
            if (session == null || owner == null || !owner.isOnline())
            {
                this.pendingDeleteReplies.remove(id);
                continue;
            }
            if (!session.deleteReplies.isEmpty())
            {
                queueDeleteReply(owner, session, session.deleteReplies.values().iterator().next());
            }

            if (session.deleteReplies.isEmpty())
            {
                this.pendingDeleteReplies.remove(id);
            }

        }
    }

    private void probeCache(Player player, Session session, Upload upload)
    {
        upload.validating = true;
        this.validations.add(upload);
        upload.workerStarted = false;
        upload.workerActive = true;
        submit(upload.owner, validationReservation(upload), "validation", () ->
        {
            upload.workerStarted = true;
            byte[] cached = null;
            try
            {
                ResourceProtection.checkCancelled();
                if (upload.cancelled)
                {
                    return null;
                }

                cached = this.store.load(upload.owner, upload.hash, upload.kind, upload.expectedBytes);
                ResourceProtection.checkCancelled();
                if (upload.cancelled)
                {
                    return null;
                }

                if (cached != null && !upload.sourceId.isEmpty())
                {
                    this.store.recordValidatedIdentity(upload.owner, upload.sourceId, upload.hash, upload.kind,
                            cached.length);
                }
            }
            catch (IOException | RuntimeException failure)
            {
                cacheFailure(failure);
            }
            finally
            {
                upload.workerActive = false;
            }
            return cached;
        }, result ->
        {
            boolean current = this.running && this.sessions.get(upload.owner) == session && session.upload == upload;
            if (!current)
            {
                releaseUpload(upload);
                return;
            }
            if (!player.isOnline() || !canUpload(player) || isServerDisguised(upload.owner))
            {
                session.upload = null;
                releaseUpload(upload);
                error(player, "private_upload_denied");
                return;
            }
            if (result != null)
            {
                upload.bytes = result;
                commitUpload(player, session, upload, null);
                return;
            }
            this.validations.remove(upload);
            upload.validating = false;
            upload.bytes = new byte[upload.expectedBytes];
            upload.lastProgress = tick();
            acceptUpload(player, session, upload);
        }, failure -> uploadJobFailed(player, session, upload, failure));
    }

    private int chunkBytes()
    {
        return negotiatedChunkBytes(this.policy.maxPayload());
    }

    static int negotiatedChunkBytes(int maxPayload)
    {
        return Math.min(CHUNK_BYTES, Math.max(384, (maxPayload - 512) * 3 / 4));
    }

    static int effectiveBundleLimit(Policy policy)
    {
        return Math.min(policy.maxBundleBytes(), negotiatedChunkBytes(policy.maxPayload()) * 1100);
    }

    private void uploadChunk(Player player, Session session, JsonObject packet, long tick) throws IOException
    {
        Upload upload = session.upload;
        if (upload == null || !upload.id.equals(uuid(packet, "uploadId")) || upload.validating)
        {
            return;
        }

        if (!canUpload(player))
        {
            cancelUpload(session);
            error(player, "private_upload_denied");
            return;
        }
        int index = (int) PrivateModelBundle.integer(packet, "index", 0, 16383);
        String text = PrivateModelBundle.string(packet, "data", 16000);
        byte[] chunk;
        try
        {
            chunk = Base64.getDecoder().decode(text);
        }
        catch (IllegalArgumentException invalid)
        {
            cancelUpload(session);
            throw new IOException("base64", invalid);
        }
        if (index != upload.index || chunk.length == 0 || chunk.length > chunkBytes()
                || chunk.length > upload.bytes.length - upload.offset
                || !Base64.getEncoder().encodeToString(chunk).equals(text))
        {
            cancelUpload(session);
            throw new IOException("upload_chunk");
        }
        System.arraycopy(chunk, 0, upload.bytes, upload.offset, chunk.length);
        upload.offset += chunk.length;
        upload.index++;
        upload.lastProgress = tick;
        if (session.uploadCredit)
        {
            grantUploadCredits(player, session, upload);
        }

    }

    private void finishUpload(Player player, Session session, JsonObject packet) throws IOException
    {
        UUID uploadId = uuid(packet, "uploadId");
        Upload upload = session.upload;
        if (upload == null)
        {
            Publication previous = session.committedPublication;
            if (uploadId.equals(session.committedUploadId) && previous != null
                    && this.publications.get(session.owner) == previous && canUpload(player)
                    && !isServerDisguised(session.owner))
            {
                sendCommitted(player, session, previous);
            }

            return;
        }
        if (!upload.id.equals(uploadId) || upload.validating)
        {
            return;
        }

        if (!canUpload(player) || upload.offset != upload.bytes.length)
        {
            cancelUpload(session);
            error(player, "private_upload_integrity");
            return;
        }
        upload.validating = true;
        this.validations.add(upload);
        this.limits.clearUploadCredits(session.owner);
        upload.workerStarted = false;
        upload.workerActive = true;
        submit(upload.owner, validationReservation(upload), "validation", () ->
        {
            upload.workerStarted = true;
            String failure = null;
            try
            {
                ResourceProtection.checkCancelled();
                if (upload.cancelled)
                {
                    return "upload_cancelled";
                }

                if (!PrivateModelBundle.hash(upload.bytes).equals(upload.hash))
                {
                    failure = "private_upload_integrity";
                }

                else
                {
                    if (this.resources == null)
                    {
                        PrivateModelBundle.validate(upload.bytes, upload.kind);
                    }

                    else
                    {
                        PrivateModelBundle.validate(upload.bytes, upload.kind, this.resources.complexityLimits());
                    }

                    ResourceProtection.checkCancelled();
                    if (upload.cancelled)
                    {
                        return "upload_cancelled";
                    }

                    if (this.store != null && this.store.enabled())
                    {
                        try
                        {
                            if (upload.sourceId.isEmpty())
                            {
                                this.store.saveValidated(upload.owner, upload.hash, upload.bytes);
                            }

                            else
                            {
                                this.store.saveValidated(upload.owner, upload.sourceId, upload.hash, upload.kind,
                                        upload.bytes);
                            }

                        }
                        catch (IOException | RuntimeException cacheFailed)
                        {
                            ResourceProtection.checkCancelled();
                            if (Set.of("disk_space_low", "disk_budget", "disk_unavailable")
                                    .contains(cacheFailed.getMessage()))
                            {
                                return cacheFailed.getMessage();
                            }

                            cacheFailure(cacheFailed);
                        }
                    }

                }
            }
            catch (IOException | RuntimeException invalid)
            {
                failure = invalid.getMessage() != null && invalid.getMessage().startsWith("model_complexity")
                        ? "model_complexity_limit"
                        : "private_bundle_invalid";
            }
            finally
            {
                upload.workerActive = false;
            }
            return failure;
        }, result -> commitUpload(player, session, upload, result),
                failure -> uploadJobFailed(player, session, upload, failure));
    }

    private void uploadJobFailed(Player player, Session session, Upload upload, ResourceError failure)
    {
        if (!upload.workerStarted)
        {
            upload.workerActive = false;
        }

        upload.cancelled = true;
        boolean current = this.sessions.get(upload.owner) == session && session.upload == upload;
        if (current)
        {
            cancelUpload(session);
        }
        else
        {
            releaseUpload(upload);
        }

        if (current)
        {
            resourceError(player, failure, upload);
        }

    }

    /** Raw receiving has its own smaller bound; parsing also reserves JSON/string/decoded expansion space. */
    private static long validationReservation(Upload upload)
    {
        return upload.reservation + 4L * PrivateModelBundle.MAX_BYTES;
    }

    private void commitUpload(Player player, Session session, Upload upload, String failure)
    {
        boolean current = this.running && this.sessions.get(player.getUniqueId()) == session
                && session.upload == upload;
        if (current)
        {
            session.upload = null;
        }

        releaseUpload(upload);
        if (!current)
        {
            return;
        }

        if (failure != null || !player.isOnline() || !canUpload(player))
        {
            String code = failure == null ? "permission_revoked" : failure;
            boolean disk = Set.of("disk_space_low", "disk_budget", "disk_unavailable").contains(code);
            resourceError(player, new ResourceError(code, "validation", disk, disk ? 600 : 0), upload);
            return;
        }
        if (isServerDisguised(player.getUniqueId()))
        {
            error(player, "server_model_priority");
            return;
        }
        if (!acceptsNewWork())
        {
            resourceError(player, new ResourceError("tps_protection", "upload", true, 600), upload);
            return;
        }
        if (this.resources != null && !this.publications.containsKey(upload.owner)
                && this.publications.size() >= this.resources.settings().models().maxPublications())
        {
            resourceError(player, new ResourceError("publication_limit", "upload", true, 100));
            return;
        }
        removePublication(player.getUniqueId(), "model_changed");
        Publication publication = new Publication(player.getUniqueId(), upload.generation, upload.hash, upload.kind,
                upload.bytes, upload.appearance, tick());
        publication.sourceId = upload.sourceId;
        publication.flying = player.isFlying();
        this.publications.put(player.getUniqueId(), publication);
        this.storedBytes += upload.bytes.length;
        session.committedUploadId = upload.id;
        session.committedPublication = publication;
        sendCommitted(player, session, publication);
        updateCatalog(player, session);
    }

    private void sendCommitted(Player player, Session session, Publication publication)
    {
        JsonObject committed = envelope("upload_committed");
        committed.addProperty("generation", publication.generation.toString());
        committed.addProperty("hash", publication.hash);
        if (send(player, committed, false))
        {
            session.pendingCommit = null;
            clearPendingIndex(session);
        }
        else
        {
            session.pendingCommit = publication;
            this.pendingControls.add(session.owner);
        }
    }

    private synchronized void cacheFailure(Exception failure)
    {
        if (failure instanceof CancellationException)
        {
            return;
        }

        if (this.cacheWarning)
        {
            return;
        }
        this.cacheWarning = true;
        this.plugin.getLogger().warning("私人模型磁盘缓存不可用，继续普通上传：" + failure.getClass().getSimpleName());
    }

    private void cancelUpload(Session session)
    {
        Upload upload = session.upload;
        if (upload == null)
        {
            return;
        }
        session.upload = null;
        upload.cancelled = true;
        this.limits.clearUploadCredits(session.owner);
        if (upload.transferReserved)
        {
            this.limits.releaseTransfer(session.owner);
            upload.transferReserved = false;
        }
        // A cancelled asynchronous validator still holds raw/expanded bytes. Preserve its global memory
        // reservation until that exact worker returns; a new hello cannot open unlimited validators.
        if (!upload.validating)
        {
            releaseUpload(upload);
        }

    }

    private void releaseUpload(Upload upload)
    {
        if (upload.workerActive)
        {
            return;
        }

        if (upload.released)
        {
            return;
        }
        upload.released = true;
        this.validations.remove(upload);
        this.reservedBytes -= upload.reservation;
        this.uploads--;
        if (upload.transferReserved)
        {
            this.limits.releaseTransfer(upload.owner);
            upload.transferReserved = false;
        }
    }

    private void feedback(Player player, Session session, JsonObject packet, long tick) throws IOException
    {
        Offer offer = findOffer(session, uuid(packet, "offerId"), PrivateModelBundle.string(packet, "hash", 64));
        if (offer == null || !authorized(player, offer.publication, serverDisguised()))
        {
            return;
        }

        String status = PrivateModelBundle.string(packet, "status", 16);
        if (status.equals("rejected"))
        {
            // A renderer can fail after caching, downloading or acknowledging readiness. Retain this
            // owner/generation for the session; render_ready's shared attempt reset must not reoffer it.
            session.rejected.put(offer.publication.owner, offer.publication.generation);
            removeOffer(player, session, offer, "asset_rejected");
            return;
        }
        if (offer.status != Status.OFFERED)
        {
            return;
        }

        switch (status)
        {
        case "cached" ->
        {
            offer.status = Status.CACHED;
            offer.lastProgress = tick;
        }
        case "missing" ->
        {
            if (!this.limits.reserveWaiting(session.owner))
            {
                resourceError(player, new ResourceError("asset_queue_full", "download", true, 100));
                removeOffer(player, session, offer, "transfer_queue_full");
                return;
            }
            offer.waitingReserved = true;
            offer.status = Status.QUEUED;
            offer.lastProgress = tick;
            this.pendingTransfers.add(offer);
        }
        default -> throw new IOException("status");
        }
    }

    private void ready(Player player, Session session, JsonObject packet, long tick) throws IOException
    {
        UUID owner = uuid(packet, "owner"), generation = uuid(packet, "generation");
        String hash = PrivateModelBundle.string(packet, "hash", 64);
        Offer offer = session.offers.get(owner);
        if (offer == null || !matches(offer.publication, generation, hash)
                || !Set.of(Status.CACHED, Status.DELIVERED, Status.READY).contains(offer.status)
                || !authorized(player, offer.publication, serverDisguised()))
        {
            error(player, "private_render_not_authorized");
            return;
        }
        JsonObject ack = binding("private_ack", offer.publication);
        if (send(player, ack, false))
        {
            offer.status = Status.READY;
            offer.lease = tick;
            this.limits.pushRenderReady(player.getUniqueId(), hash);
            sendState(player, offer.publication);
        }
    }

    private void heartbeat(Player player, Session session, JsonObject packet, long tick) throws IOException
    {
        if (packet.has("viewEnabled"))
        {
            if (!session.viewState)
            {
                throw new IOException("view_state_not_negotiated");
            }

            updateViewState(player, session, booleanValue(packet.get("viewEnabled")));
        }
        JsonArray bindings = packet.getAsJsonArray("bindings");
        Set<UUID> owners = new HashSet<>();
        Set<UUID> disguised = serverDisguised();
        JsonArray renewed = new JsonArray();
        for (JsonElement element : bindings)
        {
            JsonObject entry = PrivateModelBundle.object(element);
            UUID owner = uuid(entry, "owner"), generation = uuid(entry, "generation");
            String hash = PrivateModelBundle.string(entry, "hash", 64);
            if (!owners.add(owner))
            {
                throw new IOException("duplicate_binding");
            }

            if (owner.equals(player.getUniqueId()))
            {
                Publication own = this.publications.get(owner);
                if (canUpload(player) && matches(own, generation, hash))
                {
                    own.lease = tick;
                    renewed.add(entry.deepCopy());
                }
            }
            else
            {
                Offer offer = session.offers.get(owner);
                if (offer != null && offer.status == Status.READY && matches(offer.publication, generation, hash)
                        && authorized(player, offer.publication, disguised))
                {
                    offer.lease = tick;
                    renewed.add(entry.deepCopy());
                }
            }
        }
        JsonObject confirmation = envelope("heartbeat");
        confirmation.add("bindings", renewed);
        if (session.viewState)
        {
            confirmation.addProperty("viewEnabled", session.viewEnabled);
        }

        confirmation.addProperty("allowedUpload", canUpload(player));
        confirmation.addProperty("allowedView", canView(player));
        send(player, confirmation, false);
    }

    private void updateViewState(Player viewer, Session session, boolean enabled)
    {
        if (session.viewEnabled == enabled)
        {
            return;
        }

        session.viewEnabled = enabled;
        if (!enabled)
        {
            for (Offer offer : List.copyOf(session.offers.values()))
            {
                removeOffer(viewer, session, offer, "viewer_paused");
            }

            this.audiences.removeViewer(session.owner);
        }
        // Resuming uses the normal phased audience discovery; no all-publication scan on a toggle.
    }

    private Publication owned(Player player, JsonObject packet) throws IOException
    {
        Publication publication = this.publications.get(player.getUniqueId());
        return canUpload(player)
                && matches(publication, uuid(packet, "generation"), PrivateModelBundle.string(packet, "hash", 64))
                        ? publication
                        : null;
    }

    private void updateState(Player player, JsonObject packet, long tick) throws IOException
    {
        Publication publication = owned(player, packet);
        if (publication == null)
        {
            error(player, "private_state_not_authorized");
            return;
        }
        if (!this.rates.computeIfAbsent(player.getUniqueId(), unused -> new Rates()).state
                .take(this.nanoClock.getAsLong()))
        {
            return;
        }

        JsonObject extra = extra(packet.get("extra"));
        long sequence = PrivateModelBundle.integer(extra, "sequence", 0, MAX_SEQUENCE);
        if (sequence < publication.extra.get("sequence").getAsLong())
        {
            return;
        }

        JsonObject appearance = appearance(packet.get("appearance"));
        if (!fitsAppearance(appearance, extra))
        {
            error(player, "private_appearance_size");
            return;
        }
        publication.lease = tick;
        // Periodic replay of the owner's final state repairs an inbound-budget loss without
        // allocating a new action sequence or replaying an unchanged author animation.
        if (publication.appearance.equals(appearance) && publication.extra.equals(extra))
        {
            return;
        }

        publication.appearance = appearance;
        publication.extra = extra;
        publication.sequence++;
        publication.lease = tick;
        broadcastState(publication);
    }

    private void broadcastState(Publication publication)
    {
        Set<UUID> disguised = serverDisguised();
        for (UUID viewerId : this.audiences.viewers(publication.owner, publication.generation))
        {
            Session session = this.sessions.get(viewerId);
            if (session == null)
            {
                continue;
            }

            Offer offer = session.offers.get(publication.owner);
            Player viewer = Bukkit.getPlayer(viewerId);
            if (offer != null && offer.status == Status.READY && offer.publication == publication
                    && authorized(viewer, publication, disguised))
            {
                sendState(viewer, publication);
            }

        }
    }

    private void event(Player player, JsonObject packet, long tick) throws IOException
    {
        Publication publication = owned(player, packet);
        if (publication == null || serverDisguised().contains(player.getUniqueId()))
        {
            error(player, "private_event_not_authorized");
            return;
        }
        if (!this.rates.computeIfAbsent(player.getUniqueId(), unused -> new Rates()).event
                .take(this.nanoClock.getAsLong()))
        {
            return;
        }

        publication.lease = tick;
        JsonObject event = binding("private_event", publication);
        event.addProperty("sequence", ++publication.eventSequence);
        event.add("args", packet.get("args").deepCopy());
        // Mature ysm.sync is relay-only: the owner also receives exactly one authoritative echo.
        send(player, event, false);
        Set<UUID> disguised = serverDisguised();
        for (UUID viewerId : this.audiences.viewers(publication.owner, publication.generation))
        {
            Session session = this.sessions.get(viewerId);
            if (session == null)
            {
                continue;
            }

            Offer offer = session.offers.get(publication.owner);
            Player viewer = Bukkit.getPlayer(viewerId);
            if (offer != null && offer.status == Status.READY && offer.publication == publication
                    && authorized(viewer, publication, disguised))
            {
                send(viewer, event, false);
            }

        }
    }

    private void maintain()
    {
        long started = System.nanoTime();
        try
        {
            maintainTick();
        }
        finally
        {
            if (this.resources != null)
            {
                this.resources.recordWork(System.nanoTime() - started);
            }
        }
    }

    private void maintainTick()
    {
        if (!this.running || !this.policy.enabled() && this.sessions.isEmpty() && this.publications.isEmpty()
                && this.uploads == 0)
        {
            return;
        }

        long tick = tick();
        boolean validate = !this.maintenanceInitialized
                || distance(tick, this.lastMaintenanceTick) >= this.validationTicks;
        refreshCatalog(tick);
        if (validate)
        {
            for (Upload completed : List.copyOf(this.validations))
            {
                if (completed.cancelled && !completed.workerActive)
                {
                    releaseUpload(completed);
                }

            }

            this.maintenanceInitialized = true;
            this.lastMaintenanceTick = tick;
            this.limits.pruneOffline(id ->
            {
                Player player = Bukkit.getPlayer(id);
                return player != null && player.isOnline();
            });
            for (Publication publication : List.copyOf(this.publications.values()))
            {
                Player owner = Bukkit.getPlayer(publication.owner);
                if (owner == null || !owner.isOnline() || !canUpload(owner)
                        || distance(tick, publication.lease) >= LEASE_TICKS)

                {
                    removePublication(publication.owner, "publisher_expired");
                }

            }
            for (Session session : List.copyOf(this.sessions.values()))
            {
                Player viewer = Bukkit.getPlayer(session.owner);
                if (viewer == null || !viewer.isOnline())
                {
                    this.sessions.remove(session.owner);
                    endSession(session.owner, session, "offline");
                    this.rates.remove(session.owner);
                    continue;
                }
                updateCatalog(viewer, session);
                Upload upload = session.upload;
                int total = this.resources == null ? TRANSFER_TIMEOUT
                        : this.resources.settings().network().transferTotalTicks();
                int idle = this.resources == null ? 300 : this.resources.settings().network().transferIdleTicks();
                if (upload != null && !canUpload(viewer))
                {
                    cancelUpload(session);
                    resourceError(viewer, new ResourceError("permission_revoked", "upload", false, 0), upload);
                }
                else if (upload != null && !upload.validating
                        && (distance(tick, upload.created) >= total || distance(tick, upload.lastProgress) >= idle))
                {
                    cancelUpload(session);
                    resourceError(viewer, new ResourceError("upload_timeout", "upload", true, 200), upload);
                }
            }
        }
        if (this.resources != null && this.resources.state() == ResourceProtection.State.EMERGENCY)
        {
            int remaining = this.resources.settings().models().emergencyRemovalsPerTick();
            for (UUID owner : List.copyOf(this.publications.keySet()))
            {
                if (remaining-- <= 0)
                {
                    break;
                }
                removePublication(owner, "tps_protection");
            }
        }
        for (Session session : this.sessions.values())
        {
            if (session.uploadCredit && session.upload != null && !session.upload.validating)

            {
                grantUploadCredits(Bukkit.getPlayer(session.owner), session, session.upload);
            }

        }

        pumpDeleteReplies();
        pumpCatalogs(tick);
        // A disabled/private-idle relay never asks the ModelEngine backend for its owners.
        if (!this.policy.enabled() || this.publications.isEmpty())
        {
            return;
        }

        for (Publication publication : this.publications.values())
        {
            // OpenYSM PlayerStateSynchronizer samples authoritative server abilities for remote players.
            // Reuse the active publication walk; a client cannot publish or renew this state itself.
            Player owner = Bukkit.getPlayer(publication.owner);
            if (owner != null && owner.isOnline())
            {
                boolean flying = owner.isFlying();
                if (publication.flying != flying)
                {
                    publication.flying = flying;
                    publication.sequence++;
                    broadcastState(publication);
                }
            }
            if (acceptsNewWork() && this.audiences.due(publication.owner, publication.generation, tick)
                    && (this.resources == null || this.resources.tryWork()) && this.audiences.refreshIfDue(
                            publication.owner, publication.generation, tick, () -> discoverAudience(publication)))

            {
                for (UUID viewer : this.audiences.viewers(publication.owner, publication.generation))
                {
                    offer(Bukkit.getPlayer(viewer), publication, tick);
                }

            }

        }
        retryStates();
        if (validate)
        {
            Set<UUID> disguised;
            try
            {
                disguised = serverDisguised();
            }
            catch (RuntimeException unavailable)
            {
                disguised = Set.copyOf(this.publications.keySet());
            }
            for (Session session : this.sessions.values())
            {
                Player viewer = Bukkit.getPlayer(session.owner);
                for (Offer offer : List.copyOf(session.offers.values()))
                {
                    if (!authorized(viewer, offer.publication, disguised))
                    {
                        removeOffer(viewer, session, offer,
                                disguised.contains(offer.publication.owner) ? "server_model_priority" : "out_of_range");
                        continue;
                    }
                    if (offer.status == Status.READY)
                    {
                        if (distance(tick, offer.lease) >= LEASE_TICKS)
                        {
                            removeOffer(viewer, session, offer, "lease_expired");
                        }

                        continue;
                    }
                    int idle = offer.status == Status.OFFERED ? 100
                            : (offer.status == Status.CACHED || offer.status == Status.DELIVERED
                                    ? (this.resources == null ? 200 : this.resources.settings().network().readyTicks())
                                    : (this.resources == null ? 300
                                            : this.resources.settings().network().transferIdleTicks()));
                    int total = this.resources == null ? TRANSFER_TIMEOUT
                            : this.resources.settings().network().transferTotalTicks();
                    int queued = this.resources == null ? 300
                            : this.resources.settings().network().transferQueueTicks();
                    if (distance(tick, offer.created) >= total
                            || distance(tick, offer.lastProgress) >= (offer.status == Status.QUEUED ? queued : idle))
                    {
                        resourceError(viewer,
                                new ResourceError(offer.status == Status.CACHED || offer.status == Status.DELIVERED
                                        ? "ready_timeout"
                                        : "download_timeout", "download", true, 200));
                        removeOffer(viewer, session, offer, "offer_expired");
                    }
                }
            }
        }
        pumpTransfers(tick);
    }

    private Set<UUID> discoverAudience(Publication publication)
    {
        Player owner = Bukkit.getPlayer(publication.owner);
        if (owner == null || !owner.isOnline() || !canUpload(owner) || isServerDisguised(publication.owner)
                || this.policy.maxViewers() == 0)
        {
            if (this.resources != null)
            {
                this.resources.releaseRelationships("private", publication.owner);
            }
            return Set.of();
        }
        Location origin = owner.getLocation();
        double range = this.policy.viewDistance() * this.policy.viewDistance();
        Set<UUID> discovered = owner.getTrackedBy().stream().filter(Player::isOnline)
                .filter(candidate -> !candidate.getUniqueId().equals(owner.getUniqueId()))
                .filter(candidate -> receivesModels(candidate.getUniqueId()) && canView(candidate)
                        && candidate.getWorld().equals(owner.getWorld()) && candidate.canSee(owner))
                .map(candidate -> new AudienceCandidate(candidate.getUniqueId(),
                        candidate.getLocation().distanceSquared(origin)))
                .filter(candidate -> candidate.distance < range)
                .sorted(Comparator.comparingDouble(AudienceCandidate::distance)
                        .thenComparing(AudienceCandidate::viewer))
                .limit(this.policy.maxViewers()).map(AudienceCandidate::viewer)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (this.resources == null)
        {
            return discovered;
        }

        return this.resources.reserveRelationships("private", publication.owner, discovered);
    }

    private void offer(Player viewer, Publication publication, long tick)
    {
        if (viewer == null || !acceptsNewWork())
        {
            return;
        }
        Session session = this.sessions.get(viewer.getUniqueId());
        if (session == null || !session.viewEnabled)
        {
            return;
        }

        if (this.resources != null && session.offers.size() >= this.resources.settings().models().maxModelsPerViewer())
        {
            return;
        }

        if (session.offers.size() >= MAX_OFFERS
                || session.offers.values().stream().filter(offer -> offer.status != Status.READY).count() >= 2)
        {
            return;
        }

        UUID rejected = session.rejected.get(publication.owner);
        if (rejected != null)
        {
            if (rejected.equals(publication.generation))
            {
                return;
            }

            session.rejected.remove(publication.owner);
        }
        // Live offers and rejection records share the existing 64-entry memory budget.
        if (session.offers.size() + session.rejected.size() >= MAX_OFFERS
                || session.offers.containsKey(publication.owner) || !authorized(viewer, publication)
                || !this.limits.canPushOffer(session.owner, publication.hash, Set.of(publication.generation), tick))
        {
            return;
        }

        Offer offer = new Offer(session.owner, publication, tick);
        JsonObject packet = binding("private_offer", publication);
        packet.addProperty("offerId", offer.id.toString());
        packet.addProperty("kind", publication.kind);
        packet.addProperty("bytes", publication.bytes.length);
        if (session.viewState && !publication.sourceId.isEmpty())
        {
            packet.addProperty("modelId", publication.sourceId);
        }

        addAppearance(packet, publication);
        if (send(viewer, packet, false))
        {
            session.observationFailures.remove(publication.owner);
            session.offers.put(publication.owner, offer);
            publication.offeredViewers.add(session.owner);
            this.limits.pushOfferSent(session.owner, publication.hash, Set.of(publication.generation), tick);
        }
    }

    private boolean authorized(Player viewer, Publication publication, Set<UUID> disguised)
    {
        return liveAuthorized(viewer, publication, disguised.contains(publication.owner));
    }

    private boolean authorized(Player viewer, Publication publication)
    {
        return liveAuthorized(viewer, publication, isServerDisguised(publication.owner));
    }

    private boolean liveAuthorized(Player viewer, Publication publication, boolean disguised)
    {
        if (viewer == null || !viewer.isOnline() || !canView(viewer) || !receivesModels(viewer.getUniqueId())
                || viewer.getUniqueId().equals(publication.owner)
                || this.publications.get(publication.owner) != publication || disguised
                || !this.audiences.contains(publication.owner, publication.generation, viewer.getUniqueId()))
        {
            return false;
        }

        Player owner = Bukkit.getPlayer(publication.owner);
        if (owner == null || !owner.isOnline() || !canUpload(owner) || !owner.getWorld().equals(viewer.getWorld())
                || !viewer.canSee(owner) || !owner.getTrackedBy().contains(viewer)
                || owner.getLocation().distanceSquared(viewer.getLocation()) >= this.policy.viewDistance()
                        * this.policy.viewDistance())
        {
            return false;
        }

        return true;
    }

    private void pumpTransfers(long tick)
    {
        List<Offer> sending = List.copyOf(this.activeTransfers);
        for (Offer offer : sending)
        {
            pumpOffer(offer, tick);
        }

        // Rotate the first active transfer so global byte exhaustion does not always favor one viewer.
        if (!sending.isEmpty() && this.activeTransfers.remove(sending.get(0)))
        {
            this.activeTransfers.add(sending.get(0));
        }

        List<Offer> waiting = new ArrayList<>();
        Iterator<PrivateModelSyncService.Offer> iterator = this.pendingTransfers.iterator();
        for (int count = 0; count < ConnectionLimits.GLOBAL_TRANSFERS && iterator.hasNext(); count++)
        {
            waiting.add(iterator.next());
            iterator.remove();
        }
        for (Offer offer : waiting)
        {
            pumpOffer(offer, tick);
            if (offer.status == Status.QUEUED && currentOffer(offer))
            {
                this.pendingTransfers.add(offer);
            }
        }
    }

    private boolean currentOffer(Offer offer)
    {
        Session session = this.sessions.get(offer.viewer);
        return session != null && session.offers.get(offer.publication.owner) == offer;
    }

    private void pumpOffer(Offer offer, long tick)
    {
        Session session = this.sessions.get(offer.viewer);
        Player viewer = Bukkit.getPlayer(offer.viewer);
        if (session == null || session.offers.get(offer.publication.owner) != offer)
        {
            this.activeTransfers.remove(offer);
            return;
        }
        if (!authorized(viewer, offer.publication))
        {
            removeOffer(viewer, session, offer, "out_of_range");
            return;
        }
        pump(viewer, session, offer, tick);
    }

    private void pump(Player viewer, Session session, Offer offer, long tick)
    {
        if (offer.status == Status.QUEUED)
        {
            if (!startsTransfer())
            {
                return;
            }

            if (!authorized(viewer, offer.publication))
            {
                removeOffer(viewer, session, offer, "out_of_range");
                return;
            }
            if (!this.limits.reserveTransfer(session.owner))
            {
                return;
            }
            offer.reserved = true;
            JsonObject begin = envelope("asset_begin");
            begin.addProperty("offerId", offer.id.toString());
            begin.addProperty("hash", offer.publication.hash);
            begin.addProperty("bytes", offer.publication.bytes.length);
            begin.addProperty("chunks", (offer.publication.bytes.length + chunkBytes() - 1) / chunkBytes());
            if (!send(viewer, begin, true))
            {
                this.limits.releaseTransfer(session.owner);
                offer.reserved = false;
                return;
            }
            if (offer.waitingReserved)
            {
                this.limits.releaseWaiting(session.owner);
                offer.waitingReserved = false;
            }
            offer.status = Status.SENDING;
            offer.lastProgress = tick;
            this.activeTransfers.add(offer);
        }
        if (offer.status != Status.SENDING)
        {
            return;
        }

        for (int count = 0; count < 2 && offer.offset < offer.publication.bytes.length; count++)
        {
            if (this.resources != null && !this.resources.tryWork())
            {
                return;
            }

            // No nearest-viewer sorting here: recheck current permission/tracking/generation for every chunk.
            if (!authorized(viewer, offer.publication))
            {
                removeOffer(viewer, session, offer, "out_of_range");
                return;
            }
            if (offer.pendingPacket == null)
            {
                if (!this.limits.canQueueOutgoing(session.owner, this.policy.maxPayload()))
                {
                    return;
                }

                offer.pendingEnd = Math.min(offer.offset + chunkBytes(), offer.publication.bytes.length);
                JsonObject chunk = envelope("asset_chunk");
                chunk.addProperty("offerId", offer.id.toString());
                chunk.addProperty("hash", offer.publication.hash);
                chunk.addProperty("index", offer.index);
                chunk.addProperty("data", Base64.getEncoder()
                        .encodeToString(Arrays.copyOfRange(offer.publication.bytes, offer.offset, offer.pendingEnd)));
                byte[] encoded = GSON.toJson(chunk).getBytes(StandardCharsets.UTF_8);
                if (!this.limits.queueOutgoing(session.owner, encoded.length))
                {
                    return;
                }

                offer.pendingPacket = encoded;
            }
            if (!sendBytes(viewer, offer.pendingPacket, true))
            {
                return;
            }

            this.limits.releaseOutgoing(session.owner, offer.pendingPacket.length);
            offer.pendingPacket = null;
            offer.offset = offer.pendingEnd;
            offer.index++;
            offer.lastProgress = tick;
        }
        if (offer.offset == offer.publication.bytes.length)
        {
            if (!authorized(viewer, offer.publication))
            {
                removeOffer(viewer, session, offer, "out_of_range");
                return;
            }
            JsonObject end = envelope("asset_end");
            end.addProperty("offerId", offer.id.toString());
            end.addProperty("hash", offer.publication.hash);
            if (send(viewer, end, true))
            {
                offer.status = Status.DELIVERED;
                offer.lastProgress = tick;
                this.limits.releaseTransfer(session.owner);
                offer.reserved = false;
                this.activeTransfers.remove(offer);
            }
        }
    }

    private void removePublication(UUID owner, String reason)
    {
        Publication publication = this.publications.remove(owner);
        if (publication == null)
        {
            return;
        }
        this.storedBytes -= publication.bytes.length;
        this.audiences.remove(owner);
        if (this.resources != null)
        {
            this.resources.releaseRelationships("private", owner);
        }

        Player publisher = Bukkit.getPlayer(owner);
        Session publishing = this.sessions.get(owner);
        if (publishing != null)
        {
            if (publishing.pendingCommit == publication)
            {
                publishing.pendingCommit = null;
                clearPendingIndex(publishing);
            }
            if (publishing.committedPublication == publication)
            {
                publishing.committedPublication = null;
                publishing.committedUploadId = null;
            }
        }
        if (publisher != null && publisher.isOnline())
        {
            JsonObject removed = binding("private_remove", publication);
            removed.addProperty("reason", reason);
            send(publisher, removed, false);
        }
        for (UUID viewer : List.copyOf(publication.offeredViewers))
        {
            Session session = this.sessions.get(viewer);
            if (session == null)
            {
                continue;
            }

            Offer offer = session.offers.get(owner);
            if (offer != null && offer.publication == publication)
            {
                removeOffer(Bukkit.getPlayer(session.owner), session, offer, reason);
            }

        }
    }

    private void removeOffer(Player viewer, Session session, Offer offer, String reason)
    {
        if (!session.offers.remove(offer.publication.owner, offer))
        {
            return;
        }

        if (reason.equals("viewer_paused") && offer.status != Status.READY)

        {
            this.limits.cancelPausedPushOffer(session.owner, offer.publication.hash,
                    Set.of(offer.publication.generation), offer.created);
        }

        session.observationFailures.remove(offer.publication.owner);
        session.observationFailures.put(offer.publication.owner,
                new ObservationFailure(offer.publication.owner, offer.publication.generation,
                        offer.publication.sourceId, offer.publication.kind, offer.publication.hash, offer.status.name(),
                        reason, tick()));
        while (session.observationFailures.size() > DIAGNOSTIC_ENTRIES)
        {
            session.observationFailures.remove(session.observationFailures.keySet().iterator().next());
        }

        session.pendingStates.remove(offer.publication.owner);
        clearPendingIndex(session);
        this.pendingTransfers.remove(offer);
        this.activeTransfers.remove(offer);
        offer.publication.offeredViewers.remove(session.owner);
        this.audiences.remove(offer.publication.owner, offer.publication.generation, session.owner);
        if (this.resources != null)
        {
            this.resources.releaseRelationship("private", offer.publication.owner, session.owner);
        }

        if (offer.reserved)
        {
            this.limits.releaseTransfer(session.owner);
            offer.reserved = false;
        }
        if (offer.waitingReserved)
        {
            this.limits.releaseWaiting(session.owner);
            offer.waitingReserved = false;
        }
        if (offer.pendingPacket != null)
        {
            this.limits.releaseOutgoing(session.owner, offer.pendingPacket.length);
            offer.pendingPacket = null;
        }
        if (viewer != null && viewer.isOnline())
        {
            JsonObject remove = binding("private_remove", offer.publication);
            remove.addProperty("reason", reason);
            send(viewer, remove, false);
        }
    }

    private void endSession(UUID owner, Session session, String reason)
    {
        cancelUpload(session);
        removePublication(owner, reason);
        for (Offer offer : List.copyOf(session.offers.values()))
        {
            removeOffer(Bukkit.getPlayer(owner), session, offer, reason);
        }

        this.pendingControls.remove(owner);
        this.pendingCatalogs.remove(owner);
        this.pendingDeleteReplies.remove(owner);
        this.audiences.removeViewer(owner);
        if (this.resources != null)
        {
            for (Publication publication : this.publications.values())
            {
                this.resources.releaseRelationship("private", publication.owner, owner);
            }

        }

    }

    private void clearSessions(String reason)
    {
        for (Map.Entry<UUID, PrivateModelSyncService.Session> entry : List.copyOf(this.sessions.entrySet()))
        {
            endSession(entry.getKey(), entry.getValue(), reason);
        }

        this.sessions.clear();
        this.publications.clear();
        this.storedBytes = 0;
        this.audiences.clear();
        this.pendingTransfers.clear();
        this.activeTransfers.clear();
        this.pendingControls.clear();
        this.pendingCatalogs.clear();
        this.pendingDeleteReplies.clear();
        this.maintenanceInitialized = false;
    }

    /** One shared worker refresh, not one disk scan for each player or game tick. */
    private void refreshCatalog(long tick)
    {
        if (this.store == null || !this.store.enabled() || !this.policy.enabled() || !acceptsNewWork()
                || this.catalogRefreshing || this.catalogRefreshInitialized
                        && distance(tick, this.lastCatalogRefreshTick) < PrivateUploadCatalog.REFRESH_TICKS)
        {
            return;
        }

        this.catalogRefreshInitialized = true;
        this.lastCatalogRefreshTick = tick;
        this.catalogRefreshing = true;
        PrivateModelStore current = this.store;
        submit(null, 6L * PrivateModelBundle.MAX_BYTES, "catalog", () ->
        {
            try
            {
                current.refreshCatalog();
            }
            catch (IOException | RuntimeException failure)
            {
                cacheFailure(failure);
            }
            return true;
        }, ignored -> this.catalogRefreshing = false, failure -> this.catalogRefreshing = false);
    }

    private void updateCatalog(Player owner, Session session)
    {
        if (!session.uploadCatalog)
        {
            return;
        }

        boolean available = owner != null && owner.isOnline() && canUpload(owner) && this.store != null
                && this.store.enabled();
        List<PrivateModelStore.UploadedModel> models = available ? this.store.listUploaded(session.owner) : List.of();
        if (session.catalogInitialized && session.catalogAvailable == available && session.catalogModels.equals(models))
        {
            return;
        }

        session.catalogInitialized = true;
        session.catalogAvailable = available;
        session.catalogModels = models;
        session.catalogPackets = PrivateUploadCatalog.packets(models, available, this.policy.maxPayload(),
                ++session.catalogRevision);
        session.catalogIndex = 0;
        this.pendingCatalogs.add(session.owner);
    }

    /** Metadata uses the existing connection/global budget and sends at most one fragment per tick. */
    private void pumpCatalogs(long tick)
    {
        List<UUID> rotation = List.copyOf(this.pendingCatalogs);
        int processed = 0;
        for (UUID id : rotation)
        {
            if (this.resources != null && !this.resources.tryWork())
            {
                break;
            }

            processed++;
            Session session = this.sessions.get(id);
            Player owner = Bukkit.getPlayer(id);
            if (session == null || owner == null || !owner.isOnline())
            {
                this.pendingCatalogs.remove(id);
                continue;
            }
            updateCatalog(owner, session); // Revoke permission even while a multipart snapshot is pending.
            if (session.lastCatalogSendTick == tick)
            {
                continue;
            }

            if (session.catalogIndex >= session.catalogPackets.size())
            {
                this.pendingCatalogs.remove(id);
                continue;
            }
            if (send(owner, session.catalogPackets.get(session.catalogIndex), false))
            {
                session.catalogIndex++;
                session.lastCatalogSendTick = tick;
                if (session.catalogIndex == session.catalogPackets.size())
                {
                    this.pendingCatalogs.remove(id);
                }

            }
        }
        for (int index = 0; index < processed; index++)
        {
            UUID id = rotation.get(index);
            if (this.pendingCatalogs.remove(id))
            {
                this.pendingCatalogs.add(id);
            }
        }
    }

    private void sendState(Player player, Publication publication)
    {
        if (player == null)
        {
            return;
        }
        Session session = this.sessions.get(player.getUniqueId());
        if (session == null)
        {
            return;
        }

        if (this.resources != null && !this.resources.tryWork())
        {
            deferState(session, publication);
            return;
        }
        JsonObject state = binding("private_state", publication);
        addAppearance(state, publication);
        if (send(player, state, false))
        {
            session.pendingStates.remove(publication.owner);
            clearPendingIndex(session);
        }
        else
        {
            deferState(session, publication);
        }

    }

    private void deferState(Session session, Publication publication)
    {
        if (session.pendingStates.size() < MAX_OFFERS || session.pendingStates.containsKey(publication.owner))
        {
            session.pendingStates.remove(publication.owner);
            session.pendingStates.put(publication.owner, publication);
            this.pendingControls.add(session.owner);
        }
    }

    private void clearPendingIndex(Session session)
    {
        if (session.pendingCommit == null && session.pendingStates.isEmpty())
        {
            this.pendingControls.remove(session.owner);
        }
    }

    private void retryStates()
    {
        List<UUID> waiting = List.copyOf(this.pendingControls);
        for (UUID viewer : waiting)
        {
            Session session = this.sessions.get(viewer);
            if (session == null)
            {
                this.pendingControls.remove(viewer);
                continue;
            }
            Player player = Bukkit.getPlayer(session.owner);
            Publication committed = session.pendingCommit;
            if (committed != null)
            {
                if (this.publications.get(session.owner) != committed || player == null || !player.isOnline()
                        || !canUpload(player))
                {
                    session.pendingCommit = null;
                }

                else if (isServerDisguised(session.owner))
                {
                    session.pendingCommit = null;
                    removePublication(session.owner, "server_model_priority");
                }
                else
                {
                    sendCommitted(player, session, committed);
                }

            }
            int attempts = 0;
            for (Publication publication : List.copyOf(session.pendingStates.values()))
            {
                if (++attempts > 4)
                {
                    break;
                }
                Offer offer = session.offers.get(publication.owner);
                if (offer == null || offer.publication != publication || offer.status != Status.READY
                        || !authorized(player, publication))
                {
                    session.pendingStates.remove(publication.owner);
                }

                else
                {
                    sendState(player, publication);
                }

            }
            clearPendingIndex(session);
        }
        if (!waiting.isEmpty() && this.pendingControls.remove(waiting.getFirst()))
        {
            this.pendingControls.add(waiting.getFirst());
        }

    }

    private boolean fitsAppearance(JsonObject appearance, JsonObject extra)
    {
        return GSON.toJson(appearance).getBytes(StandardCharsets.UTF_8).length
                + GSON.toJson(extra).getBytes(StandardCharsets.UTF_8).length <= this.policy.maxPayload() - 768;
    }

    private static void addAppearance(JsonObject packet, Publication publication)
    {
        packet.addProperty("sequence", publication.sequence);
        packet.add("appearance", publication.appearance.deepCopy());
        packet.add("extra", publication.extra.deepCopy());
        JsonObject state = new JsonObject();
        state.addProperty("flying", publication.flying);
        packet.add("state", state);
    }

    private boolean send(Player viewer, JsonObject packet, boolean asset)
    {
        if (viewer == null || !viewer.isOnline())
        {
            return false;
        }
        byte[] bytes = GSON.toJson(packet).getBytes(StandardCharsets.UTF_8);
        return sendBytes(viewer, bytes, asset);
    }

    private boolean sendBytes(Player viewer, byte[] bytes, boolean asset)
    {
        if (viewer == null || !viewer.isOnline())
        {
            return false;
        }

        if (bytes.length > this.policy.maxPayload() || !this.limits.allowOutbound(viewer.getUniqueId(), bytes.length,
                asset, this.nanoClock.getAsLong(), tick()))
        {
            return false;
        }

        try
        {
            viewer.sendPluginMessage(this.plugin, CHANNEL, bytes);
            return true;
        }
        catch (RuntimeException disconnected)
        {
            return false;
        }
    }

    private void resourceError(Player player, ResourceError error)
    {
        Session session = player == null ? null : this.sessions.get(player.getUniqueId());
        resourceError(player, error, session == null ? null : session.upload);
    }

    private void resourceError(Player player, ResourceError error, Upload upload)
    {
        if (this.resources != null)
        {
            this.resources.reject(error.code());
        }

        JsonObject packet = envelope("error");
        packet.addProperty("code", error.code());
        packet.addProperty("stage", error.stage());
        addErrorMetadata(packet, error);
        if (upload != null)
        {
            packet.addProperty("uploadId", upload.id.toString());
            packet.addProperty("generation", upload.generation.toString());
            packet.addProperty("hash", upload.hash);
        }
        send(player, packet, false);
    }

    private static void addErrorMetadata(JsonObject packet, ResourceError error)
    {
        error.json().entrySet().forEach(entry -> packet.add(entry.getKey(), entry.getValue()));
    }

    private void error(Player player, String code)
    {
        boolean retry = code.contains("busy") || code.contains("cooldown") || code.contains("expired")
                || code.contains("unavailable");
        resourceError(player, new ResourceError(code, "private", retry, retry ? 200 : 0));
    }

    private static JsonObject envelope(String type)
    {
        JsonObject packet = new JsonObject();
        packet.addProperty("protocol", PROTOCOL);
        packet.addProperty("type", type);
        return packet;
    }

    private static JsonObject binding(String type, Publication publication)
    {
        JsonObject packet = envelope(type);
        packet.addProperty("owner", publication.owner.toString());
        packet.addProperty("generation", publication.generation.toString());
        packet.addProperty("hash", publication.hash);
        return packet;
    }

    private static boolean matches(Publication publication, UUID generation, String hash)
    {
        return publication != null && publication.generation.equals(generation) && publication.hash.equals(hash);
    }

    private static Offer findOffer(Session session, UUID id, String hash)
    {
        return session.offers.values().stream()
                .filter(offer -> offer.id.equals(id) && offer.publication.hash.equals(hash)).findFirst().orElse(null);
    }

    private static long tick()
    {
        return Integer.toUnsignedLong(Bukkit.getCurrentTick());
    }

    private static long distance(long tick, long previous)
    {
        return (tick - previous) & 0xffffffffL;
    }

    static JsonObject decode(byte[] bytes) throws IOException
    {
        JsonObject packet = PrivateModelBundle.object(PrivateModelBundle.parseJson(bytes, 30000));
        if (PrivateModelBundle.integer(packet, "protocol", PROTOCOL, PROTOCOL) != PROTOCOL)
        {
            throw new IOException("protocol");
        }

        String type = PrivateModelBundle.string(packet, "type", 32);
        Set<String> keys = new HashSet<>(Set.of("protocol", "type"));
        switch (type)
        {
        case "hello" ->
        {
            keys.add("capabilities");
            JsonElement element = packet.get("capabilities");
            if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < 1
                    || element.getAsJsonArray().size() > 4)
            {
                throw new IOException("capabilities");
            }

            Set<String> seen = new HashSet<>();
            for (JsonElement capability : element.getAsJsonArray())
            {
                if (!capability.isJsonPrimitive() || !capability.getAsJsonPrimitive().isString())
                {
                    throw new IOException("capabilities");
                }

                String name = capability.getAsString();
                if (!Set.of(CAPABILITY, PrivateUploadCatalog.CAPABILITY, UPLOAD_CREDIT, VIEW_STATE).contains(name)
                        || !seen.add(name))
                {
                    throw new IOException("capabilities");
                }

            }
            if (!seen.contains(CAPABILITY))
            {
                throw new IOException("capabilities");
            }

            if (packet.has("viewEnabled"))
            {
                if (!seen.contains(VIEW_STATE))
                {
                    throw new IOException("view_state_not_negotiated");
                }
                keys.add("viewEnabled");
                booleanValue(packet.get("viewEnabled"));
            }
        }
        case "upload_offer" ->
        {
            keys.addAll(Set.of("generation", "hash", "bytes", "kind", "appearance"));
            uuid(packet, "generation");
            hash(packet);
            PrivateModelBundle.integer(packet, "bytes", 1, PrivateModelBundle.MAX_BYTES);
            if (!Set.of("bbmodel", "ysm").contains(PrivateModelBundle.string(packet, "kind", 16)))
            {
                throw new IOException("kind");
            }
            appearance(packet.get("appearance"));
            if (packet.has("modelId"))
            {
                keys.add("modelId");
                if (!PrivateModelStore.validSourceId(PrivateModelBundle.string(packet, "modelId", 128)))
                {
                    throw new IOException("model_source");
                }
            }
        }
        case "upload_chunk" ->
        {
            keys.addAll(Set.of("uploadId", "index", "data"));
            uuid(packet, "uploadId");
            PrivateModelBundle.integer(packet, "index", 0, 16383);
            PrivateModelBundle.string(packet, "data", 16000);
        }
        case "upload_end" ->
        {
            keys.add("uploadId");
            uuid(packet, "uploadId");
        }
        case "upload_delete" ->
        {
            keys.addAll(Set.of("requestId", "catalogToken", "requestSequence", "modelId", "hash", "kind", "bytes"));
            uuid(packet, "requestId");
            uuid(packet, "catalogToken");
            PrivateModelBundle.integer(packet, "requestSequence", 1, MAX_SEQUENCE);
            hash(packet);
            if (!PrivateModelStore.validSourceId(PrivateModelBundle.string(packet, "modelId", 128))
                    || !Set.of("ysm", "bbmodel").contains(PrivateModelBundle.string(packet, "kind", 16)))
            {
                throw new IOException("delete_identity");
            }

            PrivateModelBundle.integer(packet, "bytes", 22, PrivateModelBundle.MAX_BYTES);
        }
        case "private_status" ->
        {
            keys.addAll(Set.of("offerId", "hash", "status"));
            uuid(packet, "offerId");
            hash(packet);
            if (!Set.of("cached", "missing", "rejected").contains(PrivateModelBundle.string(packet, "status", 16)))
            {
                throw new IOException("status");
            }
        }
        case "private_ready" ->
        {
            keys.addAll(Set.of("owner", "generation", "hash"));
            uuid(packet, "owner");
            uuid(packet, "generation");
            hash(packet);
        }
        case "private_heartbeat" ->
        {
            if (packet.has("viewEnabled"))
            {
                keys.add("viewEnabled");
                booleanValue(packet.get("viewEnabled"));
            }
            keys.add("bindings");
            JsonElement value = packet.get("bindings");
            if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 64)
            {
                throw new IOException("bindings");
            }

            Set<UUID> owners = new HashSet<>();
            for (JsonElement item : value.getAsJsonArray())
            {
                JsonObject binding = PrivateModelBundle.object(item);
                PrivateModelBundle.requireKeys(binding, Set.of("owner", "generation", "hash"));
                if (!owners.add(uuid(binding, "owner")))
                {
                    throw new IOException("duplicate_binding");
                }
                uuid(binding, "generation");
                hash(binding);
            }
        }
        case "private_state" ->
        {
            keys.addAll(Set.of("generation", "hash", "appearance", "extra"));
            uuid(packet, "generation");
            hash(packet);
            appearance(packet.get("appearance"));
            extra(packet.get("extra"));
        }
        case "private_event" ->
        {
            keys.addAll(Set.of("generation", "hash", "args"));
            uuid(packet, "generation");
            hash(packet);
            JsonElement value = packet.get("args");
            if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 16)
            {
                throw new IOException("event_args");
            }

            for (JsonElement arg : value.getAsJsonArray())
            {
                number(arg, -Double.MAX_VALUE, Double.MAX_VALUE);
            }

        }
        case "clear" ->
            {
            }
        default -> throw new IOException("type");
        }
        PrivateModelBundle.requireKeys(packet, keys);
        return packet;
    }

    private static boolean booleanValue(JsonElement value) throws IOException
    {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
        {
            throw new IOException("boolean");
        }

        return value.getAsBoolean();
    }

    static JsonObject appearance(JsonElement element) throws IOException
    {
        JsonObject source = PrivateModelBundle.object(element), result = new JsonObject();
        Set<String> allowed = Set.of("scale", "offsetX", "offsetY", "offsetZ", "textureId", "variables",
                "radioSelections");
        if (!allowed.containsAll(source.keySet()))
        {
            throw new IOException("appearance_fields");
        }

        result.addProperty("scale", source.has("scale") ? number(source.get("scale"), .05, 8) : 1);
        for (String key : List.of("offsetX", "offsetY", "offsetZ"))
        {
            result.addProperty(key, source.has(key) ? number(source.get(key), -32, 32) : 0);
        }

        result.addProperty("textureId",
                source.has("textureId") ? PrivateModelBundle.string(source, "textureId", 128) : "");
        for (String field : List.of("variables", "radioSelections"))
        {
            JsonObject values = source.has(field) ? PrivateModelBundle.object(source.get(field)) : new JsonObject(),
                    safe = new JsonObject();
            if (values.size() > 128)
            {
                throw new IOException("appearance_variable_limit");
            }

            int roamingCount = 0;
            for (Map.Entry<String, JsonElement> entry : values.entrySet())
            {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                if (name.isBlank() || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl)
                        || safe.has(name) || field.equals("variables")
                                && (!name.equals(entry.getKey()) || !MODEL_VARIABLE.matcher(name).matches()))
                {
                    throw new IOException("appearance_variable_name");
                }

                if (field.equals("variables") && name.startsWith(ROAMING_PREFIX)
                        && (name.length() - ROAMING_PREFIX.length() > 32
                                || !ROAMING_NAME.matcher(name.substring(ROAMING_PREFIX.length())).matches()
                                || ++roamingCount > 64))
                {
                    throw new IOException("appearance_roaming_limit");
                }

                double value = number(entry.getValue(), field.equals("variables") ? -1_000_000 : 0,
                        field.equals("variables") ? 1_000_000 : 255);
                if (field.equals("radioSelections") && value != (int) value)
                {
                    throw new IOException("appearance_radio_integer");
                }

                safe.addProperty(name, value);
            }
            result.add(field, safe);
        }
        return result;
    }

    static JsonObject extra(JsonElement element) throws IOException
    {
        JsonObject source = PrivateModelBundle.object(element);
        PrivateModelBundle.requireKeys(source, Set.of("id", "loop", "locked", "sequence"));
        String id = PrivateModelBundle.string(source, "id", 128);
        if (!id.isEmpty() && !NAME.matcher(id).matches())
        {
            throw new IOException("animation_id");
        }

        if (!Set.of("ONCE", "LOOP", "HOLD").contains(PrivateModelBundle.string(source, "loop", 8)))
        {
            throw new IOException("animation_loop");
        }

        if (!source.get("locked").isJsonPrimitive() || !source.get("locked").getAsJsonPrimitive().isBoolean())
        {
            throw new IOException("animation_locked");
        }

        PrivateModelBundle.integer(source, "sequence", 0, MAX_SEQUENCE);
        return source.deepCopy();
    }

    private static double number(JsonElement value, double min, double max) throws IOException
    {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            throw new IOException("expected_number");
        }

        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number < min || number > max)
        {
            throw new IOException("number_range");
        }
        return number;
    }

    private static UUID uuid(JsonObject packet, String key) throws IOException
    {
        String value = PrivateModelBundle.string(packet, key, 36);
        try
        {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value))
            {
                throw new IOException("uuid");
            }
            return uuid;
        }
        catch (IllegalArgumentException invalid)
        {
            throw new IOException("uuid", invalid);
        }
    }

    private static String hash(JsonObject packet) throws IOException
    {
        String hash = PrivateModelBundle.string(packet, "hash", 64);
        if (!HASH.matcher(hash).matches())
        {
            throw new IOException("hash");
        }
        return hash;
    }

    private enum Status
    {
        OFFERED, CACHED, QUEUED, SENDING, DELIVERED, READY
    }

    private static final class Session
    {
        final UUID owner;
        final Map<UUID, Offer> offers = new LinkedHashMap<>();
        final Map<UUID, UUID> rejected = new LinkedHashMap<>();
        final Map<UUID, ObservationFailure> observationFailures = new LinkedHashMap<>();
        final Map<UUID, Publication> pendingStates = new LinkedHashMap<>();
        Publication pendingCommit, committedPublication;
        UUID committedUploadId;
        Upload upload;
        boolean uploadCatalog, uploadCredit, catalogAvailable, catalogInitialized, viewState, viewEnabled = true;
        long catalogRevision, lastCatalogSendTick = -1;
        final UUID uploadCatalogToken = UUID.randomUUID();
        long lastDeleteSequence;
        List<PrivateModelStore.UploadedModel> catalogModels = List.of();
        List<JsonObject> catalogPackets = List.of();
        int catalogIndex;
        final Map<UUID, Delete> deletes = new LinkedHashMap<>();
        final Map<UUID, JsonObject> deleteReplies = new LinkedHashMap<>();

        Session(UUID owner)
        {
            this.owner = owner;
        }
    }

    private static final class Upload
    {
        final UUID owner, id, generation;
        final String hash, kind;
        byte[] bytes;
        final int expectedBytes;
        final JsonObject appearance;
        final long created, reservation;
        int offset, index, creditLimit, sentCreditLimit;
        long lastProgress, lastCreditSend;
        boolean validating, released, transferReserved = true;
        volatile boolean cancelled, workerActive, workerStarted;
        String sourceId = "";

        Upload(UUID owner, UUID id, UUID generation, String hash, String kind, int bytes, JsonObject appearance,
                long tick, long reservation, boolean allocate)
        {
            this.owner = owner;
            this.id = id;
            this.generation = generation;
            this.hash = hash;
            this.kind = kind;
            this.expectedBytes = bytes;
            this.bytes = allocate ? new byte[bytes] : null;
            this.appearance = appearance;
            this.created = this.lastProgress = tick;
            this.reservation = reservation;
        }
    }

    private static final class Publication
    {
        final UUID owner, generation;
        final String hash, kind;
        final byte[] bytes;
        JsonObject appearance, extra;
        final Set<UUID> offeredViewers = new HashSet<>();
        long sequence, eventSequence, lease;
        boolean flying;
        String sourceId = "";

        Publication(UUID owner, UUID generation, String hash, String kind, byte[] bytes, JsonObject appearance,
                long tick)
        {
            this.owner = owner;
            this.generation = generation;
            this.hash = hash;
            this.kind = kind;
            this.bytes = bytes;
            this.appearance = appearance;
            this.lease = tick;
            this.extra = new JsonObject();
            this.extra.addProperty("id", "");
            this.extra.addProperty("loop", "ONCE");
            this.extra.addProperty("locked", false);
            this.extra.addProperty("sequence", 0);
        }
    }

    private static final class Offer
    {
        final UUID id = UUID.randomUUID(), viewer;
        final Publication publication;
        final long created;
        Status status = Status.OFFERED;
        long lastProgress, lease;
        int offset, index, pendingEnd;
        boolean reserved, waitingReserved;
        byte[] pendingPacket;

        Offer(UUID viewer, Publication publication, long tick)
        {
            this.viewer = viewer;
            this.publication = publication;
            this.created = this.lastProgress = tick;
        }
    }

    private record AudienceCandidate(UUID viewer, double distance)
    {
    }

    private record ObservationFailure(UUID owner, UUID generation, String sourceId, String kind, String hash,
            String phase, String reason, long tick)
    {
    }

    private record OwnerHash(UUID owner, String hash)
    {
    }

    private record OwnerSource(UUID owner, String sourceId)
    {
    }

    private static final class Delete
    {
        final UUID request;
        final String sourceId, hash, kind;
        final int bytes;
        final long sequence;
        JsonObject reply;

        Delete(UUID request, String sourceId, String hash, String kind, int bytes, long sequence)
        {
            this.request = request;
            this.sourceId = sourceId;
            this.hash = hash;
            this.kind = kind;
            this.bytes = bytes;
            this.sequence = sequence;
        }
    }

    private record DeleteResult(boolean removed, String failure)
    {
    }

    private static final class Rates
    {
        final Rate state = new Rate(), event = new Rate();
        final HelloRate hello = new HelloRate();
    }

    private static final class HelloRate
    {
        long last;
        boolean initialized;

        boolean take(long now)
        {
            if (this.initialized && now - this.last < 1_000_000_000L)
            {
                return false;
            }
            this.initialized = true;
            this.last = now;
            return true;
        }
    }

    static final class Rate
    {
        long start;
        int count;
        boolean initialized;

        boolean take(long now)
        {
            if (!this.initialized || now - this.start >= 1_000_000_000L)
            {
                this.initialized = true;
                this.start = now;
                this.count = 0;
            }
            if (this.count >= 8)
            {
                return false;
            }
            this.count++;
            return true;
        }
    }
}
