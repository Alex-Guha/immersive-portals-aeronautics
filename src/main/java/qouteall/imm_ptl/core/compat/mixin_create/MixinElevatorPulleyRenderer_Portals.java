/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyRenderer;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortalsClient;

/**
 * Create's vanilla elevator pulley renderer (without Flywheel): of a pulley whose shaft runs through a portal, the
 * magnet and the belt past the portal plane are not drawn on its side (the far side draws them,
 * {@link IPElevatorPortalsClient#drawFarPulleyParts}), like its Flywheel visual ({@code MixinElevatorPulleyVisual_Portals}):
 * {@link IPElevatorPortalsClient#vanillaPartAt}.
 */
@Mixin(value = ElevatorPulleyRenderer.class, remap = false)
public class MixinElevatorPulleyRenderer_Portals {
    @WrapOperation(
        method = "renderSafe(Lcom/simibubi/create/content/contraptions/elevator/ElevatorPulleyBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/contraptions/pulley/AbstractPulleyRenderer;renderAt(Lnet/minecraft/world/level/LevelAccessor;Lnet/createmod/catnip/render/SuperByteBuffer;FLnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"
        )
    )
    private void ip$notPastPlane(LevelAccessor world, SuperByteBuffer partial, float offset, BlockPos pulleyPos, PoseStack ms,
                                 VertexConsumer buffer, Operation<Void> original,
                                 @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) ElevatorPulleyBlockEntity pulley) {
        if (!IPElevatorPortalsClient.vanillaPartAt(pulley, world, partial, offset, pulleyPos, ms, buffer)) {
            original.call(world, partial, offset, pulleyPos, ms, buffer);
        }
    }
}
