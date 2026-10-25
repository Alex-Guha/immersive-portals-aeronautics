/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainControls;

/**
 * A train driven through a portal ({@link IPCreateTrainControls}): Create's reach test to a contraption in another
 * dimension than the player goes through a portal next to the player, as does a failed one to a carriage in the
 * player's level (a portal within it), and every stop of a carriage's driver is logged.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public class MixinAbstractContraptionEntity_TrainControls {
    @Inject(
        method = "canInteractWithBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;D)Z",
        at = @At("HEAD"), cancellable = true, require = 0, expect = 0
    )
    private void ip_reachThroughPortal(final Player player, final Vec3 localPos, final double distance,
                                       final CallbackInfoReturnable<Boolean> cir) {
        final AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (player.level() != self.level()) {
            cir.setReturnValue(IPCreateTrainControls.canReach(self, player, localPos, distance));
        }
    }

    @Inject(
        method = "canInteractWithBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;D)Z",
        at = @At("RETURN"), cancellable = true, require = 0, expect = 0
    )
    private void ip_reachThroughPortalWithin(final Player player, final Vec3 localPos, final double distance,
                                             final CallbackInfoReturnable<Boolean> cir) {
        final AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (!cir.getReturnValueZ() && self instanceof CarriageContraptionEntity && player.level() == self.level()) {
            cir.setReturnValue(IPCreateTrainControls.canReach(self, player, localPos, distance));
        }
    }

    @Inject(method = "stopControlling", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_logStop(final BlockPos controlsLocalPos, final CallbackInfo ci) {
        if ((Object) this instanceof final CarriageContraptionEntity carriage) {
            IPCreateTrainControls.onStop(carriage, controlsLocalPos);
        }
    }
}
