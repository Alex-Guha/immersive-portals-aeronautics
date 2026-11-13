/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleHandoffLatch;

/**
 * Create: Pocket Sized (Pym) keeps each ship's client scale by UUID and puts it into the ship every client tick, twice:
 * into Sable's interpolation snapshot before the pose is interpolated ({@code ClientSubLevel.tick},
 * {@code pym$preserveScaleThroughSableInterpolation}) and into the pose after it ({@code ScaleMotion.enforceClientScale}),
 * both from {@code ScaleState.getClientScale(ClientSubLevel)}. The old copy a hand-off holds until its replacement is
 * drawn has the same UUID, so a ship resized at a scaled portal showed at its new size in the world it was leaving for
 * a frame or more (1-5 frames, by enforceClientScale; skipping only that one still left 3, by the
 * snapshot). The held copy answers with the scale it had when held.
 */
@Mixin(targets = "com.misterblusky9.pym.internal.scale.ScaleState", remap = false)
public class MixinPymScaleState_HeldCopy {
    @Inject(method = "getClientScale(Ldev/ryanhcode/sable/sublevel/ClientSubLevel;)D", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ip_heldCopyScale(final ClientSubLevel ship, final CallbackInfoReturnable<Double> cir) {
        final double held = IPStraddleHandoffLatch.heldScale(ship);
        if (!Double.isNaN(held)) {
            cir.setReturnValue(held);
        }
    }
}
