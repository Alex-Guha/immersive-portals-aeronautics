/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client half of {@link IPSeatSyncTrace}: the local player's vehicle as this client has it (and whether that entity is
 * still in the client's level), the passengers and removal packets about it or any carriage, and in singleplayer the
 * moment the client's vehicle and the server's stop agreeing for more than {@value #DISAGREE_TICKS} ticks.
 */
public final class IPSeatSyncTraceClient {
    /** The sides change vehicles a tick or two apart at every crossing; longer than this is a desync. */
    private static final int DISAGREE_TICKS = 10;

    private static String lastSeat;
    private static int disagreeTicks;
    private static boolean disagreeReported;

    private IPSeatSyncTraceClient() {
    }

    public static void init() {
        if (!IPSeatSyncTrace.ON) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        final Minecraft client = Minecraft.getInstance();
        final LocalPlayer player = client.player;
        if (player == null) {
            lastSeat = null;
            return;
        }
        final Entity vehicle = player.getVehicle();
        final String key = IPSeatSyncTrace.vehicleKey(vehicle);
        final String seat = key + ghost(player, vehicle);
        if (!seat.equals(lastSeat)) {
            IPSeatSyncTrace.LOGGER.info("[IPSeatSync] client: {} rides {}{} (was {}), at {} in {}", player.getScoreboardName(),
                IPSeatSyncTrace.describe(vehicle), ghost(player, vehicle), lastSeat, IPSeatSyncTrace.vec(player.position()),
                player.level().dimension().location());
            lastSeat = seat;
        }
        final IntegratedServer server = client.getSingleplayerServer();
        final String serverKey = server == null ? null : IPSeatSyncTrace.SERVER_SEATS.get(player.getUUID());
        if (serverKey == null) {
            return;
        }
        if (Objects.equals(key, serverKey)) {
            if (disagreeReported) {
                IPSeatSyncTrace.LOGGER.info("[IPSeatSync] client and server agree again: {} rides {}",
                    player.getScoreboardName(), key);
            }
            disagreeTicks = 0;
            disagreeReported = false;
            return;
        }
        if (++disagreeTicks == DISAGREE_TICKS) {
            disagreeReported = true;
            // read across threads, for the log only
            final ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
            IPSeatSyncTrace.LOGGER.warn("[IPSeatSync] client and server disagree for {} ticks: the client has {} riding {}{} at {} in {},"
                    + " the server has them riding {} at {} in {}", DISAGREE_TICKS, player.getScoreboardName(),
                IPSeatSyncTrace.describe(vehicle), ghost(player, vehicle), IPSeatSyncTrace.vec(player.position()),
                player.level().dimension().location(), serverKey,
                serverPlayer == null ? "?" : IPSeatSyncTrace.vec(serverPlayer.position()),
                serverPlayer == null ? "?" : serverPlayer.level().dimension().location());
        }
    }

    /** A vehicle the client's level no longer holds (removed, or another entity under its id). */
    private static String ghost(final LocalPlayer player, final Entity vehicle) {
        if (vehicle == null) {
            return "";
        }
        if (vehicle.isRemoved()) {
            return " [removed on this client]";
        }
        if (!(vehicle.level() instanceof final ClientLevel level) || level.getEntity(vehicle.getId()) != vehicle) {
            return " [not in its client level]";
        }
        return vehicle.level() != player.level() ? " [in another level than the player]" : "";
    }

    /** {@code ClientPacketListener.handleSetEntityPassengersPacket}, once applied (on the render thread). */
    public static void onPassengers(final ClientLevel level, final ClientboundSetPassengersPacket packet) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        final Entity entity = level.getEntity(packet.getVehicle());
        final boolean aboutPlayer = Arrays.stream(packet.getPassengers()).anyMatch(id -> id == player.getId())
            || (player.getVehicle() != null && player.getVehicle().getId() == packet.getVehicle());
        if (!aboutPlayer && !(entity instanceof CarriageContraptionEntity)) {
            return;
        }
        IPSeatSyncTrace.LOGGER.info("[IPSeatSync] client: passengers packet in {} for entity {} ({}): passengers {}, the player now rides {}",
            level.dimension().location(), packet.getVehicle(), entity == null ? "not in this level" : IPSeatSyncTrace.describe(entity),
            Arrays.toString(packet.getPassengers()), IPSeatSyncTrace.vehicleKey(player.getVehicle()));
    }

    /** {@code ClientPacketListener.handleRemoveEntities}, before it applies (on the render thread). */
    public static void onRemoveEntities(final ClientLevel level, final IntList ids) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        for (final int id : ids) {
            final Entity entity = level.getEntity(id);
            final boolean ridden = player.getVehicle() != null && player.getVehicle().getId() == id;
            if (ridden || entity instanceof CarriageContraptionEntity) {
                IPSeatSyncTrace.LOGGER.info("[IPSeatSync] client: removal packet in {} for entity {} ({}){}",
                    level.dimension().location(), id, entity == null ? "not in this level" : IPSeatSyncTrace.describe(entity),
                    ridden ? ": the vehicle the player rides" : "");
            }
        }
    }
}
