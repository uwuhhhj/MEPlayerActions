package com.simmc.meplayeractions.client.ui;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DiffuseLighting;
import com.simmc.meplayeractions.client.render.YsmItemRenderer;
import com.simmc.meplayeractions.client.render.YsmEquipmentRenderer;
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
import org.joml.Matrix4f;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
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
        private long renderCount, emittedVertices, submittedItems, submittedEquipment;
        private String colorFormat = "", depthFormat = "";
        private int targetWidth, targetHeight;
        private boolean colorAttachment, depthAttachment;

        Map<String, Object> diagnostics() {
            return Map.of("renderCount", renderCount, "emittedVertices", emittedVertices,
                    "colorAttachment", colorAttachment, "depthAttachment", depthAttachment,
                    "colorFormat", colorFormat, "depthFormat", depthFormat,
                    "targetWidth", targetWidth, "targetHeight", targetHeight,
                    "submittedItems", submittedItems, "submittedEquipment", submittedEquipment);
        }
    }

    /** Frozen author-pose locators and native item/equipment states for an OWNER preview only. */
    record Attachments(Matrix4f parent, List<YsmItemRenderer.Attachment> items,
                       List<YsmEquipmentRenderer.Attachment> equipment) {
        static final Attachments EMPTY = new Attachments(new Matrix4f(), List.of(), List.of());
        Attachments { parent = new Matrix4f(parent); items = List.copyOf(items); equipment = List.copyOf(equipment); }
        @Override public Matrix4f parent() { return new Matrix4f(parent); }
        boolean isEmpty() { return items.isEmpty() && equipment.isEmpty(); }
    }

    record State(int x1, int y1, int x2, int y2, Matrix3x2f pose, ScreenRect scissorArea,
                 ScreenRect bounds, List<PreviewMesh.Quad> quads, Map<Integer, PreviewAtlasUv> rects,
                 Identifier texture, GpuSampler sampler, float pixelsPerBlock,
                 AtomicLong emittedVertices, Evidence evidence, Attachments attachments,
                 Map<Integer, Identifier> textureOverrides) implements SpecialGuiElementRenderState {
        State { textureOverrides = Map.copyOf(textureOverrides); }
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
        float centerX = (state.x1() + state.x2()) * .5f, centerY = (state.y1() + state.y2()) * .5f;
        Map<Identifier, List<PreviewMesh.Quad>> batches = new LinkedHashMap<>();
        for (PreviewMesh.Quad quad : state.quads()) {
            Identifier texture = state.textureOverrides().getOrDefault(quad.texture(), state.texture());
            batches.computeIfAbsent(texture, ignored -> new ArrayList<>()).add(quad);
        }
        for (var batch : batches.entrySet()) {
            RenderLayer layer = RenderLayer.of("meplayeractions_gui_model_native_depth", RenderSetup.builder(PIPELINE)
                    .texture("Sampler0", batch.getKey(), state::sampler).build());
            VertexConsumer vertices = vertexConsumers.getBuffer(layer);
            for (PreviewMesh.Quad quad : batch.getValue()) {
                PreviewAtlasUv rect = state.rects().get(quad.texture());
                if (rect == null) throw new IllegalArgumentException("Missing native GUI texture rectangle");
                boolean overridden = state.textureOverrides().containsKey(quad.texture());
                for (PreviewMesh.Point point : quad.points()) {
                    // SpecialGuiElementRenderer supplies S(scale, scale, -scale). Smaller source
                    // camera Z is therefore nearer in its native orthographic depth attachment.
                    vertices.vertex(matrices.peek(), point.x() - centerX, point.y() - centerY,
                                    point.depth() * state.pixelsPerBlock())
                            .texture(overridden ? point.u() : rect.u(point.u()), overridden ? point.v() : rect.v(point.v()))
                            .color(quad.color());
                }
            }
            vertexConsumers.draw(layer);
        }
        // Draw while Minecraft's offscreen target overrides are installed. The base class
        // performs its normal final flush/composite and Fabric manages additional card instances.
        if (!state.attachments().isEmpty()) {
            // The native EntityGuiElementRenderer submits/flushes this same dispatcher while the
            // offscreen target overrides are active. Do not reduce an item to a flat GUI icon.
            var client = MinecraftClient.getInstance();
            client.gameRenderer.getDiffuseLighting().setShaderLights(DiffuseLighting.Type.ENTITY_IN_UI);
            var dispatcher = client.gameRenderer.getEntityRenderDispatcher();
            matrices.push();
            try {
                matrices.multiplyPositionMatrix(state.attachments().parent());
                YsmItemRenderer.submit(state.attachments().items(), matrices, dispatcher.getQueue(), 0xF000F0);
                YsmEquipmentRenderer.submit(state.attachments().equipment(), matrices, dispatcher.getQueue(), 0xF000F0);
                dispatcher.render();
                evidence.submittedItems += state.attachments().items().size();
                evidence.submittedEquipment += state.attachments().equipment().size();
            } finally { matrices.pop(); }
        }
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
