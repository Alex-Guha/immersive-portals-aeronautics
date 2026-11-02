/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Keeps IP's stencil settings intact across Distant Horizons' render calls inside a portal pass.
 * <p>
 * DH's post-processing steps (copy, apply, fog, ...) snapshot the GL state on entry and restore it on exit
 * ({@code GLState}), reading the stencil reference with {@code glGetInteger(GL_STENCIL_REF)} while DH's own
 * framebuffer, which has no stencil buffer, is bound. Mesa (radeonsi, zink) returns the reference clamped to the
 * bound framebuffer's stencil bits, so DH restored 0. A portal pass draws where the stencil equals its layer (1 or
 * more); with 0, DH's full-screen LOD composite and then the rest of the pass drew everywhere outside the portal: the
 * other dimension's LODs and terrain across the screen (player reports with 2.2.4 on Mesa, 2026-10-16; story in
 * docs/lessons/portal-rendering-dh.md). NVIDIA returns the unclamped value, so it never showed there.
 * <p>
 * The settings are read at the start of each DH call, while Minecraft's framebuffer is bound; DH never changes them
 * itself, so they are the true ones for the whole call. Every DH snapshot taken during the call gets them
 * ({@code MixinDhGLState_PortalStencil}), and they are written back after the call as well.
 * <p>
 * No DH types here.
 */
public final class IPDhPortalStencilGuard {
    private static boolean saved;
    private static boolean stencilTest;
    private static int func, ref, valueMask, writeMask, sfail, dpfail, dppass;

    private IPDhPortalStencilGuard() {
    }

    public static void beforeDh() {
        saved = false;
        if (!PortalRendering.isRendering()) {
            return;
        }
        stencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        func = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        ref = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        valueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        writeMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        sfail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
        dpfail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        dppass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        saved = true;
    }

    /** True inside a DH call in a portal pass, when {@link #stencilTest()} etc. hold IP's real settings. */
    public static boolean active() {
        return saved;
    }

    public static boolean stencilTest() {
        return stencilTest;
    }

    public static int func() {
        return func;
    }

    public static int ref() {
        return ref;
    }

    public static int valueMask() {
        return valueMask;
    }

    public static void afterDh() {
        if (!saved) {
            return;
        }
        saved = false;
        if (stencilTest) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
        }
        else {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
        GL11.glStencilFunc(func, ref, valueMask);
        GL11.glStencilOp(sfail, dpfail, dppass);
        GL11.glStencilMask(writeMask);
    }
}
