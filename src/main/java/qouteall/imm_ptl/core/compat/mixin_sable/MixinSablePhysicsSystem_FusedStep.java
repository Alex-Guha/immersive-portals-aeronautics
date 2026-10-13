/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplFusedStepMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_physics.IPFusedPhysicsStep;

/**
 * While {@link IPFusedPhysicsStep} runs every level's physics tick, each level's substep loop is collected instead
 * of run, so the loops can run interleaved over the one shared native world. Otherwise returns immediately.
 */
@Mixin(
    value = SubLevelPhysicsSystem.class,
    remap = false
)
public class MixinSablePhysicsSystem_FusedStep {
    @Inject(method = "tickPipelinePhysics", at = @At("HEAD"), cancellable = true)
    private void ip_collectPipelinePhysics(ServerSubLevelContainer container, CallbackInfo ci) {
        if (IPFusedPhysicsStep.collect((SubLevelPhysicsSystem) (Object) this, container)) {
            ci.cancel();
        }
    }
}
