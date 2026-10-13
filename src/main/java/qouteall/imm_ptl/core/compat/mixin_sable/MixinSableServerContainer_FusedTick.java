/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The approach (the first call per tick drives every level) follows IPSable 0.5.0's IplFusedStepMixin by r2smith141
 * (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_physics.IPFusedPhysicsStep;

/**
 * With our ipa_rapier backend, the first server-side sub-level container tick in a server tick runs every level's
 * container tick, phase by phase, so their physics can share one native world ({@link IPFusedPhysicsStep}); the
 * other levels' own container ticks are skipped. Without our backend this returns immediately.
 */
@Mixin(
    value = ServerSubLevelContainer.class,
    remap = false
)
public class MixinSableServerContainer_FusedTick {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ip_fuseContainerTick(CallbackInfo ci) {
        if (IPFusedPhysicsStep.onContainerTick((ServerSubLevelContainer) (Object) this)) {
            ci.cancel();
        }
    }
}
