/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;

/**
 * Elevator cars through a portal ({@link IPElevatorPortals}): a far copy keeps the place its car gave it, ticks no
 * actors on the server, and its chests open the car's own storage (one inventory, whichever side it is opened from); a
 * car or far copy leaves alone the entities wholly behind the portal on its side; a car may go below its level's
 * floor into its shaft.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class MixinAbstractContraptionEntity_ElevatorPortals {
    @Shadow
    protected Contraption contraption;

    @Inject(method = "tick", at = @At("TAIL"))
    private void ip$keepFarCopyPlace(CallbackInfo ci) {
        final AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (IPElevatorPortals.farInfo(self) != null) {
            IPElevatorPortals.onFarCopyTicked(self);
        }
    }

    @Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
    private void ip$cutAtPlane(Entity other, CallbackInfoReturnable<Boolean> cir) {
        if (IPElevatorPortals.skipsCollision((AbstractContraptionEntity) (Object) this, other)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tickActors", at = @At("HEAD"), cancellable = true)
    private void ip$noFarActors(CallbackInfo ci) {
        final AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (!self.level().isClientSide() && IPElevatorPortals.farInfo(self) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "onBelowWorld", at = @At("HEAD"), cancellable = true)
    private void ip$shaftBelowWorld(CallbackInfo ci) {
        if (IPElevatorPortals.homeShaft((AbstractContraptionEntity) (Object) this) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerInteraction", at = @At("HEAD"), cancellable = true)
    private void ip$farCopyInteraction(Player player, BlockPos localPos, Direction side, InteractionHand hand,
                                       CallbackInfoReturnable<Boolean> cir) {
        final AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (IPElevatorPortals.farInfo(self) == null || contraption == null || contraption.getSeats().contains(localPos)
            || ((IPContraptionInteractorsAccessor) contraption).ip_getInteractors().containsKey(localPos)
            || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return; // the car; seats and controls on either copy; the client's side of a click: Create's own handling
        }
        // a storage block on a far copy: the car's own storage
        cir.setReturnValue(IPElevatorPortals.openCarStorage(self, serverPlayer, localPos));
    }
}
