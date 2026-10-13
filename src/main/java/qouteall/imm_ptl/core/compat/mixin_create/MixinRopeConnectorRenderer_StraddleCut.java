/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.createmod.catnip.render.SuperByteBuffer;
import dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

/**
 * A rope connector of a straddling ship draws its rope (cut per segment, {@code MixinRopeStrandRenderer_StraddleCut})
 * and the rope's end piece at the block: the end piece follows the block-centre cut of the ship's other block
 * entities, or every connector's end piece showed in the far half.
 */
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorRenderer", remap = false)
public abstract class MixinRopeConnectorRenderer_StraddleCut {
    @WrapOperation(method = "renderSafe", at = @At(value = "INVOKE",
            target = "Lnet/createmod/catnip/render/SuperByteBuffer;renderInto(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"),
            require = 0)
    private void ip_cutEndPiece(final SuperByteBuffer endPiece, final PoseStack poseStack, final VertexConsumer consumer,
                                final Operation<Void> original, @Local(argsOnly = true) final RopeConnectorBlockEntity connector) {
        if (IPStraddleRender.keepsByCentre(connector)) {
            original.call(endPiece, poseStack, consumer);
        }
    }
}
