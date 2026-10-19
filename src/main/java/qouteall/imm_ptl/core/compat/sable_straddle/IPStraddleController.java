/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The per-portal decisions follow IPSable 0.5.0's SableTransitController by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalAnchors;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffHooks;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableBridge;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipJoints;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipRopeCompat;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipRagdollGuard;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipThroughPortalSync;
import qouteall.imm_ptl.core.compat.sable_straddle.IPShipCrossingDetector.Direction;
import qouteall.imm_ptl.core.compat.sable_straddle.IPShipCrossingDetector.Phase;
import qouteall.imm_ptl.core.compat.sable_straddle.IPShipCrossingDetector.State;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs once per server tick, after every level ticked (the physics of this tick is done: {@code lastPose ->
 * logicalPose} is its segment). For every ship near a teleportable unscaled portal it decides, per portal face:
 * <ul>
 *     <li>STRADDLING (and entered through this face) or about to enter within one segment: open or keep the session;</li>
 *     <li>CROSSED, moving on through the face it entered: hand the ship off to the destination level, at exactly its
 *     pose mapped through the portal and with its velocity mapped too (today's whole-ship move, without arrival
 *     placement: the ship already sits in the destination world physically);</li>
 *     <li>otherwise (backed out, slid out sideways): close the session.</li>
 * </ul>
 * Sessions not confirmed in a tick (portal gone, ship gone or unloaded) are closed at its end.
 */
final class IPStraddleController {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** How far around a ship's swept bounds portals are considered. */
    private static final double PORTAL_QUERY_INFLATION = 4.0;
    /** Physics segments of lookahead for opening a session before first contact. */
    private static final double EARLY_OPEN_SEGMENTS = 1.0;
    /** Ships whose swept bounds are larger than this (in blocks^3) are left alone, as by the one-step crossing. */
    private static final double MAX_SUBLEVEL_VOLUME = 2000000.0;
    /** A session stays open while its ship is less than this far before the plane (opening needs 0.1). */
    private static final double KEEP_DISTANCE = 0.5;
    /** A session stays open at least this many ticks. */
    private static final long MIN_SESSION_TICKS = 10;
    /** After a hand-off failed while loading the ship in the destination, no new attempt for this many ticks. */
    private static final long FAILED_HANDOFF_RETRY_TICKS = 100;
    /** Speed [m/s] a ship whose hand-off was refused is given back towards the plane. */
    private static final double REFUSED_PUSH_BACK_SPEED = 1.0;
    /**
     * Growth of the portal's opening on each side when deciding whether a ship goes through it: none. A block centre in
     * the frame (a ship pressed against the obsidian, e.g. by the physics staff) must not count as entering; a ship
     * really going through has its block centres at least half a block inside the opening. The clip regions keep their
     * {@link IPStraddleSessions#APERTURE_MARGIN}.
     */
    static final double DETECTION_MARGIN = 0.0;
    /**
     * A ship block pushed this far [blocks] into the portal's frame was driven into it (a hard hit: the physics staff, a
     * fast impact); resting or sliding contact stays far below.
     */
    private static final double FRAME_PRESSED = 0.1;
    /** A ship block this close [blocks] to the portal's frame touches it. */
    private static final double FRAME_TOUCHING = 0.05;
    /**
     * A ship pressed into the frame has nothing really through the opening while no block reaches this far [blocks] past
     * the plane: the frame's own half before the plane is that deep, so a hit can push blocks no further.
     */
    private static final double FRAME_HELD_DEPTH = 0.5;
    /**
     * A ship whose blocks are this far [blocks] inside the portal's frame for {@value #FRAME_STUCK_TICKS} ticks is stuck
     * in it (a hard hit or a physics staff pull tunnelled a frame block into the hull: every contact then pushes another
     * way and nothing gets it out). It is let through those frame blocks until it is free of them.
     */
    private static final double FRAME_STUCK_DEPTH = 0.25;
    private static final long FRAME_STUCK_TICKS = 10;
    /** Ships deep in a frame, with the tick they got there. */
    private static final Map<UUID, Long> STUCK_SINCE = new HashMap<>();
    /** Ships let through frame blocks, with those blocks (as last sent). */
    private static final Map<UUID, Set<BlockPos>> LET_THROUGH = new HashMap<>();
    /** How far [blocks] around a doorway a rope partner counts as being at it ({@link #partnerPastFace}). */
    private static final double PARTNER_REACH = 32.0;
    /** Diagnostics: last tick a "declined" line was logged per ship and portal. */
    private static final Map<String, Long> DECLINED_LOGGED = new HashMap<>();
    /** How long [ticks] a ship wholly through a portal waits for the ships tied to it before crossing alone. */
    private static final long GROUP_WAIT_TICKS = 100;
    /** Extra clip depth [blocks] for a ship waiting for the ships tied to it. */
    private static final double WAITING_EXTRA_DEPTH = 16.0;
    /** Test switch: {@code -Dip_aeronautics.debug.refuseHandoff=true} refuses every straddle hand-off. */
    private static final boolean DEBUG_REFUSE_HANDOFF = Boolean.getBoolean("ip_aeronautics.debug.refuseHandoff");

    private record Handoff(ServerSubLevel ship, ServerLevel level, Portal portal) {
    }

    /** The last refused or failed hand-off of a ship: through which portal, why, and the first tick to try again. */
    private record Refusal(UUID portal, String reason, long retryTick) {
    }

    private static final Map<UUID, Refusal> REFUSALS = new HashMap<>();
    /** Ships wholly through a portal waiting for the ships tied to them, with the tick they started waiting. */
    private static final Map<UUID, Long> WAITING_SINCE = new HashMap<>();

    private IPStraddleController() {
    }

    /** A server starts: refusals hold its tick counts. */
    static void reset() {
        REFUSALS.clear();
        DECLINED_LOGGED.clear();
        WAITING_SINCE.clear();
        STUCK_SINCE.clear();
        LET_THROUGH.clear();
    }

    static void tick(final MinecraftServer server, final long tick) {
        final Set<IPStraddleSessions.Key> seen = new HashSet<>();
        final Set<UUID> live = new HashSet<>();
        final List<Handoff> handoffs = new ArrayList<>();

        // every ship's segment first: a ship is judged against a portal mounted on another ship relative to that
        // carrier's segment of the same tick (IPShipCrossingDetector)
        for (final ServerLevel level : server.getAllLevels()) {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null) {
                for (final ServerSubLevel ship : container.getAllSubLevels()) {
                    if (!ship.isRemoved()) {
                        IPShipCrossingDetector.capture(ship);
                    }
                }
            }
        }
        for (final ServerLevel level : server.getAllLevels()) {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            for (final ServerSubLevel ship : List.copyOf(container.getAllSubLevels())) {
                if (ship.isRemoved()) {
                    continue;
                }
                live.add(ship.getUniqueId());
                try {
                    considerShip(level, ship, tick, seen, handoffs);
                } catch (final Throwable e) {
                    LOGGER.warn("[IPStraddle] ship {} failed", ship.getUniqueId(), e);
                }
            }
        }

        for (final IPStraddleSessions.Key key : IPStraddleSessions.keys()) {
            if (!seen.contains(key)) {
                IPStraddleSessions.close(key, live.contains(key.ship()) ? "away from the portal" : "ship gone");
            }
        }
        IPShipCrossingDetector.retain(live);
        REFUSALS.keySet().retainAll(live);
        STUCK_SINCE.keySet().retainAll(live);
        LET_THROUGH.keySet().retainAll(live);
        IPStraddleSessions.retainGhosts(live);
        IPPortalRims.retainLetThrough(live);
        IPPortalRims.tick(tick);

        handOffAll(server, handoffs, tick);
    }

    /**
     * Hands off the ships wholly through a portal this tick. Ships tied together by ropes or joints ({@link
     * #crossingGroup}) cross as a group, in the same tick, with their ropes; their joints' owners rebuild the joints in
     * the destination. A ship whose group is not all through the same portal yet waits in its session (physically in
     * the destination already) for up to {@value #GROUP_WAIT_TICKS} ticks, then crosses alone, its ropes to the others
     * broken as before.
     */
    private static void handOffAll(final MinecraftServer server, final List<Handoff> handoffs, final long tick) {
        final Map<UUID, Handoff> ready = new HashMap<>();
        for (final Handoff handoff : handoffs) {
            ready.put(handoff.ship().getUniqueId(), handoff);
        }
        WAITING_SINCE.keySet().retainAll(ready.keySet());
        final Set<UUID> done = new HashSet<>();
        for (final Handoff handoff : handoffs) {
            final UUID shipId = handoff.ship().getUniqueId();
            if (done.contains(shipId)) {
                continue;
            }
            Set<UUID> group = crossingGroup(handoff.level(), handoff.ship());
            Map<UUID, Handoff> members = group.size() > 1 ? groupHandoffs(group, handoff, ready, tick) : Map.of(shipId, handoff);
            if (members == null) {
                final long since = WAITING_SINCE.computeIfAbsent(shipId, id -> tick);
                if (tick - since < GROUP_WAIT_TICKS) {
                    if (since == tick) {
                        LOGGER.info("[IPStraddle] ship {} is through portal {}; waiting for the {} other ship(s) tied to it",
                                shipId, handoff.portal().getUUID(), group.size() - 1);
                    }
                    waitForGroup(handoff, tick);
                    done.add(shipId);
                    continue;
                }
                LOGGER.info("[IPStraddle] ship {} waited {} ticks for the ships tied to it; crossing alone (its ropes to them break, its joints to them come apart)",
                        shipId, tick - since);
                group = Set.of(shipId);
                members = Map.of(shipId, handoff);
            }
            final Map<UUID, BlockPos> shifts = new HashMap<>();
            ServerLevel dest = null;
            for (final UUID member : group) {
                final Handoff memberHandoff = members.get(member);
                done.add(member);
                WAITING_SINCE.remove(member);
                try {
                    final IPSableBridge.Moved moved = handOff(server, memberHandoff, group);
                    if (moved != null) {
                        shifts.put(member, moved.shift());
                        dest = moved.sub().getLevel();
                    }
                } catch (final Throwable e) {
                    LOGGER.error("[IPStraddle] hand-off of ship {} failed", member, e);
                }
            }
            if (group.size() > 1 && dest != null) {
                IPShipRopeCompat.shiftLoadedAnchors(dest, shifts);
                IPShipJoints.shiftLoadedJoints(dest, shifts);
                LOGGER.info("[IPStraddle] {} ships tied together crossed as one group", shifts.size());
            }
        }
    }

    /**
     * @return the ships of {@code level} that cross a portal together with {@code ship}: tied to it by ropes or by
     * joints between ships (swivel bearings, docking connectors), directly or through other ships, including {@code ship}
     */
    static Set<UUID> crossingGroup(final ServerLevel level, final ServerSubLevel ship) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        final Set<UUID> group = new HashSet<>();
        final Set<UUID> roped = new HashSet<>();
        final Deque<ServerSubLevel> queue = new ArrayDeque<>();
        group.add(ship.getUniqueId());
        queue.add(ship);
        while (!queue.isEmpty()) {
            final ServerSubLevel member = queue.poll();
            final Set<UUID> tied = new HashSet<>(IPShipJoints.partners(member));
            if (!roped.contains(member.getUniqueId())) {
                // a rope group is closed: its other members need no lookup of their own
                final Set<UUID> ropeGroup = IPShipRopeCompat.ropeGroup(level, member);
                roped.addAll(ropeGroup);
                tied.addAll(ropeGroup);
            }
            for (final UUID id : tied) {
                if (group.add(id) && container != null && container.getSubLevel(id) instanceof final ServerSubLevel next
                        && !next.isRemoved()) {
                    queue.add(next);
                }
            }
        }
        return group;
    }

    /**
     * Diagnostics (debug level, at most once a second per ship and portal): a ship near a portal is not considered.
     */
    private static void skipped(final ServerSubLevel ship, final Portal portal, final String reason) {
        declined(ship, portal, IPShipCrossingDetector.State.EMPTY, reason);
    }

    /**
     * Diagnostics (debug level, at most once a second per ship and portal): why a ship at a portal plane does not start
     * or continue crossing it.
     */
    private static void declined(final ServerSubLevel ship, final Portal portal, final State state, final String reason) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final String key = ship.getUniqueId() + " " + portal.getUUID();
        final Long last = DECLINED_LOGGED.get(key);
        if (last != null && now - last < 1000L) {
            return;
        }
        DECLINED_LOGGED.put(key, now);
        if (DECLINED_LOGGED.size() > 1024) {
            DECLINED_LOGGED.clear();
        }
        LOGGER.debug("[IPStraddle] ship {} at portal {} (facing {}) not crossing: {} [phase {}, swept through opening {}, entry {}, moving {}, nearest-to-far distance {}]",
                ship.getUniqueId(), portal.getUUID(), portal.getNormal(), reason, state.phase(), state.sweptThroughAperture(),
                state.sweptDirection(), state.currentDirection(), String.format("%.2f", state.maxDistance()));
    }

    /**
     * Whether a ship tied to {@code ship} is already on the far side of {@code portal}'s plane (its centre past it)
     * without crossing through that face itself: then {@code ship} must not start crossing it, it would be taken away
     * from it (a partner that went through the face first, and waits or straddles there, does not count). This happens
     * when a ship passed
     * the plane outside the opening (a balloon over the frame of the portal its hull goes through) and is pulled back:
     * its edge sweeps through the opening's margin the other way, which reads as entering the opposite face.
     *
     * @param normal the face's source-to-destination normal
     */
    private static boolean partnerPastFace(final ServerLevel level, final ServerSubLevel ship, final Portal portal, final Vec3 normal) {
        final Set<UUID> group = crossingGroup(level, ship);
        if (group.size() < 2) {
            return false;
        }
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        final Vec3 origin = portal.getOriginPos();
        for (final UUID member : group) {
            if (member.equals(ship.getUniqueId()) || !(container.getSubLevel(member) instanceof final ServerSubLevel partner)) {
                continue;
            }
            final Vector3dc position = partner.logicalPose().position();
            final Vec3 offset = new Vec3(position.x(), position.y(), position.z()).subtract(origin);
            final double depth = offset.dot(normal);
            // only past the doorway itself (behind the opening, within reach of a rope), not anywhere past its infinite plane
            if (depth > 0.0 && depth <= PARTNER_REACH
                    && Math.abs(offset.dot(portal.getAxisW())) <= portal.getWidth() * 0.5 + PARTNER_REACH
                    && Math.abs(offset.dot(portal.getAxisH())) <= portal.getHeight() * 0.5 + PARTNER_REACH
                    && !crossingThrough(member, portal)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a ship tied to {@code ship} has a session on another face of {@code portal}'s doorway (the opposite face):
     * a group crosses one doorway one way. A ship that went past the plane outside the opening (over the frame) and
     * swings back through the opening's edge would otherwise enter the opposite face while its partner crosses this one.
     */
    private static boolean partnerCrossesOtherFace(final ServerLevel level, final ServerSubLevel ship, final Portal portal) {
        final Set<UUID> group = crossingGroup(level, ship);
        if (group.size() < 2) {
            return false;
        }
        final boolean[] found = {false};
        IPStraddleSessions.forEach(session -> {
            if (!session.key.ship().equals(ship.getUniqueId()) && group.contains(session.key.ship())
                    && session.portal.getOriginPos().distanceToSqr(portal.getOriginPos()) < 1.0e-6
                    && session.portal.getNormal().dot(portal.getNormal()) < -0.999) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** Whether ship {@code shipId} has a session on {@code portal} or another entity of the same passage. */
    private static boolean crossingThrough(final UUID shipId, final Portal portal) {
        final boolean[] found = {false};
        IPStraddleSessions.forEach(session -> {
            if (session.key.ship().equals(shipId) && samePassage(session.portal, portal)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * The hand-offs of every ship of {@code group} through {@code leader}'s portal, or null when the group is not ready:
     * each member must be wholly through the same passage this tick, or wholly past its plane (a balloon that went over
     * the frame of the portal its hull went through: it crosses with the group, keeping its place relative to the
     * portal, instead of breaking its ropes), and movable to the destination.
     */
    private static @Nullable Map<UUID, Handoff> groupHandoffs(final Set<UUID> group, final Handoff leader,
                                                             final Map<UUID, Handoff> ready, final long tick) {
        final ServerLevel dest = leader.level().getServer().getLevel(leader.portal().getDestDim());
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(leader.level());
        if (dest == null || container == null) {
            return null;
        }
        final Map<UUID, Handoff> members = new HashMap<>();
        for (final UUID member : group) {
            Handoff memberHandoff = ready.get(member);
            if (memberHandoff == null || !samePassage(memberHandoff.portal(), leader.portal())) {
                if (!(container.getSubLevel(member) instanceof final ServerSubLevel ship) || ship.isRemoved()) {
                    return null;
                }
                final State state = IPShipCrossingDetector.evaluate(ship, IPShipCrossingDetector.blocks(ship, tick), leader.portal(),
                        leader.portal().getNormal().scale(-1.0), DETECTION_MARGIN);
                if (state.phase() != Phase.CROSSED) {
                    return null;
                }
                memberHandoff = new Handoff(ship, leader.level(), leader.portal());
                LOGGER.info("[IPStraddle] ship {} is past portal {} outside its opening; it crosses with the ships tied to it",
                        member, leader.portal().getUUID());
            }
            if (IPSableBridge.whyNotMovable(memberHandoff.ship(), memberHandoff.level(), dest) != null) {
                return null;
            }
            members.put(member, memberHandoff);
        }
        return members;
    }

    /**
     * Whether two portals take ships to the same place the same way: the same portal, or another entity of the same
     * doorway face (IP may create several) with the same destination and map.
     */
    private static boolean samePassage(final Portal a, final Portal b) {
        return a.getUUID().equals(b.getUUID())
                || (a.getDestDim().equals(b.getDestDim()) && IPPortalIsometry.of(a).sameAs(IPPortalIsometry.of(b))
                && a.getNormal().dot(b.getNormal()) > 0.999 && a.getOriginPos().distanceToSqr(b.getOriginPos()) < 1.0e-6);
    }

    /** A ship waiting for the ships tied to it keeps its session, with clip regions reaching further past the plane. */
    private static void waitForGroup(final Handoff handoff, final long tick) {
        final Vec3 normal = handoff.portal().getNormal().scale(-1.0);
        if (IPStraddleSessions.openOrRefresh(handoff.ship(), handoff.portal(), handoff.level(), normal, tick)) {
            IPStraddleSessions.setExtraDepth(new IPStraddleSessions.Key(handoff.ship().getUniqueId(), handoff.portal().getUUID()),
                    WAITING_EXTRA_DEPTH);
        }
    }

    private static void considerShip(final ServerLevel level, final ServerSubLevel ship, final long tick,
                                     final Set<IPStraddleSessions.Key> seen, final List<Handoff> handoffs) {
        if (ship.getPlot().getBoundingBox() == BoundingBox3i.EMPTY || IPShipRagdollGuard.isRagdollSubLevel(ship)) {
            return;
        }
        final AABB swept = IPShipCrossingDetector.sweptBounds(ship);
        if (swept == null) {
            return;
        }
        final boolean tooLarge = swept.getXsize() * swept.getYsize() * swept.getZsize() > MAX_SUBLEVEL_VOLUME;

        final List<Portal> nearby = nearbyPortals(level, ship, swept.inflate(PORTAL_QUERY_INFLATION));
        if (!tooLarge && (!nearby.isEmpty() || LET_THROUGH.containsKey(ship.getUniqueId()))) {
            letThroughFrameIfStuck(level, ship, IPShipCrossingDetector.blocks(ship, tick), nearby, tick);
        }
        if (nearby.isEmpty()) {
            return;
        }
        for (final Portal portal : nearby) {
            IPPortalRims.need(level, portal, tick);
        }
        if (tooLarge) {
            skipped(ship, nearby.get(0), "its swept bounds are larger than " + (long) MAX_SUBLEVEL_VOLUME + " blocks^3 (" + swept + ")");
            return;
        }
        final List<BlockPos> blocks = IPShipCrossingDetector.blocks(ship, tick);
        if (blocks.isEmpty()) {
            skipped(ship, nearby.get(0), "no blocks were read from its plot (" + ship.getPlot().getLoadedChunks().size() + " loaded plot chunks, bounds "
                    + ship.getPlot().getBoundingBox() + ")");
        }

        for (final Portal portal : nearby) {
            if (!isCanonicalFace(portal, nearby)) {
                skipped(ship, portal, "this portal entity duplicates another one of the same face (the other is used)");
                continue;
            }
            final IPStraddleSessions.Key key = new IPStraddleSessions.Key(ship.getUniqueId(), portal.getUUID());
            final boolean haveSession = IPStraddleSessions.has(key);
            final Vec3 normal = portal.getNormal().scale(-1.0);
            final State state = IPShipCrossingDetector.evaluate(ship, blocks, portal, normal, DETECTION_MARGIN);

            // Opening one segment early is neutral for a ship still in front of the plane (the clip regions only
            // remove contacts past it) and lets a fast ship meet an already open doorway.
            // Not for a ship about to hit the frame: a ship wider than the opening (its middle headed into it) opened a
            // session that its rest against the frame kept for good (2.0.7).
            final boolean predicted = !haveSession && state.phase() == Phase.APPROACHING
                    && IPShipCrossingDetector.willEnter(ship, blocks, portal, normal, DETECTION_MARGIN, EARLY_OPEN_SEGMENTS)
                    && IPShipCrossingDetector.frameContactAhead(level, ship, blocks, portal, normal, EARLY_OPEN_SEGMENTS) < -FRAME_TOUCHING;
            final boolean straddling = (state.phase() == Phase.STRADDLING && (haveSession || state.sweptThroughAperture()))
                    || predicted;

            if (haveSession && state.phase() == Phase.STRADDLING
                    && !IPShipCrossingDetector.straddlesInsideAperture(ship, blocks, portal, normal, DETECTION_MARGIN)) {
                // it slid along the plane out of the opening: the part past the plane is beside the portal, in its own world
                IPStraddleSessions.close(key, "left the opening");
                IPShipCrossingDetector.forget(ship.getUniqueId());
                continue;
            }
            if (state.phase() == Phase.STRADDLING && (haveSession ? state.maxDistance() < FRAME_HELD_DEPTH : straddling)
                    && !LET_THROUGH.containsKey(ship.getUniqueId())
                    && IPShipCrossingDetector.frameContact(level, ship, blocks, portal, normal) >= FRAME_PRESSED) {
                // A hard hit on the frame pushed blocks in front of the opening up to the plane: the frame holds the ship,
                // nothing is through (not while it is let through the frame: then the frame holds nothing). With a session the clip regions would leave the frame's inner half past the plane
                // without collision, and the ship drawn cut at the plane.
                if (haveSession) {
                    IPStraddleSessions.close(key, "held by the portal's frame");
                    IPShipCrossingDetector.forget(ship.getUniqueId());
                } else {
                    declined(ship, portal, state, "it is pressed into the portal's frame");
                }
                continue;
            }
            if (!haveSession && state.phase() == Phase.STRADDLING && !straddling) {
                declined(ship, portal, state, "no block centre has moved through the opening yet");
            }
            if (straddling) {
                final Portal twin = oppositeFace(portal, nearby);
                if (!ownsFace(twin, state, haveSession, predicted)) {
                    declined(ship, portal, state, "the opposite face owns the doorway (entry direction " + state.sweptDirection() + ")");
                    continue;
                }
                if (!haveSession && partnerPastFace(level, ship, portal, normal)) {
                    declined(ship, portal, state, "a ship tied to it is already past this face without crossing it");
                    continue;
                }
                if (!haveSession && partnerCrossesOtherFace(level, ship, portal)) {
                    declined(ship, portal, state, "a ship tied to it crosses the opposite face of this doorway");
                    continue;
                }
                final boolean twinHasSession = twin != null && hasSession(ship, twin);
                if (twinHasSession) {
                    declined(ship, portal, state, "it has a session on the opposite face");
                    if (haveSession) {
                        // one session per doorway; cannot normally happen
                        IPStraddleSessions.close(key, "the opposite face of the doorway has a session");
                    }
                    continue;
                }
                if (IPStraddleSessions.openOrRefresh(ship, portal, level, normal, tick)) {
                    seen.add(key);
                }
                continue;
            }

            final boolean throughOwnedFace = haveSession
                    ? state.currentDirection() != Direction.TOWARD_SOURCE
                    : state.startedBeforePlane() && state.sweptThroughAperture() && state.sweptDirection() == Direction.TOWARD_DESTINATION;
            if (state.phase() == Phase.CROSSED && throughOwnedFace) {
                final Portal twin = oppositeFace(portal, nearby);
                if (!haveSession && twin != null && hasSession(ship, twin)) {
                    // backing out through the other face, which owns the doorway
                    continue;
                }
                if (!ownsFace(twin, state, haveSession, false)) {
                    declined(ship, portal, state, "wholly past the plane, but the opposite face owns the doorway");
                    continue;
                }
                if (!haveSession && (partnerPastFace(level, ship, portal, normal) || partnerCrossesOtherFace(level, ship, portal))) {
                    declined(ship, portal, state, "wholly past the plane, but the ships tied to it are on another passage");
                    continue;
                }
                final String refusal = refusalReason(ship, level, portal, tick);
                if (refusal != null) {
                    if (refuse(ship, level, portal, normal, tick, refusal)) {
                        seen.add(key);
                    }
                    return;
                }
                if (haveSession) {
                    seen.add(key);
                }
                handoffs.add(new Handoff(ship, level, portal));
                return;
            }

            if (!haveSession && state.phase() == Phase.CROSSED && !throughOwnedFace && state.currentDirection() != Direction.NONE) {
                declined(ship, portal, state, "wholly past the plane without having entered through the opening (started before the plane: "
                        + state.startedBeforePlane() + ")");
            }

            if (haveSession && state.phase() == Phase.CROSSED && state.currentDirection() == Direction.TOWARD_SOURCE) {
                // pulled back while wholly past the plane: keep the face until it reaches the source side
                seen.add(key);
                IPStraddleSessions.openOrRefresh(ship, portal, level, normal, tick);
                continue;
            }

            if (haveSession && state.phase() == Phase.APPROACHING && keepsOpen(key, state, tick)
                    && IPShipCrossingDetector.frameContact(level, ship, blocks, portal, normal) < -FRAME_TOUCHING) {
                // hysteresis: a ship hovering at the plane (or opened early by the lookahead) keeps its session
                seen.add(key);
                IPStraddleSessions.openOrRefresh(ship, portal, level, normal, tick);
                continue;
            }

            if (haveSession) {
                IPStraddleSessions.close(key, state.phase() != Phase.APPROACHING ? "left the aperture"
                        : keepsOpen(key, state, tick) ? "resting against the portal's frame" : "backed out");
                // The segment just unwound must not be read as an entry through the opposite face.
                IPShipCrossingDetector.forget(ship.getUniqueId());
                return;
            }
        }
    }

    /**
     * Lets a ship stuck in a portal's frame through the frame blocks it is inside ({@value #FRAME_STUCK_DEPTH} deep for
     * {@value #FRAME_STUCK_TICKS} ticks), on both sides of the portal, until it overlaps none of them: it can then be
     * flown or dragged out, and collides with the frame again as soon as it is clear. Frame blocks it moves into
     * meanwhile are let through too.
     */
    private static void letThroughFrameIfStuck(final ServerLevel level, final ServerSubLevel ship, final List<BlockPos> blocks,
                                               final List<Portal> nearby, final long tick) {
        final UUID id = ship.getUniqueId();
        final Map<BlockPos, Double> overlapping = new HashMap<>();
        double deepest = Double.NEGATIVE_INFINITY;
        Portal deepestPortal = null;
        for (final Portal portal : nearby) {
            if (!isCanonicalFace(portal, nearby) || blocks.isEmpty()) {
                continue;
            }
            final double depth = IPShipCrossingDetector.frameOverlaps(level, ship, blocks, portal, portal.getNormal().scale(-1.0),
                    0.0, overlapping);
            if (depth > deepest) {
                deepest = depth;
                deepestPortal = portal;
            }
        }
        final Set<BlockPos> current = LET_THROUGH.get(id);
        if (current != null) {
            if (overlapping.isEmpty()) {
                LET_THROUGH.remove(id);
                IPStraddleSessions.setGhosts(ship, level, List.of());
                IPPortalRims.setLetThrough(ship, false);
                LOGGER.info("[IPStraddle] ship {} is free of the portal frame: it collides with the frame again", id);
            } else if (!current.equals(overlapping.keySet())) {
                LET_THROUGH.put(id, new HashSet<>(overlapping.keySet()));
                IPStraddleSessions.setGhosts(ship, level, new ArrayList<>(overlapping.keySet()));
            }
            return;
        }
        if (deepest < FRAME_STUCK_DEPTH) {
            STUCK_SINCE.remove(id);
            return;
        }
        final long since = STUCK_SINCE.computeIfAbsent(id, key -> tick);
        if (tick - since < FRAME_STUCK_TICKS) {
            return;
        }
        STUCK_SINCE.remove(id);
        LET_THROUGH.put(id, new HashSet<>(overlapping.keySet()));
        IPStraddleSessions.setGhosts(ship, level, new ArrayList<>(overlapping.keySet()));
        IPPortalRims.setLetThrough(ship, true);
        LOGGER.info("[IPStraddle] ship {} is stuck {} blocks deep in the frame of portal {}: letting it through {} frame block(s) until it is free",
                id, String.format("%.2f", deepest), deepestPortal.getUUID(), overlapping.size());
    }

    /**
     * Whether a session whose ship is back before the plane stays open: while the ship is within
     * {@value #KEEP_DISTANCE} of the plane, or for {@value #MIN_SESSION_TICKS} ticks after it opened. Without this a
     * ship resting at the plane, or opened one segment early and then slowed down, opened and closed its session (and
     * its physics image) every few ticks. The caller closes it anyway while the ship touches the portal's frame: a ship
     * resting against a nether portal's frame is exactly {@value #KEEP_DISTANCE} before the plane.
     */
    private static boolean keepsOpen(final IPStraddleSessions.Key key, final State state, final long tick) {
        final IPStraddleSessions.Session session = IPStraddleSessions.get(key);
        return state.maxDistance() >= -KEEP_DISTANCE || (session != null && tick - session.openedTick < MIN_SESSION_TICKS);
    }

    /**
     * Why {@code ship}, wholly through {@code portal}, cannot be handed off now, or null when it can: the destination
     * level is missing, the move would fail ({@link IPSableBridge#whyNotMovable}), or a hand-off through this portal
     * failed less than {@value #FAILED_HANDOFF_RETRY_TICKS} ticks ago.
     */
    @Nullable
    private static String refusalReason(final ServerSubLevel ship, final ServerLevel level, final Portal portal, final long tick) {
        final ServerLevel dest = level.getServer().getLevel(portal.getDestDim());
        if (dest == null) {
            return "its destination " + portal.getDestDim().location() + " is not loaded";
        }
        if (DEBUG_REFUSE_HANDOFF) {
            return "hand-offs are switched off for testing (-Dip_aeronautics.debug.refuseHandoff)";
        }
        final Refusal last = REFUSALS.get(ship.getUniqueId());
        if (last != null && last.portal().equals(portal.getUUID()) && tick < last.retryTick()) {
            return last.reason();
        }
        return IPSableBridge.whyNotMovable(ship, level, dest);
    }

    /**
     * A ship wholly through a portal that cannot be handed off keeps its session, so it stays physically in the
     * destination (its image) and nothing behind the portal in its own world touches it, and is pushed back towards
     * the plane: the straddle path's replacement for the one-step crossing's refusal push and stuck-ship shove. Logged
     * once per refusal reason and portal.
     *
     * @return whether the session is open
     */
    private static boolean refuse(final ServerSubLevel ship, final ServerLevel level, final Portal portal, final Vec3 normal,
                                  final long tick, final String reason) {
        final Refusal last = REFUSALS.get(ship.getUniqueId());
        if (last == null || !last.portal().equals(portal.getUUID()) || !last.reason().equals(reason)) {
            LOGGER.info("[IPStraddle] ship {} is through portal {} but cannot be handed off: {}; pushed back",
                    ship.getUniqueId(), portal.getUUID(), reason);
        }
        final long retryTick = last != null && last.portal().equals(portal.getUUID()) ? last.retryTick() : tick;
        REFUSALS.put(ship.getUniqueId(), new Refusal(portal.getUUID(), reason, retryTick));
        final boolean open = IPStraddleSessions.openOrRefresh(ship, portal, level, normal, tick);
        IPSableBridge.pushBackOutOfPortal(ship, normal.scale(-1.0), REFUSED_PUSH_BACK_SPEED);
        return open;
    }

    /**
     * Teleportable unscaled portals around the ship, plus the portals of its sessions (an oblique ship can straddle a
     * plane after its box has moved past the query area). Sorted by UUID for a stable face order. Breakable portals
     * whose blocks are gone are broken instead, as by the one-step crossing.
     */
    private static List<Portal> nearbyPortals(final ServerLevel level, final ServerSubLevel ship, final AABB area) {
        final List<Portal> nearby = new ArrayList<>();
        // never a portal mounted on this very ship
        for (final Portal portal : qouteall.imm_ptl.core.compat.IPPortalLookup.touching(level, area,
                p -> isShipPortal(p) && !IPShipPortalAnchors.isCarriedBy(p, ship))) {
            if (portal instanceof final BreakablePortalEntity breakable && !breakable.isIntactNow()) {
                LOGGER.info("[IPStraddle] ignoring and breaking {}: its portal blocks are no longer in place", portal);
                breakable.breakPortalNow();
                continue;
            }
            nearby.add(portal);
        }
        IPStraddleSessions.forEach(session -> {
            if (session.ship == ship && session.source == level && !session.portal.isRemoved() && !nearby.contains(session.portal)) {
                nearby.add(session.portal);
            }
        });
        nearby.sort(Comparator.comparing(portal -> portal.getUUID().toString()));
        return nearby;
    }

    static boolean isShipPortal(final Entity entity) {
        return entity instanceof final Portal portal && portal.isTeleportable() && portal.getDestDim() != null
                && Math.abs(portal.getScale() - 1.0) <= 1.0E-4;
    }

    private static boolean hasSession(final ServerSubLevel ship, final Portal face) {
        return IPStraddleSessions.has(new IPStraddleSessions.Key(ship.getUniqueId(), face.getUUID()));
    }

    /**
     * A new session belongs to the face the ship's blocks were swept through towards the destination; an existing one
     * keeps its face. Faces with no coincident opposite face need no proof.
     */
    private static boolean ownsFace(@Nullable final Portal twin, final State state, final boolean haveSession, final boolean predicted) {
        if (twin == null || haveSession || predicted) {
            return true;
        }
        return state.sweptDirection() == Direction.TOWARD_DESTINATION;
    }

    /**
     * The other face of the same doorway (IP's flipped portal, or a coincident portal with the opposite normal and
     * the same size), or null.
     */
    @Nullable
    private static Portal oppositeFace(final Portal portal, final List<Portal> candidates) {
        final PortalExtension extension = PortalExtension.get(portal);
        Portal flipped = extension.flippedPortal;
        if (flipped == null && extension.flippedPortalId != null && portal.level() instanceof final ServerLevel level
                && level.getEntity(extension.flippedPortalId) instanceof final Portal byId) {
            flipped = byId;
        }
        if (flipped != null && !flipped.isRemoved()) {
            return flipped;
        }
        for (final Portal other : candidates) {
            if (other != portal
                    && portal.getOriginPos().distanceToSqr(other.getOriginPos()) <= 1.0e-12
                    && portal.getNormal().dot(other.getNormal()) <= -0.999999
                    && Math.abs(portal.getWidth() - other.getWidth()) <= 1.0e-6
                    && Math.abs(portal.getHeight() - other.getHeight()) <= 1.0e-6) {
                return other;
            }
        }
        return null;
    }

    /**
     * IP can create several entities for one face of a doorway (same rectangle, same destination); only the one with
     * the smallest UUID is used. Opposite faces are never duplicates.
     */
    private static boolean isCanonicalFace(final Portal portal, final List<Portal> candidates) {
        for (final Portal other : candidates) {
            if (other == portal || !portal.getDestDim().equals(other.getDestDim())) {
                continue;
            }
            if (portal.getOriginPos().distanceToSqr(other.getOriginPos()) > 1.0e-12
                    || portal.getDestPos().distanceToSqr(other.getDestPos()) > 1.0e-12
                    || Math.abs(portal.getWidth() - other.getWidth()) > 1.0e-6
                    || Math.abs(portal.getHeight() - other.getHeight()) > 1.0e-6
                    || portal.getNormal().dot(other.getNormal()) < 0.999999) {
                continue;
            }
            if (portal.getUUID().toString().compareTo(other.getUUID().toString()) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Moves a ship that is wholly through a portal to the destination level, at its pose and velocity mapped through
     * the portal. Its sessions are closed first (the move removes its body, and the images with it).
     */
    @Nullable
    private static IPSableBridge.Moved handOff(final MinecraftServer server, final Handoff handoff, final Set<UUID> travelling) {
        final ServerSubLevel ship = handoff.ship();
        if (ship.isRemoved()) {
            return null;
        }
        final Portal portal = handoff.portal();
        final ServerLevel dest = server.getLevel(portal.getDestDim());
        if (dest == null) {
            return null;
        }
        final UUID shipId = ship.getUniqueId();
        // an earlier hand-off of this tick may have taken the destination's last free plot
        final String refusal = IPSableBridge.whyNotMovable(ship, handoff.level(), dest);
        if (refusal != null) {
            refuse(ship, handoff.level(), portal, portal.getNormal().scale(-1.0), server.getTickCount(), refusal);
            if (travelling.size() > 1) {
                IPShipRopeCompat.breakAttachedRopes(handoff.level(), ship, Set.of(shipId));
            }
            return null;
        }
        final IPPortalIsometry isometry = IPPortalIsometry.of(portal);
        final Vector3dc position = ship.logicalPose().position();
        final Vec3 destPos = isometry.mapPoint(new Vec3(position.x(), position.y(), position.z()));
        final Vec3 arrivalDisplacement = isometry.mapVec(toVec3(position).subtract(toVec3(ship.lastPose().position())));
        final Quaterniond rotation = new Quaterniond(isometry.rotation());

        IPSableBridge.Velocity carried = null;
        final RigidBodyHandle body = RigidBodyHandle.of(ship);
        final IPShipPortalAnchors.Mount mount = IPShipPortalAnchors.mountFor(portal);
        if (body != null && mount != null) {
            // a portal mounted on a ship: the velocity relative to its moving side (IPMountedStraddle)
            final Vector3d[] velocity = IPMountedStraddle.handoffVelocity(mount, isometry, toVec3(position), destPos,
                    body.getLinearVelocity(new Vector3d()), body.getAngularVelocity(new Vector3d()));
            carried = new IPSableBridge.Velocity(velocity[0], velocity[1]);
        } else if (body != null) {
            carried = new IPSableBridge.Velocity(
                    toVector(isometry.mapVec(toVec3(body.getLinearVelocity(new Vector3d())))),
                    toVector(isometry.mapVec(toVec3(body.getAngularVelocity(new Vector3d())))));
        }

        if (LET_THROUGH.remove(shipId) != null) {
            // the frame blocks are the source level's; the moved ship gets a new body
            IPStraddleSessions.setGhosts(ship, handoff.level(), List.of());
        }
        STUCK_SINCE.remove(shipId);
        IPStraddleSync.markHandedOff(new IPStraddleSessions.Key(shipId, portal.getUUID()));
        for (final IPStraddleSessions.Key key : IPStraddleSessions.keys()) {
            if (key.ship().equals(shipId)) {
                IPStraddleSessions.close(key, "handed off to " + dest.dimension().location());
            }
        }
        IPShipCrossingDetector.forget(shipId);

        LOGGER.info("[IPStraddle] ship {} is through portal {}: handing off {} -> {} at {} with velocity {}",
                shipId, portal.getUUID(), handoff.level().dimension().location(), dest.dimension().location(), destPos,
                carried == null ? "none" : carried.linear());
        IPShipThroughPortalSync.resendLastPose(handoff.level(), ship);
        final dev.ryanhcode.sable.companion.math.Pose3d sentPose = new dev.ryanhcode.sable.companion.math.Pose3d(ship.lastNetworkedPose());
        final dev.ryanhcode.sable.companion.math.Pose3d handoffPose = new dev.ryanhcode.sable.companion.math.Pose3d(ship.logicalPose());
        final IPSableBridge.Moved moved = IPSableBridge.moveThroughPortal(ship, handoff.level(), dest, destPos, rotation, carried, travelling);
        if (IPStraddleDebugTrace.ENABLED && moved != null) {
            final dev.ryanhcode.sable.companion.math.Pose3d mapped = isometry.mapPose(handoffPose);
            final dev.ryanhcode.sable.companion.math.Pose3dc arrived = moved.sub().logicalPose();
            LOGGER.info("[IPStraddle] trace: hand-off pose vs last sent {} m; arrived vs mapped: position {} m, rotation point {} (old {}), last pose {} m",
                    handoffPose.position().distance(sentPose.position()), arrived.position().distance(mapped.position()),
                    arrived.rotationPoint(), handoffPose.rotationPoint(), moved.sub().lastPose().position().distance(arrived.position()));
        }
        if (moved == null) {
            LOGGER.warn("[IPStraddle] hand-off of ship {} failed (see above)", shipId);
            onHandoffFailed(handoff, server.getTickCount(), travelling);
        } else {
            REFUSALS.remove(shipId);
            IPShipCrossingDetector.seedArrival(moved.sub(), arrivalDisplacement, isometry.mapPoint(portal.getOriginPos()),
                    isometry.mapVec(portal.getNormal().scale(-1.0)), server.getTickCount());
            IPStraddleDebugTrace.onHandedOff(shipId, server.getTickCount());
            LOGGER.info("[IPStraddle] ship {} arrived in {} (plot-space shift {})", shipId, dest.dimension().location(), moved.shift());
            IPShipThroughPortalSync.onShipLeftDimension(server, shipId, handoff.level().dimension());
            IPShipThroughPortalSync.onShipArrived(dest, moved.sub());
            IPStraddleSeats.onHandedOff(handoff.level(), dest, shipId, moved);
            IPPhysicsStaffHooks.onShipMoved(handoff.level(), dest, shipId, portal, moved);
        }
        return moved;
    }

    /**
     * A hand-off that got past {@link IPSableBridge#whyNotMovable} failed while loading the ship in the destination; the
     * ship was restored to its own level (a new copy, at rest, wholly past the plane) or, if even that failed, waits in
     * the teleport journal. A restored ship gets its session back at once (otherwise it sits behind the portal in its
     * own world) and is refused until {@value #FAILED_HANDOFF_RETRY_TICKS} ticks have passed.
     */
    private static void onHandoffFailed(final Handoff handoff, final long tick, final Set<UUID> travelling) {
        final UUID shipId = handoff.ship().getUniqueId();
        REFUSALS.put(shipId, new Refusal(handoff.portal().getUUID(), "loading it in the destination failed", tick + FAILED_HANDOFF_RETRY_TICKS));
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(handoff.level());
        if (container != null && container.getSubLevel(shipId) instanceof final ServerSubLevel restored && !restored.isRemoved()) {
            if (travelling.size() > 1) {
                // its partners crossed: ropes tied to them now name ships of another level
                IPShipRopeCompat.breakAttachedRopes(handoff.level(), restored, Set.of(shipId));
            }
            refuse(restored, handoff.level(), handoff.portal(), handoff.portal().getNormal().scale(-1.0), tick,
                    "loading it in the destination failed");
        }
    }

    private static Vec3 toVec3(final Vector3dc v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static Vector3d toVector(final Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
