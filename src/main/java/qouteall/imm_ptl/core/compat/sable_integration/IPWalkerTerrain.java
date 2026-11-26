/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.letitwalk.walker.LegRuntime;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.IPPortalLookup;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalSimilarity;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddle;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalAnchors;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Let It Walk's terrain sensing at portals (server).
 * <p>
 * A walker finds footholds, checks its knees' path and looks for walls to climb by reading its own level at world
 * positions. Past a portal it walks into, the ground its legs will stand on, and that its far half collides with, is
 * the destination's; its own level there holds whatever is behind the portal (often the rock a nether portal is set in),
 * so it refused every step through. So while a walker ticks ({@link #begin}), the space past each portal it straddles
 * ({@link IPStraddle#farSidesOf}) or stands in front of, within the opening, is read in that portal's destination: a
 * point there is mapped through the portal, a probe from before the plane to past it is split at the plane, and what it
 * finds is mapped back.
 */
public final class IPWalkerTerrain {
    /** How far [blocks] past a portal a walker in front of it looks into its destination. */
    private static final double APPROACH_DEPTH = 32.0;
    /** How far [blocks] around a walker's ships a portal counts as one it walks into. */
    private static final double APPROACH_REACH = 4.0;
    /** Test switch: walkers read only their own level, as Let It Walk alone does. */
    private static final boolean DISABLED = Boolean.getBoolean("ip_aeronautics.debug.noWalkerTerrain");

    /** The space past a portal's plane within its opening, read in {@code dest} at {@code map} of a point. */
    private record Side(Level dest, IPPortalSimilarity map, Vec3 origin, Vec3 normal, Vec3 axisW, double halfW, Vec3 axisH,
                        double halfH, double depth) {
        static Side of(final IPStraddle.FarSide far) {
            final double[] r = far.region();
            return new Side(far.dest(), far.map(), new Vec3(r[0], r[1], r[2]), new Vec3(r[3], r[4], r[5]), new Vec3(r[6], r[7], r[8]),
                r[9], new Vec3(r[10], r[11], r[12]), r[13], r[15]);
        }

        /** @return how far past the plane {@code p} is (negative: before it) */
        double depthOf(final Vec3 p) {
            return p.subtract(this.origin).dot(this.normal);
        }

        boolean withinOpening(final Vec3 p) {
            final Vec3 d = p.subtract(this.origin);
            return Math.abs(d.dot(this.axisW)) <= this.halfW && Math.abs(d.dot(this.axisH)) <= this.halfH;
        }

        boolean contains(final Vec3 p) {
            final double depth = depthOf(p);
            return depth > 0.0 && depth <= this.depth && withinOpening(p);
        }

        /** @return where the segment {@code from}-{@code to} goes through the opening, from before the plane, or null */
        @Nullable
        Vec3 entry(final Vec3 from, final Vec3 to) {
            final double a = depthOf(from);
            final double b = depthOf(to);
            if (a > 0.0 || b <= 0.0) {
                return null;
            }
            final Vec3 cross = from.lerp(to, a / (a - b));
            return withinOpening(cross) ? cross : null;
        }
    }

    private static final ThreadLocal<List<Side>> SIDES = new ThreadLocal<>();
    /**
     * Set while a query runs in a destination: Let It Walk's helpers call each other (a climb probe checks that it
     * starts in the open), and a destination's points are not to be tested against this level's portals.
     */
    private static final ThreadLocal<Boolean> IN_DEST = new ThreadLocal<>();

    private IPWalkerTerrain() {
    }

    /** The walker {@code core} on {@code hull} starts its tick: its probes read past the portals it walks into. */
    public static void begin(final WalkerCoreBlockEntity core, final ServerSubLevel hull) {
        SIDES.remove();
        if (DISABLED || !IPStraddle.isAvailable() || !(core.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        final List<SubLevel> ships = new ArrayList<>();
        ships.add(hull);
        for (final LegRuntime leg : core.legs) {
            for (final UUID id : leg.built.segments) {
                final SubLevel segment = container.getSubLevel(id);
                if (segment != null && !segment.isRemoved()) {
                    ships.add(segment);
                }
            }
        }
        final List<Side> sides = new ArrayList<>();
        AABB around = null;
        for (final SubLevel ship : ships) {
            for (final IPStraddle.FarSide far : IPStraddle.farSidesOf(ship)) {
                sides.add(Side.of(far));
            }
            final BoundingBox3dc b = ship.boundingBox();
            final AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
            around = around == null ? box : around.minmax(box);
        }
        // a portal also straddled is listed twice: harmless, the first side holding a point answers
        final Vec3 centre = hull.logicalPose().transformPosition(Vec3.atCenterOf(core.getBlockPos()));
        for (final Portal portal : IPPortalLookup.touching(level, around.inflate(APPROACH_REACH), IPWalkerTerrain::walkable)) {
            final Level dest = portal.isInFrontOfPortal(centre) ? portal.getDestinationWorld() : null;
            if (dest == null) {
                continue;
            }
            sides.add(new Side(dest, IPPhysicsStaffPortals.mapOf(portal), portal.getOriginPos(), portal.getNormal().scale(-1.0),
                portal.getAxisW(), portal.getWidth() / 2.0, portal.getAxisH(), portal.getHeight() / 2.0, APPROACH_DEPTH));
        }
        if (!sides.isEmpty()) {
            SIDES.set(sides);
        }
    }

    /** The walker's tick is over. */
    public static void end() {
        SIDES.remove();
    }

    /** A portal a ship could straddle into another world: as the crossings take, unscaled, a mirror excepted. */
    private static boolean walkable(final Portal portal) {
        return portal.getTeleportable() && portal.getDestDim() != null && IPPhysicsStaffPortals.isMappable(portal)
            && Math.abs(portal.getScale() - 1.0) <= 1.0e-4 && !IPShipPortalAnchors.isShipMounted(portal);
    }

    @Nullable
    private static List<Side> sides() {
        return IN_DEST.get() == null ? SIDES.get() : null;
    }

    private static <T> T inDest(final java.util.function.Supplier<T> query) {
        IN_DEST.set(Boolean.TRUE);
        try {
            return query.get();
        } finally {
            IN_DEST.remove();
        }
    }

    @Nullable
    private static Side containing(final Vec3 p) {
        final List<Side> sides = sides();
        if (sides != null) {
            for (final Side side : sides) {
                if (side.contains(p)) {
                    return side;
                }
            }
        }
        return null;
    }

    /** A point query ({@code query} in a level at a point): past a portal, it runs in the destination. */
    public static <T> T atPoint(final Level level, final Vec3 p, final BiFunction<Level, Vec3, T> query) {
        final Side side = containing(p);
        return side == null ? query.apply(level, p) : inDest(() -> query.apply(side.dest(), side.map().mapPoint(p)));
    }

    /** A ray query from {@code from} to {@code to}. */
    @FunctionalInterface
    public interface Ray<T> {
        @Nullable
        T cast(Level level, Vec3 from, Vec3 to);
    }

    /** What a ray found, mapped back out of a portal's destination. */
    @FunctionalInterface
    public interface Unmap<T> {
        T unmap(T found, IPPortalSimilarity map);
    }

    /**
     * A ray query: past a portal it runs in the destination; one from before the plane through the opening runs to the
     * plane here, then on from there in the destination. What it finds there is mapped back with {@code unmap}.
     */
    @Nullable
    public static <T> T alongRay(final Level level, final Vec3 from, final Vec3 to, final Ray<T> ray, final Unmap<T> unmap) {
        final List<Side> sides = sides();
        if (sides == null) {
            return ray.cast(level, from, to);
        }
        for (final Side side : sides) {
            if (side.contains(from)) {
                final T found = inDest(() -> ray.cast(side.dest(), side.map().mapPoint(from), side.map().mapPoint(to)));
                return found == null ? null : unmap.unmap(found, side.map());
            }
        }
        Side nearest = null;
        Vec3 entry = null;
        for (final Side side : sides) {
            final Vec3 cross = side.entry(from, to);
            if (cross != null && (entry == null || cross.distanceToSqr(from) < entry.distanceToSqr(from))) {
                nearest = side;
                entry = cross;
            }
        }
        if (nearest == null) {
            return ray.cast(level, from, to);
        }
        final T before = ray.cast(level, from, entry);
        if (before != null) {
            return before;
        }
        final Side through = nearest;
        final Vec3 at = entry;
        final T found = inDest(() -> ray.cast(through.dest(), through.map().mapPoint(at), through.map().mapPoint(to)));
        return found == null ? null : unmap.unmap(found, nearest.map());
    }

    /** A block hit mapped back out of a portal's destination: its point and face (its block position stays there). */
    public static BlockHitResult unmapHit(final BlockHitResult hit, final IPPortalSimilarity map) {
        final Vec3 face = map.unmapDirection(Vec3.atLowerCornerOf(hit.getDirection().getNormal()));
        return new BlockHitResult(map.unmapPoint(hit.getLocation()), Direction.getNearest(face.x, face.y, face.z), hit.getBlockPos(), hit.isInside());
    }
}
