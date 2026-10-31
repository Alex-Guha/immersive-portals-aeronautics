/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplAtlasStraddleSession by r2smith141 (Apache License 2.0), re-expressed for ships that
 * stay in their own level (no hosting dimension).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalAnchors;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipaeronautics.rapier.RapierExt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
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
 *     <li>the image drops every contact before the destination plane: only the part past the plane touches the
 *     destination world ({@link #imageClipRegions}).</li>
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
        /** The portal's carrier when it is mounted on a ship ({@link IPMountedStraddle}), else null. */
        @Nullable
        final IPShipPortalAnchors.Mount mount;

        private Session(final Key key, final ServerSubLevel ship, final Portal portal, final ServerLevel source,
                        final ServerLevel dest, final IPPortalIsometry isometry, final Vec3 normal,
                        @Nullable final IPShipPortalAnchors.Mount mount) {
            this.key = key;
            this.ship = ship;
            this.portal = portal;
            this.source = source;
            this.dest = dest;
            this.bodyId = ship.getRuntimeId();
            this.isometry = isometry;
            this.normal = normal;
            this.origin = portal.getOriginPos();
            this.mount = mount;
        }
    }

    private static final Map<Key, Session> SESSIONS = new HashMap<>();
    /**
     * Frame blocks a ship stuck in a portal's frame is let through until it is free of them (world boxes of its own
     * level, grown by {@link #GHOST_MARGIN}; {@code IPStraddleController}): their contacts are dropped for its body and,
     * mapped through each of its portals, for its images (the far portal's frame lies there).
     */
    private static final Map<UUID, List<AABB>> GHOSTS = new HashMap<>();
    /** How far [blocks] around a let-through frame block contacts are dropped too (contact points sit between surfaces). */
    private static final double GHOST_MARGIN = 0.1;

    private IPStraddleSessions() {
    }

    static boolean anyOfShip(final UUID shipId) {
        return SESSIONS.keySet().stream().anyMatch(key -> key.ship().equals(shipId));
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

        // a ship carrying portals going through a portal shatters them first (docs/plans/ship-mounted-portals.md)
        IPShipPortalAnchors.shatterCarriedPortals(ship.getUniqueId(), "its ship is going through a portal");
        final IPPortalIsometry isometry = IPPortalIsometry.of(portal);
        checkAgainstPortal(isometry, portal);
        final IPShipPortalAnchors.Mount mount = IPShipPortalAnchors.mountFor(portal);
        final Session session = new Session(key, ship, portal, source, dest, isometry, normal, mount);
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
        IPMountedStraddle.sendAnchor(session, destScene);
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
        GHOSTS.clear();
    }

    private static void refreshIfMoved(final Session session, final Portal portal, final Vec3 normal) {
        final IPPortalIsometry fresh = IPPortalIsometry.of(portal);
        final boolean isometryMoved = !fresh.sameAs(session.isometry);
        final boolean apertureMoved = session.origin.distanceToSqr(portal.getOriginPos()) > 1.0e-10
                || session.normal.distanceToSqr(normal) > 1.0e-10;
        // a mounted portal's transforms are in its carrier's body frame, which Sable re-bases when the carrier's mass
        // changes: re-sent every tick
        if (!isometryMoved && !apertureMoved && session.mount == null) {
            return;
        }
        session.isometry = fresh;
        session.normal = normal;
        session.origin = portal.getOriginPos();
        if (session.mount == null) {
            // clients derive a mounted session's geometry from the carrier they draw (IPStraddleSync.sendMount)
            session.version++;
        }
        if (session.mount != null) {
            // the native side moves the image with the carrier every step; only the body-frame transform is refreshed
            IPMountedStraddle.sendAnchor(session, RapierExt.sceneHandle(session.dest));
        } else if (isometryMoved) {
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
        final double[] imageClip = imageClipRegions(iso.mapPoint(session.origin), iso.mapVec(session.normal),
                iso.mapVec(portal.getAxisW()), iso.mapVec(portal.getAxisH()), halfW, halfH, depth);
        final long destScene = RapierExt.sceneHandle(session.dest);
        if (destScene != 0L) {
            final double[] regions = concat(imageClip, ghostRegions(session.ship.getUniqueId(), iso));
            if (session.mount != null && session.mount.destination() != null) {
                // the destination plane is on a carrier: the image's regions follow it
                final ServerSubLevel carrier = session.mount.destination();
                RapierExt.setAnchoredClipRegions(destScene, session.bodyId, session.imageHandle,
                        carrier.getRuntimeId(), IPMountedStraddle.toCarrierFrame(carrier, regions));
            } else {
                RapierExt.setImageClipRegions(destScene, session.bodyId, session.imageHandle, regions);
            }
        }
    }

    /**
     * Lets {@code ship} through the given frame blocks of its own level (world block positions) until the next call;
     * an empty list ends it. Re-sends the clip regions of its body and of its images.
     */
    static void setGhosts(final ServerSubLevel ship, final ServerLevel level, final List<net.minecraft.core.BlockPos> frameBlocks) {
        final UUID id = ship.getUniqueId();
        if (frameBlocks.isEmpty()) {
            if (GHOSTS.remove(id) == null) {
                return;
            }
        } else {
            final List<AABB> boxes = new ArrayList<>(frameBlocks.size());
            for (final net.minecraft.core.BlockPos pos : frameBlocks) {
                boxes.add(new AABB(pos).inflate(GHOST_MARGIN));
            }
            GHOSTS.put(id, boxes);
        }
        boolean anySession = false;
        for (final Session session : SESSIONS.values()) {
            if (session.ship == ship) {
                anySession = true;
                applyClipRegions(session, session.portal);
            }
        }
        if (!anySession && !ship.isRemoved()) {
            pushRealClipRegions(ship, level, ship.getRuntimeId());
        }
    }

    static boolean hasGhosts(final UUID shipId) {
        return GHOSTS.containsKey(shipId);
    }

    /** Drops the let-through frame blocks of every ship not in {@code live}, without native calls (their bodies are gone). */
    static void retainGhosts(final java.util.Set<UUID> live) {
        GHOSTS.keySet().retainAll(live);
    }

    /**
     * Clip regions covering a ship's let-through frame blocks, in its own level or, with {@code isometry}, mapped into a
     * portal's destination.
     */
    private static double[] ghostRegions(final UUID shipId, @Nullable final IPPortalIsometry isometry) {
        final List<AABB> boxes = GHOSTS.get(shipId);
        if (boxes == null) {
            return new double[0];
        }
        final double[] flat = new double[boxes.size() * RapierExt.CLIP_REGION_DOUBLES];
        for (int i = 0; i < boxes.size(); i++) {
            final AABB box = boxes.get(i);
            Vec3 point = new Vec3(box.minX, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5);
            Vec3 normal = new Vec3(1.0, 0.0, 0.0);
            Vec3 axisW = new Vec3(0.0, 1.0, 0.0);
            Vec3 axisH = new Vec3(0.0, 0.0, 1.0);
            if (isometry != null) {
                point = isometry.mapPoint(point);
                normal = isometry.mapVec(normal);
                axisW = isometry.mapVec(axisW);
                axisH = isometry.mapVec(axisH);
            }
            System.arraycopy(clipRegion(point, normal, axisW, axisH, box.getYsize() * 0.5, box.getZsize() * 0.5, box.getXsize()),
                    0, flat, i * RapierExt.CLIP_REGION_DOUBLES, RapierExt.CLIP_REGION_DOUBLES);
        }
        return flat;
    }

    private static double[] concat(final double[] a, final double[] b) {
        if (b.length == 0) {
            return a;
        }
        final double[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * The image's clip region: everything before the destination plane, not only the mapped opening (as IPSable). The
     * part of the ship before the plane is drawn in, and touches, its own world only; with the opening alone, a ship
     * wider than it touched whatever lies behind the destination portal (a ship resting on a frame was held up by
     * terrain behind the far portal, 2.0.7). Past the plane the image keeps colliding everywhere, as the far half is
     * drawn past the whole plane.
     *
     * @param point  the destination plane's origin
     * @param normal the destination plane's normal pointing away from the portal into the destination world
     * @param halfW  half the clipped aperture's width (opening plus margin)
     * @param depth  how far the region reaches (the ship cannot reach further from the plane)
     */
    private static double[] imageClipRegions(final Vec3 point, final Vec3 normal, final Vec3 axisW, final Vec3 axisH,
                                             final double halfW, final double halfH, final double depth) {
        return clipRegion(point, normal.scale(-1.0), axisW, axisH, halfW + depth, halfH + depth, depth);
    }


    /**
     * The body's clip regions replace wholesale, so they are the union over all of the ship's sessions (a ship can
     * straddle two portals at once).
     */
    private static void pushRealClipRegions(final ServerSubLevel ship, final ServerLevel source, final int bodyId) {
        final List<double[]> regions = new ArrayList<>(2);
        ServerSubLevel carrier = null;
        for (final Session session : SESSIONS.values()) {
            if (session.ship == ship && session.realClip != null) {
                regions.add(session.realClip);
                if (session.mount != null && session.mount.source() != null) {
                    carrier = session.mount.source();
                }
            }
        }
        final double[] flat = new double[regions.size() * RapierExt.CLIP_REGION_DOUBLES];
        for (int i = 0; i < regions.size(); i++) {
            System.arraycopy(regions.get(i), 0, flat, i * RapierExt.CLIP_REGION_DOUBLES, RapierExt.CLIP_REGION_DOUBLES);
        }
        final long sourceScene = RapierExt.sceneHandle(source);
        if (sourceScene != 0L) {
            final double[] all = concat(flat, ghostRegions(ship.getUniqueId(), null));
            if (carrier != null) {
                // a portal of this ship's sessions moves with its carrier: the body's regions follow the carrier (a second,
                // still portal straddled at the same time would follow it too: accepted, it is re-sent every tick)
                RapierExt.setAnchoredClipRegions(sourceScene, bodyId, -1L, carrier.getRuntimeId(), IPMountedStraddle.toCarrierFrame(carrier, all));
            } else {
                RapierExt.setClipRegions(sourceScene, bodyId, all);
            }
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
