/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.TrackBlock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.IPPortalLookup;
import qouteall.imm_ptl.core.compat.mixin_create.IPCarriageAccessor;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * A Create carriage crossing an IP portal (docs/lessons/create-trains.md). Create keeps one carriage entity per dimension the
 * carriage spans; each is placed from the bogeys in its own dimension and, for the part in the other one, from a straight
 * extrapolation through the portal-track node ({@code DimensionalCarriageEntity.pivot}). At a portal back into the same
 * dimension the two copies are keyed by portal side instead ({@link IPCreateTrainSides}); everything here goes by a
 * copy's key ({@code IPCreateTrainSides.keyOf}), so both kinds of portal are crossed the same way.
 * <p>
 * Create counts the step between the two portal-track nodes as length 0 ({@code TrackEdge.getLength} of an
 * inter-dimensional edge), but the nodes sit on the faces of the track blocks while an IP portal's plane runs through
 * the middle of the portal blocks: through the portal the far node lies about one block past the near one. Seen through
 * the portal, the far copy is that much further along than the near copy continues, so Create hides the overlap behind a
 * whole-block cut and a slice of purple glass.
 * <p>
 * The gap is closed gradually: every copy near a portal track is moved along its axis towards the portal by
 * {@code gap * ramp(u)}, where {@code u} is the distance of its middle from the portal plane, measured into its own
 * track's side. The ramp rises smoothly from 0 at {@code u = gap/2 + RAMP} to {@code gap} at {@code u = gap/2 - RAMP},
 * and {@code ramp(u) + ramp(gap - u) = 1}. Through the portal the two copies of a carriage have {@code u + u' = gap}
 * (Create's straight extrapolation through the zero-length step), so their shifts add up to the gap: they line up
 * exactly at every point, whatever Create's split state, while the carriage gains the extra block over 2 * RAMP blocks
 * of track (a few percent of its speed) instead of over its own length. 1.23 closed it over the carriage's wheel span,
 * which made each carriage lurch towards the portal. The entity itself is moved (server and client), so riders,
 * collisions and couplings follow; carriages within RAMP of a portal stand up to half a block off their rails' spot.
 * Rendering then shows each copy whole, cut at the portal plane ({@code IPCreateCarriageCrossingClient}).
 * <p>
 * A side of the portal may be on a ship ({@link IPCreateTrainPortal}): that copy stands in the ship's plot, and all of
 * the above holds there in plot coordinates, since track and portal are rigid against each other.
 * <p>
 * {@code -Dip_aeronautics.stockTrains=true} turns all of this off (Create's glass and whole-block cut).
 */
public final class IPCreateCarriageCrossing {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean STOCK = Boolean.getBoolean("ip_aeronautics.stockTrains");
    /** A larger gap means the portal found is not the one the track crosses. */
    private static final double MAX_GAP = 4.0;
    /** The gap of an IP nether portal with the far track right next to it: two half blocks. */
    private static final double DEFAULT_GAP = 1.0;
    /** Half the length of track over which the gap is closed. */
    private static final double RAMP = 24.0;
    /** How far from a portal a carriage's middle is looked for, and how often (ticks). */
    private static final double SEARCH = RAMP + 16.0;
    private static final int SEARCH_INTERVAL = 5;
    /** The carriage axis must meet the portal plane at least this steeply (cosine). */
    private static final double MIN_AXIS_DOT = 0.7;

    /** The portal each copy approaches or leaves, if any (weak: Create drops copies). */
    private static final Map<Carriage.DimensionalCarriageEntity, Approach> APPROACHES =
        Collections.synchronizedMap(new WeakHashMap<>());
    /** Measured gaps by portal (both copies measure it once the carriage is split). */
    private static final Map<UUID, Double> GAPS = new ConcurrentHashMap<>();

    /**
     * @param portal      the portal on the copy's track, or null
     * @param origin      the plane's point, in the track's coordinates
     * @param trackNormal the portal plane's normal pointing to the side the track (and the copy's wheels) lie on
     * @param withWheel   whether a wheel point of the carriage was in the copy's dimension at the search
     */
    private record Approach(@Nullable Portal portal, Vec3 origin, Vec3 trackNormal, long searchedAt, boolean withWheel) {
    }

    /** The portal of each copy's crossing (weak: Create drops copies). Server and client thread both use it. */
    private static final Map<Carriage.DimensionalCarriageEntity, CachedPortal> PORTALS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private IPCreateCarriageCrossing() {
    }

    /**
     * One copy's crossing.
     *
     * @param via        the IP portal at this copy's portal-track node, leading to the other copy's dimension
     * @param keepPoint  a point of the portal plane, in the coordinates of this copy's track (its ship's plot on a ship)
     * @param keepNormal the plane's normal in those coordinates, pointing to the side this copy's own part lies on
     * @param farHalf    the carriage is not split (Create has no copy on the other side yet, or no longer): the part
     *                   past the plane is drawn in the other dimension through the portal (client only)
     * @param otherKey   the key of the carriage's copy past the portal ({@code Carriage.entities}): the portal's
     *                   destination dimension, or for a portal within one dimension the side key of its far face
     */
    public record Crossing(IPCreateTrainPortal via, Vec3 keepPoint, Vec3 keepNormal, boolean farHalf, ResourceKey<Level> otherKey) {
        public Portal portal() {
            return via.portal();
        }

        /** @return whether a position in this copy's coordinates lies past the portal plane */
        public boolean isBeyond(final Vec3 position) {
            return position.subtract(keepPoint).dot(keepNormal) < 0.0;
        }

        /** @return how far a position in the world, in this copy's level, is on this copy's side of the plane */
        public double worldDepth(final Vec3 position) {
            return position.subtract(via.toWorld(keepPoint)).dot(via.toWorldDirection(keepNormal));
        }
    }

    private record CachedPortal(TrackNodeLocation node, ResourceKey<Level> otherKey, Portal portal) {
    }

    /** The last crossing of each copy, for a copy Create keeps a few ticks after the carriage left. */
    private static final Map<Carriage.DimensionalCarriageEntity, Crossing> LAST =
        Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * @return the crossing of this copy of a carriage, or null if the carriage is not split between this copy's key
     * and one other through an IP portal
     */
    @Nullable
    public static Crossing of(final CarriageContraptionEntity entity, final Carriage.DimensionalCarriageEntity dce) {
        if (STOCK || dce.pivot == null) {
            return fail(STOCK ? "stock" : "no portal node in this dimension");
        }
        final Carriage carriage = entity.getCarriage();
        if (carriage == null) {
            return fail("no carriage");
        }
        final Level level = entity.level();
        final ResourceKey<Level> key = IPCreateTrainSides.keyOf(entity);
        TrackNodeLocation otherNode = null;
        ResourceKey<Level> otherKey = null;
        for (final Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entry :
            ((IPCarriageAccessor) carriage).ip_getEntities().entrySet()) {
            if (entry.getValue() == dce || entry.getKey().equals(key) || entry.getValue() == null
                || entry.getValue().pivot == null) {
                continue;
            }
            if (otherNode != null) {
                // spread over three dimensions (or portal sides): leave it to Create
                return fail("portal nodes in three dimensions");
            }
            otherNode = entry.getValue().pivot;
            otherKey = entry.getKey();
        }
        if (otherNode == null) {
            // Create keeps a copy for a few ticks after the carriage has left its dimension (manageEntities'
            // discardTicks); the other copy's node is gone by then. That copy lies entirely past the plane: it keeps
            // its last cut, which hides it
            final Crossing last = LAST.get(dce);
            if (last != null && !hasPointIn(carriage, key)) {
                return last;
            }
            LAST.remove(dce);
            return fail("no portal node in another dimension");
        }
        final Vec3 node = dce.pivot.getLocation();
        final IPCreateTrainPortal via = portalAt(level, dce, dce.pivot, otherKey, otherNode);
        if (via == null) {
            return fail("no IP portal to " + otherKey.location() + " at node " + node);
        }
        final Vec3 gap = via.back(otherNode.getLocation()).subtract(node);
        if (gap.lengthSqr() > MAX_GAP * MAX_GAP) {
            return fail("gap " + gap + " through portal " + via.portal().getId() + " too large");
        }
        final Vec3 origin = via.origin();
        Vec3 normal = via.normal();
        if (node.subtract(origin).dot(normal) < 0.0) {
            normal = normal.scale(-1.0);
        }
        if (GAPS.put(via.portal().getUUID(), gap.length()) == null && via.isThroughShip()) {
            LOGGER.debug("[IPTrainCrossing] carriage {} in {} crosses portal {} through a ship: gap {}, plane {} normal {}",
                entity.getId(), level.dimension().location(), via.portal().getId(), String.format("%.3f", gap.length()), origin, normal);
        }
        final Crossing crossing = new Crossing(via, origin, normal, false, otherKey);
        LAST.put(dce, crossing);
        return crossing;
    }

    /**
     * @return how far this copy is moved (along its axis, towards the portal) to close the portal gap; zero away from
     * portal tracks
     */
    public static Vec3 shift(final CarriageContraptionEntity entity, final Carriage.DimensionalCarriageEntity dce) {
        if (STOCK) {
            return Vec3.ZERO;
        }
        final Carriage carriage = entity.getCarriage();
        final Vec3 first = dce.rotationAnchors.getFirst();
        final Vec3 second = dce.rotationAnchors.getSecond();
        if (carriage == null || first == null || second == null || first.distanceToSqr(second) < 1.0E-6) {
            return Vec3.ZERO;
        }
        final Vec3 middle = first.add(second).scale(0.5);
        if (!isFinite(middle)) {
            return Vec3.ZERO;
        }
        final Vec3 axis = first.subtract(second).normalize();
        final Level level = entity.level();
        final long now = level.getGameTime();
        Approach approach = APPROACHES.get(dce);
        // a search made while no wheel was here found nothing (a client copy aligned from the anchors Create kept from
        // an earlier trip, before this client's carriage reached the portal): searched again as the wheels arrive, or
        // the new copy stood unshifted, up to the gap ahead, for the rest of the interval (the front flashed ahead)
        final ResourceKey<Level> key = IPCreateTrainSides.keyOf(entity);
        final boolean withWheel = hasWheelIn(carriage, key);
        if (approach == null || now - approach.searchedAt() >= SEARCH_INTERVAL || now < approach.searchedAt()
            || (approach.portal() != null && approach.portal().isRemoved()) || approach.withWheel() != withWheel) {
            final Approach previous = approach;
            approach = search(level, key, carriage, middle, axis, approach, now);
            APPROACHES.put(dce, approach);
            if (previous != null && withWheel && !previous.withWheel() && previous.portal() == null
                && approach.portal() != null && now - previous.searchedAt() < SEARCH_INTERVAL) {
                LOGGER.debug("[IPTrainCrossing] carriage {} in {}: wheels arrived, portal {} found {} ticks before the next search",
                    entity.getId(), level.dimension().location(), approach.portal().getId(),
                    SEARCH_INTERVAL - (now - previous.searchedAt()));
            }
        }
        final Portal portal = approach.portal();
        if (portal == null) {
            return Vec3.ZERO;
        }
        final Vec3 trackNormal = approach.trackNormal();
        final double u = middle.subtract(approach.origin()).dot(trackNormal);
        final double gap = GAPS.getOrDefault(portal.getUUID(), DEFAULT_GAP);
        final double x = Mth.clamp((gap / 2.0 + RAMP - u) / (2.0 * RAMP), 0.0, 1.0);
        final double amount = gap * x * x * (3.0 - 2.0 * x);
        if (amount < 1.0E-7) {
            return Vec3.ZERO;
        }
        // along the carriage's own axis, towards the portal
        return (axis.dot(trackNormal) > 0.0 ? axis.scale(-1.0) : axis).scale(amount);
    }

    /**
     * Finds the portal whose opening the carriage's axis runs through, within SEARCH of its middle, with a Create
     * portal track in front of it on the side of the carriage's wheels under this copy's key. Portals back into the
     * same dimension count too: a portal track in front of one is a portal edge of ours ({@link IPCreatePortalEdges}).
     */
    private static Approach search(final Level level, final ResourceKey<Level> key, final Carriage carriage,
                                   final Vec3 middle, final Vec3 axis, @Nullable final Approach previous, final long now) {
        final Vec3 wheel = wheelIn(carriage, key);
        final IPCreatePortalShafts.Frame frame = IPCreateTrainPortal.frameAt(level, middle);
        final Vec3 worldMiddle = frame == null ? middle : frame.toWorld(middle);
        final List<Portal> candidates = IPPortalLookup.touching(
            level, new AABB(worldMiddle, worldMiddle).inflate(SEARCH),
            p -> !(p instanceof Mirror) && p.getTeleportable()
                && IPCreatePortalTracks.trainsCross(p, frame == null ? 1.0 : frame.scale())
                && IPCreatePortalShafts.onOwnSide(p, frame)
        );
        Portal best = null;
        Vec3 bestOrigin = null;
        Vec3 bestNormal = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Portal candidate : candidates) {
            final IPCreateTrainPortal via = IPCreateTrainPortal.of(candidate, frame);
            if (via == null) {
                continue;
            }
            final Vec3 normal = via.normal();
            final double along = axis.dot(normal);
            if (Math.abs(along) < MIN_AXIS_DOT) {
                continue;
            }
            final double distance = via.distanceToPlane(middle);
            if (Math.abs(distance) > SEARCH) {
                continue;
            }
            final Vec3 hit = middle.subtract(axis.scale(distance / along));
            if (via.distanceToOpening(hit) > 1.0) {
                continue;
            }
            final Vec3 trackNormal;
            if (wheel != null) {
                final double side = via.distanceToPlane(wheel);
                if (side == 0.0) {
                    continue;
                }
                trackNormal = side > 0.0 ? normal : normal.scale(-1.0);
            } else if (previous != null && previous.portal() == candidate) {
                trackNormal = previous.trackNormal();
            } else {
                continue;
            }
            if (!hasPortalTrack(level, hit, trackNormal)) {
                continue;
            }
            if (Math.abs(distance) < bestDistance) {
                best = candidate;
                bestOrigin = via.origin();
                bestNormal = trackNormal;
                bestDistance = Math.abs(distance);
            }
        }
        return new Approach(best, bestOrigin != null ? bestOrigin : Vec3.ZERO, bestNormal != null ? bestNormal : Vec3.ZERO, now, wheel != null);
    }

    /** Whether a Create portal track lies in front of the portal at {@code hit} (the carriage axis at anchor height). */
    private static boolean hasPortalTrack(final Level level, final Vec3 hit, final Vec3 trackNormal) {
        final BlockPos base = BlockPos.containing(hit.add(trackNormal.scale(0.75)));
        for (int dy = -2; dy <= 0; dy++) {
            final BlockState state = level.getBlockState(base.offset(0, dy, 0));
            if (state.getBlock() instanceof TrackBlock && state.getValue(TrackBlock.SHAPE).isPortal()) {
                return true;
            }
        }
        return false;
    }

    /** @return the position of a wheel point of the carriage under this key (a dimension or a portal side), or null */
    @Nullable
    private static Vec3 wheelIn(final Carriage carriage, final ResourceKey<Level> dimension) {
        for (final CarriageBogey bogey : carriage.bogeys) {
            if (bogey == null) {
                continue;
            }
            for (final TravellingPoint point : new TravellingPoint[]{bogey.leading(), bogey.trailing()}) {
                if (point.node1 != null && point.edge != null && dimension.equals(IPCreateTrainSides.pointKey(carriage, point))) {
                    return point.getPosition(carriage.train.graph);
                }
            }
        }
        return null;
    }

    /** @return whether a wheel point of the carriage is on track in this dimension (what {@link #wheelIn} looks for) */
    private static boolean hasWheelIn(final Carriage carriage, final ResourceKey<Level> dimension) {
        for (final CarriageBogey bogey : carriage.bogeys) {
            if (bogey == null) {
                continue;
            }
            for (final TravellingPoint point : new TravellingPoint[]{bogey.leading(), bogey.trailing()}) {
                if (point.node1 != null && point.edge != null && dimension.equals(IPCreateTrainSides.pointKey(carriage, point))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isFinite(final Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /**
     * @return whether a wheel point of the carriage is in this dimension, or on this side of a portal within one
     * dimension (as Create's manageEntities counts it, keys of {@link IPCreateTrainSides})
     */
    public static boolean hasPointIn(final Carriage carriage, final ResourceKey<Level> dimension) {
        for (final CarriageBogey bogey : carriage.bogeys) {
            if (bogey == null) {
                continue;
            }
            for (final TravellingPoint point : new TravellingPoint[]{bogey.leading(), bogey.trailing()}) {
                if (point.node1 == null || dimension.equals(IPCreateTrainSides.pointKey(carriage, point))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Why the last {@link #of} on this thread returned null (diagnostics). */
    private static final ThreadLocal<String> LAST_FAILURE = new ThreadLocal<>();

    @Nullable
    private static Crossing fail(final String reason) {
        LAST_FAILURE.set(reason);
        return null;
    }

    /** @return why the last {@link #of} on this thread returned null */
    public static String lastFailure() {
        final String reason = LAST_FAILURE.get();
        return reason != null ? reason : "?";
    }

    /**
     * The IP portal at a copy's portal-track node leading to the other copy's node: into the other dimension, or
     * within this one to the pair's other face (within {@link #MAX_GAP} of where the portal maps this node).
     */
    @Nullable
    private static IPCreateTrainPortal portalAt(final Level level, final Carriage.DimensionalCarriageEntity dce,
                                                final TrackNodeLocation node, final ResourceKey<Level> otherKey,
                                                final TrackNodeLocation otherNode) {
        final Vec3 location = node.getLocation();
        final IPCreatePortalShafts.Frame frame = IPCreateTrainPortal.frameAt(level, location);
        final CachedPortal cached = PORTALS.get(dce);
        if (cached != null && cached.node().equals(node) && cached.otherKey().equals(otherKey)
            && !cached.portal().isRemoved() && cached.portal().level() == level) {
            return IPCreateTrainPortal.of(cached.portal(), frame);
        }
        final ResourceKey<Level> otherDim = IPCreateTrainSides.realDimension(otherKey);
        final boolean within = otherDim.equals(level.dimension());
        final Vec3 worldLocation = frame == null ? location : frame.toWorld(location);
        final Vec3 otherLocation = otherNode.getLocation();
        final List<Portal> candidates = IPPortalLookup.touching(
            level, new AABB(worldLocation, worldLocation).inflate(1.5),
            p -> !(p instanceof Mirror) && p.getTeleportable() && otherDim.equals(p.getDestDim())
                && IPCreatePortalShafts.onOwnSide(p, frame)
                && IPCreatePortalTracks.trainsCross(p, frame == null ? 1.0 : frame.scale())
        );
        IPCreateTrainPortal best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Portal candidate : candidates) {
            final IPCreateTrainPortal via = IPCreateTrainPortal.of(candidate, frame);
            if (via == null || (within && via.through(location).distanceToSqr(otherLocation) >= MAX_GAP * MAX_GAP)) {
                continue;
            }
            // the node sits on the track block's face, half a block before the plane, on the portal's bottom edge
            final double distance = candidate.getDistanceToNearestPointInPortal(worldLocation);
            if (distance < 1.5 && distance < bestDistance) {
                best = via;
                bestDistance = distance;
            }
        }
        if (best != null) {
            PORTALS.put(dce, new CachedPortal(node, otherKey, best.portal()));
        } else {
            PORTALS.remove(dce);
        }
        return best;
    }
}
