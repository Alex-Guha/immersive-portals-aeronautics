/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_physics.IPPhysicsBackend;

/**
 * Loads our physics library in place of Sable's, from {@code Rapier3D}'s static initialiser, when
 * {@link IPPhysicsBackend#loadInPlaceOfSable} says so; otherwise Sable's own load runs.
 */
@Mixin(value = Rapier3D.class, remap = false)
public class MixinSableRapier3D_LoadLibrary {
    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    private static void ip_loadOurs(CallbackInfo ci) {
        if (IPPhysicsBackend.loadInPlaceOfSable()) {
            ci.cancel();
        }
    }
}
