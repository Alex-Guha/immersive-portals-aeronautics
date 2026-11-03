/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPSeatSyncTrace;

/**
 * Diagnostics ({@link IPSeatSyncTrace}): a player starting and stopping to track a carriage. A rider who stops tracking
 * the carriage they ride hears nothing more about it.
 */
@Mixin(ServerEntity.class)
public class MixinServerEntity_SeatSyncTrace {
    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "addPairing", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_traceAddPairing(final ServerPlayer player, final CallbackInfo ci) {
        if (IPSeatSyncTrace.ON && entity instanceof final CarriageContraptionEntity carriage) {
            IPSeatSyncTrace.onPairing(carriage, player, true);
        }
    }

    @Inject(method = "removePairing", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_traceRemovePairing(final ServerPlayer player, final CallbackInfo ci) {
        if (IPSeatSyncTrace.ON && entity instanceof final CarriageContraptionEntity carriage) {
            IPSeatSyncTrace.onPairing(carriage, player, false);
        }
    }
}
