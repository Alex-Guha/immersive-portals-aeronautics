/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossing;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainSides;

@Mixin(value = CarriageBogey.class, remap = false)
public class MixinCarriageBogey_TrainSides {
    @Shadow
    AbstractBogeyBlock<?> type;
    @Shadow
    LerpedFloat yaw;
    @Shadow
    LerpedFloat pitch;

    /** Whether the last {@code updateAngles} took the angles from the wheel points (null before the first). */
    @Unique
    private Boolean ip_anglesFromPoints;

    @Inject(method = "getStress", at = @At("HEAD"), cancellable = true)
    private void ip_stressThroughSamePortal(final CallbackInfoReturnable<Double> cir) {
        final double stress = IPCreateTrainSides.splitBogeyStress((CarriageBogey) (Object) this, type.getWheelPointSpacing());
        if (!Double.isNaN(stress)) {
            cir.setReturnValue(stress);
        }
    }

    @ModifyReturnValue(method = "getDimension", at = @At("RETURN"))
    private ResourceKey<Level> ip_splitBySamePortal(final ResourceKey<Level> dimension) {
        return IPCreateTrainSides.bogeyDimension((CarriageBogey) (Object) this, dimension);
    }

    /**
     * {@code updateAngles} takes a bogey's yaw and pitch from its wheel points only when the copy updating it is in the
     * bogey's dimension, else from that copy's heading. One copy updates the shared angles per tick (on the client the
     * one driving the sync), so the bogey's own side could get the other copy's heading, turned by the portal's
     * rotation. While its wheels are on one side the angles are now always its real heading; a copy drawing a bogey of
     * another side turns it its own way ({@code IPCreateCarriageCrossingClient.bogeyAngle}).
     */
    @WrapOperation(
        method = "updateAngles",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;dimension()Lnet/minecraft/resources/ResourceKey;"),
        require = 0,
        expect = 0
    )
    private ResourceKey<Level> ip_ownHeading(final Level level, final Operation<ResourceKey<Level>> original) {
        final ResourceKey<Level> own = IPCreateCarriageCrossing.STOCK ? null : ((CarriageBogey) (Object) this).getDimension();
        return own != null ? own : original.call(level);
    }

    /**
     * While a bogey spans a portal its angles come from the updating copy's heading, and once its last wheel point is
     * through, from its wheel points again: on the far side of a rotating portal the two differ by the portal's
     * rotation, and Create interpolated from one to the other over a tick (the bogey turned 90 degrees
     * for a moment as the rear axle passed). When the source changes, the new angles show at once.
     */
    @Inject(method = "updateAngles", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_noTurnBetweenSides(final CarriageContraptionEntity entity, final double distanceMoved,
                                       final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (IPCreateCarriageCrossing.STOCK) {
            return;
        }
        final CarriageBogey self = (CarriageBogey) (Object) this;
        final boolean fromPoints = self.leading().edge != null && !self.carriage.train.derailed && self.getDimension() != null;
        if (ip_anglesFromPoints != null && ip_anglesFromPoints != fromPoints) {
            yaw.startWithValue(yaw.getValue());
            pitch.startWithValue(pitch.getValue());
        }
        ip_anglesFromPoints = fromPoints;
    }
}
