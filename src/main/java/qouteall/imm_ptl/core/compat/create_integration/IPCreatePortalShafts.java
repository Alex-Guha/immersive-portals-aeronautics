/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_create.IPRotationPropagatorInvoker;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipMount;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

/**
 * Create shafts through IP portals. A kinetic block with a shaft on the face that opens onto a portal (the face
 * within a block of the portal plane, facing it, inside the opening) is paired with the kinetic block at the same spot
 * on the other side, if that one has a shaft towards the portal too. Any kinetic block: shafts, gearboxes, gearshifts,
 * generators, machines, other mods' blocks.
 * <p>
 * Create's rotation spreads within one level only (a block's source is a position in its own level, networks are kept
 * per level), so the two sides stay two networks. When one end turns and the other does not, the turning end drives
 * the other: the driven end's Create source becomes the portal block in front of it, as if a shaft there turned it,
 * at the far shaft's speed mapped through the portal (the shaft turns the same way on both sides), and Create spreads
 * that through the driven network as from any source ({@link #startDriving}). Blocks that care which side their source
 * is on (gearboxes, gearshifts, clutches) see the portal side. The two networks share stress: the driven end lends
 * its network what the driving network has to spare, the driving end adds what the driven network needs beyond its
 * own capacity to its own network's stress ({@code MixinKineticNetwork_PortalShafts}), so both overstress together.
 * Stopping the driving side stops the other.
 * <p>
 * Both sides turning on their own: as when Create joins two turning networks, the faster side (either when equal)
 * drives the other and overpowers its generators, which then add their capacity to the driven network, so the two
 * sides' capacities add up as with no portal between them; turning against each other breaks the slower side's end
 * block, as Create breaks the block joining them. A driven end taken over by a faster network on its own side is let go
 * and drives the other way from the next tick.
 * <p>
 * Ships: a shaft on a Sable ship pairs only through a portal mounted on that same ship, with a shaft at the fixed far
 * side, and a shaft at the fixed side of a ship-mounted portal only with a shaft on its ship. The ship's blocks are put
 * in the world by the ship's pose; the ship and its portal move together, so the two still line up exactly (within
 * {@link #SHIP_COS}/{@link #SHIP_GAP}, for rounding). Such pairs are checked again every tick.
 * <p>
 * Loops: a pair never drives while its driving network takes power (through any chain of driven ends) from the pair
 * itself ({@link Pair#upstream}), and within one level while both ends are in one network; either would keep the
 * shafts turning with nothing driving them.
 * <p>
 * Pairs are found every second from the portals' side (each portal's opening, one block deep), and sooner after a
 * kinetic block next to a portal block loads or is placed. While one end is unloaded the other keeps its state, as a
 * Create network keeps turning while its generator's chunk is unloaded. Mixins: {@code MixinKineticBlockEntity_PortalShafts},
 * {@code MixinKineticNetwork_PortalShafts}. Rendering: {@code IPCreatePortalShaftHalves}. Gaps and limits:
 * docs/lessons/create.md, "Shafts through portals".
 */
public final class IPCreatePortalShafts {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPPortalShafts] ";
    /** {@code -Dip_aeronautics.debug.portalShafts=true}: logs pairs found and dropped, and the stress they share. */
    private static final boolean DEBUG = Boolean.getBoolean("ip_aeronautics.debug.portalShafts");
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    private static final int SCAN_TICKS = 20;
    /** Scans a pair may go unseen (its portal not loaded yet, say) before it is dropped. */
    private static final int MISSED_SCANS = 3;
    /** Ticks a pair stays idle after it was stopped for driving itself. */
    private static final int LOOP_COOLDOWN_TICKS = 100;
    /** Cells looked at per portal at most (a 64x64 portal, two deep). */
    private static final int MAX_CELLS = 64 * 64 * 2;
    private static final double PLANE_EPSILON = 1.0e-3;
    /** Slack for a ship's end, whose pose and portal are both doubles worked out each tick: about 2.5 degrees... */
    private static final double SHIP_COS = 1 - 1.0e-3;
    /** ...and 1/20 block. */
    private static final double SHIP_GAP = 0.05;

    /** Where a block's coordinates sit in the world: a ship's plot, moved by the ship. World blocks have none (null). */
    interface Frame {
        Vec3 toWorld(Vec3 local);

        Vec3 toWorldDirection(Vec3 local);

        Vec3 toLocal(Vec3 world);

        Vec3 toLocalDirection(Vec3 world);

        /** Whether {@code pos} is a block of this frame (of this ship's plot). */
        boolean holds(Level level, BlockPos pos);

        /** The ship's id. */
        java.util.UUID ship();

        /** The rotation from frame to world directions. */
        org.joml.Quaternionf rotation();
    }

    /** A shaft face that opens onto a portal. */
    record End(ResourceKey<Level> dimension, BlockPos pos, Direction side) {
    }

    /** The far end found through a portal, and the speed factor from the near face to it. */
    private record FarEnd(ServerLevel level, End end, KineticBlockEntity be, float sign, boolean onShip) {
    }

    /** Two shaft ends joined through a portal. */
    static final class Pair {
        final End a;
        final End b;
        /** Speed at {@code b}'s face = sign * speed at {@code a}'s face (both as Create's axis speeds). */
        float sign;
        /** The portal {@link #portalEnd} opens onto. */
        Portal portal;
        End portalEnd;
        /** A ship or a ship-mounted portal is involved: checked again every tick. */
        final boolean moving;
        /** The end that drives the other, or null. */
        @Nullable End driver;
        /** Pairs whose driven ends the driving network takes power from, this one included. */
        Set<Pair> upstream = Set.of();
        /** What this pair adds to {@code a}'s network (the other side's own capacity and stress, SU), as last applied. */
        float capacityA;
        float loadA;
        /** The same for {@code b}'s network. */
        float capacityB;
        float loadB;
        int missed;
        long idleUntil;

        Pair(final End a, final End b, final float sign, final Portal portal, final boolean moving) {
            this.a = a;
            this.b = b;
            this.sign = sign;
            this.portal = portal;
            this.portalEnd = a;
            this.moving = moving;
        }

        End other(final End end) {
            return end.equals(a) ? b : a;
        }

        boolean joins(final End x, final End y) {
            return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
        }

        void clearShares() {
            capacityA = 0;
            loadA = 0;
            capacityB = 0;
            loadB = 0;
        }
    }

    private static final Map<End, Pair> PAIRS = new HashMap<>();
    private static final Set<Pair> ALL = new LinkedHashSet<>();
    /** Block entities that loaded with saved portal state and are in no pair yet: scans missed. */
    private static final Map<KineticBlockEntity, Integer> ORPHANS = new WeakHashMap<>();
    /** Ends given a share of the far side's capacity or stress last tick. */
    private static Set<KineticBlockEntity> shared = Set.of();
    private static boolean scanSoon;
    private static long tick;

    private IPCreatePortalShafts() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> onServerTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> clear());
    }

    private static void clear() {
        PAIRS.clear();
        ALL.clear();
        ORPHANS.clear();
        shared = Set.of();
        scanSoon = false;
        tick = 0;
    }

    /** A kinetic block entity's first tick: look for its portal now if it has saved state or sits by a portal block. */
    public static void onInitialize(final KineticBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (((IPPortalShaftEnd) be).ip$hasPortalState()) {
            ORPHANS.putIfAbsent(be, 0);
            scanSoon = true;
            return;
        }
        final BlockPos pos = be.getBlockPos();
        for (final Direction side : Direction.values()) {
            final BlockPos next = pos.relative(side);
            if (level.isLoaded(next) && IPCreatePortalPipes.isPlaceholderFacing(level.getBlockState(next), side)) {
                scanSoon = true;
                return;
            }
        }
    }

    private static void onServerTick(final MinecraftServer server) {
        tick++;
        if (scanSoon || tick % SCAN_TICKS == 0) {
            scanSoon = false;
            scan(server);
        }
        if (!ALL.isEmpty() || !shared.isEmpty()) {
            update(server);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Finding pairs

    private static void scan(final MinecraftServer server) {
        final Set<Pair> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final ServerLevel level : server.getAllLevels()) {
            final List<Portal> portals = new ArrayList<>();
            for (final Entity entity : level.getAllEntities()) {
                if (entity instanceof Portal portal) {
                    portals.add(portal);
                }
            }
            for (final Portal global : GlobalPortalStorage.getGlobalPortals(level)) {
                if (!portals.contains(global)) {
                    portals.add(global);
                }
            }
            for (final Portal portal : portals) {
                scanPortal(level, portal, seen);
            }
        }

        for (final Pair pair : new ArrayList<>(ALL)) {
            if (seen.contains(pair)) {
                pair.missed = 0;
            } else if (++pair.missed >= MISSED_SCANS && isLoaded(server, pair.a) && isLoaded(server, pair.b)) {
                dissolve(server, pair, "its portal is gone");
            }
        }

        for (final Map.Entry<KineticBlockEntity, Integer> orphan : new ArrayList<>(ORPHANS.entrySet())) {
            final KineticBlockEntity be = orphan.getKey();
            if (be.isRemoved() || inPair(be)) {
                ORPHANS.remove(be);
            } else if (orphan.getValue() + 1 >= MISSED_SCANS) {
                ORPHANS.remove(be);
                LOGGER.info(TAG + "{} {}: no portal any more, saved portal drive cleared",
                    be.getLevel().dimension().location(), be.getBlockPos().toShortString());
                stopDriving(be);
                setShare(be, 0, 0);
            } else {
                orphan.setValue(orphan.getValue() + 1);
            }
        }
    }

    private static void scanPortal(final ServerLevel level, final Portal portal, final Set<Pair> seen) {
        if (portal.isRemoved() || !portal.isTeleportable() || Math.abs(portal.getScale() - 1) > 1.0e-6) {
            return;
        }
        final Vec3 normal = portal.getNormal();
        final Direction front = Direction.getNearest(normal.x, normal.y, normal.z);
        // the blocks whose face towards the portal lies within a block in front of its plane
        final AABB region = portal.getBoundingBox().expandTowards(normal.scale(1.5));
        if (Vec3.atLowerCornerOf(front.getNormal()).dot(normal) >= 1 - PLANE_EPSILON) {
            scanCells(level, portal, region.deflate(PLANE_EPSILON), null, front.getOpposite(), seen);
        }
        final IPShipMount mount = IPShipPortals.clusterMountOf(portal);
        if (SABLE && mount != null) {
            // the shafts of the portal's own ship, where the ship-side portal is
            for (final Frame ship : IPCreateShaftShips.shipsTouching(level, region.inflate(1))) {
                if (ship.ship().equals(mount.carrier())) {
                    scanCells(level, portal, toLocalBounds(ship, region.inflate(0.5)), ship, null, seen);
                }
            }
        }
    }

    /**
     * The kinetic blocks in {@code cells} (in {@code frame}'s coordinates) with a shaft on {@code onlySide}, or on
     * any side when null, that open onto {@code portal}.
     */
    private static void scanCells(
        final ServerLevel level, final Portal portal, final AABB cells, final @Nullable Frame frame,
        final @Nullable Direction onlySide, final Set<Pair> seen
    ) {
        final BlockPos min = BlockPos.containing(cells.minX, cells.minY, cells.minZ);
        final BlockPos max = BlockPos.containing(cells.maxX, cells.maxY, cells.maxZ);
        if ((long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1) > MAX_CELLS) {
            return;
        }
        for (final BlockPos cell : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(cell) || !(level.getBlockEntity(cell) instanceof KineticBlockEntity be)
                || (frame != null && !frame.holds(level, cell))) {
                continue;
            }
            for (final Direction side : onlySide == null ? Direction.values() : new Direction[]{onlySide}) {
                if (!hasShaft(level, cell, be.getBlockState(), side) || isJoinedInLevel(level, cell, side)) {
                    continue;
                }
                final FarEnd far = link(portal, level, cell, side, frame);
                if (far != null) {
                    found(level, be, new End(level.dimension(), cell.immutable(), side), far, portal, frame != null, seen);
                }
            }
        }
    }

    private static void found(
        final ServerLevel level, final KineticBlockEntity be, final End near, final FarEnd far, final Portal portal,
        final boolean nearOnShip, final Set<Pair> seen
    ) {
        final Pair existing = PAIRS.get(near);
        if (existing != null) {
            if (existing.joins(near, far.end())) {
                existing.portal = portal;
                existing.portalEnd = near;
                seen.add(existing);
                return;
            }
            dissolve(level.getServer(), existing, "its portal leads elsewhere now");
        }
        if (PAIRS.get(far.end()) != null) {
            return; // the far end is joined to another end already
        }
        final boolean moving = nearOnShip || far.onShip() || IPShipPortals.isShipMounted(portal);
        final Pair pair = new Pair(near, far.end(), far.sign(), portal, moving);
        PAIRS.put(near, pair);
        PAIRS.put(far.end(), pair);
        ALL.add(pair);
        seen.add(pair);
        ORPHANS.remove(be);
        ORPHANS.remove(far.be());
        if (DEBUG) {
            LOGGER.info(TAG + "pair {} {} {} <-> {} {} {} (sign {}{})", near.dimension().location(), near.pos().toShortString(),
                near.side(), far.end().dimension().location(), far.end().pos().toShortString(), far.end().side(), far.sign(),
                moving ? ", moving" : "");
        }
    }

    /**
     * The far end of the shaft face {@code side} of {@code pos} through {@code portal}, with a kinetic block that has
     * a shaft towards the portal, or null. Worked out in world space ({@code frame} is the near block's ship, null in
     * the world). Through a ship-mounted portal (any portal of its cluster) exactly one end is on its ship: a ship's
     * shaft at its own portal pairs with a shaft at the fixed side, and back. Through any other portal both ends are
     * world blocks.
     */
    private static @Nullable FarEnd link(final Portal portal, final ServerLevel level, final BlockPos pos, final Direction side, final @Nullable Frame frame) {
        if (portal.isRemoved() || Math.abs(portal.getScale() - 1) > 1.0e-6
            || !(portal.getDestWorld() instanceof ServerLevel farLevel)) {
            return null;
        }
        final IPShipMount mount = IPShipPortals.clusterMountOf(portal);
        if (frame != null && (mount == null || !frame.ship().equals(mount.carrier()))) {
            return null;
        }
        final boolean shipInvolved = mount != null;
        final double cos = shipInvolved ? SHIP_COS : 1 - PLANE_EPSILON;
        final double slack = shipInvolved ? SHIP_GAP : PLANE_EPSILON;

        final Vec3 localNormal = Vec3.atLowerCornerOf(side.getNormal());
        final Vec3 localFace = Vec3.atCenterOf(pos).add(localNormal.scale(0.5));
        final Vec3 n = frame == null ? localNormal : frame.toWorldDirection(localNormal).normalize();
        final Vec3 face = frame == null ? localFace : frame.toWorld(localFace);
        final Vec3 portalNormal = portal.getNormal();
        if (portalNormal.dot(n) > -cos) {
            return null;
        }
        final double gap = portal.getDistanceToPlane(face);
        if (gap < -slack || gap >= 1 - PLANE_EPSILON) {
            return null;
        }
        final Vec3 onPlane = face.subtract(portalNormal.scale(gap));
        if (!portal.getPortalShape().isBoxInPortalProjection(portal.getThisSideState(), AABB.ofSize(onPlane, 0.1, 0.1, 0.1))) {
            return null;
        }

        final Vec3 farOnPlane = portal.transformPoint(onPlane);
        final Vec3 farDir = portal.transformLocalVecNonScale(n);
        final Vec3 localAxis = Vec3.atLowerCornerOf(Direction.get(Direction.AxisDirection.POSITIVE, side.getAxis()).getNormal());
        final Vec3 axisThere = portal.transformLocalVecNonScale(frame == null ? localAxis : frame.toWorldDirection(localAxis));

        if (!shipInvolved || frame != null) {
            // a world far end: fixed
            return farCandidate(farLevel, farOnPlane, farDir, axisThere, null, cos, slack);
        }
        // a fixed shaft at a ship-mounted portal: the far end is on the portal's ship
        if (!SABLE) {
            return null;
        }
        final AABB around = new AABB(farOnPlane, farOnPlane.add(farDir.scale(1.5))).inflate(1);
        for (final Frame ship : IPCreateShaftShips.shipsTouching(farLevel, around)) {
            if (ship.ship().equals(mount.carrier())) {
                final FarEnd found = farCandidate(farLevel, farOnPlane, farDir, axisThere, ship, cos, slack);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A kinetic block past the far plane in {@code frame} (null: the world) whose face towards the plane lines up. */
    private static @Nullable FarEnd farCandidate(
        final ServerLevel farLevel, final Vec3 farOnPlane, final Vec3 farDir, final Vec3 axisThere, final @Nullable Frame frame,
        final double cos, final double slack
    ) {
        final Vec3 dir = frame == null ? farDir : frame.toLocalDirection(farDir).normalize();
        final Vec3 plane = frame == null ? farOnPlane : frame.toLocal(farOnPlane);
        final Direction out = Direction.getNearest(dir.x, dir.y, dir.z);
        final Vec3 outNormal = Vec3.atLowerCornerOf(out.getNormal());
        if (outNormal.dot(dir) < cos) {
            return null; // rotated off the block grid
        }
        for (final double reach : new double[]{0.5, 1.5}) {
            final BlockPos candidate = BlockPos.containing(plane.add(dir.scale(reach)));
            final Vec3 candidateFace = Vec3.atCenterOf(candidate).subtract(outNormal.scale(0.5));
            final double farGap = candidateFace.subtract(plane).dot(outNormal);
            if (farGap < -slack || farGap >= 1 - PLANE_EPSILON) {
                continue;
            }
            if (!farLevel.isLoaded(candidate) || (frame != null && !frame.holds(farLevel, candidate))
                || !(farLevel.getBlockEntity(candidate) instanceof KineticBlockEntity be)) {
                continue;
            }
            final Direction farSide = out.getOpposite();
            if (!hasShaft(farLevel, candidate, be.getBlockState(), farSide) || isJoinedInLevel(farLevel, candidate, farSide)) {
                continue;
            }
            final Vec3 localAxis = Vec3.atLowerCornerOf(Direction.get(Direction.AxisDirection.POSITIVE, out.getAxis()).getNormal());
            final Vec3 farAxis = frame == null ? localAxis : frame.toWorldDirection(localAxis);
            final float sign = axisThere.dot(farAxis) >= 0 ? 1 : -1;
            return new FarEnd(farLevel, new End(farLevel.dimension(), candidate.immutable(), farSide), be, sign, frame != null);
        }
        return null;
    }

    static AABB toLocalBounds(final Frame frame, final AABB world) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final Vec3 corner = frame.toLocal(new Vec3(
                (i & 1) == 0 ? world.minX : world.maxX, (i & 2) == 0 ? world.minY : world.maxY, (i & 4) == 0 ? world.minZ : world.maxZ));
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Whether the block at {@code pos} has a shaft on {@code side}. */
    private static boolean hasShaft(final Level level, final BlockPos pos, final BlockState state, final Direction side) {
        return state.getBlock() instanceof IRotate rotate && rotate.hasShaftTowards(level, pos, state, side);
    }

    /** Create joins the face to its neighbour's shaft itself (the neighbour lies past a frameless portal's plane). */
    private static boolean isJoinedInLevel(final Level level, final BlockPos pos, final Direction side) {
        final BlockPos next = pos.relative(side);
        return level.isLoaded(next) && hasShaft(level, next, level.getBlockState(next), side.getOpposite());
    }

    private static boolean inPair(final KineticBlockEntity be) {
        final ResourceKey<Level> dimension = be.getLevel().dimension();
        for (final Direction side : Direction.values()) {
            if (PAIRS.containsKey(new End(dimension, be.getBlockPos(), side))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Driving

    private static void update(final MinecraftServer server) {
        // driven end -> its pair, for the loop checks
        final Map<KineticBlockEntity, Pair> drivenBy = new IdentityHashMap<>();
        for (final Pair pair : ALL) {
            if (pair.driver != null) {
                final KineticBlockEntity driven = loadedEnd(server, pair.other(pair.driver));
                if (driven != null) {
                    drivenBy.put(driven, pair);
                }
            }
        }

        // per end, the sum over its pairs of what they add to its network: [capacity, stress]
        final Map<KineticBlockEntity, float[]> shares = new IdentityHashMap<>();
        for (final Pair pair : new ArrayList<>(ALL)) {
            final boolean aLoaded = isLoaded(server, pair.a);
            final boolean bLoaded = isLoaded(server, pair.b);
            final KineticBlockEntity ka = aLoaded ? loadedEnd(server, pair.a) : null;
            final KineticBlockEntity kb = bLoaded ? loadedEnd(server, pair.b) : null;
            if ((aLoaded && ka == null) || (bLoaded && kb == null)) {
                dissolve(server, pair, "a shaft is gone");
                continue;
            }
            if (pair.portal.isRemoved() && pair.portal.getRemovalReason() != null && pair.portal.getRemovalReason().shouldDestroy()) {
                dissolve(server, pair, "its portal was removed");
                continue;
            }
            if (aLoaded && bLoaded) {
                if (pair.moving && !stillLinked(server, pair)) {
                    dissolve(server, pair, "the shafts no longer line up");
                    continue;
                }
                if (pair.driver == null) {
                    tryStart(server, pair, ka, kb, drivenBy);
                }
                if (pair.driver != null) {
                    step(server, pair, ka, kb, drivenBy);
                }
                if (pair.driver != null) {
                    shareStress(pair, ka, kb);
                }
            }
            // one side unloaded: the loaded end keeps its last shares
            if (pair.driver != null) {
                if (ka != null) {
                    addShare(shares, ka, pair.capacityA, pair.loadA);
                }
                if (kb != null) {
                    addShare(shares, kb, pair.capacityB, pair.loadB);
                }
            }
        }

        for (final Map.Entry<KineticBlockEntity, float[]> entry : shares.entrySet()) {
            setShare(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }
        for (final KineticBlockEntity be : shared) {
            if (!shares.containsKey(be) && !be.isRemoved()) {
                setShare(be, 0, 0);
            }
        }
        if (shares.isEmpty()) {
            shared = Set.of();
        } else {
            shared = Collections.newSetFromMap(new IdentityHashMap<>(shares.size()));
            shared.addAll(shares.keySet());
        }
    }

    private static void addShare(final Map<KineticBlockEntity, float[]> shares, final KineticBlockEntity be, final float capacity, final float load) {
        final float[] share = shares.computeIfAbsent(be, k -> new float[2]);
        share[0] += capacity;
        share[1] += load;
    }

    /** A pair with a ship end still lines up through its portal (the portal is still the ship's), with an up-to-date sign. */
    private static boolean stillLinked(final MinecraftServer server, final Pair pair) {
        final ServerLevel level = server.getLevel(pair.portalEnd.dimension());
        if (level == null) {
            return false;
        }
        final Frame frame = SABLE ? IPCreateShaftShips.frameAt(level, pair.portalEnd.pos()) : null;
        final FarEnd far = link(pair.portal, level, pair.portalEnd.pos(), pair.portalEnd.side(), frame);
        if (far == null || !far.end().equals(pair.other(pair.portalEnd))) {
            return false;
        }
        pair.sign = far.sign(); // the same either way round
        return true;
    }

    private static void tryStart(
        final MinecraftServer server, final Pair pair, final KineticBlockEntity ka, final KineticBlockEntity kb,
        final Map<KineticBlockEntity, Pair> drivenBy
    ) {
        // an end saved while driven by this pair (a reload): carry on where it was
        if (savedDriven(kb, pair.b.side())) {
            adopt(pair, pair.a, ka, kb);
            drivenBy.put(kb, pair);
            return;
        }
        if (savedDriven(ka, pair.a.side())) {
            adopt(pair, pair.b, kb, ka);
            drivenBy.put(ka, pair);
            return;
        }
        if (tick < pair.idleUntil) {
            return;
        }
        final float sa = faceSpeed(ka, pair.a.side());
        final float sb = faceSpeed(kb, pair.b.side());
        if (sa == 0 && sb == 0) {
            return;
        }
        final boolean both = sa != 0 && sb != 0;
        // one side turning drives the other; both turning: as when Create joins two turning networks, the faster
        // overpowers the slower (the first when equal)
        final End driver = sb == 0 ? pair.a : sa == 0 ? pair.b : Math.abs(sa) >= Math.abs(sb) ? pair.a : pair.b;
        final KineticBlockEntity driving = driver.equals(pair.a) ? ka : kb;
        final KineticBlockEntity driven = driver.equals(pair.a) ? kb : ka;
        if (!canBeDriven(driven, drivenBy) || feedsBack(driving, pair, drivenBy)
            || (driving.getLevel() == driven.getLevel() && driving.network != null && Objects.equals(driving.network, driven.network))) {
            return;
        }
        if (both && (portalFed(driven, drivenBy) || dependsOn(server, driving, driven, pair, drivenBy))) {
            return; // the other side is turned through the portals already
        }
        if (both && Math.signum(pair.sign * sa) != Math.signum(sb)) {
            // turning against each other: Create breaks the block that would join them
            LOGGER.info(TAG + "{} {} and {} {} turn against each other through a portal: breaking {}", pair.a.dimension().location(),
                pair.a.pos().toShortString(), pair.b.dimension().location(), pair.b.pos().toShortString(), driven.getBlockPos().toShortString());
            driven.getLevel().destroyBlock(driven.getBlockPos(), true);
            return;
        }
        pair.driver = driver;
        LOGGER.info(TAG + "{} {} drives {} {} through a portal at {} RPM{}", driver.dimension().location(), driver.pos().toShortString(),
            pair.other(driver).dimension().location(), pair.other(driver).pos().toShortString(), faceSpeed(driving, driver.side()),
            both ? " (overpowering its own " + faceSpeed(driven, pair.other(driver).side()) + " RPM)" : "");
    }

    private static void adopt(final Pair pair, final End driver, final KineticBlockEntity driving, final KineticBlockEntity driven) {
        pair.driver = driver;
        final KineticBlockEntity ka = driver.equals(pair.a) ? driving : driven;
        final KineticBlockEntity kb = driver.equals(pair.a) ? driven : driving;
        pair.capacityA = ((IPPortalShaftEnd) ka).ip$getPortalCapacity();
        pair.loadA = ((IPPortalShaftEnd) ka).ip$getPortalLoad();
        pair.capacityB = ((IPPortalShaftEnd) kb).ip$getPortalCapacity();
        pair.loadB = ((IPPortalShaftEnd) kb).ip$getPortalLoad();
    }

    private static void step(
        final MinecraftServer server, final Pair pair, final KineticBlockEntity ka, final KineticBlockEntity kb,
        final Map<KineticBlockEntity, Pair> drivenBy
    ) {
        final boolean fromA = pair.driver.equals(pair.a);
        final KineticBlockEntity driving = fromA ? ka : kb;
        final KineticBlockEntity driven = fromA ? kb : ka;
        final Direction drivingSide = pair.driver.side();
        final Direction drivenSide = pair.other(pair.driver).side();
        final IPPortalShaftEnd end = (IPPortalShaftEnd) driven;

        final float speed = faceSpeed(driving, drivingSide);
        if (speed == 0) {
            release(pair, driven, "the driving shaft stopped");
            drivenBy.remove(driven);
            return;
        }
        if (feedsBack(driving, pair, drivenBy)
            || (driving.getLevel() == driven.getLevel() && driving.network != null && Objects.equals(driving.network, driven.network))) {
            release(pair, driven, "it was driving itself (a loop through the portal)");
            drivenBy.remove(driven);
            pair.idleUntil = tick + LOOP_COOLDOWN_TICKS;
            return;
        }
        if (end.ip$getPortalSpeed() != 0 && !end.ip$isPortalDriven()) {
            // its own side took it over (a faster network), or Create reset it (a gearshift switched): let go; from
            // the next tick it is driven again, or drives the other way
            LOGGER.info(TAG + "{} {} no longer drives {} {}: its own side took it over", pair.driver.dimension().location(),
                pair.driver.pos().toShortString(), driven.getLevel().dimension().location(), driven.getBlockPos().toShortString());
            forget(driven);
            pair.driver = null;
            pair.upstream = Set.of();
            pair.clearShares();
            drivenBy.remove(driven);
            return;
        }
        pair.upstream = upstream(driving, pair, drivenBy);

        final float target = pair.sign * speed;
        if (end.ip$getPortalSpeed() == 0) {
            if (!startDriving(driven, drivenSide, target)) {
                pair.driver = null;
                pair.idleUntil = tick + LOOP_COOLDOWN_TICKS;
                return;
            }
            drivenBy.put(driven, pair);
        } else if (end.ip$getPortalSpeed() != target) {
            changeDriving(driven, drivenSide, target);
        }
    }

    /**
     * Each side's network gets the other side's own capacity and stress (its totals as Create last synced them,
     * without what this pair added there), so both show the totals one network would and overstress together.
     */
    private static void shareStress(final Pair pair, final KineticBlockEntity ka, final KineticBlockEntity kb) {
        final IPPortalShaftEnd ea = (IPPortalShaftEnd) ka;
        final IPPortalShaftEnd eb = (IPPortalShaftEnd) kb;
        final float ownCapacityA = Math.max(0, ea.ip$getNetworkCapacity() - pair.capacityA);
        final float ownStressA = Math.max(0, ea.ip$getNetworkStress() - pair.loadA);
        final float ownCapacityB = Math.max(0, eb.ip$getNetworkCapacity() - pair.capacityB);
        final float ownStressB = Math.max(0, eb.ip$getNetworkStress() - pair.loadB);
        if (DEBUG && (changed(pair.capacityA, ownCapacityB) || changed(pair.loadA, ownStressB)
            || changed(pair.capacityB, ownCapacityA) || changed(pair.loadB, ownStressA))) {
            LOGGER.info(TAG + "{} {}: own {} SU / {} SU; {} {}: own {} SU / {} SU", pair.a.dimension().location(), pair.a.pos().toShortString(),
                ownCapacityA, ownStressA, pair.b.dimension().location(), pair.b.pos().toShortString(), ownCapacityB, ownStressB);
        }
        pair.capacityA = ownCapacityB;
        pair.loadA = ownStressB;
        pair.capacityB = ownCapacityA;
        pair.loadB = ownStressA;
    }

    /** An end's shares of the far sides: the sums over all its pairs. */
    private static void setShare(final KineticBlockEntity be, final float capacity, final float load) {
        final IPPortalShaftEnd end = (IPPortalShaftEnd) be;
        if (!changed(end.ip$getPortalCapacity(), capacity) && !changed(end.ip$getPortalLoad(), load)) {
            return;
        }
        end.ip$setPortalCapacity(capacity);
        end.ip$setPortalLoad(load);
        if (be.hasNetwork()) {
            final var network = be.getOrCreateNetwork();
            if (capacity != 0 && network.members.containsKey(be)) {
                network.sources.putIfAbsent(be, 0f); // counted where Create sums its generators
            }
            network.updateNetwork();
        }
        be.setChanged();
    }

    private static void release(final Pair pair, final @Nullable KineticBlockEntity driven, final String why) {
        if (pair.driver == null) {
            return;
        }
        final End drivenEnd = pair.other(pair.driver);
        if (driven != null) {
            stopDriving(driven);
        }
        LOGGER.info(TAG + "{} {} no longer drives {} {}: {}", pair.driver.dimension().location(), pair.driver.pos().toShortString(),
            drivenEnd.dimension().location(), drivenEnd.pos().toShortString(), why);
        pair.driver = null;
        pair.upstream = Set.of();
        pair.clearShares();
    }

    private static void dissolve(final MinecraftServer server, final Pair pair, final String why) {
        if (pair.driver != null) {
            release(pair, loadedEnd(server, pair.other(pair.driver)), why);
        }
        PAIRS.remove(pair.a, pair);
        PAIRS.remove(pair.b, pair);
        ALL.remove(pair);
        if (DEBUG) {
            LOGGER.info(TAG + "pair {} {} <-> {} {} dropped: {}", pair.a.dimension().location(), pair.a.pos().toShortString(),
                pair.b.dimension().location(), pair.b.pos().toShortString(), why);
        }
    }

    /** A stable network id for the network a driven end roots; never a block's {@code asLong} as generators use. */
    private static long networkId(final BlockPos pos, final Direction side) {
        return (pos.asLong() * 31 + side.ordinal()) ^ 0x69705f7368616674L;
    }

    /**
     * Starts turning an end from the portal: its Create source becomes the portal block in front of the face, where no
     * block entity is, and Create spreads its speed as from any source, overpowering what turned it before (its own
     * generators keep their capacity in the network, as overpowered generators do). False if its face turns no shaft.
     */
    private static boolean startDriving(final KineticBlockEntity be, final Direction side, final float faceSpeed) {
        final IPPortalShaftEnd end = (IPPortalShaftEnd) be;
        final float previous = be.getSpeed();
        if (be.getTheoreticalSpeed() != 0) {
            be.detachKinetics(); // as a generator becoming faster than its network does
        }
        end.ip$setPortalSide(side);
        end.ip$setPortalSpeed(faceSpeed);
        be.source = be.getBlockPos().relative(side).immutable();
        final float modifier = IPRotationPropagatorInvoker.ip$getAxisModifier(be, side);
        if (modifier == 0) {
            be.source = null;
            forget(be);
            return false;
        }
        be.setSpeed(faceSpeed / modifier);
        be.setNetwork(networkId(be.getBlockPos(), side));
        be.attachKinetics();
        be.onSpeedChanged(previous);
        be.sendData();
        return true;
    }

    /** A new speed for a driven end: re-spread from it, as Create does when a generator changes speed. */
    private static void changeDriving(final KineticBlockEntity be, final Direction side, final float faceSpeed) {
        final IPPortalShaftEnd end = (IPPortalShaftEnd) be;
        final float previous = be.getSpeed();
        end.ip$setPortalSpeed(faceSpeed);
        final float modifier = IPRotationPropagatorInvoker.ip$getAxisModifier(be, side);
        be.detachKinetics();
        be.setSpeed(modifier == 0 ? 0 : faceSpeed / modifier);
        be.attachKinetics();
        be.onSpeedChanged(previous);
        be.sendData();
    }

    /** Stops a driven end and what it drives (its side's own generators then turn it again, as Create's do). */
    private static void stopDriving(final KineticBlockEntity be) {
        final IPPortalShaftEnd end = (IPPortalShaftEnd) be;
        if (end.ip$isPortalDriven()) {
            if (be.getTheoreticalSpeed() != 0) {
                be.detachKinetics();
            }
            be.removeSource();
            be.sendData();
        }
        forget(be);
    }

    /** Drops a driven end's drive, leaving its Create state alone (its shares go with the pair). */
    private static void forget(final KineticBlockEntity be) {
        final IPPortalShaftEnd end = (IPPortalShaftEnd) be;
        end.ip$setPortalSpeed(0);
        end.ip$setPortalSide(null);
        be.setChanged();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Loops

    /** Whether {@code driving}'s network takes power from a driven end whose own power comes through {@code pair}. */
    private static boolean feedsBack(final KineticBlockEntity driving, final Pair pair, final Map<KineticBlockEntity, Pair> drivenBy) {
        if (!driving.hasNetwork()) {
            return false;
        }
        for (final KineticBlockEntity source : driving.getOrCreateNetwork().sources.keySet()) {
            final Pair through = drivenBy.get(source);
            if (through != null && (through == pair || through.upstream.contains(pair))) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code be}'s network is turned through a portal (by any pair) already. */
    private static boolean portalFed(final KineticBlockEntity be, final Map<KineticBlockEntity, Pair> drivenBy) {
        if (!be.hasNetwork()) {
            return false;
        }
        for (final KineticBlockEntity source : be.getOrCreateNetwork().sources.keySet()) {
            if (drivenBy.containsKey(source)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code driving}'s network takes power, through portals, from {@code driven}'s network. */
    private static boolean dependsOn(
        final MinecraftServer server, final KineticBlockEntity driving, final KineticBlockEntity driven, final Pair pair,
        final Map<KineticBlockEntity, Pair> drivenBy
    ) {
        if (driven.network == null) {
            return false;
        }
        for (final Pair through : upstream(driving, pair, drivenBy)) {
            if (through == pair || through.driver == null) {
                continue;
            }
            final KineticBlockEntity from = loadedEnd(server, through.driver);
            if (from != null && from.getLevel() == driven.getLevel() && Objects.equals(from.network, driven.network)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Pair> upstream(final KineticBlockEntity driving, final Pair pair, final Map<KineticBlockEntity, Pair> drivenBy) {
        final Set<Pair> upstream = Collections.newSetFromMap(new IdentityHashMap<>());
        upstream.add(pair);
        if (driving.hasNetwork()) {
            for (final KineticBlockEntity source : driving.getOrCreateNetwork().sources.keySet()) {
                final Pair through = drivenBy.get(source);
                if (through != null) {
                    upstream.addAll(through.upstream);
                }
            }
        }
        return upstream;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ends

    /** Create's speed of a block's shaft on one face. */
    public static float faceSpeed(final KineticBlockEntity be, final Direction side) {
        final float speed = be.getTheoreticalSpeed();
        return speed == 0 ? 0 : speed * IPRotationPropagatorInvoker.ip$getAxisModifier(be, side);
    }

    private static boolean savedDriven(final KineticBlockEntity be, final Direction side) {
        final IPPortalShaftEnd end = (IPPortalShaftEnd) be;
        return end.ip$isPortalDriven() && end.ip$getPortalSide() == side;
    }

    /** Not driven by a pair already. Any kinetic block can be driven. */
    private static boolean canBeDriven(final KineticBlockEntity be, final Map<KineticBlockEntity, Pair> drivenBy) {
        return !drivenBy.containsKey(be) && ((IPPortalShaftEnd) be).ip$getPortalSpeed() == 0;
    }

    private static boolean isLoaded(final MinecraftServer server, final End end) {
        final ServerLevel level = server.getLevel(end.dimension());
        return level != null && level.isLoaded(end.pos());
    }

    /** The end's kinetic block entity if loaded and still with a shaft towards the portal, else null. */
    private static @Nullable KineticBlockEntity loadedEnd(final MinecraftServer server, final End end) {
        final ServerLevel level = server.getLevel(end.dimension());
        if (level == null || !level.isLoaded(end.pos())) {
            return null;
        }
        final BlockEntity be = level.getBlockEntity(end.pos());
        if (!(be instanceof KineticBlockEntity kinetic) || kinetic.isRemoved() || !hasShaft(level, end.pos(), kinetic.getBlockState(), end.side())) {
            return null;
        }
        return kinetic;
    }

    private static boolean changed(final float before, final float after) {
        return Math.abs(before - after) > 1.0e-4f + 1.0e-4f * Math.max(Math.abs(before), Math.abs(after));
    }
}
