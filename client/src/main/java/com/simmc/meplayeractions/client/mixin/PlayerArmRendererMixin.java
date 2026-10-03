package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.ModelRenderer;
import com.simmc.meplayeractions.client.render.YsmEquipmentRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerArmRendererMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void meplayeractions$equipmentContext(EntityRendererFactory.Context context, boolean slim, CallbackInfo ci) {
        YsmEquipmentRenderer.rendererContext(context);
    }
    @Inject(method = "renderRightArm", at = @At("HEAD"), cancellable = true)
    private void meplayeractions$rightArm(MatrixStack matrices, OrderedRenderCommandQueue queue,
            int light, Identifier texture, boolean sleeveVisible, CallbackInfo ci) {
        if (ModelRenderer.renderFirstPersonArm(Arm.RIGHT, matrices, queue, light)) ci.cancel();
    }
    @Inject(method = "renderLeftArm", at = @At("HEAD"), cancellable = true)
    private void meplayeractions$leftArm(MatrixStack matrices, OrderedRenderCommandQueue queue,
            int light, Identifier texture, boolean sleeveVisible, CallbackInfo ci) {
        if (ModelRenderer.renderFirstPersonArm(Arm.LEFT, matrices, queue, light)) ci.cancel();
    }
}
