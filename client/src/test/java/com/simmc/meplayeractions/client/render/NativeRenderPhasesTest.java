package com.simmc.meplayeractions.client.render;

import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRenderPhasesTest {
    @AfterEach void clearFrame() { NativeRenderPhases.beginFrame(); }

    @Test void authoredLayerOrderSurvivesNativeCommandCategoryBatching() {
        OrderedRenderCommandQueueImpl queue = new OrderedRenderCommandQueueImpl();
        OrderedRenderCommandQueue layers = NativeRenderPhases.attachments(queue, true);
        OrderedRenderCommandQueue body = NativeRenderPhases.geometry(queue, true);
        assertNotSame(body, layers);
        // Native OrderedRenderCommandQueueImpl submission must dispatch into the
        // adapter's parent, not its unused child map.
        layers.submitShadowPieces(new MatrixStack(), .5f, List.of());
        body.submitShadowPieces(new MatrixStack(), .5f, List.of());
        assertSame(layers.getBatchingQueue(0), queue.getBatchingQueues().get(queue.getBatchingQueues().firstIntKey()));
        assertSame(body.getBatchingQueue(0), queue.getBatchingQueues().get(queue.getBatchingQueues().lastIntKey()));
        assertSame(body, NativeRenderPhases.attachments(queue, false));
        assertSame(layers, NativeRenderPhases.geometry(queue, false));
    }

    @Test void nativePhaseFlushesAreBoundedAndDoNotFlushUnrelatedQueues() {
        OrderedRenderCommandQueueImpl queue = new OrderedRenderCommandQueueImpl();
        RenderCommandQueue nativeQueue = queue.getBatchingQueue(0);
        assertFalse(NativeRenderPhases.requiresFlush(nativeQueue));
        for (int player = 0; player < 1000; player++) {
            assertTrue(NativeRenderPhases.requiresFlush(NativeRenderPhases.geometry(queue, player % 2 == 0).getBatchingQueue(0)));
            assertTrue(NativeRenderPhases.requiresFlush(NativeRenderPhases.attachments(queue, player % 2 == 0).getBatchingQueue(0)));
        }
        assertEquals(3, queue.getBatchingQueues().size(), "All players share two author phases; this is not a per-player flush");
        assertFalse(NativeRenderPhases.requiresFlush(nativeQueue));
        RenderCommandQueue priorFrame = NativeRenderPhases.geometry(queue, true).getBatchingQueue(0);
        NativeRenderPhases.beginFrame();
        assertFalse(NativeRenderPhases.requiresFlush(priorFrame));
    }

    @Test void nativeEquipmentTextureGlintAndTrimBatchesStayInsideTheAuthoredPhase() {
        OrderedRenderCommandQueueImpl queue = new OrderedRenderCommandQueueImpl();
        OrderedRenderCommandQueue layers = NativeRenderPhases.attachments(queue, true);
        OrderedRenderCommandQueue body = NativeRenderPhases.geometry(queue, true);
        RenderCommandQueue texture = layers.getBatchingQueue(0);
        RenderCommandQueue glint = layers.getBatchingQueue(1);
        RenderCommandQueue trim = layers.getBatchingQueue(2);
        RenderCommandQueue geometry = body.getBatchingQueue(0);
        assertEquals(List.of(texture, glint, trim, geometry), List.copyOf(queue.getBatchingQueues().values()));
        assertFalse(NativeRenderPhases.requiresFlush(texture));
        assertFalse(NativeRenderPhases.requiresFlush(glint));
        assertTrue(NativeRenderPhases.requiresFlush(trim), "Flush once after the complete attachment phase");
        assertTrue(NativeRenderPhases.requiresFlush(geometry));
        assertSame(texture, layers.getBatchingQueue(0));
        assertFalse(NativeRenderPhases.requiresFlush(texture), "A later player's base layer must not move the final flush backward");
    }
}
