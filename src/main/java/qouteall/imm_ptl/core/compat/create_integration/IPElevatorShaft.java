/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.animation.UnilateralPortalState;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * The portal an elevator's shaft runs through (docs/plans/elevators.md): the first floor portal below the pulley whose
 * opening holds the whole car, unscaled, turned by whole quarter turns about the vertical at most, mapping blocks onto
 * blocks. Below its plane the shaft goes on in {@link #farDim}: a point p of the pulley's frame is at
 * {@code turn(p) + shift} there ({@link #toFar}), so a far floor at y lies at {@code y - shift.y} in the pulley's frame.
 * Positions are in each side's own frame ({@link IPElevatorFrames}): a ship's plot for a side on a ship, so for a ship's
 * portal the shift is between plot coordinates. A portal comes to be turned when a ship carrying one of its ends is
 * disassembled: Create Aeronautics turns the ship to the nearest quarter turn as it places it.
 *
 * @param turns   quarter turns about +y from the pulley's frame to the far frame, 0 to 3
 * @param bottomY the lowest y in the pulley's frame the car may reach: the far level's floor, mapped back
 */
public record IPElevatorShaft(UUID portalId, ResourceKey<Level> farDim, double planeY, BlockPos shift, int turns, int bottomY) {
    private static final double EPS = 1.0e-4;

    /** The turn of {@code turns} quarter turns about +y, as IP's portals express rotations. */
    public static DQuaternion turnOf(final int turns) {
        return DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), 90.0 * turns);
    }

    /** {@code v} turned by the shaft's turn (a direction or offset; no shift). */
    public Vec3 turn(final Vec3 v) {
        return turns == 0 ? v : clean(turnOf(turns).rotate(v));
    }

    /** A turned value without the quaternion's rounding error (a whole-block place came out a hair short of it). */
    private static Vec3 clean(final Vec3 v) {
        return new Vec3(Math.rint(v.x * 1.0e9) / 1.0e9, Math.rint(v.y * 1.0e9) / 1.0e9, Math.rint(v.z * 1.0e9) / 1.0e9);
    }

    /** A point of the pulley's frame in the far frame. */
    public Vec3 toFar(final Vec3 pos) {
        return turn(pos).add(shift.getX(), shift.getY(), shift.getZ());
    }

    /** A point of the far frame in the pulley's frame. */
    public Vec3 fromFar(final Vec3 pos) {
        final Vec3 v = pos.subtract(shift.getX(), shift.getY(), shift.getZ());
        return turns == 0 ? v : clean(turnOf(4 - turns).rotate(v));
    }

    /**
     * The far place of a contraption entity at {@code pos}: Create turns a contraption about the centre of its anchor block
     * ({@code toGlobalVector}), so the anchor block's centre maps through the portal.
     */
    public Vec3 entityToFar(final Vec3 pos) {
        return turns == 0 ? toFar(pos) : toFar(pos.add(0.5, 0.5, 0.5)).subtract(0.5, 0.5, 0.5);
    }

    /** A block of the pulley's frame in the far frame. */
    public BlockPos blockToFar(final BlockPos pos) {
        return turns == 0 ? pos.offset(shift) : BlockPos.containing(toFar(Vec3.atCenterOf(pos)));
    }

    /** A horizontal direction of the pulley's frame in the far frame. */
    public Direction directionToFar(final Direction direction) {
        if (turns == 0) {
            return direction;
        }
        final Vec3 v = turn(Vec3.atLowerCornerOf(direction.getNormal()));
        return Direction.getNearest(v.x, v.y, v.z);
    }

    /**
     * The angle about y, in Create's terms ({@code VecHelper.rotate}), that turns a contraption as this shaft turns
     * points: found by turning the x and z axes both ways, so it holds whichever way either side counts its angles.
     */
    public float createAngle() {
        if (turns == 0) {
            return 0f;
        }
        final Vec3 x = turn(new Vec3(1, 0, 0));
        for (int k = 1; k < 4; k++) {
            final Vec3 created = net.createmod.catnip.math.VecHelper.rotate(new Vec3(1, 0, 0), 90.0 * k, net.minecraft.core.Direction.Axis.Y);
            if (created.distanceToSqr(x) < 1.0e-6) {
                return 90f * k;
            }
        }
        return 90f * turns;
    }

    public double farPlaneY() {
        return planeY + shift.getY();
    }

    public CompoundTag write() {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("Portal", portalId);
        tag.putString("FarDim", farDim.location().toString());
        tag.putDouble("PlaneY", planeY);
        tag.put("Shift", NbtUtils.writeBlockPos(shift));
        tag.putInt("Turns", turns);
        tag.putInt("BottomY", bottomY);
        return tag;
    }

    public static @Nullable IPElevatorShaft read(final CompoundTag tag) {
        final ResourceLocation dim = ResourceLocation.tryParse(tag.getString("FarDim"));
        if (dim == null || !tag.hasUUID("Portal")) {
            return null;
        }
        return new IPElevatorShaft(tag.getUUID("Portal"), ResourceKey.create(Registries.DIMENSION, dim), tag.getDouble("PlaneY"),
            NbtUtils.readBlockPos(tag, "Shift").orElse(BlockPos.ZERO), Math.floorMod(tag.getInt("Turns"), 4), tag.getInt("BottomY"));
    }

    /**
     * The shaft below {@code pulley} for a car with these bounds, or null if the rope meets no such portal. The portal's
     * end there must be in the pulley's frame (both fixed in the world, or both on one ship: an elevator fixed in the
     * world never runs through a ship's portal); its other end may be anywhere, fixed or on a ship. Each end is taken in
     * its own frame ({@link IPShipPortals#framedSides}), so the shift between them stays put while ships fly.
     */
    public static @Nullable IPElevatorShaft find(final ServerLevel level, final BlockPos pulley, final AABB car) {
        final double top = pulley.getY();
        final double bottom = level.getMinBuildHeight() - 1;
        final AABB column = new AABB(car.minX, bottom, car.minZ, car.maxX, top, car.maxZ);
        final UUID ship = IPElevatorFrames.shipAt(level, pulley);
        final AABB search = ship == null ? column : IPElevatorFrames.worldBox(level, Vec3.atCenterOf(pulley), column);
        final List<Portal> candidates = new ArrayList<>(level.getEntitiesOfClass(Portal.class, search.inflate(1.0)));
        if (ship == null) {
            candidates.addAll(GlobalPortalStorage.getGlobalPortals(level));
        }
        Portal best = null;
        IPShipPortals.FramedSides bestSides = null;
        final StringBuilder why = new StringBuilder();
        for (final Portal portal : candidates) {
            if (portal.isRemoved() || !portal.isTeleportable()) {
                continue;
            }
            if (IPShipPortals.hasScaledCarrier(portal)) {
                note(why, portal, "a scaled ship carries an end");
                continue;
            }
            final IPShipPortals.FramedSides sides = IPShipPortals.framedSides(portal);
            if (sides == null) {
                note(why, portal, "an end's place on its ship is not known");
                continue;
            }
            if (!java.util.Objects.equals(sides.ownCarrier(), ship)) {
                note(why, portal, "its end here is " + (sides.ownCarrier() == null ? "fixed in the world" : "on ship " + sides.ownCarrier())
                    + ", the pulley " + (ship == null ? "in the world" : "on ship " + ship));
                continue;
            }
            final UnilateralPortalState own = sides.own();
            final UnilateralPortalState far = sides.far();
            if (own.getNormal().y < 1 - EPS) {
                continue; // a wall or ceiling face (each floor portal has a ceiling face too)
            }
            final double plane = own.position().y;
            if (plane >= top || plane <= bottom || (bestSides != null && plane <= bestSides.own().position().y)) {
                continue;
            }
            final DQuaternion turn = UnilateralPortalState.combine(own, far).rotation;
            final int turns = quarterTurns(turn);
            // the shift after the turn: far = turn(own) + offset
            final Vec3 offset = turns < 0 ? Vec3.ZERO : far.position().subtract(turnOf(turns).rotate(own.position()));
            if (Math.abs(far.width() / own.width() - 1) > EPS) {
                note(why, portal, "scaled " + far.width() / own.width());
            } else if (turns < 0) {
                note(why, portal, "turned, not by quarter turns about the vertical: " + turn);
            } else if (!wholeBlocks(offset)) {
                note(why, portal, "shift not whole blocks " + offset);
            } else if (!holds(own, car)) {
                note(why, portal, "the car " + car + " is not inside the opening at " + own.position() + " (" + own.width() + " x "
                    + own.height() + ")");
            } else {
                best = portal;
                bestSides = sides;
            }
        }
        lastRejections = why.toString();
        if (best == null) {
            return null;
        }
        final ServerLevel farLevel = level.getServer().getLevel(best.getDestDim());
        if (farLevel == null) {
            return null;
        }
        final int turns = quarterTurns(UnilateralPortalState.combine(bestSides.own(), bestSides.far()).rotation);
        final Vec3 d = bestSides.far().position().subtract(turnOf(turns).rotate(bestSides.own().position()));
        final BlockPos shift = new BlockPos((int) Math.round(d.x), (int) Math.round(d.y), (int) Math.round(d.z));
        // rounded: a plane on a ship is found through its pose, a hair off from one search to the next
        final double planeY = Math.round(bestSides.own().position().y * 1.0e6) / 1.0e6;
        return new IPElevatorShaft(best.getUUID(), best.getDestDim(), planeY, shift, turns, farLevel.getMinBuildHeight() - shift.getY());
    }

    /** Why the last {@link #find} turned down the floor portals below the pulley (empty if none): for its log line. */
    static String lastRejections = "";

    private static void note(final StringBuilder why, final Portal portal, final String reason) {
        if (why.length() < 1000) {
            why.append(why.isEmpty() ? "" : "; ").append("portal ").append(portal.getUUID()).append(" at ")
                .append(portal.getOriginPos()).append(": ").append(reason);
        }
    }

    /** How many quarter turns about +y {@code turn} is, 0 to 3, or -1 if it is not one. */
    private static int quarterTurns(final DQuaternion turn) {
        for (int k = 0; k < 4; k++) {
            if (DQuaternion.isClose(turn, turnOf(k), 1.0e-5)) {
                return k;
            }
        }
        return -1;
    }

    private static boolean wholeBlocks(final Vec3 d) {
        return Math.abs(d.x - Math.round(d.x)) < EPS && Math.abs(d.y - Math.round(d.y)) < EPS
            && Math.abs(d.z - Math.round(d.z)) < EPS;
    }

    /** Whether the car's footprint lies inside the portal's opening. */
    private static boolean holds(final UnilateralPortalState portal, final AABB car) {
        final Vec3 origin = portal.position();
        final double halfW = portal.width() / 2 + EPS;
        final double halfH = portal.height() / 2 + EPS;
        final AABB foot = car.deflate(0.01, 0, 0.01);
        for (final double x : new double[]{foot.minX, foot.maxX}) {
            for (final double z : new double[]{foot.minZ, foot.maxZ}) {
                final Vec3 v = new Vec3(x - origin.x, 0, z - origin.z);
                if (Math.abs(v.dot(portal.getAxisW())) > halfW || Math.abs(v.dot(portal.getAxisH())) > halfH) {
                    return false;
                }
            }
        }
        return true;
    }
}
