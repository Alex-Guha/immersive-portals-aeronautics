/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Shield Generators (shield_generators 1.4.0) and straddling ships, server side.
 * <p>
 * The mod keeps its shields per dimension ({@code ShieldRegistry}) and checks a projectile only against the shields of
 * the projectile's dimension. While a shielded ship straddles a portal, its shield is mirrored into the destination:
 * registered there under the same generator position with the ship's pose and the shield's world bounds mapped through
 * the portal, so the mod's own code reflects projectiles (and explosions) on the far half, with its sounds and sparks
 * where the far half is. Each instance only counts hits on its side of the portal plane, as the ship's blocks do
 * ({@link #keepSide}). An impact on the mirror is recorded on the generator in the ship's own dimension
 * ({@link #blockEntityFor}), which sends the impact animation to its viewers.
 * <p>
 * The mod is reached by reflection (its classes are package-private); anything missing disables this quietly.
 */
public final class IPStraddleShields {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String REGISTRY = "com.anton.shieldgenerators.ShieldRegistry";
    /** A hit this close behind a portal plane [blocks] still counts (the seam). */
    private static final double SEAM = 1.0e-3;

    /** A mirrored shield in a destination dimension: the generator is in {@code source}; refreshed at {@code tick}. */
    private record Mirror(ServerLevel source, long tick) {
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Mirror>> MIRRORS = new HashMap<>();
    /** Per shield instance, the planes {px, py, pz, nx, ny, nz} it counts hits in front of. */
    private static final Map<Object, double[][]> KEEP = Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean resolved;
    @Nullable
    private static Method update;
    @Nullable
    private static Field shields;
    @Nullable
    private static Method hitWorld;
    private static boolean mirroring;

    private IPStraddleShields() {
    }

    /**
     * After {@code ShieldRegistry.update} (once per server tick for every active shield): mirrors a ship's shield into
     * the destination of each straddle session of the ship and limits each instance to its side of the plane.
     */
    public static void afterUpdate(final ServerLevel level, final BlockPos generatorPos, final AABB bounds, final AABB renderBounds,
                                   final List<?> panels, final List<?> triangles, @Nullable final Pose3dc pose, final double strength) {
        if (mirroring || pose == null || !resolve()) {
            return;
        }
        final Object instance = instanceAt(level, generatorPos);
        if (instance == null) {
            return;
        }
        final SubLevel ship = Sable.HELPER.getContaining(level, generatorPos);
        final List<double[]> sourcePlanes = new ArrayList<>(1);
        if (ship != null) {
            IPStraddleSessions.forEach(session -> {
                if (session.ship != ship || session.source != level || session.dest == level || session.ship.isRemoved()) {
                    return;
                }
                sourcePlanes.add(plane(session.origin, session.normal.scale(-1.0)));
                mirror(session, generatorPos, bounds, renderBounds, panels, triangles, pose, strength);
            });
        }
        if (sourcePlanes.isEmpty()) {
            KEEP.remove(instance);
        } else {
            KEEP.put(instance, sourcePlanes.toArray(double[][]::new));
        }
    }

    private static void mirror(final IPStraddleSessions.Session session, final BlockPos generatorPos, final AABB bounds,
                               final AABB renderBounds, final List<?> panels, final List<?> triangles, final Pose3dc pose,
                               final double strength) {
        final ServerLevel dest = session.dest;
        final BlockPos key = generatorPos.immutable();
        final Map<BlockPos, Mirror> mirrors = MIRRORS.computeIfAbsent(dest.dimension(), k -> new HashMap<>());
        if (!mirrors.containsKey(key) && instanceAt(dest, key) != null) {
            // a shield of a ship of the destination has the same plot position (plot grids are per level)
            return;
        }
        final IPPortalSimilarity isometry = session.map;
        mirroring = true;
        try {
            update.invoke(null, dest, key, mapBox(isometry, bounds), renderBounds, panels, triangles,
                    isometry.mapPose(pose), dest.getGameTime(), strength);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            disable("mirroring a shield", e);
            return;
        } finally {
            mirroring = false;
        }
        mirrors.put(key, new Mirror(session.source, dest.getGameTime()));
        final Object instance = instanceAt(dest, key);
        if (instance != null) {
            KEEP.put(instance, new double[][]{plane(isometry.mapPoint(session.origin), isometry.mapDirection(session.normal))});
        }
    }

    /**
     * After {@code ShieldInstance.clipShell}: a hit on the side of a portal plane where the instance's ship is not (the
     * part of a straddling ship past the plane in its own dimension, the part before it in the destination) is no hit.
     */
    public static Optional<?> keepSide(final Object instance, final Optional<?> hit) {
        if (hit.isEmpty()) {
            return hit;
        }
        final double[][] planes = KEEP.get(instance);
        if (planes == null) {
            return hit;
        }
        final Vec3 world = hitWorld(hit.get());
        if (world == null) {
            return hit;
        }
        for (final double[] plane : planes) {
            if (plane[3] * (world.x - plane[0]) + plane[4] * (world.y - plane[1]) + plane[5] * (world.z - plane[2]) < -SEAM) {
                return Optional.empty();
            }
        }
        return hit;
    }

    /**
     * For the mod's purge: a mirrored shield stays registered while its generator is loaded in the ship's dimension
     * (its plot position means nothing in the destination).
     */
    public static boolean isLoaded(final ServerLevel level, final BlockPos pos) {
        final Mirror mirror = mirrorAt(level, pos);
        return mirror != null ? mirror.source().isLoaded(pos) : level.isLoaded(pos);
    }

    /**
     * For the mod's impact recording: the generator of a mirrored shield is in the ship's dimension.
     */
    @Nullable
    public static BlockEntity blockEntityFor(final ServerLevel level, final BlockPos pos) {
        final Mirror mirror = mirrorAt(level, pos);
        return mirror != null ? mirror.source().getBlockEntity(pos) : level.getBlockEntity(pos);
    }

    @Nullable
    private static Mirror mirrorAt(final ServerLevel level, final BlockPos pos) {
        final Map<BlockPos, Mirror> mirrors = MIRRORS.get(level.dimension());
        if (mirrors == null) {
            return null;
        }
        final Mirror mirror = mirrors.get(pos);
        if (mirror == null) {
            return null;
        }
        // the mod drops shields not refreshed for 2 ticks; so does the mirror record
        if (level.getGameTime() - mirror.tick() > 2L) {
            mirrors.remove(pos);
            if (mirrors.isEmpty()) {
                MIRRORS.remove(level.dimension());
            }
            return null;
        }
        return mirror;
    }

    /** Forgets every mirror (server stopped: the mod's registry is static too, but keyed by dimension only). */
    public static void reset() {
        MIRRORS.clear();
        KEEP.clear();
    }

    private static double[] plane(final Vec3 point, final Vec3 normal) {
        return new double[]{point.x, point.y, point.z, normal.x, normal.y, normal.z};
    }

    private static AABB mapBox(final IPPortalSimilarity isometry, final AABB box) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final Vec3 corner = isometry.mapPoint(new Vec3(
                    (i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ));
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Nullable
    private static Object instanceAt(final ServerLevel level, final BlockPos pos) {
        try {
            final Map<?, ?> byLevel = (Map<?, ?>) shields.get(null);
            final Map<?, ?> levelShields = byLevel == null ? null : (Map<?, ?>) byLevel.get(level.dimension());
            return levelShields == null ? null : levelShields.get(pos);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            disable("reading the shield registry", e);
            return null;
        }
    }

    @Nullable
    private static Vec3 hitWorld(final Object hit) {
        try {
            Method method = hitWorld;
            if (method == null) {
                method = hit.getClass().getDeclaredMethod("world");
                method.setAccessible(true);
                hitWorld = method;
            }
            return (Vec3) method.invoke(hit);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            disable("reading a shield hit", e);
            return null;
        }
    }

    private static boolean resolve() {
        if (resolved) {
            return update != null;
        }
        resolved = true;
        try {
            final Class<?> registry = Class.forName(REGISTRY);
            final Method method = registry.getDeclaredMethod("update", ServerLevel.class, BlockPos.class, AABB.class, AABB.class,
                    List.class, List.class, Pose3dc.class, long.class, double.class);
            method.setAccessible(true);
            final Field field = registry.getDeclaredField("SHIELDS");
            field.setAccessible(true);
            update = method;
            shields = field;
            LOGGER.info("[IPStraddle] Shield Generators found: shields of straddling ships are mirrored through the portal");
        } catch (final ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[IPStraddle] Shield Generators' registry not as expected; shields are not mirrored through portals", e);
        }
        return update != null;
    }

    private static void disable(final String what, final Throwable e) {
        if (update != null) {
            LOGGER.warn("[IPStraddle] Shield Generators: failed {}; shields are no longer mirrored through portals", what, e);
        }
        update = null;
        shields = null;
    }
}
