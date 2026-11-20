/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.iris_integration.IPPortalViewShadows;

/**
 * Portal View Shadows ({@link IPPortalViewShadows}): in a portal view, the pack's switches for what the shadow pass
 * draws read false for what the option leaves out. Each is read once in {@code renderShadows}, right before its draw
 * (terrain's three opaque layers, entities or else the player, block entities or else light-emitting ones, translucent
 * terrain).
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public class MixinIrisShadowRenderer_PortalViewShadows {
    @ModifyExpressionValue(
        method = "renderShadows",
        at = {
            @At(value = "FIELD", target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;shouldRenderTerrain:Z"),
            @At(value = "FIELD", target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;shouldRenderTranslucent:Z")
        }
    )
    private boolean ip_portalViewDrawsTerrain(boolean packDraws) {
        return IPPortalViewShadows.drawsTerrain(packDraws);
    }

    @ModifyExpressionValue(
        method = "renderShadows",
        at = {
            @At(value = "FIELD", target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;shouldRenderEntities:Z"),
            @At(value = "FIELD", target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;shouldRenderPlayer:Z"),
            @At(value = "FIELD", target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;shouldRenderBlockEntities:Z"),
            @At(value = "FIELD", target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;shouldRenderLightBlockEntities:Z")
        }
    )
    private boolean ip_portalViewDrawsEntities(boolean packDraws) {
        return IPPortalViewShadows.drawsEntities(packDraws);
    }
}
