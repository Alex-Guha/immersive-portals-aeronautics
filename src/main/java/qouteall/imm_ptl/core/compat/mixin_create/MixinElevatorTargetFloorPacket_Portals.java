/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.elevator.ElevatorTargetFloorPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;

/**
 * A floor picked on the controls of an elevator car with a shaft through a portal, or of its far copy: the floor may be
 * in either column ({@link IPElevatorPortals#onTargetFloor}). Create looks only in the clicked contraption's own column.
 */
@Mixin(value = ElevatorTargetFloorPacket.class, remap = false)
public abstract class MixinElevatorTargetFloorPacket_Portals {
    @Shadow
    public abstract int entityId();

    @Shadow
    public abstract int targetY();

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void ip$eitherColumn(ServerPlayer sender, CallbackInfo ci) {
        if (IPElevatorPortals.onTargetFloor(sender.serverLevel(), sender.serverLevel().getEntity(entityId()), targetY(), sender)) {
            ci.cancel();
        }
    }
}
