/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer;
import net.createmod.catnip.animation.LerpedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/**
 * A bogey's yaw and pitch in {@code translateBogey} as the copy placing it should show them
 * ({@link IPCreateCarriageCrossingClient#bogeyAngle}).
 */
@Mixin(value = CarriageContraptionEntityRenderer.class, remap = false)
public class MixinCarriageContraptionEntityRenderer_BogeySide {
    @WrapOperation(
        method = "translateBogey",
        at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/animation/LerpedFloat;getValue(F)F", ordinal = 0),
        require = 0,
        expect = 0
    )
    private static float ip_yawOnThisSide(final LerpedFloat value, final float partialTicks, final Operation<Float> original,
                                          @Local(argsOnly = true) final CarriageBogey bogey,
                                          @Local(argsOnly = true, ordinal = 0) final float viewYRot) {
        return IPCreateCarriageCrossingClient.bogeyAngle(bogey, original.call(value, partialTicks), 90.0F + viewYRot);
    }

    @WrapOperation(
        method = "translateBogey",
        at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/animation/LerpedFloat;getValue(F)F", ordinal = 1),
        require = 0,
        expect = 0
    )
    private static float ip_pitchOnThisSide(final LerpedFloat value, final float partialTicks, final Operation<Float> original,
                                            @Local(argsOnly = true) final CarriageBogey bogey) {
        return IPCreateCarriageCrossingClient.bogeyAngle(bogey, original.call(value, partialTicks), 0.0F);
    }
}
