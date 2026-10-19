/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.nio.ByteBuffer;

/**
 * Keeps depth clears issued while a world's sky is drawn inside a stencil portal pass to the portal.
 * <p>
 * A {@code glClear} ignores the stencil test, so a depth clear in a portal pass wipes the depth of the
 * outer world (and of portals drawn earlier) wherever the scissor allows, often the whole screen.
 * Everything the outer world draws after its portals (other portals' faces, translucent terrain,
 * particles) then shows through its terrain. Cirrus clears depth this way after masking the sun behind
 * its clouds. Inside the portal the result is the same as a clear: the stencil-limited pixels get the
 * clear depth, as IP does for the portal's own view area ({@code RendererUsingStencil}).
 */
public final class IPPortalPassDepthClear {
    private static boolean renderingSky;

    private IPPortalPassDepthClear() {
    }

    public static void setRenderingSky(boolean value) {
        renderingSky = value;
    }

    /** Whether a clear with this mask must not reach the GL as it is. */
    public static boolean shouldReplace(int mask) {
        return renderingSky
            && (mask & GL11.GL_DEPTH_BUFFER_BIT) != 0
            && PortalRendering.isRendering()
            && GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    }

    /**
     * Writes the clear depth to the pixels that pass the current stencil and scissor tests. Like a
     * clear, it honours the depth write mask and touches no colour. GL state is read back and restored
     * raw, so GlStateManager's cache stays as it was.
     */
    public static void clearDepthWithinStencil() {
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean clipping = FrontClipping.isClippingEnabled;
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        float clearDepth = GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE);
        boolean red, green, blue, alpha;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer colorMask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            red = colorMask.get(0) != 0;
            green = colorMask.get(1) != 0;
            blue = colorMask.get(2) != 0;
            alpha = colorMask.get(3) != 0;
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDepthRange(clearDepth, clearDepth);
        GL11.glColorMask(false, false, false, false);
        // the mirror pass culls front faces
        GL11.glDisable(GL11.GL_CULL_FACE);
        if (clipping) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
        }
        try {
            MyRenderHelper.renderScreenTriangle();
        }
        finally {
            if (clipping) {
                GL11.glEnable(GL11.GL_CLIP_PLANE0);
            }
            if (cull) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            }
            GL11.glColorMask(red, green, blue, alpha);
            GL11.glDepthRange(0, 1);
            GL11.glDepthFunc(depthFunc);
            if (!depthTest) {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
        }
    }
}
