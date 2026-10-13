/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

/**
 * A Create Aeronautics (Simulated) physics staff drag ({@code IPPhysicsStaffPortals}). The vectors are the session's
 * own, mutable.
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler$DragSession", remap = false)
public interface IPPhysicsStaffDragSessionAccessor {
    @Accessor(value = "playerUUID", remap = false)
    UUID ip_getPlayerUUID();

    @Accessor(value = "subLevel", remap = false)
    ServerSubLevel ip_getSubLevel();

    @Accessor(value = "plotAnchor", remap = false)
    Vector3d ip_getPlotAnchor();

    @Accessor(value = "playerRelativeGoal", remap = false)
    Vector3d ip_getPlayerRelativeGoal();

    @Accessor(value = "orientation", remap = false)
    Quaterniond ip_getOrientation();
}
