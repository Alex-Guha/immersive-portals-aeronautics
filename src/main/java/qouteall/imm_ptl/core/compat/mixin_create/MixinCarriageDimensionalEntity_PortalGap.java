/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossing;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/**
 * Places a carriage copy that crosses an IP portal so that it lines up with the other dimension's copy through the
 * portal ({@link IPCreateCarriageCrossing}: Create's portal step has length 0, the portal's has about one block;
 * the difference is closed gradually over the track on both sides).
 * Wraps the {@code setPos(positionAnchor)} of {@code Carriage.DimensionalCarriageEntity.alignEntity}, on both sides;
 * on the client the crossing is also handed to the rendering. A copy Create creates mid-crossing is spawned at the
 * shifted position too: {@code createEntity} adds it at the unshifted anchor, and the spawn packet goes out at once,
 * before the {@code alignEntity} that follows.
 */
@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity", remap = false)
public class MixinCarriageDimensionalEntity_PortalGap {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Shadow
    public Vec3 positionAnchor;

    /**
     * A copy is never created at a non-finite position (Create would throw "Entity has invalid position" and crash the
     * server); this also lets a save with such an anchor load (it is recomputed on the carriage's next travel).
     */
    @Inject(method = "createEntity", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ip_noCopyAtInvalidPosition(final net.minecraft.world.level.Level level, final boolean loadPassengers,
                                            final CallbackInfo ci) {
        if (positionAnchor != null && !IPCreateCarriageCrossing.isFinite(positionAnchor)) {
            ip_LOGGER.warn("[IPTrainCrossing] not creating a carriage copy in {} at invalid position {}",
                level.dimension().location(), positionAnchor);
            ci.cancel();
        }
    }

    @WrapOperation(
        method = "alignEntity",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;setPos(Lnet/minecraft/world/phys/Vec3;)V"
        ),
        require = 0,
        expect = 0
    )
    private void ip_closePortalGap(final CarriageContraptionEntity entity, final Vec3 position, final Operation<Void> original) {
        final Carriage.DimensionalCarriageEntity dce = (Carriage.DimensionalCarriageEntity) (Object) this;
        final Vec3 shift = IPCreateCarriageCrossing.shift(entity, dce);
        qouteall.imm_ptl.core.compat.create_integration.IPCreateCrossingTrace.shift(entity, shift);
        original.call(entity, position == null ? null : position.add(shift));
        if (entity.level().isClientSide()) {
            IPCreateCarriageCrossingClient.onAligned(entity, IPCreateCarriageCrossing.of(entity, dce));
        }
    }

    /** Client: a copy whose dimension holds none of its carriage's wheel points follows the copy that has them. */
    @Inject(method = "alignEntity", at = @At("RETURN"), require = 0, expect = 0)
    private void ip_followThroughPortal(final CarriageContraptionEntity entity, final CallbackInfo ci) {
        if (entity.level().isClientSide()) {
            IPCreateCarriageCrossingClient.follow(entity);
        }
    }

    @WrapOperation(
        method = "createEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"
        ),
        require = 0,
        expect = 0
    )
    private boolean ip_spawnShifted(final ServerLevel level, final Entity entity, final Operation<Boolean> original) {
        if (entity instanceof final CarriageContraptionEntity carriageEntity) {
            final Vec3 shift = IPCreateCarriageCrossing.shift(carriageEntity, (Carriage.DimensionalCarriageEntity) (Object) this);
            if (shift != Vec3.ZERO) {
                entity.moveTo(entity.position().add(shift));
            }
        }
        return original.call(level, entity);
    }
}
