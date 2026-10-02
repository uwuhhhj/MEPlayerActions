package com.simmc.meplayeractions.me;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.handler.AnimationHandler;
import com.ticxo.modelengine.api.animation.handler.IStateMachineHandler;
import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import com.ticxo.modelengine.api.animation.property.SimpleProperty;
import com.ticxo.modelengine.api.entity.data.BukkitEntityData;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes;
import com.ticxo.modelengine.api.model.bone.type.PlayerLimb;
import com.ticxo.modelengine.api.model.bone.type.UserLimb;
import com.ticxo.modelengine.api.nms.entity.wrapper.TrackedEntity;
import com.ticxo.modelengine.api.utils.config.ConfigProperty;
import com.ticxo.modelengine.api.utils.data.io.SavedData;
import com.ticxo.modelengine.core.animation.handler.StateMachineHandler;
import com.simmc.meplayeractions.action.DisguiseOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** ModelEngine R4.1.1 state-machine adapter. All operations run on Paper's main thread. */
public final class ModelEngineBridge {
    private final Map<UUID, Session> sessions = new HashMap<>();

    public record Attachment(UUID playerId, String modelId, ActiveModel activeModel, boolean owned) {
        public Attachment {
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(modelId);
            Objects.requireNonNull(activeModel);
        }
    }

    public List<String> modelIds() {
        requireMainThread();
        return ModelEngineAPI.getAPI().getModelRegistry().getOrderedId().stream().sorted().toList();
    }

    /** Creates a model only when the player has no other attached ModelEngine models. */
    public Attachment disguise(Player player, DisguiseOptions options) {
        requireMainThread();
        requirePlayer(player);
        requireUnusedPlayer(player.getUniqueId());
        double scale = options.scale();
        boolean hideSelf = options.hideSelf();
        String id = requireId(options.modelId(), "模型");
        ModelBlueprint blueprint = ModelEngineAPI.getBlueprint(id);
        if (blueprint == null) throw new IllegalArgumentException("模型未加载：" + id);

        ModeledEntity entity = ModelEngineAPI.getModeledEntity(player.getUniqueId());
        if (entity != null && !entity.getModels().isEmpty()) {
            throw new IllegalStateException("玩家已有模型，请先解除伪装或使用接管命令");
        }
        if (entity != null && entity.isDestroyed()) {
            throw new IllegalStateException("旧模型正在销毁，请稍后重试");
        }
        boolean createdEntity = entity == null;
        if (entity == null) entity = ModelEngineAPI.createModeledEntity(player);
        if (entity == null) throw new IllegalStateException("ModelEngine 未能创建玩家模型实体");
        entity.restore();

        Session session = null;
        try {
            ActiveModel model = ModelEngineAPI.createActiveModel(blueprint, null, active -> {
                SavedData data = new SavedData();
                data.putString("id", "state_machine");
                AnimationHandler handler = ModelEngineAPI.getAnimationHandlerRegistry().createHandler(active, data);
                if (!(handler instanceof IStateMachineHandler)) {
                    throw new IllegalStateException("ModelEngine 未注册 state_machine 动画处理器");
                }
                return handler;
            });
            if (model == null) throw new IllegalStateException("ModelEngine 未能创建模型：" + id);
            Attachment attachment = new Attachment(player.getUniqueId(), blueprint.getName(), model, true);
            session = new Session(attachment, player, entity);
            session.audience = new ModelAudience(options);
            session.audience.update(player, tracked(session));
            // The entity data constructor cached its initial viewers before our filter
            // existed. Reconcile on the main thread before any model can be spawned;
            // ME's next async update consumes the start/stop queues for the renderer.
            if (entity.getBase().getData() instanceof BukkitEntityData data) data.syncUpdate();
            model.setScale(scale);
            // R4.1.1's player mount offset uses STANDING dimensions, while the client
            // uses the actual player pose. Keep the new model's default independent
            // feet-anchored display pivot; setPivotOverride(null) is unsupported in ME.
            // A disguise retains the real player's collision box.
            Optional<ActiveModel> replaced = entity.addModel(model, false);
            if (replaced.isPresent()) {
                session.displaced = replaced.get();
                throw new IllegalStateException("添加模型时检测到其他模型，已取消本次伪装");
            }
            if (entity.getModel(attachment.modelId()).orElse(null) != model) {
                throw new IllegalStateException("添加模型被其他插件取消");
            }

            session.changedPlayerMode = !session.previousPlayerMode;
            if (session.changedPlayerMode) entity.getBase().getBodyRotationController().setPlayerMode(true);
            session.assignedBaseVisible = !hideSelf;
            session.changedBaseVisible = entity.isBaseEntityVisible() != session.assignedBaseVisible;
            if (session.changedBaseVisible) entity.setBaseEntityVisible(session.assignedBaseVisible);
            if (hideSelf && !session.previousForcedInvisible) {
                session.changedForcedInvisible = true;
                ModelEngineAPI.getEntityHandler().setForcedInvisible(player, true);
            }
            model.getBones().values().forEach(bone -> {
                bone.getBoneBehavior(BoneBehaviorTypes.PLAYER_LIMB)
                        .ifPresent(limb -> ((PlayerLimb) limb).setTexture(player));
                bone.getBoneBehavior(BoneBehaviorTypes.USER_LIMB)
                        .ifPresent(limb -> ((UserLimb) limb).setTexture(player));
            });
            sessions.put(player.getUniqueId(), session);
            return attachment;
        } catch (RuntimeException | LinkageError failure) {
            try {
                if (session != null) cleanUp(session);
                else if (createdEntity && entity.getModels().isEmpty()
                        && ModelEngineAPI.getModeledEntity(player.getUniqueId()) == entity) {
                    ModelEngineAPI.removeModeledEntity(player.getUniqueId());
                }
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("创建玩家伪装失败：" + failure.getMessage(), failure);
        }
    }

    /** Adopts an existing /meg disguise without altering its visibility, pivot or renderer. */
    public Attachment attachExisting(Player player, String modelId) {
        requireMainThread();
        requirePlayer(player);
        requireUnusedPlayer(player.getUniqueId());
        ModeledEntity entity = ModelEngineAPI.getModeledEntity(player.getUniqueId());
        if (entity == null || entity.isDestroyed() || entity.getModels().isEmpty()) {
            throw new IllegalStateException("玩家没有可接管的 ModelEngine 伪装");
        }
        String id;
        if (modelId == null || modelId.isBlank()) {
            if (entity.getModels().size() != 1) throw new IllegalArgumentException("玩家有多个模型，请指定模型名");
            id = entity.getModels().keySet().iterator().next();
        } else id = modelId.trim();
        ActiveModel model = entity.getModel(id).orElseThrow(() -> new IllegalArgumentException("玩家未伪装为模型：" + id));
        requireStateMachine(model);
        if (model.isDestroyed() || model.isRemoved()) throw new IllegalStateException("该模型已经解除或正在销毁");
        Attachment attachment = new Attachment(player.getUniqueId(), id, model, false);
        sessions.put(player.getUniqueId(), new Session(attachment, player, entity));
        return attachment;
    }

    public boolean isAttached(Attachment attachment) {
        requireMainThread();
        if (attachment == null) return false;
        Session session = sessions.get(attachment.playerId());
        if (session == null || session.attachment != attachment || session.released) return false;
        ModeledEntity entity = ModelEngineAPI.getModeledEntity(attachment.playerId());
        return entity == session.entity && !entity.isDestroyed()
                && entity.getModel(attachment.modelId()).orElse(null) == attachment.activeModel()
                && !attachment.activeModel().isDestroyed() && !attachment.activeModel().isRemoved();
    }

    public List<String> animations(Attachment attachment) {
        requireMainThread();
        Session session = requireSession(attachment);
        var names = new java.util.TreeSet<>(attachment.activeModel().getBlueprint().getAnimations().keySet());
        names.addAll(session.compatibility.clips().keySet());
        return List.copyOf(names);
    }
    public boolean updateAudience(Attachment attachment) {
        Session session = requireSession(attachment);
        return session.audience != null && session.audience.update(session.player, tracked(session));
    }
    public boolean canView(Attachment attachment, UUID viewer) {
        Session session = requireSession(attachment);
        return session.audience == null || session.audience.allows(viewer);
    }
    public int viewerCount(Attachment attachment) {
        Session session = requireSession(attachment);
        return session.audience == null ? -1 : session.audience.otherViewers(attachment.playerId());
    }
    public record Rotation(float bodyYaw, float headYaw, float headPitch) { }
    /** Read the base's live rotation rather than our delayed model's locked values. */
    public Rotation rotation(Attachment attachment) {
        var base = requireSession(attachment).entity.getBase();
        return new Rotation(base.getYBodyRot(), base.getYHeadRot(), base.getXHeadRot());
    }
    /** Owned model rotation and bone offsets share the exact delayed visual frame. */
    public void visualRotation(Attachment attachment, float bodyYaw, float headYaw, float headPitch) {
        if (!attachment.owned()) return;
        var model = requireSession(attachment).attachment.activeModel();
        model.setLockedYBodyRot(bodyYaw); model.setLockedYHeadRot(headYaw); model.setLockedXHeadRot(headPitch);
        model.setModelRotationLocked(true);
    }
    public boolean supportsLocalRendering(Attachment attachment) {
        if (!isAttached(attachment) || !attachment.owned()) return false;
        Session session = requireSession(attachment);
        return session.audience != null && session.entity.getModels().size() == 1;
    }
    /** Per-viewer display replacement; never hides a foreign model sharing the entity. */
    public boolean localRendering(Attachment attachment, UUID viewer, boolean enabled) {
        if (!isAttached(attachment)) return !enabled;
        Session session = requireSession(attachment);
        if (enabled && (!supportsLocalRendering(attachment) || !session.audience.allows(viewer))) return false;
        if (session.audience == null) return !enabled;
        session.audience.localRendering(viewer, enabled);
        session.audience.update(session.player, tracked(session));
        if (session.entity.getBase().getData() instanceof BukkitEntityData data) data.syncUpdate();
        return true;
    }
    private static TrackedEntity tracked(Session session) {
        return session.entity.getBase().getData() instanceof BukkitEntityData data ? data.getTracked() : null;
    }
    public double animationLength(Attachment attachment, String name) {
        return animation(requireSession(attachment), name).getLength();
    }
    public String animationSource(Attachment attachment, String name) {
        return requireSession(attachment).compatibility.sources().getOrDefault(name, "ModelEngine 已加载模型");
    }
    public List<String> compatibilityAnimations(Attachment attachment) {
        return requireSession(attachment).compatibility.clips().keySet().stream().sorted().toList();
    }
    public String compatibilityDiagnosis(Attachment attachment) {
        return requireSession(attachment).compatibility.diagnosis();
    }
    private static BlueprintAnimation animation(Session session, String name) {
        BlueprintAnimation clip = session.attachment.activeModel().getBlueprint().getAnimations().get(name);
        if (clip == null) clip = session.compatibility.clips().get(name);
        if (clip == null) throw new IllegalArgumentException("模型没有动画：" + name);
        return clip;
    }

    /** Owns one property per reserved priority; refuses layers occupied by other code. */
    public OwnedAnimation play(Attachment attachment, int priority, String animation, int inTicks,
                               int outTicks, double speed, BlueprintAnimation.LoopMode loop, boolean override) {
        requireMainThread();
        Session session = requireSession(attachment);
        if (priority <= ConfigProperty.DEFAULT_PRIORITY.getInt()) {
            throw new IllegalArgumentException("动作优先级必须高于 ModelEngine 默认动作层");
        }
        if (inTicks < 0 || outTicks < 0 || !Double.isFinite(speed) || speed <= 0) {
            throw new IllegalArgumentException("动作过渡时间和速度无效");
        }
        if (loop == null) throw new IllegalArgumentException("必须指定动作循环方式");
        String name = requireId(animation, "动画");
        BlueprintAnimation clip = animation(session, name);
        IStateMachineHandler handler = requireStateMachine(attachment.activeModel());
        synchronized (layerLock(handler)) {
            OwnedAnimation old = session.layers.get(priority);
            IAnimationProperty current = priorityProperty(handler, priority);
            if (current != null && (old == null || current != old.property)) {
                throw new IllegalStateException("动作层 " + priority + " 已被其他技能占用");
            }
            SimpleProperty property = new SimpleProperty(attachment.activeModel(), clip,
                    inTicks * 0.05, outTicks * 0.05, speed);
            property.setForceLoopMode(loop);
            property.setForceOverride(override ? BlueprintAnimation.OverrideMode.OVERRIDE : BlueprintAnimation.OverrideMode.NONE);
            property.setEmptyZero(false);
            if (!handler.playAnimation(priority, property, current != null)) {
                throw new IllegalStateException("动作播放被取消或动作层尚未空闲");
            }
            OwnedAnimation result = new OwnedAnimation(this, session, handler, priority, name, property,
                    inTicks, outTicks, speed, loop);
            session.layers.put(priority, result);
            // Do not call name-based stop after queuing the replacement: it may add a root
            // transition before the newly queued node. Finish only our old property itself.
            if (old != null) finishImmediately(old.property);
            session.ownedAnimations.removeIf(handle -> handle.property.isEnded());
            session.ownedAnimations.add(result);
            return result;
        }
    }

    /** Releases actions, and removes the model only if this adapter created it. */
    public void release(Attachment attachment) {
        requireMainThread();
        if (attachment == null) return;
        Session session = sessions.get(attachment.playerId());
        if (session == null || session.attachment != attachment || session.released) return;
        cleanUp(session);
        session.released = true;
        sessions.remove(attachment.playerId(), session);
    }

    private void cleanUp(Session session) {
        RuntimeException failure = null;
        for (OwnedAnimation action : List.copyOf(session.ownedAnimations)) {
            try { action.stop(true); }
            catch (RuntimeException problem) { failure = accumulate(failure, problem); }
        }
        if (!session.attachment.owned()) {
            if (failure != null) throw failure;
            return;
        }
        ActiveModel own = session.attachment.activeModel();
        if (session.audience != null) session.audience.close(tracked(session), session.attachment.playerId());
        ModeledEntity current = ModelEngineAPI.getModeledEntity(session.attachment.playerId());
        boolean sameEntity = current == session.entity;
        if (sameEntity && !current.isDestroyed() && current.getModel(session.attachment.modelId()).orElse(null) == own) {
            Optional<ActiveModel> removed = current.removeModel(session.attachment.modelId());
            if (removed.orElse(null) != own && current.getModel(session.attachment.modelId()).orElse(null) == own) {
                if (current.getModels().values().stream().allMatch(model -> model == own)) {
                    // The official API defers destruction to the ME updater, just as /meg undisguise.
                    ModelEngineAPI.removeModeledEntity(session.attachment.playerId());
                } else throw new IllegalStateException("解除本插件模型被其他插件取消；其他模型已保留");
            } else if (!own.isDestroyed()) own.destroy();
        } else if (!own.isDestroyed() && (own.getModeledEntity() == null || own.getModeledEntity() == session.entity)) {
            // AddModelEvent may cancel before ME binds the new model to its entity.
            if (own.getModeledEntity() == null) own.setModeledEntity(session.entity);
            own.destroy();
        }
        if (session.displaced != null && sameEntity && !current.isDestroyed()
                && current.getModel(session.attachment.modelId()).isEmpty()) {
            current.addModel(session.displaced, false);
            if (current.getModel(session.attachment.modelId()).orElse(null) != session.displaced) {
                throw new IllegalStateException("创建失败后无法恢复被替换的原模型");
            }
        }
        boolean noForeignModel = sameEntity
                && current.getModels().values().stream().allMatch(model -> model == own);
        // Shared player state is retained if another plugin attached a replacement/model.
        if (noForeignModel) {
            if (session.changedBaseVisible && current.isBaseEntityVisible() == session.assignedBaseVisible) {
                current.setBaseEntityVisible(session.previousBaseVisible);
            }
            if (session.changedPlayerMode && current.getBase().getBodyRotationController().isPlayerMode()) {
                current.getBase().getBodyRotationController().setPlayerMode(session.previousPlayerMode);
            }
        }
        if (session.changedForcedInvisible && (noForeignModel || current == null)
                && ModelEngineAPI.getEntityHandler().isForcedInvisible(session.attachment.playerId())) {
            if (session.player.isOnline()) {
                ModelEngineAPI.getEntityHandler().setForcedInvisible(session.player, session.previousForcedInvisible);
            } else ModelEngineAPI.getEntityHandler().clearForcedInvisible(session.attachment.playerId());
        }
        if (sameEntity && !current.isDestroyed() && current.getModels().isEmpty()) {
            ModelEngineAPI.removeModeledEntity(session.attachment.playerId());
        }
        if (failure != null) throw failure;
    }

    private Session requireSession(Attachment attachment) {
        if (!isAttached(attachment)) throw new IllegalStateException("模型连接已失效，请重新伪装或接管");
        return sessions.get(attachment.playerId());
    }

    private void requireUnusedPlayer(UUID id) {
        Session existing = sessions.get(id);
        if (existing != null && !existing.released) {
            throw new IllegalStateException("玩家已有动作会话，请先结束当前会话");
        }
    }

    private static IStateMachineHandler requireStateMachine(ActiveModel model) {
        if (!(model.getAnimationHandler() instanceof IStateMachineHandler handler)) {
            throw new IllegalStateException("该伪装未使用 state_machine，请用 /meplayeractions disguise 重新伪装，或开启 ME 的 Model-Engine.Use-State-Machine 后重新伪装");
        }
        return handler;
    }

    private static IAnimationProperty priorityProperty(IStateMachineHandler handler, int priority) {
        synchronized (layerLock(handler)) {
            String prefix = priority + ":";
            for (Map.Entry<String, IAnimationProperty> entry : handler.getAnimations().entrySet()) {
                if (entry.getKey().startsWith(prefix)) return entry.getValue();
            }
            return null;
        }
    }

    private static Object layerLock(IStateMachineHandler handler) {
        // R4.1.1 prepare/play use this same map monitor. Its getAnimations() does not.
        return handler instanceof StateMachineHandler stateMachine ? stateMachine.getStateMachines() : handler;
    }

    private static void finishImmediately(SimpleProperty property) {
        // Hybrid getAnimation(name) excludes LERPOUT. Finishing our own property also
        // handles that phase, without a blanket stop or touching another property.
        property.stop();
        property.setLerpOut(0);
        property.setPhase(IAnimationProperty.Phase.LERPOUT);
        property.update();
    }

    private static RuntimeException accumulate(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static String requireId(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "名称不能为空");
        return value.trim();
    }

    private static void requirePlayer(Player player) {
        if (player == null || !player.isOnline()) throw new IllegalArgumentException("玩家不在线");
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("模型操作必须在 Paper 主线程执行");
    }

    private static final class Session {
        final Attachment attachment;
        final Player player;
        final ModeledEntity entity;
        final boolean previousBaseVisible;
        final boolean previousPlayerMode;
        final boolean previousForcedInvisible;
        final Map<Integer, OwnedAnimation> layers = new HashMap<>();
        final List<OwnedAnimation> ownedAnimations = new ArrayList<>();
        final LegacyNpcAnimations.Result compatibility;
        boolean assignedBaseVisible;
        boolean changedBaseVisible;
        boolean changedPlayerMode;
        boolean changedForcedInvisible;
        boolean released;
        ModelAudience audience;
        ActiveModel displaced;

        Session(Attachment attachment, Player player, ModeledEntity entity) {
            this.attachment = attachment;
            this.player = player;
            this.entity = entity;
            compatibility = LegacyNpcAnimations.create(attachment.activeModel().getBlueprint());
            previousBaseVisible = entity.isBaseEntityVisible();
            previousPlayerMode = entity.getBase().getBodyRotationController().isPlayerMode();
            previousForcedInvisible = ModelEngineAPI.getEntityHandler().isForcedInvisible(player);
        }
    }

    public static final class OwnedAnimation {
        private final ModelEngineBridge bridge;
        private final Session session;
        private final IStateMachineHandler handler;
        private final int priority;
        private final String animation;
        private final SimpleProperty property;
        private final int inTicks;
        private final int outTicks;
        private final double speed;
        private final BlueprintAnimation.LoopMode loop;

        private OwnedAnimation(ModelEngineBridge bridge, Session session, IStateMachineHandler handler,
                               int priority, String animation, SimpleProperty property,
                               int inTicks, int outTicks, double speed, BlueprintAnimation.LoopMode loop) {
            this.bridge = bridge;
            this.session = session;
            this.handler = handler;
            this.priority = priority;
            this.animation = animation;
            this.property = property;
            this.inTicks = inTicks;
            this.outTicks = outTicks;
            this.speed = speed;
            this.loop = loop;
        }

        public void stop(boolean immediate) {
            requireMainThread();
            synchronized (layerLock(handler)) {
                IAnimationProperty effective = priorityProperty(handler, priority);
                if (effective == property && handler.getAnimation(priority, animation) == property) {
                    if (immediate) handler.forceStopAnimation(priority, animation);
                    else handler.stopAnimation(priority, animation);
                }
                // Keep immediate escalation available after an earlier graceful stop.
                property.stop();
                if (immediate || (effective == property && priorityProperty(handler, priority) != property)) {
                    finishImmediately(property);
                }
            }
        }

        public boolean isFinished() {
            requireMainThread();
            synchronized (layerLock(handler)) {
                return property.isEnded() || session.released || !bridge.isAttached(session.attachment)
                        || priorityProperty(handler, priority) != property;
            }
        }

        public String animation() { return animation; }
        public double speed() { return speed; }
        public BlueprintAnimation.LoopMode loop() { return loop; }
        public String status() {
            requireMainThread();
            synchronized (layerLock(handler)) {
                return property.getPhase() + "；已结束 " + property.isEnded()
                        + "；此层持有 " + (priorityProperty(handler, priority) == property);
            }
        }
        public int inTicks() { return inTicks; }
        public int outTicks() { return outTicks; }
    }
}
