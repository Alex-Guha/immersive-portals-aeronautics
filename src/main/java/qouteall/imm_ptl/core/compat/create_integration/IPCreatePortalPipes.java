/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.FluidNetwork;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.foundation.ICapabilityProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.IPPortalLookup;
import qouteall.imm_ptl.core.compat.mixin_create.IPFluidNetworkAccessor;
import qouteall.imm_ptl.core.compat.mixin_create.IPPipeConnectionAccessor;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipMount;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * Create's fluid pipes through IP portals. A pipe end that opens onto a portal (its face within a block of the portal
 * plane, facing it, inside the opening) is joined to the pipe end at the same spot on the other side, as if the two
 * were neighbours:
 * <ul>
 *     <li>Fluid: the end is a network endpoint ({@link PortalSource}). Create moves fluid from a network's source
 *     straight into its targets, so filling the portal end on one side fills the targets of the other side's network
 *     (the one whose source is the far end) at once; nothing is held in the portal. Its flow animation follows the
 *     fluid leaving the near end, as between two pipes.</li>
 *     <li>Pressure: a pump's pressure that reaches a portal end carries on from the far end, as if a pump of that
 *     strength stood in the portal ({@link #mirrorPressure}), with the pump range left over. One pump moves fluid
 *     through a portal; pumps on both sides add up, as in a single network.</li>
 *     <li>Pipe changes on either side re-run Create's pump search on the other ({@link #propagateAcross}).</li>
 * </ul>
 * Ships: a pipe on a Sable ship joins through a portal mounted on that same ship, with the pipe at the fixed far side,
 * and back (the ship's blocks are placed in the world by its pose; {@link #linkVia}).
 * <p>
 * Mixins: {@code MixinFluidPropagator_PortalPipes}, {@code MixinFluidPipeBlock_PortalPipes},
 * {@code MixinPipeConnection_PortalPipes}, {@code MixinPumpBlockEntity_PortalPipes}. Rendering:
 * {@link IPCreatePortalPipeModel}.
 */
public final class IPCreatePortalPipes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPPortalPipes] ";
    /** {@code -Dip_aeronautics.debug.portalPipes=true}: logs links, mirrored pressure and cross-portal updates. */
    private static final boolean DEBUG = Boolean.getBoolean("ip_aeronautics.debug.portalPipes");

    /** Slack for a pipe face lying in the portal plane or a hair past it. */
    private static final double PLANE_EPSILON = 1.0e-3;
    /** Slack for a pipe on a ship, whose pose is worked out in doubles each tick (about 2.5 degrees, 1/20 block). */
    private static final double SHIP_COS = 1 - 1.0e-3;
    private static final double SHIP_GAP = 0.05;
    private static final boolean SABLE = net.neoforged.fml.ModList.get().isLoaded("sable");
    /** Ticks a {@link PortalSource} keeps its link before looking for the portal again. */
    private static final int LINK_REFRESH_TICKS = 20;
    /** Portal ends passed one into another within one fill (a network looping through several portals). */
    private static final int MAX_FILL_DEPTH = 8;

    /** Portal faces crossed in the current mirror or update chain (both ends of each), against loops. */
    private static final ThreadLocal<Set<String>> CROSSED = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<int[]> CHAIN_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<int[]> FILL_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private IPCreatePortalPipes() {
    }

    /**
     * The other end of a pipe face that opens onto a portal: the block at the same spot past the portal and its face
     * towards the portal. The far pipe may be missing or unloaded.
     */
    public record Link(Portal portal, ServerLevel farLevel, BlockPos farPos, Direction farSide) {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Finding portal faces

    /** A placeholder block (IP's nether-style portals) whose portal plane faces a pipe face on {@code side}. */
    public static boolean isPlaceholderFacing(final BlockState neighbour, final Direction side) {
        return neighbour.getBlock() == PortalPlaceholderBlock.instance
            && neighbour.getValue(PortalPlaceholderBlock.AXIS) == side.getAxis();
    }

    /**
     * Whether the pipe face of {@code pos} on {@code side} opens onto a portal. Next to a placeholder block on any side
     * (so the client, the pipe's block state and the window before the portal entity spawns all agree); on the server
     * also before any portal plane, frameless ones included.
     */
    public static boolean isPortalFace(final BlockGetter level, final BlockPos pos, final Direction side) {
        final BlockPos neighbourPos = pos.relative(side);
        if (isPlaceholderFacing(level.getBlockState(neighbourPos), side)) {
            return true;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        // Create's own neighbours (pipes, pumps) win over a portal plane between them
        if (FluidPropagator.getPipe(level, neighbourPos) != null) {
            return false;
        }
        return link(serverLevel, pos, side) != null;
    }

    /** The portal a pipe face opens onto and the far end, or null. */
    public static @Nullable Link link(final ServerLevel level, final BlockPos pos, final Direction side) {
        final IPCreatePortalShafts.Frame frame = SABLE ? IPCreateShaftShips.frameAt(level, pos) : null;
        final Vec3 localNormal = Vec3.atLowerCornerOf(side.getNormal());
        final Vec3 localFace = Vec3.atCenterOf(pos).add(localNormal.scale(0.5));
        final Vec3 n = frame == null ? localNormal : frame.toWorldDirection(localNormal);
        final Vec3 face = frame == null ? localFace : frame.toWorld(localFace);
        final AABB search = new AABB(face, face.add(n)).inflate(0.25);
        for (final Portal portal : IPPortalLookup.touching(level, search, p -> p.isTeleportable() && !p.isRemoved())) {
            final Link found = linkVia(portal, pos, side, frame);
            if (found != null) {
                // a belt or shaft across the portal owns the space between: nothing passes ({@link IPCreatePortalEnds})
                return IPCreatePortalEnds.outranked(found.farLevel(), found.farPos(), found.farSide(), IPCreatePortalEnds.Kind.PIPE)
                    ? null : found;
            }
        }
        return null;
    }

    /**
     * The far end of the face {@code side} of {@code pos} through {@code portal}: when the face lies within a block in
     * front of its plane, facing it, inside the opening, and the portal keeps the block grid. Else null. Worked out in
     * world space ({@code frame}: the ship whose plot holds {@code pos}, null in the world): through a ship-mounted
     * portal (any portal of its cluster) exactly one end is on its ship, as for shafts ({@link IPCreatePortalShafts}): a
     * pipe on a ship at its own portal joins the fixed far side, and back.
     */
    private static @Nullable Link linkVia(
        final Portal portal, final BlockPos pos, final Direction side, final IPCreatePortalShafts.@Nullable Frame frame
    ) {
        if (Math.abs(portal.getScale() - 1) > 1.0e-6 || !(portal.getDestWorld() instanceof ServerLevel farLevel)) {
            return null;
        }
        final IPShipMount mount = IPShipPortals.clusterMountOf(portal);
        if (frame != null && (mount == null || !frame.ship().equals(mount.carrier()))) {
            return null;
        }
        final double cos = mount != null ? SHIP_COS : 1 - PLANE_EPSILON;
        final double slack = mount != null ? SHIP_GAP : PLANE_EPSILON;
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
        if (mount == null || frame != null) {
            return farEnd(portal, farLevel, farOnPlane, farDir, null, cos, slack);
        }
        if (!SABLE) {
            return null;
        }
        // a fixed pipe at a ship-mounted portal: the far end is on the portal's ship
        final AABB around = new AABB(farOnPlane, farOnPlane.add(farDir.scale(1.5))).inflate(1);
        for (final IPCreatePortalShafts.Frame ship : IPCreateShaftShips.shipsTouching(farLevel, around)) {
            if (ship.ship().equals(mount.carrier())) {
                final Link found = farEnd(portal, farLevel, farOnPlane, farDir, ship, cos, slack);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The block past the far plane in {@code frame} (null: the world) whose face towards the plane lies within a block of it. */
    private static @Nullable Link farEnd(
        final Portal portal, final ServerLevel farLevel, final Vec3 farOnPlane, final Vec3 farDir,
        final IPCreatePortalShafts.@Nullable Frame frame, final double cos, final double slack
    ) {
        final Vec3 dir = frame == null ? farDir : frame.toLocalDirection(farDir).normalize();
        final Vec3 plane = frame == null ? farOnPlane : frame.toLocal(farOnPlane);
        final Direction farOut = Direction.getNearest(dir.x, dir.y, dir.z);
        final Vec3 farN = Vec3.atLowerCornerOf(farOut.getNormal());
        if (farN.dot(dir) < cos) {
            return null; // rotated off the block grid
        }
        for (final double reach : new double[]{0.5, 1.5}) {
            final BlockPos candidate = BlockPos.containing(plane.add(farN.scale(reach)));
            final Vec3 candidateFace = Vec3.atCenterOf(candidate).subtract(farN.scale(0.5));
            final double farGap = candidateFace.subtract(plane).dot(farN);
            if (farGap >= -slack && farGap < 1 - PLANE_EPSILON && (frame == null || frame.holds(farLevel, candidate))) {
                return new Link(portal, farLevel, candidate, farOut.getOpposite());
            }
        }
        return null;
    }

    /** The far pipe of a link when loaded and open towards the portal, else null. */
    static @Nullable FluidTransportBehaviour farPipe(final Link link) {
        if (!link.farLevel().isLoaded(link.farPos())) {
            return null;
        }
        final FluidTransportBehaviour pipe = FluidPropagator.getPipe(link.farLevel(), link.farPos());
        if (pipe == null || !pipe.canHaveFlowToward(link.farLevel().getBlockState(link.farPos()), link.farSide())) {
            return null;
        }
        return pipe;
    }

    private static String key(final Level level, final BlockPos pos, final Direction side) {
        return level.dimension().location() + "|" + pos.asLong() + "|" + side.ordinal();
    }

    /** Marks both ends of a link crossed in the current chain; false when either already was. */
    private static boolean cross(final Level level, final BlockPos pos, final Direction side, final Link link) {
        final Set<String> crossed = CROSSED.get();
        final String near = key(level, pos, side);
        final String far = key(link.farLevel(), link.farPos(), link.farSide());
        if (crossed.contains(near) || crossed.contains(far)) {
            return false;
        }
        crossed.add(near);
        crossed.add(far);
        return true;
    }

    private static void enterChain() {
        CHAIN_DEPTH.get()[0]++;
    }

    private static void exitChain() {
        if (--CHAIN_DEPTH.get()[0] == 0) {
            CROSSED.get().clear();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Pipe updates

    /**
     * After Create's {@code FluidPropagator.propagateChangedPipe} visited {@code visited}: for each portal end among
     * them, the same update from the far end, so the far side's pipes drop the pressure mirrored into them and its
     * pumps (and, through them, ours) distribute pressure again. A pump against the portal, at either end, is
     * rescheduled on that side instead, as Create reschedules pumps only from the pipes next to them.
     */
    public static void propagateAcross(
        final LevelAccessor world, final BlockPos changedPos, final BlockState changedState, final Set<BlockPos> visited
    ) {
        if (!(world instanceof ServerLevel level) || visited.isEmpty()) {
            return;
        }
        enterChain();
        try {
            for (final BlockPos pos : new ArrayList<>(visited)) {
                final FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
                if (pipe == null) {
                    continue;
                }
                final BlockState state = pos.equals(changedPos) ? changedState : level.getBlockState(pos);
                for (final Direction side : FluidPropagator.getPipeConnections(state, pipe)) {
                    if (FluidPropagator.getPipe(level, pos.relative(side)) != null) {
                        continue;
                    }
                    final Link link = link(level, pos, side);
                    if (link == null || !cross(level, pos, side, link) || !link.farLevel().isLoaded(link.farPos())
                        || FluidPropagator.getPipe(link.farLevel(), link.farPos()) == null) {
                        continue;
                    }
                    if (DEBUG) {
                        LOGGER.info(TAG + "pipe change at {} {} reaches {} {} {}", level.dimension().location(),
                            pos.toShortString(), link.farLevel().dimension().location(), link.farPos().toShortString(),
                            link.farSide());
                    }
                    // Create reschedules a pump only from a pipe next to it, and a pump against a portal has none on
                    // that side: reschedule it here, at either end, as that pipe would
                    if (level.getBlockEntity(pos) instanceof PumpBlockEntity pump) {
                        pump.updatePipesOnSide(side);
                    }
                    if (link.farLevel().getBlockEntity(link.farPos()) instanceof PumpBlockEntity farPump) {
                        farPump.updatePipesOnSide(link.farSide());
                        continue;
                    }
                    FluidPropagator.propagateChangedPipe(link.farLevel(), link.farPos(),
                        link.farLevel().getBlockState(link.farPos()));
                }
            }
        } finally {
            exitChain();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Pressure

    /**
     * A pump gave {@code pressure} to the face {@code side} of {@code pipe}, {@code distance} pipes from the pump.
     * When that face is a portal end, the same pressure carries on from the far end. {@code inbound} is the pump's
     * {@code pull} for an end face.
     */
    public static void afterPumpPressure(
        final FluidTransportBehaviour pipe, final Direction side, final boolean inbound, final float pressure, final int distance
    ) {
        if (!(pipe.getWorld() instanceof ServerLevel level) || pressure == 0) {
            return;
        }
        final BlockPos pos = pipe.getPos();
        if (FluidPropagator.getPipe(level, pos.relative(side)) != null) {
            return; // an inner face of the network
        }
        final Link link = link(level, pos, side);
        if (link != null) {
            mirrorPressure(level, pos, side, link, inbound, pressure, distance + 1);
        }
    }

    private static void mirrorPressure(
        final ServerLevel level, final BlockPos pos, final Direction side, final Link link,
        final boolean pull, final float pressure, final int distance
    ) {
        if (distance >= FluidPropagator.getPumpRange() || farPipe(link) == null) {
            return;
        }
        enterChain();
        try {
            if (!cross(level, pos, side, link)) {
                return;
            }
            if (DEBUG) {
                LOGGER.info(TAG + "{} pressure {} from {} {} {} into {} {} {} (pipe {} of {})", pull ? "pull" : "push",
                    pressure, level.dimension().location(), pos.toShortString(), side,
                    link.farLevel().dimension().location(), link.farPos().toShortString(), link.farSide(), distance,
                    FluidPropagator.getPumpRange());
            }
            distributeFrom(link.farLevel(), link.farPos(), link.farSide(), pull, pressure, distance);
        } finally {
            exitChain();
        }
    }

    /** One pipe's faces as seen by a pump's search: its distance and, per face, whether the flow there is inbound. */
    private static final class Node {
        final int distance;
        final Map<Direction, Boolean> inbound = new EnumMap<>(Direction.class);

        Node(final int distance) {
            this.distance = distance;
        }
    }

    /**
     * Pressure from a pump standing in the portal in front of {@code firstPipe}'s face {@code entry}: the search a
     * Create pump runs from its side ({@code PumpBlockEntity.distributePressureTo}), re-implemented for a pump without
     * a block. Same rules: a breadth-first walk over open pipe faces within the pump range, endpoints where the walk
     * meets a fluid handler, an open end, a pump facing the right way or a portal end; then only the branches that
     * reach an endpoint get pressure, each face {@code pressure} divided among the parallel faces at its distance.
     * Portal ends reached carry on again.
     */
    private static void distributeFrom(
        final ServerLevel level, final BlockPos firstPipe, final Direction entry, final boolean pull, final float pressure,
        final int firstDistance
    ) {
        final int range = FluidPropagator.getPumpRange();
        final Map<BlockPos, Node> graph = new HashMap<>();
        final Set<BlockFace> targets = new HashSet<>();
        final Set<BlockPos> visited = new HashSet<>();
        final List<BlockPos> frontier = new ArrayList<>();

        graph.computeIfAbsent(firstPipe, p -> new Node(firstDistance)).inbound.put(entry, !pull);
        frontier.add(firstPipe);
        while (!frontier.isEmpty()) {
            final BlockPos pos = frontier.remove(0);
            if (!level.isLoaded(pos) || !visited.add(pos)) {
                continue;
            }
            final FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
            if (pipe == null) {
                continue;
            }
            final Node node = graph.get(pos);
            for (final Direction face : FluidPropagator.getPipeConnections(level.getBlockState(pos), pipe)) {
                if (pos.equals(firstPipe) && face == entry) {
                    continue;
                }
                final BlockPos next = pos.relative(face);
                if (!level.isLoaded(next)) {
                    continue;
                }
                if (isEndpoint(level, pos, face, pull)) {
                    node.inbound.put(face, pull);
                    targets.add(new BlockFace(pos, face));
                    continue;
                }
                final FluidTransportBehaviour nextPipe = FluidPropagator.getPipe(level, next);
                if (nextPipe == null || level.getBlockEntity(next) instanceof PumpBlockEntity || visited.contains(next)) {
                    continue;
                }
                node.inbound.put(face, pull);
                if (node.distance + 1 >= range) {
                    targets.add(new BlockFace(pos, face));
                    continue;
                }
                graph.computeIfAbsent(next, p -> new Node(node.distance + 1)).inbound.put(face.getOpposite(), !pull);
                frontier.add(next);
            }
        }

        final Map<Integer, Set<BlockFace>> reached = new HashMap<>();
        reachesEndpoint(graph, targets, reached, firstPipe, entry, pull);

        for (final Set<BlockFace> faces : reached.values()) {
            final float share = pressure / Math.max(1, faces.size() - 1);
            for (final BlockFace face : faces) {
                final FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, face.getPos());
                final Node node = graph.get(face.getPos());
                final Boolean inbound = node == null ? null : node.inbound.get(face.getFace());
                if (pipe == null || inbound == null) {
                    continue;
                }
                pipe.addPressure(face.getFace(), inbound, share);
                if (targets.contains(face) && !(pipe.getPos().equals(firstPipe) && face.getFace() == entry)) {
                    afterPumpPressure(pipe, face.getFace(), inbound, share, node.distance);
                }
            }
        }
    }

    /** Depth-first: which faces lie on a way from {@code entry} of {@code pos} to an endpoint, grouped by distance. */
    private static boolean reachesEndpoint(
        final Map<BlockPos, Node> graph, final Set<BlockFace> targets, final Map<Integer, Set<BlockFace>> reached,
        final BlockPos pos, final Direction entry, final boolean pull
    ) {
        final Node node = graph.get(pos);
        if (node == null) {
            return false;
        }
        boolean any = false;
        for (final Map.Entry<Direction, Boolean> face : node.inbound.entrySet()) {
            final Direction side = face.getKey();
            if (side == entry) {
                continue;
            }
            final BlockFace here = new BlockFace(pos, side);
            if (targets.contains(here)) {
                reached.computeIfAbsent(node.distance, d -> new HashSet<>()).add(here);
                any = true;
                continue;
            }
            if (face.getValue() != pull || !reachesEndpoint(graph, targets, reached, pos.relative(side), side.getOpposite(), pull)) {
                continue;
            }
            reached.computeIfAbsent(node.distance, d -> new HashSet<>()).add(here);
            any = true;
        }
        if (any) {
            reached.computeIfAbsent(node.distance, d -> new HashSet<>()).add(new BlockFace(pos, entry));
        }
        return any;
    }

    /** Create's endpoint test of a pump's search ({@code PumpBlockEntity.hasReachedValidEndpoint}), plus portal ends. */
    private static boolean isEndpoint(final ServerLevel level, final BlockPos pos, final Direction face, final boolean pull) {
        final BlockPos next = pos.relative(face);
        final BlockState nextState = level.getBlockState(next);
        if (PumpBlock.isPump(nextState) && nextState.getValue(PumpBlock.FACING).getAxis() == face.getAxis()
            && level.getBlockEntity(next) instanceof PumpBlockEntity pump) {
            // a pump pulls on its back; the face of it we touch is face.getOpposite()
            final boolean front = nextState.getValue(PumpBlock.FACING) == face.getOpposite();
            return pump.isPullingOnSide(front) != pull;
        }
        final FluidTransportBehaviour nextPipe = FluidPropagator.getPipe(level, next);
        if (nextPipe != null && nextPipe.canHaveFlowToward(nextState, face.getOpposite())) {
            return false;
        }
        final BlockEntity blockEntity = level.getBlockEntity(next);
        if (blockEntity != null && level.getCapability(Capabilities.FluidHandler.BLOCK, next, face.getOpposite()) != null) {
            return true;
        }
        if (isPortalFace(level, pos, face)) {
            return true;
        }
        return FluidPropagator.isOpenEnd(level, pos, face);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fluid

    /**
     * The source of a pipe face that opens onto a portal. An endpoint: Create's networks fill it (the far side's
     * targets get the fluid) and start from it (its fluid is whatever the far end is pushing out).
     */
    public static final class PortalSource extends FlowSource {
        private final BlockPos pos;
        private final Direction side;
        private final ICapabilityProvider<IFluidHandler> handler = ICapabilityProvider.of(new Passage(this));
        private @Nullable ServerLevel level;
        private @Nullable Link link;
        private boolean linkChecked;
        private long linkCheckedAt;

        public PortalSource(final BlockFace location) {
            super(location);
            this.pos = location.getPos();
            this.side = location.getFace();
        }

        @Override
        public void manageSource(final Level world, final BlockEntity networkBE) {
            if (world instanceof ServerLevel serverLevel) {
                level = serverLevel;
            }
        }

        @Nullable Link link() {
            final ServerLevel current = level;
            if (current == null) {
                return null;
            }
            final long now = current.getGameTime();
            if (!linkChecked || now - linkCheckedAt >= LINK_REFRESH_TICKS || now < linkCheckedAt) {
                final Link found = IPCreatePortalPipes.link(current, pos, side);
                if (DEBUG && (found == null) != (link == null)) {
                    LOGGER.info(TAG + "{} {} {}: {}", current.dimension().location(), pos.toShortString(), side,
                        found == null ? "no portal" : "linked to " + found.farLevel().dimension().location() + " "
                            + found.farPos().toShortString() + " " + found.farSide());
                }
                link = found;
                linkChecked = true;
                linkCheckedAt = now;
            }
            return link;
        }

        @Override
        public FluidStack provideFluid(final Predicate<FluidStack> extractionPredicate) {
            final Link current = link();
            final FluidTransportBehaviour far = current == null ? null : farPipe(current);
            if (far == null) {
                return FluidStack.EMPTY;
            }
            final FluidStack outward = far.getProvidedOutwardFluid(current.farSide());
            return extractionPredicate.test(outward) ? outward : FluidStack.EMPTY;
        }

        @Override
        public boolean isEndpoint() {
            return true;
        }

        @Override
        public @Nullable ICapabilityProvider<IFluidHandler> provideHandler() {
            return handler;
        }

        /** The network on the far side that starts at the far end, or null. */
        @Nullable FluidNetwork farNetwork() {
            final Link current = link();
            final FluidTransportBehaviour far = current == null ? null : farPipe(current);
            final PipeConnection connection = far == null ? null : far.getConnection(current.farSide());
            if (connection == null) {
                return null;
            }
            final Optional<FluidNetwork> network = ((IPPipeConnectionAccessor) connection).ip$network();
            return network.orElse(null);
        }
    }

    /**
     * The fluid handler of a portal end. Filling it fills the far side's targets, split evenly as Create splits a
     * transfer; it holds nothing and gives nothing (the far network's own transfer from it finds it empty).
     */
    private static final class Passage implements IFluidHandler {
        private final PortalSource source;

        Passage(final PortalSource source) {
            this.source = source;
        }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public @NotNull FluidStack getFluidInTank(final int tank) {
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(final int tank) {
            return 1000;
        }

        @Override
        public boolean isFluidValid(final int tank, final @NotNull FluidStack stack) {
            return true;
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            if (resource.isEmpty()) {
                return 0;
            }
            final int[] depth = FILL_DEPTH.get();
            if (depth[0] >= MAX_FILL_DEPTH) {
                return 0;
            }
            depth[0]++;
            try {
                return fillFar(resource, action);
            } finally {
                depth[0]--;
            }
        }

        private int fillFar(final FluidStack resource, final FluidAction action) {
            final FluidNetwork network = source.farNetwork();
            if (network == null) {
                return 0;
            }
            final IPFluidNetworkAccessor far = (IPFluidNetworkAccessor) network;
            final FluidStack networkFluid = far.ip$fluid();
            if (!networkFluid.isEmpty() && !FluidStack.isSameFluidSameComponents(networkFluid, resource)) {
                return 0;
            }
            final List<IFluidHandler> outputs = new ArrayList<>();
            for (final Pair<BlockFace, FlowSource> target : far.ip$targets()) {
                final ICapabilityProvider<IFluidHandler> provider = target.getSecond() == null ? null : target.getSecond().provideHandler();
                final IFluidHandler output = provider == null ? null : provider.getCapability();
                if (output != null && output != this && !outputs.contains(output)) {
                    outputs.add(output);
                }
            }
            final boolean simulate = action.simulate();
            final Map<IFluidHandler, Integer> simulated = new IdentityHashMap<>();
            int remaining = resource.getAmount();
            while (!outputs.isEmpty() && remaining > 0) {
                final int share = remaining / outputs.size();
                int extra = remaining % outputs.size();
                for (int i = 0; i < outputs.size() && remaining > 0; ) {
                    final IFluidHandler output = outputs.get(i);
                    int amount = share;
                    if (extra > 0) {
                        amount++;
                        extra--;
                    }
                    if (amount == 0) {
                        i++;
                        continue;
                    }
                    final int already = simulate ? simulated.getOrDefault(output, 0) : 0;
                    final int accepted = output.fill(resource.copyWithAmount(already + amount), action) - already;
                    final int filled = Math.max(0, Math.min(amount, accepted));
                    if (simulate) {
                        simulated.put(output, already + filled);
                    }
                    remaining -= filled;
                    if (filled < amount) {
                        outputs.remove(i);
                    } else {
                        i++;
                    }
                }
            }
            return resource.getAmount() - remaining;
        }

        @Override
        public @NotNull FluidStack drain(final FluidStack resource, final FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public @NotNull FluidStack drain(final int maxDrain, final FluidAction action) {
            return FluidStack.EMPTY;
        }
    }
}
