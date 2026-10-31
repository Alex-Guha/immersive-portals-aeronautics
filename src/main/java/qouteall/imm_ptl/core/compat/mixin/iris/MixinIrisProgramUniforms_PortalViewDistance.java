/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.gl.program.ProgramUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

/**
 * Portal view distance ({@link IPPortalViewDistance}): Iris refreshes a program's per-frame uniforms ({@code far},
 * {@code dhRenderDistance}) once per frame, at the program's first use. A portal into the player's own dimension uses
 * the player's programs, so its view kept the player's distances. A program's per-frame uniforms are refreshed again
 * whenever the cut, or an approach ramp's LOD fog, it is used under differs from the last one
 * ({@link IPPortalViewDistance#uniformKey}); with the option off that never happens.
 */
@Mixin(value = ProgramUniforms.class, remap = false)
public class MixinIrisProgramUniforms_PortalViewDistance {
    @Shadow
    int lastFrame;

    @Unique
    private double ip_lastKey = Double.NaN;

    @Inject(method = "update", at = @At("HEAD"))
    private void ip_refreshOnCutChange(CallbackInfo ci) {
        double key = IPPortalViewDistance.uniformKey();
        if (Double.compare(key, ip_lastKey) != 0) {
            ip_lastKey = key;
            lastFrame = -1;
        }
    }
}
