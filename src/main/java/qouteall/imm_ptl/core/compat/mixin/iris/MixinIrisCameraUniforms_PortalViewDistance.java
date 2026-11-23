/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
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
 * <p>
 * Without a cut, with Distant Horizons' LODs drawn, it is where the far side's terrain ends
 * ({@link qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalNearClip#shaderFar}): packs blend terrain into LODs
 * by {@code far}, and at the render distance a band of sky showed between the two.
 */
@Mixin(value = CameraUniforms.class, remap = false)
public class MixinIrisCameraUniforms_PortalViewDistance {
    @ModifyReturnValue(method = "getRenderDistanceInBlocks", at = @At("RETURN"))
    private static int ip_farAtPortalViewDistance(int blocks) {
        int result = IPPortalViewDistance.capRenderDistanceBlocks(blocks);
        // with LODs and no cut, where the far side's terrain ends (IPDhPortalNearClip.terrainReach)
        if (!IPPortalViewDistance.hasCut()) {
            result = qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalNearClip.shaderFar(
                result, net.irisshaders.iris.compat.dh.DHCompat.hasRenderingEnabled());
        }
        return result;
    }
}
