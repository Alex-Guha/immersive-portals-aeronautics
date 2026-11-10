/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Keeps IP's front clipping active while Iris flushes its batched entity rendering.
 * <p>
 * With Iris installed (shader pack or not), every entity and block entity draw of a level render is
 * collected by Iris's {@code FullyBufferedMultiBufferSource} and drawn in one go when vanilla's final
 * {@code endBatch()} runs, after the translucent terrain layer. IP enables its clip plane and feeds the
 * clip uniform only during the entity phase, so in a portal pass those deferred draws happened with
 * clipping off and everything behind the destination portal (chests, beds, heads on ships, mobs) leaked
 * into the view. Wrapping the flush re-enables the plane and the uniform for its duration.
 * <p>
 * In a pass through an odd number of mirrors the view is reflected, which reverses the winding of every
 * triangle on screen, so back-face culling removes the faces turned to the viewer. IP flips the cull face
 * for terrain and the sky, and upstream turns culling off for entity draws in
 * {@code MixinMultiBufferSourceBufferSource}, at {@code BufferSource.endBatch(RenderType)}; Iris's flush
 * draws each render type itself and never reaches that method, so culled entity draws (a block held in
 * hand, chests, items) showed only their back faces in mirrors. The flush culls front faces instead.
 */
public final class IPIrisBatchClip {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static int depth;
    private static boolean logged;

    private IPIrisBatchClip() {
    }

    public static void aroundFlush(Runnable flush) {
        if (depth > 0 || !PortalRendering.isRendering()) {
            depth++;
            try {
                flush.run();
            }
            finally {
                depth--;
            }
            return;
        }
        boolean wasRenderingEntities = CrossPortalEntityRenderer.isRenderingEntityNormally;
        boolean wasClipping = FrontClipping.isClippingEnabled;
        boolean mirrored = PortalRendering.isRenderingOddNumberOfMirrors();
        depth++;
        try {
            if (mirrored) {
                MyRenderHelper.applyMirrorFaceCulling();
            }
            CrossPortalEntityRenderer.isRenderingEntityNormally = true;
            FrontClipping.setupInnerClipping(
                PortalRendering.getActiveClippingPlane(), new Matrix4f(RenderSystem.getModelViewMatrix()), 0
            );
            if (!logged) {
                logged = true;
                LOGGER.debug(
                    "[IPIrisBatchClip] clipping Iris's deferred entity flush in a portal pass (clip plane enabled: {})",
                    FrontClipping.isClippingEnabled
                );
            }
            flush.run();
        }
        finally {
            depth--;
            if (mirrored) {
                MyRenderHelper.recoverFaceCulling();
            }
            CrossPortalEntityRenderer.isRenderingEntityNormally = wasRenderingEntities;
            if (!wasClipping) {
                FrontClipping.disableClipping();
            }
        }
    }
}
