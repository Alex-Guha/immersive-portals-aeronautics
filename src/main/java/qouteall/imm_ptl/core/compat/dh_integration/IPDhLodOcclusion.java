/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * Portals hidden behind Distant Horizons' LODs.
 * <p>
 * DH composites its LODs into Minecraft's colour buffer only; the depth buffer keeps the clear value wherever vanilla
 * drew nothing. A portal whose view area reaches past the vanilla terrain therefore passes the depth test there and
 * its stencil area covers the LODs in front of it. Small portals stay inside the drawn terrain, but a dimension
 * stack's floor and ceiling ({@code VerticalConnectingPortal}, a 23333-block plane) do not: from the overworld the
 * nether's roof LODs were drawn over the distant overworld LODs, and from the nether the overworld's bottom over the
 * nether's (BUGS #6). The other dimension was also rendered every frame for those pixels alone.
 * <p>
 * So DH's depth texture is copied in every pass, right before DH applies its LODs ({@link #capture}, one copy per
 * portal layer, since a nested pass reuses DH's textures), and while IP draws a portal's view area into the stencil
 * the view-area shader ({@code portal_area_lod}) discards fragments behind that pass's LODs: it reconstructs the LOD's
 * distance from the camera with DH's inverse projection and compares it with the view-area fragment's. A portal hidden
 * everywhere by LODs is then not rendered at all (its occlusion query sees no samples).
 * <p>
 * Not with an Iris shader pack (the pack draws LODs into its own buffers). No DH types here: this class is loaded
 * whether or not DH is present.
 */
public final class IPDhLodOcclusion {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Master switch. */
    public static volatile boolean ENABLED = true;

    public static final String SAMPLER = "IPDhDepth";
    public static final String INVERSE_PROJECTION_UNIFORM = "IPDhInverseProjection";
    public static final String DEPTH_PARAMS_UNIFORM = "IPDhDepthParams";

    private static final int MAX_LAYERS = 16;
    private static final Slot[] SLOTS = new Slot[MAX_LAYERS];

    private static int readFramebuffer = 0;
    private static int drawFramebuffer = 0;
    private static boolean failed = false;

    private static final class Slot {
        int texture = 0;
        int width = -1;
        int height = -1;
        int internalFormat = -1;
        boolean checked = false;
        boolean valid = false;
        int frame = -1;
        final Matrix4f inverseProjection = new Matrix4f();
        float depthScale;
        float depthOffset;
        float emptyDepth;
    }

    private IPDhLodOcclusion() {
    }

    /**
     * IP entered a portal pass at {@code layer}: a capture held for that layer is from an earlier pass (a sibling
     * portal's) until this pass's own DH render replaces it.
     */
    public static void onPortalLayerPushed(int layer) {
        if (layer >= 0 && layer < MAX_LAYERS && SLOTS[layer] != null) {
            SLOTS[layer].valid = false;
        }
    }

    /**
     * Copies DH's depth texture for the current pass. Called right before DH applies its LODs to Minecraft's buffer.
     *
     * @param dhProjection DH's projection matrix for this pass
     * @param depthScale   NDC z = depth * depthScale + depthOffset
     * @param emptyDepth   the depth DH clears to (no LOD at that pixel)
     */
    public static void capture(int depthTexture, Matrix4f dhProjection, float depthScale, float depthOffset, float emptyDepth) {
        if (!ENABLED || failed || depthTexture <= 0) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        int layer = PortalRendering.getPortalLayer();
        if (layer >= MAX_LAYERS) {
            return;
        }
        Slot slot = SLOTS[layer];
        if (slot == null) {
            slot = SLOTS[layer] = new Slot();
        }
        slot.valid = false;
        if (IrisInterface.invoker.isShaders()) {
            return;
        }

        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            int internalFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (width <= 0 || height <= 0) {
                return;
            }
            if (slot.texture == 0 || slot.width != width || slot.height != height || slot.internalFormat != internalFormat) {
                allocate(slot, width, height, internalFormat);
            }

            if (readFramebuffer == 0) {
                readFramebuffer = GL30.glGenFramebuffers();
                drawFramebuffer = GL30.glGenFramebuffers();
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
            GL11.glReadBuffer(GL11.GL_NONE);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, slot.texture, 0);
            GL11.glDrawBuffer(GL11.GL_NONE);

            if (!slot.checked) {
                while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                    // drop errors raised before us
                }
                int readStatus = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
                int drawStatus = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                if (readStatus != GL30.GL_FRAMEBUFFER_COMPLETE || drawStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    fail("framebuffers incomplete (read 0x%x, draw 0x%x, depth format 0x%x)".formatted(readStatus, drawStatus, internalFormat));
                    return;
                }
            }

            if (scissor) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

            if (!slot.checked) {
                int error = GL11.glGetError();
                if (error != GL11.GL_NO_ERROR) {
                    fail("depth copy raised GL error 0x%x (depth format 0x%x, %dx%d)".formatted(error, internalFormat, width, height));
                    return;
                }
                slot.checked = true;
                LOGGER.info("[IPDhLodOcclusion] copying DH's depth ({}x{}, format 0x{}) for portal layer {}; portals behind LODs are hidden",
                    width, height, Integer.toHexString(internalFormat), layer);
            }

            slot.inverseProjection.set(dhProjection).invert();
            slot.depthScale = depthScale;
            slot.depthOffset = depthOffset;
            slot.emptyDepth = emptyDepth;
            slot.frame = RenderStates.frameIndex;
            slot.valid = true;
        }
        finally {
            if (scissor) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
    }

    private static void allocate(Slot slot, int width, int height, int internalFormat) {
        if (slot.texture == 0) {
            slot.texture = GL11.glGenTextures();
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, slot.texture);
        boolean withStencil = internalFormat == GL30.GL_DEPTH24_STENCIL8 || internalFormat == GL30.GL_DEPTH32F_STENCIL8;
        int format = withStencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
        int type = internalFormat == GL30.GL_DEPTH24_STENCIL8 ? GL30.GL_UNSIGNED_INT_24_8
            : internalFormat == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
            : GL11.GL_FLOAT;
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        slot.width = width;
        slot.height = height;
        slot.internalFormat = internalFormat;
        slot.checked = false;
    }

    private static void fail(String reason) {
        failed = true;
        for (Slot slot : SLOTS) {
            if (slot != null) {
                slot.valid = false;
            }
        }
        LOGGER.warn("[IPDhLodOcclusion] {}; portals are no longer hidden behind DH's LODs", reason);
    }

    /** DH's event handler could not run; stop trying. */
    public static void disable(Throwable cause) {
        if (!failed) {
            fail("capture failed: " + cause);
        }
    }

    /**
     * Binds this pass's LOD depth to the view-area shader {@code portal_area_lod}; false if this pass has none (no DH
     * render in it yet, an Iris shader pack, or a size mismatch), in which case the plain view-area shader is used.
     */
    public static boolean applyTo(ShaderInstance shader) {
        if (!ENABLED || failed) {
            return false;
        }
        int layer = PortalRendering.getPortalLayer();
        if (layer >= MAX_LAYERS) {
            return false;
        }
        Slot slot = SLOTS[layer];
        if (slot == null || !slot.valid || slot.frame != RenderStates.frameIndex || IrisInterface.invoker.isShaders()) {
            return false;
        }
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target.width != slot.width || target.height != slot.height) {
            return false;
        }
        Uniform inverseProjection = shader.getUniform(INVERSE_PROJECTION_UNIFORM);
        Uniform depthParams = shader.getUniform(DEPTH_PARAMS_UNIFORM);
        if (inverseProjection == null || depthParams == null) {
            return false;
        }
        shader.setSampler(SAMPLER, slot.texture);
        inverseProjection.set(slot.inverseProjection);
        depthParams.set(slot.depthScale, slot.depthOffset, slot.emptyDepth);
        return true;
    }
}
