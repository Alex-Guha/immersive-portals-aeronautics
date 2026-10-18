/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplFlywheelEmbeddedClipMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelStraddleClip;

/**
 * Registers Flywheel's embedded environments (Sable ships' embeddings among them) and, on the instancing backend,
 * sets the straddle cut of the environment about to draw ({@link IPFlywheelStraddleClip}).
 */
@Mixin(value = EmbeddedEnvironment.class, remap = false)
public class MixinFlywheelEnvironment_StraddleClip {
    /**
     * Known from creation: the first flush comes inside the draw, after the pass's planes were set, so an environment
     * created this frame (a carriage copy the server just spawned) was drawn its first frame with no cut and not hidden.
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 0, expect = 0)
    private void ip_registerNewEnvironment(final CallbackInfo ci) {
        IPFlywheelStraddleClip.onFlush((EmbeddedEnvironment) (Object) this);
    }

    @Inject(method = "flush", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_registerEnvironment(final long ptr, final CallbackInfo ci) {
        IPFlywheelStraddleClip.onFlush((EmbeddedEnvironment) (Object) this);
    }

    @Inject(method = "setupDraw", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_setStraddleClip(final GlProgram program, final CallbackInfo ci) {
        IPFlywheelStraddleClip.onSetupDraw((EmbeddedEnvironment) (Object) this, program);
    }
}
