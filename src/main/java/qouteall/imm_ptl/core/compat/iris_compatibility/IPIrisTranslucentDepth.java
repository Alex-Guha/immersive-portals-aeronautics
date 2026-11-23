/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.Arrays;

/**
 * Translucent things in front of a portal with a shader pack. {@link IrisPortalRenderer} draws portal views after the
 * whole world and decides a portal's area by testing it against the world's depth; translucent terrain writes depth
 * (water, ice, stained glass, and plain glass under packs that move it to the translucent layer), so the area failed
 * behind it and those pixels kept this world: a portal was invisible through glass. Particles and clouds in front of a
 * portal cut holes into it the same way.
 * <p>
 * Iris copies the depth just before the translucent stage ({@code depthtex1}, after opaque terrain, entities, block
 * entities and the solid hand). That copy is recorded per portal layer ({@link #onBeginTranslucents}), and the depth a
 * pass's portal areas are decided against becomes it ({@link #correctPortalDepth}), so the view shows through anything
 * translucent in front of the portal. What is translucent in front of the portal is then covered by the view over the
 * portal's area, the same as everything drawn in front of a portal with a shader pack (front-loss): the view is drawn
 * after the pack has finished this world's image, so it cannot go behind this world's translucents. A translucent held
 * item keeps its depth, so the view never covers the hand: Iris draws it after its copy, and the depth from just before
 * it ({@link #beforeTranslucentHand}) shows where it is.
 */
public final class IPIrisTranslucentDepth {
    /** Per portal layer, Iris's pre-translucent depth texture of the last pass at that layer (0: none recorded). */
    private static int[] preTranslucent = new int[0];
    /** The main framebuffer's depth from just before Iris drew a translucent held item, and its layer (-1: none). */
    @Nullable
    private static SecondaryFrameBuffer beforeHand;
    private static int beforeHandLayer = -1;
    /** A framebuffer with only a pre-translucent depth texture attached, for the occlusion test of a layer-0 portal. */
    private static int testFramebuffer;
    private static int testTexture;
    private static boolean testComplete;

    private IPIrisTranslucentDepth() {
    }

    private static boolean active() {
        return IPCGlobal.renderer == IrisPortalRenderer.instance && !IrisInterface.invoker.isRenderingShadowMap();
    }

    /** End of Iris's {@code beginTranslucents}: {@code texture} holds this pass's depth without translucents. */
    public static void onBeginTranslucents(int texture) {
        if (!active()) {
            return;
        }
        int layer = PortalRendering.getPortalLayer();
        if (preTranslucent.length <= layer) {
            preTranslucent = Arrays.copyOf(preTranslucent, layer + 1);
        }
        preTranslucent[layer] = texture;
    }

    /** Iris is about to draw a translucent held item into the main framebuffer: keep the depth before it. */
    public static void beforeTranslucentHand() {
        if (!active()) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (beforeHand == null) {
            beforeHand = new SecondaryFrameBuffer();
        }
        beforeHand.prepare(main.width, main.height);
        IPIrisDepthFormatFix.matchDeferredDepthFormat(main, beforeHand.fb);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, beforeHand.fb.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height,
            GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        beforeHandLayer = PortalRendering.getPortalLayer();
    }

    /** @return the pre-translucent depth texture of the pass at {@code layer}, or 0 when Iris gave none */
    private static int preTranslucentDepth(int layer) {
        return layer < preTranslucent.length ? preTranslucent[layer] : 0;
    }

    /**
     * @return the depth texture of the nearest opaque thing on screen for the pass at {@code layer}: the
     * pre-translucent depth, or {@code main}'s depth when there is none
     */
    public static int nearestOpaqueDepth(int layer, RenderTarget main) {
        int texture = preTranslucentDepth(layer);
        return texture != 0 ? texture : main.getDepthTextureId();
    }

    /**
     * Before the portal areas of the pass at {@code layer} are decided: {@code deferred}'s depth (a copy of
     * {@code main}'s) becomes the pass's depth without translucents, except where a translucent held item was drawn.
     */
    public static void correctPortalDepth(int layer, RenderTarget main, RenderTarget deferred) {
        int texture = preTranslucentDepth(layer);
        ShaderInstance shader = MyRenderHelper.portalTranslucentDepthShader;
        if (texture == 0 || shader == null) {
            return;
        }
        // without a held translucent item the main depth is compared with itself, and every pixel is corrected
        int handTexture = beforeHandLayer == layer && beforeHand != null
            ? beforeHand.fb.getDepthTextureId() : main.getDepthTextureId();

        deferred.bindWrite(false);
        RenderSystem.viewport(0, 0, deferred.width, deferred.height);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        shader.setSampler("IPMainDepth", main.getDepthTextureId());
        shader.setSampler("IPPreTranslucentDepth", texture);
        shader.setSampler("IPBeforeHandDepth", handTexture);
        shader.apply();
        BufferBuilder builder = RenderSystem.renderThreadTesselator()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        builder.addVertex(0.0f, 0.0f, 0.0f);
        builder.addVertex(1.0f, 0.0f, 0.0f);
        builder.addVertex(1.0f, 1.0f, 0.0f);
        builder.addVertex(0.0f, 1.0f, 0.0f);
        BufferUploader.draw(builder.buildOrThrow());
        shader.clear();

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.colorMask(true, true, true, true);
    }

    /**
     * Binds a framebuffer whose depth is the pre-translucent depth of the pass at {@code layer}, for a portal's
     * occlusion test drawn without colour or depth writes.
     *
     * @return false when there is none (the caller tests against the main framebuffer)
     */
    public static boolean bindOcclusionTestTarget(int layer) {
        int texture = preTranslucentDepth(layer);
        if (texture == 0) {
            return false;
        }
        if (testFramebuffer == 0) {
            testFramebuffer = GL30.glGenFramebuffers();
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, testFramebuffer);
        // attached on every use: Iris may have deleted and remade its texture under the same name
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
        if (texture != testTexture) {
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);
            testComplete = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
            testTexture = texture;
        }
        if (!testComplete) {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
            return false;
        }
        return true;
    }

    /** Forgets what the passes at {@code layer} recorded, once that pass's portals are done. */
    public static void clear(int layer) {
        if (layer < preTranslucent.length) {
            preTranslucent[layer] = 0;
        }
        if (beforeHandLayer == layer) {
            beforeHandLayer = -1;
        }
    }

    /** Forgets everything recorded (start of a frame). */
    public static void clearAll() {
        Arrays.fill(preTranslucent, 0);
        beforeHandLayer = -1;
    }
}
