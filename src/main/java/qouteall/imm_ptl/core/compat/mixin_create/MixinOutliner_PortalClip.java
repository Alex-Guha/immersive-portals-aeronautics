/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.create_integration.IPOutlinerPortalClip;

/**
 * Cuts Catnip's outlines (glue boxes, selections, ...) at the clip plane in portal passes ({@link IPOutlinerPortalClip}).
 */
@Mixin(targets = "net.createmod.catnip.outliner.Outliner", remap = false)
public class MixinOutliner_PortalClip {
    @WrapMethod(method = "renderOutlines", require = 0)
    private void ip_clipInPortalPass(final PoseStack poseStack, final SuperRenderTypeBuffer buffer, final Vec3 camera,
                                     final float partialTick, final Operation<Void> original) {
        IPOutlinerPortalClip.render(poseStack, buffer, camera, clipped -> original.call(poseStack, clipped, camera, partialTick));
    }
}
