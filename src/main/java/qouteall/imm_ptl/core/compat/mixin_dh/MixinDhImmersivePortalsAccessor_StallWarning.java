/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.common.wrappers.modAccessor.AbstractImmersivePortalsAccessorCommon_neoforge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DH injects at the head of IP's {@code RenderStates.updatePreRenderInfo} (once per frame) and stamps the time; while
 * rendering it warns in chat ("Immersive Portals Mixin Fail") when that stamp is over 5 s old. One long frame trips it
 * although the injection works: enabling a shader pack makes Iris compile the pipeline of each dimension the first time
 * it is drawn, inside a portal pass of that frame (about 7 s for Complementary). The stamp is -1 until DH's injection
 * has run once, so a stamp set means it is applied and the warning is skipped; a missing injection still warns.
 */
@Mixin(value = AbstractImmersivePortalsAccessorCommon_neoforge.class, remap = false)
public class MixinDhImmersivePortalsAccessor_StallWarning {
    @Inject(
        method = "logWarningIfMixinNotRunRecently",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_skipWarningAfterLongFrame(CallbackInfo ci) {
        if (AbstractImmersivePortalsAccessorCommon_neoforge.lastUpdatedMsTime != -1L) {
            ci.cancel();
        }
    }
}
