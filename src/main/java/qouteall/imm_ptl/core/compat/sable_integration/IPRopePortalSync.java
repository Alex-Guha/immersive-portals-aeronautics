/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;

import java.util.ArrayList;
import java.util.List;

/**
 * Server side of showing Create Aeronautics (Simulated) rope strands through portals.
 * <p>
 * A rope holder sends its strand to {@code chunkMap.getPlayers} of its own chunk, i.e. only to players in its
 * dimension, and sends it raw. Players viewing the holder through a portal are added here: for a holder on a
 * ship, the players {@link IPShipThroughPortalSync} syncs that ship to; for a holder in the world, players in
 * other dimensions that IP's chunk tracking shows the holder's chunk to. Packets to them are dimension-tagged
 * so the client applies them to the right world (see {@code MixinRopePackets_RedirectedLevel}).
 */
public final class IPRopePortalSync {
    private IPRopePortalSync() {
    }

    /** {@code base} plus the players in other dimensions that see the rope holder at {@code pos} through a portal. */
    public static List<ServerPlayer> withPortalViewers(ServerLevel level, BlockPos pos, List<ServerPlayer> base) {
        List<ServerPlayer> viewers;
        if (IPSableCompatHelper.isPlotSpacePosition(pos.getX(), pos.getZ())) {
            SubLevel ship = Sable.HELPER.getContaining(level, pos);
            if (ship == null) {
                return base;
            }
            viewers = IPShipThroughPortalSync.getPortalViewers(level.getServer(), ship.getUniqueId());
        } else {
            viewers = ImmPtlChunkTracking.getPlayersViewingChunk(level.dimension(), pos.getX() >> 4, pos.getZ() >> 4).toList();
        }
        List<ServerPlayer> result = null;
        for (ServerPlayer viewer : viewers) {
            if (viewer.level() != level && !base.contains(viewer)) {
                if (result == null) {
                    result = new ArrayList<>(base);
                }
                result.add(viewer);
            }
        }
        return result == null ? base : result;
    }

    /** A packet sink to one player for something in {@code level}, dimension-tagged when the player is elsewhere. */
    public static VeilPacketManager.PacketSink sinkFor(ServerPlayer player, ServerLevel level) {
        return packet -> IPShipThroughPortalSync.sendToViewer(player, level.dimension(), packet);
    }

    /** A packet sink to several players for something in {@code level}. */
    public static VeilPacketManager.PacketSink sinkFor(List<ServerPlayer> players, ServerLevel level) {
        return packet -> {
            for (ServerPlayer player : players) {
                IPShipThroughPortalSync.sendToViewer(player, level.dimension(), (Packet<?>) packet);
            }
        };
    }
}
