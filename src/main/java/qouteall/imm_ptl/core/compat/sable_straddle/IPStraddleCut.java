/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The block keep-filter and per-box trim at the portal plane follow IPSable 0.5.0's IplStraddleBlockClipMixin and
 * IplStraddleCollisionClip by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Which blocks of a straddling ship are past its portal plane, for entity collision in the ship's own world
 * (docs/lessons/straddle-entities.md): there, the part of the ship that is through the portal is not drawn and must not be
 * solid either. A block counts as cut away when all of it is past the plane; one crossing the plane stays solid.
 * Server: the straddle sessions; client: their copy. Both sides compute entity collision (players on the client).
 */
public final class IPStraddleCut {
    /** How far a block may reach over the plane and still count as on its own side (touching is not crossing). */
    private static final double TOUCH = 1.0e-4;
    private IPStraddleCut() {
    }

    /**
     * Keep planes of a ship in its plot space, as {nx, ny, nz, d}: plot points p with {@code n . p + d >= 0} are kept.
     *
     * @return null when the block at {@code pos} is wholly kept, {@link #CUT} when it is wholly past a plane,
     * otherwise the planes it crosses (its collision boxes must be trimmed)
     */
    @org.jetbrains.annotations.Nullable
    public static List<double[]> classify(final List<double[]> localPlanes, final BlockPos pos) {
        List<double[]> crossing = null;
        for (final double[] plane : localPlanes) {
            final double s = plane[0] * (pos.getX() + 0.5) + plane[1] * (pos.getY() + 0.5) + plane[2] * (pos.getZ() + 0.5) + plane[3];
            final double support = 0.5 * (Math.abs(plane[0]) + Math.abs(plane[1]) + Math.abs(plane[2]));
            if (s + support <= TOUCH) {
                return CUT;
            }
            if (s - support < -TOUCH) {
                if (crossing == null) {
                    crossing = new ArrayList<>(1);
                }
                crossing.add(plane);
            }
        }
        return crossing;
    }

    /** {@link #classify} result for a block wholly past a plane. */
    public static final List<double[]> CUT = List.of();

    /**
     * Trims one box of a block that crosses cut planes to the kept side: exactly when a plane is axis-aligned in the
     * ship's frame (within 45 degrees, along its dominant axis), otherwise the box is dropped only when it is wholly
     * past the plane.
     *
     * @param min    the box's minimum corner relative to the block's corner, trimmed in place
     * @param max    the box's maximum corner relative to the block's corner, trimmed in place
     * @param pos    the block's plot position
     * @param planes the planes the block crosses ({@link #classify}), in plot space
     * @return false when nothing of the box is kept
     */
    public static boolean trimBox(final double[] min, final double[] max, final BlockPos pos, final List<double[]> planes) {
        for (final double[] plane : planes) {
            // the plane in block-local coordinates (the boxes are relative to the block's corner)
            final double d = plane[3] + plane[0] * pos.getX() + plane[1] * pos.getY() + plane[2] * pos.getZ();
            int axis = 0;
            for (int i = 1; i < 3; i++) {
                if (Math.abs(plane[i]) > Math.abs(plane[axis])) {
                    axis = i;
                }
            }
            final double n = plane[axis];
            if (Math.abs(n) < 0.7) {
                // steeper than 45 degrees to every axis: drop the box only when it is wholly past the plane
                boolean anyKept = false;
                for (int i = 0; i < 8 && !anyKept; i++) {
                    final double x = (i & 1) == 0 ? min[0] : max[0], y = (i & 2) == 0 ? min[1] : max[1], z = (i & 4) == 0 ? min[2] : max[2];
                    anyKept = plane[0] * x + plane[1] * y + plane[2] * z + d > 1.0e-4;
                }
                if (!anyKept) {
                    return false;
                }
                continue;
            }
            // cut along the plane's dominant axis where the plane passes the box's centre line (a ship always sits at a
            // slight tilt; the error is tan(tilt) times half the box)
            double lateral = d;
            for (int i = 0; i < 3; i++) {
                if (i != axis) {
                    lateral += plane[i] * 0.5 * (min[i] + max[i]);
                }
            }
            final double t = -lateral / n;
            if (n > 0) {
                min[axis] = Math.max(min[axis], t);
            } else {
                max[axis] = Math.min(max[axis], t);
            }
            if (max[axis] - min[axis] < 1.0e-4) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the ship of {@code level} whose plot holds block position {@code pos}, or null
     */
    @org.jetbrains.annotations.Nullable
    public static SubLevel shipAt(final Level level, final BlockPos pos) {
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        final LevelPlot plot = container.getPlot(new ChunkPos(pos));
        return plot == null ? null : plot.getSubLevel();
    }

    /**
     * @return the world plane (point, normal kept side) in the plot space of {@code pose}, as {nx, ny, nz, d}
     */
    public static double[] toLocal(final Pose3dc pose, final Vec3 point, final Vec3 normal) {
        final Vector3d n = pose.orientation().transformInverse(new Vector3d(normal.x, normal.y, normal.z));
        final Vec3 p = pose.transformPositionInverse(point);
        return new double[]{n.x, n.y, n.z, -(n.x * p.x + n.y * p.y + n.z * p.z)};
    }

    /**
     * @return the keep planes, in plot space at {@code pose}, of {@code ship} straddling out of {@code level} (its own),
     * empty when it does not straddle
     */
    public static List<double[]> sourceLocalPlanes(final Level level, final SubLevel ship, final Pose3dc pose) {
        final List<double[]> planes = level.isClientSide()
                ? qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions.keepPlanesOf(ship)
                : serverKeepPlanes(level, ship);
        if (planes.isEmpty()) {
            return List.of();
        }
        final List<double[]> local = new ArrayList<>(planes.size());
        for (final double[] plane : planes) {
            local.add(toLocal(pose, new Vec3(plane[0], plane[1], plane[2]), new Vec3(plane[3], plane[4], plane[5])));
        }
        return local;
    }

    /**
     * @return whether any straddle session concerns {@code level} as the ship's own dimension (fast check)
     */
    public static boolean anyFrom(final Level level) {
        return level.isClientSide() ? !qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions.isEmpty()
                : IPStraddleSessions.anyFrom(level);
    }

    /**
     * @param pos a plot-space block position in {@code level}
     * @return whether that block belongs to a ship straddling a portal out of {@code level} and is wholly past the plane
     */
    public static boolean isCutAway(final Level level, final BlockPos pos) {
        if (level.isClientSide() ? qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions.isEmpty()
                : !IPStraddleSessions.anyFrom(level)) {
            return false;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        final LevelPlot plot = container.getPlot(new ChunkPos(pos));
        if (plot == null) {
            return false;
        }
        final SubLevel ship = plot.getSubLevel();
        if (ship == null) {
            return false;
        }
        final List<double[]> planes = level.isClientSide()
                ? qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions.keepPlanesOf(ship)
                : serverKeepPlanes(level, ship);
        if (planes.isEmpty()) {
            return false;
        }
        final Pose3dc pose = ship.logicalPose();
        final Vec3 center = pose.transformPosition(Vec3.atCenterOf(pos));
        for (final double[] plane : planes) {
            final Vector3d local = pose.orientation().transformInverse(new Vector3d(plane[3], plane[4], plane[5]));
            // a block's half extent along the normal, in world units (a scaled ship's blocks are s wide)
            final double support = 0.5 * IPShipScale.of(pose) * (Math.abs(local.x) + Math.abs(local.y) + Math.abs(local.z));
            final double distance = plane[3] * (center.x - plane[0]) + plane[4] * (center.y - plane[1]) + plane[5] * (center.z - plane[2]);
            if (distance + support <= TOUCH) {
                return true;
            }
        }
        return false;
    }

    /**
     * A ship of another dimension straddling a portal into this one: its far half here is solid.
     *
     * @param keepPoint  a point of the destination plane
     * @param keepNormal the destination plane's normal towards the side where the far half is (kept side)
     * @param portal     the portal the ship straddles (an entity of the ship's own dimension)
     */
    public record Projection(SubLevel ship, IPPortalSimilarity map, Vec3 keepPoint, Vec3 keepNormal, java.util.UUID portal) {
        /**
         * @return the ship's world bounds mapped through the portal
         */
        public net.minecraft.world.phys.AABB mappedBounds() {
            final dev.ryanhcode.sable.companion.math.BoundingBox3dc box = this.ship.boundingBox();
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 8; i++) {
                final Vec3 corner = this.map.mapPoint(new Vec3(
                        (i & 1) == 0 ? box.minX() : box.maxX(),
                        (i & 2) == 0 ? box.minY() : box.maxY(),
                        (i & 4) == 0 ? box.minZ() : box.maxZ()));
                minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
            }
            return new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        /**
         * @return the destination keep plane in the plot space of {@code mappedPose}, as {nx, ny, nz, d}
         */
        public double[] localPlane(final Pose3dc mappedPose) {
            return toLocal(mappedPose, this.keepPoint, this.keepNormal);
        }

        /**
         * @param mappedPose the ship's pose mapped into this dimension
         * @return whether any part of the block at plot position {@code pos} is past the destination plane
         */
        public boolean keepsBlock(final Pose3dc mappedPose, final BlockPos pos) {
            final Vec3 center = mappedPose.transformPosition(Vec3.atCenterOf(pos));
            final Vector3d local = mappedPose.orientation().transformInverse(
                    new Vector3d(this.keepNormal.x, this.keepNormal.y, this.keepNormal.z));
            final double support = 0.5 * IPShipScale.of(mappedPose) * (Math.abs(local.x) + Math.abs(local.y) + Math.abs(local.z));
            // a block only touching the plane is not past it
            return this.keepNormal.dot(center.subtract(this.keepPoint)) + support > TOUCH;
        }
    }

    /**
     * @return the ships of other dimensions straddling a portal into {@code level}
     */
    public static List<Projection> projectionsInto(final Level level) {
        if (level.isClientSide()) {
            return qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions.projectionsInto(level);
        }
        final List<Projection> projections = new ArrayList<>(1);
        IPStraddleSessions.forEach(session -> {
            if (session.dest == level && session.source != level && !session.ship.isRemoved()) {
                projections.add(new Projection(session.ship, session.map,
                        session.map.mapPoint(session.origin), session.map.mapDirection(session.normal), session.portal.getUUID()));
            }
        });
        return projections;
    }

    /**
     * Ships behind the portals an entity touches, mapped into the entity's world (docs/lessons/dimension-stack.md,
     * "Riders on a ship through a floor or ceiling"): the part of the entity past a portal's plane is in the
     * destination, and stands on that world's ships as IP's own collision lets it stand on that world's blocks. A player whose eye has crossed a
     * dimension stack's ceiling has the feet behind it, on a deck still below. Kept: blocks behind the portal's plane.
     *
     * @param portals the portals the entity touches (its portal collision entries)
     * @param bounds  the region of {@code level} to look in
     */
    public static List<Projection> behindPortals(final Level level, final List<qouteall.imm_ptl.core.portal.Portal> portals,
                                                 final net.minecraft.world.phys.AABB bounds) {
        List<Projection> projections = List.of();
        for (final qouteall.imm_ptl.core.portal.Portal portal : portals) {
            if (portal.getScale() != 1.0 || portal.getDestDim() == level.dimension()) {
                continue;
            }
            final Level dest = portal.getDestWorld();
            if (dest == null) {
                continue;
            }
            final IPPortalSimilarity isometry = IPPortalSimilarity.of(portal);
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 8; i++) {
                final Vec3 corner = isometry.mapPoint(new Vec3(
                        (i & 1) == 0 ? bounds.minX : bounds.maxX,
                        (i & 2) == 0 ? bounds.minY : bounds.maxY,
                        (i & 4) == 0 ? bounds.minZ : bounds.maxZ));
                minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
            }
            final dev.ryanhcode.sable.companion.math.BoundingBox3d mapped =
                    new dev.ryanhcode.sable.companion.math.BoundingBox3d(minX, minY, minZ, maxX, maxY, maxZ);
            for (final SubLevel ship : dev.ryanhcode.sable.Sable.HELPER.getAllIntersecting(dest, mapped)) {
                if (ship.isRemoved()) {
                    continue;
                }
                if (projections.isEmpty()) {
                    projections = new ArrayList<>(1);
                }
                projections.add(new Projection(ship, isometry.inverse(), portal.getOriginPos(), portal.getNormal().scale(-1.0), portal.getUUID()));
            }
        }
        return projections;
    }

    /**
     * @return the projection of {@code ship} into {@code level}, or null when it has none there
     */
    @org.jetbrains.annotations.Nullable
    public static Projection projectionOf(final Level level, final SubLevel ship) {
        if (ship.getLevel() == level) {
            return null;
        }
        for (final Projection projection : projectionsInto(level)) {
            if (projection.ship() == ship) {
                return projection;
            }
        }
        return null;
    }

    /**
     * @return the projection whose far half the entity stands on or next to (its feet within a block of the mapped
     * bounds), or null
     */
    @org.jetbrains.annotations.Nullable
    public static Projection farHalfNear(final net.minecraft.world.entity.Entity entity) {
        final List<Projection> projections = projectionsInto(entity.level());
        if (projections.isEmpty()) {
            return null;
        }
        final Vec3 feet = entity.position().subtract(0.0, 0.2, 0.0);
        for (final Projection projection : projections) {
            if (projection.mappedBounds().inflate(1.0).contains(feet)) {
                return projection;
            }
        }
        return null;
    }

    /**
     * The block of a ship's far half under an entity's feet, for what vanilla reads from the block below (landing
     * effects such as bouncing on a hot-air envelope, fall damage, friction): the entity does not track a ship of
     * another level, so that block is not found otherwise.
     *
     * @param depth how far below the feet to look (vanilla: 0.2 for landing, 0.5 for friction)
     * @return the block state, or null when the entity is not above the far half of a ship
     */
    @org.jetbrains.annotations.Nullable
    public static net.minecraft.world.level.block.state.BlockState farHalfBlockUnder(
            final net.minecraft.world.entity.Entity entity, final double depth) {
        final FarHalfUnder under = farHalfUnder(entity, depth);
        return under == null ? null : under.state();
    }

    /**
     * @return the ship whose far half is under an entity's feet (as {@link #farHalfBlockUnder}), or null: the entity
     * stands on that ship, though it does not track it
     */
    @org.jetbrains.annotations.Nullable
    public static SubLevel farHalfShipUnder(final net.minecraft.world.entity.Entity entity, final double depth) {
        final FarHalfUnder under = farHalfUnder(entity, depth);
        return under == null ? null : under.ship();
    }

    private record FarHalfUnder(SubLevel ship, net.minecraft.world.level.block.state.BlockState state) {
    }

    @org.jetbrains.annotations.Nullable
    private static FarHalfUnder farHalfUnder(final net.minecraft.world.entity.Entity entity, final double depth) {
        final List<Projection> projections = projectionsInto(entity.level());
        if (projections.isEmpty()) {
            return null;
        }
        final Vec3 feet = entity.position().subtract(0.0, depth, 0.0);
        for (final Projection projection : projections) {
            if (!projection.mappedBounds().inflate(0.5).contains(feet)) {
                continue;
            }
            final Pose3dc mapped = projection.map().mapPose(projection.ship().logicalPose());
            final BlockPos pos = BlockPos.containing(mapped.transformPositionInverse(feet));
            if (!projection.keepsBlock(mapped, pos)) {
                continue;
            }
            final net.minecraft.world.level.block.state.BlockState state = projection.ship().getLevel().getBlockState(pos);
            if (!state.isAir()) {
                return new FarHalfUnder(projection.ship(), state);
            }
        }
        return null;
    }

    /**
     * @return {px, py, pz, nx, ny, nz} per session of {@code ship} out of {@code level}; points p with
     * {@code n . (x - p) >= 0} are kept
     */
    private static List<double[]> serverKeepPlanes(final Level level, final SubLevel ship) {
        final List<double[]> planes = new ArrayList<>(1);
        IPStraddleSessions.forEach(session -> {
            if (session.source == level && session.ship == ship) {
                planes.add(new double[]{session.origin.x, session.origin.y, session.origin.z,
                        -session.normal.x, -session.normal.y, -session.normal.z});
            }
        });
        return planes;
    }
}
