/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

/**
 * Replaces the local player's physics staff drag when its ship moved to another dimension, and moves its beam along
 * ({@code IPPhysicsStaffPortalsClient}).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public interface IPPhysicsStaffClientHandlerAccessor {
    @Accessor(value = "dragSession", remap = false)
    void ip_setDragSession(PhysicsStaffClientHandler.ClientDragSession session);

    @Accessor(value = "beams", remap = false)
    Object2ObjectMap<UUID, Object> ip_getBeams();
}
