package com.simmc.meplayeractions.client.render;

import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.command.RenderCommandQueue;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Source render_layers_first, adapted to Minecraft's deferred render command groups. */
public final class NativeRenderPhases {
    // Vanilla equipment layers use nonnegative, incrementing batch indices. Give each
    // semantic phase its own range so armor texture/glint/trim ordering stays native.
    private static final int PHASE_SIZE = 1 << 29;
    private static final int FIRST = 1 << 30;
    private static final int SECOND = FIRST + PHASE_SIZE;
    private static final Map<OrderedRenderCommandQueue, PhasePair> PHASES = new IdentityHashMap<>();
    private static final Set<RenderCommandQueue> FLUSH = Collections.newSetFromMap(new IdentityHashMap<>());
    private NativeRenderPhases() { }

    public static void beginFrame() { PHASES.clear(); FLUSH.clear(); }
    public static OrderedRenderCommandQueue geometry(OrderedRenderCommandQueue queue, boolean layersFirst) {
        return phase(queue, layersFirst);
    }
    public static OrderedRenderCommandQueue attachments(OrderedRenderCommandQueue queue, boolean layersFirst) {
        return phase(queue, !layersFirst);
    }
    private static OrderedRenderCommandQueue phase(OrderedRenderCommandQueue queue, boolean second) {
        PhasePair pair = PHASES.computeIfAbsent(queue,
                parent -> new PhasePair(new PhaseQueue(parent, FIRST), new PhaseQueue(parent, SECOND)));
        return second ? pair.second() : pair.first();
    }
    public static boolean requiresFlush(RenderCommandQueue queue) { return FLUSH.contains(queue); }

    private record PhasePair(PhaseQueue first, PhaseQueue second) { }

    /** Reuse native submit forwarding, while relocating its ordered child batches. */
    private static final class PhaseQueue extends OrderedRenderCommandQueueImpl {
        private final OrderedRenderCommandQueue parent;
        private final int base;
        private int lastOrder = -1;
        private BatchingRenderCommandQueue lastQueue;

        private PhaseQueue(OrderedRenderCommandQueue parent, int base) {
            this.parent = parent;
            this.base = base;
        }

        @Override public BatchingRenderCommandQueue getBatchingQueue(int order) {
            if (order < 0 || order >= PHASE_SIZE)
                throw new IllegalArgumentException("Native equipment batch index outside its phase: " + order);
            BatchingRenderCommandQueue batching = (BatchingRenderCommandQueue) parent.getBatchingQueue(base + order);
            if (order > lastOrder) {
                FLUSH.remove(lastQueue);
                lastOrder = order;
                lastQueue = batching;
                FLUSH.add(batching);
            }
            return batching;
        }
    }
}
