package com.simmc.meplayeractions.client.mixin;

import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the actual resolved layers; no native inventory reference is retained. */
@Mixin(ItemRenderState.class)
public interface ItemRenderStateAccessor {
    @Accessor("layerCount") int meplayeractions$getLayerCount();
    @Accessor("layers") ItemRenderState.LayerRenderState[] meplayeractions$getLayers();
    @Accessor("displayContext") ItemDisplayContext meplayeractions$getDisplayContext();
}
