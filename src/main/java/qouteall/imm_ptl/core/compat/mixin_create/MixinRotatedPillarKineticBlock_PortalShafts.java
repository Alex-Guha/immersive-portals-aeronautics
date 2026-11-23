/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalShafts;

/**
 * A shaft across a portal counts as a neighbour for the axis of a kinetic block placed next to the portal, so a shaft
 * placed against a half shaft coming out of a portal continues it ({@link IPCreatePortalShafts#preferredAxis}).
 */
@Mixin(value = RotatedPillarKineticBlock.class, remap = false)
public class MixinRotatedPillarKineticBlock_PortalShafts {
    @ModifyReturnValue(method = "getPreferredAxis", at = @At("RETURN"), require = 1)
    private static Direction.Axis ip_shaftAcrossPortal(final Direction.Axis original, final BlockPlaceContext context) {
        return IPCreatePortalShafts.preferredAxis(original, context.getLevel(), context.getClickedPos());
    }
}
