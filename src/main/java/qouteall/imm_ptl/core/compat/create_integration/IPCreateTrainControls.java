/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsServerHandler;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsStopControllingPacket;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.createmod.catnip.data.WorldAttached;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.compat.mixin_create.IPCarriageAccessor;
import qouteall.imm_ptl.core.compat.mixin_create.IPControlsContextAccessor;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Server: a player driving a train keeps the controls while the train goes through a portal, whether they ride it or
 * stand by and drive it within reach.
 * <p>
 * Create binds a driver to the carriage copy whose controls were clicked and stops them when:
 * <ul>
 *     <li>that copy is removed: the copy on the side the carriage has left is discarded a few ticks after, and its
 *     actors' {@code stopMoving} stops the controls ({@code ControlsMovementBehaviour});</li>
 *     <li>the driver is out of reach of the controls ({@code control()}: {@code canInteractWithBlock}, 8 blocks past
 *     the player's reach), measured in one dimension only;</li>
 *     <li>the driver's level no longer holds them ({@code ControlsServerHandler.tick} keeps held keys per level).</li>
 * </ul>
 * Stock Create does all of this at its own portals too. Here, reach to a copy in another dimension, or past a portal
 * within the driver's level, is measured through a portal next to the driver ({@link #canReach}). The held keys move
 * to the driver's level and to the best live copy of the carriage ({@link #bestCopy}: the one the driver rides, else
 * one in the driver's level, else any), together with its {@code CONTROLLED_BY}, and removing a copy while another one
 * lives doesn't stop the driver. Every copy drives the same {@code Train}. At a portal within one dimension both copies
 * are in the driver's level. The client follows on its side ({@link IPCreateTrainControlsClient}).
 */
public final class IPCreateTrainControls {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPTrainControls] ";

    private IPCreateTrainControls() {
    }

    /**
     * {@code ControlsServerHandler.tick}, head: moves each driver's held keys to the driver's level and to the best live
     * copy of the carriage.
     */
    public static void beforeTick(final LevelAccessor world) {
        if (!(world instanceof final ServerLevel level)) {
            return;
        }
        final Map<UUID, Object> contexts = contexts(level);
        if (contexts.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<UUID, Object>> iterator = contexts.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Object> entry = iterator.next();
            final IPControlsContextAccessor ctx = (IPControlsContextAccessor) entry.getValue();
            if (!(ctx.ip_getEntity() instanceof final CarriageContraptionEntity from)) {
                continue;
            }
            final ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            final CarriageContraptionEntity to = bestCopy(from, player);
            if (to == null || (to == from && player.level() == level)) {
                continue;
            }
            // Create's context moves as it is, held keys and all: a tick without them slows the train
            // (Train.tickPassiveSlowdown). Input that already reached the new copy has a context there to keep.
            if (player.level() != level) {
                iterator.remove();
                final Map<UUID, Object> target = contexts((ServerLevel) player.level());
                final Object existing = target.get(entry.getKey());
                if (existing == null || ((IPControlsContextAccessor) existing).ip_getEntity() != to) {
                    target.put(entry.getKey(), ctx);
                }
            }
            ctx.ip_setEntity(to);
            moveControllingPlayer(from, to, entry.getKey());
            LOGGER.info(TAG + "{} keeps the controls of carriage {}: entity {} in {}{} -> {} in {} (player in {})",
                player.getScoreboardName(), carriageId(from), from.getId(), from.level().dimension().location(),
                from.isRemoved() ? ", removed" : "", to.getId(), to.level().dimension().location(),
                player.level().dimension().location());
        }
    }

    /**
     * {@code ControlsMovementBehaviour.stopMoving} (the copy's actors stop because it is being removed): whether the
     * copy's driver is stopped. Not while another copy of the carriage lives: the carriage only left this dimension, or
     * this side's chunk unloaded. The driver's {@code CONTROLLED_BY} moves to that copy.
     */
    public static boolean stopsDriverOnRemoval(final AbstractContraptionEntity entity) {
        if (!(entity instanceof final CarriageContraptionEntity removed) || entity.level().isClientSide()) {
            return true;
        }
        final Optional<UUID> driver = removed.getControllingPlayer();
        if (driver.isEmpty()) {
            return true;
        }
        final ServerPlayer player = ((ServerLevel) entity.level()).getServer().getPlayerList().getPlayer(driver.get());
        final CarriageContraptionEntity other = otherLiveCopy(removed, player == null ? null : player.level());
        if (other == null) {
            return true;
        }
        moveControllingPlayer(removed, other, driver.get());
        LOGGER.info(TAG + "carriage {}: copy {} in {} removed, its driver {} stays on copy {} in {}",
            carriageId(removed), removed.getId(), removed.level().dimension().location(),
            player == null ? driver.get() : player.getScoreboardName(), other.getId(), other.level().dimension().location());
        return false;
    }

    /**
     * {@code AbstractContraptionEntity.canInteractWithBlock} for a player in another dimension than the contraption, or
     * out of reach of a carriage in the same one: Create's reach test ({@code Player.canInteractWithBlock}: within the
     * player's block reach plus {@code distance} of the block) through a portal near the player that leads to the
     * contraption's dimension.
     */
    public static boolean canReach(final AbstractContraptionEntity entity, final Player player, final Vec3 localPos,
                                   final double distance) {
        // a copy on a ship stands in its plot: portals and players are in the world
        final AABB block = new AABB(BlockPos.containing(worldPosition(entity, entity.toGlobalVector(localPos, 0.0f))));
        final double range = player.blockInteractionRange() + distance;
        final Vec3 eye = player.getEyePosition();
        for (final Portal portal : IPMcHelper.getNearbyPortalList(player.level(), eye, range,
            candidate -> candidate.getDestDim().equals(entity.level().dimension()) && candidate.isTeleportable())) {
            final Vec3 center = portal.inverseTransformPoint(block.getCenter());
            final AABB mapped = block.move(center.subtract(block.getCenter()));
            if (mapped.distanceToSqr(eye) < range * range) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code ControlsInputPacket.handle}: the contraption a driver's input names, in any level. Entity ids are unique
     * across the server's levels, and the copy a driver steers may be past a portal.
     */
    public static @Nullable Entity findControlledEntity(final ServerPlayer player, final int id) {
        for (final ServerLevel level : player.serverLevel().getServer().getAllLevels()) {
            if (level != player.level() && level.getEntity(id) instanceof final CarriageContraptionEntity carriage) {
                return carriage;
            }
        }
        return null;
    }

    /**
     * {@code AbstractContraptionEntity.stopControlling}, head, server: logs a carriage's driver being stopped, and tells a
     * driver in another dimension (out of reach through the portal), whom Create, looking in the copy's level only,
     * would leave controlling with their movement keys taken.
     */
    public static void onStop(final CarriageContraptionEntity entity, final BlockPos controlsPos) {
        final Optional<UUID> driver = entity.getControllingPlayer();
        if (driver.isEmpty() || !(entity.level() instanceof final ServerLevel level)) {
            return;
        }
        final ServerPlayer player = level.getServer().getPlayerList().getPlayer(driver.get());
        final boolean told = level.getPlayerByUUID(driver.get()) != null;
        final String where = player == null ? "offline" : player.level().dimension().location()
            + (player.level() == level ? ", " + String.format("%.2f",
            worldPosition(entity, entity.toGlobalVector(Vec3.atCenterOf(controlsPos), 1.0f)).distanceTo(player.getEyePosition()))
            + " from the controls" : "");
        if (player != null && !told) {
            CatnipServices.NETWORK.sendToClient(player, ControlsStopControllingPacket.INSTANCE);
        }
        LOGGER.info(TAG + "carriage {} (entity {} in {}{}) stops being controlled by {} ({}){}: {}",
            carriageId(entity), entity.getId(), level.dimension().location(), entity.isRemoved() ? ", removed" : "",
            player == null ? driver.get() : player.getScoreboardName(), where, player == null ? "" : ", client told", caller());
    }

    /**
     * The copy of {@code entity}'s carriage a driver should steer: the one they ride; else {@code entity} while it
     * lives in the driver's level; else the copy in that level nearest the driver (at a portal within one dimension
     * both copies are in it); else {@code entity} while it lives, else any live copy. Null when none lives.
     */
    public static @Nullable CarriageContraptionEntity bestCopy(final CarriageContraptionEntity entity, final Player driver) {
        if (driver.getVehicle() instanceof final CarriageContraptionEntity ridden && !ridden.isRemoved()
            && ridden.getCarriage() != null && ridden.getCarriage() == entity.getCarriage()) {
            return ridden;
        }
        if (!entity.isRemoved() && entity.level() == driver.level()) {
            return entity;
        }
        final CarriageContraptionEntity own = copyIn(entity, driver);
        if (own != null) {
            return own;
        }
        return entity.isRemoved() ? otherLiveCopy(entity, null) : entity;
    }

    /** @return the live copy of {@code entity}'s carriage in the player's level nearest the player, if there is one */
    private static @Nullable CarriageContraptionEntity copyIn(final CarriageContraptionEntity entity, final Player player) {
        final Carriage carriage = entity.getCarriage();
        if (carriage == null) {
            return null;
        }
        CarriageContraptionEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Carriage.DimensionalCarriageEntity dce : ((IPCarriageAccessor) carriage).ip_getEntities().values()) {
            final CarriageContraptionEntity copy = dce == null ? null : dce.entity.get();
            if (copy == null || copy.isRemoved() || copy.level() != player.level()) {
                continue;
            }
            final double distance = worldPosition(copy, copy.position()).distanceToSqr(player.position());
            if (distance < bestDistance) {
                best = copy;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** A live copy of {@code entity}'s carriage other than {@code entity}, the one in {@code preferred} if any. */
    private static @Nullable CarriageContraptionEntity otherLiveCopy(final CarriageContraptionEntity entity,
                                                                    final @Nullable Level preferred) {
        final Carriage carriage = entity.getCarriage();
        if (carriage == null) {
            return null;
        }
        CarriageContraptionEntity found = null;
        for (final Carriage.DimensionalCarriageEntity dce : ((IPCarriageAccessor) carriage).ip_getEntities().values()) {
            final CarriageContraptionEntity copy = dce == null ? null : dce.entity.get();
            if (copy == null || copy == entity || copy.isRemoved()) {
                continue;
            }
            if (copy.level() == preferred) {
                return copy;
            }
            found = copy;
        }
        return found;
    }

    private static void moveControllingPlayer(final CarriageContraptionEntity from, final CarriageContraptionEntity to,
                                              final UUID driver) {
        if (from != to && from.getControllingPlayer().filter(driver::equals).isPresent()) {
            from.setControllingPlayer(null);
        }
        to.setControllingPlayer(driver);
    }

    /** A position of a carriage copy, in the world: on a ship through its pose. */
    private static Vec3 worldPosition(final AbstractContraptionEntity entity, final Vec3 position) {
        final IPCreatePortalShafts.Frame frame = IPCreateTrainPortal.frameAt(entity.level(), position);
        return frame == null ? position : frame.toWorld(position);
    }

    /** The calls that led here (callers of the logging hook), for the log. */
    static String caller() {
        return Arrays.stream(Thread.currentThread().getStackTrace())
            .skip(3) // getStackTrace, caller, the logging hook
            .map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName())
            .filter(frame -> !frame.contains("handler$") && !frame.contains("$$"))
            .limit(6)
            .collect(Collectors.joining(" < "));
    }

    static String carriageId(final CarriageContraptionEntity entity) {
        final Carriage carriage = entity.getCarriage();
        return carriage == null ? "?" : carriage.train.name.getString() + "#" + carriage.id;
    }

    /** Create's per-level driver contexts ({@code ControlsServerHandler.ControlsContext} is package-private). */
    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> contexts(final ServerLevel level) {
        final WorldAttached<?> inputs = ControlsServerHandler.receivedInputs;
        return (Map<UUID, Object>) inputs.get(level);
    }
}
