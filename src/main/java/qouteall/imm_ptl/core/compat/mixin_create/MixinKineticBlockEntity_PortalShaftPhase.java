/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalShaftPhase;

/**
 * Shafts through portals, client: the angle offset Create draws a network driven through a portal with is shifted so
 * its shafts line up with the driving side's at the portal ({@link IPCreatePortalShaftPhase}).
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class MixinKineticBlockEntity_PortalShaftPhase {
    @Inject(method = "getRotationAngleOffset", at = @At("RETURN"), cancellable = true)
    private void ip$portalPhase(final Direction.Axis axis, final CallbackInfoReturnable<Integer> cir) {
        final int extra = IPCreatePortalShaftPhase.extraOffset((KineticBlockEntity) (Object) this);
        if (extra != 0) {
            cir.setReturnValue(cir.getReturnValueI() + extra);
        }
    }
}
