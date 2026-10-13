/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaRenderer_neoforge;
import com.seibel.distanthorizons.core.render.RenderParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalView;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * DH's temporal anti-aliasing reprojects each frame against the previous frame's camera position,
 * matrices and colour history. A portal pass in the same frame would overwrite all three with the
 * portal camera's, so the next outer pass reprojected against the wrong history and the LODs
 * jittered badly whenever a portal was on screen. The anti-aliasing is skipped in portal passes
 * (the raw LOD colour is what gets copied to Minecraft, so nothing else changes) and the outer
 * world's history stays intact.
 */
@Mixin(value = GlDhTaaRenderer_neoforge.class, remap = false)
public class MixinDhTaaRenderer_SkipInPortals {
    @Inject(
        method = "render",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_skipTemporalAntiAliasingInPortalViews(RenderParams renderParams, CallbackInfo ci) {
        if (IPDhPortalView.ENABLED && PortalRendering.isRendering()) {
            ci.cancel();
        }
    }
}
