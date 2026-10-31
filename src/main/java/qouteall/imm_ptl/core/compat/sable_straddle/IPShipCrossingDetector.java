/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Ported from IPSable 0.5.0's PortalCrossingDetector by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalAnchors;
import org.joml.Vector3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
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
    /** Ship blocks whose box comes this close [blocks] to the plane are tested against the frame. */
    private static final double FRAME_REACH = 1.0;
    /** How far [blocks] around a ship block world blocks are tested against it ({@link #frameContact}). */
    private static final double FRAME_SEARCH = 0.1;
    /** A world block counts as frame when it reaches within this distance [blocks] of the plane, as a nether portal's frame. */
    private static final double FRAME_HALF_THICKNESS = 0.5;
    /** A world block reaching less than this far [blocks] into the opening still counts as beside it. */
    private static final double EDGE_TOLERANCE = 1.0e-4;

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
     * Starts the history of a ship just handed off through a portal with the end of its incoming segment, mapped
     * through the portal: the part along which it was already wholly out of the portal it came out of (at least
     * {@value #HYSTERESIS} past that plane, so it is never read as going back in). Without it the history starts at
     * the arrival pose, and a fast ship arriving already on another portal's plane (stacked portals, a portal leading
     * onto itself) is never seen entering that portal and falls through it.
     *
     * @param ship         the ship's new sub-level, at its arrival pose
     * @param displacement its last segment's displacement, mapped through the portal
     * @param exitOrigin   a point of the plane it came out of, in the destination
     * @param exitNormal   that plane's unit normal pointing into the destination world
     */
    static void seedArrival(final ServerSubLevel ship, final Vec3 displacement, final Vec3 exitOrigin, final Vec3 exitNormal,
                            final long tick) {
        final Pose3dc arrived = ship.logicalPose();
        final Trail trail = new Trail();
        trail.owner = ship;
        trail.start.set(arrived);
        trail.end.set(arrived);
        TRAILS.put(ship.getUniqueId(), trail);
        final double outward = displacement.dot(exitNormal);
        final List<BlockPos> blocks = blocks(ship, tick);
        if (outward <= EPSILON || blocks.isEmpty()) {
            return;
        }
        final double clearance = sample(blocks, new Frame(arrived), exitOrigin, exitNormal)[0] - HYSTERESIS;
        final double back = Math.clamp(clearance / outward, 0.0, 1.0);
        trail.start.position().set(
                arrived.position().x() - displacement.x * back,
                arrived.position().y() - displacement.y * back,
                arrived.position().z() - displacement.z * back);
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
        final Trail trail = trail(ship, portal);
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
     * How deep the ship is pushed into the portal's frame now: the largest overlap [blocks] of a ship block near the
     * plane with a solid block of the ship's own world that sits at the plane wholly beside the opening, or with the
     * portal's rim ({@link IPPortalRims}) (negative: the gap to the nearest one; {@link Double#NEGATIVE_INFINITY} when
     * none is within {@value #FRAME_SEARCH} of the ship's blocks). A ship resting against a frame touches it (about 0);
     * only a hard hit pushes it in by a tenth of a block.
     */
    static double frameContact(final Level level, final ServerSubLevel ship, final List<BlockPos> blocks, final Portal portal,
                               final Vec3 normal) {
        return frameContact(level, blocks, portal, normal, new Frame(trail(ship).end), null);
    }

    /**
     * The frame blocks (as {@link #frameContact}, without the rim) the ship overlaps by more than {@code minOverlap} now,
     * each with its largest overlap, added to {@code out}.
     *
     * @return the largest overlap, as {@link #frameContact}
     */
    static double frameOverlaps(final Level level, final ServerSubLevel ship, final List<BlockPos> blocks, final Portal portal,
                                final Vec3 normal, final double minOverlap, final Map<BlockPos, Double> out) {
        final Map<BlockPos, Double> all = new HashMap<>();
        final double deepest = frameContact(level, blocks, portal, normal, new Frame(trail(ship).end), all);
        all.forEach((pos, overlap) -> {
            if (overlap > minOverlap) {
                out.merge(pos, overlap, Math::max);
            }
        });
        return deepest;
    }

    /**
     * {@link #frameContact} at the pose the latest segment extrapolated by {@code lookahead} segments reaches (the pose
     * {@link #willEnter} predicts).
     */
    static double frameContactAhead(final Level level, final ServerSubLevel ship, final List<BlockPos> blocks, final Portal portal,
                                    final Vec3 normal, final double lookahead) {
        final Trail trail = trail(ship, portal);
        return frameContact(level, blocks, portal, normal, new Frame(extrapolate(trail.start, trail.end, 1.0 + lookahead)), null);
    }

    /**
     * @param out when not null, receives every frame block within reach with its largest overlap; the portal's rim is
     *            then left out (it has no blocks to let a stuck ship through)
     */
    private static double frameContact(final Level level, final List<BlockPos> blocks, final Portal portal, final Vec3 normal,
                                       final Frame frame, @org.jetbrains.annotations.Nullable final Map<BlockPos, Double> out) {
        final Vec3 origin = portal.getOriginPos();
        final Vec3 axisW = portal.getAxisW();
        final Vec3 axisH = portal.getAxisH();
        final double halfW = portal.getWidth() * 0.5;
        final double halfH = portal.getHeight() * 0.5;
        final double shipN = frame.obbSupport(normal.x, normal.y, normal.z);
        final double shipW = frame.obbSupport(axisW.x, axisW.y, axisW.z);
        final double shipH = frame.obbSupport(axisH.x, axisH.y, axisH.z);
        // a world block is a unit cube: its support along a unit axis
        final double worldN = (Math.abs(normal.x) + Math.abs(normal.y) + Math.abs(normal.z)) * 0.5;
        final double worldW = (Math.abs(axisW.x) + Math.abs(axisW.y) + Math.abs(axisW.z)) * 0.5;
        final double worldH = (Math.abs(axisH.x) + Math.abs(axisH.y) + Math.abs(axisH.z)) * 0.5;
        final Map<BlockPos, Boolean> solid = new HashMap<>();
        final List<IPPortalRims.Bar> rim = out == null ? IPPortalRims.bars(portal) : List.of();
        double deepest = Double.NEGATIVE_INFINITY;
        for (final BlockPos block : blocks) {
            final double cx = frame.centerX(block), cy = frame.centerY(block), cz = frame.centerZ(block);
            final double depth = (cx - origin.x) * normal.x + (cy - origin.y) * normal.y + (cz - origin.z) * normal.z;
            if (depth + shipN < -FRAME_REACH || depth - shipN > FRAME_REACH) {
                continue;
            }
            final double w = (cx - origin.x) * axisW.x + (cy - origin.y) * axisW.y + (cz - origin.z) * axisW.z;
            final double h = (cx - origin.x) * axisH.x + (cy - origin.y) * axisH.y + (cz - origin.z) * axisH.z;
            if (Math.abs(w) + shipW <= halfW && Math.abs(h) + shipH <= halfH) {
                continue; // wholly inside the opening's prism: nothing beside the opening to touch
            }
            for (final IPPortalRims.Bar bar : rim) {
                final double overlap = frame.overlapWithBox(cx, cy, cz, bar);
                if (overlap > -FRAME_SEARCH) {
                    deepest = Math.max(deepest, overlap);
                }
            }
            final int minX = Mth.floor(cx - frame.halfX - FRAME_SEARCH), maxX = Mth.floor(cx + frame.halfX + FRAME_SEARCH);
            final int minY = Mth.floor(cy - frame.halfY - FRAME_SEARCH), maxY = Mth.floor(cy + frame.halfY + FRAME_SEARCH);
            final int minZ = Mth.floor(cz - frame.halfZ - FRAME_SEARCH), maxZ = Mth.floor(cz + frame.halfZ + FRAME_SEARCH);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        final double dx = x + 0.5 - origin.x, dy = y + 0.5 - origin.y, dz = z + 0.5 - origin.z;
                        final double worldDepth = dx * normal.x + dy * normal.y + dz * normal.z;
                        if (worldDepth - worldN >= FRAME_HALF_THICKNESS || worldDepth + worldN <= -FRAME_HALF_THICKNESS) {
                            continue; // not at the plane
                        }
                        if (Math.abs(dx * axisW.x + dy * axisW.y + dz * axisW.z) - worldW < halfW - EDGE_TOLERANCE
                                && Math.abs(dx * axisH.x + dy * axisH.y + dz * axisH.z) - worldH < halfH - EDGE_TOLERANCE) {
                            continue; // reaches into the opening: not frame
                        }
                        final BlockPos pos = new BlockPos(x, y, z);
                        if (!solid.computeIfAbsent(pos, p -> isSolid(level, p))) {
                            continue;
                        }
                        final double overlap = frame.overlapWithCube(cx, cy, cz, x + 0.5, y + 0.5, z + 0.5);
                        deepest = Math.max(deepest, overlap);
                        if (out != null) {
                            out.merge(pos, overlap, Math::max);
                        }
                    }
                }
            }
        }
        return deepest;
    }

    private static boolean isSolid(final Level level, final BlockPos pos) {
        return level.isLoaded(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
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
        final Trail trail = trail(ship, portal);
        final Pose3d ahead = extrapolate(trail.start, trail.end, 1.0 + lookahead);
        final Sweep predicted = sweep(portal, blocks, new Frame(trail.end), new Frame(ahead), normal, margin);
        return predicted.intersects() && predicted.direction() == Direction.TOWARD_DESTINATION;
    }

    /**
     * The ship's trail as seen from {@code portal}: for a portal mounted on another ship (the carrier's side, which
     * moves), every pose is taken relative to the carrier, moved by the carrier's motion since then
     * ({@code M_now ∘ M_then⁻¹}), so only the motion relative to the portal counts: a portal swept over a still ship
     * enters it, and a ship riding along beside it does not.
     */
    private static Trail trail(final ServerSubLevel ship, final Portal portal) {
        final Trail own = trail(ship);
        final IPShipPortalAnchors.Mount mount = IPShipPortalAnchors.mountFor(portal);
        if (mount == null || mount.source() == null || mount.source() == ship) {
            return own;
        }
        final Trail carrier = trail(mount.source());
        final Trail relative = new Trail();
        relative.owner = ship;
        relative.hasOlder = own.hasOlder;
        relative.end.set(own.end);
        relativeTo(own.start, carrier.start, carrier.end, relative.start);
        if (own.hasOlder) {
            relativeTo(own.older, carrier.hasOlder ? carrier.older : carrier.start, carrier.end, relative.older);
        }
        return relative;
    }

    /**
     * {@code out} = the pose {@code pose} (taken when the carrier was at {@code then}) carried along with the carrier to
     * {@code now}: {@code now ∘ then⁻¹ ∘ pose}, keeping the ship's rotation point.
     */
    private static void relativeTo(final Pose3dc pose, final Pose3dc then, final Pose3dc now, final Pose3d out) {
        final Quaterniond carry = new Quaterniond(now.orientation()).mul(new Quaterniond(then.orientation()).conjugate());
        final Vector3d shift = new Vector3d(then.rotationPoint()).sub(now.rotationPoint());
        now.orientation().transform(shift);
        shift.add(now.position()).sub(carry.transform(new Vector3d(then.position())));
        out.set(pose);
        carry.transform(out.position()).add(shift);
        new Quaterniond(carry).mul(pose.orientation(), out.orientation());
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

        /**
         * Separating-axis overlap of a ship block's box (centre {@code c}) with a world-aligned unit cube (centre
         * {@code p}): the smallest overlap over the 15 candidate axes, negative when they are apart (a lower bound of the
         * gap). Exact for a tilted block whose edge only touches the cube, which an AABB test would read as inside it.
         */
        double overlapWithCube(final double cx, final double cy, final double cz, final double px, final double py, final double pz) {
            final double tx = cx - px, ty = cy - py, tz = cz - pz;
            final double[][] edges = {{this.xx, this.xy, this.xz}, {this.yx, this.yy, this.yz}, {this.zx, this.zy, this.zz}};
            double overlap = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 3; i++) {
                final double[] world = {i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0};
                overlap = Math.min(overlap, this.overlapAlong(world[0], world[1], world[2], tx, ty, tz));
                overlap = Math.min(overlap, this.overlapAlong(edges[i][0], edges[i][1], edges[i][2], tx, ty, tz));
                for (final double[] edge : edges) {
                    overlap = Math.min(overlap, this.overlapAlong(
                            world[1] * edge[2] - world[2] * edge[1],
                            world[2] * edge[0] - world[0] * edge[2],
                            world[0] * edge[1] - world[1] * edge[0], tx, ty, tz));
                }
            }
            return overlap;
        }

        /**
         * {@link #overlapWithCube} for an oriented box (a rim bar).
         */
        double overlapWithBox(final double cx, final double cy, final double cz, final IPPortalRims.Bar bar) {
            final double tx = cx - bar.center().x, ty = cy - bar.center().y, tz = cz - bar.center().z;
            final double[][] edges = {{this.xx, this.xy, this.xz}, {this.yx, this.yy, this.yz}, {this.zx, this.zy, this.zz}};
            final Vec3[] axes = bar.axes();
            double overlap = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 3; i++) {
                overlap = Math.min(overlap, this.overlapAlong(axes[i].x, axes[i].y, axes[i].z, tx, ty, tz, bar));
                overlap = Math.min(overlap, this.overlapAlong(edges[i][0], edges[i][1], edges[i][2], tx, ty, tz, bar));
                for (final double[] edge : edges) {
                    overlap = Math.min(overlap, this.overlapAlong(
                            axes[i].y * edge[2] - axes[i].z * edge[1],
                            axes[i].z * edge[0] - axes[i].x * edge[2],
                            axes[i].x * edge[1] - axes[i].y * edge[0], tx, ty, tz, bar));
                }
            }
            return overlap;
        }

        private double overlapAlong(final double x, final double y, final double z, final double tx, final double ty, final double tz,
                                    final IPPortalRims.Bar bar) {
            final double length = Math.sqrt(x * x + y * y + z * z);
            if (length < 1.0e-6) {
                return Double.POSITIVE_INFINITY;
            }
            final Vec3 u = new Vec3(x / length, y / length, z / length);
            return this.obbSupport(u.x, u.y, u.z) + bar.support(u) - Math.abs(tx * u.x + ty * u.y + tz * u.z);
        }

        private double overlapAlong(final double x, final double y, final double z, final double tx, final double ty, final double tz) {
            final double length = Math.sqrt(x * x + y * y + z * z);
            if (length < 1.0e-6) {
                return Double.POSITIVE_INFINITY; // parallel edges: covered by the face axes
            }
            final double ux = x / length, uy = y / length, uz = z / length;
            final double cube = (Math.abs(ux) + Math.abs(uy) + Math.abs(uz)) * 0.5;
            return this.obbSupport(ux, uy, uz) + cube - Math.abs(tx * ux + ty * uy + tz * uz);
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
