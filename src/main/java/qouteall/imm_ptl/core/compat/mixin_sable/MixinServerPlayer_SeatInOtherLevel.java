/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleSeats;

/** A player cannot ride a vehicle in another level: see {@link IPStraddleSeats#sitInOtherLevel}. */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer_SeatInOtherLevel {
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("HEAD"), cancellable = true)
    private void ip_sitInOtherLevel(final Entity vehicle, final boolean force, final CallbackInfoReturnable<Boolean> cir) {
        final ServerPlayer player = (ServerPlayer) (Object) this;
        if (vehicle.level() != player.level()) {
            IPStraddleSeats.sitInOtherLevel(player, vehicle);
            cir.setReturnValue(false);
        }
    }
}
