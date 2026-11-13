/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.djefrey.colorwheel.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelStraddleClip;

/**
 * Colorwheel's embeddings get the straddle and carriage cuts the way Flywheel's do
 * ({@code MixinFlywheelEnvironment_StraddleClip}): registered when made and flushed, the instancing backend's plane set
 * when one is bound for a draw. Applied only with Colorwheel.
 */
@Mixin(value = EmbeddedEnvironment.class, remap = false)
public class MixinColorwheelEnvironment_StraddleClip {
    @Inject(method = "<init>(Ldev/djefrey/colorwheel/engine/ClrwlEngine;Ldev/djefrey/colorwheel/engine/ClrwlInstanceVisual;Lnet/minecraft/core/Vec3i;Ldev/djefrey/colorwheel/engine/embed/EmbeddedEnvironment;)V",
        at = @At("RETURN"), require = 0, expect = 0)
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
