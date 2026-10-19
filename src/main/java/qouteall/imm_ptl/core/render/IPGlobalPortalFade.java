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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalView;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalTrackedPortal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

/**
 * How far away a global portal (a dimension stack's floor or ceiling) is drawn, and its fade towards that limit.
 * <p>
 * IP skips every portal past the render distance. For a dimension stack's ceiling that made the whole stacked
 * dimension (an Aether over the overworld, its blocks and DH LODs) vanish at once when the player went more than the
 * render distance below it, and pop back on rising. 2.4.7 drew such portals at any distance with DH, which costs a
 * second world pass whenever the sky is in view. Now the range is the render distance unless the client option
 * {@code dimensionStackViewDistance} raises it (with DH; past the vanilla terrain only LODs are there to see), and
 * over the last quarter of the range the portal fades out: what was behind the portal is copied before its pass and
 * blended back over the portal's stencil area afterwards, so blocks, LODs and clouds fade together.
 * <p>
 * Only for the player's own view through a global portal with the stencil renderer; nested views and Iris shader
 * packs keep the hard cut.
 */
public final class IPGlobalPortalFade {
    private static TextureTarget backdrop = null;

    private IPGlobalPortalFade() {
    }

    private static boolean applies(Portal portal) {
        return portal instanceof GlobalTrackedPortal && PortalRendering.getPortalLayer() == 0
            && !RenderStates.isLaggy && !IPGlobal.reducedPortalRendering;
    }

    /** The distance (blocks) beyond which {@code portal} is not rendered. */
    public static double viewRange(Portal portal) {
        double range = PortalRenderer.getRenderRange();
        if (!applies(portal)) {
            return range;
        }
        int chunks = IPConfig.getConfig().dimensionStackViewDistance;
        if (chunks > 0 && IPDhPortalView.ENABLED && IPDhPortalView.isDhLoaded()) {
            range = Math.max(range, chunks * 16.0);
        }
        return range;
    }

    /** 1 where {@code portal} is fully shown, falling to 0 over the last quarter of its view range. */
    public static float visibility(Portal portal) {
        if (!applies(portal)) {
            return 1;
        }
        double range = viewRange(portal);
        double band = Math.max(16, range * 0.25);
        double distance = portal.getDistanceToNearestPointInPortal(TransformationManager.getIsometricAdjustedCameraPos());
        return (float) Mth.clamp((range - distance) / band, 0, 1);
    }

    /**
     * Before the portal is drawn: if it is fading, copies the current colour buffer (what is behind the portal).
     * Returns whether {@link #endFade} must be called.
     */
    public static boolean beginFade(float visibility) {
        if (visibility >= 1) {
            return false;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (backdrop == null) {
            backdrop = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
        }
        else if (backdrop.width != main.width || backdrop.height != main.height) {
            backdrop.resize(main.width, main.height, Minecraft.ON_OSX);
        }

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, backdrop.frameBufferId);
        GL30.glBlitFramebuffer(
            0, 0, main.width, main.height, 0, 0, main.width, main.height,
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
     * After the portal's content is drawn (its stencil value still marks its area): blends the copied backdrop back
     * over that area with opacity {@code 1 - visibility}.
     */
    public static void endFade(float visibility, int portalStencilValue) {
        ShaderInstance shader = MyRenderHelper.portalFadeShader;
        if (backdrop == null || shader == null) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();

        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_EQUAL, portalStencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.viewport(0, 0, main.width, main.height);
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
