/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPulley;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorShaft;

/**
 * An elevator's rope runs on through its shaft's portal: the car may go down to the far level's floor (mapped into the
 * pulley's frame), still no further than the rope length, instead of stopping at the pulley's own level's floor.
 */
@Mixin(value = PulleyBlockEntity.class, remap = false)
public class MixinPulleyBlockEntity_ElevatorRange {
    @Inject(method = "getExtensionRange", at = @At("RETURN"), cancellable = true)
    private void ip$throughShaft(CallbackInfoReturnable<Integer> cir) {
        if (!((Object) this instanceof IPElevatorPulley pulley)) {
            return;
        }
        final IPElevatorShaft shaft = pulley.ip$shaft();
        if (shaft == null) {
            return;
        }
        final int y = ((PulleyBlockEntity) (Object) this).getBlockPos().getY();
        final int range = Math.min(AllConfigs.server().kinetics.maxRopeLength.get(), y - 1 - shaft.bottomY());
        cir.setReturnValue(Math.max(cir.getReturnValue(), Math.max(0, range)));
    }
}
