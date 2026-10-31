/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

/** Portal view distance: a reloaded pack is patched again or not ({@code MixinIrisTransformPatcher_PortalViewFog}). */
@Mixin(value = Iris.class, remap = false)
public class MixinIrisIris_PortalViewFog {
    @Inject(method = "reload", at = @At("HEAD"))
    private static void ip_resetPatchedBorderFog(CallbackInfo ci) {
        IPPortalViewDistance.packFogPatched = false;
    }
}
