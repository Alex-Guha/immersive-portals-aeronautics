/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.compat.sable_integration.IPLaserIrisPortals;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.IPPortalDrawDistance;
import qouteall.imm_ptl.core.render.IPViewGuard;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;
import qouteall.q_misc_util.Helper;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_EQUAL;
import static org.lwjgl.opengl.GL11.GL_INCR;
import static org.lwjgl.opengl.GL11.GL_KEEP;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glStencilFunc;
import static org.lwjgl.opengl.GL11.glStencilOp;

public class IrisPortalRenderer extends PortalRenderer {
    public static final IrisPortalRenderer instance = new IrisPortalRenderer();
    
    
    private SecondaryFrameBuffer[] deferredFbs = new SecondaryFrameBuffer[0];
    
    // Immersive Aeronautics: per layer, whether this pass has set up its deferred buffer (preparePass): only once one
    // of the pass's portals is to be drawn. A pass that draws no portal leaves its image in the main framebuffer.
    private boolean[] preparedLayers = new boolean[0];
    
    // Immersive Aeronautics: the layer whose deferred stencil preparePass has just initialised, so the pass's first
    // portal does not initialise it again
    private int stencilFreshLayer = -1;
    
    // Immersive Aeronautics: per layer, the textures and size the depth blit was last checked with; the format
    // match and the blit's error check run again only when one of them changes (they stall the CPU on the driver)
    private long[] checkedDepthBlits = new long[0];
    
    IrisPortalRenderer() {
    }
    
    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }
    
    @Override
    public void prepareRendering() {
        Validate.isTrue(!PortalRendering.isRendering());
    
        if (deferredFbs.length != IPGlobal.maxPortalLayer + 1) {
            // As I tested, in Nvidia videocard, glCopyImageSubData can convert depth32 into depth24stencil8.
            // but in AMD videocard it cannot. AMD videocard only supports converting depth32 into depth32stencil8.
            // Immersive Aeronautics: decided here, with the buffers, instead of every frame: the vendor read stalls,
            // and redone every frame it overwrote the choice from the main framebuffer's real format below
            IPCGlobal.useSeparatedStencilFormat = !IPMcHelper.isNvidiaVideocard();
            
            for (SecondaryFrameBuffer fb : deferredFbs) {
                fb.fb.destroyBuffers();
            }
            
            deferredFbs = new SecondaryFrameBuffer[IPGlobal.maxPortalLayer + 1];
            for (int i = 0; i < deferredFbs.length; i++) {
                deferredFbs[i] = new SecondaryFrameBuffer();
            }
            checkedDepthBlits = new long[deferredFbs.length];
            preparedLayers = new boolean[deferredFbs.length];
            
            // Immersive Aeronautics: pick the stencil format from the main framebuffer's actual
            // depth texture format instead of guessing from the GPU vendor
            RenderTarget mainTargetForFormatCheck = client.getMainRenderTarget();
            int mainDepthTexForFormatCheck = mainTargetForFormatCheck.getDepthTextureId();
            if (mainDepthTexForFormatCheck > 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainDepthTexForFormatCheck);
                int mainFormatForFormatCheck = GL11.glGetTexLevelParameteri(
                    GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT
                );
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                if (mainFormatForFormatCheck == GL30.GL_DEPTH32F_STENCIL8) {
                    IPCGlobal.useSeparatedStencilFormat = true;
                }
                else if (mainFormatForFormatCheck == GL30.GL_DEPTH24_STENCIL8) {
                    IPCGlobal.useSeparatedStencilFormat = false;
                }
            }
        }
        
        CHelper.checkGlError();
        
        // Immersive Aeronautics: the deferred buffers are sized and filled per pass, when a portal is to be drawn
        // (preparePass)
        java.util.Arrays.fill(preparedLayers, false);
        stencilFreshLayer = -1;
    
        IPPortingLibCompat.setIsStencilEnabled(client.getMainRenderTarget(), false);
        
        // Immersive Aeronautics: laser beams recorded for drawing over portal views
        IPLaserIrisPortals.clearAll();
        
        // Iris now use vanilla framebuffer's depth
        client.getMainRenderTarget().bindWrite(false);
    }
    
    /** Immersive Aeronautics: what the depth blit into a deferred buffer depends on, for {@link #checkedDepthBlits}. */
    private static long depthBlitKey(RenderTarget main, RenderTarget deferred) {
        long key = main.getDepthTextureId();
        key = key * 31 + deferred.getDepthTextureId();
        key = key * 31 + main.viewWidth;
        key = key * 31 + main.viewHeight;
        key = key * 31 + deferred.viewWidth;
        key = key * 31 + deferred.viewHeight;
        return key == 0 ? 1 : key;
    }
    
    @Override
    public void onBeforeHandRendering(Matrix4f modelView) {
        doMainRenderings(modelView);
    }
    
    private void doMainRenderings(Matrix4f modelView) {
        CHelper.checkGlError();
        
        int portalLayer = PortalRendering.getPortalLayer();
        // Immersive Aeronautics: nothing is copied until one of this pass's portals is to be drawn (preparePass)
        preparedLayers[portalLayer] = false;
        
        renderPortals(modelView);
        
        // Immersive Aeronautics: this pass's laser beams are drawn in its portal views by now
        IPLaserIrisPortals.clear(portalLayer);
        
        if (portalLayer == 0) {
            finish();
        }
        
        client.getMainRenderTarget().bindWrite(true);
    }
    
    /**
     * Immersive Aeronautics: sets up this pass's deferred buffer before its first portal is drawn: the main
     * framebuffer's depth and, within this layer's stencil area, its colour, which still hold this pass's world
     * (no portal of the pass has drawn its view yet). A pass none of whose portals is drawn skips it, and its view is
     * composited from the main framebuffer ({@link #renderViewAndComposite}); with no portal drawn on the screen,
     * nothing is copied at all.
     */
    private void preparePass(int portalLayer) {
        RenderTarget mcFrameBuffer = client.getMainRenderTarget();
        
        deferredFbs[portalLayer].prepare();
        IPPortingLibCompat.setIsStencilEnabled(deferredFbs[portalLayer].fb, true);
        
        // Immersive Aeronautics: the format match and the error check only when the textures or size changed
        long depthBlitKey = depthBlitKey(mcFrameBuffer, deferredFbs[portalLayer].fb);
        boolean checkDepthBlit = checkedDepthBlits[portalLayer] != depthBlitKey;
        if (checkDepthBlit) {
            CHelper.doCheckGlError();
            
            // copy depth from mc fb to deferred fb
            // Immersive Aeronautics: make the deferred depth format match the main one first
            IPIrisDepthFormatFix.matchDeferredDepthFormat(mcFrameBuffer, deferredFbs[portalLayer].fb);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mcFrameBuffer.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, deferredFbs[portalLayer].fb.frameBufferId);
        GL30.glBlitFramebuffer(
            0, 0, mcFrameBuffer.viewWidth, mcFrameBuffer.viewHeight,
            0, 0, mcFrameBuffer.viewWidth, mcFrameBuffer.viewHeight,
            GL_DEPTH_BUFFER_BIT, GL_NEAREST
        );
        
        int errorCode = checkDepthBlit ? GL11.glGetError() : GL_NO_ERROR;
        if (checkDepthBlit && errorCode == GL_NO_ERROR) {
            checkedDepthBlits[portalLayer] = depthBlitKey;
        }
        if (errorCode != GL_NO_ERROR) {
            int mainDepthTex = mcFrameBuffer.getDepthTextureId();
            int deferredDepthTex = deferredFbs[portalLayer].fb.getDepthTextureId();
            int mainInternalFormat = 0;
            int deferredInternalFormat = 0;
            if (mainDepthTex > 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainDepthTex);
                mainInternalFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            }
            if (deferredDepthTex > 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, deferredDepthTex);
                deferredInternalFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            Helper.LOGGER.error(
                "[ImmPtl] Depth blit from main framebuffer ({}x{}, depthTex={}, internalFormat=0x{}) to deferred framebuffer ({}x{}, depthTex={}, internalFormat=0x{}) failed with GL error 0x{}",
                mcFrameBuffer.viewWidth, mcFrameBuffer.viewHeight,
                mainDepthTex, Integer.toHexString(mainInternalFormat),
                deferredFbs[portalLayer].fb.viewWidth, deferredFbs[portalLayer].fb.viewHeight,
                deferredDepthTex, Integer.toHexString(deferredInternalFormat),
                Integer.toHexString(errorCode)
            );
            IPGlobal.renderMode = IPGlobal.RenderMode.compatibility;
            CHelper.printChat("[Immersive Portals]" +
                "Switched to compatibility portal rendering mode." +
                " Portal-in-portal wont' be rendered");
        }
        
        // Immersive Aeronautics: laser beams do not hide the portals behind them
        IPLaserIrisPortals.correctPortalDepth(portalLayer, mcFrameBuffer, deferredFbs[portalLayer].fb);
        
        initStencilForLayer(portalLayer);
        
        deferredFbs[portalLayer].fb.bindWrite(true);
        
        glEnable(GL_STENCIL_TEST);
        glStencilFunc(GL_EQUAL, portalLayer, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        
        // draw from mc fb into deferred fb within stencil
        MyRenderHelper.drawScreenFrameBuffer(mcFrameBuffer, false, true);
        
        glDisable(GL_STENCIL_TEST);
        
        deferredFbs[portalLayer].fb.unbindWrite();
        
        mcFrameBuffer.bindWrite(false);
        
        preparedLayers[portalLayer] = true;
        stencilFreshLayer = portalLayer;
    }
    
    @Override
    public void onHandRenderingEnded() {
    
    }
    
    private void initStencilForLayer(int portalLayer) {
        if (portalLayer == 0) {
            deferredFbs[portalLayer].fb.bindWrite(true);
            GlStateManager._clearStencil(0);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        }
        else {
            CHelper.checkGlError();
            
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, deferredFbs[portalLayer - 1].fb.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, deferredFbs[portalLayer].fb.frameBufferId);
            
            GL30.glBlitFramebuffer(
                0, 0, deferredFbs[0].fb.viewWidth, deferredFbs[0].fb.viewHeight,
                0, 0, deferredFbs[0].fb.viewWidth, deferredFbs[0].fb.viewHeight,
                GL_STENCIL_BUFFER_BIT, GL_NEAREST
            );
            
            CHelper.checkGlError();
        }
    }
    
    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void onAfterTranslucentRendering(Matrix4f modelView) {
    
    }
    
    
    @Override
    public void finishRendering() {
    
    }
    
    private void finish() {
        GlStateManager._colorMask(true, true, true, true);
        
        // Immersive Aeronautics: no portal on the screen drawn (a view or the fill of one not drawn): the main
        // framebuffer is the image
        if (!preparedLayers[0]) {
            return;
        }
        
        RenderTarget mainFrameBuffer = client.getMainRenderTarget();
        mainFrameBuffer.bindWrite(true);
        
        deferredFbs[0].fb.blitToScreen(mainFrameBuffer.viewWidth, mainFrameBuffer.viewHeight);
        
        // Immersive Aeronautics: put the portal depth back so the held item is not clipped
        IPIrisHandDepthFix.restorePortalDepth(Minecraft.getInstance().getMainRenderTarget(), deferredFbs[0].fb);
        
        CHelper.checkGlError();
    }
    
    protected void doRenderPortal(Portal portal, Matrix4f modelView) {
        int portalLayer = PortalRendering.getPortalLayer();
        
        //reset projection matrix
//        client.gameRenderer.loadProjectionMatrix(RenderStates.basicProjectionMatrix);
        
        // Immersive Aeronautics: before the pass's deferred buffer is set up (preparePass), the screen's portals are
        // tested against the main framebuffer, which holds the same depth: a portal hidden behind a wall costs no
        // copies. A pass with laser beams corrects the deferred depth first (IPLaserIrisPortals), and inside a view
        // the test must be limited to the view's stencil area, so those prepare first.
        boolean visibilityDecided = false;
        if (!preparedLayers[portalLayer]) {
            if (portalLayer == 0 && !IPLaserIrisPortals.hasBeams(portalLayer)) {
                if (!testVisibilityInMainFrameBuffer(portal, modelView)) {
                    return;
                }
                visibilityDecided = true;
            }
            preparePass(portalLayer);
        }
        
        // Immersive Aeronautics: a portal fades out towards its draw distance (IPPortalDrawDistance); what is behind
        // it is this layer's deferred buffer, where the outer view is composited
        float visibility = IPPortalDrawDistance.visibility(portal);
        boolean fading = IPPortalDrawDistance.beginFade(visibility, deferredFbs[portalLayer].fb);
        
        // Immersive Aeronautics: the portal frame rate target may drop this view (IPViewGuard); a view that is not
        // drawn keeps what is behind its portal, under the nether portal texture, so its area is not painted black
        IPViewGuard.push(portal, modelView);
        try {
            boolean notDrawn = portalLayer + 1 > PortalRendering.getMaxPortalLayer()
                || IPViewGuard.dropsCurrent();
            
            //write to deferred buffer
            if (!tryRenderViewAreaInDeferredBufferAndIncreaseStencil(portal, modelView, !notDrawn, !visibilityDecided)) {
                return;
            }
            
            renderViewAndComposite(portal, modelView, fading, visibility);
        }
        finally {
            IPViewGuard.pop();
        }
    }
    
    private void renderViewAndComposite(Portal portal, Matrix4f modelView, boolean fading, float visibility) {
        PortalRendering.pushPortalLayer(portal);
        
        // this is important
        client.getMainRenderTarget().bindWrite(true);
        
        int innerLayer = PortalRendering.getPortalLayer();
        if (innerLayer < preparedLayers.length) {
            preparedLayers[innerLayer] = false;
        }
        
        renderPortalContent(portal);
        
        
        PortalRendering.popPortalLayer();
        
        int outerLayer = PortalRendering.getPortalLayer();
        
        if (innerLayer > PortalRendering.getMaxPortalLayer() || IPViewGuard.dropsCurrent()) {
            // Immersive Aeronautics: the view is not drawn; its area shows the nether portal texture, not black
            deferredFbs[outerLayer].fb.bindWrite(true);
            glEnable(GL_STENCIL_TEST);
            glStencilFunc(GL_EQUAL, innerLayer, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            ViewAreaRenderer.renderNotDrawnViewFill(portal, modelView, RenderSystem.getProjectionMatrix());
            glDisable(GL_STENCIL_TEST);
            deferredFbs[outerLayer].fb.unbindWrite();
            return;
        }
        
        deferredFbs[outerLayer].fb.bindWrite(true);
        
        glEnable(GL_STENCIL_TEST);
        glStencilFunc(GL_EQUAL, innerLayer, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        
        // Immersive Aeronautics: a view that drew no portal of its own is still in the main framebuffer, the same
        // pixels its deferred buffer would have had within this stencil area (preparePass)
        MyRenderHelper.drawScreenFrameBuffer(
            preparedLayers[innerLayer] ? deferredFbs[innerLayer].fb : client.getMainRenderTarget(),
            true,
            false
        );
        
        // Immersive Aeronautics: see above
        if (fading) {
            IPPortalDrawDistance.endFade(visibility, innerLayer, deferredFbs[outerLayer].fb);
        }
        
        glDisable(GL_STENCIL_TEST);
        
        deferredFbs[outerLayer].fb.unbindWrite();
    }
    
    private boolean tryRenderViewAreaInDeferredBufferAndIncreaseStencil(
        Portal portal, Matrix4f modelView, boolean paintArea, boolean decideVisibility
    ) {
        
        int portalLayer = PortalRendering.getPortalLayer();
        
        // Immersive Aeronautics: not again for the stencil preparePass has just initialised
        if (stencilFreshLayer != portalLayer) {
            initStencilForLayer(portalLayer);
        }
        stencilFreshLayer = -1;
        
        deferredFbs[portalLayer].fb.bindWrite(true);
        
        GL11.glEnable(GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_EQUAL, portalLayer, 0xFF);
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        
        GlStateManager._enableDepthTest();
        
        Runnable drawArea = () -> {
            ViewAreaRenderer.renderPortalArea(
                portal, Vec3.ZERO,
                modelView,
                RenderSystem.getProjectionMatrix(),
                true, paintArea, true, true
            );
        };
        // Immersive Aeronautics: or already decided in the main framebuffer (testVisibilityInMainFrameBuffer)
        boolean result;
        if (decideVisibility) {
            result = PortalRenderInfo.renderAndDecideVisibility(portal, drawArea);
        }
        else {
            drawArea.run();
            result = true;
        }
        
        GL11.glDisable(GL_STENCIL_TEST);
        
        return result;
    }
    
    /**
     * Immersive Aeronautics: the portal's occlusion test drawn into the main framebuffer (no colour, no depth written),
     * for a pass whose deferred buffer is not set up yet ({@link #doRenderPortal}).
     */
    private boolean testVisibilityInMainFrameBuffer(Portal portal, Matrix4f modelView) {
        client.getMainRenderTarget().bindWrite(false);
        GL11.glDisable(GL_STENCIL_TEST);
        GlStateManager._enableDepthTest();
        return PortalRenderInfo.renderAndDecideVisibility(portal, () -> {
            ViewAreaRenderer.renderPortalArea(
                portal, Vec3.ZERO,
                modelView,
                RenderSystem.getProjectionMatrix(),
                true, false, false, true
            );
        });
    }
    
    @Override
    public void invokeWorldRendering(
        WorldRenderInfo worldRenderInfo
    ) {
        MyGameRenderer.renderWorldNew(
            worldRenderInfo,
            Runnable::run
        );
    }
    
    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
    
    }
    
    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);

        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }
}
