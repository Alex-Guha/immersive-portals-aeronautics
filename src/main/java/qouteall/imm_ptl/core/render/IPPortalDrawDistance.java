/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalView;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalTrackedPortal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

/**
 * How far away a portal is drawn (client option {@code portalDrawDistance}), and its fade towards that limit.
 * <p>
 * IP skips every portal past the render distance, and the fork skipped every portal past 100 blocks under a shader
 * pack (portal passes cost the most there). For a dimension stack's ceiling that made the whole stacked dimension (an
 * Aether over the overworld, its blocks and DH LODs) vanish at once when the player went past that distance below it.
 * Now the range is the option's when it is set, otherwise the render distance, or {@link #SHADER_PACK_DEFAULT} under a
 * pack. Only a dimension stack's floor or ceiling is drawn past the render distance, and only with LODs (DH or Voxy):
 * past the vanilla terrain nothing else is there to see. Regular portals stop at the render distance: the server
 * sends a portal only while its chunk is in the player's view distance, and a view through a portal farther than that
 * has no terrain in range of its camera (tried in 2.8.8.3-draw-distance, given up: user 2026-11-02). Over the last quarter of the range the portal fades out: what was behind the portal is
 * copied before its pass and blended back over the portal's stencil area afterwards, so blocks, LODs and clouds fade
 * together.
 * <p>
 * The fade is only for the player's own view; nested views keep the hard cut. With a shader pack the backdrop is IP's
 * deferred buffer instead of the main one ({@code IrisPortalRenderer.doRenderPortal}).
 */
public final class IPPortalDrawDistance {
    /** The range under a shader pack with the option at 0, the fork's cut-off. */
    public static final double SHADER_PACK_DEFAULT = 100.0;

    private static TextureTarget backdrop = null;

    private IPPortalDrawDistance() {
    }

    private static boolean fades(Portal portal) {
        return PortalRendering.getPortalLayer() == 0 && !RenderStates.isLaggy && !IPGlobal.reducedPortalRendering;
    }

    /** The distance (blocks) beyond which {@code portal} is not rendered. */
    public static double viewRange(Portal portal) {
        double range = PortalRenderer.getRenderRange();
        int chunks = IPConfig.getConfig().portalDrawDistance;
        if (chunks <= 0) {
            return IPIrisCompat.isShaderPackInUse() ? Math.min(range, SHADER_PACK_DEFAULT) : range;
        }
        if (portal instanceof GlobalTrackedPortal && fades(portal) && hasLods()) {
            // the projection's far plane is 4 times the render distance: past it the portal itself is clipped
            return Math.min(chunks * 16.0, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.95);
        }
        return Math.min(range, chunks * 16.0);
    }

    private static Boolean voxyLoaded = null;

    /** Whether LODs are drawn (Distant Horizons or Voxy): only they show anything past the render distance. */
    public static boolean hasLods() {
        if (voxyLoaded == null) {
            voxyLoaded = ModList.get() != null && ModList.get().isLoaded("voxy");
        }
        return (IPDhPortalView.ENABLED && IPDhPortalView.isDhLoaded()) || voxyLoaded;
    }

    /** 1 where {@code portal} is fully shown, falling to 0 over the last quarter of its view range. */
    public static float visibility(Portal portal) {
        if (!fades(portal)) {
            return 1;
        }
        double range = viewRange(portal);
        double band = Math.max(16, range * 0.25);
        double distance = portal.getDistanceToNearestPointInPortal(TransformationManager.getIsometricAdjustedCameraPos());
        return (float) Mth.clamp((range - distance) / band, 0, 1);
    }

    /**
     * Before the portal is drawn: if it is fading, copies the colour buffer of {@code target} (what is behind the
     * portal). Returns whether {@link #endFade} must be called.
     */
    public static boolean beginFade(float visibility, RenderTarget target) {
        if (visibility >= 1) {
            return false;
        }
        if (backdrop == null) {
            backdrop = new TextureTarget(target.width, target.height, false, Minecraft.ON_OSX);
        }
        else if (backdrop.width != target.width || backdrop.height != target.height) {
            backdrop.resize(target.width, target.height, Minecraft.ON_OSX);
        }

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, backdrop.frameBufferId);
        GL30.glBlitFramebuffer(
            0, 0, target.width, target.height, 0, 0, target.width, target.height,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
        );
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        return true;
    }

    /**
     * After the portal's content is drawn into {@code target}, which is bound and whose stencil value still marks the
     * portal's area: blends the copied backdrop back over that area with opacity {@code 1 - visibility}.
     */
    public static void endFade(float visibility, int portalStencilValue, RenderTarget target) {
        ShaderInstance shader = MyRenderHelper.portalFadeShader;
        if (backdrop == null || shader == null) {
            return;
        }

        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_EQUAL, portalStencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.viewport(0, 0, target.width, target.height);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE
        );
        RenderSystem.colorMask(true, true, true, false);

        shader.setSampler("DiffuseSampler", backdrop.getColorTextureId());
        shader.safeGetUniform("FadeAlpha").set(1 - visibility);
        shader.apply();
        BufferBuilder bufferBuilder = RenderSystem.renderThreadTesselator()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        bufferBuilder.addVertex(0.0f, 0.0f, 0.0f);
        bufferBuilder.addVertex(1.0f, 0.0f, 0.0f);
        bufferBuilder.addVertex(1.0f, 1.0f, 0.0f);
        bufferBuilder.addVertex(0.0f, 1.0f, 0.0f);
        BufferUploader.draw(bufferBuilder.buildOrThrow());
        shader.clear();

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
