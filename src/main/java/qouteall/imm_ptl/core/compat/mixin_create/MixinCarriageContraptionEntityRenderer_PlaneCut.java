/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageVanillaDraw;

/**
 * A crossing carriage Create draws as a plain entity, Flywheel's backend being off: everything it draws goes through
 * the buffers of this one method, block structure, block entities, actors and bogeys, so that is where it is cut at
 * the portal plane ({@link IPCreateCarriageVanillaDraw}).
 */
@Mixin(value = CarriageContraptionEntityRenderer.class, remap = false)
public class MixinCarriageContraptionEntityRenderer_PlaneCut {
    private static final String RENDER =
        "render(Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @Inject(method = RENDER, at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ip_skipHiddenCopy(final CarriageContraptionEntity entity, final float yaw, final float partialTicks, final PoseStack pose,
                                   final MultiBufferSource buffers, final int overlay, final CallbackInfo ci) {
        if (IPCreateCarriageVanillaDraw.isSkipped(entity)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = RENDER, at = @At("HEAD"), argsOnly = true, require = 0, expect = 0)
    private MultiBufferSource ip_cutAtPlane(final MultiBufferSource buffers, @Local(argsOnly = true) final CarriageContraptionEntity entity) {
        return IPCreateCarriageVanillaDraw.cutNear(entity, buffers);
    }

    @Inject(method = RENDER, at = @At("RETURN"), require = 0, expect = 0)
    private void ip_finishCut(final CallbackInfo ci) {
        IPCreateCarriageVanillaDraw.afterRender();
    }
}
