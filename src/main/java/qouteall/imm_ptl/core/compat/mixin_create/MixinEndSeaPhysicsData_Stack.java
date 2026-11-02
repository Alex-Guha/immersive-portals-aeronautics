/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.simulated_team.simulated.content.end_sea.EndSeaPhysics;
import dev.simulated_team.simulated.content.end_sea.EndSeaPhysicsData;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;

/**
 * No End Sea past a dimension stack's floor or ceiling: that side leads to the next dimension. Physics and both renderers
 * read the sea from here. Simulated gives the Aether a sea at y 1, which holds a ship's centre of mass on a floor at y 0
 * (docs/lessons/dimension-stack.md).
 */
@Mixin(value = EndSeaPhysicsData.class, remap = false)
public abstract class MixinEndSeaPhysicsData_Stack {
    @Inject(method = "of", at = @At("RETURN"), cancellable = true)
    private static void ip_noSeaPastStack(final Level level, final CallbackInfoReturnable<EndSeaPhysics> cir) {
        final EndSeaPhysics sea = cir.getReturnValue();
        if (sea != null && VerticalConnectingPortal.getConnectingPortal(level, sea.depthGradient() < 0.0
            ? VerticalConnectingPortal.ConnectorType.ceil : VerticalConnectingPortal.ConnectorType.floor) != null) {
            cir.setReturnValue(null);
        }
    }
}
