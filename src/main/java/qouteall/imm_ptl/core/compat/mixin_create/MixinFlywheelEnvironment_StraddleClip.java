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
    @Inject(method = "flush", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_registerEnvironment(final long ptr, final CallbackInfo ci) {
        IPFlywheelStraddleClip.onFlush((EmbeddedEnvironment) (Object) this);
    }

    @Inject(method = "setupDraw", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_setStraddleClip(final GlProgram program, final CallbackInfo ci) {
        IPFlywheelStraddleClip.onSetupDraw((EmbeddedEnvironment) (Object) this, program);
    }
}
