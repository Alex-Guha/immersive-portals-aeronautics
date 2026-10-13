/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/**
 * With Iris, the held item is drawn against the main framebuffer's depth, which after a
 * portal pass still holds the portal plane and clips the hand. {@link #restorePortalDepth}
 * blits the depth buffer from the portal-rendered target back into the main framebuffer
 * (same size only). Called by {@code IrisPortalRenderer} after {@code blitToScreen}.
 */
public final class IPIrisHandDepthFix {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int GL_READ_FRAMEBUFFER = 36008;
    private static final int GL_DRAW_FRAMEBUFFER = 36009;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 36010;
    private static final int GL_DRAW_FRAMEBUFFER_BINDING = 36006;
    private static final int GL_DEPTH_BUFFER_BIT = 256;
    private static final int GL_NEAREST = 9728;
    private static boolean loggedSuccess;
    private static boolean loggedFailure;

    private IPIrisHandDepthFix() {
    }

    public static void restorePortalDepth(RenderTarget mainTarget, RenderTarget portalTarget) {
        if (mainTarget == null || portalTarget == null) {
            return;
        }
        int width = mainTarget.viewWidth;
        int height = mainTarget.viewHeight;
        if (width <= 0 || height <= 0 || portalTarget.viewWidth != width || portalTarget.viewHeight != height) {
            return;
        }
        int previousReadFb = 0;
        int previousDrawFb = 0;

        try {
            previousReadFb = GL11.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            previousDrawFb = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            GL11.glGetError();
            GL30.glBindFramebuffer(GL_READ_FRAMEBUFFER, portalTarget.frameBufferId);
            GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, mainTarget.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                if (!loggedFailure) {
                    loggedFailure = true;
                    LOGGER.debug(
                        "[IPIrisHandDepth] depth copy back to the main framebuffer failed (GL error 0x{}); the held item may be clipped by nearby portals",
                        Integer.toHexString(error)
                    );
                }
            } else if (!loggedSuccess) {
                loggedSuccess = true;
                LOGGER.debug(
                    "[IPIrisHandDepth] portal depth copied back to the main framebuffer; held item will not be clipped at the portal plane"
                );
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                GL30.glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFb);
                GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFb);
            } catch (Throwable ignored) {
            }
        }
    }
}
