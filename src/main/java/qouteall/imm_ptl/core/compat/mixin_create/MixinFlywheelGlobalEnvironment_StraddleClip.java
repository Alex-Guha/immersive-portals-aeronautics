/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.backend.engine.embed.GlobalEnvironment;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelStraddleClip;

/**
 * Instancing backend: world-level Flywheel draws reset the straddle cut uniform ({@link IPFlywheelStraddleClip}).
 */
@Mixin(value = GlobalEnvironment.class, remap = false)
public class MixinFlywheelGlobalEnvironment_StraddleClip {
    @Inject(method = "setupDraw", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_clearStraddleClip(final GlProgram program, final CallbackInfo ci) {
        IPFlywheelStraddleClip.onGlobalSetupDraw(program);
    }
}
