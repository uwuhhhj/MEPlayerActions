package com.simmc.meplayeractions.action;

import com.simmc.meplayeractions.client.ClientSyncService;
import com.simmc.meplayeractions.client.ClientSyncService.LayerState;
import com.simmc.meplayeractions.client.ClientSyncService.StateSnapshot;
import com.simmc.meplayeractions.client.ClientSyncService.AnimationInfo;
import com.simmc.meplayeractions.client.ClientSyncService.MotionState;
import com.simmc.meplayeractions.config.Settings;
import com.simmc.meplayeractions.gameplay.GameplayBackend;
import com.simmc.meplayeractions.gameplay.GSitAnchor;
import com.simmc.meplayeractions.gameplay.DisguiseEffects;
import com.simmc.meplayeractions.gameplay.PaperEffectPort;
import com.simmc.meplayeractions.me.ModelEngineBridge;
import com.simmc.meplayeractions.me.ModelEngineBridge.Attachment;
import com.simmc.meplayeractions.me.ModelEngineBridge.OwnedAnimation;
import com.simmc.meplayeractions.me.VisualOffset;
import com.ticxo.modelengine.api.events.BoneTransformReadEvent;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ActionController {
    private final JavaPlugin plugin;
    private final Settings settings;
    private final ModelEngineBridge bridge;
    private final GameplayBackend gameplay;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private Set<UUID> ownerIds = Set.of();
    private final Map<ActiveModel, VisualOffset> visualOffsets = new ConcurrentHashMap<>();
    private final Set<UUID> adoptWarning = new HashSet<>();
    private final Set<UUID> adoptPaused = new HashSet<>();
    private final File preferenceFile;
    private final YamlConfiguration preferences;
    private BukkitTask task;
    private ClientSyncService clients;
    private long clock;
    private final RollingScan<Player> adoption;

    private static final class Session {
        final Player player;
        final Attachment attachment;
        final UUID instance = UUID.randomUUID();
        final List<String> animations;
        final List<AnimationInfo> actionDirectory;
        final DisguiseOptions options;
        final DisguiseEffects effects;
        long sequence, manualStarted, postureStarted, interactionStarted, nextManual, nextSample, nextAnimation, lastSwing, appliedJumpCycle;
        int manualLimit;
        OwnedAnimation manual, posture, interaction;
        ActionState state, interactionState;
        StateSelector.Sample sample;
        boolean inputInterrupt;
        MovementSampler movement = new MovementSampler();
        final PositionMotion actualMotion = new PositionMotion();
        final InteractionTracker interactions;
        final JumpTracker jump = new JumpTracker();
        final VisualTimeline visual = new VisualTimeline();
        int jumpDuration;
        VisualTimeline.Frame visualFrame;
        BedAnchor bedAnchor;
        GSitAnchor gsitAnchor;
        Location lastVisualLocation;
        final Set<ActionState> missingMappings = EnumSet.noneOf(ActionState.class);
        String failure = "";
        String manualPosture = "standing";
        Session(Player player, Attachment attachment, List<String> animations, Settings settings, DisguiseOptions options) {
            this.player = player; this.attachment = attachment; this.animations = List.copyOf(animations);
            actionDirectory = ActionDirectory.build(settings, this.animations);
            this.options = options;
            effects = new DisguiseEffects(new PaperEffectPort(player));
            interactions = new InteractionTracker(Math.max(settings.swingTicks, settings.animationInterval + 1),
                    settings.miningTimeoutTicks);
        }
    }

    public ActionController(JavaPlugin plugin, Settings settings, ModelEngineBridge bridge,
                            GameplayBackend gameplay) {
        this.plugin = plugin; this.settings = settings; this.bridge = bridge; this.gameplay = gameplay;
        adoption = new RollingScan<>(settings.performance.adoptionScanTicks());
        clock = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        preferenceFile = new File(plugin.getDataFolder(), "players.yml");
        preferences = YamlConfiguration.loadConfiguration(preferenceFile);
    }
    public void clients(ClientSyncService clients) { this.clients = clients; }
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }
    public List<String> models() { return bridge.modelIds().stream().filter(settings.allowedModels::contains).toList(); }
    public boolean controlled(Player player) { return sessions.containsKey(player.getUniqueId()); }
    public String modelId(Player player) { return requireSession(player).attachment.modelId(); }
    public List<String> animations(Player player) { return requireSession(player).animations.stream().filter(id -> !id.startsWith("parallel") && !id.startsWith("pre_parallel")).toList(); }

    public void disguise(Player player, String modelId) {
        disguise(player, DisguiseOptions.defaults(modelId, settings));
    }
    public void disguise(Player player, DisguiseOptions options) {
        clock = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        settings.requireAllowedModel(options.modelId());
        if (!options.effects().isEmpty()) permission(player, "mact.disguise.effects");
        options.requireEffects(PaperEffectPort::supported);
        if (!bridge.modelIds().contains(options.modelId())) throw new IllegalArgumentException("模型未加载：" + options.modelId());
        Session existing = sessions.get(player.getUniqueId());
        if (existing != null && !existing.attachment.owned())
            throw new IllegalStateException("先用 /meg undisguise 解除原生伪装，再使用 /meplayeractions disguise");
        if (existing != null && existing.options.equals(options) && options.effects().isEmpty()) return;
        if (existing != null) remove(player, "model-change");
        Attachment attached = bridge.disguise(player, options);
        register(player, attached, options);
    }
    public void attach(Player player, String modelId) {
        settings.requireAllowedModel(modelId);
        if (controlled(player)) throw new IllegalStateException("已有动作会话；先 /meplayeractions undisguise 释放");
        register(player, bridge.attachExisting(player, modelId));
    }
    private void register(Player player, Attachment attachment) {
        register(player, attachment, DisguiseOptions.defaults(attachment.modelId(), settings));
    }
    private void register(Player player, Attachment attachment, DisguiseOptions options) {
        Session session = new Session(player, attachment, bridge.animations(attachment), settings, options);
        String jumpClip = settings.animation(attachment.modelId(), ActionState.JUMP, session.animations);
        double length = jumpClip == null ? 0.6 : bridge.animationLength(attachment, jumpClip);
        var jumpPlayback = settings.playback(ActionState.JUMP);
        session.jumpDuration = settings.jumpMinTicks > 0 ? settings.jumpMinTicks
                : (int) Math.max(1, Math.min(100, Math.ceil(length * 20 / jumpPlayback.speed()) + jumpPlayback.inTicks()));
        sessions.put(player.getUniqueId(), session);
        ownerIds = Set.copyOf(sessions.keySet());
        adoptWarning.remove(player.getUniqueId());
        adoptPaused.remove(player.getUniqueId());
        try {
            session.effects.apply(options.effects(), clock); update(player, session); changed(session);
            if (!bridge.compatibilityAnimations(attachment).isEmpty()) {
                String diagnosis = bridge.compatibilityDiagnosis(attachment);
                player.sendMessage("§e[动作] " + diagnosis + "。");
                plugin.getLogger().info(player.getName() + "：" + diagnosis);
            }
        }
        catch (RuntimeException failure) {
            try { remove(player, "registration-failed"); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public boolean remove(Player player, String reason) {
        if (reason.equals("quit") || reason.equals("offline")) {
            adoptPaused.remove(player.getUniqueId()); adoptWarning.remove(player.getUniqueId());
        }
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return false;
        boolean owned = s.attachment.owned();
        RuntimeException failure = attempt(null, () -> stopLayers(s));
        failure = attempt(failure, () -> s.effects.clear(Integer.toUnsignedLong(Bukkit.getCurrentTick()), !reason.equals("death")));
        failure = attempt(failure, () -> gameplay.cleanup(player));
        failure = attempt(failure, () -> { if (clients != null) clients.unbind(player.getUniqueId(), s.instance, reason); });
        failure = attempt(failure, () -> bridge.release(s.attachment));
        if (failure != null) throw new IllegalStateException("会话清理尚未完成，将继续尝试", failure);
        sessions.remove(player.getUniqueId(), s); adoptWarning.remove(player.getUniqueId());
        ownerIds = Set.copyOf(sessions.keySet());
        visualOffsets.remove(s.attachment.activeModel());
        if (!owned && reason.equals("command")) adoptPaused.add(player.getUniqueId());
        return owned;
    }
    public void play(Player player, String name, Double speedOverride, LoopMode loopOverride) {
        clock = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        Session s = requireSession(player);
        if (clock < s.nextManual) throw new IllegalStateException("动作切换过快，请稍后再试");
        Settings.CustomAction action = settings.customActions.get(name);
        String clip;
        double speed;
        LoopMode loop;
        int limit;
        if (action != null) {
            permission(player, action.permission());
            clip = action.animation(); speed = action.speed(); loop = action.loop(); limit = action.maxTicks();
        } else {
            if (!settings.rawPlay) throw new IllegalArgumentException("动作未列入 custom-actions：" + name);
            permission(player, "mact.play");
            clip = Settings.id(name); speed = 1; loop = LoopMode.ONCE; limit = settings.maxManualTicks;
        }
        if (speedOverride != null) speed = speedOverride;
        if (loopOverride != null) loop = loopOverride;
        settings.validateSpeed(speed);
        if (!s.animations.contains(clip)) throw new IllegalArgumentException("当前模型没有动画：" + clip);
        // Validate before replacing the old action.
        OwnedAnimation next = bridge.play(s.attachment, settings.manualPriority, clip,
                settings.inTicks, settings.outTicks, speed, loop, true);
        if (s.manual != null) s.manual.stop(true);
        s.manual = next; s.manualStarted = clock; s.manualLimit = limit;
        s.manualPosture = sample(player, s).postureKey();
        clearInteraction(s); s.interactions.clear();
        s.nextManual = clock + settings.cooldownTicks;
        changed(s);
    }
    public void stop(Player player) {
        Session s = requireSession(player);
        if (s.manual != null) { s.manual.stop(false); s.manual = null; changed(s); }
    }
    public void reset(Player player, boolean safeDismount) {
        Session s = sessions.get(player.getUniqueId());
        RuntimeException failure = attempt(null, () -> gameplay.cleanup(player, safeDismount));
        if (s != null) {
            failure = attempt(failure, () -> stopLayers(s));
            s.state = null; s.sample = null; s.movement = new MovementSampler();
            s.interactions.clear(); s.nextSample = s.nextAnimation = 0; changed(s);
            s.jump.reset(); s.visual.clear(); s.lastVisualLocation = null; s.visualFrame = null;
            visualOffsets.remove(s.attachment.activeModel());
        }
        if (failure != null) throw new IllegalStateException("重置部分失败，请查看日志", failure);
    }
    public void clearDisguiseEffects(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) s.effects.clear(Integer.toUnsignedLong(Bukkit.getCurrentTick()), true);
    }
    public void potionChanged(Player player, String id, boolean expiration) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) s.effects.changed(id, expiration);
    }
    public void sit(Player player) {
        Session s = requireSession(player);
        if (syncEnabled(player, SyncFeature.SIT)) requireAnimation(s, ActionState.SIT);
        gameplay.sit(player);
        update(player, s);
    }
    public void crawl(Player player) {
        Session s = requireSession(player);
        if (syncEnabled(player, SyncFeature.CRAWL)) requireAnimation(s, ActionState.CRAWL_IDLE);
        gameplay.crawl(player);
        update(player, s);
    }
    public void flight(Player player, boolean enabled) {
        requireSession(player);
        if (enabled) {
            permission(player, "mact.flight");
            if (!settings.allowFlight) throw new IllegalStateException("管理员需开启 gameplay.allow-flight-command");
            gameplay.enableFlight(player, settings.flightSpeed);
        } else gameplay.disableOwnedFlight(player);
        update(player, requireSession(player));
    }
    public void damaged(Player player) {
        if (settings.interruptDamage && controlled(player)) stop(player);
    }
    public void swung(Player player, EquipmentSlot hand) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) s.interactions.swing(hand == EquipmentSlot.OFF_HAND,
                Integer.toUnsignedLong(Bukkit.getCurrentTick()));
    }
    public void mining(Player player, Block block) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) s.interactions.startMining(target(block), Integer.toUnsignedLong(Bukkit.getCurrentTick()));
    }
    public void miningStopped(Player player, Block block) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) s.interactions.stopMining(target(block));
    }
    public void clearInteractions(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) s.interactions.clear();
    }
    public void jumped(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) { s.jump.signal(Integer.toUnsignedLong(Bukkit.getCurrentTick())); s.nextSample = 0; }
    }
    /** ME can call this on its renderer thread: only immutable offsets and model data are read. */
    public void visualTransform(BoneTransformReadEvent event) {
        ActiveModel model = event.getModelBone().getActiveModel();
        VisualOffset offset = visualOffsets.get(model);
        if (offset != null) offset.apply(event.getPosition(), model.getYBodyRot());
    }
    private static InteractionTracker.Target target(Block block) {
        return block == null ? null : new InteractionTracker.Target(block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ());
    }
    public boolean syncEnabled(Player player, SyncFeature feature) {
        return settings.syncEnabled && settings.sync(feature)
                && preferences.getBoolean(player.getUniqueId() + "." + feature.key(), true);
    }
    public void synchronization(Player player, SyncFeature feature, Boolean value) {
        preferences.set(player.getUniqueId() + "." + feature.key(), value);
        try { preferences.save(preferenceFile); }
        catch (IOException ex) { throw new IllegalStateException("无法保存玩家同步偏好", ex); }
        Session s = sessions.get(player.getUniqueId());
        if (s != null) {
            clearPosture(s); clearInteraction(s); s.interactions.clear();
            s.state = null; s.interactionState = null; s.jump.reset(); s.visual.clear();
            visualOffsets.remove(s.attachment.activeModel()); update(player, s); changed(s);
        }
    }
    public List<String> debug(Player player) {
        Session s = requireSession(player);
        var localRendering = bridge.localRenderingDiagnosis(s.attachment);
        List<String> lines = new ArrayList<>(List.of("模型：" + s.attachment.modelId(),
                "处理器：" + s.attachment.activeModel().getAnimationHandler().getId(),
                "模型原有动画：" + s.attachment.activeModel().getBlueprint().getAnimations().size()
                        + "；会话内补齐：" + bridge.compatibilityAnimations(s.attachment),
                "模型兼容：" + bridge.compatibilityDiagnosis(s.attachment),
                "伪装来源：" + (s.attachment.owned() ? "本插件创建" : "接管原生 ME"),
                "模型定位：" + (s.attachment.activeModel().getPivotOverride().isPresent()
                        ? "挂载玩家/原生枢轴（高度由 ME 乘挂偏移决定）" : "独立视觉枢轴（以玩家脚底坐标定位）"),
                "指令参数：" + (s.attachment.owned() ? s.options.description() : "保留原生伪装参数")
                        + "；受管药水：" + s.effects.activeIds(),
                "状态：" + s.state + "；自动动画：" + (s.posture == null ? "无" : s.posture.animation()),
                "手动动画：" + (s.manual == null ? "无" : s.manual.animation()),
                "手臂状态：" + s.interactionState + "；动画：" + (s.interaction == null ? "无" : s.interaction.animation()),
                "采样姿态：" + (s.sample == null ? "未采样" : s.sample.postureKey())
                        + "；原生 Pose：" + player.getPose() + "；载具：" + (player.getVehicle() == null ? "无" : player.getVehicle().getType()),
                "采样/动画切换间隔：" + settings.interval + "/" + settings.animationInterval + " tick；过渡：" + settings.inTicks + "/" + settings.outTicks,
                "视觉延迟：" + s.options.visualDelay() + " tick；跳跃最短播放：" + s.jumpDuration + " tick；视觉离地状态："
                        + (s.visualFrame == null ? "未采样" : s.visualFrame.air()),
                "真实坐下/爬行：" + gameplay.isSitting(player) + "/" + gameplay.isCrawling(player),
                "GSit：" + gameplay.diagnosis(),
                "本地接管：" + (localRendering.allowed() ? "允许申请" : "不允许")
                        + "；attached=" + localRendering.attached() + "；owned=" + localRendering.owned()
                        + "；audience=" + localRendering.audience() + "；模型数量=" + localRendering.modelCount() + "/1"
                        + "；" + localRendering.reason(),
                "客户端：" + (clients == null ? "未启用" : clients.status(player))));
        if (!s.failure.isEmpty()) lines.add("最近动作失败：" + s.failure);
        if (s.attachment.owned()) lines.add("模型观众：" + bridge.viewerCount(s.attachment)
                + "/" + s.options.maxViewers() + " 名其他玩家（最近者优先，受 ME 跟踪/剔除限制）；本人可见 " + s.options.showSelf());
        if (s.state != null) {
            var candidates = settings.animationCandidates(s.attachment.modelId(), s.state);
            String resolved = settings.animation(s.attachment.modelId(), s.state, s.animations);
            lines.add("动画映射：" + s.state.key() + " → " + candidates + "；实际命中：" + (resolved == null ? "无" : resolved));
            if (resolved == null) lines.add(candidates.isEmpty() ? "未播放原因：配置禁用了此映射"
                    : "未播放原因：当前实例缺少全部候选动画；检查重复同名 bbmodel 和模型重载结果");
            else lines.add("动画来源：" + bridge.animationSource(s.attachment, resolved) + "；播放层："
                    + (s.posture == null ? "已退出（候选动画存在）" : s.posture.status()));
        }
        lines.add("原生陆地趴下/爬行无需 GSit；GSit 后端状态仅影响 GSit API 姿态及 /meplayeractions pose sit、pose crawl。");
        return lines;
    }
    public List<StateSnapshot> snapshots() { return sessions.values().stream().map(this::snapshot).toList(); }
    /** Immutable owner membership, rebuilt only when a session is added or removed. */
    public Set<UUID> ownerIds() { return ownerIds; }
    /** Build only the requested owner's current state; absent sessions have no snapshot. */
    public StateSnapshot snapshot(UUID owner) {
        Session session = sessions.get(owner);
        return session == null ? null : snapshot(session);
    }
    public boolean canView(Player viewer, UUID owner) {
        Session session = sessions.get(owner);
        return session != null && bridge.isAttached(session.attachment) && bridge.canView(session.attachment, viewer.getUniqueId());
    }
    public boolean localRendering(UUID viewer, UUID owner, UUID instance, boolean enabled) {
        Session session = sessions.get(owner);
        if (session == null || !session.instance.equals(instance)) return !enabled;
        return bridge.localRendering(session.attachment, viewer, enabled);
    }

    private void tick() {
        clock = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        for (Session s : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(s.attachment.playerId());
            try {
                if (player == null || !player.isOnline()) { remove(s.player, "offline"); continue; }
                if (!bridge.isAttached(s.attachment)) { remove(player, "external-undisguise"); continue; }
                if (bridge.updateAudience(s.attachment)) changed(s);
                s.effects.tick(clock);
                update(player, s, false);
                bridge.updateExpressions(s.attachment,clock,settings.posturePriority,settings.manualPriority);
            }
            catch (RuntimeException ex) {
                if (!Objects.equals(ex.getMessage(), s.failure)) plugin.getLogger().warning("动作更新失败 " + s.player.getName() + ": " + ex.getMessage());
                s.failure = Objects.toString(ex.getMessage(), ex.getClass().getSimpleName());
            }
        }
        if (settings.adoptOriginal) {
            for (Player player : adoption.next(clock, Bukkit::getOnlinePlayers)) {
                if (!player.isOnline() || controlled(player) || adoptPaused.contains(player.getUniqueId()) || !player.hasPermission("mact.use")) continue;
                for (String model : settings.allowedModels) {
                    try { register(player, bridge.attachExisting(player, model)); break; }
                    catch (IllegalArgumentException ignored) { /* This model is not attached. */ }
                    catch (IllegalStateException ex) {
                        if (Objects.toString(ex.getMessage(), "").contains("state_machine") && adoptWarning.add(player.getUniqueId()))
                            player.sendMessage("§e[动作] 原生伪装不是状态机。请重新用 /meplayeractions disguise，或启用 ME 的 Use-State-Machine 后重新伪装。");
                    }
                }
            }
        }
    }
    private void update(Player player, Session s) {
        clock = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        update(player, s, true);
    }
    private StateSelector.Sample sample(Player player, Session s) {
        Location now = player.getLocation();
        Input input = player.getCurrentInput();
        boolean moveInput = input.isForward() || input.isBackward() || input.isLeft() || input.isRight()
                || (player.isFlying() && (input.isJump() || input.isSneak()));
        boolean moving = s.movement.sample(now.getWorld().getUID(), now.getX(), now.getY(), now.getZ(), clock,
                player.isFlying() || player.isSwimming(), moveInput, settings.movementThreshold);
        var posture = gameplay.observe(player);
        s.bedAnchor = BedAnchor.observe(player);
        s.gsitAnchor = gameplay.visualAnchor(player);
        s.inputInterrupt = input.isJump() || input.isSneak();
        if (s.interactions.target() != null) s.interactions.validateTarget(target(player.getTargetBlockExact(6)));
        s.nextSample = clock + settings.interval;
        s.sample = new StateSelector.Sample(posture.sitting(), posture.crawling(), player.isGliding(), player.isFlying(),
                player.isSwimming() || (player.isInWater() && player.getPose() == org.bukkit.entity.Pose.SWIMMING),
                player.isInWater(), player.isOnGround(), player.isSneaking() || player.getPose() == org.bukkit.entity.Pose.SNEAKING, player.isSprinting(),
                moving, posture.sleeping(), s.bedAnchor != null, posture.vehicle());
        return s.sample;
    }
    private void update(Player player, Session s, boolean immediate) {
        gameplay.syncPoseReplica(player, s.attachment.owned() && s.options.hideSelf());
        if (immediate || s.sample == null || clock >= s.nextSample) sample(player, s);
        StateSelector.Sample sample = s.sample;
        if (s.manual != null && (s.manual.isFinished() || clock - s.manualStarted >= s.manualLimit
                || (settings.interruptMove && (sample.moving() || s.inputInterrupt))
                || (settings.interruptPosture && !s.manualPosture.equals(sample.postureKey())))) {
            s.manual.stop(false); s.manual = null; changed(s);
        }
        if (s.manual != null || sample.sleeping()) s.interactions.clear();
        Location location = player.getLocation();
        if (s.lastVisualLocation != null && (s.lastVisualLocation.getWorld() != location.getWorld()
                || s.lastVisualLocation.distanceSquared(location) > settings.visualMaxDistance * settings.visualMaxDistance)) {
            s.jump.reset(); s.visual.clear();
        }
        double rise = s.actualMotion.sample(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), clock).y();
        s.lastVisualLocation = location;
        boolean blocked = sample.sitting() || sample.crawling() || sample.sleeping()
                || sample.vehicle() != StateSelector.Vehicle.NONE || sample.flying() || sample.gliding()
                || sample.swimming() || sample.inWater() || !syncEnabled(player, SyncFeature.JUMP);
        blocked |= player.isClimbing();
        ActionState air = s.jump.sample(player.isOnGround(), rise, blocked, clock, s.jumpDuration, settings.jumpLandingTicks);
        var bed = sample.bedSleeping() ? s.bedAnchor : null;
        var gsit = bed == null && (sample.sitting() || sample.sleeping() || sample.crawling()) ? s.gsitAnchor : null;
        var rotation = bridge.rotation(s.attachment);
        float bodyYaw = bed != null ? bed.bodyYaw() : gsit != null ? gsit.bodyYaw() : rotation.bodyYaw();
        boolean reclining = bed != null || (gsit != null && sample.sleeping());
        var frame = new VisualTimeline.Frame(clock, location.getWorld().getUID(), bed != null ? bed.x() : gsit != null ? gsit.x() : location.getX(),
                bed != null ? bed.y() : gsit != null ? gsit.y() : location.getY(), bed != null ? bed.z() : gsit != null ? gsit.z() : location.getZ(),
                sample, air, s.jump.cycle(), bodyYaw, reclining ? bodyYaw : rotation.headYaw(),
                reclining ? 0 : rotation.headPitch());
        s.visualFrame = s.visual.sample(frame, s.options.visualDelay(), settings.visualMaxDistance, settings.visualSnapPostures);
        bridge.visualRotation(s.attachment, s.visualFrame.bodyYaw(), s.visualFrame.headYaw(), s.visualFrame.headPitch());
        visualOffsets.put(s.attachment.activeModel(), new VisualOffset(s.visualFrame.x() - location.getX(),
                s.visualFrame.y() - location.getY(), s.visualFrame.z() - location.getZ()));
        if (!immediate && clock < s.nextAnimation) return;
        s.nextAnimation = clock + settings.animationInterval;
        ActionState next = StateSelector.select(s.visualFrame.pose(), f -> syncEnabled(player, f), s.visualFrame.air());
        if ((sample.postureKey().equals("standing") || sample.postureKey().equals("sneak")) && player.isClimbing())
            next = syncEnabled(player, SyncFeature.MOVEMENT) ? Math.abs(rise) > settings.movementThreshold ? ActionState.LADDER_MOVE : ActionState.LADDER_IDLE : null;
        if (next == ActionState.RIDE && player.getVehicle() instanceof org.bukkit.entity.Pig) next = ActionState.RIDE_PIG;
        boolean newJump = next == ActionState.JUMP && s.appliedJumpCycle != s.visualFrame.jumpCycle();
        if (newJump || next != s.state || (s.posture == null && next != null && settings.playback(next).loop() != LoopMode.ONCE
                && settings.animation(s.attachment.modelId(), next, s.animations) != null)) {
            String clip = next == null ? null : settings.animation(s.attachment.modelId(), next, s.animations);
            var playback = next == null ? null : settings.playback(next);
            if (next != null && clip == null && s.missingMappings.add(next)) {
                String reason = settings.animationCandidates(s.attachment.modelId(), next).isEmpty()
                        ? "配置禁用了映射" : "模型没有候选动画";
                String message = "模型 " + s.attachment.modelId() + " 的 " + next.key() + " 未播放：" + reason;
                if (next == ActionState.CRAWL_IDLE || next == ActionState.CRAWL_WALK)
                    message += "。请更新安装包内对应 bbmodel，/meg reload models 后重新伪装；不要映射到直立 climb";
                player.sendMessage("§e[动作] " + message);
                plugin.getLogger().warning(message);
            }
            OwnedAnimation nextAnimation = null;
            if (!newJump && samePlayback(s.posture, clip, playback)) nextAnimation = s.posture;
            else {
                if (clip != null) nextAnimation = bridge.play(s.attachment, settings.posturePriority, clip,
                        playback.inTicks(), playback.outTicks(), playback.speed(), playback.loop(), true);
                clearPosture(s); s.postureStarted = clock;
            }
            s.state = next; s.posture = nextAnimation;
            if (next == ActionState.JUMP) s.appliedJumpCycle = s.visualFrame.jumpCycle();
            s.failure = ""; changed(s);
        } else if (s.posture != null && s.posture.isFinished()) {
            s.posture.stop(false); s.posture = null; changed(s);
        }
        updateInteraction(player, s);
    }
    private static boolean samePlayback(OwnedAnimation current, String clip, Settings.Playback playback) {
        return current != null && playback != null && !current.isFinished() && current.animation().equals(clip)
                && current.speed() == playback.speed() && current.loop() == playback.loop()
                && current.inTicks() == playback.inTicks() && current.outTicks() == playback.outTicks();
    }
    private void updateInteraction(Player player, Session s) {
        ActionState next = s.interactions.select(clock, syncEnabled(player, SyncFeature.MINING), syncEnabled(player, SyncFeature.SWING));
        if (s.manual != null || s.sample.sleeping()) next = null;
        boolean finished = s.interaction != null && s.interaction.isFinished();
        boolean newSwing = s.lastSwing != s.interactions.swingSequence();
        if (next != s.interactionState || (next != null && (finished || s.interaction == null) && newSwing)
                || (next == ActionState.MINING && finished)) {
            String clip = next == null ? null : settings.animation(s.attachment.modelId(), next, s.animations);
            var playback = next == null ? null : settings.playback(next);
            OwnedAnimation nextAnimation = clip == null ? null : bridge.play(s.attachment, settings.interactionPriority,
                    clip, playback.inTicks(), playback.outTicks(), playback.speed(), playback.loop(), false);
            clearInteraction(s);
            s.interaction = nextAnimation; s.interactionState = next;
            s.interactionStarted = clock; s.lastSwing = s.interactions.swingSequence(); changed(s);
        } else if (finished) { clearInteraction(s); changed(s); }
    }
    private void clearInteraction(Session s) {
        if (s.interaction != null) { s.interaction.stop(false); s.interaction = null; }
    }
    private void requireAnimation(Session s, ActionState state) {
        if (settings.animation(s.attachment.modelId(), state, s.animations) == null)
            throw new IllegalArgumentException("模型缺少 " + state.key() + " 动画；请安装玩家模型或关闭对应姿态同步");
    }
    private Session requireSession(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) throw new IllegalStateException("先使用 /meplayeractions disguise，或 /meplayeractions attach <模型名>");
        if (!bridge.isAttached(s.attachment)) throw new IllegalStateException("伪装已被解除，请重新伪装");
        return s;
    }
    private void clearPosture(Session s) {
        clearPosture(s, false);
    }
    private void clearPosture(Session s, boolean immediate) {
        if (s.posture != null) { s.posture.stop(immediate); s.posture = null; }
    }
    private void stopLayers(Session s) {
        RuntimeException failure = attempt(null, () -> clearPosture(s, true));
        failure = attempt(failure, () -> clearInteraction(s));
        s.interactions.clear(); s.interactionState = null;
        failure = attempt(failure, () -> { if (s.manual != null) { s.manual.stop(true); s.manual = null; } });
        if (failure != null) throw failure;
    }
    private static RuntimeException attempt(RuntimeException first, Runnable step) {
        try { step.run(); }
        catch (RuntimeException next) { if (first == null) return next; if (first != next) first.addSuppressed(next); }
        return first;
    }
    private void changed(Session s) { s.sequence++; if (clients != null) clients.broadcast(snapshot(s)); }
    private StateSnapshot snapshot(Session s) {
        List<LayerState> layers = new ArrayList<>();
        if (s.posture != null) layers.add(layer("posture", s.posture, s.postureStarted));
        if (s.interaction != null) layers.add(layer("interaction", s.interaction, s.interactionStarted));
        if (s.manual != null) layers.add(layer("manual", s.manual, s.manualStarted));
        Location location = s.player.getLocation();
        var model = s.attachment.activeModel();
        var visual = s.visualFrame != null && s.visualFrame.world().equals(location.getWorld().getUID()) ? s.visualFrame : null;
        return new StateSnapshot(s.attachment.playerId(), s.instance, s.attachment.modelId(), s.sequence,
                Integer.toUnsignedLong(Bukkit.getCurrentTick()), List.copyOf(layers), location.getWorld().getUID(),
                visual == null ? location.getX() : visual.x(),
                visual == null ? location.getY() : visual.y(),
                visual == null ? location.getZ() : visual.z(),
                visual == null ? model.getYBodyRot() : visual.bodyYaw(),
                visual == null ? model.getYHeadRot() : visual.headYaw(),
                visual == null ? model.getXHeadRot() : visual.headPitch(), model.getScale().x(),
                s.options.hideSelf(), s.options.showSelf(), s.actionDirectory, bridge.supportsLocalRendering(s.attachment), motion(s, location),
                bridge.accessories(s.attachment));
    }
    private MotionState motion(Session s, Location location) {
        List<String> features = Arrays.stream(SyncFeature.values()).filter(f -> syncEnabled(s.player, f)).map(SyncFeature::key).toList();
        List<LayerState> clips = new ArrayList<>();
        for (ActionState state : ActionState.values()) {
            String animation = settings.animation(s.attachment.modelId(), state, s.animations);
            var playback = settings.playback(state);
            if (animation != null) clips.add(new LayerState(state.key(), animation, 0, playback.speed(), playback.loop().name(),
                    playback.inTicks(), playback.outTicks()));
        }
        // GSit lowers the real player's seat. Send its current contact offset, never the delayed visual frame.
        var anchor = s.gsitAnchor;
        String special = anchor == null || !anchor.world().equals(location.getWorld().getUID()) || s.sample == null
                || s.sample.bedSleeping() ? "" : s.sample.sleeping() ? "sleep" : s.sample.sitting() ? "sit" : s.sample.crawling() ? "crawl" : "";
        boolean anchored = !special.isEmpty();
        String forced = s.sample != null && s.sample.crawling() && (s.player.hasFixedPose() || gameplay.isCrawling(s.player))
                ? "crawl" : s.player.hasFixedPose() && s.player.getPose() == org.bukkit.entity.Pose.SNEAKING ? "sneak" : "";
        return new MotionState(features, clips, s.jumpDuration, settings.jumpLandingTicks, settings.movementThreshold,
                settings.interruptMove, settings.interruptPosture, s.player.isFlying(), s.interactionState == null ? "" : s.interactionState.key(), forced, special,
                anchored ? anchor.x() - location.getX() : 0, anchored ? anchor.y() - location.getY() : 0,
                anchored ? anchor.z() - location.getZ() : 0, anchored ? anchor.bodyYaw() : 0);
    }
    private static LayerState layer(String name, OwnedAnimation a, long tick) {
        return new LayerState(name, a.animation(), tick, a.speed(), a.loop().name(), a.inTicks(), a.outTicks());
    }
    public static void permission(Player player, String node) {
        if (!node.isEmpty() && !player.hasPermission(node)) throw new IllegalStateException("缺少权限：" + node);
    }
    public void close() {
        if (task != null) task.cancel();
        for (Session s : List.copyOf(sessions.values())) {
            try {
                remove(s.player, "plugin-close");
            } catch (RuntimeException ex) { plugin.getLogger().warning("会话清理失败：" + ex.getMessage()); }
        }
        sessions.clear(); ownerIds = Set.of(); adoption.clear(); visualOffsets.clear(); adoptPaused.clear(); adoptWarning.clear();
    }
}
