package com.simmc.meplayeractions.gameplay;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Minecart;
import com.simmc.meplayeractions.action.StateSelector.Vehicle;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Real player movement for Paper 1.21.11, with an optional GSit 3.x public API backend.
 * All state-changing calls must run on the Paper main thread.
 */
public final class GameplayBackend implements AutoCloseable {
    private final Plugin plugin;
    private final GSitBridge gsit;
    private final Map<UUID, OwnedPosture> postures = new HashMap<>();
    private final Map<UUID, FlightSession> flights = new HashMap<>();
    private final PoseReplicaVisibility replicas = new PoseReplicaVisibility();
    private final Listener poseLifecycle = new Listener() {};
    private boolean replicaAdapter;
    private final Set<String> replicaWarnings = new HashSet<>();
    private String gsitDiagnosis = "GSit 后端尚未初始化。";
    private boolean closed;

    public GameplayBackend(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gsit = loadGSit();
        replicaAdapter = gsit != null && gsit.dependency.getDescription().getVersion().equals("3.5.1");
        if (replicaAdapter) {
            try { registerPoseLifecycle(); }
            catch (RuntimeException failure) {
                replicaAdapter = false;
                plugin.getLogger().log(Level.WARNING, "GSit 假玩家适配器已停用；公共姿态采样仍可使用", failure);
            }
        }
    }

    /** Whether the supported optional backend is currently enabled. */
    public boolean available() {
        return !closed && gsit != null && gsit.enabled();
    }

    public String diagnosis() {
        if (closed) {
            return "玩家动作服务已经关闭。";
        }
        if (gsit != null && !gsit.enabled()) {
            return "GSit 已被停用，真实坐下和爬行不可用。";
        }
        return gsitDiagnosis;
    }

    /** Includes postures started by GSit itself or another plugin. */
    public boolean isSitting(Player player) {
        Objects.requireNonNull(player, "player");
        return gsit != null && gsit.enabled() && gsit.seat(player) != null;
    }

    /** Includes postures started by GSit itself or another plugin. */
    public boolean isCrawling(Player player) {
        Objects.requireNonNull(player, "player");
        return gsit != null && gsit.enabled() && gsit.crawl(player) != null;
    }

    /** Read-only: never dismounts or changes another plugin's posture. */
    public PostureResolver.Posture observe(Player player) {
        var vehicle = player.getVehicle();
        Vehicle type = vehicle == null ? Vehicle.NONE : vehicle instanceof Boat ? Vehicle.BOAT
                : vehicle instanceof Minecart ? Vehicle.MINECART : Vehicle.OTHER;
        String pose = gsit != null && gsit.enabled() ? gsit.poseType(player) : "";
        return PostureResolver.resolve(isSitting(player), isCrawling(player), pose,
                player.isSleeping(), player.getPose(), player.isInWater(), player.isFlying(), player.isGliding(), type);
    }

    /** Model contact and direction from GSit's public seat API, never its lowered player mount. */
    public GSitAnchor visualAnchor(Player player) {
        return gsit != null && gsit.enabled() ? gsit.visualAnchor(player) : null;
    }

    /** Suppress the GSit packet replica only when this plugin owns and hides the player's disguise. */
    public void syncPoseReplica(Player player, boolean hideAvatar) {
        requireMainThread();
        if (!hideAvatar || gsit == null || !gsit.enabled() || !replicaAdapter) { replicas.restore(player.getUniqueId()); return; }
        try {
            Object pose = gsit.pose(player);
            if (pose != null && (!pose.getClass().getName().startsWith("dev.geco.gsit.mcv.")
                    || !pose.getClass().getName().endsWith(".model.Pose")))
                throw new IllegalStateException("Unknown GSit pose implementation: " + pose.getClass().getName());
            replicas.suppress(player.getUniqueId(), pose);
        } catch (RuntimeException failure) {
            // Optional private rendering hooks must never prevent public pose/animation synchronization.
            if (replicaWarnings.add(failure.getMessage()))
                plugin.getLogger().log(Level.WARNING, "GSit 3.5.1 假玩家隐藏失败；姿态同步保持启用", failure);
        }
    }

    public void sit(Player player) {
        requireMutation(player);
        requireGSit();
        if (!preparePosture(player, PostureKind.SIT)) {
            return;
        }
        Object seat = gsit.createSeat(player);
        if (seat == null) {
            throw new IllegalStateException("无法坐下：GSit 创建座位失败，或动作被其他插件取消。");
        }
        postures.put(player.getUniqueId(), new OwnedPosture(PostureKind.SIT, seat));
    }

    public void crawl(Player player) {
        requireMutation(player);
        requireGSit();
        if (!preparePosture(player, PostureKind.CRAWL)) {
            return;
        }
        Object crawl = gsit.startCrawl(player);
        if (crawl == null) {
            throw new IllegalStateException("无法爬行：GSit 创建爬行会话失败，或动作被其他插件取消。");
        }
        postures.put(player.getUniqueId(), new OwnedPosture(PostureKind.CRAWL, crawl));
    }

    /** Stops only the exact GSit object created by this backend. */
    public void stopOwnedPosture(Player player) {
        stopOwnedPosture(player, true);
    }

    /** Disable safe dismount when a teleport has already established the destination. */
    public void stopOwnedPosture(Player player, boolean useSafeDismount) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        OwnedPosture owned = postures.get(id);
        if (owned == null) {
            return;
        }
        if (gsit == null || !gsit.enabled()) {
            // A disabled GSit owns its own shutdown cleanup; do not call stale API objects.
            postures.remove(id, owned);
            return;
        }
        Object current = owned.kind == PostureKind.SIT ? gsit.seat(player) : gsit.crawl(player);
        if (current != owned.handle) {
            postures.remove(id, owned);
            return;
        }
        boolean stopped = owned.kind == PostureKind.SIT
                ? gsit.removeSeat(owned.handle, useSafeDismount)
                : gsit.stopCrawl(owned.handle);
        if (!stopped) {
            // Keep ownership so another cleanup attempt can inspect the actual live object.
            throw new IllegalStateException("无法结束动作：GSit 未成功停止本插件创建的姿态。");
        }
        postures.remove(id, owned);
    }

    /** Grants actual creative-style flight; callers must check the separate flight permission. */
    public void enableFlight(Player player, double speed) {
        requireMutation(player);
        float requestedSpeed = (float) speed;
        if (!Double.isFinite(speed) || speed <= 0.0 || speed > 1.0
                || !Float.isFinite(requestedSpeed) || requestedSpeed <= 0.0f) {
            throw new IllegalArgumentException("飞行速度必须在 (0, 1] 范围内。");
        }
        // End an earlier grant first, preserving fields that were changed externally.
        disableOwnedFlight(player);
        stopOwnedPosture(player);
        if (isSitting(player) || isCrawling(player)) {
            throw new IllegalStateException("请先退出其他插件创建的坐姿或爬行，再开启飞行。");
        }
        FlightSession session = new FlightSession(player);
        flights.put(player.getUniqueId(), session);
        try {
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
                session.ownsAllowFlight = true;
            }
            if (!player.isFlying()) {
                player.setFlying(true);
                session.ownsFlying = true;
            }
            if (Float.compare(player.getFlySpeed(), requestedSpeed) != 0) {
                player.setFlySpeed(requestedSpeed);
                session.writtenFlySpeed = requestedSpeed;
                session.ownsFlySpeed = true;
            }
        } catch (RuntimeException failure) {
            try {
                disableOwnedFlight(player);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("开启飞行失败，已尝试恢复本插件修改的状态。", failure);
        }
    }

    /** Restores only fields still matching this backend's last write. */
    public void disableOwnedFlight(Player player) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        FlightSession session = flights.get(id);
        if (session == null) {
            return;
        }
        if (player.getGameMode() != session.originalGameMode) {
            // A game-mode transition owns the new abilities; the old snapshot is obsolete.
            flights.remove(id, session);
            return;
        }
        RuntimeException failure = null;
        if (session.ownsFlySpeed) {
            if (Float.compare(player.getFlySpeed(), session.writtenFlySpeed) != 0) {
                session.ownsFlySpeed = false;
            } else {
                try {
                    player.setFlySpeed(session.originalFlySpeed);
                    session.ownsFlySpeed = false;
                } catch (RuntimeException e) {
                    failure = appendFailure(failure, e);
                }
            }
        }
        if (session.ownsFlying) {
            if (!player.isFlying()) {
                session.ownsFlying = false;
            } else if (session.originalFlying && !player.getAllowFlight()) {
                session.ownsFlying = false;
            } else {
                try {
                    player.setFlying(session.originalFlying);
                    session.ownsFlying = false;
                } catch (RuntimeException e) {
                    failure = appendFailure(failure, e);
                }
            }
        }
        if (session.ownsAllowFlight) {
            if (!player.getAllowFlight()) {
                session.ownsAllowFlight = false;
            } else if (!session.originalAllowFlight && player.isFlying()) {
                // Revoking mayfly also stops flying. Preserve a current flight we no longer own.
                if (!session.ownsFlying) {
                    session.ownsAllowFlight = false;
                }
            } else {
                try {
                    player.setAllowFlight(session.originalAllowFlight);
                    session.ownsAllowFlight = false;
                } catch (RuntimeException e) {
                    failure = appendFailure(failure, e);
                }
            }
        }
        if (!session.ownsAllowFlight && !session.ownsFlying && !session.ownsFlySpeed) {
            flights.remove(id, session);
        }
        if (failure != null) {
            throw new IllegalStateException("恢复飞行状态失败；未完成的本插件状态将保留以便再次清理。", failure);
        }
    }

    /** Idempotent; flight cleanup is attempted even if GSit cleanup fails. */
    public void cleanup(Player player) {
        cleanup(player, true);
    }

    public void cleanup(Player player, boolean useSafeDismount) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        RuntimeException failure = null;
        try { replicas.restore(player.getUniqueId()); }
        catch (RuntimeException e) { failure = e; }
        try {
            stopOwnedPosture(player, useSafeDismount);
        } catch (RuntimeException e) {
            failure = appendFailure(failure, e);
        }
        try {
            disableOwnedFlight(player);
        } catch (RuntimeException e) {
            failure = appendFailure(failure, e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        requireMainThread();
        if (closed) {
            return;
        }
        Set<UUID> ids = new HashSet<>(postures.keySet());
        ids.addAll(flights.keySet());
        for (UUID id : ids) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                // PlayerQuitEvent should already have called cleanup while the player was present.
                postures.remove(id);
                flights.remove(id);
                continue;
            }
            try {
                cleanup(player);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "无法完整清理玩家 " + player.getName() + " 的动作状态。", failure);
            }
        }
        replicas.close();
        HandlerList.unregisterAll(poseLifecycle);
        closed = true;
    }

    private boolean preparePosture(Player player, PostureKind requested) {
        UUID id = player.getUniqueId();
        Object seat = gsit.seat(player);
        Object crawl = gsit.crawl(player);
        OwnedPosture owned = postures.get(id);
        if (owned != null && (owned.kind == PostureKind.SIT ? seat : crawl) != owned.handle) {
            postures.remove(id, owned);
            owned = null;
        }
        if (seat != null && (owned == null || owned.kind != PostureKind.SIT || seat != owned.handle)
                || crawl != null && (owned == null || owned.kind != PostureKind.CRAWL || crawl != owned.handle)) {
            throw new IllegalStateException("玩家已处于其他插件创建的姿态，请先正常起身或结束爬行。");
        }
        if (owned != null && owned.kind == requested) {
            return false;
        }
        if (player.isInsideVehicle() && owned == null) {
            throw new IllegalStateException("请先离开当前载具，再使用坐下或爬行动作。");
        }
        if (player.isFlying()) {
            FlightSession flight = flights.get(id);
            if (flight == null || !flight.ownsFlying || player.getGameMode() != flight.originalGameMode) {
                throw new IllegalStateException("请先停止当前飞行，再使用坐下或爬行动作。");
            }
            disableOwnedFlight(player);
            if (player.isFlying()) {
                throw new IllegalStateException("玩家仍在飞行，无法进入坐下或爬行。");
            }
        }
        stopOwnedPosture(player);
        return true;
    }

    private void requireGSit() {
        if (!available()) {
            throw new IllegalStateException("真实坐下和爬行需要已启用公共 API 的 GSit；其他模型动作仍可使用。");
        }
    }

    private void requireMutation(Player player) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        if (closed) {
            throw new IllegalStateException("玩家动作服务已经关闭。");
        }
        if (!player.isOnline() || player.isDead()) {
            throw new IllegalStateException("玩家不在线或已经死亡，无法开始动作。");
        }
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("真实玩家动作必须在 Paper 主线程执行。");
        }
    }

    private GSitBridge loadGSit() {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("GSit");
        if (dependency == null || !dependency.isEnabled()) {
            gsitDiagnosis = "未启用兼容的 GSit，GSit 坐姿和爬行指令不可用；原生趴下、床上睡眠和载具动画仍可识别。";
            plugin.getLogger().warning(gsitDiagnosis);
            return null;
        }
        String version = dependency.getDescription().getVersion();
        if (!version.startsWith("3.")) {
            gsitDiagnosis = "检测到 GSit " + version + "；本插件需要 GSit 3.x 公共 API，GSit 姿态后端已停用。";
            plugin.getLogger().warning(gsitDiagnosis);
            return null;
        }
        try {
            GSitBridge bridge = new GSitBridge(dependency);
            gsitDiagnosis = "已接入 GSit " + version + " 公共 API：坐姿/躺下/趴下/爬行可识别。"
                    + (bridge.startCrawl == null ? " 当前 API 无单参数爬行入口，请用 GSit 自身命令开始爬行。" : " 坐下和爬行指令可用。");
            plugin.getLogger().info(gsitDiagnosis);
            return bridge;
        } catch (ReflectiveOperationException | LinkageError failure) {
            gsitDiagnosis = "GSit " + version + " 公共 API 加载失败，GSit 姿态后端已停用。";
            plugin.getLogger().log(Level.WARNING, gsitDiagnosis, failure);
            return null;
        }
    }

    private void registerPoseLifecycle() {
        try {
            Class<? extends Event> beforeStop = Class.forName("dev.geco.gsit.api.event.PrePlayerStopPoseEvent", true,
                    gsit.dependency.getClass().getClassLoader()).asSubclass(Event.class);
            Method eventPose = beforeStop.getMethod("getPose");
            plugin.getServer().getPluginManager().registerEvent(beforeStop, poseLifecycle, EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            Player owner = ((PlayerEvent) event).getPlayer();
                            int range = owner.getWorld().getSimulationDistance() * 16;
                            var fallback = owner.getWorld().getPlayers().stream()
                                    .filter(viewer -> viewer.canSee(owner) && viewer.getLocation().distanceSquared(owner.getLocation()) <= (double) range * range).toList();
                            replicas.beforeRemoval(owner.getUniqueId(), eventPose.invoke(event), fallback);
                        }
                        catch (ReflectiveOperationException | RuntimeException failure) {
                            plugin.getLogger().log(Level.WARNING, "GSit 姿态结束前恢复显示失败", failure);
                        }
                    }, plugin, true);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("GSit pose lifecycle API is unavailable", failure);
        }
    }

    private static RuntimeException appendFailure(RuntimeException previous, RuntimeException next) {
        if (previous == null) {
            return next;
        }
        if (previous != next) {
            previous.addSuppressed(next);
        }
        return previous;
    }

    private enum PostureKind { SIT, CRAWL }

    private record OwnedPosture(PostureKind kind, Object handle) { }

    private static final class FlightSession {
        private final boolean originalAllowFlight;
        private final boolean originalFlying;
        private final float originalFlySpeed;
        private final GameMode originalGameMode;
        private boolean ownsAllowFlight;
        private boolean ownsFlying;
        private boolean ownsFlySpeed;
        private float writtenFlySpeed;

        private FlightSession(Player player) {
            originalAllowFlight = player.getAllowFlight();
            originalFlying = player.isFlying();
            originalFlySpeed = player.getFlySpeed();
            originalGameMode = player.getGameMode();
        }
    }

    /** Public API signatures checked at runtime, without a hard optional dependency. */
    private static final class GSitBridge {
        private final Plugin dependency;
        private final Method createSeat;
        private final Method getSeat;
        private final Method removeSeat;
        private final Method startCrawl;
        private final Method getCrawl;
        private final Method stopCrawl;
        private final Method getPose;
        private final Method getPoseType;
        private final Method getPoseSeat;
        private final Method getSeatLocation;
        private final Object sitService;
        private final Method getBaseOffset;
        private final Object pluginReason;

        private GSitBridge(Plugin dependency) throws ReflectiveOperationException {
            this.dependency = dependency;
            ClassLoader loader = dependency.getClass().getClassLoader();
            Class<?> api = Class.forName("dev.geco.gsit.api.GSitAPI", true, loader);
            Class<?> seat = Class.forName("dev.geco.gsit.model.Seat", true, loader);
            Class<?> crawl = Class.forName("dev.geco.gsit.model.Crawl", true, loader);
            Class<?> reason = Class.forName("dev.geco.gsit.model.StopReason", true, loader);
            pluginReason = reason.getField("PLUGIN").get(null);
            createSeat = api.getMethod("createSeat", Block.class, LivingEntity.class,
                    boolean.class, double.class, double.class, double.class, float.class, boolean.class);
            getSeat = api.getMethod("getSeatByEntity", LivingEntity.class);
            removeSeat = api.getMethod("removeSeat", seat, reason, boolean.class);
            Method crawlStart;
            try { crawlStart = api.getMethod("startCrawl", Player.class); }
            catch (NoSuchMethodException ignored) { crawlStart = null; }
            startCrawl = crawlStart;
            getCrawl = api.getMethod("getCrawlByPlayer", Player.class);
            stopCrawl = api.getMethod("stopCrawl", crawl, reason);
            getPose = api.getMethod("getPoseByPlayer", Player.class);
            getPoseType = getPose.getReturnType().getMethod("getPoseType");
            getPoseSeat = getPose.getReturnType().getMethod("getSeat");
            getSeatLocation = seat.getMethod("getLocation");
            // Class.getMethod enumerates every GSitMain return type and accidentally loads
            // optional PlaceholderAPI classes. Resolve just this exact public signature.
            Class<?> sitServiceType = Class.forName("dev.geco.gsit.service.SitService", true, loader);
            try {
                sitService = MethodHandles.publicLookup().findVirtual(dependency.getClass(), "getSitService",
                        MethodType.methodType(sitServiceType)).invoke(dependency);
            } catch (Throwable failure) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new ReflectiveOperationException("GSit getSitService resolution failed", failure);
            }
            getBaseOffset = sitService.getClass().getMethod("getBaseOffset");
        }

        private boolean enabled() {
            return dependency.isEnabled();
        }

        private Object createSeat(Player player) {
            // Stay at the player's X/Z instead of snapping to the center of the supporting block.
            Block block = player.getLocation().clone().subtract(0.0, 0.1, 0.0).getBlock();
            return invoke(createSeat, "创建座位", block, player, true, 0.0, 0.0, 0.0,
                    player.getLocation().getYaw(), false);
        }

        private Object seat(Player player) {
            return invoke(getSeat, "查询坐姿", player);
        }

        private boolean removeSeat(Object seat, boolean useSafeDismount) {
            return Boolean.TRUE.equals(invoke(removeSeat, "结束坐姿", seat, pluginReason, useSafeDismount));
        }

        private Object startCrawl(Player player) {
            if (startCrawl == null)
                throw new IllegalStateException("当前 GSit 版本没有兼容的 startCrawl(Player) 入口，请使用 GSit 的爬行命令；动画照常同步。");
            return invoke(startCrawl, "开始爬行", player);
        }

        private Object crawl(Player player) {
            return invoke(getCrawl, "查询爬行", player);
        }

        private String poseType(Player player) {
            Object pose = pose(player);
            if (pose == null) return "";
            try { return ((Enum<?>) getPoseType.invoke(pose)).name(); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException("GSit 查询姿态类型失败", ex); }
        }

        private Object pose(Player player) { return invoke(getPose, "查询躺卧姿态", player); }

        private GSitAnchor visualAnchor(Player player) {
            Object pose = pose(player);
            try {
                Object seat = pose == null ? seat(player) : getPoseSeat.invoke(pose);
                return seat == null ? null : GSitAnchor.of((org.bukkit.Location) getSeatLocation.invoke(seat),
                        ((Number) getBaseOffset.invoke(sitService)).doubleValue());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("GSit 查询姿态接触面失败", failure);
            }
        }

        private boolean stopCrawl(Object crawl) {
            return Boolean.TRUE.equals(invoke(stopCrawl, "结束爬行", crawl, pluginReason));
        }

        private static Object invoke(Method method, String operation, Object... args) {
            try {
                return method.invoke(null, args);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("GSit " + operation + "失败。", e.getCause());
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                throw new IllegalStateException("无法调用 GSit 的" + operation + "公共接口。", e);
            }
        }
    }
}
