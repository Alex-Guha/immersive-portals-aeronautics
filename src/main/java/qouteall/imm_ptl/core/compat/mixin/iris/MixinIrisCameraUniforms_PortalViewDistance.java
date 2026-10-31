/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.irisshaders.iris.uniforms.CameraUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

/**
 * Portal view distance ({@link IPPortalViewDistance}): in a portal pass with a cut, Iris's {@code far} uniform (the
 * render distance in blocks) is the cut, so a shader pack's border fog ends the view there. Packs ignore vanilla's
 * fog distances for terrain; most draw their border fog from {@code far}.
 */
@Mixin(value = CameraUniforms.class, remap = false)
public class MixinIrisCameraUniforms_PortalViewDistance {
    @ModifyReturnValue(method = "getRenderDistanceInBlocks", at = @At("RETURN"))
    private static int ip_farAtPortalViewDistance(int blocks) {
        return IPPortalViewDistance.capRenderDistanceBlocks(blocks);
    }
}
