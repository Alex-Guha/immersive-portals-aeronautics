/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Create elevator car's far copy on a ship (docs/plans/elevators.md) is not one of the ship's contraptions: Sable adds
 * a contraption's mass, lift and floating blocks to the ship whose plot holds it, and the car hangs from its pulley on
 * the other side of the portal, whose ship (or the world) already carries it. With the copy counted, the far ship took
 * the car's weight for as long as the car was through, a car parked at a far floor for good.
 */
@Mixin(value = ServerLevelPlot.class, remap = false)
public class MixinServerLevelPlot_ElevatorFarCopies {
    @Unique
    private static final boolean ip_CREATE = ModList.get().isLoaded("create");

    @Inject(method = "addContraption", at = @At("HEAD"), cancellable = true, require = 1)
    private void ip_notTheFarCopy(KinematicContraption contraption, CallbackInfo ci) {
        if (ip_CREATE && qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals.isFarCopy(contraption)) {
            ci.cancel();
        }
    }
}
