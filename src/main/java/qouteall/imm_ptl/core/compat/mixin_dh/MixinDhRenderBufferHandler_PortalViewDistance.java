/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

/**
 * Portal view distance ({@link IPPortalViewDistance}) with a shader pack: in a portal view with a cut, DH runs its
 * pass but culls every LOD. The pack composites DH's depth textures in every pass, and only DH's own pass clears
 * them; skipping the pass (as without a pack) left the depth of DH's last draw in that dimension, which BSL showed as
 * pale LOD terrain past the cut. The transparent pass reuses this render list.
 */
@Mixin(value = RenderBufferHandler.class, remap = false)
public class MixinDhRenderBufferHandler_PortalViewDistance {
    @WrapOperation(
        method = "buildRenderList",
        at = @At(
            value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/api/interfaces/override/rendering/IDhApiCullingFrustum;intersects(IIII)Z"
        ),
        require = 0
    )
    private boolean ip_cullAllInPortalViewWithCut(
        IDhApiCullingFrustum frustum, int minX, int minZ, int width, int detailLevel, Operation<Boolean> original
    ) {
        if (IPPortalViewDistance.hasCut()) {
            return false;
        }
        return original.call(frustum, minX, minZ, width, detailLevel);
    }
}
