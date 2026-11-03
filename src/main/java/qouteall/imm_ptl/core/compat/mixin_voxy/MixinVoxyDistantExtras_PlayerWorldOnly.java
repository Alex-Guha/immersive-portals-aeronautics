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
 * Neo Voxy's distant extras are one global renderer each, run in every Voxy draw, portal passes included, and hold the
 * player's world only. In a portal pass into another dimension the distant tracks and machines of the player's world
 * were drawn at their own coordinates in the other world, and the beacon renderer threw all its state away whenever
 * the pass's world was not the one it was bound to (twice a frame with such a portal in view), so distant beacon beams
 * vanished. They now draw nothing there ({@link IPVoxyPortalView#isOtherWorld}); a view into another dimension has
 * none of them, as Neo Voxy's distant trains and contraptions already keep to the player's dimension. Lasers:
 * {@code MixinVoxyDistantLaserRenderer_PlayerWorldOnly}. Classes Roxy's Voxy does not have.
 */
@Pseudo
@Mixin(
    targets = {
        "me.cortex.voxy.client.compat.create.DistantTrackRenderer",
        "me.cortex.voxy.client.compat.create.DistantKineticRenderer",
        "me.cortex.voxy.client.core.beacon.DistantBeaconRenderer"
    },
    remap = false
)
public class MixinVoxyDistantExtras_PlayerWorldOnly {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_playerWorldOnly(CallbackInfo ci) {
        if (IPVoxyPortalView.isOtherWorld()) {
            ci.cancel();
        }
    }
}
