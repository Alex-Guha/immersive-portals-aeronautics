/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.track.AllPortalTracks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalTrackProvider;

/**
 * After Create registers its default portal-track providers
 * ({@code AllPortalTracks.registerDefaults}), registers IP's
 * {@link IPCreatePortalTrackProvider} so Create trains can path through IP portals.
 */
@Mixin(
    value = AllPortalTracks.class,
    remap = false
)
public class MixinCreateAllPortalTracks {
    @Inject(
        method = "registerDefaults",
        at = @At("TAIL"),
        require = 0,
        expect = 0
    )
    private static void ip_registerImmersivePortals(CallbackInfo ci) {
        IPCreatePortalTrackProvider.register();
    }
}
