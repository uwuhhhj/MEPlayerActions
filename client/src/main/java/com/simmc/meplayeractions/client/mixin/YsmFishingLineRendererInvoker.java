package com.simmc.meplayeractions.client.mixin;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuse the native 1.21.11 fishing-line geometry after replacing only the bobber model. */
@Mixin(FishingBobberEntityRenderer.class)
public interface YsmFishingLineRendererInvoker {
    @Invoker("renderFishingLine")
    static void meplayeractions$renderFishingLine(float x, float y, float z, VertexConsumer vertices,
                                                   MatrixStack.Entry entry, float start, float end, float width) {
        throw new AssertionError("Mixin invoker not applied");
    }
}
