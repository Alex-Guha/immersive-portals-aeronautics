/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import net.irisshaders.iris.pathways.HandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IPIrisTranslucentDepth;

/**
 * Just before Iris draws a translucent held item (past its checks for one), keeps the depth, so portal views never
 * cover the item ({@link IPIrisTranslucentDepth}).
 */
@Mixin(value = HandRenderer.class, remap = false)
public class MixinIrisHandRenderer_TranslucentDepth {
    @Inject(
        method = "renderTranslucent",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;setPhase(Lnet/irisshaders/iris/pipeline/WorldRenderingPhase;)V",
            ordinal = 0
        )
    )
    private void ip_beforeTranslucentHand(CallbackInfo ci) {
        IPIrisTranslucentDepth.beforeTranslucentHand();
    }
}
