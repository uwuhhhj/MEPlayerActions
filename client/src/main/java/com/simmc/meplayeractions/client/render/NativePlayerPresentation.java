package com.simmc.meplayeractions.client.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The vanilla player's presentation for this extracted frame, before its geometry is replaced. */
public final class NativePlayerPresentation {
    private static final Map<UUID, Snapshot> FRAME = new HashMap<>();
    private static final Set<UUID> CAPTURED = new HashSet<>();

    private NativePlayerPresentation() { }

    /** Consume the complete vanilla frame instead of reconstructing its positions from entity tick fields. */
    public static void beginFrame(List<EntityRenderState> states) {
        FRAME.clear();
        CAPTURED.clear();
        if (states.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        for (EntityRenderState raw : states) {
            if (!(raw instanceof PlayerEntityRenderState state) || !(raw instanceof ComponentRenderState identity)) continue;
            UUID owner = identity.meplayeractions$entityUuid();
            if (owner == null) continue;
            PlayerEntity entity = client.world.getPlayerByUuid(owner);
            if (entity == null) continue;
            EntityRenderer<Entity, EntityRenderState> renderer = renderer(client, entity);
            store(owner, state, identity.meplayeractions$tickDelta(), renderer.getPositionOffset(state),
                    YsmBodyTransform.extract(entity, state), true);
        }
    }

    /** A remote body must belong to this vanilla world frame, including its native visibility decision. */
    public static boolean captured(UUID owner) { return CAPTURED.contains(owner); }

    public static Snapshot get(UUID owner) { return FRAME.get(owner); }

    /** First-person players have no world body state; their arm/scripts still need one native snapshot. */
    public static Snapshot frame(PlayerEntity entity) {
        if (entity == null) return null;
        Snapshot captured = FRAME.get(entity.getUuid());
        if (captured != null) return captured;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        float delta = client.getRenderTickCounter().getTickProgress(!client.world.getTickManager().shouldSkipTick(entity));
        EntityRenderer<Entity, EntityRenderState> renderer = renderer(client, entity);
        EntityRenderState fresh = renderer.createRenderState();
        renderer.updateRenderState(entity, fresh, delta);
        if (!(fresh instanceof PlayerEntityRenderState state)) return null;
        return store(entity.getUuid(), state, delta, renderer.getPositionOffset(state),
                YsmBodyTransform.extract(entity, state), false);
    }

    @SuppressWarnings("unchecked")
    private static EntityRenderer<Entity, EntityRenderState> renderer(MinecraftClient client, PlayerEntity entity) {
        return (EntityRenderer<Entity, EntityRenderState>) (Object) client.getEntityRenderDispatcher().getRenderer(entity);
    }

    /** Freeze values now: vanilla render-state and matrix objects remain mutable after extraction. */
    static Snapshot store(UUID owner, PlayerEntityRenderState state, float delta, Vec3d offset,
                          Matrix4f parent, boolean worldFrame) {
        Vec3d shift = offset == null ? Vec3d.ZERO : offset;
        // Vanilla reverses these presentation angles for Dinnerbone/Grumm. Author head queries
        // still use the entity angles; the native parent matrix already flips the whole body.
        float headSign = state.flipUpsideDown ? -1 : 1;
        Snapshot snapshot = new Snapshot(state.x + shift.x, state.y + shift.y, state.z + shift.z,
                state.x, state.y, state.z, delta, state.bodyYaw,
                state.bodyYaw + state.relativeHeadYaw * headSign, state.pitch * headSign, state.pose, parent);
        FRAME.put(owner, snapshot);
        if (worldFrame) CAPTURED.add(owner);
        return snapshot;
    }

    public record Snapshot(double x, double y, double z, double nativeX, double nativeY, double nativeZ,
                           float tickDelta, float bodyYaw,
                           float headYaw, float headPitch, EntityPose pose, Matrix4f parent) {
        public Snapshot { parent = new Matrix4f(parent); }
        @Override public Matrix4f parent() { return new Matrix4f(parent); }
        /** Render-only offsets must never become synthetic motion for author physics and queries. */
        public Vec3d entityPosition() { return new Vec3d(nativeX, nativeY, nativeZ); }
    }
}
