/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.common.commonMixins.MixinVanillaFogCommon_neoforge;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IImmersivePortalsAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalView;

/**
 * DH replaces vanilla terrain fog with its own (pushing the vanilla fog out of sight) except during
 * a portal pass, where it left vanilla fog on. Now that LODs render inside portal views, the portal
 * side gets the same fog treatment as the outer world; otherwise the vanilla terrain in the portal
 * fades into fog at the render distance with LODs visible behind the haze.
 */
@Mixin(value = MixinVanillaFogCommon_neoforge.class, remap = false)
public class MixinDhVanillaFog_CancelInPortals {
    @Redirect(
        method = "cancelFog",
        at = @At(
            value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/modAccessor/IImmersivePortalsAccessor;isRenderingPortal()Z"
        ),
        require = 0
    )
    private static boolean ip_treatPortalPassLikeOuterWorld(IImmersivePortalsAccessor accessor) {
        return !IPDhPortalView.ENABLED && accessor.isRenderingPortal();
    }
}
