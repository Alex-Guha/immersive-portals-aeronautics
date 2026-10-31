/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

/**
 * Portal view distance ({@link IPPortalViewDistance}): in a portal pass with a cut, Sodium searches for visible
 * sections only out to the cut. {@code getSearchDistance} is where Sodium's own fog occlusion takes the fog end; Iris
 * redirects the option read inside it, which this leaves alone.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinSodiumRenderSectionManager_PortalViewDistance {
    @ModifyReturnValue(method = "getSearchDistance", at = @At("RETURN"))
    private float ip_capAtPortalViewDistance(float distance) {
        return IPPortalViewDistance.capSearchDistance(distance);
    }
}
