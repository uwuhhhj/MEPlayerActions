package com.simmc.meplayeractions.client.ui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Yarn 1.21.11 port of OpenYSM RadialSliceRenderState.java at 0306e1f (MIT).
 * A slice is the original four-vertex polygon, not a circle texture or scan-line approximation.
 * See THIRD_PARTY_NOTICES.md for the upstream license and source attribution.
 */
public record RadialSliceRenderState(Matrix3x2f pose, List<WheelSelection.Point> vertices, int color,
                                    ScreenRect scissorArea, ScreenRect bounds, AtomicLong emittedVertices)
        implements SimpleGuiElementRenderState {
    public static RadialSliceRenderState of(Matrix3x2fc currentPose, List<WheelSelection.Point> vertices,
                                           int color, ScreenRect scissor, AtomicLong emittedVertices) {
        if (vertices.size() != 4) throw new IllegalArgumentException("A roulette slice has four vertices");
        Matrix3x2f pose = new Matrix3x2f(currentPose);
        return new RadialSliceRenderState(pose, List.copyOf(vertices), color, scissor,
                computeBounds(pose, vertices, scissor), emittedVertices);
    }
    @Override public void setupVertices(VertexConsumer consumer) {
        for (WheelSelection.Point point : vertices) consumer.vertex(pose, point.x(), point.y()).color(color);
        emittedVertices.addAndGet(4);
    }
    @Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
    @Override public TextureSetup textureSetup() { return TextureSetup.empty(); }

    private static ScreenRect computeBounds(Matrix3x2fc pose, List<WheelSelection.Point> points, ScreenRect scissor) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (WheelSelection.Point point : points) {
            Vector2f transformed = pose.transformPosition(point.x(), point.y(), new Vector2f());
            minX = Math.min(minX, transformed.x); minY = Math.min(minY, transformed.y);
            maxX = Math.max(maxX, transformed.x); maxY = Math.max(maxY, transformed.y);
        }
        int left = (int) Math.floor(minX), top = (int) Math.floor(minY);
        ScreenRect bounds = new ScreenRect(left, top, (int) Math.ceil(maxX) - left, (int) Math.ceil(maxY) - top);
        return scissor == null ? bounds : bounds.intersection(scissor);
    }
}
