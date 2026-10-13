/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces {@code DhApiRenderProxy.getNearClipPlaneDistanceInBlocks} to 2 blocks so Distant Horizons
 * LODs do not leave a visible gap in front of the camera. This is the DH API path used when an Iris
 * shader pack renders the LODs; the stock terrain pass is handled by {@link MixinDhTerrainShader_NearClipGap}.
 */
@Mixin(value = DhApiRenderProxy.class, remap = false)
public class MixinDhApiRenderProxy_NearClipGap {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged = false;

    @Inject(
        method = "getNearClipPlaneDistanceInBlocks",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_removeLodNearClipGapForShaders(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!ip_logged) {
            ip_logged = true;
            ip_LOGGER.debug("[IPDhGapFix] DH API near-clip lowered to 2 blocks (covers the Iris shader path)");
        }

        cir.setReturnValue(2.0F);
    }
}
