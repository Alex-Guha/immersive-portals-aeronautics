/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortalsClient;

/**
 * An elevator car's far copy is drawn from where its car is and was ({@link IPElevatorPortalsClient#beforeLevelRender}
 * at the start of every level render, portal passes included), and put back again right before Minecraft draws it.
 * After it, the pulley's belt and magnet past the plane are drawn with it. Drawn through a portal from the
 * pulley's side its previous position had become its current one, so it was not interpolated between ticks and
 * stepped once a tick while the car moved smoothly.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_ElevatorFarCopy {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void ip$placeFarCopies(CallbackInfo ci) {
        IPElevatorPortalsClient.beforeLevelRender();
    }

    @Inject(method = "renderEntity", at = @At("TAIL"))
    private void ip$farPulleyParts(Entity entity, double camX, double camY, double camZ, float partialTick, PoseStack poseStack,
                                   MultiBufferSource buffers, CallbackInfo ci) {
        if (entity instanceof AbstractContraptionEntity contraption && IPElevatorPortals.farInfo(contraption) != null) {
            IPElevatorPortalsClient.drawFarPulleyParts(contraption, camX, camY, camZ, partialTick, poseStack, buffers);
        }
    }

    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void ip$farCopyInterpolates(Entity entity, double camX, double camY, double camZ, float partialTick, PoseStack poseStack,
                                        MultiBufferSource buffers, CallbackInfo ci) {
        if (entity instanceof AbstractContraptionEntity contraption && IPElevatorPortals.farInfo(contraption) != null) {
            IPElevatorPortals.applyTargets(contraption);
        }
    }
}
