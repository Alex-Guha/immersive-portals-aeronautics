/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer_neoforge;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** The DH-typed half of {@link IPDhTextureGuard}: copies DH's active colour/depth textures aside and back. */
final class IPDhTextureGuardImpl {
    private static int backupColor;
    private static int backupDepth;
    private static int backupWidth;
    private static int backupHeight;
    private static int readFbo;
    private static int drawFbo;
    private static boolean saved;
    private static int savedColor;
    private static int savedDepth;

    private IPDhTextureGuardImpl() {
    }

    static boolean save() {
        saved = false;
        GlDhMetaRenderer_neoforge meta = GlDhMetaRenderer_neoforge.INSTANCE;
        int color = meta.getActiveColorTextureId();
        int depth = meta.getActiveDepthTextureId();
        if (color == 0 || depth == 0) {
            return false;
        }
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        int width = target.viewWidth;
        int height = target.viewHeight;
        if (width <= 0 || height <= 0) {
            return false;
        }
        ensureBackups(width, height);
        blit(color, depth, backupColor, backupDepth, width, height, true);
        savedColor = color;
        savedDepth = depth;
        saved = true;
        return true;
    }

    static void restore() {
        if (!saved) {
            return;
        }
        saved = false;
        GlDhMetaRenderer_neoforge meta = GlDhMetaRenderer_neoforge.INSTANCE;
        // the colour texture is only copied back if the portal passes drew into the same one (they
        // always draw into the raw colour texture; with anti-aliasing on the outer world's active
        // texture is the anti-aliasing output, which they leave alone)
        boolean colorOverwritten = meta.getActiveColorTextureId() == savedColor;
        blit(backupColor, backupDepth, savedColor, savedDepth, backupWidth, backupHeight, colorOverwritten);
        meta.setActiveColorTextureId(savedColor);
        meta.setActiveDepthTextureId(savedDepth);
    }

    private static void ensureBackups(int width, int height) {
        if (backupColor != 0 && backupWidth == width && backupHeight == height) {
            return;
        }
        if (backupColor != 0) {
            GL11.glDeleteTextures(backupColor);
            GL11.glDeleteTextures(backupDepth);
        }
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        backupColor = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backupColor);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        backupDepth = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backupDepth);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // DH's depth texture is DEPTH32F; a depth blit needs identical formats
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, width, height, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        backupWidth = width;
        backupHeight = height;
        if (readFbo == 0) {
            readFbo = GL30.glGenFramebuffers();
            drawFbo = GL30.glGenFramebuffers();
        }
    }

    private static void blit(int srcColor, int srcDepth, int dstColor, int dstDepth, int width, int height, boolean copyColor) {
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, srcColor, 0);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, srcDepth, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, dstColor, 0);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, dstDepth, 0);

        if (copyColor) {
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        // detach so a texture DH later deletes is not left dangling on our framebuffers
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }
}
