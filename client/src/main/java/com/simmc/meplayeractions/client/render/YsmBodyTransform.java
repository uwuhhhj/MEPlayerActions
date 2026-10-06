package com.simmc.meplayeractions.client.render;

import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/** OpenYSM GeoReplacedEntityRenderer's native LivingEntityRenderer parent, independent of author animation. */
final class YsmBodyTransform {
    private static NativeParent nativeParent;
    private YsmBodyTransform() { }

    static void rendererContext(EntityRendererFactory.Context context) {
        nativeParent = new NativeParent(context);
        NativeHumanoidPoseSampler.rendererContext(context);
    }

    static Matrix4f extract(PlayerEntity entity, PlayerEntityRenderState presentation) {
        float bodyYaw = presentation.bodyYaw;
        if (nativeParent == null)
            return new Matrix4f().rotateY((float) Math.toRadians(180 - bodyYaw));
        PlayerEntityRenderState state = copyForModel(presentation);
        if (entity != null && entity.isClimbing()) {
            var position = entity.getClimbingPos();
            if (position.isPresent()) {
                var block = entity.getEntityWorld().getBlockState(position.get());
                if (block.contains(Properties.HORIZONTAL_FACING))
                    bodyYaw = block.get(Properties.HORIZONTAL_FACING).getOpposite().getHorizontalQuarterTurns() * 90;
            }
        }
        MatrixStack matrices = new MatrixStack();
        if (state.isInPose(EntityPose.SLEEPING) && state.sleepingDirection != null) {
            var direction = state.sleepingDirection;
            float eye = state.standingEyeHeight - .1f;
            matrices.translate(-direction.getOffsetX() * eye, 0, -direction.getOffsetZ() * eye);
        }
        nativeParent.apply(state, matrices, bodyYaw);
        return new Matrix4f(matrices.peek().getPositionMatrix());
    }

    /** Copy the fields read by base setupTransforms; never alter vanilla's actual submitted state. */
    static PlayerEntityRenderState copyForModel(PlayerEntityRenderState source) {
        PlayerEntityRenderState state = new PlayerEntityRenderState();
        copyForModel(source, state);
        return state;
    }

    static void copyForModel(PlayerEntityRenderState source, PlayerEntityRenderState state) {
        state.age = source.age;
        state.height = source.height;
        state.standingEyeHeight = source.standingEyeHeight;
        state.bodyYaw = source.bodyYaw;
        state.relativeHeadYaw = source.relativeHeadYaw;
        state.pitch = source.pitch;
        state.shaking = source.shaking;
        state.flipUpsideDown = source.flipUpsideDown;
        state.pose = source.pose;
        state.sleepingDirection = source.sleepingDirection;
        // OpenYSM leaves death and spin to author controllers instead of the vanilla rotations.
        state.deathTime = 0;
        state.usingRiptide = false;
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
