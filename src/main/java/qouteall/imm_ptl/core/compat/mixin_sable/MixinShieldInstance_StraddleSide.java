/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleShields;

import java.util.Optional;

/**
 * Shield Generators: a shield of a straddling ship (or its mirror in the destination) is hit only on the side of the
 * portal plane where that part of the ship is ({@link IPStraddleShields#keepSide}).
 */
@Mixin(targets = "com.anton.shieldgenerators.ShieldRegistry$ShieldInstance", remap = false)
public class MixinShieldInstance_StraddleSide {
    @Inject(method = "clipShell", at = @At("RETURN"), cancellable = true, require = 0)
    private void ip_keepSide(final Vec3 start, final Vec3 end, final CallbackInfoReturnable<Optional<?>> cir) {
        final Optional<?> hit = cir.getReturnValue();
        final Optional<?> kept = IPStraddleShields.keepSide(this, hit);
        if (kept != hit) {
            cir.setReturnValue(kept);
        }
    }
}
