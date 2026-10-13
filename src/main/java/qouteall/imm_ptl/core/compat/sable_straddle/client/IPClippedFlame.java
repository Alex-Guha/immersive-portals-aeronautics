/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Create Aeronautics' hot air burner flame, cut at clip planes ({@link IPStraddleRender#immediateClipPlanes}). The
 * burner draws its flame at once, with its own Veil shader (already bound when this runs): one 2x2 quad in the flame's
 * pose, textured 0..1, depth-tested, not culled. This draws the same quad through {@link IPPlaneClippingVertexConsumer}s.
 */
public final class IPClippedFlame {
    private static final float SIZE = 2.0f;

    private IPClippedFlame() {
    }

    public static void draw(final PoseStack poseStack, final List<Vector3f[]> planes) {
        final BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        final List<IPPlaneClippingVertexConsumer> clippers = new ArrayList<>(planes.size());
        VertexConsumer out = builder;
        for (final Vector3f[] plane : planes) {
            final IPPlaneClippingVertexConsumer clipper = new IPPlaneClippingVertexConsumer(out, plane[0], plane[1]);
            clippers.add(clipper);
            out = clipper;
        }
        final Matrix4f pose = poseStack.last().pose();
        out.addVertex(pose, 0.0f, 0.0f, 0.0f).setUv(0.0f, 1.0f);
        out.addVertex(pose, SIZE, 0.0f, 0.0f).setUv(1.0f, 1.0f);
        out.addVertex(pose, SIZE, SIZE, 0.0f).setUv(1.0f, 0.0f);
        out.addVertex(pose, 0.0f, SIZE, 0.0f).setUv(0.0f, 0.0f);
        // the outermost clipper feeds the next one in
        for (int i = clippers.size() - 1; i >= 0; i--) {
            clippers.get(i).flush();
        }
        final MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        BufferUploader.drawWithShader(mesh);
        RenderSystem.disableDepthTest();
        RenderSystem.enableCull();
    }
}
