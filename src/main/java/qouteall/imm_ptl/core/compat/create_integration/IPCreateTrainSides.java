/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_create.IPCarriageAccessor;

/**
 * Carriages through a portal within one dimension. Create keeps one copy of a carriage per dimension
 * ({@code Carriage.entities}, keyed by the dimension of each wheel point's {@code node1}) and cuts each at its portal
 * node; at a portal back into the same dimension both copies would share one key. While a carriage spans a portal edge
 * of ours ({@link IPCreatePortalEdges}), each side of the edge gets a dimension key of its own (a side key: the portal
 * face's node and the real dimension, {@link #sideKey}), and Create's per-dimension code runs on those keys:
 * <ul>
 *     <li>the wheel points behind the edge (graph order, rear to front) take the rear face's key, those past it the
 *     front face's; Create's lookups of a wheel point's dimension inside {@code Carriage} return it
 *     ({@link #located}), as do the bogey's ({@link #bogeyDimension});</li>
 *     <li>a side key resolves to its real level ({@link #realDimension}) where Create asks the server for one, and to its
 *     face as the copy's portal node ({@link #face});</li>
 *     <li>as the carriage starts to span the edge, its one record moves from the real dimension to the side its copy
 *     stands on; when it stops, the record of the side its wheels are on moves back ({@link #prepare});</li>
 *     <li>each copy knows its key: the record it is in, else the key the server gave it ({@link #recordOf}).</li>
 * </ul>
 * Server and client each work this out from their own carriage; the client's runs a few ticks behind. Idea after Create
 * Teleporters' same-dimension trains (no code taken: it has no licence).
 */
public final class IPCreateTrainSides {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPTrainSides] ";
    private static final String NAMESPACE = "ip_aeronautics";
    private static final String PREFIX = "train_side/";
    /** Graph distance searched past a carriage's (or gap's) length for its portal edge. */
    private static final double SEARCH_MARGIN = 20.0;
    /** Below Create's derailing stress ({@code Train.tick}: above 4). */
    private static final double MAX_MENDING_STRESS = 3.5;

    private IPCreateTrainSides() {
    }

    /** A carriage spanning a same-dimension portal edge: the faces behind and past it, in graph order rear to front. */
    public record Crossing(TrackNode rear, TrackNode front, ResourceKey<Level> rearKey, ResourceKey<Level> frontKey,
                           ResourceKey<Level> dimension) {
        boolean sameEdge(@Nullable final Crossing other) {
            return other != null && other.rear == rear && other.front == front;
        }
    }

    /** Per carriage (server and client have their own carriages). */
    public static final class State {
        /** Whether {@link #prepare} has looked at the carriage with a graph since it was loaded or created. */
        boolean prepared;
        /** Client: two copies given one key was logged. */
        boolean conflictLogged;
        @Nullable
        Crossing crossing;
        /** Each wheel point's {@code node1} while crossing: its side key. */
        final Map<TrackNode, ResourceKey<Level>> sides = new IdentityHashMap<>();
        /** The locations handed to Create for those nodes (their dimension the side key). */
        final Map<TrackNode, TrackNodeLocation> scoped = new IdentityHashMap<>();
    }

    // ---- side keys ----------------------------------------------------------------------------------------------

    public static ResourceKey<Level> sideKey(final TrackNodeLocation face) {
        final ResourceLocation dimension = face.dimension.location();
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(NAMESPACE,
            PREFIX + face.getX() + "/" + face.getY() + "/" + face.getZ() + "/" + face.yOffsetPixels + "/"
                + dimension.getNamespace() + "/" + dimension.getPath()));
    }

    public static boolean isSide(@Nullable final ResourceKey<Level> key) {
        return key != null && key.location().getNamespace().equals(NAMESPACE) && key.location().getPath().startsWith(PREFIX);
    }

    /** The real dimension of a side key; any other key as it is. */
    public static ResourceKey<Level> realDimension(final ResourceKey<Level> key) {
        if (!isSide(key)) {
            return key;
        }
        final String[] parts = parts(key);
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(parts[4], parts[5]));
    }

    /** The portal face a side key stands for (its real dimension set). */
    public static TrackNodeLocation face(final ResourceKey<Level> key) {
        final String[] parts = parts(key);
        final net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("X", Integer.parseInt(parts[0]));
        tag.putInt("Y", Integer.parseInt(parts[1]));
        tag.putInt("Z", Integer.parseInt(parts[2]));
        tag.putInt("YO", Integer.parseInt(parts[3]));
        final TrackNodeLocation face = TrackNodeLocation.read(tag, null);
        face.dimension = realDimension(key);
        return face;
    }

    /** The side key of the portal-edge face in {@code dimension} nearest {@code at}, within {@code within}; else null. */
    @Nullable
    public static ResourceKey<Level> faceKeyNear(final ResourceKey<Level> dimension, final Vec3 at, final double within) {
        TrackNodeLocation best = null;
        double bestDistance = within * within;
        for (final TrackNodeLocation face : IPCreatePortalEdges.faces()) {
            final double distance = face.getLocation().distanceToSqr(at);
            if (dimension.equals(face.dimension) && distance < bestDistance) {
                best = face;
                bestDistance = distance;
            }
        }
        return best == null ? null : sideKey(best);
    }

    private static String[] parts(final ResourceKey<Level> key) {
        return key.location().getPath().substring(PREFIX.length()).split("/", 6);
    }

    // ---- state --------------------------------------------------------------------------------------------------

    public static State state(final Carriage carriage) {
        return ((IPTrainSidesHolder) carriage).ip_trainSides();
    }

    /** Whether the carriage spans a same-dimension portal edge (as of its last {@link #prepare}). */
    public static boolean isCrossing(@Nullable final Carriage carriage) {
        return carriage != null && state(carriage).crossing != null;
    }

    @Nullable
    public static Crossing crossingOf(@Nullable final Carriage carriage) {
        return carriage == null ? null : state(carriage).crossing;
    }

    /**
     * {@code Carriage.updateContraptionAnchors}, head (server and client, after the wheel points moved): works out the
     * carriage's crossing and each wheel point's side, and moves records between keys as a crossing starts or ends.
     */
    public static void prepare(final Carriage carriage) {
        final State state = state(carriage);
        final Crossing previous = state.crossing;
        if (previous == null && !IPCreatePortalEdges.any()) {
            return;
        }
        if (carriage.train == null || carriage.train.graph == null) {
            // no graph to find the crossing on (a train off the graph, or a client whose train has none yet): as it was
            return;
        }
        state.prepared = true;
        final List<TravellingPoint> points = points(carriage);
        final Map<TravellingPoint, Boolean> past = new IdentityHashMap<>();
        final Crossing found = findCrossing(carriage, points, past);
        state.sides.clear();
        state.scoped.clear();
        if (found != null) {
            for (final TravellingPoint point : points) {
                state.sides.put(point.node1, Boolean.TRUE.equals(past.get(point)) ? found.frontKey() : found.rearKey());
            }
        }
        if (found == null ? previous != null : !found.sameEdge(previous)) {
            if (previous != null) {
                end(carriage, previous);
            }
            state.crossing = found;
            if (found != null) {
                start(carriage, found);
            }
        }
        state.crossing = found;
        followServerKeys(carriage, found);
    }

    /**
     * {@code Carriage.manageEntities}, head: whether Create may manage the carriage's copies now. Create discards the
     * records of keys no wheel point holds, and runs this without placing the carriage first while its train has no
     * graph, before anything could work out a crossing: side records (a carriage saved standing across a portal of
     * ours) are then kept until it can.
     */
    public static boolean canManageEntities(final Carriage carriage) {
        if (carriage.train != null && carriage.train.graph != null) {
            return true;
        }
        for (final ResourceKey<Level> key : entities(carriage).keySet()) {
            if (isSide(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Server: works out the carriage's crossing if nothing has since it was loaded. Create moves a train's wheel points
     * by their stress ({@code Carriage.travel}: {@code CarriageBogey.getStress}, {@code getAnchorDiff}) before it places
     * the carriage ({@code updateContraptionAnchors}, where {@link #prepare} runs): a carriage saved standing across a
     * portal of ours was first moved with no crossing known, its bogey's wheel points a straight line through the world
     * apart, which pulled them along the track every tick (a train left in a portal slid on its own after
     * a reload, its bogey torn across the portal).
     */
    static void ensurePrepared(final Carriage carriage) {
        if (!state(carriage).prepared) {
            prepare(carriage);
        }
    }

    /**
     * {@code CarriageBogey.getStress} for a bogey whose wheel points are on both sides of a portal edge of ours: its
     * wheel spacing minus their distance along the track through the portal. Create gives no stress for a bogey across a
     * portal (its dimension is null), so nothing kept the two points together there, and wheel points pulled apart
     * before (in a world saved by an older version) stayed apart, or had passed each other (the leading point behind the
     * trailing one: the distance counts negative and they are moved back past each other). Kept below Create's
     * derailing stress (4), so such a bogey is mended over a few ticks. NaN where Create's own stress applies.
     */
    public static double splitBogeyStress(final CarriageBogey bogey, final double wheelSpacing) {
        final Carriage carriage = bogey.carriage;
        if (carriage == null || carriage.train == null || carriage.train.graph == null || carriage.train.derailed) {
            return Double.NaN;
        }
        ensurePrepared(carriage);
        if (state(carriage).crossing == null) {
            return Double.NaN;
        }
        final ResourceKey<Level> leading = pointKey(carriage, bogey.leading());
        final ResourceKey<Level> trailing = pointKey(carriage, bogey.trailing());
        if (leading == null || leading.equals(trailing)) {
            return Double.NaN;
        }
        final double limit = wheelSpacing + 2 * SEARCH_MARGIN;
        double distance = trackDistance(carriage.train.graph, bogey.trailing(), bogey.leading(), limit);
        if (Double.isNaN(distance)) {
            distance = -trackDistance(carriage.train.graph, bogey.leading(), bogey.trailing(), limit);
        }
        return Double.isNaN(distance) ? 0.0 : Math.min(wheelSpacing - distance, MAX_MENDING_STRESS);
    }

    /** The distance along the track from {@code from} forward to {@code to}, NaN if not within {@code limit}. */
    private static double trackDistance(final TrackGraph graph, final TravellingPoint from, final TravellingPoint to,
                                        final double limit) {
        if (from.edge == null || to.edge == null || from.node1 == null || to.node1 == null) {
            return Double.NaN;
        }
        final List<Step> path = path(graph, from, to, limit);
        return path == null ? Double.NaN : path.getLast().start() + to.position;
    }

    /** The carriage's wheel points, front to rear. */
    private static List<TravellingPoint> points(final Carriage carriage) {
        final List<TravellingPoint> points = new ArrayList<>(4);
        for (final CarriageBogey bogey : carriage.bogeys) {
            if (bogey != null) {
                points.add(bogey.leading());
                points.add(bogey.trailing());
            }
        }
        return points;
    }

    /**
     * The same-dimension portal edge on the track between the carriage's rear and front wheel points, if it is the only
     * portal edge there; {@code past} gets the points lying past it.
     */
    @Nullable
    private static Crossing findCrossing(final Carriage carriage, final List<TravellingPoint> points,
                                         final Map<TravellingPoint, Boolean> past) {
        final TrackGraph graph = carriage.train.graph;
        if (graph == null) {
            return null;
        }
        for (final TravellingPoint point : points) {
            if (point.edge == null || point.node1 == null || point.node2 == null) {
                return null;
            }
        }
        final TravellingPoint front = carriage.getLeadingPoint();
        final TravellingPoint rear = carriage.getTrailingPoint();
        final double reach = carriage.bogeySpacing + SEARCH_MARGIN;
        if (!nearFace(graph, front, reach) && !nearFace(graph, rear, reach)) {
            return null;
        }
        List<Step> path = path(graph, rear, front, reach);
        if (path == null) {
            // the points' edges run the other way (never seen; Create keeps one orientation per train)
            path = path(graph, front, rear, reach);
        }
        if (path == null) {
            return null;
        }
        int portalIndex = -1;
        for (int i = 0; i < path.size(); i++) {
            final TrackEdge edge = path.get(i).edge;
            if (!edge.isInterDimensional()) {
                continue;
            }
            if (portalIndex >= 0 || !IPCreatePortalEdges.isLink(edge.node1.getLocation(), edge.node2.getLocation())) {
                // another portal on the way (Create's own, or a second one): leave it to Create
                return null;
            }
            portalIndex = i;
        }
        if (portalIndex < 0) {
            return null;
        }
        final Step portal = path.get(portalIndex);
        for (final TravellingPoint point : points) {
            int index = -1;
            for (int i = 0; i < path.size(); i++) {
                if (path.get(i).from == point.node1 && path.get(i).to == point.node2) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                // off the path between the ends (cannot happen on one track): by the nearer face
                final Vec3 at = point.getPosition(graph);
                index = at.distanceToSqr(portal.to.getLocation().getLocation()) < at.distanceToSqr(portal.from.getLocation().getLocation())
                    ? portalIndex + 1 : portalIndex;
            }
            // a point on the portal edge itself is behind it (Create counts a point by its node1)
            past.put(point, index > portalIndex);
        }
        final TrackNodeLocation rearFace = portal.from.getLocation();
        final TrackNodeLocation frontFace = portal.to.getLocation();
        return new Crossing(portal.from, portal.to, sideKey(rearFace), sideKey(frontFace), rearFace.dimension);
    }

    private static boolean nearFace(final TrackGraph graph, final TravellingPoint point, final double reach) {
        final Vec3 at = point.getPosition(graph);
        for (final TrackNodeLocation face : IPCreatePortalEdges.faces()) {
            if (face.getLocation().distanceToSqr(at) < reach * reach) {
                return true;
            }
        }
        return false;
    }

    private record Step(TrackNode from, TrackNode to, TrackEdge edge, double start, @Nullable Step parent) {
    }

    /**
     * The edges from {@code from}'s edge to {@code to}'s, following each edge's direction (node1 to node2, a train's
     * forward), shortest first; null if {@code to} is not within {@code limit} along the track.
     */
    @Nullable
    private static List<Step> path(final TrackGraph graph, final TravellingPoint from, final TravellingPoint to, final double limit) {
        final PriorityQueue<Step> queue = new PriorityQueue<>(Comparator.comparingDouble(Step::start));
        final Set<TrackEdge> visited = new HashSet<>();
        queue.add(new Step(from.node1, from.node2, from.edge, -from.position, null));
        while (!queue.isEmpty()) {
            final Step step = queue.poll();
            if (step.start() > limit || !visited.add(step.edge())) {
                continue;
            }
            if (step.from() == to.node1 && step.to() == to.node2 && step.start() + to.position >= -1.0E-5) {
                final List<Step> path = new ArrayList<>();
                for (Step s = step; s != null; s = s.parent()) {
                    path.add(0, s);
                }
                return path;
            }
            final double end = step.start() + step.edge().getLength();
            if (end > limit) {
                continue;
            }
            final Map<TrackNode, TrackEdge> next = graph.getConnectionsFrom(step.to());
            if (next == null) {
                continue;
            }
            for (final Map.Entry<TrackNode, TrackEdge> entry : next.entrySet()) {
                if (entry.getKey() != step.from() && step.edge().canTravelTo(entry.getValue())) {
                    queue.add(new Step(step.to(), entry.getKey(), entry.getValue(), end, step));
                }
            }
        }
        return null;
    }

    /** The carriage starts to span the edge: its record in the real dimension moves to the side its copy stands on. */
    private static void start(final Carriage carriage, final Crossing crossing) {
        final Map<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entities = entities(carriage);
        dropEmptySides(entities, crossing);
        final Carriage.DimensionalCarriageEntity real = entities.get(crossing.dimension());
        if (real == null) {
            return;
        }
        // the client takes the side the server gave the copy (its carriage runs ahead, so it has usually started)
        final ResourceKey<Level> given = serverKey(real, crossing.dimension());
        final ResourceKey<Level> key = given != null && (given.equals(crossing.frontKey()) || given.equals(crossing.rearKey()))
            ? given
            : real.positionAnchor != null
            && real.positionAnchor.distanceToSqr(crossing.front().getLocation().getLocation())
            < real.positionAnchor.distanceToSqr(crossing.rear().getLocation().getLocation())
            ? crossing.frontKey() : crossing.rearKey();
        final Carriage.DimensionalCarriageEntity there = entities.get(key);
        if (there != null && there != real && live(there)) {
            LOGGER.warn(TAG + "carriage {} starts crossing at {}, but its side {} already has a copy; leaving its record",
                carriage.id, crossing.rear().getLocation().getLocation(), key.location());
            return;
        }
        entities.remove(crossing.dimension());
        entities.put(key, real);
        setSide(real, key);
        LOGGER.info(TAG + "carriage {} starts crossing the portal at {} within {}; its copy is on side {} ({})",
            carriage.id, crossing.rear().getLocation().getLocation(), crossing.dimension().location(), key.location(),
            describeRecords(carriage));
    }

    /** The carriage no longer spans the edge: the record on the side its wheels are on moves back to the real dimension. */
    private static void end(final Carriage carriage, final Crossing crossing) {
        final Map<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entities = entities(carriage);
        final Carriage.DimensionalCarriageEntity real = entities.get(crossing.dimension());
        if (real != null && live(real)) {
            return;
        }
        final TrackGraph graph = carriage.train != null ? carriage.train.graph : null;
        final TravellingPoint front = carriage.getLeadingPoint();
        final Vec3 at = graph != null && front.edge != null ? front.getPosition(graph) : null;
        ResourceKey<Level> best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entry : entities.entrySet()) {
            final Carriage.DimensionalCarriageEntity dce = entry.getValue();
            if (!isSide(entry.getKey()) || dce == null || !realDimension(entry.getKey()).equals(crossing.dimension())) {
                continue;
            }
            final double distance = at == null || dce.positionAnchor == null ? Double.MAX_VALUE / 2
                : dce.positionAnchor.distanceToSqr(at);
            // the copy the server already carries on with (client) wins, then a record with a live copy over one
            // without: the server removes the other copy a few ticks later
            final double rank = crossing.dimension().equals(serverKey(dce, crossing.dimension())) ? -1.0
                : live(dce) ? distance : Double.MAX_VALUE / 2 + Math.min(distance, Double.MAX_VALUE / 4);
            if (best == null || rank < bestDistance) {
                best = entry.getKey();
                bestDistance = rank;
            }
        }
        if (best == null) {
            return;
        }
        final Carriage.DimensionalCarriageEntity survivor = entities.remove(best);
        entities.put(crossing.dimension(), survivor);
        setSide(survivor, crossing.dimension());
        LOGGER.info(TAG + "carriage {} has crossed the portal at {} within {}; the copy of side {} carries on ({})",
            carriage.id, crossing.rear().getLocation().getLocation(), crossing.dimension().location(), best.location(),
            describeRecords(carriage));
    }

    /** Client: records of other crossings' sides with no copy (the server discards them on its side). */
    private static void dropEmptySides(final Map<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entities,
                                       final Crossing crossing) {
        final Iterator<Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity>> iterator = entities.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entry = iterator.next();
            final CarriageContraptionEntity copy = entry.getValue() == null ? null : entry.getValue().entity.get();
            if (isSide(entry.getKey()) && !entry.getKey().equals(crossing.rearKey()) && !entry.getKey().equals(crossing.frontKey())
                && copy != null && copy.level().isClientSide() && copy.isRemoved()) {
                iterator.remove();
            }
        }
    }

    /**
     * Client: the key the server gave a record's live copy (its synced side, else the real dimension); null on the
     * server, without a live copy, or while the copy's side is still unknown.
     */
    @Nullable
    private static ResourceKey<Level> serverKey(final Carriage.DimensionalCarriageEntity dce, final ResourceKey<Level> dimension) {
        final CarriageContraptionEntity copy = dce.entity.get();
        if (copy == null || copy.isRemoved() || !copy.level().isClientSide()) {
            return null;
        }
        final ResourceKey<Level> given = givenKey(copy);
        return given != null ? given : dimension;
    }

    /**
     * Client: puts each live copy's record under the key the server gave the copy (its synced side, or none for the
     * real dimension) once that key fits this client's own state: a side of its crossing while it crosses, the real
     * dimension while it does not (the server runs a tick or two ahead; a key that does not fit yet waits). This
     * client's own choices at a crossing's start and end ({@link #start}, {@link #end}) only stand in until then: they
     * picked the wrong copy at times (at the end and at the start: a copy drawn for a frame where
     * the other one belongs).
     */
    private static void followServerKeys(final Carriage carriage, @Nullable final Crossing crossing) {
        final Map<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entities = entities(carriage);
        List<Carriage.DimensionalCarriageEntity> moving = null;
        List<ResourceKey<Level>> targets = null;
        for (final Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entry : entities.entrySet()) {
            final ResourceKey<Level> target = entry.getValue() == null ? null : serverKey(entry.getValue(), realDimension(entry.getKey()));
            if (target == null || target.equals(entry.getKey())
                || (crossing == null ? isSide(target) : !target.equals(crossing.rearKey()) && !target.equals(crossing.frontKey()))) {
                continue;
            }
            if (moving == null) {
                moving = new ArrayList<>(2);
                targets = new ArrayList<>(2);
            }
            moving.add(entry.getValue());
            targets.add(target);
        }
        if (moving == null) {
            return;
        }
        for (int i = 0; i < moving.size(); i++) {
            final Carriage.DimensionalCarriageEntity dce = moving.get(i);
            final ResourceKey<Level> target = targets.get(i);
            final ResourceKey<Level> current = keyOfRecord(carriage, dce);
            final Carriage.DimensionalCarriageEntity there = entities.get(target);
            if (current == null || there == dce) {
                continue;
            }
            if (there != null && target.equals(serverKey(there, realDimension(target)))) {
                // two copies the server gave the same key: one of them is stale
                if (!state(carriage).conflictLogged) {
                    state(carriage).conflictLogged = true;
                    LOGGER.warn(TAG + "client: carriage {}: two copies on {} ({})", carriage.id, target.location(),
                        describeRecords(carriage));
                }
                continue;
            }
            // the record there, if any, takes this one's old key (the two copies swapped)
            entities.remove(current);
            if (there != null) {
                entities.put(current, there);
            }
            entities.put(target, dce);
            final CarriageContraptionEntity copy = dce.entity.get();
            LOGGER.info(TAG + "client: carriage {}: copy {} moved from {} to {}, as the server has it",
                carriage.id, copy == null ? "?" : copy.getId(), current.location(), target.location());
        }
    }

    /** Every record of a carriage: key, copy (and the key the server gave it), anchor and the copy's position. */
    static String describeRecords(final Carriage carriage) {
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entry : entities(carriage).entrySet()) {
            final Carriage.DimensionalCarriageEntity dce = entry.getValue();
            final CarriageContraptionEntity copy = dce == null ? null : dce.entity.get();
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(isSide(entry.getKey()) ? "side " + face(entry.getKey()).getLocation() : entry.getKey().location())
                .append(": copy ").append(copy == null ? "none" : copy.getId() + (copy.isRemoved() ? " (removed)" : "")
                    + " given '" + ((IPCarriageSideHolder) copy).ip_getTrainSide() + "' at " + copy.position())
                .append(" anchor ").append(dce == null ? null : dce.positionAnchor);
        }
        return text.toString();
    }

    private static boolean live(final Carriage.DimensionalCarriageEntity dce) {
        final CarriageContraptionEntity copy = dce.entity.get();
        return copy != null && !copy.isRemoved();
    }

    private static Map<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entities(final Carriage carriage) {
        return ((IPCarriageAccessor) carriage).ip_getEntities();
    }

    // ---- lookups for Create's code --------------------------------------------------------------------------------

    /**
     * {@code TrackNode.getLocation()} of a wheel point's node inside {@code Carriage} (only its dimension is read): with
     * the point's side key while the carriage crosses.
     */
    public static TrackNodeLocation located(final Carriage carriage, final TrackNode node, final TrackNodeLocation actual) {
        ensurePrepared(carriage);
        final State state = state(carriage);
        if (state.crossing == null) {
            return actual;
        }
        final ResourceKey<Level> side = state.sides.get(node);
        if (side == null) {
            return actual;
        }
        return state.scoped.computeIfAbsent(node, n -> {
            final TrackNodeLocation scoped = TrackNodeLocation.read(actual.write(null), null);
            scoped.dimension = side;
            return scoped;
        });
    }

    /** The key of a wheel point: its side's while the carriage crosses, else its node's dimension. */
    @Nullable
    public static ResourceKey<Level> pointKey(final Carriage carriage, final TravellingPoint point) {
        if (point.node1 == null) {
            return null;
        }
        final State state = state(carriage);
        final ResourceKey<Level> side = state.crossing == null ? null : state.sides.get(point.node1);
        return side != null ? side : point.node1.getLocation().dimension;
    }

    /** {@code CarriageBogey.getDimension}: none for a bogey whose wheels are on both sides of the edge. */
    @Nullable
    public static ResourceKey<Level> bogeyDimension(final CarriageBogey bogey, @Nullable final ResourceKey<Level> stock) {
        final Carriage carriage = bogey.carriage;
        if (stock == null || carriage == null || state(carriage).crossing == null) {
            return stock;
        }
        final ResourceKey<Level> leading = pointKey(carriage, bogey.leading());
        final ResourceKey<Level> trailing = pointKey(carriage, bogey.trailing());
        return leading != null && leading.equals(trailing) ? stock : null;
    }

    /** The bogey's dimension as Create's anchor code compares it with its wheel points': their side key. */
    @Nullable
    public static ResourceKey<Level> bogeySide(final CarriageBogey bogey, @Nullable final ResourceKey<Level> dimension) {
        final Carriage carriage = bogey.carriage;
        if (dimension == null || carriage == null || state(carriage).crossing == null) {
            return dimension;
        }
        return pointKey(carriage, bogey.leading());
    }

    /**
     * {@code Carriage.getDimensional(ResourceKey)} for a real dimension the carriage has no record of while it crosses
     * a portal within it: the record of the side with a live copy (callers outside the anchor code, which asks by
     * side). Null to let Create create one.
     */
    @Nullable
    public static Carriage.DimensionalCarriageEntity sideRecordFor(final Carriage carriage, final ResourceKey<Level> dimension) {
        final Crossing crossing = state(carriage).crossing;
        if (crossing == null || isSide(dimension) || !crossing.dimension().equals(dimension)) {
            return null;
        }
        final Map<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entities = entities(carriage);
        if (entities.containsKey(dimension)) {
            return null;
        }
        Carriage.DimensionalCarriageEntity fallback = null;
        for (final ResourceKey<Level> key : List.of(crossing.rearKey(), crossing.frontKey())) {
            final Carriage.DimensionalCarriageEntity dce = entities.get(key);
            if (dce != null && live(dce)) {
                return dce;
            }
            if (fallback == null) {
                fallback = dce;
            }
        }
        return fallback;
    }

    /**
     * The record of a carriage copy: the one holding it, else the one of the key the server gave it (created if
     * missing). Null for a copy with no side key and no record (Create then looks it up by its level).
     */
    @Nullable
    public static Carriage.DimensionalCarriageEntity recordOf(@Nullable final Carriage carriage, final CarriageContraptionEntity entity) {
        if (carriage == null) {
            return null;
        }
        for (final Carriage.DimensionalCarriageEntity dce : entities(carriage).values()) {
            if (dce != null && dce.entity.get() == entity) {
                return dce;
            }
        }
        final ResourceKey<Level> given = givenKey(entity);
        return given != null ? carriage.getDimensional(given) : null;
    }

    /**
     * For queries: the record of a carriage copy (as {@link #recordOf}, never creating one), else that of its level's
     * dimension; null if neither exists.
     */
    @Nullable
    public static Carriage.DimensionalCarriageEntity existingRecordOf(@Nullable final Carriage carriage, final CarriageContraptionEntity entity) {
        if (carriage == null) {
            return null;
        }
        final Map<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entities = entities(carriage);
        for (final Carriage.DimensionalCarriageEntity dce : entities.values()) {
            if (dce != null && dce.entity.get() == entity) {
                return dce;
            }
        }
        final ResourceKey<Level> given = givenKey(entity);
        return entities.get(given != null ? given : entity.level().dimension());
    }

    /** A copy's key: that of its record, else the one the server gave it, else its level's dimension. */
    public static ResourceKey<Level> keyOf(final CarriageContraptionEntity entity) {
        final Carriage carriage = entity.getCarriage();
        if (carriage != null) {
            for (final Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entry : entities(carriage).entrySet()) {
                if (entry.getValue() != null && entry.getValue().entity.get() == entity) {
                    return entry.getKey();
                }
            }
        }
        final ResourceKey<Level> given = givenKey(entity);
        return given != null ? given : entity.level().dimension();
    }

    /**
     * Client, {@code CarriageContraptionEntity.bindCarriage}: whether a copy not bound yet, with no side from the server
     * yet, must wait because another live copy holds the record of its level ({@code MixinCarriageContraptionEntity_TrainSides}).
     */
    public static boolean waitsForSide(final CarriageContraptionEntity entity, @Nullable final java.util.UUID trainId,
                                       final int carriageIndex) {
        if (entity.getCarriage() != null || trainId == null || !entity.level().isClientSide() || givenKey(entity) != null
            || !IPCreatePortalEdges.any()) {
            return false;
        }
        final Train train = com.simibubi.create.Create.RAILWAYS.sided(entity.level()).trains.get(trainId);
        if (train == null || carriageIndex < 0 || carriageIndex >= train.carriages.size()) {
            return false;
        }
        final Carriage carriage = train.carriages.get(carriageIndex);
        if (carriage == null) {
            return false;
        }
        final Carriage.DimensionalCarriageEntity there = entities(carriage).get(entity.level().dimension());
        final CarriageContraptionEntity holder = there == null ? null : there.entity.get();
        return holder != null && holder != entity && !holder.isRemoved();
    }

    /** Whether a copy stands for one side of a portal within its dimension. */
    public static boolean isSideCopy(@Nullable final CarriageContraptionEntity entity) {
        return entity != null && isSide(keyOf(entity));
    }

    @Nullable
    private static ResourceKey<Level> givenKey(final CarriageContraptionEntity entity) {
        final String side = ((IPCarriageSideHolder) entity).ip_getTrainSide();
        if (side == null || side.isEmpty()) {
            return null;
        }
        final ResourceLocation location = ResourceLocation.tryParse(side);
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    /** Server: tells a record's copy (and so the clients) its key. */
    public static void setSide(final Carriage.DimensionalCarriageEntity dce, final ResourceKey<Level> key) {
        final CarriageContraptionEntity copy = dce.entity.get();
        if (copy != null && !copy.level().isClientSide()) {
            ((IPCarriageSideHolder) copy).ip_setTrainSide(isSide(key) ? key.location().toString() : "");
        }
    }

    /** The key of the record about to create a copy (server, {@code createEntity}). */
    @Nullable
    public static ResourceKey<Level> keyOfRecord(final Carriage carriage, final Carriage.DimensionalCarriageEntity dce) {
        for (final Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entry : entities(carriage).entrySet()) {
            if (entry.getValue() == dce) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ---- between carriages ----------------------------------------------------------------------------------------

    /**
     * Whether the gap in front of carriage {@code index} of a train (between its front wheel point and the rear one of
     * the carriage ahead) spans a same-dimension portal edge.
     */
    public static boolean gapCrosses(final Train train, final int index) {
        if (!IPCreatePortalEdges.any() || index <= 0 || index >= train.carriages.size() || train.graph == null) {
            return false;
        }
        final TravellingPoint rear = train.carriages.get(index).getLeadingPoint();
        final TravellingPoint front = train.carriages.get(index - 1).getTrailingPoint();
        if (rear.edge == null || front.edge == null || rear.node1 == null || front.node1 == null) {
            return false;
        }
        final double reach = (index - 1 < train.carriageSpacing.size() ? train.carriageSpacing.get(index - 1) : 0) + SEARCH_MARGIN;
        if (!nearFace(train.graph, rear, reach) && !nearFace(train.graph, front, reach)) {
            return false;
        }
        List<Step> path = path(train.graph, rear, front, reach);
        if (path == null) {
            path = path(train.graph, front, rear, reach);
        }
        if (path == null) {
            return false;
        }
        for (final Step step : path) {
            if (step.edge().isInterDimensional()
                && IPCreatePortalEdges.isLink(step.edge().node1.getLocation(), step.edge().node2.getLocation())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether something of a train between carriages {@code index} and {@code index + 1} is on both sides of a
     * same-dimension portal: either carriage, or the gap between them (their coupling is not drawn).
     */
    public static boolean couplingSplit(final Train train, final int index) {
        return index >= 0 && index + 1 < train.carriages.size()
            && (isCrossing(train.carriages.get(index)) || isCrossing(train.carriages.get(index + 1)) || gapCrosses(train, index + 1));
    }
}
