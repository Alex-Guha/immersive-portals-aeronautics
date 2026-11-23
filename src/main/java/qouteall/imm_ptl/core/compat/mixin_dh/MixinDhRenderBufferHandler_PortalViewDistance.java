/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.IPFrustumCapture;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Portal view distance ({@link IPPortalViewDistance}) with a shader pack: in a portal view with a cut, DH runs its
 * pass but culls every LOD. The pack composites DH's depth textures in every pass, and only DH's own pass clears
 * them; skipping the pass (as without a pack) left the depth of DH's last draw in that dimension, which BSL showed as
 * pale LOD terrain past the cut. The transparent pass reuses this render list.
 * <p>
 * In every portal pass, DH's frustum is the portal camera's whole view, so LOD sections that IP's inner culling puts
 * outside the portal's opening are dropped too, as {@code MixinSodiumViewport} does for terrain. The culler is
 * Sodium's, updated per pass and empty in the shadow pass.
 * <p>
 * In the player's own view, LOD sections wholly hidden by the portal nearest the camera (IP's outer culling, as
 * {@code MixinSodiumSectionCollector} for terrain) are dropped: the portal's view paints over them. Not in the shadow
 * pass (what is behind a portal still casts shadows in front of it).
 * <p>
 * With a frustum captured (F3+U, {@link IPFrustumCapture}) the culler is the captured camera's, so LODs follow the
 * capture as terrain does: after the player walks through a portal, their own world keeps only the LOD sections inside
 * that portal's opening as seen from the captured spot (the inner culling, outside a portal pass). DH builds that
 * honour vanilla's capture also cull by the captured frustum itself, which IP carries through the portals; other DH
 * builds keep their live frustum, and only IP's part of the culling follows the capture.
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
        if (!original.call(frustum, minX, minZ, width, detailLevel)) {
            return false;
        }
        if (SodiumInterface.frustumCuller != null && (PortalRendering.isRendering() ||
            IPFrustumCapture.rimPortal() != null && !IrisInterface.invoker.isRenderingShadowMap())) {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null || !SodiumInterface.frustumCuller.canInnerCullWorldBox(
                minX, level.getMinBuildHeight(), minZ, minX + width, level.getMaxBuildHeight(), minZ + width
            );
        }
        if (!PortalRendering.isRendering() && SodiumInterface.frustumCuller != null &&
            SodiumInterface.frustumCuller.getOuterCulling() != null &&
            !IrisInterface.invoker.isRenderingShadowMap()) {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null || !SodiumInterface.frustumCuller.canOuterCullWorldBox(
                minX, level.getMinBuildHeight(), minZ, minX + width, level.getMaxBuildHeight(), minZ + width
            );
        }
        return true;
    }
}
