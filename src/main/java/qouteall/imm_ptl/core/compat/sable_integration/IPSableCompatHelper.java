/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.mixin.common.chunk_sync.IPChunkMapAccessor;

/**
 * Sable packet-routing helpers wired into IP's packet redirection.
 * <p>
 * {@link #getPlayersMerged} widens IP's {@code ChunkMap.getPlayers} override so mods that use it to pick
 * packet recipients (Sable) also see vanilla-tracked players. {@link #isDimensionAgnosticPacket} exempts
 * {@code sable}/{@code sable_*}/{@code veil}/{@code ragdoll_reactions} custom payloads from IP's
 * dimension-redirect wrapper unless {@link IPSablePortalSync#isForceRedirect()} is set.
 */
public final class IPSableCompatHelper {
    /**
     * Sable keeps sub-level ("plot") chunks beyond this chunk coordinate on either axis; an entity whose
     * chunk is past it is on a ship.
     */
    public static final int PLOT_SPACE_CHUNK_THRESHOLD = 100000;
    /** {@link #PLOT_SPACE_CHUNK_THRESHOLD} in blocks. */
    public static final double PLOT_SPACE_BLOCK_THRESHOLD = PLOT_SPACE_CHUNK_THRESHOLD * 16.0;

    private IPSableCompatHelper() {
    }

    /** True when the chunk lies in Sable's plot space (see {@link #PLOT_SPACE_CHUNK_THRESHOLD}). */
    public static boolean isPlotSpaceChunk(ChunkPos chunkPos) {
        return Math.abs(chunkPos.x) > PLOT_SPACE_CHUNK_THRESHOLD || Math.abs(chunkPos.z) > PLOT_SPACE_CHUNK_THRESHOLD;
    }

    /** True when the block position lies in Sable's plot space (see {@link #PLOT_SPACE_BLOCK_THRESHOLD}). */
    public static boolean isPlotSpacePosition(double x, double z) {
        return Math.abs(x) > PLOT_SPACE_BLOCK_THRESHOLD || Math.abs(z) > PLOT_SPACE_BLOCK_THRESHOLD;
    }

    /**
     * True when the entity rides a vehicle in Sable's plot space (a seat on a ship). Sable keeps such a rider at the
     * ship's world position every tick, but the vehicle stays in the plot; IP's own portal teleport must leave them
     * alone, since it would pull the seat out of the ship and into the other dimension. The ship crossing carries
     * them ({@link IPShipRiderCarry}).
     */
    public static boolean isRidingOnShip(Entity entity) {
        Entity vehicle = entity.getVehicle();
        // a seat proxy carries its rider through the portal with a straddling ship's seat (IPStraddleSeats)
        return vehicle != null && (isPlotSpacePosition(vehicle.getX(), vehicle.getZ())
            || qouteall.imm_ptl.core.compat.sable_straddle.IPSeatProxies.isProxy(vehicle));
    }

    public static List<ServerPlayer> getPlayersMerged(ChunkMap chunkMap, ServerLevel level, ChunkPos pos, boolean boundaryOnly) {
        List<ServerPlayer> ipPlayers = ImmPtlChunkTracking.getPlayersViewingChunk(level.dimension(), pos.x, pos.z, boundaryOnly);
        if (boundaryOnly) {
            return ipPlayers;
        }

        IPChunkMapAccessor accessor = (IPChunkMapAccessor) chunkMap;
        ArrayList<ServerPlayer> merged = null;

        for (ServerPlayer player : accessor.ip_getPlayerMap().getAllPlayers()) {
            if (accessor.ip_isChunkTracked(player, pos.x, pos.z) && !ipPlayers.contains(player)) {
                if (merged == null) {
                    merged = new ArrayList<>(ipPlayers);
                }

                merged.add(player);
            }
        }

        return merged != null ? merged : ipPlayers;
    }

    public static boolean isDimensionAgnosticPacket(Packet<?> packet) {
        if (IPSablePortalSync.isForceRedirect()) {
            return false;
        } else if (!(packet instanceof ClientboundCustomPayloadPacket)) {
            return false;
        } else {
            try {
                String namespace = ((ClientboundCustomPayloadPacket) packet).payload().type().id().getNamespace();
                return namespace.equals("sable") || namespace.equals("veil") || namespace.startsWith("sable_") || namespace.equals("ragdoll_reactions");
            } catch (Throwable e) {
                return false;
            }
        }
    }
}
