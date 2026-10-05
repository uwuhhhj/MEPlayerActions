package com.simmc.meplayeractions.client.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/** OpenYSM GeoReplacedEntityRenderer's native LivingEntityRenderer parent, independent of author animation. */
final class YsmBodyTransform {
    private static NativeParent nativeParent;
    private YsmBodyTransform() { }

    static void rendererContext(EntityRendererFactory.Context context) { nativeParent = new NativeParent(context); }

    @SuppressWarnings("unchecked")
    static Matrix4f extract(PlayerEntity entity, float bodyYaw, float delta) {
        if (entity == null || nativeParent == null)
            return new Matrix4f().rotateY((float) Math.toRadians(180 - bodyYaw));
        EntityRenderer<Entity, EntityRenderState> renderer = (EntityRenderer<Entity, EntityRenderState>) (Object)
                MinecraftClient.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState fresh = renderer.createRenderState();
        renderer.updateRenderState(entity, fresh, delta);
        if (!(fresh instanceof PlayerEntityRenderState state))
            return new Matrix4f().rotateY((float) Math.toRadians(180 - bodyYaw));
        // The source explicitly disables vanilla death and spin rotations because author controllers own them.
        // Modify only this fresh state, never the actual player or vanilla's already submitted render state.
        state.deathTime = 0;
        state.usingRiptide = false;
        if (entity.isClimbing()) {
            var position = entity.getClimbingPos();
            if (position.isPresent()) {
                var block = entity.getEntityWorld().getBlockState(position.get());
                if (block.contains(Properties.HORIZONTAL_FACING))
                    bodyYaw = block.get(Properties.HORIZONTAL_FACING).getOpposite().getHorizontalQuarterTurns() * 90;
            }
        }
        MatrixStack matrices = new MatrixStack();
        if (entity.isSleeping() && entity.getSleepingDirection() != null) {
            var direction = entity.getSleepingDirection();
            float eye = entity.getEyeHeight(EntityPose.STANDING) - .1f;
            matrices.translate(-direction.getOffsetX() * eye, 0, -direction.getOffsetZ() * eye);
        }
        nativeParent.apply(state, matrices, bodyYaw);
        return new Matrix4f(matrices.peek().getPositionMatrix());
    }

    /** Call the base LivingEntityRenderer method, as OpenYSM does; the native PlayerRenderer adds different swim/glide rules. */
    private static final class NativeParent extends LivingEntityRenderer<PlayerEntity, PlayerEntityRenderState, PlayerEntityModel> {
        NativeParent(EntityRendererFactory.Context context) {
            super(context, new PlayerEntityModel(context.getPart(EntityModelLayers.PLAYER), false), .5f);
        }
        void apply(PlayerEntityRenderState state, MatrixStack matrices, float bodyYaw) {
            super.setupTransforms(state, matrices, bodyYaw, 1);
        }
        @Override public PlayerEntityRenderState createRenderState() { return new PlayerEntityRenderState(); }
        @Override public Identifier getTexture(PlayerEntityRenderState state) { return state.skinTextures.body().texturePath(); }
    }
}
