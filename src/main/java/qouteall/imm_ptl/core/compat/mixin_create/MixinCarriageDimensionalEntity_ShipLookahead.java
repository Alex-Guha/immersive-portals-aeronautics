/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainPortal;

/**
 * Server: a carriage carrying a player waits ({@code Train.carriageWaitingForChunks}, the train goes nowhere) while the
 * chunk 16 blocks ahead of it is not entity-ticking ({@code Carriage.DimensionalCarriageEntity.alignEntity}). A copy on a
 * ship stands in the ship's plot, and so did that point: past a small ship's plot chunks it never ticks, and a seated
 * driver could not move the train at all (the user's ship with the far end of a portal within the overworld,
 * 2026-11-02; standing at the controls they could). The point is tested where it is in the world, through the ship's
 * pose, which is what Create asks: whether the world ahead is loaded.
 */
@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity", remap = false)
public class MixinCarriageDimensionalEntity_ShipLookahead {
    @WrapOperation(
        method = "alignEntity",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageEntityHandler;isActiveChunk(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
        ),
        require = 0, expect = 0
    )
    private boolean ip_lookAheadInWorld(final Level level, final BlockPos pos, final Operation<Boolean> original,
                                        final CarriageContraptionEntity entity) {
        return original.call(level, IPCreateTrainPortal.inWorld(level, entity.position(), pos));
    }
}
