/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplAtlasStraddleSession by r2smith141 (Apache License 2.0), re-expressed for ships that
 * stay in their own level (no hosting dimension).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipaeronautics.rapier.RapierExt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Straddle sessions, one per (ship, portal) (docs/lessons/straddle-sessions.md). While a ship straddles a
 * portal its rigid body, still in its own level's chart, gains an image collider in the destination level's chart:
 * its own shape at its pose mapped through the portal. Contacts on the image act on the one real body, solved in the
 * same step. Two clip regions make the seam half-open:
 * <ul>
 *     <li>the body's own collider drops contacts past the source plane, inside the aperture (grown by
 *     {@value #APERTURE_MARGIN} on each side) and up to the ship's diagonal deep: the part through the portal no
 *     longer touches what is behind the portal in the source world;</li>
 *     <li>the image drops contacts before the destination plane, in the mapped aperture: only the part through the
 *     portal touches the destination world.</li>
 * </ul>
 * Sessions are server-only and never saved; a ship that is not straddling has no image and no clip regions.
 */
final class IPStraddleSessions {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Lateral growth of the clip apertures, as in IPSable (the same margin admits ships in the detector). */
    static final double APERTURE_MARGIN = 0.5;
    /** Tolerance before the plane: a contact a hair before the cut whose geometry is already past it is dropped. */
    private static final double CLIP_SKIN = 1.0e-3;

    record Key(UUID ship, UUID portal) {
    }

    static final class Session {
        final Key key;
        final ServerSubLevel ship;
        final Portal portal;
        final ServerLevel source;
        final ServerLevel dest;
        final int bodyId;
        IPPortalIsometry isometry;
        Vec3 normal;
        Vec3 origin;
        double[] realClip;
        long imageHandle = -1L;
        /** Bumped whenever the portal geometry changes, so clients are told again ({@link IPStraddleSync}). */
        int version;
        /** Server tick the session opened at. */
        long openedTick;
        /** Added to the clip depth while the ship waits wholly past the plane ({@link #setExtraDepth}). */
        double extraDepth;

        private Session(final Key key, final ServerSubLevel ship, final Portal portal, final ServerLevel source,
                        final ServerLevel dest, final IPPortalIsometry isometry, final Vec3 normal) {
            this.key = key;
            this.ship = ship;
            this.portal = portal;
            this.source = source;
            this.dest = dest;
            this.bodyId = ship.getRuntimeId();
            this.isometry = isometry;
            this.normal = normal;
            this.origin = portal.getOriginPos();
        }
    }

    private static final Map<Key, Session> SESSIONS = new HashMap<>();

    private IPStraddleSessions() {
    }

    /**
     * @return whether a ship of {@code level} straddles a portal out of it
     */
    static boolean anyFrom(final net.minecraft.world.level.Level level) {
        for (final Session session : SESSIONS.values()) {
            if (session.source == level) {
                return true;
            }
        }
        return false;
    }

    static boolean has(final Key key) {
        return SESSIONS.containsKey(key);
    }

    @Nullable
    static Session get(final Key key) {
        return SESSIONS.get(key);
    }

    static List<Key> keys() {
        return new ArrayList<>(SESSIONS.keySet());
    }

    static void forEach(final Consumer<Session> visitor) {
        for (final Session session : SESSIONS.values()) {
            visitor.accept(session);
        }
    }

    /**
     * Opens the session for {@code ship} straddling {@code portal}, or refreshes it when the portal moved.
     *
     * @param normal the portal's source-to-destination unit normal
     * @return whether a session exists afterwards
     */
    static boolean openOrRefresh(final ServerSubLevel ship, final Portal portal, final ServerLevel source, final Vec3 normal,
                                 final long tick) {
        final Key key = new Key(ship.getUniqueId(), portal.getUUID());
        final Session existing = SESSIONS.get(key);
        if (existing != null) {
            if (existing.ship != ship || existing.bodyId != ship.getRuntimeId()) {
                // the ship was reloaded (new body): its old image died with the old body
                close(key, "ship reloaded");
            } else {
                refreshIfMoved(existing, portal, normal);
                return true;
            }
        }

        final ServerLevel dest = source.getServer().getLevel(portal.getDestDim());
        if (dest == null) {
            return false;
        }
        final long sourceScene = RapierExt.sceneHandle(source);
        final long destScene = RapierExt.sceneHandle(dest);
        if (sourceScene == 0L || destScene == 0L) {
            return false;
        }

        final IPPortalIsometry isometry = IPPortalIsometry.of(portal);
        checkAgainstPortal(isometry, portal);
        final Session session = new Session(key, ship, portal, source, dest, isometry, normal);
        session.openedTick = tick;
        final Vec3 t = isometry.translation();
        final Quaterniondc q = isometry.rotation();
        // Created through the destination's chart view: the image pairs with that chart's terrain and bodies. Body
        // lookup is world-wide, and the image reads the ship's blocks from the body's own (source) chart.
        session.imageHandle = RapierExt.createImageCollider(destScene, session.bodyId, t.x, t.y, t.z, q.x(), q.y(), q.z(), q.w());
        if (session.imageHandle < 0L) {
            LOGGER.warn("[IPStraddle] could not create the image of ship {} (body {}) in {}; no session",
                    ship.getUniqueId(), session.bodyId, dest.dimension().location());
            return false;
        }
        SESSIONS.put(key, session);
        applyClipRegions(session, portal);

        LOGGER.info("[IPStraddle] ship {} straddles portal {} ({} -> {}, facing {}){}: image collider {}",
                ship.getUniqueId(), portal.getUUID(), source.dimension().location(), dest.dimension().location(),
                portal.getNormal(), isIdentity(q) ? "" : " (rotated)", session.imageHandle);
        return true;
    }

    /**
     * Closes a session: removes the image and recomputes the ship's own clip regions from its remaining sessions.
     */
    static void close(final Key key, final String reason) {
        final Session session = SESSIONS.remove(key);
        if (session == null) {
            return;
        }
        final long destScene = RapierExt.sceneHandle(session.dest);
        if (destScene != 0L) {
            RapierExt.removeImageCollider(destScene, session.bodyId, session.imageHandle);
        }
        if (!session.ship.isRemoved()) {
            pushRealClipRegions(session.ship, session.source, session.bodyId);
        }
        LOGGER.info("[IPStraddle] ship {} left portal {}: {}", session.ship.getUniqueId(), session.portal.getUUID(), reason);
    }

    /**
     * Forgets every session of a ship without native calls, for a ship whose body is being removed (its images go with
     * it).
     */
    static void dropShip(final UUID shipId) {
        SESSIONS.keySet().removeIf(key -> key.ship().equals(shipId));
    }

    /**
     * Closes every session (straddling switched off).
     */
    static void closeAll(final String reason) {
        for (final Key key : keys()) {
            close(key, reason);
        }
    }

    /**
     * Forgets every session without native calls (a server stopping or starting: the scenes are or were disposed).
     */
    static void reset() {
        SESSIONS.clear();
    }

    private static void refreshIfMoved(final Session session, final Portal portal, final Vec3 normal) {
        final IPPortalIsometry fresh = IPPortalIsometry.of(portal);
        final boolean isometryMoved = !fresh.sameAs(session.isometry);
        final boolean apertureMoved = session.origin.distanceToSqr(portal.getOriginPos()) > 1.0e-10
                || session.normal.distanceToSqr(normal) > 1.0e-10;
        if (!isometryMoved && !apertureMoved) {
            return;
        }
        session.isometry = fresh;
        session.normal = normal;
        session.origin = portal.getOriginPos();
        session.version++;
        if (isometryMoved) {
            final long destScene = RapierExt.sceneHandle(session.dest);
            if (destScene != 0L) {
                final Vec3 t = fresh.translation();
                final Quaterniondc q = fresh.rotation();
                RapierExt.setImagePrefix(destScene, session.imageHandle, t.x, t.y, t.z, q.x(), q.y(), q.z(), q.w());
            }
        }
        applyClipRegions(session, portal);
    }

    /**
     * Lets the clip regions of a session reach {@code extraDepth} further past the plane: a ship waiting wholly past it
     * (held for the ships roped to it) must not meet what is behind the portal in its own world.
     */
    static void setExtraDepth(final Key key, final double extraDepth) {
        final Session session = SESSIONS.get(key);
        if (session != null && session.extraDepth != extraDepth) {
            session.extraDepth = extraDepth;
            applyClipRegions(session, session.portal);
        }
    }

    private static void applyClipRegions(final Session session, final Portal portal) {
        final double depth = clipDepth(session.ship) + session.extraDepth;
        final double halfW = portal.getWidth() * 0.5 + APERTURE_MARGIN;
        final double halfH = portal.getHeight() * 0.5 + APERTURE_MARGIN;
        session.realClip = clipRegion(session.origin, session.normal, portal.getAxisW(), portal.getAxisH(), halfW, halfH, depth);
        pushRealClipRegions(session.ship, session.source, session.bodyId);

        final IPPortalIsometry iso = session.isometry;
        final double[] imageClip = clipRegion(iso.mapPoint(session.origin), iso.mapVec(session.normal).scale(-1.0),
                iso.mapVec(portal.getAxisW()), iso.mapVec(portal.getAxisH()), halfW, halfH, depth);
        final long destScene = RapierExt.sceneHandle(session.dest);
        if (destScene != 0L) {
            RapierExt.setImageClipRegions(destScene, session.bodyId, session.imageHandle, imageClip);
        }
    }

    /**
     * The body's clip regions replace wholesale, so they are the union over all of the ship's sessions (a ship can
     * straddle two portals at once).
     */
    private static void pushRealClipRegions(final ServerSubLevel ship, final ServerLevel source, final int bodyId) {
        final List<double[]> regions = new ArrayList<>(2);
        for (final Session session : SESSIONS.values()) {
            if (session.ship == ship && session.realClip != null) {
                regions.add(session.realClip);
            }
        }
        final double[] flat = new double[regions.size() * RapierExt.CLIP_REGION_DOUBLES];
        for (int i = 0; i < regions.size(); i++) {
            System.arraycopy(regions.get(i), 0, flat, i * RapierExt.CLIP_REGION_DOUBLES, RapierExt.CLIP_REGION_DOUBLES);
        }
        final long sourceScene = RapierExt.sceneHandle(source);
        if (sourceScene != 0L) {
            RapierExt.setClipRegions(sourceScene, bodyId, flat);
        }
    }

    /**
     * How far past the plane a clip region reaches: the ship's plot diagonal plus the margin, so the cutter is a closed
     * box around what can be straddling rather than everything behind the portal.
     */
    private static double clipDepth(final ServerSubLevel ship) {
        final BoundingBox3ic box = ship.getPlot().getBoundingBox();
        final double dx = box.maxX() - box.minX() + 1.0;
        final double dy = box.maxY() - box.minY() + 1.0;
        final double dz = box.maxZ() - box.minZ() + 1.0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) + APERTURE_MARGIN;
    }

    private static double[] clipRegion(final Vec3 point, final Vec3 normal, final Vec3 axisW, final Vec3 axisH,
                                       final double halfW, final double halfH, final double maxDepth) {
        return new double[]{
                point.x, point.y, point.z,
                normal.x, normal.y, normal.z,
                axisW.x, axisW.y, axisW.z, halfW,
                axisH.x, axisH.y, axisH.z, halfH,
                CLIP_SKIN, maxDepth
        };
    }

    private static boolean isIdentity(final Quaterniondc q) {
        return Math.abs(Math.abs(q.w()) - 1.0) < 1.0e-9;
    }

    /**
     * The image's isometry is built from the portal's quaternion with JOML; IP maps entities with its own quaternion
     * code. Logs once if the two disagree for a portal (they must, or the image sits where the ship is not drawn).
     */
    private static void checkAgainstPortal(final IPPortalIsometry isometry, final Portal portal) {
        final Vec3 probe = portal.getOriginPos().add(portal.getAxisW().scale(1.7)).add(portal.getNormal().scale(-2.3)).add(0.0, 0.9, 0.0);
        final double error = isometry.mapPoint(probe).distanceTo(portal.transformPoint(probe));
        if (error > 1.0e-6 && !warnedMismatch) {
            warnedMismatch = true;
            LOGGER.warn("[IPStraddle] portal {}: image isometry and IP's portal transform differ by {} blocks (logged once)",
                    portal.getUUID(), error);
        }
    }

    private static boolean warnedMismatch;
}
