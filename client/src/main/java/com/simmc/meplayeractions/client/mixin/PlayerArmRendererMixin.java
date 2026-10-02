package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.ModelRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerArmRendererMixin {
    @Inject(method = {"renderRightArm", "renderLeftArm"}, at = @At("HEAD"), cancellable = true)
    private void meplayeractions$hideVanillaArm(MatrixStack matrices, OrderedRenderCommandQueue queue,
            int light, Identifier texture, boolean sleeveVisible, CallbackInfo ci) {
        if (ModelRenderer.shouldHideFirstPersonArm()) ci.cancel();
    }
}
