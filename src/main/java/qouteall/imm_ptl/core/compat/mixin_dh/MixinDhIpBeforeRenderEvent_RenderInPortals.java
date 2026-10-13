/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalView;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Distant Horizons' own Immersive Portals hook cancels the LOD pass whenever a portal is being
 * rendered. Skips that cancellation so LODs and DH clouds appear inside portal views
 * ({@link IPDhPortalView}). Everything else in the pass already works per level: DH picks the level
 * from the level renderer that is drawing (IP swaps it per dimension), loads it on demand, renders
 * from the current camera, and composites with a fullscreen draw that respects IP's stencil mask.
 */
@Mixin(
    targets = "com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractImmersivePortalsAccessor$BeforeRenderEvent",
    remap = false
)
public class MixinDhIpBeforeRenderEvent_RenderInPortals {
    @Inject(
        method = "beforeRender",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_renderLodsInPortalViews(DhApiCancelableEventParam<DhApiRenderParam> event, CallbackInfo ci) {
        if (IPDhPortalView.ENABLED && PortalRendering.isRendering()) {
            ci.cancel();
        }
    }
}
