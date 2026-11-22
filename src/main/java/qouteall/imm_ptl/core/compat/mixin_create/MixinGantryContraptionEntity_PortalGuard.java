/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.IPPortalTeleportExempt;

/**
 * A gantry carriage belongs to its gantry shaft in its own level, like a controlled contraption to its controller
 * ({@link MixinControlledContraptionEntity_PortalGuard}): IP never carries it through a portal.
 */
@Mixin(value = GantryContraptionEntity.class, remap = false)
public class MixinGantryContraptionEntity_PortalGuard implements IPPortalTeleportExempt {
}
