package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.ModelRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides only native equipment in the extracted state; body and hand items remain independent. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEquipmentVisibilityMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL"))
    private void meplayeractions$hideNativeEquipment(PlayerLikeEntity entity, PlayerEntityRenderState state,
            float tickDelta, CallbackInfo ci) {
        if (!ModelRenderer.shouldHideVanillaLayers(entity.getUuid())) return;
        // Minecraft's armor/elytra features consume these copied stacks, not the live inventory.
        state.equippedHeadStack = ItemStack.EMPTY;
        state.equippedChestStack = ItemStack.EMPTY;
        state.equippedLegsStack = ItemStack.EMPTY;
        state.equippedFeetStack = ItemStack.EMPTY;
        state.capeVisible = false;
        state.headItemRenderState.clear();
        state.wearingSkullType = null;
        state.wearingSkullProfile = null;
    }
}
