package com.simmc.meplayeractions.client.mixin;

import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.model.special.SpecialModelRenderer;
import net.minecraft.client.render.model.json.Transformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The transform and special renderer used by this native render layer. */
@Mixin(ItemRenderState.LayerRenderState.class)
public interface ItemLayerRenderStateAccessor {
    @Accessor("transform") Transformation meplayeractions$getTransform();
    @Accessor("specialModelType") SpecialModelRenderer<?> meplayeractions$getSpecialModel();
}
