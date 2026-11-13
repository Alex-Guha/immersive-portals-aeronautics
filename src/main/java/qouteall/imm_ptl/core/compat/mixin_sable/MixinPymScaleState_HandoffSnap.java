/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleHandoffLatch;

import java.util.UUID;

/**
 * Create: Pocket Sized (Pym) flags a ship for an interpolation snap when it is placed discontinuously
 * ({@code ScaleState.consumeClientInterpolationSnap}); its client tick then sets the ship to its newest snapshot and
 * clears the interpolation buffer ({@code ClientSubLevelMixin.pym$resetDiscontinuousPlacementInterpolation}). On the
 * new copy of a hand-off, playing on its old copy's timeline with the old copy's snapshots carried over, that threw the
 * carried snapshots away and put the ship at its newest one, ahead of the timeline: a jump of up to a few blocks, then a
 * hold. The same on the held old copy: a resize at the hand-off flags the ship, and when the
 * held copy ticked first it threw away the history the new copy was to be given, so no timeline was borrowed and the
 * new copy jolted (0.5x -> 1x crossings). The flag is still consumed, so it
 * does not fire later on either copy.
 */
@Mixin(targets = "com.misterblusky9.pym.internal.scale.ScaleState", remap = false)
public class MixinPymScaleState_HandoffSnap {
    @Inject(method = "consumeClientInterpolationSnap", at = @At("RETURN"), cancellable = true, require = 0)
    private static void ip_noSnapOnHandoff(final UUID ship, final CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && (IPStraddleHandoffLatch.onBorrowedTimeline(ship) || IPStraddleHandoffLatch.isPending(ship))) {
            cir.setReturnValue(false);
        }
    }
}
