package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.NativeRenderPhases;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.LayeredCustomCommandRenderer;
import net.minecraft.client.render.command.RenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Flush only the two YSM phases so buffered render layers cannot reverse the author's order. */
@Mixin(RenderDispatcher.class)
public abstract class YsmRenderPhaseFlushMixin {
    @Shadow @Final private VertexConsumerProvider.Immediate vertexConsumers;
    @Shadow @Final private OutlineVertexConsumerProvider outlineVertexConsumers;

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/command/LayeredCustomCommandRenderer;render(Lnet/minecraft/client/render/command/BatchingRenderCommandQueue;)V"))
    private void meplayeractions$flushAuthorPhase(LayeredCustomCommandRenderer renderer, BatchingRenderCommandQueue queue) {
        renderer.render(queue);
        if (NativeRenderPhases.requiresFlush(queue)) {
            vertexConsumers.draw();
            outlineVertexConsumers.draw();
        }
    }
}
