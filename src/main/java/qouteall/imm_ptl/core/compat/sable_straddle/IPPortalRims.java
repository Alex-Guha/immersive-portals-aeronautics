/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The rim follows IPSable 0.5.0's IplStraddlePortalRim by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipaeronautics.rapier.RapierExt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalAnchors;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rims of frameless portals (docs/lessons/straddle-sessions.md, "Portal rims"): an invisible frame hugging the
 * opening's edge, in the physics world only (players and other entities never touch it). A straddling ship rests
 * against the edge instead of sliding out of the opening sideways, and a ship wider than the opening stops at it, as at
 * a nether portal's frame.
 * <p>
 * It is shaped like a nether portal's frame along the normal ({@value #HALF_THICKNESS} on each side of the plane) but
 * only {@value #WIDTH} wide outward. The thickness is what stops a ship: Sable collides each ship block on its own, and a
 * block whose face lies on the plane (an axis-aligned ship at a portal on block boundaries) is pushed off a thin rim
 * along the normal rather than sideways. Like a frame, its half past the plane is cut for a straddling ship's body by
 * the clip regions (the reverse portal's rim holds the far half, as the far frame does for nether portals). The natives
 * give ship blocks against a rim Sable's interior check and the ship's full extent (natives/README.md); frame contact
 * ({@link IPShipCrossingDetector#frameContact}) counts it like frame blocks.
 * <p>
 * Only for portals without frame blocks (not {@link BreakablePortalEntity}: nether portals and datapack portals have
 * frames), not for portals whose edges have solid blocks on both sides of the plane all
 * around (a frame of placed blocks, in the world or on a ship), not for a portal set into a floor or wall (terrain on one side of the plane along
 * an edge: that terrain holds a straddling ship already, and the rim would stand out of it as a half-block curb), one
 * rim per opening (the two faces of a two-sided portal share it), and only while a ship is near: {@link #need} keeps a
 * rim for {@value #KEEP_TICKS} ticks. A ship let through a stuck frame is let through every rim of its level too
 * ({@link #setLetThrough}).
 * <p>
 * A custom portal mounted on a ship (docs/plans/custom-mounts.md): its fixed side gets an ordinary rim. Its ship side's
 * rim is anchored to the carrier in the engine ({@link RapierExt#setPortalRimAnchor}), so it moves with the carrier
 * through every substep, and never touches the carrier itself; whether it needs one is judged in the carrier's plot (its
 * hull is the terrain there). A ship-side portal whose carrier is not loaded gets none.
 */
final class IPPortalRims {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** How far [blocks] the rim reaches outward from the opening's edge. */
    private static final double WIDTH = 0.25;
    /** Half the rim's thickness [blocks] along the portal normal: a nether portal frame's. */
    private static final double HALF_THICKNESS = 0.5;
    /** A rim no ship came near for this many ticks is removed. */
    private static final long KEEP_TICKS = 100;
    /** Test switch: {@code -Dip_aeronautics.portalRims=false} creates no rims. */
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ip_aeronautics.portalRims"));

    private static final class Rim {
        final Portal portal;
        final ServerLevel level;
        final PhysicsPipeline pipeline;
        final long scene;
        final int id;
        final double width;
        final double height;
        /** The ship carrying the portal (its ship side), or null for a rim fixed in the world. */
        @Nullable
        final ServerSubLevel carrier;
        Vec3 origin;
        Vec3 axisW;
        Vec3 axisH;
        long neededTick;

        Rim(final Portal portal, final ServerLevel level, final PhysicsPipeline pipeline, final long scene, final int id,
            @Nullable final ServerSubLevel carrier) {
            this.portal = portal;
            this.level = level;
            this.pipeline = pipeline;
            this.scene = scene;
            this.id = id;
            this.width = portal.getWidth();
            this.height = portal.getHeight();
            this.carrier = carrier;
        }
    }

    /**
     * One bar of a rim in world space: centre, unit axes and half extents along them.
     */
    record Bar(Vec3 center, Vec3[] axes, double[] halves) {
        /** Half the bar's extent along unit vector {@code u}. */
        double support(final Vec3 u) {
            return this.halves[0] * Math.abs(this.axes[0].dot(u)) + this.halves[1] * Math.abs(this.axes[1].dot(u))
                    + this.halves[2] * Math.abs(this.axes[2].dot(u));
        }
    }

    /** Rims by portal entity. */
    private static final Map<UUID, Rim> RIMS = new HashMap<>();
    /** Ships let through a portal frame ({@code IPStraddleController}), by id: their bodies pass through rims too. */
    private static final Map<UUID, ServerSubLevel> LET_THROUGH = new HashMap<>();
    /** Portals found to need no rim ({@link #noRimReason}), with the tick until which they are not checked again. */
    private static final Map<UUID, Long> NO_RIM = new HashMap<>();

    private IPPortalRims() {
    }

    /**
     * Whether {@code portal} gets a rim: no frame blocks, a plain portal entity (not a global one), and a portal ships
     * straddle ({@link IPStraddleController#isShipPortal}).
     */
    static boolean wants(final Portal portal) {
        return ENABLED && !(portal instanceof BreakablePortalEntity) && !portal.getIsGlobal()
                && IPStraddleController.isShipPortal(portal);
    }

    /**
     * A ship is near {@code portal}: makes sure it has a rim (if it {@link #wants} one) for the next
     * {@value #KEEP_TICKS} ticks.
     */
    static void need(final ServerLevel level, final Portal portal, final long tick) {
        if (!wants(portal)) {
            return;
        }
        final Rim existing = RIMS.get(portal.getUUID());
        if (existing != null) {
            existing.neededTick = tick;
            return;
        }
        final Long noRimUntil = NO_RIM.get(portal.getUUID());
        if (sharesOpening(portal) || (noRimUntil != null && tick < noRimUntil)) {
            return;
        }
        final IPShipPortalAnchors.Mount mount = IPShipPortalAnchors.mountFor(portal);
        if (mount == null && IPShipPortals.isShipMounted(portal)) {
            return; // its ship is not loaded
        }
        final ServerSubLevel carrier = mount == null ? null : mount.source();
        final String noRim = noRimReason(level, portal, carrier);
        if (noRim != null) {
            NO_RIM.put(portal.getUUID(), tick + KEEP_TICKS);
            LOGGER.debug("[IPStraddle] no rim for portal {}: {}", portal.getUUID(), noRim);
            return;
        }
        final PhysicsPipeline pipeline = pipelineOf(level);
        final long scene = RapierExt.sceneHandle(level);
        if (pipeline == null || scene == 0L) {
            return;
        }
        final int id = RapierExt.createPortalRim(scene, portal.getWidth(), portal.getHeight(), WIDTH, HALF_THICKNESS);
        if (id == -1) {
            LOGGER.warn("[IPStraddle] no rim for portal {} ({} x {})", portal.getUUID(), portal.getWidth(), portal.getHeight());
            return;
        }
        final Rim rim = new Rim(portal, level, pipeline, scene, id, carrier);
        rim.neededTick = tick;
        place(rim);
        if (carrier != null) {
            RapierExt.setBodyPairExclusion(scene, id, carrier.getRuntimeId(), true);
            anchor(rim, carrier);
        }
        for (final ServerSubLevel ship : LET_THROUGH.values()) {
            if (ship.getLevel() == level && ship != carrier) {
                RapierExt.setBodyPairExclusion(scene, id, ship.getRuntimeId(), true);
            }
        }
        RIMS.put(portal.getUUID(), rim);
        LOGGER.debug("[IPStraddle] rim {} around portal {} ({} x {})", id, portal.getUUID(), rim.width, rim.height);
    }

    /**
     * Once per tick after the straddle decisions: follows portals that moved, rebuilds rims of resized ones, removes
     * rims of removed portals and of portals no ship came near for {@value #KEEP_TICKS} ticks.
     */
    static void tick(final long tick) {
        NO_RIM.values().removeIf(until -> tick >= until);
        final Iterator<Rim> it = RIMS.values().iterator();
        while (it.hasNext()) {
            final Rim rim = it.next();
            if (!isLive(rim)) {
                // the level's physics scene is gone, and the rim with it
                it.remove();
                continue;
            }
            if (rim.portal.isRemoved() || tick - rim.neededTick > KEEP_TICKS || !wants(rim.portal)
                    || rim.portal.getWidth() != rim.width || rim.portal.getHeight() != rim.height
                    || carrierOf(rim.portal) != rim.carrier) {
                RapierExt.removePortalRim(rim.scene, rim.id);
                it.remove();
                continue;
            }
            // an anchored rim follows its carrier in the engine
            if (rim.carrier == null && (!rim.portal.getOriginPos().equals(rim.origin) || !rim.portal.getAxisW().equals(rim.axisW)
                    || !rim.portal.getAxisH().equals(rim.axisH))) {
                place(rim);
            }
        }
    }

    /**
     * Lets {@code ship} through every rim of its level ({@code on}), or no longer: a ship stuck in a portal's frame is let
     * through the frame blocks, and a rim inside them would still hold it.
     */
    static void setLetThrough(final ServerSubLevel ship, final boolean on) {
        if (on ? LET_THROUGH.put(ship.getUniqueId(), ship) != null : LET_THROUGH.remove(ship.getUniqueId()) == null) {
            return;
        }
        for (final Rim rim : RIMS.values()) {
            // a carrier never touches its own portal's rim, let through or not
            if (rim.level == ship.getLevel() && rim.carrier != ship && isLive(rim)) {
                RapierExt.setBodyPairExclusion(rim.scene, rim.id, ship.getRuntimeId(), on);
            }
        }
    }

    /** Forgets the let-through ships not in {@code live} (their bodies are gone). */
    static void retainLetThrough(final java.util.Set<UUID> live) {
        LET_THROUGH.keySet().retainAll(live);
    }

    /** Removes every rim (straddling switched off). */
    static void clearAll() {
        for (final Rim rim : RIMS.values()) {
            if (isLive(rim)) {
                RapierExt.removePortalRim(rim.scene, rim.id);
            }
        }
        RIMS.clear();
    }

    /** A server starts: forgets the previous server's rims (their scenes are gone). */
    static void reset() {
        RIMS.clear();
        LET_THROUGH.clear();
        NO_RIM.clear();
    }

    /**
     * The bars of the rim around {@code portal}'s opening (its own or its other face's), as the natives build them; empty
     * when it has none.
     */
    static List<Bar> bars(final Portal portal) {
        if (!RIMS.containsKey(portal.getUUID()) && !sharesOpening(portal)) {
            return List.of();
        }
        final Vec3 origin = portal.getOriginPos();
        final Vec3 w = portal.getAxisW().normalize();
        final Vec3 h = portal.getAxisH().normalize();
        final Vec3 n = portal.getNormal().normalize();
        final double halfW = portal.getWidth() * 0.5;
        final double halfH = portal.getHeight() * 0.5;
        final Vec3[] axes = {w, n, h};
        final double side = WIDTH * 0.5;
        return List.of(
                new Bar(origin.add(w.scale(halfW + side)), axes, new double[]{side, HALF_THICKNESS, halfH + WIDTH}),
                new Bar(origin.add(w.scale(-halfW - side)), axes, new double[]{side, HALF_THICKNESS, halfH + WIDTH}),
                new Bar(origin.add(h.scale(halfH + side)), axes, new double[]{halfW, HALF_THICKNESS, side}),
                new Bar(origin.add(h.scale(-halfH - side)), axes, new double[]{halfW, HALF_THICKNESS, side}));
    }

    /** Whether another portal face with the same opening (the other face of a two-sided portal) has a rim. */
    private static boolean sharesOpening(final Portal portal) {
        for (final Rim rim : RIMS.values()) {
            final Portal other = rim.portal;
            if (other != portal && other.level() == portal.level() && other.getOriginPos().distanceToSqr(portal.getOriginPos()) < 1.0E-8
                    && Math.abs(other.getNormal().dot(portal.getNormal())) > 1.0 - 1.0E-6
                    && Math.abs(other.getWidth() - portal.getWidth()) < 1.0E-6 && Math.abs(other.getHeight() - portal.getHeight()) < 1.0E-6
                    && Math.abs(Math.abs(other.getAxisW().dot(portal.getAxisW())) - 1.0) < 1.0E-6) {
                return true;
            }
        }
        return false;
    }

    /** The ship carrying {@code portal}'s side (loaded), or null for a portal fixed in the world. */
    @Nullable
    private static ServerSubLevel carrierOf(final Portal portal) {
        final IPShipPortalAnchors.Mount mount = IPShipPortalAnchors.mountFor(portal);
        return mount == null ? null : mount.source();
    }

    /**
     * Why {@code portal} needs no rim, or null. Samples the world just outside its edges (half a block out, a quarter of
     * a block before and past the plane, every block along each edge): terrain on one side of the plane only anywhere is
     * a portal set into a floor or wall; solid blocks on both sides all around is a frame. A portal on {@code carrier} is
     * sampled in its plot, where the carrier's own blocks are the terrain.
     */
    @Nullable
    private static String noRimReason(final ServerLevel level, final Portal portal, @Nullable final ServerSubLevel carrier) {
        Vec3 origin = portal.getOriginPos();
        Vec3 normal = portal.getNormal().normalize();
        Vec3 axisW = portal.getAxisW().normalize();
        Vec3 axisH = portal.getAxisH().normalize();
        if (carrier != null) {
            final Pose3dc pose = carrier.logicalPose();
            origin = pose.transformPositionInverse(origin);
            normal = pose.transformNormalInverse(normal);
            axisW = pose.transformNormalInverse(axisW);
            axisH = pose.transformNormalInverse(axisH);
        }
        final double halfW = portal.getWidth() * 0.5;
        final double halfH = portal.getHeight() * 0.5;
        final int[] samples = new int[3]; // one side solid, both sides solid, all
        sampleEdge(level, origin.add(axisW.scale(halfW)), axisW, normal, axisH, halfH, samples);
        sampleEdge(level, origin.add(axisW.scale(-halfW)), axisW.scale(-1.0), normal, axisH, halfH, samples);
        sampleEdge(level, origin.add(axisH.scale(halfH)), axisH, normal, axisW, halfW, samples);
        sampleEdge(level, origin.add(axisH.scale(-halfH)), axisH.scale(-1.0), normal, axisW, halfW, samples);
        if (samples[0] > 0) {
            return "it is set into terrain";
        }
        return samples[1] == samples[2] ? "it has a frame" : null;
    }

    /** {@link #noRimReason} along one edge, given by its centre, the outward axis and the axis along it. */
    private static void sampleEdge(final ServerLevel level, final Vec3 centre, final Vec3 outward, final Vec3 normal,
                                   final Vec3 along, final double halfLength, final int[] samples) {
        final int steps = (int) Math.max(1, Math.min(64, Math.ceil(2.0 * halfLength)));
        for (int i = 0; i <= steps; i++) {
            final Vec3 point = centre.add(outward.scale(0.5)).add(along.scale(halfLength * (2.0 * i / steps - 1.0)));
            boolean front = isSolid(level, point.add(normal.scale(0.25)));
            boolean back = isSolid(level, point.add(normal.scale(-0.25)));
            // a ship's blocks on both sides frame the portal too (a custom portal unmounted from a ship's hull); on one
            // side only they are a ship resting on the rim, which must keep it
            if (!front && !back && isShipSolid(level, point.add(normal.scale(0.25))) && isShipSolid(level, point.add(normal.scale(-0.25)))) {
                front = true;
                back = true;
            }
            if (front != back) {
                samples[0]++;
            } else if (front) {
                samples[1]++;
            }
            samples[2]++;
        }
    }

    private static boolean isSolid(final ServerLevel level, final Vec3 point) {
        final net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(point);
        return level.isLoaded(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isShipSolid(final ServerLevel level, final Vec3 point) {
        for (final SubLevel ship : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(point, point))) {
            final net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(ship.logicalPose().transformPositionInverse(point));
            if (!ship.isRemoved() && level.isLoaded(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLive(final Rim rim) {
        return pipelineOf(rim.level) == rim.pipeline && RapierExt.sceneHandle(rim.level) == rim.scene;
    }

    @Nullable
    private static PhysicsPipeline pipelineOf(final ServerLevel level) {
        final SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        return system == null ? null : system.getPipeline();
    }

    /**
     * Puts the rim at its portal: local X along the width, Y along the portal's normal, centred on the plane.
     */
    private static void place(final Rim rim) {
        final Portal portal = rim.portal;
        rim.origin = portal.getOriginPos();
        rim.axisW = portal.getAxisW();
        rim.axisH = portal.getAxisH();
        final Quaterniond rotation = rotationOf(rim);
        RapierExt.setPortalRimTransform(rim.scene, rim.id, rim.origin.x, rim.origin.y, rim.origin.z,
                rotation.x, rotation.y, rotation.z, rotation.w);
    }

    /**
     * The rim's world rotation as {@link #place} puts it, the same for both faces of a doorway (local Y along the
     * normal's dominant axis, positive). The two faces' rims are the same bars, yet a sphere resting half through a
     * two-faced portal with its block faces on the plane was thrown out with the rim built from one face and held with
     * the other's (contact points on the plane, the clip regions' skin deciding by rounding): which face got the rim
     * went by UUID order, so it happened at random.
     */
    private static Quaterniond rotationOf(final Rim rim) {
        Vec3 x = rim.axisW.normalize();
        Vec3 y = rim.portal.getNormal().normalize();
        final double ax = Math.abs(y.x), ay = Math.abs(y.y), az = Math.abs(y.z);
        if ((ay >= ax && ay >= az ? y.y : ax >= az ? y.x : y.z) < 0.0) {
            x = x.scale(-1.0);
            y = y.scale(-1.0);
        }
        final Vec3 z = x.cross(y).normalize();
        return new Quaterniond().setFromNormalized(new Matrix3d(
                x.x, x.y, x.z,
                y.x, y.y, y.z,
                z.x, z.y, z.z));
    }

    /**
     * Anchors a placed rim to {@code carrier}: its pose in the carrier's body frame, {@code B⁻¹ ∘ R}
     * (docs/lessons/ship-mounted-portals.md: the body frame is {@code logicalPose}'s position and orientation).
     */
    private static void anchor(final Rim rim, final ServerSubLevel carrier) {
        final Pose3dc pose = carrier.logicalPose();
        final Quaterniond inverse = new Quaterniond(pose.orientation()).conjugate();
        final Vector3d t = inverse.transform(new Vector3d(rim.origin.x - pose.position().x(),
                rim.origin.y - pose.position().y(), rim.origin.z - pose.position().z()));
        final Quaterniond q = inverse.mul(rotationOf(rim)).normalize();
        RapierExt.setPortalRimAnchor(rim.scene, rim.id, carrier.getRuntimeId(), t.x, t.y, t.z, q.x, q.y, q.z, q.w);
    }
}
