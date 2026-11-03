/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInputPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainControls;
import qouteall.imm_ptl.core.compat.create_integration.IPDriveTrace;
import qouteall.imm_ptl.core.compat.create_integration.IPSeatSyncTrace;

/**
 * Diagnostics ({@link IPDriveTrace}): a driver's input, the copy it names and whether Create's reach test let it through
 * (a failed one drops it without a word).
 */
@Mixin(value = ControlsInputPacket.class, remap = false)
public class MixinControlsInputPacket_DriveTrace {
    @Inject(method = "handle", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_traceInput(final ServerPlayer player, final CallbackInfo ci) {
        if (!IPSeatSyncTrace.ON) {
            return;
        }
        final ControlsInputPacket self = (ControlsInputPacket) (Object) this;
        Entity entity = player.level().getEntity(self.contraptionEntityId());
        if (entity == null) {
            entity = IPCreateTrainControls.findControlledEntity(player, self.contraptionEntityId());
        }
        if (entity == null || self.stopControlling()) {
            IPDriveTrace.onInput(player, self.contraptionEntityId(), self.activatedButtons(), self.press(),
                self.stopControlling(), entity, null, self.controlsPos());
        }
    }

    @WrapOperation(
        method = "handle",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;canInteractWithBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;D)Z"
        ),
        require = 0, expect = 0
    )
    private boolean ip_traceReach(final AbstractContraptionEntity entity, final Player player, final BlockPos controlsPos,
                                  final double distance, final Operation<Boolean> original, final ServerPlayer sender) {
        final boolean reach = original.call(entity, player, controlsPos, distance);
        if (IPSeatSyncTrace.ON) {
            final ControlsInputPacket self = (ControlsInputPacket) (Object) this;
            IPDriveTrace.onInput(sender, self.contraptionEntityId(), self.activatedButtons(), self.press(), false, entity,
                reach, controlsPos);
        }
        return reach;
    }
}
