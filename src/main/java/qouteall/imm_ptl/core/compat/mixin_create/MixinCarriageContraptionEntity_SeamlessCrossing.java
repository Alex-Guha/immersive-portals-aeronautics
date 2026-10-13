/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossing;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateClientCarriageDriver;

/**
 * Client side of a carriage crossing an IP portal:
 * <ul>
 *     <li>no purple portal particles at the seam of a copy cut at the portal plane;</li>
 *     <li>a copy Create has just created in the other dimension is aligned to the carriage's shared anchors at once.
 *     Create aligns a client copy only after its own sync data arrived ({@code dce.pointsInitialised}), a few ticks
 *     after it spawned; until then it stood at its spawn position and Flywheel drew it there, uncut (a clone one
 *     block ahead of the carriage, which vanished when the copy caught up). The copy driving the carriage
 *     ({@link IPCreateClientCarriageDriver}) keeps every dimension's anchors current, so the new copy can follow them.</li>
 * </ul>
 */
@Mixin(value = CarriageContraptionEntity.class, remap = false)
public class MixinCarriageContraptionEntity_SeamlessCrossing {
    @Inject(method = "spawnPortalParticles", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ip_noSeamParticles(final CallbackInfo ci) {
        if (IPCreateCarriageCrossingClient.crossingOf((CarriageContraptionEntity) (Object) this) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "tickContraption", at = @At("RETURN"), require = 0, expect = 0)
    private void ip_alignNewCopy(final CallbackInfo ci) {
        final CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        final Carriage carriage = self.getCarriage();
        if (IPCreateCarriageCrossing.STOCK || carriage == null || !self.level().isClientSide() || self.isRemoved()) {
            return;
        }
        final Carriage.DimensionalCarriageEntity dce = carriage.getDimensional(self.level());
        if (dce.pointsInitialised) {
            return;
        }
        if (!IPCreateCarriageCrossing.hasPointIn(carriage, self.level().dimension())) {
            // not reached by this client's carriage yet: follow the copy that is (a rider may sit on this one)
            self.xo = self.getX();
            self.yo = self.getY();
            self.zo = self.getZ();
            IPCreateCarriageCrossingClient.follow(self);
            return;
        }
        // the anchors are current only for dimensions the carriage's wheels are in (Create's client keeps the records
        // of dimensions it left, with their last anchors)
        if (dce.positionAnchor == null || dce.rotationAnchors.either(a -> a == null)
            || !IPCreateClientCarriageDriver.isDrivenByOther(carriage, self)) {
            return;
        }
        self.xo = self.getX();
        self.yo = self.getY();
        self.zo = self.getZ();
        dce.alignEntity(self);
        self.firstPositionUpdate = false;
        self.validForRender = true;
    }
}
