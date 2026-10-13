/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Ported from IPSable 0.5.0's PortalCrossingDetector by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where a ship's blocks are relative to a portal, and how they got there (docs/lessons/straddle-sessions.md).
 * <p>
 * Phase is read from the current pose: every non-air block's oriented box is projected on the portal's
 * source-to-destination normal. APPROACHING while the whole ship is more than {@value #HYSTERESIS} before the
 * plane, CROSSED once all of it is that far past, STRADDLING in between. Phase alone does not say which portal a
 * ship is passing through (a frame has one portal per face, and a ship in front of one face is "past" the other), so
 * entry evidence comes from a swept test: each block's centre, moved along the last two physics segments, against
 * the finite aperture grown by the block's box (Minkowski sum), and the side of the plane each block started on.
 * <p>
 * Differs from IPSable: their hand-off was a parent flip that could chain several crossings inside one tick, which
 * needed trail rebasing and exit latches; ours is a whole-ship move after the ship is fully through, so a trail is
 * just the last three poses and is forgotten after a move.
 */
final class IPShipCrossingDetector {
    private static final double EPSILON = 1.0e-8;
    /** Distance from the plane at which a ship counts as touching it (entry) or clear of it (crossed). */
    static final double HYSTERESIS = 0.1;
    /** Ticks a ship's block list is reused before it is read again from the plot. */
    private static final int VOLUME_REFRESH_TICKS = 20;

    enum Phase { APPROACHING, STRADDLING, CROSSED }

    enum Direction { TOWARD_DESTINATION, TOWARD_SOURCE, AMBIGUOUS, NONE }

    /**
     * @param startedBeforePlane    some block was before the plane at the oldest retained pose
     * @param sweptThroughAperture  some block's swept path met the (grown) aperture in the retained segments
     * @param sweptDirection        which way blocks crossed the plane inside the aperture (latest segment first)
     * @param currentDirection      the same for the latest segment only
     * @param maxDistance           how far past the plane the ship's furthest block reaches now (negative: before it)
     */
    record State(Phase phase, boolean startedBeforePlane, boolean sweptThroughAperture, Direction sweptDirection,
                 Direction currentDirection, double maxDistance) {
        static final State EMPTY = new State(Phase.APPROACHING, false, false, Direction.NONE, Direction.NONE,
                Double.NEGATIVE_INFINITY);
    }

    private static final class Trail {
        final Pose3d older = new Pose3d();
        final Pose3d start = new Pose3d();
        final Pose3d end = new Pose3d();
        boolean hasOlder;
        Object owner;
    }

    private record Volume(Object owner, long tick, List<BlockPos> blocks) {
    }

    private static final Map<UUID, Trail> TRAILS = new HashMap<>();
    private static final Map<UUID, Volume> VOLUMES = new HashMap<>();

    private IPShipCrossingDetector() {
    }

    static void reset() {
        TRAILS.clear();
        VOLUMES.clear();
    }

    /**
     * Drops the state of every ship not in {@code live}.
     */
    static void retain(final java.util.Set<UUID> live) {
        TRAILS.keySet().retainAll(live);
        VOLUMES.keySet().retainAll(live);
    }

    /**
     * Forgets a ship's history, after it was moved to another level (its next segment starts there).
     */
    static void forget(final UUID shipId) {
        TRAILS.remove(shipId);
        VOLUMES.remove(shipId);
    }

    /**
     * Records this tick's physics segment ({@code lastPose -> logicalPose}); the previous one is kept as history.
     * Call once per ship per tick, before any evaluation.
     */
    static void capture(final ServerSubLevel ship) {
        Trail trail = TRAILS.get(ship.getUniqueId());
        if (trail == null || trail.owner != ship) {
            trail = new Trail();
            trail.owner = ship;
            trail.start.set(ship.lastPose());
            trail.end.set(ship.logicalPose());
            TRAILS.put(ship.getUniqueId(), trail);
            return;
        }
        trail.older.set(trail.start);
        trail.hasOlder = true;
        trail.start.set(ship.lastPose());
        trail.end.set(ship.logicalPose());
    }

    /**
     * @return the world AABB covering the ship's plot bounds at every retained pose (null for an empty plot)
     */
    static AABB sweptBounds(final ServerSubLevel ship) {
        final Trail trail = trail(ship);
        final Bounds bounds = new Bounds();
        includePlotBounds(bounds, ship, trail.start);
        includePlotBounds(bounds, ship, trail.end);
        if (trail.hasOlder) {
            includePlotBounds(bounds, ship, trail.older);
        }
        return bounds.toAabb();
    }

    /**
     * @return the ship's non-air blocks (plot coordinates), read from the plot at most every
     * {@value #VOLUME_REFRESH_TICKS} ticks
     */
    static List<BlockPos> blocks(final ServerSubLevel ship, final long tick) {
        final Volume volume = VOLUMES.get(ship.getUniqueId());
        if (volume != null && volume.owner() == ship && tick - volume.tick() < VOLUME_REFRESH_TICKS) {
            return volume.blocks();
        }
        final List<BlockPos> blocks = readBlocks(ship);
        VOLUMES.put(ship.getUniqueId(), new Volume(ship, tick, blocks));
        return blocks;
    }

    private static List<BlockPos> readBlocks(final ServerSubLevel ship) {
        final List<BlockPos> blocks = new ArrayList<>();
        for (final PlotChunkHolder holder : ship.getPlot().getLoadedChunks()) {
            final LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }
            final int baseX = chunk.getPos().getMinBlockX();
            final int baseZ = chunk.getPos().getMinBlockZ();
            final LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                final LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                final int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!section.getBlockState(x, y, z).isAir()) {
                                blocks.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                            }
                        }
                    }
                }
            }
        }
        return blocks;
    }

    /**
     * @param normal source-to-destination unit normal of the portal plane ({@code -portal.getNormal()})
     * @param margin lateral growth of the aperture on every side
     */
    static State evaluate(final ServerSubLevel ship, final List<BlockPos> blocks, final Portal portal, final Vec3 normal,
                          final double margin) {
        if (blocks.isEmpty()) {
            return State.EMPTY;
        }
        final Trail trail = trail(ship);
        final Frame start = new Frame(trail.start);
        final Frame end = new Frame(trail.end);
        final Frame older = trail.hasOlder ? new Frame(trail.older) : null;
        final Vec3 origin = portal.getOriginPos();

        final double[] current = sample(blocks, end, origin, normal);
        final double[] oldest = sample(blocks, older == null ? start : older, origin, normal);
        final Sweep recent = sweep(portal, blocks, start, end, normal, margin);
        final Sweep historic = older == null ? Sweep.NONE : sweep(portal, blocks, older, start, normal, margin);
        final Sweep merged = recent.preferOver(historic);

        return new State(phase(current[0], current[1]), oldest[0] < -EPSILON, merged.intersects(), merged.direction(),
                recent.direction(), current[1]);
    }

    /**
     * @return whether a block of the ship that touches the portal's plane now (its box within {@value #HYSTERESIS} of it) has its centre
     * inside the portal's opening grown by {@code margin}: the ship still straddles this portal and not just the plane
     * beside it
     */
    static boolean straddlesInsideAperture(final ServerSubLevel ship, final List<BlockPos> blocks, final Portal portal,
                                           final Vec3 normal, final double margin) {
        if (blocks.isEmpty()) {
            return false;
        }
        final Frame frame = new Frame(trail(ship).end);
        final Vec3 origin = portal.getOriginPos();
        final Vec3 axisW = portal.getAxisW();
        final Vec3 axisH = portal.getAxisH();
        final double halfW = portal.getWidth() * 0.5 + margin;
        final double halfH = portal.getHeight() * 0.5 + margin;
        // the same band as the STRADDLING phase: a box within HYSTERESIS of the plane counts as touching it
        final double support = frame.obbSupport(normal.x, normal.y, normal.z) + HYSTERESIS;
        for (final BlockPos block : blocks) {
            final double dx = frame.centerX(block) - origin.x;
            final double dy = frame.centerY(block) - origin.y;
            final double dz = frame.centerZ(block) - origin.z;
            if (Math.abs(dx * normal.x + dy * normal.y + dz * normal.z) > support) {
                continue;
            }
            if (Math.abs(dx * axisW.x + dy * axisW.y + dz * axisW.z) <= halfW
                    && Math.abs(dx * axisH.x + dy * axisH.y + dz * axisH.z) <= halfH) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether continuing the latest segment for {@code lookahead} more segments carries the ship into the
     * aperture from the source side (to open a session one tick early for fast ships)
     */
    static boolean willEnter(final ServerSubLevel ship, final List<BlockPos> blocks, final Portal portal, final Vec3 normal,
                             final double margin, final double lookahead) {
        if (lookahead <= 0.0 || blocks.isEmpty()) {
            return false;
        }
        final Trail trail = trail(ship);
        final Pose3d ahead = extrapolate(trail.start, trail.end, 1.0 + lookahead);
        final Sweep predicted = sweep(portal, blocks, new Frame(trail.end), new Frame(ahead), normal, margin);
        return predicted.intersects() && predicted.direction() == Direction.TOWARD_DESTINATION;
    }

    private static Trail trail(final ServerSubLevel ship) {
        final Trail trail = TRAILS.get(ship.getUniqueId());
        if (trail != null && trail.owner == ship) {
            return trail;
        }
        final Trail fresh = new Trail();
        fresh.owner = ship;
        fresh.start.set(ship.lastPose());
        fresh.end.set(ship.logicalPose());
        return fresh;
    }

    /**
     * Position extrapolated along the segment; orientation clamped at its end (spin is not extrapolated).
     */
    private static Pose3d extrapolate(final Pose3dc from, final Pose3dc to, final double t) {
        final Pose3d out = new Pose3d(from);
        out.position().set(
                from.position().x() + (to.position().x() - from.position().x()) * t,
                from.position().y() + (to.position().y() - from.position().y()) * t,
                from.position().z() + (to.position().z() - from.position().z()) * t);
        final Quaterniond rotation = new Quaterniond(from.orientation());
        rotation.slerp(new Quaterniond(to.orientation()), Math.clamp(t, 0.0, 1.0));
        out.orientation().set(rotation);
        return out;
    }

    private static void includePlotBounds(final Bounds out, final ServerSubLevel ship, final Pose3dc pose) {
        final var box = ship.getPlot().getBoundingBox();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    out.include(pose.transformPosition(new Vec3(
                            x == 0 ? box.minX() : box.maxX() + 1.0,
                            y == 0 ? box.minY() : box.maxY() + 1.0,
                            z == 0 ? box.minZ() : box.maxZ() + 1.0)));
                }
            }
        }
    }

    private static Phase phase(final double min, final double max) {
        if (min >= HYSTERESIS) {
            return Phase.CROSSED;
        }
        return max >= -HYSTERESIS ? Phase.STRADDLING : Phase.APPROACHING;
    }

    /**
     * @return {min, max} signed distance of the blocks' oriented boxes from the plane
     */
    private static double[] sample(final List<BlockPos> blocks, final Frame frame, final Vec3 plane, final Vec3 normal) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        final double support = frame.obbSupport(normal.x, normal.y, normal.z);
        for (final BlockPos block : blocks) {
            final double distance = (frame.centerX(block) - plane.x) * normal.x
                    + (frame.centerY(block) - plane.y) * normal.y
                    + (frame.centerZ(block) - plane.z) * normal.z;
            min = Math.min(min, distance - support);
            max = Math.max(max, distance + support);
        }
        return new double[]{min, max};
    }

    /**
     * Swept block boxes against the finite portal; {@code from} and {@code to} may be arbitrarily far apart.
     */
    private static Sweep sweep(final Portal portal, final List<BlockPos> blocks, final Frame from, final Frame to,
                               final Vec3 normal, final double margin) {
        boolean intersects = false;
        double towardTime = Double.POSITIVE_INFINITY;
        double sourceTime = Double.POSITIVE_INFINITY;
        final Vec3 origin = portal.getOriginPos();
        final Vec3 axisW = portal.getAxisW();
        final Vec3 axisH = portal.getAxisH();
        // The endpoint AABB is conservative for admission; the physical OBB decides which face was crossed (on an
        // inclined plane the AABB's projection is wider and could hide a real source-side entry).
        final double planeHalf = Math.max(from.aabbSupport(normal.x, normal.y, normal.z), to.aabbSupport(normal.x, normal.y, normal.z));
        final double fromPhysical = from.obbSupport(normal.x, normal.y, normal.z);
        final double toPhysical = to.obbSupport(normal.x, normal.y, normal.z);
        final double halfW = portal.getWidth() * 0.5
                + Math.max(from.aabbSupport(axisW.x, axisW.y, axisW.z), to.aabbSupport(axisW.x, axisW.y, axisW.z)) + margin;
        final double halfH = portal.getHeight() * 0.5
                + Math.max(from.aabbSupport(axisH.x, axisH.y, axisH.z), to.aabbSupport(axisH.x, axisH.y, axisH.z)) + margin;
        for (final BlockPos block : blocks) {
            final double fromX = from.centerX(block), fromY = from.centerY(block), fromZ = from.centerZ(block);
            final double toX = to.centerX(block), toY = to.centerY(block), toZ = to.centerZ(block);
            final double fromPlane = (fromX - origin.x) * normal.x + (fromY - origin.y) * normal.y + (fromZ - origin.z) * normal.z;
            final double toPlane = (toX - origin.x) * normal.x + (toY - origin.y) * normal.y + (toZ - origin.z) * normal.z;
            final double deltaPlane = toPlane - fromPlane;
            final double start;
            final double end;
            if (Math.abs(deltaPlane) <= EPSILON) {
                if (Math.abs(fromPlane) > planeHalf + HYSTERESIS + EPSILON) {
                    continue;
                }
                start = 0.0;
                end = 1.0;
            } else {
                final double a = (-planeHalf - HYSTERESIS - fromPlane) / deltaPlane;
                final double b = (planeHalf + HYSTERESIS - fromPlane) / deltaPlane;
                start = Math.max(0.0, Math.min(a, b));
                end = Math.min(1.0, Math.max(a, b));
                if (start > end + EPSILON) {
                    continue;
                }
            }
            if (!segmentMeetsAperture(origin, axisW, axisH, fromX, fromY, fromZ, toX, toY, toZ, start, end, halfW, halfH)) {
                continue;
            }

            intersects = true;
            // Only a real crossing of the block's own box gives a direction; a block already on the plane may jitter
            // either way, which keeps a session but never picks a face.
            final boolean enteredDestination = fromPlane + fromPhysical < -EPSILON && toPlane + toPhysical >= -EPSILON;
            final boolean enteredSource = fromPlane - fromPhysical > EPSILON && toPlane - toPhysical <= EPSILON;
            if (enteredDestination) {
                towardTime = Math.min(towardTime, start);
            } else if (enteredSource) {
                sourceTime = Math.min(sourceTime, start);
            }
        }
        return new Sweep(intersects, towardTime, sourceTime);
    }

    /**
     * Liang-Barsky clip of the part of a centre's path inside the plane slab against the grown aperture rectangle.
     */
    private static boolean segmentMeetsAperture(final Vec3 origin, final Vec3 axisW, final Vec3 axisH,
                                                final double fromX, final double fromY, final double fromZ,
                                                final double toX, final double toY, final double toZ,
                                                final double planeStart, final double planeEnd,
                                                final double halfW, final double halfH) {
        final double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        final double startX = fromX + dx * planeStart - origin.x;
        final double startY = fromY + dy * planeStart - origin.y;
        final double startZ = fromZ + dz * planeStart - origin.z;
        final double endX = fromX + dx * planeEnd - origin.x;
        final double endY = fromY + dy * planeEnd - origin.y;
        final double endZ = fromZ + dz * planeEnd - origin.z;
        final double startW = startX * axisW.x + startY * axisW.y + startZ * axisW.z;
        final double startH = startX * axisH.x + startY * axisH.y + startZ * axisH.z;
        final double deltaW = (endX - startX) * axisW.x + (endY - startY) * axisW.y + (endZ - startZ) * axisW.z;
        final double deltaH = (endX - startX) * axisH.x + (endY - startY) * axisH.y + (endZ - startZ) * axisH.z;
        double lower = 0.0;
        double upper = 1.0;
        if (Math.abs(deltaW) <= EPSILON) {
            if (startW < -halfW - EPSILON || startW > halfW + EPSILON) {
                return false;
            }
        } else {
            final double a = (-halfW - startW) / deltaW;
            final double b = (halfW - startW) / deltaW;
            lower = Math.max(lower, Math.min(a, b));
            upper = Math.min(upper, Math.max(a, b));
            if (lower > upper + EPSILON) {
                return false;
            }
        }
        if (Math.abs(deltaH) <= EPSILON) {
            return startH >= -halfH - EPSILON && startH <= halfH + EPSILON;
        }
        final double a = (-halfH - startH) / deltaH;
        final double b = (halfH - startH) / deltaH;
        lower = Math.max(lower, Math.min(a, b));
        upper = Math.min(upper, Math.max(a, b));
        return lower <= upper + EPSILON;
    }

    private record Sweep(boolean intersects, double towardTime, double sourceTime) {
        static final Sweep NONE = new Sweep(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

        /**
         * The latest segment decides the direction; the older one only fills in when the latest has none.
         */
        Sweep preferOver(final Sweep historic) {
            if (this.direction() != Direction.NONE) {
                return this;
            }
            if (historic.direction() != Direction.NONE) {
                return new Sweep(this.intersects || historic.intersects, historic.towardTime, historic.sourceTime);
            }
            return new Sweep(this.intersects || historic.intersects, this.towardTime, this.sourceTime);
        }

        Direction direction() {
            final boolean toward = Double.isFinite(this.towardTime);
            final boolean source = Double.isFinite(this.sourceTime);
            if (!toward && !source) return Direction.NONE;
            if (toward && !source) return Direction.TOWARD_DESTINATION;
            if (!toward) return Direction.TOWARD_SOURCE;
            if (this.towardTime + EPSILON < this.sourceTime) return Direction.TOWARD_DESTINATION;
            if (this.sourceTime + EPSILON < this.towardTime) return Direction.TOWARD_SOURCE;
            return Direction.AMBIGUOUS;
        }
    }

    /**
     * A pose's basis and the half-extents of a unit block's world AABB under it, shared by every block.
     */
    private static final class Frame {
        final double ox, oy, oz;
        final double xx, xy, xz, yx, yy, yz, zx, zy, zz;
        final double halfX, halfY, halfZ;

        Frame(final Pose3dc pose) {
            final Vec3 origin = pose.transformPosition(Vec3.ZERO);
            final Vec3 x = pose.transformPosition(new Vec3(1.0, 0.0, 0.0)).subtract(origin);
            final Vec3 y = pose.transformPosition(new Vec3(0.0, 1.0, 0.0)).subtract(origin);
            final Vec3 z = pose.transformPosition(new Vec3(0.0, 0.0, 1.0)).subtract(origin);
            this.ox = origin.x; this.oy = origin.y; this.oz = origin.z;
            this.xx = x.x; this.xy = x.y; this.xz = x.z;
            this.yx = y.x; this.yy = y.y; this.yz = y.z;
            this.zx = z.x; this.zy = z.y; this.zz = z.z;
            this.halfX = (Math.abs(this.xx) + Math.abs(this.yx) + Math.abs(this.zx)) * 0.5;
            this.halfY = (Math.abs(this.xy) + Math.abs(this.yy) + Math.abs(this.zy)) * 0.5;
            this.halfZ = (Math.abs(this.xz) + Math.abs(this.yz) + Math.abs(this.zz)) * 0.5;
        }

        double centerX(final BlockPos block) {
            return this.ox + this.xx * (block.getX() + 0.5) + this.yx * (block.getY() + 0.5) + this.zx * (block.getZ() + 0.5);
        }

        double centerY(final BlockPos block) {
            return this.oy + this.xy * (block.getX() + 0.5) + this.yy * (block.getY() + 0.5) + this.zy * (block.getZ() + 0.5);
        }

        double centerZ(final BlockPos block) {
            return this.oz + this.xz * (block.getX() + 0.5) + this.yz * (block.getY() + 0.5) + this.zz * (block.getZ() + 0.5);
        }

        double aabbSupport(final double x, final double y, final double z) {
            return this.halfX * Math.abs(x) + this.halfY * Math.abs(y) + this.halfZ * Math.abs(z);
        }

        double obbSupport(final double x, final double y, final double z) {
            return (Math.abs(this.xx * x + this.xy * y + this.xz * z)
                    + Math.abs(this.yx * x + this.yy * y + this.yz * z)
                    + Math.abs(this.zx * x + this.zy * y + this.zz * z)) * 0.5;
        }
    }

    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        void include(final Vec3 point) {
            this.minX = Math.min(this.minX, point.x); this.minY = Math.min(this.minY, point.y); this.minZ = Math.min(this.minZ, point.z);
            this.maxX = Math.max(this.maxX, point.x); this.maxY = Math.max(this.maxY, point.y); this.maxZ = Math.max(this.maxZ, point.z);
        }

        AABB toAabb() {
            return this.minX > this.maxX ? null : new AABB(this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
        }
    }
}
