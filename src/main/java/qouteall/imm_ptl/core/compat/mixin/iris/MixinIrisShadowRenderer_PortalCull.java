/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisShadowCull;

/**
 * In a portal view, the frustum Iris culls shadow casters by is cut to the portal ({@link IPIrisShadowCull}).
 * {@code createShadowFrustum} runs for terrain and again for entities in every shadow pass; its one
 * {@code Matrix4fc.mul} is {@code gbufferProjection * gbufferModelView}, only on the advanced culling path.
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public class MixinIrisShadowRenderer_PortalCull {
    @ModifyExpressionValue(
        method = "createShadowFrustum",
        at = @At(
            value = "INVOKE",
            target = "Lorg/joml/Matrix4fc;mul(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;"
        )
    )
    private Matrix4f ip_cropToPortal(Matrix4f viewProjection) {
        return IPIrisShadowCull.cropToPortal(viewProjection);
    }
}
