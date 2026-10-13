/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhGenGuard;

/**
 * Makes {@code ConfigEntry.get()} return {@link Boolean#FALSE} for Distant Horizons' distant/server
 * world-generation entries whenever C2ME is loaded (the two mods' generators conflict). The entries
 * are resolved reflectively by {@link IPDhGenGuard}. Modpack hack unrelated to portals.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.config.types.ConfigEntry", remap = false)
public class MixinDhConfigEntry_IgnoreGenWithC2ME {
    @Inject(
        method = "get",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_ignoreDhGenerationWithC2ME(CallbackInfoReturnable<Object> cir) {
        if (IPDhGenGuard.shouldForceDisable(this)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
