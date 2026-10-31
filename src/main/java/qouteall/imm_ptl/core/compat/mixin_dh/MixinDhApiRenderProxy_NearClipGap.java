/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.util.RenderUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalNearClip;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * {@code DhApiRenderProxy.getNearClipPlaneDistanceInBlocks} in portal passes: no farther than the
 * destination's drawn terrain reaches ({@link IPDhPortalNearClip}, with room for a fade up to 1.5
 * times the distance), so LODs seen through a portal do not leave a gap where the destination's
 * loaded chunks end. This is the DH API path used when an Iris shader pack renders the LODs; the
 * stock terrain pass is handled by {@link MixinDhTerrainShader_NearClipGap}, which also says why the
 * player's own view keeps DH's value.
 * <p>
 * Never 0, even with no terrain drawn yet: Iris builds {@code dhProjection} from this value. With a near plane of 0
 * every point projects to depth 1, so a pack that projects LODs with it (Sundial Lite) draws none, and
 * {@code dhProjectionInverse} is not finite, so its sky rays are NaN and the sky is black.
 */
@Mixin(value = DhApiRenderProxy.class, remap = false)
public class MixinDhApiRenderProxy_NearClipGap {
    @Unique
    private static final float ip_MIN_NEAR_CLIP = 2;

    @Inject(
        method = "getNearClipPlaneDistanceInBlocks",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_portalViewLodNearClipForShaders(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!PortalRendering.isRendering()) {
            return;
        }

        double covered = IPDhPortalNearClip.coveredDistance() / 1.5;
        cir.setReturnValue((float) Math.max(ip_MIN_NEAR_CLIP, Math.min(RenderUtil.getNearClipPlaneInBlocks(), covered)));
    }
}
