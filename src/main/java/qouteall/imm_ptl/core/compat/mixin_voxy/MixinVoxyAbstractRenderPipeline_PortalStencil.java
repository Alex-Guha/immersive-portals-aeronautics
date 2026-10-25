/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyPortalView;

/**
 * Voxy's composite ({@code transformBlitDepth}, its only draw into Minecraft's framebuffer) runs with the stencil test
 * off. In a portal pass it is limited to the portal's stencil area, or the far dimension's LODs would cover the outer
 * world's sky across the screen ({@link IPVoxyPortalView}). Voxy turns the test off again right after.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
public class MixinVoxyAbstractRenderPipeline_PortalStencil {
    @Inject(
        method = "transformBlitDepth",
        at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;blit()V"),
        require = 0
    )
    private static void ip_limitCompositeToPortal(CallbackInfo ci) {
        IPVoxyPortalView.limitCompositeToPortal();
    }
}
