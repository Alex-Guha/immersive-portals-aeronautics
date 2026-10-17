/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalStencilGuard;

/**
 * Around DH's render entry points: records IP's stencil settings at the start of each call in a portal pass and
 * writes them back after it ({@link IPDhPortalStencilGuard}).
 */
@Mixin(value = ClientApi.class, remap = false)
public class MixinDhClientApi_PortalStencil {
    @Inject(method = "renderLods", at = @At("HEAD"), require = 0)
    private void ip_saveStencilBeforeLods(CallbackInfo ci) {
        IPDhPortalStencilGuard.beforeDh();
    }

    @Inject(method = "renderLods", at = @At("RETURN"), require = 0)
    private void ip_restoreStencilAfterLods(CallbackInfo ci) {
        IPDhPortalStencilGuard.afterDh();
    }

    @Inject(method = "renderDeferredLodsForShaders", at = @At("HEAD"), require = 0)
    private void ip_saveStencilBeforeDeferred(CallbackInfo ci) {
        IPDhPortalStencilGuard.beforeDh();
    }

    @Inject(method = "renderDeferredLodsForShaders", at = @At("RETURN"), require = 0)
    private void ip_restoreStencilAfterDeferred(CallbackInfo ci) {
        IPDhPortalStencilGuard.afterDh();
    }

    @Inject(method = "renderFadeOpaque", at = @At("HEAD"), require = 0)
    private void ip_saveStencilBeforeFadeOpaque(CallbackInfo ci) {
        IPDhPortalStencilGuard.beforeDh();
    }

    @Inject(method = "renderFadeOpaque", at = @At("RETURN"), require = 0)
    private void ip_restoreStencilAfterFadeOpaque(CallbackInfo ci) {
        IPDhPortalStencilGuard.afterDh();
    }

    @Inject(method = "renderFadeTransparent", at = @At("HEAD"), require = 0)
    private void ip_saveStencilBeforeFadeTransparent(CallbackInfo ci) {
        IPDhPortalStencilGuard.beforeDh();
    }

    @Inject(method = "renderFadeTransparent", at = @At("RETURN"), require = 0)
    private void ip_restoreStencilAfterFadeTransparent(CallbackInfo ci) {
        IPDhPortalStencilGuard.afterDh();
    }
}
