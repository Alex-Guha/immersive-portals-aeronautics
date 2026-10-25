/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionVisual;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/**
 * Tells {@code translateBogey} which carriage copy places the bogey ({@link IPCreateCarriageCrossingClient#bogeyAngle}).
 */
@Mixin(value = CarriageContraptionVisual.class, remap = false)
public class MixinCarriageContraptionVisual_BogeySide {
    @Shadow
    @Final
    private CarriageContraption contraption;

    @WrapOperation(
        method = "animate",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntityRenderer;translateBogey(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/simibubi/create/content/trains/entity/CarriageBogey;IFFF)V"
        ),
        require = 0,
        expect = 0
    )
    private void ip_bogeyOfThisCopy(final PoseStack poseStack, final CarriageBogey bogey, final int bogeySpacing,
                                    final float viewYRot, final float viewXRot, final float partialTick,
                                    final Operation<Void> original) {
        IPCreateCarriageCrossingClient.beginBogey(contraption.entity);
        try {
            original.call(poseStack, bogey, bogeySpacing, viewYRot, viewXRot, partialTick);
        } finally {
            IPCreateCarriageCrossingClient.endBogey();
        }
    }
}
