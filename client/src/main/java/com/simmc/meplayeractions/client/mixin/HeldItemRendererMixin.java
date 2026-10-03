package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.YsmComponentRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Add authored arms around native items; vanilla retains its complete use/map/equip/item command path. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Shadow private void renderArmHoldingItem(MatrixStack matrices, OrderedRenderCommandQueue queue,
                                              int light, float equipProgress, float swingProgress, Arm arm) {
        throw new AssertionError("Mixin shadow");
    }

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
    private void meplayeractions$armWithNativeItem(AbstractClientPlayerEntity player, float delta, float pitch,
            Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
            OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        if (player.isUsingSpyglass() || item.contains(DataComponentTypes.MAP_ID)) return;
        Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        if (!YsmComponentRenderer.hasArm(arm)) return;
        // Empty visible hands already enter renderArmHoldingItem; maps render their own two-hand poses.
        if (!item.isEmpty() || hand == Hand.MAIN_HAND && player.isInvisible()) {
            matrices.push();
            try { renderArmHoldingItem(matrices, queue, light, equipProgress, swingProgress, arm); }
            finally { matrices.pop(); }
        }
    }
}
