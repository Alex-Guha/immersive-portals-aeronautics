/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageSyncData;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainSides;

/** A copy's sync data reads and writes its own record ({@link IPCreateTrainSides#recordOf}), not its level's. */
@Mixin(value = CarriageSyncData.class, remap = false)
public class MixinCarriageSyncData_TrainSides {
    @WrapOperation(
        method = {"update", "apply", "approach"},
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/Carriage;getDimensional(Lnet/minecraft/world/level/Level;)Lcom/simibubi/create/content/trains/entity/Carriage$DimensionalCarriageEntity;"
        )
    )
    private Carriage.DimensionalCarriageEntity ip_ownRecord(final Carriage carriage, final Level level,
                                                            final Operation<Carriage.DimensionalCarriageEntity> original,
                                                            @Local(argsOnly = true) final CarriageContraptionEntity entity) {
        final Carriage.DimensionalCarriageEntity own = IPCreateTrainSides.recordOf(carriage, entity);
        return own != null ? own : original.call(carriage, level);
    }
}
