/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipMount;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * A portal as a carriage's track sees it. A carriage on a ship stands in the ship's plot, about 2e7 from the world:
 * its anchors, wheel points and track nodes are all plot coordinates, while the portal entity stands in the world and
 * moves with the ship. Both are rigid against each other, so everything a crossing needs is constant in the plot; this
 * gives it in the track's coordinates on each side ({@link #near}: this side's ship, {@link #far}: the other side's,
 * null for a side fixed in the world, where the track's coordinates are the portal's own).
 * <p>
 * The plane comes from the mount, not from the portal entity through the ship's pose: on the client those two are a
 * frame apart on a moving ship, and the carriage would wobble against its own cut.
 */
public final class IPCreateTrainPortal {
    /** A portal normal this close to a plot axis lies on it (ship poses are worked out in doubles each tick). */
    private static final double AXIS_COS = 1.0 - 1.0e-3;

    private final Portal portal;
    private final IPCreatePortalShafts.@Nullable Frame near;
    private final IPCreatePortalShafts.@Nullable Frame far;

    private IPCreateTrainPortal(final Portal portal, final IPCreatePortalShafts.@Nullable Frame near,
                                final IPCreatePortalShafts.@Nullable Frame far) {
        this.portal = portal;
        this.near = near;
        this.far = far;
    }

    /** The frame of the track at {@code position} in {@code level}: its ship, or null in the world. */
    static IPCreatePortalShafts.@Nullable Frame frameAt(final Level level, final Vec3 position) {
        return IPCreatePortalEnds.SABLE ? IPCreateShaftShips.frameAt(level, BlockPos.containing(position)) : null;
    }

    /** @return {@code portal} seen from a track in {@code near}, or null when its far ship is not loaded here */
    static @Nullable IPCreateTrainPortal of(final Portal portal, final IPCreatePortalShafts.@Nullable Frame near) {
        final UUID farShip = IPShipPortals.farSideCarrier(portal);
        if (farShip == null) {
            return new IPCreateTrainPortal(portal, near, null);
        }
        final Level farLevel = IPCreatePortalEnds.SABLE ? portal.getDestWorld() : null;
        final IPCreatePortalShafts.Frame far = farLevel == null ? null : IPCreateShaftShips.frameOf(farLevel, farShip);
        return far == null ? null : new IPCreateTrainPortal(portal, near, far);
    }

    public Portal portal() {
        return portal;
    }

    /** Whether the track on this side is on a ship. */
    public boolean isOnShip() {
        return near != null;
    }

    /** Whether the track on either side is on a ship. */
    public boolean isThroughShip() {
        return near != null || far != null;
    }

    /** A point of the track's coordinates on this side, in the world. */
    public Vec3 toWorld(final Vec3 point) {
        return near == null ? point : near.toWorld(point);
    }

    /** A direction of the track's coordinates on this side, in the world. */
    public Vec3 toWorldDirection(final Vec3 direction) {
        return near == null ? direction : near.toWorldDirection(direction).normalize();
    }

    /** The plane's point, in the track's coordinates on this side. */
    public Vec3 origin() {
        if (near == null) {
            return portal.getOriginPos();
        }
        final IPShipMount mount = IPShipPortals.ownSideMount(portal);
        return mount != null ? mount.plotOrigin() : near.toLocal(portal.getOriginPos());
    }

    /** The plane's normal (the portal's facing), in the track's coordinates on this side. */
    public Vec3 normal() {
        if (near == null) {
            return portal.getNormal();
        }
        final Vec3 local = near.toLocalDirection(portal.getNormal()).normalize();
        final Vec3 axis = Vec3.atLowerCornerOf(Direction.getNearest(local.x, local.y, local.z).getNormal());
        return axis.dot(local) > AXIS_COS ? axis : local;
    }

    /** Signed distance of a point of the track's coordinates from the plane, positive in front of the portal. */
    public double distanceToPlane(final Vec3 point) {
        return point.subtract(origin()).dot(normal());
    }

    /** How far a point of the track's coordinates is from the portal's opening. */
    public double distanceToOpening(final Vec3 point) {
        return portal.getDistanceToNearestPointInPortal(toWorld(point));
    }

    /** A point of the track's coordinates on this side, through the portal, in the track's coordinates on the other. */
    public Vec3 through(final Vec3 point) {
        final Vec3 mapped = portal.transformPoint(toWorld(point));
        return far == null ? mapped : far.toLocal(mapped);
    }

    /**
     * Where a rider at {@code seat} comes out. A rider stands in the ship's plot while seated on a carriage there, and
     * in the world everywhere else, whichever side the track is on.
     */
    public Vec3 riderThrough(final Vec3 seat) {
        if (near != null && IPCreatePortalEnds.SABLE && IPCreateShaftShips.frameAt(portal.level(), net.minecraft.core.BlockPos.containing(seat)) != null) {
            return through(seat);
        }
        final Vec3 mapped = portal.transformPoint(seat);
        return far == null ? mapped : far.toLocal(mapped);
    }

    /** The reverse of {@link #through}. */
    public Vec3 back(final Vec3 farPoint) {
        final Vec3 mapped = portal.inverseTransformPoint(far == null ? farPoint : far.toWorld(farPoint));
        return near == null ? mapped : near.toLocal(mapped);
    }

    /** A direction of the track's coordinates on this side, through the portal, in the other side's. */
    public Vec3 throughDirection(final Vec3 direction) {
        final Vec3 mapped = portal.transformLocalVecNonScale(toWorldDirection(direction));
        return far == null ? mapped : far.toLocalDirection(mapped).normalize();
    }
}
