/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.outliner.Outline;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortalsClient;

/**
 * Draws Create Aeronautics' (Simulated) physics staff selection box on the far half of a straddling ship
 * ({@link IPPhysicsStaffPortalsClient#renderSelection}).
 */
@Mixin(targets = "net.createmod.catnip.outliner.Outliner", remap = false)
public class MixinOutliner_StaffSelection {
    private static final String STAFF_SELECTION = "physicsStaffSelection";

    @WrapOperation(
        method = "lambda$renderOutlines$0",
        at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/outliner/Outline;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/createmod/catnip/render/SuperRenderTypeBuffer;Lnet/minecraft/world/phys/Vec3;F)V"),
        require = 0
    )
    private static void ip_staffSelection(final Outline outline, final PoseStack poseStack, final SuperRenderTypeBuffer buffer, final Vec3 camera,
                                          final float partialTick, final Operation<Void> original, @Local(argsOnly = true) final Object key) {
        if (!STAFF_SELECTION.equals(key)) {
            original.call(outline, poseStack, buffer, camera, partialTick);
            return;
        }
        IPPhysicsStaffPortalsClient.renderSelection(camera, poseStack, () -> original.call(outline, poseStack, buffer, camera, partialTick));
    }
}
