/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.RopeStrandRenderer;
import net.createmod.catnip.render.SuperByteBuffer;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPPlaneClampingVertexConsumer;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRopes;

/**
 * Ropes of straddling ships ({@link IPStraddleRopes}): in Aeronautics' rope renderer, the rope's world points are
 * turned into ship-local ones with the ship's real pose also while its far half is drawn (the block entity transform
 * then applies the mapped pose, so each point lands at its image through the portal), and every segment is drawn only
 * in its parts on the current side of the portal ({@link IPStraddleRopes#segmentCut}: a segment crossing the portal
 * plane ends exactly in it; knots by their centre).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.rope.strand.client.RopeStrandRenderer", remap = false)
public abstract class MixinRopeStrandRenderer_StraddleCut {
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;renderPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;"),
            require = 0)
    private static Pose3dc ip_realPose(final ClientSubLevel ship) {
        return IPStraddleRender.realRenderPose(ship);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/createmod/catnip/render/SuperByteBuffer;renderInto(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V",
            ordinal = 1), require = 0)
    private static void ip_skipHiddenSegment(final SuperByteBuffer segment, final PoseStack poseStack, final VertexConsumer consumer,
                                             final Operation<Void> original, @Local(argsOnly = true) final SmartBlockEntity owner,
                                             @Local(ordinal = 0) final Vector3d from, @Local(ordinal = 1) final RopeStrandRenderer.RopeRenderPoint to) {
        final IPStraddleRopes.SegmentCut cut = IPStraddleRopes.segmentCut(owner, from, to.position());
        if (cut == null) {
            original.call(segment, poseStack, consumer);
            return;
        }
        // the segment model is a box from (6.5, 0, 6.5) to (9.5, 16, 9.5) pixels: here x = z = 0.5 is the rope's axis and
        // y 0..1 runs along the segment (scaled to its length)
        if (cut.clamp() != null) {
            final org.joml.Matrix4f pose = poseStack.last().pose();
            final org.joml.Vector3f anchor = pose.transformPosition(new org.joml.Vector3f(0.5f, (float) cut.clamp().at(), 0.5f));
            final org.joml.Vector3f axis = pose.transformDirection(new org.joml.Vector3f(0.0f, 1.0f, 0.0f));
            final org.joml.Vector3f normal = new org.joml.Vector3f((float) cut.clamp().normal().x, (float) cut.clamp().normal().y,
                    (float) cut.clamp().normal().z);
            // the same seam as the ship's own cut: the rope's end never lies in the portal surface
            anchor.sub(new org.joml.Vector3f(normal).mul((float) IPStraddleRender.seamReach()));
            // a segment almost parallel to the plane would slide far: draw its kept piece instead
            if (Math.abs(normal.dot(axis)) > 0.05f * axis.length()) {
                original.call(segment, poseStack, new IPPlaneClampingVertexConsumer(consumer, normal, anchor, axis));
                return;
            }
        }
        for (final double[] part : cut.parts()) {
            poseStack.pushPose();
            poseStack.translate(0.0, part[0], 0.0);
            poseStack.scale(1.0f, (float) (part[1] - part[0]), 1.0f);
            original.call(segment, poseStack, consumer);
            poseStack.popPose();
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/createmod/catnip/render/SuperByteBuffer;renderInto(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V",
            ordinal = 0), require = 0)
    private static void ip_trimKnot(final SuperByteBuffer knot, final PoseStack poseStack, final VertexConsumer consumer,
                                    final Operation<Void> original, @Local(argsOnly = true) final SmartBlockEntity owner,
                                    @Local(ordinal = 0) final Vector3d at) {
        if (IPStraddleRopes.keepsKnot(owner, at)) {
            original.call(knot, poseStack, consumer);
        }
    }
}
