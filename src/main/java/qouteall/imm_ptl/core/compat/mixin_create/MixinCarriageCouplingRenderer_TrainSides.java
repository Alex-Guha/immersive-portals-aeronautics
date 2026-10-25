/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageCouplingRenderer;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainSides;

/**
 * No coupling is drawn between two carriages when either of them, or the gap between them, spans a portal within one
 * dimension: Create would draw it in a straight line through the world between the portal's two ends. A record with no
 * copy makes Create skip the pair.
 */
@Mixin(value = CarriageCouplingRenderer.class, remap = false)
public class MixinCarriageCouplingRenderer_TrainSides {
    @WrapOperation(
        method = "renderAll",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/Carriage;getDimensional(Lnet/minecraft/world/level/Level;)Lcom/simibubi/create/content/trains/entity/Carriage$DimensionalCarriageEntity;"
        )
    )
    private static Carriage.DimensionalCarriageEntity ip_noCouplingThroughPortal(final Carriage carriage, final Level level,
                                                                                 final Operation<Carriage.DimensionalCarriageEntity> original) {
        final Train train = carriage.train;
        if (train != null && IPCreateTrainSides.couplingSplit(train, train.carriages.indexOf(carriage))) {
            return carriage.new DimensionalCarriageEntity();
        }
        return original.call(carriage, level);
    }
}
