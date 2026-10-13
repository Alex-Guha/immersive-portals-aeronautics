/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalClip;

/** Patches DH's vertex shaders with IP's front clipping as their source is loaded. */
@Mixin(value = GlShader.class, remap = false)
public class MixinDhGlShader_PortalClipSource {
    @Inject(
        method = "loadFile",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private static void ip_addPortalClipping(String path, boolean absoluteFilePath, CallbackInfoReturnable<String> cir) {
        String source = cir.getReturnValue();
        String patched = IPDhPortalClip.patchShaderSource(path, source);
        if (patched != null && patched != source) {
            cir.setReturnValue(patched);
        }
    }
}
