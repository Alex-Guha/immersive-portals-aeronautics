/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortalsClient;

/**
 * The Create Aeronautics (Simulated) physics staff in the player's hand leans towards the dragged block, computed from
 * the ship's world position; for a ship in another world (across a portal) that is mapped to where the player sees it
 * ({@link IPPhysicsStaffPortalsClient#aimPoint}).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItemRenderer", remap = false)
public class MixinPhysicsStaffItemRenderer_Portals {
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/companion/math/Pose3dc;transformPosition(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"),
        require = 0
    )
    private Vector3d ip_aimPoint(final Pose3dc pose, final Vector3d point, final Operation<Vector3d> original) {
        return IPPhysicsStaffPortalsClient.aimPoint(original.call(pose, point));
    }
}
