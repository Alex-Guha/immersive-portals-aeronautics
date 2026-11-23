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
 * A portal an elevator's shaft runs through ({@link #findChain} finds them all, top down): a floor portal whose
 * opening holds the whole car, unscaled, turned by whole quarter turns about the vertical at most, mapping blocks onto
 * blocks. Below its plane the shaft goes on in {@link #farDim}: a point p of the pulley's frame is at
 * {@code turn(p) + shift} there ({@link #toFar}), so a far floor at y lies at {@code y - shift.y} in the pulley's frame.
 * Positions are in each side's own frame ({@link IPElevatorFrames}): a ship's plot for a side on a ship, so for a ship's
 * portal the shift is between plot coordinates. A portal comes to be turned when a ship carrying one of its ends is
 * disassembled: Create Aeronautics turns the ship to the nearest quarter turn as it places it. Past the first portal
 * of a chain, each record holds the whole way from the pulley ({@link #then}): its map from the pulley's frame into the
 * level past that portal, and its plane in the pulley's frame.
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
     * This shaft followed by {@code step}, a shaft from this one's far frame: the map from the pulley's frame into
     * {@code step}'s far frame, with {@code step}'s plane and bottom in the pulley's frame. Turns about the vertical
     * leave heights alone, so heights move by the shifts' y only.
     */
    public IPElevatorShaft then(final IPElevatorShaft step) {
        final Vec3 shifted = step.turn(Vec3.atLowerCornerOf(shift));
        final BlockPos total = new BlockPos((int) Math.round(shifted.x), (int) Math.round(shifted.y), (int) Math.round(shifted.z))
            .offset(step.shift);
        return new IPElevatorShaft(step.portalId, step.farDim, step.planeY - shift.getY(), total, (turns + step.turns) % 4,
            step.bottomY - shift.getY());
    }

    /** Most portals one shaft runs through. */
    private static final int MAX_CHAIN = 8;

    /**
     * The shaft below {@code pulley} for a car with these bounds, as a chain of floor portals (empty if the rope meets
     * none): each element maps the pulley's frame into the level past that portal ({@link #then}), its plane the
     * portal's in the pulley's frame. Past each portal the rope goes on in that level, to the next floor portal below
     * whose end is in the frame of the end it came out of, at least the car's height lower (a copy of the car is cut at
     * one plane at a time, so the car may never be in two portals at once).
     */
    public static List<IPElevatorShaft> findChain(final ServerLevel level, final BlockPos pulley, final AABB car) {
        final List<IPElevatorShaft> chain = new ArrayList<>();
        final StringBuilder why = new StringBuilder();
        Step step = find(level, Vec3.atCenterOf(pulley), IPElevatorFrames.shipAt(level, pulley), pulley.getY(), car, 0.0, why);
        final java.util.Set<UUID> seen = new java.util.HashSet<>();
        while (step != null && chain.size() < MAX_CHAIN && seen.add(step.shaft().portalId())) {
            final IPElevatorShaft total = chain.isEmpty() ? step.shaft() : chain.get(chain.size() - 1).then(step.shaft());
            chain.add(total);
            final ServerLevel far = level.getServer().getLevel(total.farDim());
            if (far == null) {
                break;
            }
            final Vec3 a = total.toFar(new Vec3(car.minX, car.minY, car.minZ));
            final Vec3 b = total.toFar(new Vec3(car.maxX, car.maxY, car.maxZ));
            final AABB farCar = new AABB(a, b);
            final double top = total.farPlaneY();
            why.setLength(0);
            step = find(far, new Vec3(farCar.getCenter().x, top, farCar.getCenter().z), step.farCarrier(), top, farCar,
                car.getYsize(), why);
        }
        lastRejections = why.toString();
        return chain;
    }

    /** One portal of a shaft, in the frame it was searched in, and the ship carrying its far end (null: the world). */
    private record Step(IPElevatorShaft shaft, @Nullable UUID farCarrier) {
    }

    /**
     * The floor portal below {@code top} (in the frame of {@code ship}, or the world's if null; {@code at} a point of
     * that frame) whose end there is in that frame, its plane at least {@code gap} below {@code top}, for a car with
     * these bounds in that frame. Its other end may be anywhere, fixed or on a ship. Each end is taken in its own frame
     * ({@link IPShipPortals#framedSides}), so the shift between them stays put while ships fly.
     */
    private static @Nullable Step find(final ServerLevel level, final Vec3 at, @Nullable final UUID ship, final double top,
                                       final AABB car, final double gap, final StringBuilder why) {
        final double bottom = level.getMinBuildHeight() - 1;
        final AABB column = new AABB(car.minX, bottom, car.minZ, car.maxX, top, car.maxZ);
        final AABB search = ship == null ? column : IPElevatorFrames.worldBox(level, at, column);
        final List<Portal> candidates = new ArrayList<>(level.getEntitiesOfClass(Portal.class, search.inflate(1.0)));
        if (ship == null) {
            candidates.addAll(GlobalPortalStorage.getGlobalPortals(level));
        }
        Portal best = null;
        IPShipPortals.FramedSides bestSides = null;
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
                    + ", the shaft above it " + (ship == null ? "in the world" : "on ship " + ship));
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
            } else if (plane > top - gap + EPS) {
                note(why, portal, String.format("%.2f below the portal above it, less than the car's height %.2f", top - plane, gap));
            } else {
                best = portal;
                bestSides = sides;
            }
        }
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
        return new Step(new IPElevatorShaft(best.getUUID(), best.getDestDim(), planeY, shift, turns, farLevel.getMinBuildHeight() - shift.getY()),
            bestSides.farCarrier());
    }

    /** Writes a chain ({@link #findChain}). */
    public static CompoundTag writeChain(final List<IPElevatorShaft> chain) {
        final CompoundTag tag = new CompoundTag();
        final net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (final IPElevatorShaft shaft : chain) {
            list.add(shaft.write());
        }
        tag.put("Chain", list);
        return tag;
    }

    /** Reads a chain; empty if the tag holds none. */
    public static List<IPElevatorShaft> readChain(final CompoundTag tag) {
        final List<IPElevatorShaft> chain = new ArrayList<>();
        for (final net.minecraft.nbt.Tag element : tag.getList("Chain", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            final IPElevatorShaft shaft = read((CompoundTag) element);
            if (shaft == null) {
                return List.of();
            }
            chain.add(shaft);
        }
        return List.copyOf(chain);
    }

    /** Why the last {@link #findChain} turned down the floor portals below its last portal (or the pulley): for its log line. */
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
