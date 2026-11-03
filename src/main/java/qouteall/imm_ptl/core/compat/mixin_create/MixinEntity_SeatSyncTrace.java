/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPSeatSyncTrace;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;

/**
 * Diagnostics ({@link IPSeatSyncTrace}): players taking and leaving carriage seats, on both sides, and carriages
 * removed.
 */
@Mixin(Entity.class)
public abstract class MixinEntity_SeatSyncTrace {
    @Shadow
    public abstract Entity getVehicle();

    @Inject(method = "removeVehicle", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_traceDismount(final CallbackInfo ci) {
        if (IPSeatSyncTrace.ON && (Object) this instanceof final Player player
            && getVehicle() instanceof final CarriageContraptionEntity carriage) {
            IPSeatSyncTrace.onDismount(player, carriage);
        } else if (IPSeatSyncTrace.ON && !((Object) this instanceof Player) && getVehicle() != null
            && IPSableCompatHelper.isPlotSpacePosition(getVehicle().getX(), getVehicle().getZ())) {
            IPSeatSyncTrace.onShipSeatDismount((Entity) (Object) this, getVehicle());
        }
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("RETURN"), require = 0, expect = 0)
    private void ip_traceSeated(final Entity vehicle, final boolean force, final CallbackInfoReturnable<Boolean> cir) {
        if (IPSeatSyncTrace.ON && cir.getReturnValueZ() && (Object) this instanceof final Player player
            && vehicle instanceof CarriageContraptionEntity) {
            IPSeatSyncTrace.onSeated(player, vehicle);
        } else if (IPSeatSyncTrace.ON && cir.getReturnValueZ() && !((Object) this instanceof Player)
            && IPSableCompatHelper.isPlotSpacePosition(vehicle.getX(), vehicle.getZ())) {
            IPSeatSyncTrace.onShipSeated((Entity) (Object) this, vehicle);
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_traceRemoved(final Entity.RemovalReason reason, final CallbackInfo ci) {
        if (IPSeatSyncTrace.ON && (Object) this instanceof final CarriageContraptionEntity carriage) {
            IPSeatSyncTrace.onRemoved(carriage, reason);
        }
    }
}
