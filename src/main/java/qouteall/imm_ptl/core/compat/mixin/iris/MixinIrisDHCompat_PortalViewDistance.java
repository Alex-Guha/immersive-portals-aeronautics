/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.irisshaders.iris.compat.dh.DHCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

/**
 * Portal view distance ({@link IPPortalViewDistance}): with Distant Horizons, packs draw their border fog from Iris's
 * {@code dhRenderDistance} instead of {@code far} (Complementary's {@code renderDistance}). DH draws no LODs in a
 * portal pass with a cut, so that uniform follows the cut there too, a little short of it
 * ({@link IPPortalViewDistance#capDhRenderDistanceBlocks}).
 */
@Mixin(value = DHCompat.class, remap = false)
public class MixinIrisDHCompat_PortalViewDistance {
    @ModifyReturnValue(method = "getRenderDistance", at = @At("RETURN"))
    private static int ip_dhRenderDistanceAtPortalViewDistance(int blocks) {
        return IPPortalViewDistance.capDhRenderDistanceBlocks(blocks);
    }
}
