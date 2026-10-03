package com.simmc.meplayeractions.client.ui;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.SpecialGuiElementRenderer;
import net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Native GUI picture-in-picture rendering; Minecraft owns its RGBA8/DEPTH32 targets and their lifetime. */
public final class NativeGuiRenderBackend extends SpecialGuiElementRenderer<NativeGuiRenderBackend.State> {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
                    .withLocation(Identifier.of("meplayeractions", "pipeline/gui_model_native_depth"))
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(true).withCull(false).withBlend(BlendFunction.TRANSLUCENT).build());
    private static boolean registered;

    private NativeGuiRenderBackend(VertexConsumerProvider.Immediate consumers) { super(consumers); }

    /** Called during client initialization, before Fabric freezes its special GUI renderer factories. */
    public static void register() {
        if (registered) return;
        SpecialGuiElementRegistry.register(context -> new NativeGuiRenderBackend(context.vertexConsumers()));
        registered = true;
    }

    /** Per-submission evidence is filled only by a real offscreen render, never by GUI preparation. */
    static final class Evidence {
        private long renderCount, emittedVertices;
        private String colorFormat = "", depthFormat = "";
        private int targetWidth, targetHeight;
        private boolean colorAttachment, depthAttachment;

        Map<String, Object> diagnostics() {
            return Map.of("renderCount", renderCount, "emittedVertices", emittedVertices,
                    "colorAttachment", colorAttachment, "depthAttachment", depthAttachment,
                    "colorFormat", colorFormat, "depthFormat", depthFormat,
                    "targetWidth", targetWidth, "targetHeight", targetHeight);
        }
    }

    record State(int x1, int y1, int x2, int y2, Matrix3x2f pose, ScreenRect scissorArea,
                 ScreenRect bounds, List<PreviewMesh.Quad> quads, Map<Integer, PreviewAtlasUv> rects,
                 Identifier texture, GpuSampler sampler, float pixelsPerBlock,
                 AtomicLong emittedVertices, Evidence evidence) implements SpecialGuiElementRenderState {
        @Override public float scale() { return 1; }
    }

    @Override public Class<State> getElementClass() { return State.class; }
    @Override protected String getName() { return "MEPlayerActions native GUI model"; }

    /** The existing camera already expresses coordinates relative to a viewport center. */
    @Override protected float getYOffset(int height, int windowScale) { return height * .5f; }

    @Override protected void render(State state, MatrixStack matrices) {
        var color = RenderSystem.outputColorTextureOverride;
        var depth = RenderSystem.outputDepthTextureOverride;
        if (color == null || depth == null) throw new IllegalStateException("Native GUI color/depth attachments missing");
        Evidence evidence = state.evidence();
        evidence.colorAttachment = !color.isClosed(); evidence.depthAttachment = !depth.isClosed();
        evidence.colorFormat = color.texture().getFormat().name();
        evidence.depthFormat = depth.texture().getFormat().name();
        evidence.targetWidth = depth.getWidth(0); evidence.targetHeight = depth.getHeight(0);
        RenderLayer layer = RenderLayer.of("meplayeractions_gui_model_native_depth", RenderSetup.builder(PIPELINE)
                .texture("Sampler0", state.texture(), state::sampler).build());
        VertexConsumer vertices = vertexConsumers.getBuffer(layer);
        float centerX = (state.x1() + state.x2()) * .5f, centerY = (state.y1() + state.y2()) * .5f;
        for (PreviewMesh.Quad quad : state.quads()) {
            PreviewAtlasUv rect = state.rects().get(quad.texture());
            if (rect == null) throw new IllegalArgumentException("Missing native GUI texture rectangle");
            for (PreviewMesh.Point point : quad.points()) {
                // SpecialGuiElementRenderer supplies S(scale, scale, -scale). Smaller source
                // camera Z is therefore nearer in its native orthographic depth attachment.
                vertices.vertex(matrices.peek(), point.x() - centerX, point.y() - centerY,
                                point.depth() * state.pixelsPerBlock())
                        .texture(rect.u(point.u()), rect.v(point.v())).color(quad.color());
            }
        }
        // Draw while Minecraft's offscreen target overrides are installed. The base class
        // performs its normal final flush/composite and Fabric manages additional card instances.
        vertexConsumers.draw(layer);
        long count = (long) state.quads().size() * 4;
        state.emittedVertices().addAndGet(count);
        evidence.emittedVertices += count; evidence.renderCount++;
    }

    static Map<String, Object> pipelineDiagnostics() {
        return Map.of("backend", "minecraft-special-gui-offscreen", "pipeline", PIPELINE.getLocation().toString(),
                "shader", PIPELINE.getVertexShader().toString(), "fragmentShader", PIPELINE.getFragmentShader().toString(),
                "depthTest", PIPELINE.getDepthTestFunction().name(), "depthWrite", PIPELINE.isWriteDepth(),
                "perVertexDepth", true);
    }
}
