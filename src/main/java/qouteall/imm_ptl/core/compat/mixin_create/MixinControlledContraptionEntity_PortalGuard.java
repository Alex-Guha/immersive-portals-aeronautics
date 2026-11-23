/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.api.ImmPtlEntityExtension;
import qouteall.imm_ptl.core.compat.IPPortalTeleportExempt;

/**
 * IP never carries a contraption moved by a block (pulleys, elevators, pistons, bearings; their subclasses in other
 * mods too) through a portal. Such an entity belongs to its controller at {@code controllerPos} in its own level: carried
 * into another level, it finds no controller there and discards itself, its blocks with it, and the controller is left
 * running with nothing to move. Kept in its level, it passes behind the portal plane and stays whole.
 * <p>
 * Nor does IP treat it as touching a portal ({@code imm_ptl_canTeleportThroughPortal}, which IP's portal collision
 * asks too): IP draws a copy of an entity touching a portal on the portal's other side, and gives it collision there.
 * Nothing of such a contraption is on the other side, except an elevator car's own far copy, which the copy of IP's
 * projection covered and, being drawn on its own schedule, made the car jitter while it crossed.
 * <p>
 * Riders seated on such a contraption are not teleported by IP's client either ({@code IPSeatedCrossingHandled}):
 * an elevator car's riders are handed over at the plane by {@code IPElevatorPortals}.
 */
@Mixin(value = ControlledContraptionEntity.class, remap = false)
public class MixinControlledContraptionEntity_PortalGuard implements IPPortalTeleportExempt, ImmPtlEntityExtension,
    qouteall.imm_ptl.core.compat.IPSeatedCrossingHandled {
    @Override
    public boolean imm_ptl_canTeleportThroughPortal(Entity portal) {
        return false;
    }
}
