/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelPortalClip;

/** Patches IP's front clipping into every Flywheel vertex program as its assembled source is handed to OpenGL. */
@Mixin(targets = "dev.engine_room.flywheel.backend.compile.core.Compilation", remap = false)
public class MixinFlywheelCompilation_PortalClipSource {
    @ModifyArg(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Ldev/engine_room/flywheel/backend/gl/GlCompat;safeShaderSource(ILjava/lang/CharSequence;)V"
        ),
        index = 1,
        require = 0,
        expect = 0
    )
    private CharSequence ip_addPortalClipping(CharSequence source) {
        return IPFlywheelPortalClip.patchVertexSource(source);
    }
}
