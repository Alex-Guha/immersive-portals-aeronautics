/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IPIrisDepthFormatFix;
import qouteall.imm_ptl.core.compat.iris_compatibility.IPIrisRenderTypes;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalRenderer;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPPlaneClippingBufferSource;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Create Aeronautics' laser beams in front of portals with a shader pack. {@link IrisPortalRenderer} draws portal views
 * after the whole world, where the view area passes the depth test against the world's depth; Simulated's beam is
 * translucent but writes depth, so every pixel behind a beam was left out of the portal and showed this world through
 * it. The beam itself is drawn as Simulated draws it; only its geometry is read ({@link #beforeDraw}). In IP's copy of
 * the depth the view area is tested against, the beam's depth is replaced by the depth behind it where the beam is the
 * nearest thing ({@link #correctPortalDepth}), so the portal view covers those pixels; and the part of a beam in front of
 * a portal is drawn inside that portal's view ({@link #onStage}), mapped through the portal and cut to the camera's side
 * of it, through the same shader-pack pipeline as the beam's far part (a redraw over the finished image left a seam
 * where the two met).
 */
public final class IPLaserIrisPortals {
    /** Simulated's laser render type, set when Simulated first draws a laser; null without Simulated. */
    @Nullable
    private static RenderType laserType;

    /** A laser batch as drawn: positions relative to {@code camera} in {@code dimension}, with the draw's matrices. */
    private record Batch(float[] positions, float[] uvs, int[] colors, ResourceKey<Level> dimension, Vec3 camera,
                         Matrix4f modelView, Matrix4f projection) {
    }

    /** Recorded beam batches per portal layer (index: the layer whose world drew them). Render thread only. */
    private static final List<List<Batch>> BATCHES = new ArrayList<>();
    /** The depth before the current pass's first beam ({@link #takeDepthSnapshot}), and the layer it is for (-1: none). */
    @Nullable
    private static SecondaryFrameBuffer snapshot;
    private static int snapshotLayer = -1;

    private IPLaserIrisPortals() {
    }

    /** Simulated draws a laser: its render type, and the stage listener drawing beams in portal views. */
    public static void setLaserType(final RenderType type) {
        if (laserType == null) {
            // lowest priority: after IPLaserPortalsClient adds the beams' far parts at the same stage
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderLevelStageEvent.class, IPLaserIrisPortals::onStage);
        }
        laserType = type;
    }

    public static boolean isLaserType(final RenderType type) {
        return type == laserType;
    }

    /** @return whether IP draws with a shader pack ({@link IrisPortalRenderer}), so Iris's classes are there */
    public static boolean shaderPackInUse() {
        return IPCGlobal.renderer == IrisPortalRenderer.instance;
    }

    private static boolean active() {
        return IPCGlobal.renderer == IrisPortalRenderer.instance && !IrisInterface.invoker.isRenderingShadowMap();
    }

    /** {@code RenderType.draw} of the laser type: record the batch for the portal views (the draw is left as it is). */
    public static void beforeDraw(final MeshData mesh) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (!active() || minecraft.level == null || mesh.drawState().format() != DefaultVertexFormat.POSITION_TEX_COLOR) {
            return;
        }
        // Position (3 floats), UV0 (2 floats), Color (4 bytes)
        final ByteBuffer source = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        final int count = mesh.drawState().vertexCount();
        final int stride = DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
        final float[] positions = new float[count * 3];
        final float[] uvs = new float[count * 2];
        final int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            final int base = source.position() + i * stride;
            positions[i * 3] = source.getFloat(base);
            positions[i * 3 + 1] = source.getFloat(base + 4);
            positions[i * 3 + 2] = source.getFloat(base + 8);
            uvs[i * 2] = source.getFloat(base + 12);
            uvs[i * 2 + 1] = source.getFloat(base + 16);
            colors[i] = source.getInt(base + 20);
        }
        final int layer = PortalRendering.getPortalLayer();
        if (snapshotLayer < 0) {
            takeDepthSnapshot(layer);
        }
        while (BATCHES.size() <= layer) {
            BATCHES.add(new ArrayList<>());
        }
        BATCHES.get(layer).add(new Batch(positions, uvs, colors, minecraft.level.dimension(),
                minecraft.gameRenderer.getMainCamera().getPosition(), new Matrix4f(RenderSystem.getModelViewMatrix()),
                new Matrix4f(RenderSystem.getProjectionMatrix())));
    }

    /**
     * The depth before a pass's first beam is drawn (the beam's {@code RenderType.draw}, before its state is set up), so
     * {@link #correctPortalDepth} knows what lies behind the beams. Taken once per pass, consumed by that pass's
     * {@link #correctPortalDepth} (which runs before the portal passes nested in it).
     */
    private static void takeDepthSnapshot(final int layer) {
        final RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (snapshot == null) {
            snapshot = new SecondaryFrameBuffer();
        }
        snapshot.prepare(main.width, main.height);
        IPIrisDepthFormatFix.matchDeferredDepthFormat(main, snapshot.fb);
        final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, snapshot.fb.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        snapshotLayer = layer;
    }

    /**
     * Before the portal areas of the pass at {@code layer} are decided ({@link IrisPortalRenderer}): where one of the
     * pass's beams is the nearest thing on screen, {@code deferred}'s depth (a copy of {@code main}'s) becomes the depth
     * from before the pass's first beam ({@link #takeDepthSnapshot}). Everything else keeps its depth.
     */
    public static void correctPortalDepth(final int layer, final RenderTarget main, final RenderTarget deferred) {
        final ShaderInstance shader = MyRenderHelper.portalBeamDepthShader;
        final boolean hasSnapshot = snapshotLayer == layer && snapshot != null;
        snapshotLayer = -1;
        if (layer >= BATCHES.size() || BATCHES.get(layer).isEmpty() || shader == null || !hasSnapshot) {
            return;
        }
        deferred.bindWrite(true);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        shader.setSampler("IPMainDepth", main.getDepthTextureId());
        shader.setSampler("IPBeforeBeamsDepth", snapshot.fb.getDepthTextureId());
        for (final Batch batch : BATCHES.get(layer)) {
            final int count = batch.colors().length;
            if (count < 4) {
                continue;
            }
            final BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            final float[] p = batch.positions();
            for (int i = 0; i < count; i++) {
                builder.addVertex(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
            }
            final MeshData mesh = builder.build();
            if (mesh == null) {
                continue;
            }
            shader.MODEL_VIEW_MATRIX.set(batch.modelView());
            shader.PROJECTION_MATRIX.set(batch.projection());
            shader.apply();
            BufferUploader.draw(mesh);
            shader.clear();
        }
        RenderSystem.enableCull();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.colorMask(true, true, true, true);
    }

    /**
     * In a portal pass, the beams of the world around the portal: each vertex taken to world space, through the portal
     * and to this pass's camera, cut at the portal's plane on the side the camera looks from. Drawn with the pass's own
     * laser batch, so the shader pack treats them as the beams of this world. Translucent and depth-writing like every
     * beam, and always the nearest beams in the view, they must be drawn after the beams' far parts, which
     * {@link IPLaserPortalsClient} adds at the same stage (drawn first, they hid the far part where it was seen through
     * them, a dark square at the portal's plane). With a shader pack the buffer source is Iris's fully buffered one,
     * which ignores {@code endBatch(type)} and draws everything later, one group per render type with translucent quads
     * sorted by centre within it: the copy is wrapped as Iris wraps block entities' draws, so it shares the group of the
     * pass's own beams ({@link IPIrisRenderTypes#asBlockEntity}; as a plain laser type its group was drawn first), and
     * added in short pieces ({@link #addInPieces}). A later stage misses Iris's flush (the copy was drawn with a later
     * pass, drifting while walking), and drawing it at once from a buffer of our own after the pack's processing lost it.
     */
    private static void onStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || laserType == null || !active()
                || !PortalRendering.isRendering()) {
            return;
        }
        final int outer = PortalRendering.getPortalLayer() - 1;
        if (outer < 0 || outer >= BATCHES.size() || BATCHES.get(outer).isEmpty()) {
            return;
        }
        final Portal portal = PortalRendering.getRenderingPortal();
        final Vec3 camera = event.getCamera().getPosition();
        final Vec3 destination = portal.getDestPos();
        final Vec3 planeNormal = portal.transformLocalVecNonScale(portal.getNormal());
        // keep the side the pass's camera is on: the near side of the portal, mapped
        final Vec3 kept = planeNormal.dot(camera.subtract(destination)) >= 0.0 ? planeNormal : planeNormal.scale(-1.0);
        final MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        // in the same group of Iris's buffer as the pass's own beams, which Iris draws as block entities
        final IPPlaneClippingBufferSource clipped = new IPPlaneClippingBufferSource(IPIrisRenderTypes.asBlockEntity(buffers),
                new Vector3f((float) kept.x, (float) kept.y, (float) kept.z),
                new Vector3f((float) (destination.x - camera.x), (float) (destination.y - camera.y), (float) (destination.z - camera.z)));
        boolean drew = false;
        for (final Batch batch : BATCHES.get(outer)) {
            if (batch.dimension() != portal.getOriginWorld().dimension()) {
                continue;
            }
            final VertexConsumer consumer = clipped.getBuffer(laserType);
            final float[] p = batch.positions();
            final int count = batch.colors().length;
            final Vector3f[] mapped = new Vector3f[count];
            for (int i = 0; i < count; i++) {
                final Vec3 point = portal.transformPoint(new Vec3(p[i * 3] + batch.camera().x, p[i * 3 + 1] + batch.camera().y,
                        p[i * 3 + 2] + batch.camera().z)).subtract(camera);
                mapped[i] = new Vector3f((float) point.x, (float) point.y, (float) point.z);
            }
            for (int q = 0; q + 3 < count; q += 4) {
                addInPieces(consumer, batch, mapped, q);
            }
            drew = true;
        }
        clipped.flush();
        if (drew) {
            buffers.endBatch(laserType);
        }
    }

    /**
     * Adds quad {@code q} of {@code batch} (positions {@code mapped}) in pieces at most a block long along its long sides.
     * With a shader pack the copy shares Iris's buffer with the pass's own beams, which Iris sorts by quad centre: a beam
     * quad runs the whole beam, and one running far past the camera had its centre beyond the far beam's, so the copy was
     * drawn first and its depth hid the far beam seen through it (a dark square at the portal's plane). Colour and the
     * taper coordinate change linearly along a beam, so the pieces look the same.
     */
    private static void addInPieces(final VertexConsumer consumer, final Batch batch, final Vector3f[] mapped, final int q) {
        // the quad is a0, a1, b1, b0 in order; a and b are its short ends
        final boolean firstEdgeShort = mapped[q].distance(mapped[q + 1]) + mapped[q + 2].distance(mapped[q + 3])
                <= mapped[q + 1].distance(mapped[q + 2]) + mapped[q + 3].distance(mapped[q]);
        final int a0 = firstEdgeShort ? q : q + 1;
        final int a1 = firstEdgeShort ? q + 1 : q + 2;
        final int b1 = firstEdgeShort ? q + 2 : q + 3;
        final int b0 = firstEdgeShort ? q + 3 : q;
        final float length = Math.max(mapped[a0].distance(mapped[b0]), mapped[a1].distance(mapped[b1]));
        final int pieces = Math.max(1, Math.min(256, (int) Math.ceil(length)));
        for (int k = 0; k < pieces; k++) {
            final float t0 = (float) k / pieces;
            final float t1 = (float) (k + 1) / pieces;
            addLerped(consumer, batch, mapped, a0, b0, t0);
            addLerped(consumer, batch, mapped, a1, b1, t0);
            addLerped(consumer, batch, mapped, a1, b1, t1);
            addLerped(consumer, batch, mapped, a0, b0, t1);
        }
    }

    private static void addLerped(final VertexConsumer consumer, final Batch batch, final Vector3f[] mapped,
                                  final int from, final int to, final float t) {
        final Vector3f position = new Vector3f(mapped[from]).lerp(mapped[to], t);
        final float[] uv = batch.uvs();
        final int c0 = batch.colors()[from];
        final int c1 = batch.colors()[to];
        consumer.addVertex(position.x, position.y, position.z)
                .setUv(uv[from * 2] + (uv[to * 2] - uv[from * 2]) * t, uv[from * 2 + 1] + (uv[to * 2 + 1] - uv[from * 2 + 1]) * t)
                .setColor(lerpChannel(c0, c1, 0, t), lerpChannel(c0, c1, 8, t), lerpChannel(c0, c1, 16, t), lerpChannel(c0, c1, 24, t));
    }

    private static int lerpChannel(final int from, final int to, final int shift, final float t) {
        final int a = (from >>> shift) & 0xFF;
        final int b = (to >>> shift) & 0xFF;
        return Math.round(a + (b - a) * t);
    }

    /** Forgets the beams the world of {@code layer} drew, once its portals are done. */
    public static void clear(final int layer) {
        if (layer < BATCHES.size()) {
            BATCHES.get(layer).clear();
        }
    }

    /** Forgets every recorded beam (start of a frame). */
    public static void clearAll() {
        BATCHES.forEach(List::clear);
        snapshotLayer = -1;
    }
}
