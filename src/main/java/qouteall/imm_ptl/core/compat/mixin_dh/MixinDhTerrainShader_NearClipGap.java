/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.util.RenderUtil;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalDrawnChunks;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalNearClip;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Where DH's LOD terrain starts inside portal views (fade/SSAO distances stay stock).
 * <p>
 * The player's own view keeps DH's distance clip (a share of the vanilla render distance). In
 * portal passes the clip is turned off and the terrain fragment shader instead discards LODs in
 * the chunks the pass draws ({@link IPDhPortalDrawnChunks}; uniforms set at the end of
 * {@code fillUniformData}, while the program is bound). If DH's shader could not be patched, the
 * clip falls back to how far the destination's terrain surely reaches ({@link IPDhPortalNearClip}).
 * DH adds 16 blocks to the redirected value for {@code uClipDistance} (a clip of 0 or less turns
 * clipping off) and, with its dithered fade, fades LODs in up to 1.5 times that distance. The Iris
 * shader path is covered by {@link MixinDhApiRenderProxy_NearClipGap}.
 * <p>
 * The fork set the clip to 0 everywhere (LODs from 16 blocks), and LODs showed through every gap
 * in nearby non-full blocks (fences, stairs, slabs) wherever the sky was behind them (2.3.17).
 */
@Mixin(value = GlDhTerrainShaderProgram_neoforge.class, remap = false)
public class MixinDhTerrainShader_NearClipGap {
    @Unique
    private int ip_locationsProgram = -1;

    @Unique
    private int ip_enabledLocation = -1;

    @Unique
    private int ip_cameraLocation = -1;

    @Unique
    private int ip_maskLocation = -1;

    @Redirect(
        method = "fillUniformData",
        at = @At(
            value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/util/RenderUtil;getNearClipPlaneInBlocks()F"
        ),
        require = 0
    )
    private float ip_portalViewLodNearClip() {
        float dhNearClip = RenderUtil.getNearClipPlaneInBlocks();
        if (!PortalRendering.isRendering() || Config.Client.Advanced.Debugging.lodOnlyMode.get()) {
            return dhNearClip;
        }

        if (IPDhPortalDrawnChunks.isActive() && ip_resolveCutLocations()) {
            return -16.0F;
        }

        double fadeEnd = Config.Client.Advanced.Graphics.Quality.ditherDhFade.get() ? 1.5 : 1.0;
        float clipDistance = (float) Math.min(dhNearClip + 16.0, IPDhPortalNearClip.coveredDistance() / fadeEnd);

        // DH adds the 16
        return clipDistance - 16.0F;
    }

    @Inject(
        method = "fillUniformData",
        at = @At("TAIL"),
        require = 0
    )
    private void ip_setDrawnChunkCut(DhApiRenderParam renderParameters, CallbackInfo ci) {
        if (!ip_resolveCutLocations()) {
            return;
        }
        boolean active = IPDhPortalDrawnChunks.isActive() && !Config.Client.Advanced.Debugging.lodOnlyMode.get();
        GL20.glUniform1i(ip_enabledLocation, active ? 1 : 0);
        if (active) {
            float[] camera = IPDhPortalDrawnChunks.cameraInGrid();
            GL20.glUniform2f(ip_cameraLocation, camera[0], camera[1]);
            GL30.glUniform1uiv(ip_maskLocation, IPDhPortalDrawnChunks.mask());
        }
    }

    /** Looks up the cut's uniforms in the bound program; false if DH's shader was not patched. */
    @Unique
    private boolean ip_resolveCutLocations() {
        int program = ((GlShaderProgram) (Object) this).id;
        if (program != ip_locationsProgram) {
            ip_locationsProgram = program;
            ip_enabledLocation = GL20.glGetUniformLocation(program, IPDhPortalDrawnChunks.ENABLED_UNIFORM);
            ip_cameraLocation = GL20.glGetUniformLocation(program, IPDhPortalDrawnChunks.CAMERA_UNIFORM);
            ip_maskLocation = GL20.glGetUniformLocation(program, IPDhPortalDrawnChunks.MASK_UNIFORM);
        }
        return ip_enabledLocation >= 0 && ip_cameraLocation >= 0 && ip_maskLocation >= 0;
    }
}
