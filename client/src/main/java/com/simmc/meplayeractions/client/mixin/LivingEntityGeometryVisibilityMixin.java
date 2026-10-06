package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.HiddenPlayerRenderState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replace player geometry only; retain vanilla labels, fire and shadow submission. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityGeometryVisibilityMixin {
    @Inject(method = "getRenderLayer", at = @At("HEAD"), cancellable = true)
    private void meplayeractions$hidePlayerGeometry(LivingEntityRenderState state, boolean visible,
            boolean translucent, boolean outline, CallbackInfoReturnable<RenderLayer> cir) {
        if (hiddenPlayer(state)) cir.setReturnValue(null);
    }

    @Inject(method = "shouldRenderFeatures", at = @At("HEAD"), cancellable = true)
    private void meplayeractions$hideReplacedFeatures(LivingEntityRenderState state, CallbackInfoReturnable<Boolean> cir) {
        if (hiddenPlayer(state)) cir.setReturnValue(false);
    }

    @Unique private static boolean hiddenPlayer(LivingEntityRenderState state) {
        return state instanceof PlayerEntityRenderState && ((HiddenPlayerRenderState) state).meplayeractions$isHidden();
    }
}
