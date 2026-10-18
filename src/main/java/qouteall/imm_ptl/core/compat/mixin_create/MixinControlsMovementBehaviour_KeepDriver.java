/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsMovementBehaviour;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainControls;

/**
 * Server: removing the copy of a carriage on the side it has left (or whose chunk unloaded) stops its actors, and the
 * train controls' {@code stopMoving} stopped the driver. Not while another copy of the carriage lives
 * ({@link IPCreateTrainControls#stopsDriverOnRemoval}).
 */
@Mixin(value = ControlsMovementBehaviour.class, remap = false)
public class MixinControlsMovementBehaviour_KeepDriver {
    @WrapOperation(
        method = "stopMoving",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;stopControlling(Lnet/minecraft/core/BlockPos;)V"
        ),
        require = 0, expect = 0
    )
    private void ip_keepDriver(final AbstractContraptionEntity entity, final BlockPos controlsPos, final Operation<Void> original) {
        if (IPCreateTrainControls.stopsDriverOnRemoval(entity)) {
            original.call(entity, controlsPos);
        }
    }
}
