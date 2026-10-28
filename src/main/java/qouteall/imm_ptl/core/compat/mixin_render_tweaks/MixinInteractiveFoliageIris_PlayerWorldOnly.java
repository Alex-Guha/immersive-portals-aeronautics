/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.IPFoliageCompat;

/**
 * Interactive Foliage's foliage is not drawn into another world's shadow map ({@code IPFoliageCompat}): its GPU meshes
 * are the player's world's, and Iris's shadow pass runs in portal passes too.
 */
@Mixin(targets = "net.karto.mc2.mc2_interactivefoliage.gpu.IrisFoliageShaders", remap = false)
public class MixinInteractiveFoliageIris_PlayerWorldOnly {
    @Inject(method = "drawShadow", at = @At("HEAD"), cancellable = true)
    private static void ip_skipOtherWorldShadow(
        Matrix4f modelView, Matrix4f projection, double x, double y, double z, CallbackInfo ci
    ) {
        if (IPFoliageCompat.isOtherWorld()) {
            ci.cancel();
        }
    }
}
