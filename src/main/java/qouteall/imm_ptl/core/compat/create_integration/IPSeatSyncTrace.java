/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.IPStallRecorder;

/**
 * Diagnostics (2.8.8.1-seat-sync, user 2026-11-02: seated on a train through a ship's portal, "could no longer control
 * or unseat or move my camera in any direction but vertically"; the save had the player standing, the client had them
 * seated). Logs every change of the vehicle a player rides on each side, every seat taken on and left from a carriage
 * with its callers, every carriage removed, each player starting and stopping to track a carriage, and on the client
 * the passengers and removal packets for carriages. In singleplayer the client compares its vehicle with the server's
 * ({@link IPSeatSyncTraceClient}) and logs when they disagree for longer than a moment. {@code [IPSeatSync]}; on in
 * branch test jars and with {@code -Dip_aeronautics.debug.seatSync=true}.
 */
public final class IPSeatSyncTrace {
    static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ON = Boolean.getBoolean("ip_aeronautics.debug.seatSync") || IPStallRecorder.isTestJar();
    /** The vehicle each player rode at the end of the last server tick ({@link #vehicleKey}), read by the client. */
    static final Map<UUID, String> SERVER_SEATS = new ConcurrentHashMap<>();

    private IPSeatSyncTrace() {
    }

    public static void init() {
        if (!ON) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> serverTick(event.getServer()));
        LOGGER.info("[IPSeatSync] tracing seats on carriages");
    }

    private static void serverTick(final MinecraftServer server) {
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final String key = vehicleKey(player.getVehicle());
            final String before = SERVER_SEATS.put(player.getUUID(), key);
            if (!key.equals(before)) {
                LOGGER.info("[IPSeatSync] server: {} rides {} (was {}), at {} in {}", player.getScoreboardName(),
                    describe(player.getVehicle()), before, vec(player.position()), player.level().dimension().location());
            }
        }
    }

    /**
     * What a vehicle is, without its position: the two sides agree when their keys are equal. Entity ids match across
     * the sides, a carriage's number within its train does not (client #3 is server #2).
     */
    static String vehicleKey(@Nullable final Entity vehicle) {
        if (vehicle == null) {
            return "nothing";
        }
        final String kind = vehicle instanceof CarriageContraptionEntity ? "carriage" : vehicle.getType().toShortString();
        return kind + " " + vehicle.getId() + " in " + vehicle.level().dimension().location();
    }

    static String describe(@Nullable final Entity vehicle) {
        if (vehicle == null) {
            return "nothing";
        }
        final String train = vehicle instanceof CarriageContraptionEntity carriage
            ? " (" + IPCreateTrainControls.carriageId(carriage) + ")" : "";
        return vehicleKey(vehicle) + train + " at " + vec(vehicle.position())
            + (vehicle.isRemoved() ? " (removed: " + vehicle.getRemovalReason() + ")" : "");
    }

    static String side(final Entity entity) {
        return entity.level().isClientSide() ? "client" : "server";
    }

    static String vec(final Vec3 v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    /** {@code Entity.removeVehicle}: a player leaves a carriage's seat. */
    public static void onDismount(final Player player, final Entity vehicle) {
        LOGGER.info("[IPSeatSync] {}: {} leaves the seat of {}, at {} in {}: {}", side(player), player.getScoreboardName(),
            describe(vehicle), vec(player.position()), player.level().dimension().location(), callers(14));
    }

    /** {@code Entity.removeVehicle}: a mob leaves a seat on a ship (the seat in plot space). */
    public static void onShipSeatDismount(final Entity rider, final Entity vehicle) {
        LOGGER.info("[IPSeatSync] {}: {} {} leaves the seat {} on a ship, at {} in {} moving {}: {}", side(rider),
            rider.getType().toShortString(), rider.getId(), describe(vehicle), vec(rider.position()),
            rider.level().dimension().location(), vec(rider.getDeltaMovement()), callers(16));
    }

    /** {@code Entity.startRiding} succeeded: a mob takes a seat on a ship. */
    public static void onShipSeated(final Entity rider, final Entity vehicle) {
        LOGGER.info("[IPSeatSync] {}: {} {} seated on {} on a ship, at {} in {}: {}", side(rider),
            rider.getType().toShortString(), rider.getId(), describe(vehicle), vec(rider.position()),
            rider.level().dimension().location(), callers(10));
    }

    /** {@code Entity.startRiding} succeeded: a player takes a carriage's seat. */
    public static void onSeated(final Player player, final Entity vehicle) {
        LOGGER.info("[IPSeatSync] {}: {} seated on {}, at {} in {}: {}", side(player), player.getScoreboardName(),
            describe(vehicle), vec(player.position()), player.level().dimension().location(), callers(8));
    }

    /** {@code Entity.setRemoved} of a carriage. */
    public static void onRemoved(final CarriageContraptionEntity carriage, final Entity.RemovalReason reason) {
        LOGGER.info("[IPSeatSync] {}: {} removed ({}), passengers {}: {}", side(carriage), describe(carriage), reason,
            carriage.getPassengers().stream().map(passenger -> passenger instanceof Player p ? p.getScoreboardName()
                : passenger.getType().toShortString()).toList(), callers(8));
    }

    /** {@code ServerEntity.addPairing} / {@code removePairing} of a carriage. */
    public static void onPairing(final CarriageContraptionEntity carriage, final ServerPlayer player, final boolean starts) {
        LOGGER.info("[IPSeatSync] server: {} {} tracking {} (they ride {}, at {} in {}): {}", player.getScoreboardName(),
            starts ? "starts" : "stops", describe(carriage), vehicleKey(player.getVehicle()), vec(player.position()),
            player.level().dimension().location(), callers(8));
    }

    /** The calls that led here, ours left out; other mods' mixin handlers stay (Sable's name the mod). */
    static String callers(final int limit) {
        return Arrays.stream(Thread.currentThread().getStackTrace())
            .skip(3) // getStackTrace, callers, the logging hook
            .map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName())
            .filter(frame -> !frame.contains("ip_trace") && !frame.contains("$$"))
            .limit(limit)
            .collect(Collectors.joining(" < "));
    }
}
