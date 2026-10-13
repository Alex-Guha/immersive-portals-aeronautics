/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPClippedFlame;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

import java.util.List;

/**
 * Cuts Create Aeronautics' hot air burner flame at a straddling ship's portal plane and at IP's plane in portal views
 * ({@link IPClippedFlame}). The flame is drawn at once with its own shader, so the block entity cut (which clips what
 * goes through the buffer source) left it whole: it reached past the portal on the cut side.
 */
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerRenderer", remap = false)
public class MixinHotAirBurnerRenderer_StraddleClip {
    @WrapOperation(
        method = "renderSafe(Ldev/eriksonn/aeronautics/content/blocks/hot_air/hot_air_burner/HotAirBurnerBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(value = "INVOKE", target = "Ldev/eriksonn/aeronautics/content/blocks/hot_air/hot_air_burner/HotAirBurnerRenderer;renderFlame(Lcom/mojang/blaze3d/vertex/PoseStack;)V"),
        require = 0
    )
    private void ip_clipFlame(final PoseStack poseStack, final Operation<Void> original, @Local(argsOnly = true) final HotAirBurnerBlockEntity burner) {
        final List<Vector3f[]> planes = IPStraddleRender.immediateClipPlanes(burner, poseStack);
        if (planes.isEmpty()) {
            original.call(poseStack);
        } else {
            IPClippedFlame.draw(poseStack, planes);
        }
    }
}
