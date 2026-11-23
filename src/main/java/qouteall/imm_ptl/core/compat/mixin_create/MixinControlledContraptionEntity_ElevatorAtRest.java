/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An elevator car that has arrived at its floor (and its far copies) carries nothing standing on it. Create gives a
 * translating contraption's motion as its contact motion, and an arrived car keeps the motion of its last step: harmless
 * upright, since Create applies contact motion sideways only, but on a ship turned on its side Sable turns it with the
 * ship, and players and mobs in the stopped car slid along the shaft.
 */
@Mixin(value = ControlledContraptionEntity.class, remap = false)
public class MixinControlledContraptionEntity_ElevatorAtRest {
    @Inject(method = "getContactPointMotion", at = @At("HEAD"), cancellable = true)
    private void ip$arrivedCarIsAtRest(Vec3 globalContactPoint, CallbackInfoReturnable<Vec3> cir) {
        if (((ControlledContraptionEntity) (Object) this).getContraption() instanceof ElevatorContraption elevator && elevator.arrived) {
            cir.setReturnValue(Vec3.ZERO);
        }
    }
}
