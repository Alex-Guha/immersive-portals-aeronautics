/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/**
 * The drags of a Create Aeronautics (Simulated) physics staff handler, by player ({@code IPPhysicsStaffPortals}).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler", remap = false)
public interface IPPhysicsStaffServerHandlerAccessor {
    @Accessor(value = "draggingSessions", remap = false)
    Map<UUID, Object> ip_getDraggingSessions();
}
