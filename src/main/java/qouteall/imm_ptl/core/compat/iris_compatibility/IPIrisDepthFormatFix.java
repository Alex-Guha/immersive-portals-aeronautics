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
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.slf4j.Logger;

/**
 * Iris's deferred framebuffer may allocate its depth texture with a different packed
 * depth-stencil internal format than the main framebuffer, which makes IP's portal depth
 * blit between them illegal. {@link #matchDeferredDepthFormat} re-allocates the deferred
 * depth texture ({@code glTexImage2D}) with the main framebuffer's internal format when both
 * are packed depth+stencil formats. Called by {@code IrisPortalRenderer} before the depth blit.
 */
public final class IPIrisDepthFormatFix {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int GL_TEXTURE_INTERNAL_FORMAT = 4096;
    private static final int GL_DEPTH24_STENCIL8 = 35056;
    private static final int GL_DEPTH32F_STENCIL8 = 36013;
    private static final int GL_DEPTH_STENCIL = 34041;
    private static final int GL_UNSIGNED_INT_24_8 = 34042;
    private static final int GL_FLOAT_32_UNSIGNED_INT_24_8_REV = 36269;
    private static final int GL_TEXTURE_BINDING_2D = 32873;
    private static final int GL_PIXEL_UNPACK_BUFFER = 35052;
    private static final int GL_PIXEL_UNPACK_BUFFER_BINDING = 35055;
    private static boolean loggedSuccess = false;
    private static boolean loggedGiveUp = false;

    private IPIrisDepthFormatFix() {
    }

    public static void matchDeferredDepthFormat(RenderTarget mainTarget, RenderTarget deferredTarget) {
        if (mainTarget == null || deferredTarget == null) {
            return;
        }
        int deferredDepthTex = deferredTarget.getDepthTextureId();
        int mainDepthTex = mainTarget.getDepthTextureId();
        if (deferredDepthTex <= 0 || mainDepthTex <= 0) {
            return;
        }
        int width = deferredTarget.viewWidth;
        int height = deferredTarget.viewHeight;
        if (width <= 0 || height <= 0) {
            return;
        }
        int previousTexture = 0;
        int previousUnpackBuffer = 0;

        try {
            previousTexture = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
            previousUnpackBuffer = GL11.glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            int mainFormat = getInternalFormat(mainDepthTex);
            int deferredFormat = getInternalFormat(deferredDepthTex);
            if (mainFormat == deferredFormat) {
                return;
            }

            if (!isPackedDepthStencil(mainFormat) || !isPackedDepthStencil(deferredFormat)) {
                if (!loggedGiveUp) {
                    loggedGiveUp = true;
                    LOGGER.debug(
                        "[IPIrisDepthFix] not converting: main depth 0x{}, deferred depth 0x{} - need both to be packed depth+stencil",
                        Integer.toHexString(mainFormat), Integer.toHexString(deferredFormat)
                    );
                }

                return;
            }

            int pixelType = mainFormat == GL_DEPTH32F_STENCIL8 ? GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL_UNSIGNED_INT_24_8;
            if (previousUnpackBuffer != 0) {
                GL15.glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            }

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, deferredDepthTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, mainFormat, width, height, 0, GL_DEPTH_STENCIL, pixelType, (ByteBuffer) null);
            int error = GL11.glGetError();
            if (error == GL11.GL_NO_ERROR) {
                if (!loggedSuccess) {
                    loggedSuccess = true;
                    LOGGER.debug(
                        "[IPIrisDepthFix] deferred framebuffer depth converted 0x{} -> 0x{} to match the main framebuffer; portal depth blit is now legal",
                        Integer.toHexString(deferredFormat), Integer.toHexString(mainFormat)
                    );
                }

                return;
            }

            if (!loggedGiveUp) {
                loggedGiveUp = true;
                LOGGER.debug(
                    "[IPIrisDepthFix] could not convert deferred depth texture to 0x{} (GL error 0x{})",
                    Integer.toHexString(mainFormat), Integer.toHexString(error)
                );
            }
        } catch (Throwable t) {
            return;
        } finally {
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                if (previousUnpackBuffer != 0) {
                    GL15.glBindBuffer(GL_PIXEL_UNPACK_BUFFER, previousUnpackBuffer);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isPackedDepthStencil(int internalFormat) {
        return internalFormat == GL_DEPTH24_STENCIL8 || internalFormat == GL_DEPTH32F_STENCIL8;
    }

    private static int getInternalFormat(int textureId) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
    }
}
