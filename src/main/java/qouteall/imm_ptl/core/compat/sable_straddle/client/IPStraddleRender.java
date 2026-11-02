/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The approach (a second hardware clip plane per ship, the far half drawn by the destination's level renderer with the
 * ship's pose mapped through the portal) follows IPSable 0.5.0 by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import com.mojang.blaze3d.shaders.Uniform;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.List;

/**
 * Client rendering of straddling ships (docs/lessons/straddle-rendering.md). Render thread only.
 * <ul>
 *     <li><b>Source side:</b> a ship with a session is drawn by its own level's renderer with a second clip plane
 *     ({@code gl_ClipDistance[1]}, uniform {@code ipa_ShipClipEquation}) that keeps the part before the portal plane.
 *     IP's portal clip stays on {@code gl_ClipDistance[0]}.</li>
 *     <li><b>Far side:</b> each level renderer pass also draws the ships straddling into its dimension from their own
 *     dimension's client level, with {@code renderPose()} returning the pose mapped through the portal and the clip
 *     plane mirrored, so only the part past the destination plane shows. Same compiled geometry, no copy.</li>
 *     <li><b>Block entities</b> are kept or dropped whole, by block centre, on either side (they are batched and drawn
 *     after the ship, outside any per-ship clip).</li>
 *     <li><b>Single-block ships</b> are cut on the CPU, on both sides ({@link #drawSingleBlock}): Sable draws them in a
 *     batch after the section layers.</li>
 * </ul>
 * Only the vanilla terrain shaders and an Iris shader pack's terrain programs carry the plane
 * (shader_transformation.json). A shader without it (a pack's shadow programs) gets no source cut, and no projection is
 * drawn with it.
 */
public final class IPStraddleRender {
    public static final String CLIP_UNIFORM = "ipa_ShipClipEquation";
    /**
     * How far the cut sits off the portal plane. Cut exactly on the plane, a ship's faces end at the depth of the
     * portal's own surface and flicker against it along the cut. Seen directly, a ship is cut this much before the
     * plane (its edge stays clear of the portal surface); seen through a portal it is cut this much past it, so both
     * views still meet. Same size as IP's portal clip adjustment.
     */
    private static final double SEAM = FrontClipping.ADJUSTMENT;

    /**
     * A plane in world space: points with {@code normal . (p - point) >= 0} are kept.
     */
    public record KeepPlane(Vec3 point, Vec3 normal) {
        boolean keeps(final Vec3 p) {
            return this.normal.dot(p.subtract(this.point)) >= 0.0;
        }

        /** Signed distance of {@code p} from the plane, positive on the kept side. */
        public double distance(final Vec3 p) {
            return this.normal.dot(p.subtract(this.point));
        }
    }

    private record Projection(ClientSubLevel ship, Pose3dc pose, KeepPlane plane, IPPortalIsometry isometry,
                              IPStraddleClientSessions.Session session) {
    }

    @Nullable
    private static Projection projection;

    private IPStraddleRender() {
    }

    public static boolean isProjecting() {
        return projection != null;
    }

    /**
     * @return the pose to draw {@code ship} with right now, when it differs from its own (a projection draw)
     */
    @Nullable
    public static Pose3dc overridePose(final ClientSubLevel ship) {
        final Projection current = projection;
        return current != null && current.ship() == ship ? current.pose() : null;
    }

    /**
     * @return the portal map of the projection of {@code ship} being drawn now, or null when it is not projected
     */
    @Nullable
    public static IPPortalIsometry projectionIsometry(final ClientSubLevel ship) {
        final Projection current = projection;
        return current != null && current.ship() == ship ? current.isometry() : null;
    }

    /**
     * @return how far [blocks] the kept side of a cut reaches past its plane in the current pass: {@value #SEAM} short
     * of it when drawn directly, as far past it inside a portal pass, so a cut edge never lies in the portal surface
     * (z-fighting) while both views still meet
     */
    public static double seamReach() {
        return PortalRendering.isRendering() ? SEAM : -SEAM;
    }

    /**
     * @return the session whose far half of {@code ship} is being drawn now, or null when it is not projected
     */
    @Nullable
    public static IPStraddleClientSessions.Session projectionSession(final ClientSubLevel ship) {
        final Projection current = projection;
        return current != null && current.ship() == ship ? current.session() : null;
    }

    /**
     * @return the pose {@code ship} has in its own world now, also while its projection is drawn (when
     * {@code renderPose()} answers the mapped pose)
     */
    public static Pose3dc realRenderPose(final ClientSubLevel ship) {
        final Projection current = projection;
        return current != null && current.ship() == ship ? current.isometry().unmapPose(current.pose()) : ship.renderPose();
    }

    /**
     * @return the plane cutting {@code ship} in the draw happening now, or null when it is drawn whole
     */
    @Nullable
    public static KeepPlane planeFor(final ClientSubLevel ship) {
        final Projection current = projection;
        if (current != null) {
            return current.ship() == ship ? current.plane() : null;
        }
        final List<IPStraddleClientSessions.Session> sessions = IPStraddleClientSessions.renderSessionsOf(ship);
        if (sessions.isEmpty()) {
            return null;
        }
        // one plane per ship (a ship straddling two portals at once is cut at the first only)
        final IPStraddleClientSessions.Session session = sessions.get(0);
        return new KeepPlane(session.origin(), session.normal().scale(-1.0));
    }

    /**
     * Sets the ship clip plane into {@code shader} for the ship about to be drawn and enables it.
     *
     * @return whether the plane was enabled (then {@link #endShipDraw} must be called)
     */
    public static boolean beginShipDraw(final ClientSubLevel ship, final ShaderInstance shader,
                                        final double camX, final double camY, final double camZ) {
        final KeepPlane plane = planeFor(ship);
        if (plane == null) {
            return false;
        }
        final Uniform uniform = shader.getUniform(CLIP_UNIFORM);
        if (uniform == null) {
            return false;
        }
        if (facesPortalClipAway(plane)) {
            // In this portal view IP keeps what is beyond the portal plane and the cut keeps the ship's side before
            // that same plane: only a sliver around the plane would survive (the ship's outline). Draw nothing.
            uniform.set(0.0f, 0.0f, 0.0f, -1.0f);
            uniform.upload();
            GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
            return true;
        }
        // Ship terrain vertices are Position + ChunkOffset = R^-1 (world - camera), R the render orientation (the
        // translation is folded into ChunkOffset; MixinSableChunkedRenderData_ClipSpace relies on the same).
        final Vector3d n = ship.renderPose().orientation().transformInverse(
                new Vector3d(plane.normal().x, plane.normal().y, plane.normal().z));
        final Vec3 rel = plane.point().subtract(camX, camY, camZ);
        final double w = -plane.normal().dot(rel) + (PortalRendering.isRendering() ? SEAM : -SEAM);
        uniform.set((float) n.x, (float) n.y, (float) n.z, (float) w);
        uniform.upload();
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
        return true;
    }

    /**
     * The straddle cut of a ship's Flywheel parts in this pass, for {@code IPFlywheelStraddleClip}: the plane relative to
     * Flywheel's render origin ({@code flw_vertexPos} is world-oriented there), or null when the ship of that embedding
     * is not cut.
     *
     * @param embeddingOrigin the render origin of the ship's Flywheel embedding (a block in its plot)
     */
    @Nullable
    public static float[] flywheelPlane(final ClientLevel level, final Vec3i embeddingOrigin, final Vec3i renderOrigin) {
        if (IPStraddleClientSessions.isEmpty()) {
            return null;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        final LevelPlot plot = container.getPlot(new ChunkPos(new BlockPos(embeddingOrigin)));
        if (plot == null || !(plot.getSubLevel() instanceof final ClientSubLevel ship)) {
            return null;
        }
        final KeepPlane plane = planeFor(ship);
        if (plane == null) {
            return null;
        }
        if (facesPortalClipAway(plane)) {
            return new float[]{0f, 0f, 0f, -1f};
        }
        final Vec3 n = plane.normal();
        final Vec3 rel = plane.point().subtract(renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ());
        final double w = -n.dot(rel) + (PortalRendering.isRendering() ? SEAM : -SEAM);
        return new float[]{(float) n.x, (float) n.y, (float) n.z, (float) w};
    }

    /**
     * The cut of a ship's Flywheel parts when its own dimension's Flywheel engine draws them as the far half in the
     * destination ({@code IPFlywheelFarHalf}): keeps the part past the portal plane, in the ship's own dimension
     * relative to that engine's render origin; like the terrain far half ({@link #forEachProjection}).
     */
    public static float[] flywheelFarHalfPlane(final IPStraddleClientSessions.Session session, final Vec3i renderOrigin) {
        if (facesPortalClipAway(new KeepPlane(session.destOrigin(), session.destNormal()))) {
            return new float[]{0f, 0f, 0f, -1f};
        }
        final Vec3 n = session.normal();
        final Vec3 rel = session.origin().subtract(renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ());
        final double w = -n.dot(rel) + (PortalRendering.isRendering() ? SEAM : -SEAM);
        return new float[]{(float) n.x, (float) n.y, (float) n.z, (float) w};
    }

    /**
     * @return the client copy of the ship of {@code session}, in its own dimension, or null
     */
    @Nullable
    public static ClientSubLevel shipOf(final IPStraddleClientSessions.Session session) {
        return findShip(session);
    }

    /**
     * @return whether, in the current portal pass, IP's clip plane is the cut plane itself facing the other way (the
     * kept halves meet only on the plane)
     */
    static boolean facesPortalClipAway(final KeepPlane plane) {
        if (!PortalRendering.isRendering()) {
            return false;
        }
        final Plane clip = PortalRendering.getActiveClippingPlane();
        return clip != null
                && clip.normal().dot(plane.normal()) < -0.999
                && Math.abs(clip.normal().dot(plane.point().subtract(clip.pos()))) < 0.05;
    }

    public static void endShipDraw(final ShaderInstance shader) {
        GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        final Uniform uniform = shader.getUniform(CLIP_UNIFORM);
        if (uniform != null) {
            uniform.set(0.0f, 0.0f, 0.0f, 1.0f);
            uniform.upload();
        }
    }

    /**
     * Whether a block entity of {@code ship} at {@code pos} (plot space) is drawn in the current pass: by its centre,
     * against the ship's plane in this pass.
     */
    public static boolean keepsBlockEntity(final ClientSubLevel ship, final BlockPos pos) {
        final KeepPlane plane = planeFor(ship);
        return plane == null || plane.keeps(ship.renderPose().transformPosition(Vec3.atCenterOf(pos)));
    }

    /**
     * Whether an entity placed on a ship (in its plot: Contraption Diagrams, item frames, paintings) is hidden in the
     * current pass: its centre is past the cut of a straddling ship (or the ship is not drawn in this portal view).
     * Their far half is not drawn in the destination.
     */
    public static boolean cutsShipEntity(final net.minecraft.world.entity.Entity entity, final float partialTick) {
        if (IPStraddleClientSessions.isEmpty()) {
            return false;
        }
        if (!(dev.ryanhcode.sable.Sable.HELPER.getContaining(entity) instanceof final ClientSubLevel ship)) {
            return false;
        }
        final KeepPlane plane = planeFor(ship);
        if (plane == null) {
            return false;
        }
        if (facesPortalClipAway(plane)) {
            return true;
        }
        final Vec3 center = entity.getPosition(partialTick).add(0.0, entity.getBbHeight() * 0.5, 0.0);
        return !plane.keeps(ship.renderPose().transformPosition(center));
    }

    /** Block entities whose centre is this far [blocks] from a ship's cut are drawn whole or not at all. */
    private static final double BLOCK_ENTITY_CLIP_REACH = 2.0;
    /** Render boxes larger than this [blocks] along an axis (beacons: infinite) are not used to place a block entity. */
    private static final double MAX_RENDER_BOX = 512.0;

    /**
     * @return whether nothing of {@code ship} on the side kept in this draw can be seen in the current portal pass: IP's
     * clip plane is the ship's cut plane facing the other way (only a sliver of the seam would survive)
     */
    public static boolean rejectsInPortalPass(final ClientSubLevel ship) {
        final KeepPlane plane = planeFor(ship);
        return plane != null && facesPortalClipAway(plane);
    }

    /** How a ship's block entity is drawn in the current pass ({@link #blockEntityCut}). */
    public enum BlockEntityCut {
        WHOLE, SKIP, CLIP
    }

    /**
     * @return {@code WHOLE} when the block entity has no cut in this pass or its centre is well on the kept side,
     * {@code SKIP} when it is well past the cut (or the pass shows nothing of the kept side), else {@code CLIP}: its quads
     * and lines are clipped at the cut ({@link #clippingSource}; block entities are drawn in a batch after the ship's
     * clip plane is gone). A block entity drawing far beyond its block (Shield Generators' field around the whole ship)
     * is placed by its render box instead of its centre ({@link #largeRenderBox}).
     */
    public static BlockEntityCut blockEntityCut(final net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        if (blockEntity.getLevel() == null) {
            return BlockEntityCut.WHOLE;
        }
        final ClientSubLevel ship = cutShipAt(blockEntity.getLevel(), blockEntity.getBlockPos());
        final KeepPlane plane = ship == null ? null : planeFor(ship);
        if (plane == null) {
            return BlockEntityCut.WHOLE;
        }
        if (facesPortalClipAway(plane)) {
            return BlockEntityCut.SKIP;
        }
        final net.minecraft.world.phys.AABB box = largeRenderBox(blockEntity);
        if (box != null) {
            final double[] range = distanceRange(ship.renderPose(), box, plane.point(), plane.normal());
            return range[0] >= 0.0 ? BlockEntityCut.WHOLE : range[1] < 0.0 ? BlockEntityCut.SKIP : BlockEntityCut.CLIP;
        }
        final double centre = plane.distance(ship.renderPose().transformPosition(Vec3.atCenterOf(blockEntity.getBlockPos())));
        return centre > BLOCK_ENTITY_CLIP_REACH ? BlockEntityCut.WHOLE
                : centre < -BLOCK_ENTITY_CLIP_REACH ? BlockEntityCut.SKIP : BlockEntityCut.CLIP;
    }

    /**
     * How a block entity on {@code ship} is drawn against IP's portal plane in a portal pass: by its centre
     * ({@link #keepsInPortalPass}: {@code WHOLE} or {@code SKIP}); one drawing far beyond its block by its render box, and
     * {@code CLIP} when that crosses the plane. Batched block entities with IP's shaders are clipped on the GPU anyway;
     * a large one may draw with shaders IP's plane does not reach (Shield Generators: {@code position_color} and
     * {@code rendertype_lines}), so it is clipped on the CPU ({@link #clippingSource}).
     */
    public static BlockEntityCut portalPassCut(final SubLevel ship, final net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        if (!PortalRendering.isRendering() || !(ship instanceof final ClientSubLevel clientShip)) {
            return BlockEntityCut.WHOLE;
        }
        final Plane clip = PortalRendering.getActiveClippingPlane();
        if (clip == null) {
            return BlockEntityCut.WHOLE;
        }
        final net.minecraft.world.phys.AABB box = largeRenderBox(blockEntity);
        if (box == null) {
            return keepsInPortalPass(ship, blockEntity.getBlockPos()) ? BlockEntityCut.WHOLE : BlockEntityCut.SKIP;
        }
        final double[] range = distanceRange(clientShip.renderPose(), box, clip.pos(), clip.normal());
        return range[0] >= 0.0 ? BlockEntityCut.WHOLE : range[1] < 0.0 ? BlockEntityCut.SKIP : BlockEntityCut.CLIP;
    }

    /**
     * @return the render box (plot space) of a block entity that draws well beyond its own block, or null for one that
     * does not or whose box is too large to place it (infinite boxes)
     */
    @Nullable
    private static net.minecraft.world.phys.AABB largeRenderBox(final net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        final net.minecraft.client.renderer.blockentity.BlockEntityRenderer<net.minecraft.world.level.block.entity.BlockEntity> renderer =
                Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(blockEntity);
        if (renderer == null) {
            return null;
        }
        final net.minecraft.world.phys.AABB box = renderer.getRenderBoundingBox(blockEntity);
        if (box == null || !(box.getXsize() <= MAX_RENDER_BOX && box.getYsize() <= MAX_RENDER_BOX && box.getZsize() <= MAX_RENDER_BOX)) {
            return null;
        }
        final BlockPos pos = blockEntity.getBlockPos();
        final double reach = BLOCK_ENTITY_CLIP_REACH;
        final boolean small = box.minX >= pos.getX() - reach && box.minY >= pos.getY() - reach && box.minZ >= pos.getZ() - reach
                && box.maxX <= pos.getX() + 1 + reach && box.maxY <= pos.getY() + 1 + reach && box.maxZ <= pos.getZ() + 1 + reach;
        return small ? null : box;
    }

    /**
     * @return {min, max} of the signed distance from the plane ({@code point}, {@code normal}) of the corners of
     * {@code box} (plot space), placed with {@code pose}
     */
    private static double[] distanceRange(final Pose3dc pose, final net.minecraft.world.phys.AABB box, final Vec3 point, final Vec3 normal) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final Vec3 corner = pose.transformPosition(new Vec3(
                    (i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ));
            final double distance = normal.dot(corner.subtract(point));
            min = Math.min(min, distance);
            max = Math.max(max, distance);
        }
        return new double[]{min, max};
    }

    /**
     * A buffer source clipping a block entity's quads at its ship's cut in this pass (see {@link #blockEntityCut}), or
     * {@code source} when it has none. {@code poseStack} is the block entity's (block local to drawing space): the plane
     * is placed through it, so no camera position is needed.
     */
    public static net.minecraft.client.renderer.MultiBufferSource clippingSource(
            final net.minecraft.world.level.block.entity.BlockEntity blockEntity, final com.mojang.blaze3d.vertex.PoseStack poseStack,
            final net.minecraft.client.renderer.MultiBufferSource source) {
        return clippingSource(blockEntity, poseStack, source, true, false);
    }

    /**
     * A buffer source clipping a block entity's quads and lines at its ship's cut in this pass ({@code atCut}, see
     * {@link #blockEntityCut}) and at IP's portal plane ({@code atPortal}, see {@link #portalPassCut}), or {@code source}
     * when neither applies. {@code poseStack} is the block entity's (block local to drawing space): the planes are
     * placed through it, so no camera position is needed.
     */
    public static net.minecraft.client.renderer.MultiBufferSource clippingSource(
            final net.minecraft.world.level.block.entity.BlockEntity blockEntity, final com.mojang.blaze3d.vertex.PoseStack poseStack,
            final net.minecraft.client.renderer.MultiBufferSource source, final boolean atCut, final boolean atPortal) {
        if (blockEntity.getLevel() == null) {
            return source;
        }
        final BlockPos pos = blockEntity.getBlockPos();
        net.minecraft.client.renderer.MultiBufferSource result = source;
        if (atCut) {
            final ClientSubLevel ship = cutShipAt(blockEntity.getLevel(), pos);
            final KeepPlane plane = ship == null ? null : planeFor(ship);
            if (plane != null) {
                final org.joml.Vector3f[] drawing = drawingPlane(ship, pos, poseStack,
                        plane.point().subtract(plane.normal().scale(seamReach())), plane.normal());
                result = new IPPlaneClippingBufferSource(result, drawing[0], drawing[1]);
            }
        }
        if (atPortal && dev.ryanhcode.sable.Sable.HELPER.getContaining(blockEntity) instanceof final ClientSubLevel containing) {
            result = clippingAtPortal(containing, pos, poseStack, result);
        }
        return result;
    }

    /** A buffer source clipping what a block at {@code pos} on {@code ship} draws at {@code plane} and at IP's portal plane. */
    static net.minecraft.client.renderer.MultiBufferSource clippingSourceAt(
            final ClientSubLevel ship, final BlockPos pos, final com.mojang.blaze3d.vertex.PoseStack poseStack,
            final net.minecraft.client.renderer.MultiBufferSource source, final KeepPlane plane) {
        final org.joml.Vector3f[] drawing = drawingPlane(ship, pos, poseStack,
                plane.point().subtract(plane.normal().scale(seamReach())), plane.normal());
        return clippingAtPortal(ship, pos, poseStack, new IPPlaneClippingBufferSource(source, drawing[0], drawing[1]));
    }

    private static net.minecraft.client.renderer.MultiBufferSource clippingAtPortal(
            final ClientSubLevel ship, final BlockPos pos, final com.mojang.blaze3d.vertex.PoseStack poseStack,
            final net.minecraft.client.renderer.MultiBufferSource source) {
        final Plane clip = PortalRendering.isRendering() ? PortalRendering.getActiveClippingPlane() : null;
        if (clip == null) {
            return source;
        }
        final org.joml.Vector3f[] drawing = drawingPlane(ship, pos, poseStack, clip.pos(), clip.normal());
        return new IPPlaneClippingBufferSource(source, drawing[0], drawing[1]);
    }

    /**
     * Clip planes, in a block entity's drawing space, for what it draws at once with its own shader (Aeronautics' burner
     * flame): neither the cut's buffer source ({@link #clippingSource}) nor IP's portal plane reach such a draw. The cut
     * of a straddling ship, and in a portal pass IP's plane for a block entity on a ship. Each is {normal, anchor}, the
     * kept side being {@code normal . (p - anchor) >= 0}.
     */
    public static java.util.List<org.joml.Vector3f[]> immediateClipPlanes(final net.minecraft.world.level.block.entity.BlockEntity blockEntity,
                                                                          final com.mojang.blaze3d.vertex.PoseStack poseStack) {
        final java.util.List<org.joml.Vector3f[]> planes = new java.util.ArrayList<>(2);
        if (blockEntity.getLevel() == null) {
            return planes;
        }
        final BlockPos pos = blockEntity.getBlockPos();
        final ClientSubLevel ship = cutShipAt(blockEntity.getLevel(), pos);
        final KeepPlane plane = ship == null ? null : planeFor(ship);
        if (plane != null) {
            planes.add(drawingPlane(ship, pos, poseStack, plane.point().subtract(plane.normal().scale(seamReach())), plane.normal()));
        }
        if (PortalRendering.isRendering()) {
            final Plane clip = PortalRendering.getActiveClippingPlane();
            if (clip != null && dev.ryanhcode.sable.Sable.HELPER.getContaining(blockEntity) instanceof final ClientSubLevel containing) {
                planes.add(drawingPlane(containing, pos, poseStack, clip.pos(), clip.normal()));
            }
        }
        return planes;
    }

    /**
     * @return a plane of the rendered world ({@code point}, {@code normal}) in the drawing space of a block at
     * {@code pos} on {@code ship}, whose pose stack is {@code poseStack} (block local to drawing space): {normal, anchor}
     */
    private static org.joml.Vector3f[] drawingPlane(final ClientSubLevel ship, final BlockPos pos, final com.mojang.blaze3d.vertex.PoseStack poseStack,
                                                    final Vec3 point, final Vec3 normal) {
        final Pose3dc pose = ship.renderPose();
        final org.joml.Vector3d local = pose.transformPositionInverse(new org.joml.Vector3d(point.x, point.y, point.z))
                .sub(pos.getX(), pos.getY(), pos.getZ());
        final org.joml.Vector3d localNormal = new org.joml.Quaterniond(pose.orientation()).conjugate()
                .transform(new org.joml.Vector3d(normal.x, normal.y, normal.z));
        final org.joml.Matrix4f matrix = poseStack.last().pose();
        final org.joml.Vector3f anchor = matrix.transformPosition(new org.joml.Vector3f((float) local.x, (float) local.y, (float) local.z));
        final org.joml.Vector3f drawingNormal = matrix.transformDirection(
                new org.joml.Vector3f((float) localNormal.x, (float) localNormal.y, (float) localNormal.z)).normalize();
        return new org.joml.Vector3f[]{drawingNormal, anchor};
    }

    /**
     * Draws the block of a single-block ship, which Sable batches after the section layers: neither the ship's cut
     * ({@link #beginShipDraw}) nor IP's portal plane is on there and a batch has no per-ship uniform, so both are applied
     * on the CPU, at the same places as for a ship drawn in sections. Its vertices are written in drawing space,
     * {@code modelView (world - camera)}.
     *
     * @param draw draws the block into the consumer it is given
     */
    public static void drawSingleBlock(final ClientSubLevel ship, final com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                       final org.joml.Matrix4f modelView, final Vec3 camera,
                                       final java.util.function.Consumer<com.mojang.blaze3d.vertex.VertexConsumer> draw) {
        final KeepPlane plane = planeFor(ship);
        if (plane != null && facesPortalClipAway(plane)) {
            return;
        }
        final Plane portal = PortalRendering.isRendering() ? PortalRendering.getActiveClippingPlane() : null;
        IPPlaneClippingVertexConsumer atCut = null;
        IPPlaneClippingVertexConsumer atPortal = null;
        com.mojang.blaze3d.vertex.VertexConsumer target = consumer;
        if (plane != null) {
            target = atCut = clippingInDrawingSpace(target, plane.point().subtract(plane.normal().scale(seamReach())),
                    plane.normal(), modelView, camera);
        }
        if (portal != null) {
            target = atPortal = clippingInDrawingSpace(target, portal.pos().subtract(portal.normal().scale(FrontClipping.ADJUSTMENT)),
                    portal.normal(), modelView, camera);
        }
        draw.accept(target);
        // the outer one first: its last quad goes through the inner one
        if (atPortal != null) {
            atPortal.flush();
        }
        if (atCut != null) {
            atCut.flush();
        }
    }

    private static IPPlaneClippingVertexConsumer clippingInDrawingSpace(final com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                                                        final Vec3 point, final Vec3 normal,
                                                                        final org.joml.Matrix4f modelView, final Vec3 camera) {
        final Vec3 rel = point.subtract(camera);
        return new IPPlaneClippingVertexConsumer(consumer,
                modelView.transformDirection(new org.joml.Vector3f((float) normal.x, (float) normal.y, (float) normal.z)).normalize(),
                modelView.transformPosition(new org.joml.Vector3f((float) rel.x, (float) rel.y, (float) rel.z)));
    }

    /**
     * Whether a block entity is drawn in the current pass by its block centre: false when it is on a straddling ship and
     * its centre is past the cut, or on the clipped side of IP's portal plane in a portal pass.
     */
    public static boolean keepsByCentre(final net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        if (blockEntity.getLevel() == null) {
            return true;
        }
        final ClientSubLevel ship = cutShipAt(blockEntity.getLevel(), blockEntity.getBlockPos());
        if (ship != null && (!keepsBlockEntity(ship, blockEntity.getBlockPos()) || rejectsInPortalPass(ship))) {
            return false;
        }
        if (PortalRendering.isRendering()) {
            final SubLevel containing = dev.ryanhcode.sable.Sable.HELPER.getContaining(blockEntity);
            return containing == null || keepsInPortalPass(containing, blockEntity.getBlockPos());
        }
        return true;
    }

    /**
     * Whether a ship block entity at {@code pos} (plot space) is on the visible side of IP's portal clip plane, in a
     * portal pass. Batched block entities are clipped on the GPU by IP, but some draw at once with their own shader
     * (Aeronautics' burner flame), which IP's plane never reaches: without this, a ship right in front of the
     * destination portal shows them in the portal view, as if behind the portal.
     */
    public static boolean keepsInPortalPass(final SubLevel ship, final BlockPos pos) {
        if (!PortalRendering.isRendering() || !(ship instanceof final ClientSubLevel clientShip)) {
            return true;
        }
        final Plane clip = PortalRendering.getActiveClippingPlane();
        if (clip == null) {
            return true;
        }
        final Vec3 center = clientShip.renderPose().transformPosition(Vec3.atCenterOf(pos));
        return clip.normal().dot(center.subtract(clip.pos())) >= 0.0;
    }

    /**
     * @return the ship of {@code level} whose plot contains {@code pos} and that is cut in the current pass, or null
     */
    @Nullable
    public static ClientSubLevel cutShipAt(final net.minecraft.world.level.Level level, final BlockPos pos) {
        final Projection current = projection;
        if (current != null) {
            return current.ship();
        }
        if (IPStraddleClientSessions.isEmpty()) {
            return null;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        for (final IPStraddleClientSessions.Session session : IPStraddleClientSessions.renderSessions()) {
            if (!session.shipDim().equals(level.dimension())) {
                continue;
            }
            if (container.getSubLevel(session.ship()) instanceof final ClientSubLevel ship) {
                final BoundingBox3ic box = ship.getPlot().getBoundingBox();
                if (box.contains(pos.getX(), pos.getY(), pos.getZ())) {
                    return ship;
                }
            }
        }
        return null;
    }

    /**
     * Runs {@code draw} once for every ship straddling into the dimension being rendered now, each time with that
     * ship's pose mapped through its portal and the destination plane as its cut. No-op inside a projection draw.
     */
    public static void forEachProjection(final ProjectionDraw draw) {
        if (projection != null || IPStraddleClientSessions.isEmpty()) {
            return;
        }
        final ClientLevel rendering = Minecraft.getInstance().level;
        if (rendering == null) {
            return;
        }
        final List<IPStraddleClientSessions.Session> into = IPStraddleClientSessions.renderSessionsInto(rendering.dimension());
        if (into.isEmpty()) {
            return;
        }
        final List<Projection> projections = new ArrayList<>(into.size());
        for (final IPStraddleClientSessions.Session session : into) {
            final ClientSubLevel ship = findShip(session);
            if (ship == null) {
                continue;
            }
            final Pose3d mapped = session.isometry().mapPose(ship.renderPose());
            projections.add(new Projection(ship, mapped, new KeepPlane(session.destOrigin(), session.destNormal()), session.isometry(), session));
        }
        for (final Projection p : projections) {
            projection = p;
            try {
                draw.draw(p.ship());
            } finally {
                projection = null;
            }
        }
    }

    /** Runs {@code draw} with {@code ship} drawn mapped through the portal of {@code session}, its far side as the cut. */
    static void withProjection(final ClientSubLevel ship, final IPStraddleClientSessions.Session session, final Runnable draw) {
        projection = new Projection(ship, session.isometry().mapPose(ship.renderPose()),
                new KeepPlane(session.destOrigin(), session.destNormal()), session.isometry(), session);
        try {
            draw.run();
        } finally {
            projection = null;
        }
    }

    @FunctionalInterface
    public interface ProjectionDraw {
        void draw(ClientSubLevel ship);
    }

    @Nullable
    private static ClientSubLevel findShip(final IPStraddleClientSessions.Session session) {
        final ClientLevel level = ClientWorldLoader.getOptionalWorld(session.shipDim());
        if (level == null) {
            return null;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        final SubLevel ship = container.getSubLevel(session.ship());
        return ship instanceof final ClientSubLevel clientShip && !clientShip.isRemoved() ? clientShip : null;
    }
}
