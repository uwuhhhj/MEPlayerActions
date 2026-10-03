package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.HiddenPlayerRenderState;
import com.simmc.meplayeractions.client.render.ComponentRenderState;
import com.simmc.meplayeractions.client.render.ModelRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/entity/state/EntityRenderState;F)V",
            at = @At("TAIL"))
    private void meplayeractions$captureHidden(Entity entity, EntityRenderState state, float tickDelta, CallbackInfo ci) {
        ((ComponentRenderState) state).meplayeractions$entityUuid(entity.getUuid());
        ((HiddenPlayerRenderState) state).meplayeractions$setHidden(entity instanceof PlayerEntity
                && state instanceof PlayerEntityRenderState && ModelRenderer.shouldHidePlayer(entity.getUuid()));
    }
}
