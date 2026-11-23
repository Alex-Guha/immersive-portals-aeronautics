/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.djefrey.colorwheel.ClrwlSafeFlwImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_integration.IPPortalViewShadows;

/**
 * Portal View Shadows ({@link IPPortalViewShadows}): Colorwheel draws Flywheel's visuals (Create contraptions, kinetic
 * parts) into Iris's shadow map from its own hook in {@code ShadowRenderer.renderShadows}, once for the solid phase and
 * once for the translucent one; left out with entities. Applied only with Colorwheel.
 */
@Mixin(value = ClrwlSafeFlwImpl.class, remap = false)
public class MixinColorwheelSafeFlw_PortalViewShadows {
    @Inject(method = "submitShadowRenderContext", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ip_skipPortalViewShadows(CallbackInfo ci) {
        if (!IPPortalViewShadows.drawsEntities(true)) {
            ci.cancel();
        }
    }
}
