/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.BlockEntityRenderDispatcherExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;

/**
 * Ropes of straddling ships (Create Aeronautics / Simulated). A rope is drawn by its owner holder's block entity
 * renderer from points in its own world, as one of the ship's "global" block entities (drawn whatever the camera
 * sees), so neither the per-ship clip plane nor the block-centre cut of {@code MixinSableBlockEntityRenderer_StraddleCut}
 * fits it. Instead its segments are cut at the portal ({@link #keptParts}), on the ship's own side and in the far half,
 * where the ship's global block entities are drawn with the mapped pose ({@link #drawProjectedGlobalBlockEntities}) and
 * the rope's points are mapped through the portal like the ship.
 */
public final class IPStraddleRopes {
    private static final boolean SIMULATED_LOADED = ModList.get().isLoaded("simulated");

    private IPStraddleRopes() {
    }

    /**
     * Whether {@code blockEntity} is a rope connector: its renderer draws only the rope, which is cut per segment rather
     * than by the connector's block centre. (A rope winch also draws its drum, which keeps the block-centre cut.)
     */
    public static boolean isRopeHolder(final BlockEntity blockEntity) {
        return SIMULATED_LOADED && Impl.isRopeConnector(blockEntity);
    }

    /**
     * In a projection draw: draws the global block entities of {@code ship} (Sable draws them from the level renderer's
     * own list, only in the ship's own world), with the same set-up as Sable's ship block entity pass.
     */
    public static void drawProjectedGlobalBlockEntities(final ClientSubLevel ship, final SubLevelRenderDispatcher.BlockEntityRenderer renderer,
                                                        final double cameraX, final double cameraY, final double cameraZ, final float partialTick) {
        drawProjected(ship, renderer, cameraX, cameraY, cameraZ, partialTick, blockEntity -> {
            final BlockEntityRenderer<BlockEntity> blockEntityRenderer = renderer.getBlockEntityRenderDispatcher().getRenderer(blockEntity);
            return blockEntityRenderer != null && blockEntityRenderer.shouldRenderOffScreen(blockEntity);
        });
    }

    /** In a projection draw: draws the block entities of {@code ship} that {@code drawn} accepts, set up as above. */
    static void drawProjected(final ClientSubLevel ship, final SubLevelRenderDispatcher.BlockEntityRenderer renderer,
                              final double cameraX, final double cameraY, final double cameraZ, final float partialTick,
                              final java.util.function.Predicate<BlockEntity> drawn) {
        final SubLevelRenderData data = ship.getRenderData();
        if (data == null) {
            return;
        }
        final Vector3d chunkOffset = new Vector3d();
        ship.renderPose().rotationPoint().negate(chunkOffset);
        final Matrix4f transformation = new Matrix4f();
        data.getTransformation(cameraX, cameraY, cameraZ, transformation);
        final Vector3f cameraPosition = new Vector3f();
        transformation.invert(new Matrix4f()).transformPosition(cameraPosition.zero());
        final BlockEntityRenderDispatcherExtension dispatcher = (BlockEntityRenderDispatcherExtension) renderer.getBlockEntityRenderDispatcher();
        dispatcher.sable$setCameraPosition(new Vec3(cameraPosition.x - chunkOffset.x, cameraPosition.y - chunkOffset.y, cameraPosition.z - chunkOffset.z));
        final PoseStack matrices = new PoseStack();
        matrices.mulPose(transformation);
        try {
            forEachBlockEntity(ship, blockEntity -> {
                if (drawn.test(blockEntity)) {
                    renderer.renderSingleBE(blockEntity, matrices, partialTick, -chunkOffset.x, -chunkOffset.y, -chunkOffset.z);
                }
            });
        } finally {
            dispatcher.sable$setCameraPosition(null);
        }
    }

    /**
     * Runs {@code action} on the block entities of client ship {@code ship}. The client plot's chunk holder list stays empty
     * (Sable fills it on the server), so this reads the plot's chunks from the level (empty plot chunks do not exist on
     * the client: null).
     */
    static void forEachBlockEntity(final ClientSubLevel ship, final java.util.function.Consumer<BlockEntity> action) {
        final var bounds = ship.getPlot().getBoundingBox();
        final net.minecraft.world.level.Level level = ship.getLevel();
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                if (level.getChunk(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false)
                        instanceof final net.minecraft.world.level.chunk.LevelChunk chunk) {
                    chunk.getBlockEntities().values().forEach(action);
                }
            }
        }
    }

    /**
     * The parts of the rope segment {@code from -> to} (world points of the rope's own level) drawn now, as fraction
     * intervals {@code {t0, t1}} of the segment, or null when the segment is drawn whole. A rope point is through the
     * portal when it is past the plane and inside the portal's opening: the far half draws only those parts, the ship's
     * own side everything else. (A rope can pass the plane beside the frame, where it never went through the portal.)
     */
    public static java.util.@Nullable List<double[]> keptParts(final BlockEntity owner, final Vector3dc from, final Vector3dc to) {
        if (IPStraddleClientSessions.isEmpty() || !(Sable.HELPER.getContaining(owner) instanceof final ClientSubLevel ship)) {
            return null;
        }
        if (IPStraddleRender.rejectsInPortalPass(ship)) {
            return java.util.List.of();
        }
        final IPStraddleClientSessions.Session projected = IPStraddleRender.projectionSession(ship);
        final IPStraddleClientSessions.Session session;
        if (projected != null) {
            session = projected;
        } else {
            final java.util.List<IPStraddleClientSessions.Session> sessions = IPStraddleClientSessions.renderSessionsOf(ship);
            if (sessions.isEmpty()) {
                return null;
            }
            session = sessions.get(0);
        }
        final double[] through = throughInterval(session, from, to);
        final java.util.List<double[]> parts;
        if (projected != null) {
            parts = through == null ? java.util.List.of() : java.util.List.of(through);
        } else if (through == null) {
            return null;
        } else {
            parts = new java.util.ArrayList<>(2);
            if (through[0] > 0.0) {
                parts.add(new double[]{0.0, through[0]});
            }
            if (through[1] < 1.0) {
                parts.add(new double[]{through[1], 1.0});
            }
        }
        return parts;
    }

    /**
     * How the rope segment {@code from -> to} is drawn now: whole (null), not at all, as pieces of it, or whole with its
     * end clamped onto the portal plane ({@link Clamp}: a segment crossing the plane inside the opening; pieces would end
     * square to the rope, which past a plane met at a shallow angle overhangs it by much of the rope's thickness).
     */
    public static @Nullable SegmentCut segmentCut(final BlockEntity owner, final Vector3dc from, final Vector3dc to) {
        final java.util.List<double[]> parts = keptParts(owner, from, to);
        if (parts == null) {
            return null;
        }
        if (parts.isEmpty()) {
            return new SegmentCut(parts, null);
        }
        if (parts.size() == 1 && Sable.HELPER.getContaining(owner) instanceof final ClientSubLevel ship) {
            final IPStraddleRender.KeepPlane plane = IPStraddleRender.planeFor(ship);
            if (plane != null) {
                Vec3 a = new Vec3(from.x(), from.y(), from.z());
                Vec3 b = new Vec3(to.x(), to.y(), to.z());
                final IPPortalIsometry isometry = IPStraddleRender.projectionIsometry(ship);
                if (isometry != null) {
                    a = isometry.mapPoint(a);
                    b = isometry.mapPoint(b);
                }
                final double da = plane.distance(a);
                final double db = plane.distance(b);
                final double[] part = parts.get(0);
                if ((da < 0.0) != (db < 0.0)) {
                    final double crossing = da / (da - db);
                    // the one kept piece runs from an end of the segment to where it crosses the plane
                    final boolean planeBound = (part[0] == 0.0 && Math.abs(part[1] - crossing) < 1.0e-6)
                            || (part[1] == 1.0 && Math.abs(part[0] - crossing) < 1.0e-6);
                    if (planeBound) {
                        return new SegmentCut(parts, new Clamp(plane.normal(), crossing));
                    }
                }
            }
        }
        return new SegmentCut(parts, null);
    }

    /**
     * @param parts kept pieces (fractions of the segment); empty: not drawn
     * @param clamp when set, draw the segment whole, clamped onto the plane with this kept-side normal (world frame of
     *              the draw), crossed at fraction {@code at} of the segment
     */
    public record SegmentCut(java.util.List<double[]> parts, @Nullable Clamp clamp) {
    }

    public record Clamp(Vec3 normal, double at) {
    }

    /** Whether a rope knot at the world point {@code at} (of the rope's own level) is drawn now. */
    public static boolean keepsKnot(final BlockEntity owner, final Vector3dc at) {
        final java.util.List<double[]> parts = keptParts(owner, at, at);
        return parts == null || !parts.isEmpty();
    }

    /**
     * The interval of {@code from -> to} through the portal of {@code session} (past its plane, inside its opening), in
     * the ship's own world, or null when no part of it is.
     */
    static double @Nullable [] throughInterval(final IPStraddleClientSessions.Session session, final Vector3dc from, final Vector3dc to) {
        final Vec3 a = new Vec3(from.x(), from.y(), from.z()).subtract(session.origin());
        final Vec3 d = new Vec3(to.x() - from.x(), to.y() - from.y(), to.z() - from.z());
        // past the plane: normal . p >= 0 (the session normal points from the source side into the portal)
        final double[] range = {0.0, 1.0};
        if (!clip(range, a.dot(session.normal()), d.dot(session.normal()), 0.0, Double.POSITIVE_INFINITY)
                || !clip(range, a.dot(session.axisW()), d.dot(session.axisW()), -session.halfW(), session.halfW())
                || !clip(range, a.dot(session.axisH()), d.dot(session.axisH()), -session.halfH(), session.halfH())) {
            return null;
        }
        return range;
    }

    /** Narrows {@code range} to where {@code value + t * rate} is within [min, max]; false when nothing is left. */
    private static boolean clip(final double[] range, final double value, final double rate, final double min, final double max) {
        if (Math.abs(rate) < 1.0e-12) {
            return value >= min && value <= max && range[0] <= range[1];
        }
        double enter = (min - value) / rate;
        double exit = (max - value) / rate;
        if (enter > exit) {
            final double swap = enter;
            enter = exit;
            exit = swap;
        }
        range[0] = Math.max(range[0], enter);
        range[1] = Math.min(range[1], exit);
        return range[0] < range[1];
    }

    private static final class Impl {
        static boolean isRopeConnector(final BlockEntity blockEntity) {
            return blockEntity instanceof dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorBlockEntity;
        }
    }
}
