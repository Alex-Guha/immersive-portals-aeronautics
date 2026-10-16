/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplStraddleSessionStore by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaterniond;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The client's copy of the server's straddle sessions (docs/lessons/straddle-rendering.md), filled by
 * {@code IPStraddleSync} over IP's remote procedure calls. Render thread only.
 */
public final class IPStraddleClientSessions {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One ship straddling one portal.
     *
     * @param shipDim the dimension the ship belongs to (its client sub-level lives in that client level)
     * @param origin  a point of the portal plane, in the ship's dimension
     * @param normal  the source-to-destination unit normal of the plane
     * @param isometry maps the ship's dimension into {@code destDim} through the portal
     */
    public record Session(UUID ship, ResourceKey<Level> shipDim, UUID portal, ResourceKey<Level> destDim, Vec3 origin,
                          Vec3 normal, Vec3 axisW, Vec3 axisH, double halfW, double halfH, IPPortalIsometry isometry) {
        /**
         * @return the plane in the destination, as a point and the normal pointing into the destination world (away
         * from the portal, towards where the far half is)
         */
        public Vec3 destOrigin() {
            return this.isometry.mapPoint(this.origin);
        }

        public Vec3 destNormal() {
            return this.isometry.mapVec(this.normal);
        }
    }

    private record Key(UUID ship, UUID portal) {
    }

    /**
     * A session at a portal mounted on a ship (docs/plans/ship-mounted-portals.md, phase 4): its geometry follows the
     * carrier as drawn ({@code IPStraddleSync.sendMount} for the invariants).
     */
    private record Mount(UUID carrier, ResourceKey<Level> carrierDim, boolean carrierIsSource, Vec3 plotOrigin,
                         Vec3 plotNormal, Vec3 plotAxisW, Vec3 plotAxisH, Vec3 kDest, Quaterniond kRotation) {
    }

    private static final Map<Key, Mount> MOUNTS = new HashMap<>();

    /**
     * The session as it is to be drawn now: unchanged for a still portal; for a mounted one, recomputed from the
     * carrier's render pose (the one Sable draws it with this frame), so the cut and the far half stay on the moving
     * portal. Without the carrier on this client, the server's last geometry.
     */
    private static Session current(final Key key, final Session session) {
        final Mount mount = MOUNTS.get(key);
        if (mount == null) {
            return session;
        }
        final net.minecraft.client.multiplayer.ClientLevel level = qouteall.imm_ptl.core.ClientWorldLoader.getOptionalWorld(mount.carrierDim());
        final dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
                level == null ? null : dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null || !(container.getSubLevel(mount.carrier()) instanceof final dev.ryanhcode.sable.sublevel.ClientSubLevel carrier)
                || carrier.isRemoved()) {
            return session;
        }
        final dev.ryanhcode.sable.companion.math.Pose3dc pose = carrier.renderPose();
        if (mount.carrierIsSource()) {
            // P = K ∘ M⁻¹: the plane is where the carrier draws it, the fixed side stays
            final Vec3 origin = pose.transformPosition(mount.plotOrigin());
            final Quaterniond rotation = new Quaterniond(mount.kRotation()).mul(new Quaterniond(pose.orientation()).conjugate()).normalize();
            return new Session(session.ship(), session.shipDim(), session.portal(), session.destDim(), origin,
                    pose.transformNormal(mount.plotNormal()), pose.transformNormal(mount.plotAxisW()), pose.transformNormal(mount.plotAxisH()),
                    session.halfW(), session.halfH(), new IPPortalIsometry(origin, mount.kDest(), rotation));
        }
        // P = M ∘ K: the plane stays, the far side is where the carrier draws it
        final Quaterniond rotation = new Quaterniond(pose.orientation()).mul(mount.kRotation()).normalize();
        return new Session(session.ship(), session.shipDim(), session.portal(), session.destDim(), session.origin(),
                session.normal(), session.axisW(), session.axisH(), session.halfW(), session.halfH(),
                new IPPortalIsometry(session.origin(), pose.transformPosition(mount.kDest()), rotation));
    }

    private static void addCurrent(final List<Session> out, final Map<Key, Session> sessions) {
        for (final Map.Entry<Key, Session> entry : sessions.entrySet()) {
            out.add(current(entry.getKey(), entry.getValue()));
        }
    }

    /**
     * How long an ended session still cuts its ship: the client draws ships at an interpolated pose that trails the
     * server's, so a ship whose session just ended (backed out, or handed off and about to be removed here) can
     * still be drawn straddling for a few ticks.
     */
    private static final long RETIRED_MILLIS = 1000L;

    private static final Map<Key, Session> SESSIONS = new HashMap<>();
    /** Ended sessions, with the time they stop being drawn. */
    private static final Map<Key, Long> RETIRED_UNTIL = new HashMap<>();
    private static final Map<Key, Session> RETIRED = new HashMap<>();
    /** Ships whose last session ended in a hand-off, with the time it was reported. */
    private static final Map<UUID, Long> HANDED_OFF = new HashMap<>();
    /** For {@link #HANDED_OFF}: the dimension the ship was handed off from. */
    private static final Map<UUID, ResourceKey<Level>> HANDED_OFF_FROM = new HashMap<>();

    private IPStraddleClientSessions() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> {
            SESSIONS.clear();
            MOUNTS.clear();
            RETIRED.clear();
            RETIRED_UNTIL.clear();
            HANDED_OFF.clear();
            HANDED_OFF_FROM.clear();
            IPStraddleHandoffLatch.clear();
        });
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class, e -> IPStraddleHandoffLatch.tick());
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class, e -> IPStraddleParticles.tick());
        // also per frame: the new copy can become drawable between ticks, and both should not show at once
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, e -> IPStraddleHandoffLatch.tick());
    }

    /**
     * @return whether there is nothing to draw (no session, not even a recently ended one)
     */
    public static boolean isEmpty() {
        return SESSIONS.isEmpty() && RETIRED.isEmpty();
    }

    /**
     * @return the sessions to draw with: the active ones and the recently ended ones
     */
    public static List<Session> renderSessions() {
        pruneRetired();
        final List<Session> result = new ArrayList<>(SESSIONS.size() + RETIRED.size());
        addCurrent(result, SESSIONS);
        addCurrent(result, RETIRED);
        return result;
    }

    public static List<Session> renderSessionsOf(final dev.ryanhcode.sable.sublevel.SubLevel ship) {
        if (isEmpty()) {
            return List.of();
        }
        final List<Session> result = new ArrayList<>(1);
        for (final Session session : renderSessions()) {
            if (session.ship().equals(ship.getUniqueId()) && session.shipDim().equals(ship.getLevel().dimension())) {
                result.add(session);
            }
        }
        return result;
    }

    public static List<Session> renderSessionsInto(final ResourceKey<Level> dimension) {
        if (isEmpty()) {
            return List.of();
        }
        final List<Session> result = new ArrayList<>(1);
        for (final Session session : renderSessions()) {
            if (session.destDim().equals(dimension)) {
                result.add(session);
            }
        }
        return result;
    }

    /**
     * @return the ships of other dimensions whose far half is in {@code level} (active or recently ended sessions)
     */
    public static List<qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut.Projection> projectionsInto(final Level level) {
        if (isEmpty()) {
            return List.of();
        }
        final List<qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut.Projection> result = new ArrayList<>(1);
        for (final Session session : renderSessionsInto(level.dimension())) {
            if (session.shipDim().equals(level.dimension())) {
                continue;
            }
            final net.minecraft.client.multiplayer.ClientLevel shipLevel =
                    qouteall.imm_ptl.core.ClientWorldLoader.getOptionalWorld(session.shipDim());
            if (shipLevel == null) {
                continue;
            }
            final dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(shipLevel);
            final dev.ryanhcode.sable.sublevel.SubLevel ship = container == null ? null : container.getSubLevel(session.ship());
            if (ship != null && !ship.isRemoved()) {
                result.add(new qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut.Projection(
                        ship, session.isometry(), session.destOrigin(), session.destNormal(), session.portal()));
            }
        }
        return result;
    }

    /**
     * @return {px, py, pz, nx, ny, nz} per session (active or recently ended) of {@code ship} out of its own dimension:
     * the side of its portal plane that is kept, {@code n . (x - p) >= 0}
     */
    public static List<double[]> keepPlanesOf(final dev.ryanhcode.sable.sublevel.SubLevel ship) {
        final List<double[]> planes = new ArrayList<>(1);
        for (final Session session : renderSessionsOf(ship)) {
            planes.add(new double[]{session.origin().x, session.origin().y, session.origin().z,
                    -session.normal().x, -session.normal().y, -session.normal().z});
        }
        return planes;
    }

    /**
     * @return whether {@code ship} (a copy in its own dimension) straddles, or just stopped straddling, a portal in a
     * way that may be a hand-off: a session of it is active, or its last one ended in a hand-off
     */
    public static boolean mayBeHandingOff(final dev.ryanhcode.sable.sublevel.SubLevel ship) {
        final Long handedOff = HANDED_OFF.get(ship.getUniqueId());
        // only the copy in the dimension the ship left: a removal misrouted to the destination must never hold the new copy
        if (handedOff != null && System.currentTimeMillis() - handedOff < 10_000L
                && ship.getLevel().dimension().equals(HANDED_OFF_FROM.get(ship.getUniqueId()))) {
            return true;
        }
        for (final Session session : SESSIONS.values()) {
            if (session.ship().equals(ship.getUniqueId()) && session.shipDim().equals(ship.getLevel().dimension())) {
                return true;
            }
        }
        return false;
    }

    private static void pruneRetired() {
        if (RETIRED.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        RETIRED_UNTIL.entrySet().removeIf(entry -> {
            if (entry.getValue() > now || IPStraddleHandoffLatch.isPending(entry.getKey().ship())) {
                return false;
            }
            RETIRED.remove(entry.getKey());
            if (!SESSIONS.containsKey(entry.getKey())) {
                MOUNTS.remove(entry.getKey());
            }
            return true;
        });
    }

    public static Collection<Session> all() {
        if (MOUNTS.isEmpty()) {
            return SESSIONS.values();
        }
        final List<Session> result = new ArrayList<>(SESSIONS.size());
        addCurrent(result, SESSIONS);
        return result;
    }

    /**
     * @return the sessions of a ship (it may straddle several portals), empty when it straddles none
     */
    public static List<Session> ofShip(final UUID ship) {
        if (SESSIONS.isEmpty()) {
            return List.of();
        }
        final List<Session> result = new ArrayList<>(1);
        for (final Map.Entry<Key, Session> entry : SESSIONS.entrySet()) {
            if (entry.getValue().ship().equals(ship)) {
                result.add(current(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    /**
     * @return the sessions whose far half is in {@code dimension}
     */
    public static List<Session> into(final ResourceKey<Level> dimension) {
        if (SESSIONS.isEmpty()) {
            return List.of();
        }
        final List<Session> result = new ArrayList<>(1);
        for (final Map.Entry<Key, Session> entry : SESSIONS.entrySet()) {
            if (entry.getValue().destDim().equals(dimension)) {
                result.add(current(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    public static final class RemoteCallables {
        private RemoteCallables() {
        }

        public static void begin(final UUID ship, final ResourceKey<Level> shipDim, final UUID portal,
                                 final ResourceKey<Level> destDim, final Vec3 origin, final Vec3 normal,
                                 final Vec3 axisW, final Vec3 axisH, final double halfW, final double halfH,
                                 final Vec3 dest, final DQuaternion rotation) {
            final IPPortalIsometry isometry = new IPPortalIsometry(origin, dest,
                    new Quaterniond(rotation.x, rotation.y, rotation.z, rotation.w).normalize());
            RETIRED.remove(new Key(ship, portal));
            RETIRED_UNTIL.remove(new Key(ship, portal));
            SESSIONS.put(new Key(ship, portal),
                    new Session(ship, shipDim, portal, destDim, origin, normal, axisW, axisH, halfW, halfH, isometry));
            LOGGER.debug("[IPStraddle] client: ship {} straddles portal {} ({} -> {})", ship, portal, shipDim.location(), destDim.location());
        }

        public static void mount(final UUID ship, final UUID portal, final UUID carrier, final ResourceKey<Level> carrierDim,
                                 final boolean carrierIsSource, final Vec3 plotOrigin, final Vec3 plotNormal,
                                 final Vec3 plotAxisW, final Vec3 plotAxisH, final Vec3 kDest, final DQuaternion kRotation) {
            MOUNTS.put(new Key(ship, portal), new Mount(carrier, carrierDim, carrierIsSource, plotOrigin, plotNormal,
                    plotAxisW, plotAxisH, kDest, new Quaterniond(kRotation.x, kRotation.y, kRotation.z, kRotation.w).normalize()));
        }

        public static void end(final UUID ship, final UUID portal, final boolean handedOff) {
            final Key key = new Key(ship, portal);
            final Session ended = SESSIONS.remove(key);
            if (handedOff && ended != null) {
                HANDED_OFF.put(ship, System.currentTimeMillis());
                HANDED_OFF_FROM.put(ship, ended.shipDim());
            } else if (!handedOff) {
                HANDED_OFF.remove(ship);
                HANDED_OFF_FROM.remove(ship);
            }
            if (ended != null) {
                RETIRED.put(key, ended);
                RETIRED_UNTIL.put(key, System.currentTimeMillis() + RETIRED_MILLIS);
            }
            LOGGER.debug("[IPStraddle] client: ship {} left portal {}{}", ship, portal, handedOff ? " (handed off)" : "");
        }
    }
}
