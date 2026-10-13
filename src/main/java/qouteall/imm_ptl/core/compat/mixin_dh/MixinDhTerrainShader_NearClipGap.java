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
import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects the {@code RenderUtil.getNearClipPlaneInBlocks()} call inside
 * {@code GlDhTerrainShaderProgram_neoforge.fillUniformData} to 0 so the stock Distant Horizons
 * terrain pass has no near-clip gap in front of the camera (fade/SSAO distances stay stock).
 * The Iris shader path is covered by {@link MixinDhApiRenderProxy_NearClipGap}.
 */
@Mixin(value = GlDhTerrainShaderProgram_neoforge.class, remap = false)
public class MixinDhTerrainShader_NearClipGap {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged = false;

    @Redirect(
        method = "fillUniformData",
        at = @At(
            value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/util/RenderUtil;getNearClipPlaneInBlocks()F"
        ),
        require = 0
    )
    private float ip_removeLodNearClipGap() {
        if (!ip_logged) {
            ip_logged = true;
            ip_LOGGER.debug("[IPDhGapFix] DH LOD near-clip lowered for terrain pass (uClipDistance ~16 blocks; fade/SSAO distances stock)");
        }

        return 0.0F;
    }
}
