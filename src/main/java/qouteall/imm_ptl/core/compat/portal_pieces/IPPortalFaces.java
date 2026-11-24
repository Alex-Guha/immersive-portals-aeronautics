/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.portal_pieces;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a block face meets a portal, and what lies across it: the geometry every part facing a portal shares (Create's
 * shafts, pipes, tracks and belts). Server and client. Free of Create; Sable only behind {@link #SABLE}.
 */
public final class IPPortalFaces {
    /** Slack for a face lying in the portal plane or a hair past it. */
    public static final double PLANE_EPSILON = 1.0e-3;
    /** Slack for an end on a ship, whose pose and portal are worked out in doubles each tick: about 2.5 degrees... */
    public static final double SHIP_COS = 1 - 1.0e-3;
    /** ...and 1/20 block. */
    public static final double SHIP_GAP = 0.05;
    public static final boolean SABLE = ModList.get().isLoaded("sable");

    private IPPortalFaces() {
    }

    /** A portal block (IP's nether-style portals) whose portal plane faces a block face on {@code side}. Any thread. */
    public static boolean facesPortalBlock(final BlockState neighbour, final Direction side) {
        return neighbour.getBlock() == PortalPlaceholderBlock.instance
            && neighbour.getValue(PortalPlaceholderBlock.AXIS) == side.getAxis();
    }

    /**
     * Whether a block in {@code frame} (null: the world) is where its side of {@code portal} is: on the ship carrying
     * that side of a ship-mounted portal, in the world at a fixed side.
     */
    public static boolean onOwnSide(final Portal portal, final @Nullable IPPortalFrame frame) {
        return Objects.equals(frame == null ? null : frame.ship(), IPShipPortals.ownSideCarrier(portal));
    }

    /**
     * The face {@code side} of a block in world space.
     *
     * @param localNormal the face's normal in the block's own coordinates
     * @param centre the face's centre
     * @param normal the face's normal
     */
    public record Face(Vec3 localNormal, Vec3 centre, Vec3 normal, double scale) {
        /** Where a portal this face meets can be (within one of its blocks, {@code scale} world blocks). */
        public AABB search() {
            return new AABB(this.centre, this.centre.add(this.normal.scale(this.scale))).inflate(0.25 * this.scale);
        }
    }

    /** @param frame the ship whose plot holds {@code pos}, null in the world */
    public static Face face(final @Nullable IPPortalFrame frame, final BlockPos pos, final Direction side) {
        final Vec3 localNormal = Vec3.atLowerCornerOf(side.getNormal());
        final Vec3 localCentre = Vec3.atCenterOf(pos).add(localNormal.scale(0.5));
        return new Face(localNormal, frame == null ? localCentre : frame.toWorld(localCentre),
            frame == null ? localNormal : frame.toWorldDirection(localNormal).normalize(), frame == null ? 1.0 : frame.scale());
    }

    /**
     * Where a block face meets a portal, in world space.
     *
     * @param onPlane the face's centre on the portal plane
     * @param gap how far the face is in front of the plane
     * @param farOnPlane {@code onPlane} through the portal
     * @param farDir the way out of the far plane
     * @param cos how well a far end must line up with {@code farDir}
     * @param slack how far past its plane a far face may lie
     */
    public record Opening(Portal portal, Level farLevel, Vec3 onPlane, double gap, Vec3 farOnPlane, Vec3 farDir, double cos, double slack) {
    }

    /**
     * Where {@code face} meets {@code portal}: when it lies within a block in front of the plane, facing it, inside the
     * opening, the portal keeps the block grid, and the face is where its side of the portal is ({@link #onOwnSide}).
     * Else null. Each kind goes on with {@link #far}.
     */
    public static @Nullable Opening opening(final Portal portal, final @Nullable IPPortalFrame frame, final Face face) {
        final double nearScale = frame == null ? 1.0 : frame.scale();
        if (portal.isRemoved() || !blockForBlock(portal, nearScale) || !onOwnSide(portal, frame)) {
            return null;
        }
        final Level farLevel = portal.getDestWorld();
        if (farLevel == null) {
            return null;
        }
        final boolean shipInvolved = IPShipPortals.isShipMounted(portal);
        final double cos = shipInvolved ? SHIP_COS : 1 - PLANE_EPSILON;
        final double slack = shipInvolved ? SHIP_GAP : PLANE_EPSILON;
        final Vec3 normal = portal.getNormal();
        if (normal.dot(face.normal()) > -cos) {
            return null;
        }
        // in the near side's blocks (a scaled ship's are larger in the world)
        final double gap = portal.getDistanceToPlane(face.centre()) / nearScale;
        if (gap < -slack || gap >= 1 - PLANE_EPSILON) {
            return null;
        }
        final Vec3 onPlane = face.centre().subtract(normal.scale(gap * nearScale));
        if (!portal.getPortalShape().isBoxInPortalProjection(portal.getThisSideState(), AABB.ofSize(onPlane, 0.1, 0.1, 0.1))) {
            return null;
        }
        return new Opening(portal, farLevel, onPlane, gap, portal.transformPoint(onPlane), portal.transformLocalVecNonScale(face.normal()), cos, slack);
    }

    /**
     * Whether {@code portal} takes one block of its near side to one block of its far side, a side being the world or
     * the plot of a ship scaled by Create: Pocket Sized ({@code nearScale}: world blocks per near block): its scale
     * times the near side's over the far side's is 1. An unscaled portal between unscaled sides, and a portal mounted
     * on a scaled ship (a scaled portal that is one block to one block in the ship's own blocks). Parts link only
     * through such portals: through any other, a block faces part of a block or several.
     */
    public static boolean blockForBlock(final Portal portal, final double nearScale) {
        return Math.abs(portal.getScale() * nearScale / IPShipPortals.sideScale(portal, false) - 1.0) <= 1.0e-4;
    }

    /** What a kind wants of a far block. */
    @FunctionalInterface
    public interface FarBlock<T> {
        /**
         * @param frame the far block's ship, null in the world
         * @param out the way out of the far plane, in {@code frame}'s coordinates
         * @param gap how far the block's face towards the plane is past it
         * @param plane {@link Opening#farOnPlane} in {@code frame}'s coordinates
         * @return the far end, or null to look one block further
         */
        @Nullable T match(@Nullable IPPortalFrame frame, BlockPos pos, Direction out, double gap, Vec3 plane);
    }

    /**
     * The far end of {@code opening}: the block past the far plane whose face towards the plane lies within a block of
     * it, on the ship carrying the far side of the portal or in the world at a fixed side, that {@code match} accepts.
     * Both ends may be on ships, one ship or two.
     */
    public static <T> @Nullable T far(final Opening opening, final FarBlock<T> match) {
        final UUID farShip = IPShipPortals.farSideCarrier(opening.portal());
        if (farShip == null) {
            return farIn(opening, null, match);
        }
        if (!SABLE) {
            return null;
        }
        final AABB around = new AABB(opening.farOnPlane(), opening.farOnPlane().add(opening.farDir().scale(1.5))).inflate(1);
        for (final IPPortalFrame ship : IPPortalShipFrames.shipsTouching(opening.farLevel(), around)) {
            if (ship.ship().equals(farShip)) {
                final T found = farIn(opening, ship, match);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> @Nullable T farIn(final Opening opening, final @Nullable IPPortalFrame frame, final FarBlock<T> match) {
        final Vec3 dir = frame == null ? opening.farDir() : frame.toLocalDirection(opening.farDir()).normalize();
        final Vec3 plane = frame == null ? opening.farOnPlane() : frame.toLocal(opening.farOnPlane());
        final Direction out = Direction.getNearest(dir.x, dir.y, dir.z);
        final Vec3 outNormal = Vec3.atLowerCornerOf(out.getNormal());
        if (outNormal.dot(dir) < opening.cos()) {
            return null; // rotated off the block grid
        }
        for (final double reach : new double[]{0.5, 1.5}) {
            final BlockPos candidate = BlockPos.containing(plane.add(outNormal.scale(reach)));
            final double gap = Vec3.atCenterOf(candidate).subtract(outNormal.scale(0.5)).subtract(plane).dot(outNormal);
            if (gap >= -opening.slack() && gap < 1 - PLANE_EPSILON && (frame == null || frame.holds(opening.farLevel(), candidate))) {
                final T found = match.match(frame, candidate, out, gap, plane);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The nearest rotation by quarter turns. */
    public static Quaternionf snap(final Quaternionf rotation) {
        final Matrix3f matrix = new Matrix3f().rotation(rotation);
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                matrix.set(column, row, Math.round(matrix.get(column, row)));
            }
        }
        return new Quaternionf().setFromNormalized(matrix);
    }
}
