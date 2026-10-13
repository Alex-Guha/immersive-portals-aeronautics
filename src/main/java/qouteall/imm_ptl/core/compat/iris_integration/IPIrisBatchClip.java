/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.FrontClipping;
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
        depth++;
        try {
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
            CrossPortalEntityRenderer.isRenderingEntityNormally = wasRenderingEntities;
            if (!wasClipping) {
                FrontClipping.disableClipping();
            }
        }
    }
}
