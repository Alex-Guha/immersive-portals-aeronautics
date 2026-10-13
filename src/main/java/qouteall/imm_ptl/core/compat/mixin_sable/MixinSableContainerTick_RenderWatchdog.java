/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipRenderWatchdog;

/**
 * Hooks the end of {@code SubLevelContainer.tick} into {@link IPShipRenderWatchdog#tick},
 * which ticks the sub-level containers of non-current client worlds (portal-side views)
 * and schedules render-data rebuilds for plots whose compilation has stalled.
 */
@Mixin(
    value = SubLevelContainer.class,
    remap = false
)
public class MixinSableContainerTick_RenderWatchdog {
    @Inject(
        method = "tick",
        at = @At("TAIL"),
        require = 0
    )
    private void ip_tickRenderWatchdog(CallbackInfo ci) {
        try {
            IPShipRenderWatchdog.tick(this);
        } catch (Throwable ignored) {
        }
    }
}
