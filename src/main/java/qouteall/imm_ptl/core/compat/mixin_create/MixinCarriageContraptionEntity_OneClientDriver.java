/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageSyncData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateClientCarriageDriver;

/**
 * In {@code CarriageContraptionEntity.tickContraption} (client branch), lets only the copy
 * elected by {@link IPCreateClientCarriageDriver} apply the carriage sync
 * ({@code CarriageSyncData.approach}), recompute the shared anchors
 * ({@code Carriage.updateContraptionAnchors}) and advance the wheel angles
 * ({@code CarriageBogey.updateAngles}). Other copies of the same carriage, ticked by IP in
 * portal-side worlds, still align themselves to the shared anchors.
 */
@Mixin(value = CarriageContraptionEntity.class, remap = false)
public class MixinCarriageContraptionEntity_OneClientDriver {
    @WrapOperation(
        method = "tickContraption",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageSyncData;approach(Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;Lcom/simibubi/create/content/trains/entity/Carriage;F)V"
        ),
        require = 0,
        expect = 0
    )
    private void ip_approachOnlyAsDriver(
        CarriageSyncData data, CarriageContraptionEntity entity, Carriage carriage, float partial,
        Operation<Void> original
    ) {
        if (IPCreateClientCarriageDriver.drives(carriage, entity)) {
            original.call(data, entity, carriage, partial);
        }
    }

    @WrapOperation(
        method = "tickContraption",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/Carriage;updateContraptionAnchors()V"
        ),
        require = 0,
        expect = 0
    )
    private void ip_anchorsOnlyAsDriver(Carriage carriage, Operation<Void> original) {
        if (IPCreateClientCarriageDriver.drives(carriage, (CarriageContraptionEntity) (Object) this)) {
            original.call(carriage);
        }
    }

    @WrapOperation(
        method = "tickContraption",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageBogey;updateAngles(Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;D)V"
        ),
        require = 0,
        expect = 0
    )
    private void ip_wheelsOnlyAsDriver(
        CarriageBogey bogey, CarriageContraptionEntity entity, double distanceMoved, Operation<Void> original
    ) {
        if (IPCreateClientCarriageDriver.drives(entity.getCarriage(), entity)) {
            original.call(bogey, entity, distanceMoved);
        }
    }
}
