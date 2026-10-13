/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The end of a physics staff beam, in plot coordinates of its ship; moved to the new plot when the dragged ship moved to
 * another dimension ({@code IPPhysicsStaffPortalsClient}), so it is not interpolated between the two plots.
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler$PhysicsBeam", remap = false)
public interface IPPhysicsStaffBeamAccessor {
    @Accessor(value = "end", remap = false)
    Vec3 ip_getEnd();

    @Accessor(value = "end", remap = false)
    void ip_setEnd(Vec3 end);

    @Accessor(value = "previousEnd", remap = false)
    Vec3 ip_getPreviousEnd();

    @Accessor(value = "previousEnd", remap = false)
    void ip_setPreviousEnd(Vec3 end);

    @Accessor(value = "serverEnd", remap = false)
    Vec3 ip_getServerEnd();

    @Accessor(value = "serverEnd", remap = false)
    void ip_setServerEnd(Vec3 end);
}
