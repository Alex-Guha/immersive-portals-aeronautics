/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostics (2.8.8.3-seat-sync, user 2026-11-02: seated on a train through a portal within one dimension with one end
 * on a ship, the train stopped in the portal and could not be driven, while getting up and sitting down worked). After
 * the rider was handed to the ship side's copy the client steered that copy and the server logged nothing: Create drops
 * a driver's input silently when its reach test fails ({@code ControlsInputPacket.handle}), and stops a copy that has
 * no driver bound to it without telling anyone. Logs each driver's input as it changes (the copy it names, the keys,
 * whether the reach test passed), every refused {@code control} with its reason, and once a second while a train is
 * driven its speed, whether it waits for chunks ({@code carriageWaitingForChunks}) and which copy holds the driver.
 * {@code [IPDrive]}, with {@link IPSeatSyncTrace#ON}.
 */
public final class IPDriveTrace {
    private static final Map<UUID, String> LAST_INPUT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_REFUSAL = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STATE = new ConcurrentHashMap<>();
    private static final long REPEAT_MS = 1000;

    private IPDriveTrace() {
    }

    /** {@code ControlsInputPacket.handle}: the input a driver sent and what the server made of it. */
    public static void onInput(final ServerPlayer player, final int entityId, final List<Integer> keys, final boolean press,
                               final boolean stop, @Nullable final Entity entity, @Nullable final Boolean reach,
                               final BlockPos controlsPos) {
        final String summary = "entity " + entityId + (entity == null ? " (not found)" : "") + " keys " + keys
            + (press ? " pressed" : " released") + (stop ? ", stop" : "") + (reach == null ? "" : ", reach " + reach);
        if (summary.equals(LAST_INPUT.put(player.getUUID(), summary))) {
            return;
        }
        String where = "";
        if (entity instanceof final AbstractContraptionEntity contraption) {
            final Vec3 controls = IPCreateTrainControls.worldPosition(contraption,
                contraption.toGlobalVector(Vec3.atCenterOf(controlsPos), 0.0f));
            where = String.format(", controls at %s in the world, %.2f from the eye", IPSeatSyncTrace.vec(controls),
                controls.distanceTo(player.getEyePosition())) + copyOf(contraption);
        }
        IPSeatSyncTrace.LOGGER.info("[IPDrive] {} input: {}{}; they ride {}", player.getScoreboardName(), summary, where,
            IPSeatSyncTrace.vehicleKey(player.getVehicle()));
    }

    /** {@code CarriageContraptionEntity.control} returned on the server. */
    public static void onControl(final CarriageContraptionEntity entity, final BlockPos controlsLocalPos,
                                 final Collection<Integer> held, final Player player, final boolean accepted) {
        final long now = System.currentTimeMillis();
        if (!accepted) {
            final Long last = LAST_REFUSAL.get(player.getUUID());
            if (last == null || now - last >= REPEAT_MS) {
                LAST_REFUSAL.put(player.getUUID(), now);
                IPSeatSyncTrace.LOGGER.info("[IPDrive] carriage {} refuses {}'s keys {}: {}{}", entity.getId(),
                    player.getScoreboardName(), held, refusal(entity, controlsLocalPos, held, player), copyOf(entity));
            }
            return;
        }
        final Long last = LAST_STATE.get(player.getUUID());
        if (last != null && now - last < REPEAT_MS) {
            return;
        }
        LAST_STATE.put(player.getUUID(), now);
        final Train train = entity.getCarriage() == null ? null : entity.getCarriage().train;
        IPSeatSyncTrace.LOGGER.info("[IPDrive] {} drives carriage {} with keys {}{}; train: speed {}, target {}, manual {},"
                + " waiting for chunks of carriage {} (this one is {}), derailed {}", player.getScoreboardName(),
            entity.getId(), held, copyOf(entity), train == null ? "?" : String.format("%.3f", train.speed),
            train == null ? "?" : String.format("%.3f", train.targetSpeed), train == null ? "?" : train.manualTick,
            train == null ? "?" : train.carriageWaitingForChunks, entity.getCarriage() == null ? "?" : entity.getCarriage().id,
            train == null ? "?" : train.derailed);
    }

    private static String refusal(final CarriageContraptionEntity entity, final BlockPos controlsLocalPos,
                                  final Collection<Integer> held, final Player player) {
        if (entity.getCarriage() == null) {
            return "no carriage";
        }
        if (entity.getCarriage().train.derailed) {
            return "derailed";
        }
        if (player.isSpectator()) {
            return "spectator";
        }
        if (!entity.canInteractWithBlock(player, VecHelper.getCenterOf(controlsLocalPos), 8.0)) {
            final Vec3 controls = IPCreateTrainControls.worldPosition(entity,
                entity.toGlobalVector(VecHelper.getCenterOf(controlsLocalPos), 0.0f));
            return String.format("out of reach (controls at %s in the world, %.2f from the eye)",
                IPSeatSyncTrace.vec(controls), controls.distanceTo(player.getEyePosition()));
        }
        return held.contains(5) ? "sneak held (stop controlling)" : "?";
    }

    /** Which copy this is: its side, position, driver bound to it and passengers. */
    static String copyOf(final AbstractContraptionEntity entity) {
        if (!(entity instanceof final CarriageContraptionEntity carriage)) {
            return "";
        }
        return " [copy " + carriage.getId() + " on side " + IPCreateTrainSides.keyOf(carriage).location()
            + " at " + IPSeatSyncTrace.vec(carriage.position()) + ", driver bound " + carriage.getControllingPlayer()
            .map(UUID::toString).orElse("none") + ", passengers " + carriage.getPassengers().stream()
            .map(p -> p instanceof Player pl ? pl.getScoreboardName() : p.getType().toShortString()).toList() + "]";
    }
}
