package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.HiddenPlayerRenderState;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The player feature override bypasses LivingEntityRenderer's feature gate. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerGeometryVisibilityMixin {
    @Inject(method = "shouldRenderFeatures(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)Z",
            at = @At("HEAD"), cancellable = true)
    private void meplayeractions$hideReplacedFeatures(PlayerEntityRenderState state, CallbackInfoReturnable<Boolean> cir) {
        if (((HiddenPlayerRenderState) state).meplayeractions$isHidden()) cir.setReturnValue(false);
    }
}
