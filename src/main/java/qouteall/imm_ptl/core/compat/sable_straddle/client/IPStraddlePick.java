/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The projection raycast follows IPSable 0.5.0's IplProjectionAwareClipMixin and IplStraddleOutlineMixin by r2smith141
 * (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3dc;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipBlockInteraction;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleInteraction;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.function.Supplier;

/**
 * Targeting blocks of Sable ships across portals (docs/lessons/straddle-interaction.md), client side. Plot coordinates repeat
 * between dimensions, so a block on a ship of another dimension is an IP remote target in the ship's own dimension
 * ({@link BlockManipulationClient#remotePointedDim}); IP's block manipulation then breaks and places it there.
 * <ul>
 *     <li>through a portal: IP traces the terrain behind it; ships of that world and far halves of ships straddling
 *     into it are traced too ({@link #refineRemoteTarget});</li>
 *     <li>in the player's own world: the far half of a ship straddling into it ({@link #pickFarHalf}).</li>
 * </ul>
 * A far half is traced in the ship's own world with the ray mapped back through the portal, hitting only the part past
 * the portal plane ({@link IPStraddleInteraction#withFarHalfRay}); distances keep, the map being rigid. Its outline is
 * drawn where the far half is ({@link #renderOutline}).
 */
public final class IPStraddlePick {
    /**
     * A far half: {@code ship} straddles {@code portal} (an entity of the ship's own dimension) and is drawn in
     * {@code destDim} with {@code isometry}.
     */
    public record FarHalf(SubLevel ship, IPPortalIsometry isometry, ResourceKey<Level> destDim, java.util.UUID portal) {
    }

    /**
     * @param distance from the ray start, in the frame of the traced world
     * @param farHalf  the far half hit, or null for a ship of the traced world itself
     */
    public record Hit(IPShipBlockInteraction.Target target, double distance, @Nullable FarHalf farHalf) {
    }

    @Nullable
    private static FarHalf farHalf;

    private IPStraddlePick() {
    }

    public static void onPickStart() {
        farHalf = null;
    }

    public static boolean isFarHalfTarget() {
        return farHalf != null && BlockManipulationClient.remotePointedDim != null;
    }

    @Nullable
    public static IPShipBlockInteraction.Target refineRemoteTarget(final Level world, final Vec3 from, final Vec3 to,
                                                                   final BlockHitResult terrain, final float partialTick) {
        if (!(world instanceof final ClientLevel level)) {
            return null;
        }
        final double terrainDistance = terrain.getType() == HitResult.Type.MISS || level.getBlockState(terrain.getBlockPos()).isAir()
            ? Double.POSITIVE_INFINITY : from.distanceTo(terrain.getLocation());
        final Hit best = nearest(level, from, to, partialTick, true);
        if (best == null || best.distance() >= terrainDistance) {
            return null;
        }
        farHalf = best.farHalf();
        return best.target();
    }

    @Nullable
    public static IPShipBlockInteraction.Target pickFarHalf(final Level level, final Vec3 from, final Vec3 to, final double limit,
                                                            final float partialTick) {
        if (!(level instanceof final ClientLevel clientLevel) || IPStraddleClientSessions.isEmpty()) {
            return null;
        }
        final Hit best = nearest(clientLevel, from, to, partialTick, false);
        if (best == null || best.distance() >= limit) {
            return null;
        }
        farHalf = best.farHalf();
        return best.target();
    }

    /**
     * The nearest ship block along {@code from -> to} in {@code level}, without touching the current target (the
     * physics staff, {@code IPPhysicsStaffPortalsClient}).
     *
     * @param ownShips whether ships of {@code level} itself count (IP's own raycast behind a portal sees only terrain)
     */
    @Nullable
    public static Hit nearest(final ClientLevel level, final Vec3 from, final Vec3 to, final float partialTick, final boolean ownShips) {
        Hit best = ownShips ? clipOwnShips(level, from, to, partialTick) : null;
        for (final IPStraddleCut.Projection projection : IPStraddleCut.projectionsInto(level)) {
            final Hit hit = clipFarHalf(level, projection, from, to, partialTick);
            if (hit != null && (best == null || hit.distance() < best.distance())) {
                best = hit;
            }
        }
        return best;
    }

    @Nullable
    private static Hit clipOwnShips(final ClientLevel level, final Vec3 from, final Vec3 to, final float partialTick) {
        final ClipContext context = new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, Minecraft.getInstance().player);
        ((ClipContextExtension) context).sable$setIgnoreMainLevel(true);
        final BlockHitResult hit = withRenderPoses(level, partialTick, () -> level.clip(context));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        final SubLevel ship = IPStraddleCut.shipAt(level, hit.getBlockPos());
        if (!(ship instanceof final ClientSubLevel clientShip)) {
            return null;
        }
        final Vec3 location = clientShip.renderPose(partialTick).transformPosition(hit.getLocation());
        return new Hit(new IPShipBlockInteraction.Target(level.dimension(), hit), from.distanceTo(location), null);
    }

    /**
     * @param from the ray start in {@code level}, the far half's destination world
     */
    @Nullable
    private static Hit clipFarHalf(final ClientLevel level, final IPStraddleCut.Projection projection, final Vec3 from, final Vec3 to,
                                   final float partialTick) {
        final SubLevel ship = projection.ship();
        if (!(ship instanceof final ClientSubLevel clientShip) || !(ship.getLevel() instanceof final ClientLevel shipLevel)) {
            return null;
        }
        if (!projection.mappedBounds().inflate(0.5).clip(from, to).isPresent() && !projection.mappedBounds().contains(from)) {
            return null;
        }
        final IPPortalIsometry isometry = projection.isometry();
        final Vec3 shipFrom = isometry.unmapPoint(from);
        final Vec3 shipTo = isometry.unmapPoint(to);
        final Pose3dc pose = new Pose3d(clientShip.renderPose(partialTick));
        final double[] plane = projection.localPlane(isometry.mapPose(pose));
        final ClipContext context = new ClipContext(shipFrom, shipTo, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, Minecraft.getInstance().player);
        ((ClipContextExtension) context).sable$setIgnoreMainLevel(true);
        ((ClipContextExtension) context).sable$setSubLevelIgnoring(other -> other != ship);
        final BlockHitResult hit = IPStraddleInteraction.withFarHalfRay(ship, plane,
            () -> withRenderPoses(shipLevel, partialTick, () -> shipLevel.clip(context)));
        if (hit.getType() != HitResult.Type.BLOCK || IPStraddleCut.shipAt(shipLevel, hit.getBlockPos()) != ship) {
            return null;
        }
        final Vec3 location = pose.transformPosition(hit.getLocation());
        return new Hit(new IPShipBlockInteraction.Target(shipLevel.dimension(), hit), shipFrom.distanceTo(location),
            new FarHalf(ship, isometry, level.dimension(), projection.portal()));
    }

    /**
     * Sable's raycast poses ships of {@code level} with their interpolated render pose, as they are drawn (Sable does
     * this itself only for the player's level, during its pick).
     */
    private static <T> T withRenderPoses(final ClientLevel level, final float partialTick, final Supplier<T> raycast) {
        final LevelPoseProviderExtension poses = (LevelPoseProviderExtension) level;
        poses.sable$pushPoseSupplier(ship -> ((ClientSubLevel) ship).renderPose(partialTick));
        try {
            return raycast.get();
        } finally {
            poses.sable$popPoseSupplier();
        }
    }

    /**
     * Draws the outline of the targeted far-half block in a pass rendering {@code rendered} (the player's world, or a
     * portal view of the far half's world), where the far half is.
     */
    public static void renderOutline(@Nullable final ClientLevel rendered, final Camera camera) {
        final FarHalf far = farHalf;
        if (far == null || rendered == null || !rendered.dimension().equals(far.destDim()) || BlockManipulationClient.remotePointedDim == null
            || !(BlockManipulationClient.remoteHitResult instanceof final BlockHitResult hit)) {
            return;
        }
        if (PortalRendering.isRendering() && PortalRendering.getRenderingPortal() instanceof Mirror) {
            return;
        }
        if (far.ship().isRemoved() || !(far.ship() instanceof final ClientSubLevel ship)) {
            return;
        }
        final Level shipLevel = ship.getLevel();
        final BlockPos pos = hit.getBlockPos();
        final BlockState state = shipLevel.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        final VoxelShape shape = state.getShape(shipLevel, pos, CollisionContext.of(camera.getEntity()));
        final Pose3dc pose = far.isometry().mapPose(ship.renderPose());
        final Vec3 cameraPos = camera.getPosition();
        final Vector3dc position = pose.position();
        final Vector3dc rotationPoint = pose.rotationPoint();
        final Vector3dc scale = pose.scale();

        // world = position + R (scale (p - rotationPoint)), as Sable's Pose3dc.transformPosition
        final PoseStack poseStack = new PoseStack();
        poseStack.translate(position.x() - cameraPos.x, position.y() - cameraPos.y, position.z() - cameraPos.z);
        poseStack.mulPose(new Quaternionf().set(pose.orientation()));
        poseStack.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
        poseStack.translate(pos.getX() - rotationPoint.x(), pos.getY() - rotationPoint.y(), pos.getZ() - rotationPoint.z());

        final MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        final VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
        final PoseStack.Pose last = poseStack.last();
        // as vanilla's LevelRenderer.renderHitOutline
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            float nx = (float) (x2 - x1);
            float ny = (float) (y2 - y1);
            float nz = (float) (z2 - z1);
            final float length = Mth.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= length;
            ny /= length;
            nz /= length;
            consumer.addVertex(last, (float) x1, (float) y1, (float) z1).setColor(0.0F, 0.0F, 0.0F, 0.4F).setNormal(last, nx, ny, nz);
            consumer.addVertex(last, (float) x2, (float) y2, (float) z2).setColor(0.0F, 0.0F, 0.0F, 0.4F).setNormal(last, nx, ny, nz);
        });
        buffers.endBatch(RenderType.lines());
    }
}
