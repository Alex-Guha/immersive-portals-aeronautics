/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.IPSeatProxies;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleSeats;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSeatsClient;

/**
 * A seat proxy places its rider where the seat it stands for is, mapped through the portal, from the ship's pose on
 * this side ({@link IPStraddleSeats}), as Sable places the rider of a seat on a ship.
 */
@Mixin(Entity.class)
public class MixinEntity_SeatProxyRider {
    @Inject(method = "positionRider(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_placeOnSeat(final Entity passenger, final Entity.MoveFunction move, final CallbackInfo ci) {
        final Entity self = (Entity) (Object) this;
        if (!IPSeatProxies.isProxy(self)) {
            return;
        }
        final Vec3 position = self.level().isClientSide() ? IPStraddleSeatsClient.riderPosition(self) : IPStraddleSeats.riderPosition(self);
        if (position != null) {
            move.accept(passenger, position.x, position.y, position.z);
            ci.cancel();
        }
    }
}
