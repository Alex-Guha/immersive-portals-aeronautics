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
 * Neo Voxy's distant lasers (Simulated's laser pointers past the render distance) are collected as the lasers draw and
 * drawn in its translucent pass, for one level at a time: both clear every beam whenever {@code Minecraft.level} is not
 * the level they hold, so a portal into another dimension in view cleared them twice a frame and distant lasers
 * vanished. As the other distant extras ({@code MixinVoxyDistantExtras_PlayerWorldOnly}), they keep to the player's
 * world. A class Roxy's Voxy does not have.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.compat.simulated.DistantLaserRenderer", remap = false)
public class MixinVoxyDistantLaserRenderer_PlayerWorldOnly {
    @Inject(method = {"accept", "renderTranslucent"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_playerWorldOnly(CallbackInfo ci) {
        if (IPVoxyPortalView.isOtherWorld()) {
            ci.cancel();
        }
    }
}
