/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainSides;

/**
 * A train with carriages on both sides of a portal within one dimension ({@link IPCreateTrainSides}). Create measures
 * coupling stress and collisions in straight lines within a dimension; across such a portal those lines would run
 * through the world between its two ends:
 * <ul>
 *     <li>no coupling stress across a gap that spans the portal (as across dimensions);</li>
 *     <li>a carriage spanning the portal does not look for collisions, and another train's collision line is broken
 *     at the portal (no segment along such a carriage, none across such a gap).</li>
 * </ul>
 */
@Mixin(value = Train.class, remap = false)
public class MixinTrain_TrainSides {
    /** A point with no edge: Create skips the carriage it stands for in its collision search. */
    @Unique
    private static final TravellingPoint IP_SKIP = new TravellingPoint();

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/Carriage;getDimensionalIfPresent(Lnet/minecraft/resources/ResourceKey;)Lcom/simibubi/create/content/trains/entity/Carriage$DimensionalCarriageEntity;",
            ordinal = 0
        )
    )
    private Carriage.DimensionalCarriageEntity ip_noStressThroughPortal(final Carriage carriage, final ResourceKey<Level> dimension,
                                                                         final Operation<Carriage.DimensionalCarriageEntity> original) {
        final Train train = (Train) (Object) this;
        if (IPCreateTrainSides.gapCrosses(train, train.carriages.indexOf(carriage))) {
            return null;
        }
        return original.call(carriage, dimension);
    }

    @Inject(method = "collideWithOtherTrains", at = @At("HEAD"), cancellable = true)
    private void ip_noCollisionLineThroughPortal(final Level level, final Carriage carriage, final CallbackInfo ci) {
        if (IPCreateTrainSides.isCrossing(carriage)) {
            ci.cancel();
        }
    }

    @WrapOperation(
        method = "findCollidingTrain",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/Carriage;getLeadingPoint()Lcom/simibubi/create/content/trains/entity/TravellingPoint;"
        )
    )
    private TravellingPoint ip_breakLineAtPortal(final Carriage otherCarriage, final Operation<TravellingPoint> original,
                                                 @Local(name = "betweenBits") final boolean betweenBits,
                                                 @Local(name = "lastPoint") final LocalRef<Vec3> lastPoint) {
        final Train train = otherCarriage.train;
        if (betweenBits) {
            if (train != null && IPCreateTrainSides.gapCrosses(train, train.carriages.indexOf(otherCarriage))) {
                return IP_SKIP;
            }
        } else if (IPCreateTrainSides.isCrossing(otherCarriage)) {
            // no segment along it, and none from it to the next carriage
            lastPoint.set(null);
            return IP_SKIP;
        }
        return original.call(otherCarriage);
    }
}
