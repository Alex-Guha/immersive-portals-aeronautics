/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.actors.seat.SeatMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;

/**
 * Create's seat throws its rider off when the seat moves into a solid block. An elevator car going down through a floor
 * portal moves on past the plane in its own level, inside whatever lies behind the portal there (the ground under a floor
 * portal), and its seats got there before their riders' heads reached the hand-over: the rider was thrown off onto that
 * ground, at the portal. Blocks past a car's or far copy's plane belong to the other level and throw nobody off.
 */
@Mixin(value = SeatMovementBehaviour.class, remap = false)
public class MixinSeatMovementBehaviour_ElevatorPortals {
    @Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true, require = 1)
    private void ip$noEjectPastPlane(MovementContext context, BlockPos pos, CallbackInfo ci) {
        if (context.contraption != null && context.contraption.entity != null
            && IPElevatorPortals.isPastPlane(context.contraption.entity, pos)) {
            ci.cancel();
        }
    }
}
