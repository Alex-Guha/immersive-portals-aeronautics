/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.simulated_team.simulated.content.blocks.lasers.laser_pointer.LaserPointerBlockEntity;
import dev.simulated_team.simulated.content.blocks.lasers.laser_pointer.LaserPointerRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.sable_integration.IPLaserPortalsClient;

/**
 * A laser pointer (its lens and beam) in IP's portal passes, cut at the portal plane
 * ({@link IPLaserPortalsClient#renderClippedInPortalPass}): its lens shader is Simulated's own as well.
 */
@Mixin(value = LaserPointerRenderer.class, remap = false)
public abstract class MixinLaserPointerRenderer_PortalClip {
    @WrapMethod(
        method = "renderSafe(Ldev/simulated_team/simulated/content/blocks/lasers/laser_pointer/LaserPointerBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        require = 0
    )
    private void ip_clipInPortalPass(final LaserPointerBlockEntity blockEntity, final float partialTicks, final PoseStack pose,
                                     final MultiBufferSource buffer, final int light, final int overlay, final Operation<Void> original) {
        IPLaserPortalsClient.renderClippedInPortalPass(blockEntity, pose, buffer,
                clipped -> original.call(blockEntity, partialTicks, pose, clipped, light, overlay));
    }
}
