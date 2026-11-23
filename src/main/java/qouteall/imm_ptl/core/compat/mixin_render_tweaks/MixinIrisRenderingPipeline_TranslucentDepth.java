/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IPIrisTranslucentDepth;

/** Records each pass's depth without translucents, which Iris has just copied, for portal areas behind translucents. */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline_TranslucentDepth {
    @Shadow
    @Final
    private RenderTargets renderTargets;

    @Inject(method = "beginTranslucents", at = @At("TAIL"))
    private void ip_recordPreTranslucentDepth(CallbackInfo ci) {
        IPIrisTranslucentDepth.onBeginTranslucents(renderTargets.getDepthTextureNoTranslucents().getTextureId());
    }
}
