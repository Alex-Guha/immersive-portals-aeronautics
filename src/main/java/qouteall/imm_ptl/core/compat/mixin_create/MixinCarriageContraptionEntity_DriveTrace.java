/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPDriveTrace;
import qouteall.imm_ptl.core.compat.create_integration.IPSeatSyncTrace;

/**
 * Diagnostics ({@link IPDriveTrace}): what the server does with a driver's held keys each tick, and the train's state
 * while it is driven.
 */
@Mixin(value = CarriageContraptionEntity.class, remap = false)
public class MixinCarriageContraptionEntity_DriveTrace {
    @Inject(method = "control", at = @At("RETURN"), require = 0, expect = 0)
    private void ip_traceControl(final BlockPos controlsLocalPos, final Collection<Integer> heldControls, final Player player,
                                 final CallbackInfoReturnable<Boolean> cir) {
        final CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        if (IPSeatSyncTrace.ON && !self.level().isClientSide()) {
            IPDriveTrace.onControl(self, controlsLocalPos, heldControls, player, cir.getReturnValueZ());
        }
    }
}
