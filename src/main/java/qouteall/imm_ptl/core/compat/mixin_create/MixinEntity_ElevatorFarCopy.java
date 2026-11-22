/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorFarCopy;

/**
 * An elevator car's far copy is never saved: loaded again it would be a contraption without a controller, standing
 * still for good. Its car makes it again when the car next reaches past the plane.
 */
@Mixin(Entity.class)
public class MixinEntity_ElevatorFarCopy {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void ip$neverSaveFarCopy(CallbackInfoReturnable<Boolean> cir) {
        if (this instanceof IPElevatorFarCopy far && far.ip$farInfo() != null) {
            cir.setReturnValue(false);
        }
    }
}
