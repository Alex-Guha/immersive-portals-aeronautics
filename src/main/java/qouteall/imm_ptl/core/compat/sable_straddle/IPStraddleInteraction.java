/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The approach (raycasts skip the source half, far-half rays, reach in the mapped frame) follows IPSable 0.5.0's
 * IplProjectionAwareClipMixin and IplStraddleInteractDistanceMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Block interaction with straddling ships (docs/lessons/straddle-interaction.md), the parts both sides share:
 * <ul>
 *     <li>raycasts ({@code ClipContext.getBlockShape}) in a ship's own world do not hit the part of the ship past its
 *     portal plane: it is not drawn there and not solid either (step 5). A block crossing the plane is hit only on its
 *     kept part. This covers the crosshair, the physics staff, projectiles and every other {@code Level.clip};</li>
 *     <li>a far-half raycast ({@link #withFarHalfRay}) runs the other way round: only the part past the plane is hit;</li>
 *     <li>the server's reach check measures a ship block where the ship is, and where its far half is
 *     ({@link #reachPoints}).</li>
 * </ul>
 */
public final class IPStraddleInteraction {
    /** A far-half raycast in progress: only {@code ship}'s blocks on the kept side of {@code localPlane} are hit. */
    private record FarHalfRay(SubLevel ship, double[] localPlane) {
    }

    private static final ThreadLocal<FarHalfRay> FAR_HALF_RAY = new ThreadLocal<>();
    /**
     * Depth of raycasts in progress that started inside a ship's plot: the ship's own machinery (a hot-air burner
     * looking for its envelope, lasers, sensors) sees the whole ship, the part past the portal plane included.
     */
    private static final ThreadLocal<int[]> SHIP_RAY = ThreadLocal.withInitial(() -> new int[1]);

    private IPStraddleInteraction() {
    }

    /**
     * Runs a raycast that hits only the part of {@code ship} past its portal plane (the far half), within the ship's
     * own world.
     *
     * @param localPlane the destination keep plane in the ship's plot space ({@link IPStraddleCut.Projection#localPlane})
     */
    public static <T> T withFarHalfRay(final SubLevel ship, final double[] localPlane, final Supplier<T> raycast) {
        final FarHalfRay previous = FAR_HALF_RAY.get();
        FAR_HALF_RAY.set(new FarHalfRay(ship, localPlane));
        try {
            return raycast.get();
        } finally {
            if (previous == null) {
                FAR_HALF_RAY.remove();
            } else {
                FAR_HALF_RAY.set(previous);
            }
        }
    }

    /**
     * Marks a raycast that starts at {@code from} in {@code level}: one starting in a ship's plot is the ship's own
     * (see {@link #SHIP_RAY}); call {@link #endRay} after it, whatever this returned.
     *
     * @return whether the ray was marked
     */
    public static boolean beginRay(final Vec3 from) {
        if (!IPSableCompatHelper.isPlotSpacePosition(from.x, from.z)) {
            return false;
        }
        SHIP_RAY.get()[0]++;
        return true;
    }

    public static void endRay(final boolean marked) {
        if (marked) {
            SHIP_RAY.get()[0]--;
        }
    }

    /**
     * @param fluid whether {@code shape} is a fluid shape (kept whole when its block crosses the plane)
     * @return the shape raycasts see for the block at plot position {@code pos} of {@code level}: without the part past
     * the portal plane of a straddling ship (or, during {@link #withFarHalfRay}, without the part before it)
     */
    public static VoxelShape raycastShape(final Level level, final BlockPos pos, final VoxelShape shape, final boolean fluid) {
        final FarHalfRay far = FAR_HALF_RAY.get();
        if (far == null && (SHIP_RAY.get()[0] > 0 || !IPStraddleCut.anyFrom(level))) {
            return shape;
        }
        final SubLevel ship = IPStraddleCut.shipAt(level, pos);
        if (ship == null) {
            return shape;
        }
        final List<double[]> planes;
        if (far != null && far.ship() == ship) {
            planes = List.of(far.localPlane());
        } else {
            planes = IPStraddleCut.sourceLocalPlanes(level, ship, raycastPose(level, ship));
            if (planes.isEmpty()) {
                return shape;
            }
        }
        final List<double[]> crossing = IPStraddleCut.classify(planes, pos);
        if (crossing == null) {
            return shape;
        }
        if (crossing == IPStraddleCut.CUT) {
            return Shapes.empty();
        }
        if (fluid) {
            return shape;
        }
        VoxelShape kept = Shapes.empty();
        for (final AABB box : shape.toAabbs()) {
            final double[] min = {box.minX, box.minY, box.minZ};
            final double[] max = {box.maxX, box.maxY, box.maxZ};
            if (IPStraddleCut.trimBox(min, max, pos, crossing)) {
                kept = Shapes.or(kept, Shapes.box(min[0], min[1], min[2], max[0], max[1], max[2]));
            }
        }
        return kept;
    }

    /**
     * @return the pose Sable's raycast uses for {@code ship}: on the client the interpolated render pose while picking
     * (Sable's pose supplier), otherwise the logical pose
     */
    public static Pose3dc raycastPose(final Level level, final SubLevel ship) {
        if (level instanceof final LevelPoseProviderExtension provider) {
            return provider.sable$getPose(ship);
        }
        return ship.logicalPose();
    }

    /**
     * Where a block of {@code world} is, for the server's cross-portal reach check: its own position, or for a ship
     * block the ship's world position plus, per straddle session of that ship, the matching point of its far half in
     * the destination dimension.
     */
    public static List<Tuple<ResourceKey<Level>, Vec3>> reachPoints(final ServerLevel world, final BlockPos pos) {
        final Vec3 center = Vec3.atCenterOf(pos);
        final List<Tuple<ResourceKey<Level>, Vec3>> points = new ArrayList<>(2);
        final SubLevel ship = IPSableCompatHelper.isPlotSpacePosition(center.x, center.z) ? IPStraddleCut.shipAt(world, pos) : null;
        if (ship == null) {
            points.add(new Tuple<>(world.dimension(), center));
            return points;
        }
        final Vec3 worldPos = ship.logicalPose().transformPosition(center);
        points.add(new Tuple<>(world.dimension(), worldPos));
        IPStraddleSessions.forEach(session -> {
            if (session.ship == ship && session.source == world && session.dest != world) {
                points.add(new Tuple<>(session.dest.dimension(), session.isometry.mapPoint(worldPos)));
            }
        });
        return points;
    }

    /**
     * @return the world position of {@code location} when it lies in a ship's plot of {@code level}, else itself
     */
    public static Vec3 toWorld(final Level level, final Vec3 location) {
        if (!IPSableCompatHelper.isPlotSpacePosition(location.x, location.z)) {
            return location;
        }
        final SubLevel ship = IPStraddleCut.shipAt(level, BlockPos.containing(location));
        return ship == null ? location : raycastPose(level, ship).transformPosition(location);
    }
}
