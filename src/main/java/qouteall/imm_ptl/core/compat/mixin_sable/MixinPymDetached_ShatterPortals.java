/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalPocketCases;

/**
 * Pym (Create: Pocket Sized 0.32) taking a ship out of the world as a build, onto a hat or a hat stand
 * ({@code adopt}), or saving a detached build into an item ({@code close}: a hat picked up, with the ships riding it):
 * its portals shatter first, while its portal blocks can still be cleared from the plot that is saved. Pym re-creates
 * and closes these ships outside Sable's lookups, so the removal check would not see them go.
 */
@Mixin(targets = "com.misterblusky9.pym.api.Detached", remap = false)
public class MixinPymDetached_ShatterPortals {
    @Inject(method = "adopt", at = @At("HEAD"), require = 0)
    private static void ip_shatterPortalsBeforeAdopting(final ServerSubLevel ship, final CallbackInfoReturnable<ServerSubLevel> cir) {
        if (!ship.isRemoved()) {
            IPShipPortalPocketCases.onBecomingHeldBuild(ship);
        }
    }

    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private static void ip_shatterPortalsBeforeClosing(final ServerSubLevel ship, final CallbackInfoReturnable<net.minecraft.nbt.CompoundTag> cir) {
        IPShipPortalPocketCases.onBecomingHeldBuild(ship);
    }
}
