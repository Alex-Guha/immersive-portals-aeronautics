/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalBeltRiders;

/** Riders carried across the portal blocks at a belt's ends ({@link IPCreatePortalBeltRiders}). */
@Mixin(value = BeltBlockEntity.class, remap = false)
public abstract class MixinBeltBlockEntity_PortalRiders {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ip$carryThroughPortals(final CallbackInfo ci) {
        final BeltBlockEntity belt = (BeltBlockEntity) (Object) this;
        if (belt.isController() && !belt.isVirtual()) {
            IPCreatePortalBeltRiders.carry(belt);
        }
    }
}
