/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.datafixers.util.Pair;
import dev.ryanhcode.sable.Sable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.q_misc_util.my_util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Create Aeronautics' (Simulated) lasers through portals: laser pointers, laser sensors and optical sensors
 * ({@code LaserBehaviour}). Simulated casts the beam in its block's own level only, so a beam into a portal went on
 * through the world behind it. {@link #continueThrough} continues it in the portal's destination, through up to
 * {@value #MAX_PORTALS} portals, and the beam's hit results become those of its last leg, placed on the beam's
 * unfolded straight line ({@link Trace}): distances read off them are path lengths, and the block and entity are in
 * {@link Trace#level()}.
 */
public final class IPLaserPortals {
    /** Portals a beam goes through at most. */
    public static final int MAX_PORTALS = 4;
    /** How far [blocks] past a portal's plane a beam starts again in the destination. */
    private static final double NUDGE = 0.001;

    private IPLaserPortals() {
    }

    /** Implemented by {@code LaserBehaviour} (mixin): the beam's legs after its last cast, null when it met no portal. */
    public interface Holder {
        @Nullable
        Trace ip$portalTrace();
    }

    /**
     * One straight part of a beam, in {@code level}'s world coordinates.
     *
     * @param exit       the portal it ends in (null for the last leg)
     * @param fromSource the map from the beam's own world to this leg's world (portals before this leg, chained)
     */
    public record Leg(Level level, Vec3 from, Vec3 to, @Nullable Portal exit, IPPortalIsometry fromSource) {
    }

    /**
     * A beam through portals.
     *
     * @param legs    the beam's legs, the first in its own world
     * @param level   the level of the last leg, where the hits are
     * @param block   the last leg's block hit (or miss), its location on the unfolded beam in the beam's own world
     * @param entity  the last leg's entity hit, likewise, or null
     * @param closest whichever of {@code block} and {@code entity} is nearer
     */
    public record Trace(List<Leg> legs, Level level, BlockHitResult block, @Nullable EntityHitResult entity, HitResult closest) {
    }

    /**
     * Continues a beam Simulated just cast ({@code start} to {@code end}, plot positions on a ship, hits as Simulated
     * found them in {@code level}) through the first portal before its hit.
     *
     * @return the beam through the portals, or null when it reaches no portal before its hit
     */
    @Nullable
    public static Trace continueThrough(final Level level, final Vec3 start, final Vec3 end, @Nullable final HitResult closest,
                                        final ClipContext.Block blocks, final ClipContext.Fluid fluids, final Predicate<Entity> entities) {
        final Vec3 from = Sable.HELPER.projectOutOfSubLevel(level, start);
        final Vec3 to = Sable.HELPER.projectOutOfSubLevel(level, end);
        final double length = from.distanceTo(to);
        if (length < 1.0e-6) {
            return null;
        }
        final Vec3 direction = to.subtract(from).scale(1.0 / length);
        final double hit = closest == null || closest.getType() == HitResult.Type.MISS
                ? length
                : Math.min(length, from.distanceTo(Sable.HELPER.projectOutOfSubLevel(level, closest.getLocation())));
        Optional<Pair<Portal, RayTraceResult>> portal = nearestPortal(level, from, direction, hit);
        if (portal.isEmpty()) {
            return null;
        }

        final List<Leg> legs = new ArrayList<>();
        Level current = level;
        Vec3 legFrom = from;
        Vec3 legDirection = direction;
        IPPortalIsometry fromSource = IPPortalIsometry.IDENTITY;
        double travelled = 0.0;
        while (true) {
            final Portal through = portal.get().getFirst();
            final Vec3 at = portal.get().getSecond().hitPos();
            legs.add(new Leg(current, legFrom, at, through, fromSource));
            travelled += legFrom.distanceTo(at);
            final Level next = destination(current, through);
            final double rest = length - travelled;
            if (next == null || rest <= NUDGE || legs.size() > MAX_PORTALS) {
                // the far side is not loaded here (a client far from it), or the beam ends at the portal
                final Vec3 unfolded = from.add(direction.scale(length));
                final BlockHitResult miss = BlockHitResult.miss(unfolded, net.minecraft.core.Direction.getNearest(direction), net.minecraft.core.BlockPos.containing(unfolded));
                return new Trace(legs, current, miss, null, miss);
            }
            fromSource = fromSource.then(IPPortalIsometry.of(through));
            legDirection = through.transformLocalVecNonScale(legDirection).normalize();
            legFrom = through.transformPoint(at).add(legDirection.scale(NUDGE));
            current = next;

            final Vec3 legTo = legFrom.add(legDirection.scale(rest));
            final BlockHitResult block = current.clip(new ClipContext(legFrom, legTo, blocks, fluids, CollisionContext.empty()));
            final double blockDistance = block.getType() == HitResult.Type.MISS
                    ? rest
                    : Math.min(rest, legFrom.distanceTo(Sable.HELPER.projectOutOfSubLevel(current, block.getLocation())));
            final EntityHitResult entity = entityHit(current, legFrom, legFrom.add(legDirection.scale(blockDistance)), entities);
            final double entityDistance = entity == null ? Double.MAX_VALUE : legFrom.distanceTo(entity.getLocation());
            final double nearest = Math.min(blockDistance, entityDistance);

            portal = nearestPortal(current, legFrom, legDirection, nearest);
            if (portal.isPresent()) {
                continue;
            }
            legs.add(new Leg(current, legFrom, legFrom.add(legDirection.scale(nearest)), null, fromSource));
            final Vec3 blockAt = from.add(direction.scale(travelled + blockDistance));
            final BlockHitResult unfoldedBlock = block.getType() == HitResult.Type.MISS
                    ? BlockHitResult.miss(blockAt, block.getDirection(), block.getBlockPos())
                    : new BlockHitResult(blockAt, block.getDirection(), block.getBlockPos(), block.isInside());
            final EntityHitResult unfoldedEntity = entity == null
                    ? null
                    : new EntityHitResult(entity.getEntity(), from.add(direction.scale(travelled + entityDistance)));
            final HitResult nearestHit = unfoldedEntity != null && entityDistance < blockDistance ? unfoldedEntity : unfoldedBlock;
            return new Trace(legs, current, unfoldedBlock, unfoldedEntity, nearestHit);
        }
    }

    /** The nearest visible, unscaled portal the ray from {@code from} meets within {@code distance} (and a little). */
    private static Optional<Pair<Portal, RayTraceResult>> nearestPortal(final Level level, final Vec3 from, final Vec3 direction,
                                                                         final double distance) {
        // a little past the hit: a portal on a block's face counts (as in IP's own portal-aware raytrace)
        final Vec3 to = from.add(direction.scale(distance + 1.0e-4));
        return PortalUtils.raytracePortals(level, from, to, true, portal -> portal.isVisible() && Math.abs(portal.getScale() - 1.0) < 1.0e-9);
    }

    @Nullable
    private static Level destination(final Level level, final Portal portal) {
        if (level.isClientSide()) {
            return IPLaserPortalsClient.optionalWorld(portal.getDestDim());
        }
        return level instanceof final ServerLevel serverLevel ? serverLevel.getServer().getLevel(portal.getDestDim()) : null;
    }

    /** As Simulated's entity hit: the first matching entity along the beam, at the centre of its box. */
    @Nullable
    private static EntityHitResult entityHit(final Level level, final Vec3 from, final Vec3 to, final Predicate<Entity> entities) {
        final AABB box = new AABB(from, from).inflate(0.5f).expandTowards(to.subtract(from));
        final EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, null, from, to, box, entities, 0.1f);
        return hit == null ? null : new EntityHitResult(hit.getEntity(), hit.getEntity().getBoundingBox().getCenter());
    }
}
