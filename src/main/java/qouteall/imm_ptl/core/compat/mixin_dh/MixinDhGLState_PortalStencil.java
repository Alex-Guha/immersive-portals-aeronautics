/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.common.render.openGl.glObject.GLState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalStencilGuard;

/**
 * DH's GL state snapshot reads the stencil settings while DH's own framebuffer (no stencil buffer) may be bound; Mesa
 * then reports the reference as 0. Inside a DH call in a portal pass the snapshot gets IP's real settings instead, so
 * every DH step that restores the snapshot (and the full-screen composite that draws the LODs into Minecraft's
 * framebuffer between such steps) keeps drawing inside the portal ({@link IPDhPortalStencilGuard}).
 */
@Mixin(value = GLState.class, remap = false)
public class MixinDhGLState_PortalStencil {
    @Shadow
    public boolean stencil;
    @Shadow
    public int stencilFunc;
    @Shadow
    public int stencilRef;
    @Shadow
    public int stencilMask;

    @Inject(method = "saveState", at = @At("TAIL"), require = 0)
    private void ip_keepPortalStencil(CallbackInfo ci) {
        if (IPDhPortalStencilGuard.active()) {
            this.stencil = IPDhPortalStencilGuard.stencilTest();
            this.stencilFunc = IPDhPortalStencilGuard.func();
            this.stencilRef = IPDhPortalStencilGuard.ref();
            this.stencilMask = IPDhPortalStencilGuard.valueMask();
        }
    }
}
