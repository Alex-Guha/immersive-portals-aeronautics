/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPPlaneClippingBufferSource;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

import java.util.function.Consumer;

/**
 * Catnip's outliner (Create's and Simulated's selection boxes, glue, schematics, ...) in portal passes. Ponder draws it
 * from NeoForge's {@code AFTER_PARTICLES} stage, where IP's clip plane is off, so everything behind the far portal was
 * drawn in the view (back-bleed). In a portal pass its quads and lines are cut on the CPU at the pass's clip plane,
 * as Create Aeronautics' laser is ({@code IPLaserPortalsClient.renderClippedInPortalPass}); that also holds with a
 * shader pack, whose programs IP cannot give the plane at that stage.
 */
public final class IPOutlinerPortalClip {
    private IPOutlinerPortalClip() {
    }

    /**
     * Runs {@code render} (the outliner's draw into {@code buffer}) with the buffer cut at the active portal pass's
     * clip plane, or as is outside portal passes. Pending primitives are flushed into {@code buffer} before returning,
     * so the caller's {@code buffer.draw()} draws everything.
     */
    public static void render(final PoseStack poseStack, final SuperRenderTypeBuffer buffer, final Vec3 camera,
                              final Consumer<SuperRenderTypeBuffer> render) {
        final Plane clip = PortalRendering.isRendering() ? PortalRendering.getActiveClippingPlane() : null;
        if (clip == null) {
            render.accept(buffer);
            return;
        }
        // Outline vertices are stored as the stage pose times (world - camera); the plane goes the same way.
        final Matrix4f pose = poseStack.last().pose();
        final Vec3 local = clip.pos().subtract(camera);
        final Vector3f anchor = pose.transformPosition(new Vector3f((float) local.x, (float) local.y, (float) local.z));
        final Vector3f normal = pose.transformDirection(
            new Vector3f((float) clip.normal().x, (float) clip.normal().y, (float) clip.normal().z)
        ).normalize();
        final Clipped clipped = new Clipped(buffer, normal, anchor);
        try {
            render.accept(clipped);
        } finally {
            clipped.flush();
        }
    }

    /** A {@link SuperRenderTypeBuffer} whose three phases clip at one plane ({@link IPPlaneClippingBufferSource}). */
    private static final class Clipped implements SuperRenderTypeBuffer {
        private final SuperRenderTypeBuffer delegate;
        private final IPPlaneClippingBufferSource early;
        private final IPPlaneClippingBufferSource normal;
        private final IPPlaneClippingBufferSource late;

        Clipped(final SuperRenderTypeBuffer delegate, final Vector3f planeNormal, final Vector3f anchor) {
            this.delegate = delegate;
            this.early = new IPPlaneClippingBufferSource(delegate::getEarlyBuffer, planeNormal, anchor);
            this.normal = new IPPlaneClippingBufferSource(delegate::getBuffer, planeNormal, anchor);
            this.late = new IPPlaneClippingBufferSource(delegate::getLateBuffer, planeNormal, anchor);
        }

        // Each phase is a batched source that may finish one render type's buffer when another is asked for: emit
        // every pending primitive before any request, as IPPlaneClippingBufferSource does within one source.
        @Override
        public VertexConsumer getEarlyBuffer(final RenderType type) {
            this.flush();
            return this.early.getBuffer(type);
        }

        @Override
        public VertexConsumer getBuffer(final RenderType type) {
            this.flush();
            return this.normal.getBuffer(type);
        }

        @Override
        public VertexConsumer getLateBuffer(final RenderType type) {
            this.flush();
            return this.late.getBuffer(type);
        }

        @Override
        public void draw() {
            this.flush();
            this.delegate.draw();
        }

        @Override
        public void draw(final RenderType type) {
            this.flush();
            this.delegate.draw(type);
        }

        void flush() {
            this.early.flush();
            this.normal.flush();
            this.late.flush();
        }
    }
}
