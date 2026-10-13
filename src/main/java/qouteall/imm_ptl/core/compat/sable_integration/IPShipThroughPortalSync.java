/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.plot.SubLevelPlayerChunkSender;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddle;
import qouteall.imm_ptl.core.compat.mixin_sable.IPSubLevelTrackingSystemAccessor;
import qouteall.imm_ptl.core.network.PacketRedirection;

/**
 * Server-side "show ships through portals". Every {@value #RESCAN_INTERVAL_TICKS} ticks samples each ship's
 * footprint chunks and asks IP's chunk tracking for players viewing them from another dimension; a newly
 * visible ship gets Sable's private {@code SubLevelTrackingSystem.sendFullSync} (via
 * {@link IPSubLevelTrackingSystemAccessor}) inside {@code PacketRedirection.withForceRedirect} with
 * {@link IPSablePortalSync#beginForceRedirect()} so the bundle is wrapped as an IP redirect packet, later plot
 * chunks are topped up, and ships that are no longer viewed get a redirected StopTracking packet. Every tick
 * the pose/velocity snapshot packets (normally Sable's UDP stream) are sent per (player, dimension). On a
 * player dimension change the state is cleared and full syncs are re-sent for the arrival dimension.
 */
public final class IPShipThroughPortalSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RESCAN_INTERVAL_TICKS = 10;
    private static final int MAX_SHIPS_PER_PLAYER = 40;
    private static final int MAX_FOOTPRINT_SAMPLES = 8;
    /** Per player: the ships currently synced to them through a portal, by ship UUID. */
    private static final Map<UUID, Map<UUID, Synced>> SYNCED = new HashMap<>();
    private static final Map<UUID, ResourceKey<Level>> LAST_DIM = new HashMap<>();
    private static final Map<UUID, Long> LAST_MOVE_MS = new HashMap<>();
    private static Boolean accessorAvailable;
    private static boolean activeLogged;

    private IPShipThroughPortalSync() {
    }

    /** Forgets every player's synced ships (a new server: its players' clients start with fresh worlds). */
    static void reset() {
        SYNCED.clear();
        LAST_DIM.clear();
        LAST_MOVE_MS.clear();
    }

    public static void tick(MinecraftServer server, long tick) {
        if (!isAccessorAvailable()) {
            return;
        }

        if (tick % RESCAN_INTERVAL_TICKS == 0L) {
            rescan(server);
        }

        // movement snapshots go out every tick (the fork's MOVE_INTERVAL_TICKS was 1)
        sendMovement(server);
    }

    /**
     * Checks once that {@link IPSubLevelTrackingSystemAccessor} was applied to Sable's tracking system (the
     * Sable mixin config is {@code required:false}); the feature stays off otherwise.
     */
    private static boolean isAccessorAvailable() {
        if (accessorAvailable == null) {
            accessorAvailable = IPSubLevelTrackingSystemAccessor.class.isAssignableFrom(SubLevelTrackingSystem.class);
            if (!accessorAvailable) {
                LOGGER.warn("[IPShipThroughPortal] DISABLED - the SubLevelTrackingSystem accessor mixin was not applied");
            }
        }

        return accessorAvailable;
    }

    private static IPSubLevelTrackingSystemAccessor accessor(SubLevelTrackingSystem trackingSystem) {
        return (IPSubLevelTrackingSystemAccessor) (Object) trackingSystem;
    }

    private static void rescan(MinecraftServer server) {
        HashMap<UUID, Map<UUID, ServerSubLevel>> visibleByPlayer = new HashMap<>();
        HashMap<UUID, ServerPlayer> players = new HashMap<>();
        HashMap<UUID, ServerLevel> shipLevels = new HashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null) {
                List<ServerSubLevel> subLevels = container.getAllSubLevels();
                if (!subLevels.isEmpty()) {
                    for (ServerSubLevel sub : List.copyOf(subLevels)) {
                        if (!sub.isRemoved() && !IPShipRagdollGuard.isRagdollSubLevel(sub)) {
                            collectViewers(level, sub, visibleByPlayer, players, shipLevels);
                        }
                    }
                }
            }
        }

        HashSet<UUID> playerIds = new HashSet<>(visibleByPlayer.keySet());
        playerIds.addAll(SYNCED.keySet());

        for (UUID playerId : playerIds) {
            ServerPlayer player = players.get(playerId);
            if (player == null) {
                player = server.getPlayerList().getPlayer(playerId);
            }

            if (player == null) {
                SYNCED.remove(playerId);
                LAST_DIM.remove(playerId);
                LAST_MOVE_MS.remove(playerId);
            } else {
                reconcilePlayer(server, player, visibleByPlayer.getOrDefault(playerId, Map.of()), shipLevels);
            }
        }
    }

    /**
     * Brings one player's synced set in line with the ships currently visible to them: handles a dimension
     * change, stops tracking ships that went out of view, tops up chunks of ships still in view and starts
     * tracking newly visible ones (up to {@value #MAX_SHIPS_PER_PLAYER}).
     */
    private static void reconcilePlayer(MinecraftServer server, ServerPlayer player, Map<UUID, ServerSubLevel> visible, Map<UUID, ServerLevel> shipLevels) {
        UUID playerId = player.getUUID();
        Map<UUID, Synced> playerSynced = SYNCED.computeIfAbsent(playerId, id -> new HashMap<>());
        ResourceKey<Level> currentDim = player.level().dimension();
        ResourceKey<Level> previousDim = LAST_DIM.put(playerId, currentDim);
        if (previousDim != null && !previousDim.equals(currentDim)) {
            // Ships of the arrival dimension are now Sable's responsibility (its own tracking takes over the
            // client-side copies that were synced through the portal), so forget them WITHOUT sending a stop
            // packet. A ship that has left the arrival dimension since the last rescan is nobody's: its copy
            // gets a stop packet, or it stays on the client as a frozen ghost. Entries for other dimensions stay
            // and are reconciled normally: ships still visible through a portal keep updating, the rest get a
            // stop packet when they drop out of view.
            ServerLevel arrivalLevel = server.getLevel(currentDim);
            Map<UUID, ServerSubLevel> arrivalShips = arrivalLevel == null ? Map.of() : indexFor(arrivalLevel, new HashMap<>());
            playerSynced.entrySet().removeIf(entry -> {
                Synced synced = entry.getValue();
                if (!synced.dim.equals(currentDim)) {
                    return false;
                }
                ServerSubLevel sub = arrivalShips.get(entry.getKey());
                if (sub == null || sub.isRemoved()) {
                    sendStopTracking(player, synced);
                }
                return true;
            });
        }

        ArrayList<UUID> gone = new ArrayList<>();

        for (UUID shipId : playerSynced.keySet()) {
            if (!visible.containsKey(shipId)) {
                gone.add(shipId);
            }
        }

        for (UUID shipId : gone) {
            Synced synced = playerSynced.remove(shipId);
            if (synced != null) {
                sendStopTracking(player, synced);
            }
        }

        for (Map.Entry<UUID, ServerSubLevel> entry : visible.entrySet()) {
            Synced existing = playerSynced.get(entry.getKey());
            if (existing != null) {
                ServerLevel shipLevel = shipLevels.get(entry.getKey());
                if (shipLevel != null) {
                    topUpChunks(player, shipLevel, entry.getValue(), existing);
                }
                continue;
            }

            if (playerSynced.size() >= MAX_SHIPS_PER_PLAYER) {
                // parity note: hitting the cap stops processing the remaining visible ships for this player
                break;
            }

            ServerLevel shipLevel = shipLevels.get(entry.getKey());
            if (shipLevel != null) {
                Synced synced = sendStartTracking(player, shipLevel, entry.getValue());
                if (synced != null) {
                    playerSynced.put(entry.getKey(), synced);
                }
            }
        }

        if (playerSynced.isEmpty()) {
            SYNCED.remove(playerId);
        }
    }

    /**
     * Samples up to {@value #MAX_FOOTPRINT_SAMPLES}x{@value #MAX_FOOTPRINT_SAMPLES} chunks of the ship's hull
     * footprint and records every player viewing one of them from another dimension.
     */
    private static void collectViewers(
        ServerLevel level, ServerSubLevel sub, Map<UUID, Map<UUID, ServerSubLevel>> visibleByPlayer, Map<UUID, ServerPlayer> players, Map<UUID, ServerLevel> shipLevels
    ) {
        AABB hull = IPSableBridge.worldAabb(sub);
        int minChunkX = (int) Math.floor(hull.minX) >> 4;
        int maxChunkX = (int) Math.floor(hull.maxX) >> 4;
        int minChunkZ = (int) Math.floor(hull.minZ) >> 4;
        int maxChunkZ = (int) Math.floor(hull.maxZ) >> 4;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        int strideX = Math.max(1, (chunksX + MAX_FOOTPRINT_SAMPLES - 1) / MAX_FOOTPRINT_SAMPLES);
        int strideZ = Math.max(1, (chunksZ + MAX_FOOTPRINT_SAMPLES - 1) / MAX_FOOTPRINT_SAMPLES);
        HashMap<UUID, ServerPlayer> viewers = null;

        for (int cx = minChunkX; cx <= maxChunkX; cx += strideX) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz += strideZ) {
                for (ServerPlayer player : ImmPtlChunkTracking.getPlayersViewingChunk(level.dimension(), cx, cz).toList()) {
                    if (player.level() != level) {
                        if (viewers == null) {
                            viewers = new HashMap<>();
                        }

                        viewers.put(player.getUUID(), player);
                    }
                }
            }
        }

        // Immersive Aeronautics: players next to the ship's far half (straddling a portal into their dimension) see it
        // directly, not only through a portal
        final HashMap<UUID, ServerPlayer>[] farHalfViewers = new HashMap[]{viewers};
        IPStraddle.forEachFarHalfViewer(sub, player -> {
            if (player.level() != level) {
                if (farHalfViewers[0] == null) {
                    farHalfViewers[0] = new HashMap<>();
                }
                farHalfViewers[0].put(player.getUUID(), player);
            }
        });
        viewers = farHalfViewers[0];

        if (viewers != null) {
            for (Map.Entry<UUID, ServerPlayer> viewer : viewers.entrySet()) {
                players.put(viewer.getKey(), viewer.getValue());
                visibleByPlayer.computeIfAbsent(viewer.getKey(), id -> new HashMap<>()).put(sub.getUniqueId(), sub);
                shipLevels.put(sub.getUniqueId(), level);
            }
        }
    }

    /**
     * After a dimension change re-sends a full sync of every ship in the arrival dimension (they may have
     * only been known to the client as portal-side views).
     */
    private static Synced sendStartTracking(ServerPlayer player, ServerLevel shipLevel, ServerSubLevel sub) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(shipLevel);
        if (container == null) {
            return null;
        }

        SubLevelTrackingSystem trackingSystem = container.trackingSystem();
        if (trackingSystem == null) {
            return null;
        }

        try {
            long plotCoordinate = IPSubLevelTrackingSystemAccessor.ip_getSubLevelLong(sub, container);
            IPSablePortalSync.beginForceRedirect();

            try {
                PacketRedirection.withForceRedirect(shipLevel, () -> accessor(trackingSystem).ip_sendFullSync(player, sub, null));
            } finally {
                IPSablePortalSync.endForceRedirect();
            }

            if (!activeLogged) {
                activeLogged = true;
                LOGGER.debug(
                    "[IPShipThroughPortal] active - syncing sable craft to players viewing {} through a portal", shipLevel.dimension().location()
                );
            }

            Synced synced = new Synced(shipLevel.dimension(), plotCoordinate);

            try {
                for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
                    synced.sentChunks.add(holder.getPos().toLong());
                }
            } catch (Throwable ignored) {
            }

            LOGGER.debug(
                "[IPShipThroughPortal] sent craft {} in {} to player {} (viewing from {}, {} plot chunk(s))",
                sub.getUniqueId(),
                shipLevel.dimension().location(),
                player.getUUID(),
                player.level().dimension().location(),
                synced.sentChunks.size()
            );
            return synced;
        } catch (Throwable e) {
            LOGGER.warn("[IPShipThroughPortal] full sync failed for craft {}: {}", sub.getUniqueId(), e.toString());
            return null;
        }
    }

    /**
     * A ship just arrived in {@code level} (a straddle hand-off): players who see it from another dimension get it now
     * rather than at the next rescan (up to {@value #RESCAN_INTERVAL_TICKS} ticks later, while their client holds the
     * old copy frozen).
     */
    public static void onShipArrived(ServerLevel level, ServerSubLevel sub) {
        if (!isAccessorAvailable()) {
            return;
        }
        HashMap<UUID, Map<UUID, ServerSubLevel>> visibleByPlayer = new HashMap<>();
        HashMap<UUID, ServerPlayer> players = new HashMap<>();
        HashMap<UUID, ServerLevel> shipLevels = new HashMap<>();
        collectViewers(level, sub, visibleByPlayer, players, shipLevels);
        for (ServerPlayer player : players.values()) {
            Map<UUID, Synced> playerSynced = SYNCED.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
            if (playerSynced.containsKey(sub.getUniqueId()) || playerSynced.size() >= MAX_SHIPS_PER_PLAYER) {
                continue;
            }
            Synced synced = sendStartTracking(player, level, sub);
            if (synced != null) {
                playerSynced.put(sub.getUniqueId(), synced);
            }
        }
        SYNCED.values().removeIf(Map::isEmpty);
    }

    /**
     * Sable stops tracking {@code sub} for {@code player}, who left {@code shipLevel} (walked through a portal). If they
     * still view one of the ship's chunks, it is synced through the portal from now on instead: the client keeps its copy
     * and gets its movement from {@link #sendMovement} in the next tick, where it used to remove the ship and get it back
     * at the next rescan (every ship blinked). The next rescan stops it if it is not in view after all.
     *
     * @return whether the ship was taken over (Sable must not send its removal)
     */
    public static boolean adoptFromSable(ServerPlayer player, ServerLevel shipLevel, ServerSubLevel sub) {
        if (!isAccessorAvailable() || sub.isRemoved() || IPShipRagdollGuard.isRagdollSubLevel(sub) || player.level() == shipLevel) {
            return false;
        }
        HashMap<UUID, Map<UUID, ServerSubLevel>> visibleByPlayer = new HashMap<>();
        collectViewers(shipLevel, sub, visibleByPlayer, new HashMap<>(), new HashMap<>());
        if (!visibleByPlayer.containsKey(player.getUUID())) {
            return false;
        }
        Map<UUID, Synced> playerSynced = SYNCED.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        if (playerSynced.containsKey(sub.getUniqueId())) {
            return true;
        }
        if (playerSynced.size() >= MAX_SHIPS_PER_PLAYER) {
            return false;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(shipLevel);
        if (container == null) {
            return false;
        }
        Synced synced = new Synced(shipLevel.dimension(), IPSubLevelTrackingSystemAccessor.ip_getSubLevelLong(sub, container));
        for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
            synced.sentChunks.add(holder.getPos().toLong());
        }
        playerSynced.put(sub.getUniqueId(), synced);
        LOGGER.debug("[IPShipThroughPortal] ship {} stays on the client of {} after they left {}: synced through the portal",
            sub.getUniqueId(), player.getGameProfile().getName(), shipLevel.dimension().location());
        return true;
    }

    /**
     * Sable starts tracking {@code sub} for {@code player}, who arrived in {@code shipLevel}. If the ship is synced to
     * them through a portal already, their client has it: Sable takes it over without a full sync (only plot chunks
     * loaded since the last top-up are sent), so the ship does not blink.
     *
     * @return whether the full sync must be skipped
     */
    public static boolean handOverToSable(ServerPlayer player, ServerLevel shipLevel, ServerSubLevel sub) {
        Map<UUID, Synced> playerSynced = SYNCED.get(player.getUUID());
        Synced synced = playerSynced == null ? null : playerSynced.get(sub.getUniqueId());
        if (synced == null || !synced.dim.equals(shipLevel.dimension())) {
            return false;
        }
        topUpChunks(player, shipLevel, sub, synced);
        playerSynced.remove(sub.getUniqueId());
        if (playerSynced.isEmpty()) {
            SYNCED.remove(player.getUUID());
        }
        LOGGER.debug("[IPShipThroughPortal] ship {} was already on the client of {} through a portal: Sable tracks it without a full sync",
            sub.getUniqueId(), player.getGameProfile().getName());
        return true;
    }

    /**
     * Immersive Aeronautics: the players the given ship is currently synced to through a portal (players in
     * another dimension that see it). Used to send them data Sable does not cover, e.g. rope strands.
     */
    public static List<ServerPlayer> getPortalViewers(MinecraftServer server, UUID shipId) {
        List<ServerPlayer> viewers = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Synced>> entry : SYNCED.entrySet()) {
            if (entry.getValue().containsKey(shipId)) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    viewers.add(player);
                }
            }
        }
        return viewers;
    }

    /**
     * A straddling ship is about to be handed off from {@code level}: its trackers there get its last pose once more,
     * over the connection. Sable sends movement over its UDP path (in singleplayer a local event loop), which is not
     * ordered with the connection, so the last snapshot could reach the client after the hand-off's stop and start
     * packets; the client's hand-off latch then found no pose the old and the new copy share and the new copy stalled
     * (a pause of a ship pushed into a portal you see it through). The snapshot carries the tick Sable sent it with,
     * so the copy arriving the other way is dropped as a duplicate.
     */
    public static void resendLastPose(ServerLevel level, ServerSubLevel sub) {
        if (!isAccessorAvailable()) {
            return;
        }
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null || container.trackingSystem() == null || sub.getTrackingPlayers().isEmpty()) {
                return;
            }
            // Sable counts the tick up after sending this tick's poses
            int tick = container.trackingSystem().getInterpolationTick() - 1;
            ClientboundSableSnapshotDualPacket.Entry entry = new ClientboundSableSnapshotDualPacket.Entry(
                IPSubLevelTrackingSystemAccessor.ip_getSubLevelLong(sub, container),
                new dev.ryanhcode.sable.companion.math.Pose3d(sub.logicalPose()),
                toVector3f(sub.latestLinearVelocity),
                toVector3f(sub.latestAngularVelocity)
            );
            ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(new ClientboundSableSnapshotDualPacket(tick, List.of(entry)));
            for (UUID id : sub.getTrackingPlayers()) {
                if (level.getPlayerByUUID(id) instanceof ServerPlayer player) {
                    player.connection.send(packet);
                }
            }
        } catch (Throwable e) {
            LOGGER.debug("[IPShipThroughPortal] could not resend the last pose of ship {}: {}", sub.getUniqueId(), e.toString());
        }
    }

    /**
     * A ship left {@code dimension} through a portal: every player it was synced to from there gets the stop
     * packet now instead of at the next rescan, so a player who walks into that dimension in the meantime is
     * not left with a frozen copy.
     */
    public static void onShipLeftDimension(MinecraftServer server, UUID shipId, ResourceKey<Level> dimension) {
        for (Map.Entry<UUID, Map<UUID, Synced>> entry : SYNCED.entrySet()) {
            Synced synced = entry.getValue().get(shipId);
            if (synced == null || !synced.dim.equals(dimension)) {
                continue;
            }
            entry.getValue().remove(shipId);
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                sendStopTracking(player, synced);
            }
        }
        SYNCED.values().removeIf(Map::isEmpty);
    }

    /**
     * Immersive Aeronautics: the recipients of a change to a ship's plot chunk: {@code players} (Sable's: those
     * tracking the ship in its own world) plus the players the ship is synced to through a portal or near its far
     * half who already have that chunk. Without them, blocks broken or placed on a ship seen through a portal (or on
     * its far half) would not change on their client.
     */
    public static List<ServerPlayer> withPortalViewers(ServerLevel level, net.minecraft.world.level.ChunkPos pos, List<ServerPlayer> players) {
        if (SYNCED.isEmpty()) {
            return players;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container == null ? null : container.getPlot(pos);
        dev.ryanhcode.sable.sublevel.SubLevel ship = plot == null ? null : plot.getSubLevel();
        if (ship == null) {
            return players;
        }
        UUID shipId = ship.getUniqueId();
        long chunkKey = pos.toLong();
        ArrayList<ServerPlayer> merged = null;
        for (Map.Entry<UUID, Map<UUID, Synced>> entry : SYNCED.entrySet()) {
            Synced synced = entry.getValue().get(shipId);
            if (synced == null || !synced.dim.equals(level.dimension()) || !synced.sentChunks.contains(chunkKey)) {
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || players.contains(player)) {
                continue;
            }
            if (merged == null) {
                merged = new ArrayList<>(players);
            }
            merged.add(player);
        }
        return merged != null ? merged : players;
    }

    /**
     * Immersive Aeronautics: a packet {@code PlayerList.broadcast} sends around a position (a sound, a block event such
     * as a chest lid, a level event) also reaches the players who see that position from another dimension, as
     * dimension-tagged packets: for a ship (plot coordinates) the players it is synced to through a portal or near its
     * far half, otherwise the players watching the chunk through a portal within the broadcast distance (what
     * Immersive Portals' own {@code broadcast} did before Sable's overwrite replaced it). Players in {@code level}
     * itself are Sable's.
     */
    public static void broadcastToOtherDimensions(ServerLevel level, double x, double y, double z, double distance,
                                                  @org.jetbrains.annotations.Nullable net.minecraft.world.entity.player.Player except,
                                                  Packet<?> packet) {
        broadcastToOtherDimensions(level, x, y, z, distance, except, () -> packet);
    }

    /**
     * As {@link #broadcastToOtherDimensions(ServerLevel, double, double, double, double, net.minecraft.world.entity.player.Player, Packet)},
     * the packet made only when someone in another dimension sees the position (server particles: called very often).
     */
    public static void broadcastToOtherDimensions(ServerLevel level, double x, double y, double z, double distance,
                                                  @org.jetbrains.annotations.Nullable net.minecraft.world.entity.player.Player except,
                                                  java.util.function.Supplier<Packet<?>> packetMaker) {
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(x, y, z);
        net.minecraft.world.level.ChunkPos chunkPos = new net.minecraft.world.level.ChunkPos(pos);
        List<ServerPlayer> recipients;
        if (IPSableCompatHelper.isPlotSpacePosition(x, z)) {
            recipients = withPortalViewers(level, chunkPos, List.of());
        } else {
            var records = ImmPtlChunkTracking.getWatchRecordForChunk(level.dimension(), chunkPos.x, chunkPos.z);
            if (records == null) {
                return;
            }
            recipients = new ArrayList<>();
            for (ImmPtlChunkTracking.PlayerWatchRecord record : records.values()) {
                if (record.isLoadedToPlayer && record.player.level() != level && ImmPtlChunkTracking.isPlayerWatchingChunkWithinRadius(
                    record.player, level.dimension(), chunkPos.x, chunkPos.z, (int) distance + 16)) {
                    recipients.add(record.player);
                }
            }
        }
        Packet<?> packet = null;
        for (ServerPlayer player : recipients) {
            if (player != except && player.level() != level) {
                if (packet == null) {
                    packet = packetMaker.get();
                }
                sendRedirected(player, level.dimension(), packet);
            }
        }
    }

    /** Sends a packet about something in {@code dimension} to a player, dimension-tagged when the player is elsewhere. */
    public static void sendToViewer(ServerPlayer player, ResourceKey<Level> dimension, Packet<?> packet) {
        if (player.level().dimension().equals(dimension)) {
            player.connection.send(packet);
        } else {
            sendRedirected(player, dimension, packet);
        }
    }

    /** Sends plot chunks that loaded after the full sync (Sable would normally stream them itself). */
    private static void topUpChunks(ServerPlayer player, ServerLevel shipLevel, ServerSubLevel sub, Synced synced) {
        try {
            ServerLevelPlot plot = sub.getPlot();
            Collection<PlotChunkHolder> loadedChunks = plot.getLoadedChunks();
            if (loadedChunks.isEmpty()) {
                return;
            }

            ArrayList<PlotChunkHolder> missing = new ArrayList<>();

            for (PlotChunkHolder holder : loadedChunks) {
                long chunkKey = holder.getPos().toLong();
                if (!synced.sentChunks.contains(chunkKey)) {
                    missing.add(holder);
                }
            }

            if (missing.isEmpty()) {
                return;
            }

            IPSablePortalSync.beginForceRedirect();

            try {
                PacketRedirection.withForceRedirect(shipLevel, () -> {
                    for (PlotChunkHolder holder : missing) {
                        try {
                            SubLevelPlayerChunkSender.sendChunk(packet -> player.connection.send(packet), plot.getLightEngine(), holder.getChunk());
                            synced.sentChunks.add(holder.getPos().toLong());
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } finally {
                IPSablePortalSync.endForceRedirect();
            }

            LOGGER.debug("[IPShipThroughPortal] topped up {} late plot chunk(s) for craft {}", missing.size(), sub.getUniqueId());
        } catch (Throwable e) {
            LOGGER.warn("[IPShipThroughPortal] chunk top-up failed: {}", e.toString());
        }
    }

    private static void sendStopTracking(ServerPlayer player, Synced synced) {
        try {
            sendRedirected(player, synced.dim, new ClientboundCustomPayloadPacket(new ClientboundStopTrackingSubLevelPacket(synced.plotCoordinate)));
        } catch (Throwable e) {
            LOGGER.warn("[IPShipThroughPortal] stop-tracking failed: {}", e.toString());
        }
    }

    /** Per tick: sends every synced ship's pose/velocity snapshot to its viewers, grouped by ship dimension. */
    private static void sendMovement(MinecraftServer server) {
        if (SYNCED.isEmpty()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        HashMap<ResourceKey<Level>, Map<UUID, ServerSubLevel>> indexCache = new HashMap<>();

        for (Map.Entry<UUID, Map<UUID, Synced>> playerEntry : SYNCED.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            if (player == null) {
                continue;
            }

            Long lastMs = LAST_MOVE_MS.put(playerEntry.getKey(), nowMs);
            int msSinceLast = lastMs == null ? 50 : (int) Math.max(1L, Math.min(2000L, nowMs - lastMs));
            HashMap<ResourceKey<Level>, List<ClientboundSableSnapshotDualPacket.Entry>> entriesByDim = new HashMap<>();

            for (Map.Entry<UUID, Synced> shipEntry : playerEntry.getValue().entrySet()) {
                Synced synced = shipEntry.getValue();
                ServerLevel shipLevel = server.getLevel(synced.dim);
                if (shipLevel != null) {
                    ServerSubLevel sub = indexFor(shipLevel, indexCache).get(shipEntry.getKey());
                    if (sub != null && !sub.isRemoved()) {
                        entriesByDim.computeIfAbsent(synced.dim, dim -> new ArrayList<>())
                            .add(
                                new ClientboundSableSnapshotDualPacket.Entry(
                                    synced.plotCoordinate,
                                    sub.logicalPose(),
                                    toVector3f(sub.latestLinearVelocity),
                                    toVector3f(sub.latestAngularVelocity)
                                )
                            );
                    }
                }
            }

            for (Map.Entry<ResourceKey<Level>, List<ClientboundSableSnapshotDualPacket.Entry>> dimEntry : entriesByDim.entrySet()) {
                sendSnapshots(server, player, dimEntry.getKey(), msSinceLast, dimEntry.getValue());
            }
        }
    }

    private static void sendSnapshots(
        MinecraftServer server, ServerPlayer player, ResourceKey<Level> shipDim, int msSinceLast, List<ClientboundSableSnapshotDualPacket.Entry> entries
    ) {
        try {
            ServerLevel shipLevel = server.getLevel(shipDim);
            if (shipLevel == null) {
                return;
            }

            ServerSubLevelContainer container = SubLevelContainer.getContainer(shipLevel);
            if (container == null || container.trackingSystem() == null) {
                return;
            }

            int interpolationTick = container.trackingSystem().getInterpolationTick();
            sendRedirected(player, shipDim, new ClientboundCustomPayloadPacket(new ClientboundSableSnapshotInfoDualPacket(msSinceLast, interpolationTick, false)));
            sendRedirected(player, shipDim, new ClientboundCustomPayloadPacket(new ClientboundSableSnapshotDualPacket(interpolationTick, entries)));
        } catch (Throwable ignored) {
        }
    }

    private static Map<UUID, ServerSubLevel> indexFor(ServerLevel level, Map<ResourceKey<Level>, Map<UUID, ServerSubLevel>> indexCache) {
        return indexCache.computeIfAbsent(level.dimension(), dim -> {
            HashMap<UUID, ServerSubLevel> index = new HashMap<>();

            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container != null) {
                    for (ServerSubLevel sub : List.copyOf(container.getAllSubLevels())) {
                        index.put(sub.getUniqueId(), sub);
                    }
                }
            } catch (Throwable ignored) {
            }

            return index;
        });
    }

    @SuppressWarnings("unchecked")
    private static void sendRedirected(ServerPlayer player, ResourceKey<Level> dimension, Packet<?> packet) {
        IPSablePortalSync.beginForceRedirect();

        try {
            PacketRedirection.sendRedirectedMessage(player, dimension, (Packet<ClientGamePacketListener>) packet);
        } finally {
            IPSablePortalSync.endForceRedirect();
        }
    }

    private static Vector3f toVector3f(Vector3d vec) {
        return new Vector3f((float) vec.x, (float) vec.y, (float) vec.z);
    }

    /** One ship synced to one player: where it lives, its plot id and which plot chunks were already sent. */
    private static final class Synced {
        final ResourceKey<Level> dim;
        final long plotCoordinate;
        final Set<Long> sentChunks = new HashSet<>();

        Synced(ResourceKey<Level> dim, long plotCoordinate) {
            this.dim = dim;
            this.plotCoordinate = plotCoordinate;
        }
    }
}
