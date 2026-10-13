package com.simmc.meplayeractions.gameplay;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Minecart;
import com.simmc.meplayeractions.action.StateSelector.Vehicle;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.Function;
import org.bukkit.entity.Entity;

/**
 * Real player movement for Paper 1.21.11, with an optional GSit 3.x public API backend.
 * All state-changing calls must run on the Paper main thread.
 */
public final class GameplayBackend implements AutoCloseable {
    private final Plugin plugin;
    private final GSitAccess gsit;
    private final Map<UUID, OwnedPosture> postures = new HashMap<>();
    private final Map<UUID, FlightSession> flights = new HashMap<>();
    private final PoseReplicaVisibility replicas = new PoseReplicaVisibility();
    private final Listener poseLifecycle = new Listener() {
    };
    private boolean replicaAdapter;
    private boolean gsitFailed;
    private boolean replicaRestoreWarning;
    private String gsitDiagnosis = "GSit 后端尚未初始化。";
    private boolean closed;

    public GameplayBackend(Plugin plugin) {
        this(plugin, null);
    }

    GameplayBackend(Plugin plugin, Function<Plugin, GSitAccess> factory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gsit = loadGSit(factory);
        if (this.replicaAdapter) {
            try {
                registerPoseLifecycle();
            } catch (RuntimeException | LinkageError failure) {
                disableReplica(failure);
            }
        }
    }

    /** Whether the supported optional backend is currently enabled. */
    public boolean available() {
        if (this.closed || this.gsit == null || this.gsitFailed) {
            return false;
        }
        try {
            if (this.gsit.enabled()) {
                return true;
            }
            disableGSit(new IllegalStateException("GSit 已被停用"));
        } catch (RuntimeException | LinkageError failure) {
            disableGSit(failure);
        }
        return false;
    }

    public String diagnosis() {
        if (this.closed) {
            return "玩家动作服务已经关闭。";
        }
        available();
        return this.gsitDiagnosis;
    }

    /** Includes postures started by GSit itself or another plugin. */
    public boolean isSitting(Player player) {
        Objects.requireNonNull(player, "player");
        return gsitCall(() -> this.gsit.seat(player) != null, false);
    }

    /** Includes postures started by GSit itself or another plugin. */
    public boolean isCrawling(Player player) {
        Objects.requireNonNull(player, "player");
        return gsitCall(() -> this.gsit.crawl(player) != null, false);
    }

    /** Read-only: never dismounts or changes another plugin's posture. */
    public PostureResolver.Posture observe(Player player) {
        Entity vehicle = player.getVehicle();
        Vehicle type = vehicle == null ? Vehicle.NONE
                : (vehicle instanceof Boat ? Vehicle.BOAT
                        : (vehicle instanceof Minecart ? Vehicle.MINECART : Vehicle.OTHER));
        PostureResolver.Posture nativePosture = PostureResolver.resolve(false, false, "", player.isSleeping(),
                player.getPose(), player.isInWater(), player.isFlying(), player.isGliding(), type);
        return gsitCall(() -> PostureResolver.resolve(this.gsit.seat(player) != null,
                this.gsit.crawl(player) != null, this.gsit.poseType(player), player.isSleeping(),
                player.getPose(), player.isInWater(), player.isFlying(), player.isGliding(), type), nativePosture);
    }

    /** Model contact and direction from GSit's public seat API, never its lowered player mount. */
    public GSitAnchor visualAnchor(Player player) {
        return gsitCall(() -> this.gsit.visualAnchor(player), null);
    }

    /** Suppress the GSit packet replica only when this plugin owns and hides the player's disguise. */
    public void syncPoseReplica(Player player, boolean hideAvatar) {
        requireMainThread();
        if (!hideAvatar || !available() || !this.replicaAdapter) {
            restoreReplica(player.getUniqueId());
            return;
        }
        try {
            Object pose = gsitCall(() -> this.gsit.pose(player), null);
            if (!available()) {
                return;
            }
            if (pose != null && (!pose.getClass().getName().startsWith("dev.geco.gsit.mcv.")
                    || !pose.getClass().getName().endsWith(".model.Pose"))) {
                throw new IllegalStateException("Unknown GSit pose implementation: " + pose.getClass().getName());
            }

            this.replicas.suppress(player.getUniqueId(), pose);
        } catch (RuntimeException | LinkageError failure) {
            // Optional private rendering hooks must never prevent public pose/animation synchronization.
            disableReplica(failure);
        }
    }

    public void sit(Player player) {
        requireMutation(player);
        requireGSit();
        if (!preparePosture(player, PostureKind.SIT)) {
            return;
        }
        Object seat = gsitCall(() -> this.gsit.createSeat(player), null);
        requireGSit();
        if (seat == null) {
            throw new IllegalStateException("无法坐下：GSit 创建座位失败，或动作被其他插件取消。");
        }
        this.postures.put(player.getUniqueId(), new OwnedPosture(PostureKind.SIT, seat));
    }

    public void crawl(Player player) {
        requireMutation(player);
        requireGSit();
        if (!preparePosture(player, PostureKind.CRAWL)) {
            return;
        }
        Object crawl = gsitCall(() -> this.gsit.startCrawl(player), null);
        requireGSit();
        if (crawl == null) {
            throw new IllegalStateException("无法爬行：GSit 创建爬行会话失败，或动作被其他插件取消。");
        }
        this.postures.put(player.getUniqueId(), new OwnedPosture(PostureKind.CRAWL, crawl));
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
        OwnedPosture owned = this.postures.get(id);
        if (owned == null) {
            return;
        }
        if (!available()) {
            // A disabled GSit owns its own shutdown cleanup; do not call stale API objects.
            this.postures.remove(id, owned);
            return;
        }
        Object current = gsitCall(() -> owned.kind == PostureKind.SIT ? this.gsit.seat(player)
                : this.gsit.crawl(player), null);
        if (current != owned.handle) {
            this.postures.remove(id, owned);
            return;
        }
        Boolean stopped = gsitCall(() -> owned.kind == PostureKind.SIT
                ? this.gsit.removeSeat(owned.handle, useSafeDismount) : this.gsit.stopCrawl(owned.handle), null);
        if (Boolean.FALSE.equals(stopped)) {
            // Keep ownership so another cleanup attempt can inspect the actual live object.
            throw new IllegalStateException("无法结束动作：GSit 未成功停止本插件创建的姿态。");
        }
        this.postures.remove(id, owned);
    }

    /** Grants actual creative-style flight; callers must check the separate flight permission. */
    public void enableFlight(Player player, double speed) {
        requireMutation(player);
        float requestedSpeed = (float) speed;
        if (!Double.isFinite(speed) || speed <= 0.0 || speed > 1.0 || !Float.isFinite(requestedSpeed)
                || requestedSpeed <= 0.0f) {
            throw new IllegalArgumentException("飞行速度必须在 (0, 1] 范围内。");
        }
        // End an earlier grant first, preserving fields that were changed externally.
        disableOwnedFlight(player);
        stopOwnedPosture(player);
        if (isSitting(player) || isCrawling(player)) {
            throw new IllegalStateException("请先退出其他插件创建的坐姿或爬行，再开启飞行。");
        }
        FlightSession session = new FlightSession(player);
        this.flights.put(player.getUniqueId(), session);
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
        FlightSession session = this.flights.get(id);
        if (session == null) {
            return;
        }
        if (player.getGameMode() != session.originalGameMode) {
            // A game-mode transition owns the new abilities; the old snapshot is obsolete.
            this.flights.remove(id, session);
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
            this.flights.remove(id, session);
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
        restoreReplica(player.getUniqueId());
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
        if (this.closed) {
            return;
        }
        Set<UUID> ids = new HashSet<>(this.postures.keySet());
        ids.addAll(this.flights.keySet());
        for (UUID id : ids) {
            Player player = this.plugin.getServer().getPlayer(id);
            if (player == null) {
                // PlayerQuitEvent should already have called cleanup while the player was present.
                this.postures.remove(id);
                this.flights.remove(id);
                continue;
            }
            try {
                cleanup(player);
            } catch (RuntimeException failure) {
                this.plugin.getLogger().log(Level.WARNING, "无法完整清理玩家 " + player.getName() + " 的动作状态。", failure);
            }
        }
        try {
            this.replicas.close();
        } catch (RuntimeException | LinkageError failure) {
            this.plugin.getLogger().log(Level.WARNING, "GSit 假玩家显示清理失败；其他服务继续关闭", failure);
        }
        HandlerList.unregisterAll(this.poseLifecycle);
        this.closed = true;
    }

    private boolean preparePosture(Player player, PostureKind requested) {
        UUID id = player.getUniqueId();
        Object seat = gsitCall(() -> this.gsit.seat(player), null);
        Object crawl = gsitCall(() -> this.gsit.crawl(player), null);
        requireGSit();
        OwnedPosture owned = this.postures.get(id);
        if (owned != null && (owned.kind == PostureKind.SIT ? seat : crawl) != owned.handle) {
            this.postures.remove(id, owned);
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
            FlightSession flight = this.flights.get(id);
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
        if (this.closed) {
            throw new IllegalStateException("玩家动作服务已经关闭。");
        }
        if (!player.isOnline() || player.isDead()) {
            throw new IllegalStateException("玩家不在线或已经死亡，无法开始动作。");
        }
    }

    private void requireMainThread() {
        if (!this.plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("真实玩家动作必须在 Paper 主线程执行。");
        }
    }

    private GSitAccess loadGSit(Function<Plugin, GSitAccess> factory) {
        try {
            Plugin dependency = this.plugin.getServer().getPluginManager().getPlugin("GSit");
            if (dependency == null || !dependency.isEnabled()) {
                this.gsitDiagnosis = "未启用 GSit，GSit 坐姿和爬行指令不可用；原生趴下、床上睡眠和载具动画仍可识别。";
                this.plugin.getLogger().info(this.gsitDiagnosis);
                return null;
            }
            String version = dependency.getDescription().getVersion();
            if (!version.startsWith("3.")) {
                this.gsitDiagnosis = "检测到 GSit " + version + "；本插件需要 GSit 3.x 公共 API，GSit 姿态后端已停用。";
                this.plugin.getLogger().warning(this.gsitDiagnosis);
                return null;
            }
            // Load the class that references GSit only after checking the optional plugin.
            GSitAccess bridge = Objects.requireNonNull(factory == null ? new DirectGSitBridge(dependency)
                    : factory.apply(dependency), "GSit adapter");
            this.replicaAdapter = version.equals("3.5.1");
            this.gsitDiagnosis = "已接入 GSit " + version + " 公共 API：坐姿/躺下/趴下/爬行可识别。"
                    + (this.replicaAdapter ? " 假玩家隐藏适配可用。" : " 当前版本未启用假玩家隐藏适配。");
            this.plugin.getLogger().info(this.gsitDiagnosis);
            return bridge;
        } catch (RuntimeException | LinkageError failure) {
            this.replicaAdapter = false;
            this.gsitDiagnosis = "GSit 公共 API 加载失败，GSit 姿态后端已停用；其他功能继续运行。";
            this.plugin.getLogger().log(Level.WARNING, this.gsitDiagnosis, failure);
            return null;
        }
    }

    private void registerPoseLifecycle() {
        this.gsit.registerPoseLifecycle(this.plugin, this.poseLifecycle, (owner, pose) -> {
            int range = owner.getWorld().getSimulationDistance() * 16;
            List<Player> fallback = owner.getWorld().getPlayers().stream()
                    .filter(viewer -> viewer.canSee(owner) && viewer.getLocation()
                            .distanceSquared(owner.getLocation()) <= (double) range * range)
                    .toList();
            this.replicas.beforeRemoval(owner.getUniqueId(), pose, fallback);
        }, this::disableReplica);
    }

    private <T> T gsitCall(Supplier<T> operation, T fallback) {
        if (!available()) {
            return fallback;
        }
        try {
            return operation.get();
        } catch (RuntimeException | LinkageError failure) {
            disableGSit(failure);
            return fallback;
        }
    }

    private void disableGSit(Throwable failure) {
        if (this.gsitFailed) {
            return;
        }
        this.gsitFailed = true;
        this.postures.clear();
        this.gsitDiagnosis = "GSit 已停用或 API 调用失败，GSit 接入已隔离；原生姿态和其他功能继续运行。";
        if (this.replicaAdapter) {
            disableReplica(failure);
        }
        this.plugin.getLogger().log(Level.WARNING, this.gsitDiagnosis, failure);
    }

    private void disableReplica(Throwable failure) {
        if (!this.replicaAdapter) {
            return;
        }
        this.replicaAdapter = false;
        this.gsitDiagnosis = this.gsitDiagnosis.replace("假玩家隐藏适配可用。", "假玩家隐藏适配已因故障停用。");
        HandlerList.unregisterAll(this.poseLifecycle);
        try {
            this.replicas.close();
        } catch (RuntimeException | LinkageError cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
        this.plugin.getLogger().log(Level.WARNING, "GSit 假玩家适配器已停用；公共姿态接入不受此适配器故障影响", failure);
    }

    private void restoreReplica(UUID owner) {
        try {
            this.replicas.restore(owner);
        } catch (RuntimeException | LinkageError failure) {
            disableReplica(failure);
            if (!this.replicaRestoreWarning) {
                this.replicaRestoreWarning = true;
                this.plugin.getLogger().log(Level.WARNING, "GSit 假玩家显示恢复失败；其他功能继续运行", failure);
            }
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

    private enum PostureKind {
        SIT, CRAWL
    }

    private record OwnedPosture(PostureKind kind, Object handle) {
    }

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
            this.originalAllowFlight = player.getAllowFlight();
            this.originalFlying = player.isFlying();
            this.originalFlySpeed = player.getFlySpeed();
            this.originalGameMode = player.getGameMode();
        }
    }

}
