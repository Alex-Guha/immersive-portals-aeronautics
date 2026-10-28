/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.transport.BeltMovementHandler;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.IPPortalLookup;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

/**
 * Riders on a belt that runs into a nether-style portal (players, mobs, dropped items). Create carries an entity only
 * while it touches the belt; past the belt's last face it fell into the portal block, stood on the frame below the
 * belt's surface and stopped short of the plane, and on the far side faced a step up onto the far belt. In the half of
 * the portal block between a belt's end and the plane (and a little past it), a rider coming off the belt is held at the
 * belt's surface and moved on at the belt's speed (held only, while Create still counts it as on the belt; mobs slowed
 * as Create slows them on a belt), the way the belt runs: into the portal at the end items run to, onto the belt at the
 * other. So a rider is carried through the portal and onto the belt past it, at one height. Only riders whose feet are
 * near the surface (coming off the belt), never ones walking on the frame below it.
 * <p>
 * Each side moves what it owns, as Create does: the server everything but players, the client its own player. Not at
 * frameless portals (no gap to carry across), not at sloped ends. A belt on a ship at its own mounted portal too
 * (2.6.12.9): the half is worked out in the ship's plot and its riders, which are in the world, taken into it. Mixin: {@code MixinBeltBlockEntity_PortalRiders}.
 */
public final class IPCreatePortalBeltRiders {
    /** Create's belt surface, above the bottom of the belt's block ({@code BeltMovementHandler}). */
    private static final double TOP = 0.8125;
    /** Feet this far below the surface are still coming off the belt (a tick's fall), not walking on the frame. */
    private static final double BELOW = 0.5;
    private static final double ABOVE = 0.6;
    /**
     * How far past the plane the half reaches: a rider is teleported a little after it crosses, and one left there fell
     * to the frame (headless, a ship's portal: 0.07 past the plane).
     */
    private static final double PAST = 0.3;
    /** Ticks after carrying a rider through a portal before this carries it through one again (never straight back). */
    private static final int THROUGH_TICKS = 10;
    /** Riders this carried through a portal, with the game tick (server; weak, as entities come and go). */
    private static final Map<Entity, Long> CARRIED_THROUGH = new WeakHashMap<>();
    private static final boolean SABLE = net.neoforged.fml.ModList.get().isLoaded("sable");

    private IPCreatePortalBeltRiders() {
    }

    /** Each tick of a belt's controller: carries riders across the portal blocks at its ends. */
    public static void carry(final BeltBlockEntity controller) {
        final Level level = controller.getLevel();
        final BlockState state = controller.getBlockState();
        if (level == null || controller.beltLength < 2 || Math.abs(controller.getSpeed()) < 1
            || !state.hasProperty(BeltBlock.SLOPE) || state.getValue(BeltBlock.SLOPE) != BeltSlope.HORIZONTAL) {
            return;
        }
        final Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        // Create's direction of travel (BeltMovementHandler.transportEntity)
        final Direction.Axis axis = facing.getAxis();
        final Direction travel = Direction.get(axis == Direction.Axis.X ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE, axis);
        final Vec3 movement = Vec3.atLowerCornerOf(travel.getNormal()).scale(controller.getBeltMovementSpeed());
        for (final boolean atEnd : new boolean[]{false, true}) {
            final BlockPos end = BeltHelper.getPositionForOffset(controller, atEnd ? controller.beltLength - 1 : 0);
            final Direction out = atEnd ? facing : facing.getOpposite();
            final BlockPos cell = end.relative(out);
            if (!level.isLoaded(cell) || !IPCreatePortalPipes.isPlaceholderFacing(level.getBlockState(cell), out)) {
                continue;
            }
            carryIn(level, controller, cell, out, movement, SABLE ? IPCreateShaftShips.frameAt(level, cell) : null);
        }
    }

    /**
     * Holds and moves riders in the half of the portal block {@code cell} between the belt's last face and the plane,
     * worked out in the belt's coordinates: the world's, or a ship's plot ({@code frame}), whose riders are in the world.
     */
    private static void carryIn(
        final Level level, final BeltBlockEntity controller, final BlockPos cell, final Direction out, final Vec3 movement,
        final IPCreatePortalShafts.@Nullable Frame frame
    ) {
        final Vec3 n = Vec3.atLowerCornerOf(out.getNormal());
        final Vec3 face = Vec3.atCenterOf(cell).subtract(n.scale(0.5));
        final double top = cell.getY() + TOP;
        // past the plane only where the belt runs into the portal: where it runs out, past the plane is behind the
        // portal, and pushing a rider from there took it through the portal's back face (user report, 2.6.12.9)
        final boolean intoPortal = movement.dot(n) > 0;
        final AABB half = new AABB(face, Vec3.atCenterOf(cell).add(n.scale(intoPortal ? PAST : 0)))
            .inflate(out.getAxis() == Direction.Axis.X ? 0 : 0.5, 0, out.getAxis() == Direction.Axis.Z ? 0 : 0.5);
        final AABB feet = new AABB(half.minX, top - BELOW, half.minZ, half.maxX, top + ABOVE, half.maxZ);
        final AABB search = frame == null ? feet.inflate(1, 0, 1) : worldBounds(frame, feet.inflate(1));
        final Vec3 up = frame == null ? new Vec3(0, 1, 0) : frame.toWorldDirection(new Vec3(0, 1, 0)).normalize();
        final Vec3 push = frame == null ? movement : frame.toWorldDirection(movement);
        for (final Entity entity : level.getEntities((Entity) null, search, e -> rides(level, e))) {
            final Vec3 pos = entity.position();
            final Vec3 local = frame == null ? pos : frame.toLocal(pos);
            final double rising = entity.getDeltaMovement().dot(up);
            if (!feet.contains(local) || rising > 0.1) {
                continue; // beside the belt's line, or jumping
            }
            if (local.y < top) {
                final Vec3 onSurface = new Vec3(local.x, top, local.z);
                entity.move(MoverType.SELF, (frame == null ? onSurface : frame.toWorld(onSurface)).subtract(pos));
            }
            if (rising < 0) {
                entity.setDeltaMovement(entity.getDeltaMovement().subtract(up.scale(rising)));
            }
            entity.fallDistance = 0;
            if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
                // as Create slows mobs on a belt, so they do not walk against it (back and forth through the portal)
                living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 1, false, false));
            }
            if (controller.passengers == null || !controller.passengers.containsKey(entity)) {
                final Vec3 eyeBefore = entity.getEyePosition();
                entity.move(MoverType.SELF, push); // Create carries it a tick or two after it left the belt: held, not pushed twice
                teleportIfCrossed(level, entity, eyeBefore);
            }
            entity.setOnGround(true);
        }
    }

    /**
     * IP teleports an entity when a portal, ticking, sees its eye move through it since the entity's last tick. This
     * moves riders in the belt's tick, after both: a rider that ticks before the portal has already taken its moved
     * position as its last one, and was never teleported (headless, a ship's portal: held 0.33 past the plane, then
     * dropped). So a move that takes a rider's eye through a portal teleports it here (server, not players: their
     * client crosses them).
     */
    private static void teleportIfCrossed(final Level level, final Entity entity, final Vec3 eyeBefore) {
        if (!(level instanceof ServerLevel server) || entity instanceof Player || justCarriedThrough(entity, level.getGameTime())) {
            return;
        }
        final Vec3 eyeAfter = entity.getEyePosition();
        final AABB path = new AABB(eyeBefore, eyeAfter).inflate(0.5);
        for (final Portal portal : IPPortalLookup.touching(level, path, p -> p.isTeleportable() && !p.isRemoved())) {
            if (portal.canTeleportEntity(entity) && portal.isMovedThroughPortal(eyeBefore, eyeAfter)) {
                ServerTeleportationManager.of(server.getServer()).startTeleportingRegularEntity(portal, entity);
                CARRIED_THROUGH.put(entity, level.getGameTime());
                return;
            }
        }
    }

    /** A rider this carried through a portal in the last {@link #THROUGH_TICKS} ticks: not carried through again yet. */
    private static boolean justCarriedThrough(final Entity entity, final long now) {
        final Long at = CARRIED_THROUGH.get(entity);
        if (at == null) {
            return false;
        }
        if (now - at >= THROUGH_TICKS || now < at) {
            CARRIED_THROUGH.remove(entity);
            return false;
        }
        return true;
    }

    /** The world box around a box in a ship's plot. */
    private static AABB worldBounds(final IPCreatePortalShafts.Frame frame, final AABB local) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final Vec3 corner = frame.toWorld(new Vec3(
                (i & 1) == 0 ? local.minX : local.maxX, (i & 2) == 0 ? local.minY : local.maxY, (i & 4) == 0 ? local.minZ : local.maxZ));
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean rides(final Level level, final Entity entity) {
        if (entity instanceof Portal || entity.isSpectator() || entity.noPhysics || entity.isPassenger()
            || !BeltMovementHandler.canBeTransported(entity)) {
            return false;
        }
        if (entity instanceof Player player && player.getAbilities().flying) {
            return false;
        }
        return level.isClientSide ? entity.isControlledByLocalInstance() : !(entity instanceof Player);
    }
}
