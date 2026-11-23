/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import dev.ryanhcode.offroad.network.borehead_bearing.ClientboundMultiMiningSync;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import foundry.veil.api.network.VeilPacketManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipThroughPortalSync;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddle;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Offroad's borehead (Create Aeronautics) grinding through a portal its ship straddles. Each tick the bearing gathers
 * the blocks around its rock cutting wheels, at the wheels' world positions in its own level, and hands them to that
 * level's mining manager. A wheel past the plane of a portal the ship straddles is in the other world there: the blocks
 * it meets are the destination's, at the mapped positions, and the own level's blocks behind the portal are left alone,
 * as the ship's far half does not touch them either. {@code MixinBoreheadBearingBlockEntity_Portals} asks {@link #far}
 * about each block the bearing looks at, and hands the ones past a plane to the destination's mining manager, which
 * breaks them there.
 * <p>
 * The cracks: a mining manager sends its progress to the players tracking the position of the bearing breaking a block
 * ({@code MultiMiningServerManager.handleTicking}). In another dimension than the bearing's own, nobody tracks that
 * (a plot position of the bearing's level), so {@code MixinMultiMiningServerManager_Portals} sends the progress of such
 * bearings ({@link #markFarSupplier}) to the players who have the blocks' chunks instead, dimension-tagged for those
 * who see them through a portal. The client applies it in that dimension ({@code IPBoreheadPortalsClient}).
 */
public final class IPBoreheadPortals {
    /** How long [ticks] a bearing counts as mining in a level after it last handed it a block: past Offroad's 20-tick timeout, after which the progress is cleared. */
    private static final long FAR_SUPPLIER_TICKS = 100L;

    /** Bearings mining in a level other than their own, by that level: their position, and the game time last seen. */
    private static final Map<ServerLevel, Map<BlockPos, Long>> FAR_SUPPLIERS = new WeakHashMap<>();

    private IPBoreheadPortals() {
    }

    /**
     * A block a borehead looks at that is past the plane of a portal its ship straddles.
     *
     * @param level the portal's destination
     * @param pos   the block there (world position)
     * @param state its state, air when its chunk is not loaded
     */
    public record Far(ServerLevel level, BlockPos pos, BlockState state) {
    }

    /**
     * @return the far sides of the portals the ship carrying {@code bearing} straddles (server), empty when it is on no
     * straddling ship
     */
    public static List<IPStraddle.FarSide> farSides(final BlockEntity bearing) {
        final Level level = bearing.getLevel();
        if (level == null || level.isClientSide()) {
            return List.of();
        }
        final SubLevel ship = Sable.HELPER.getContaining(bearing);
        if (ship == null || !IPStraddle.straddles(ship)) {
            return List.of();
        }
        return IPStraddle.farSidesOf(ship);
    }

    /**
     * @param pos a block of the bearing's own level (world position) the borehead looks at
     * @return where that block is when it is past the plane of one of {@code sides}, or null when it is in the own level
     */
    @Nullable
    public static Far far(final List<IPStraddle.FarSide> sides, final BlockPos pos) {
        final double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        for (final IPStraddle.FarSide side : sides) {
            if (!side.contains(x, y, z)) {
                continue;
            }
            final BlockPos mapped = BlockPos.containing(side.map().mapPoint(new Vec3(x, y, z)));
            final ServerLevel dest = side.dest();
            // never load a chunk for this: one not loaded has nothing to grind yet
            final BlockState state = dest.isLoaded(mapped) ? dest.getBlockState(mapped) : Blocks.AIR.defaultBlockState();
            return new Far(dest, mapped, state);
        }
        return null;
    }

    /**
     * Notes that the borehead bearing at {@code bearing} (a position of its own level) hands {@code level}, another
     * level, a block to break: Offroad syncs the cracks under that position.
     */
    public static void markFarSupplier(final ServerLevel level, final BlockPos bearing) {
        final Map<BlockPos, Long> suppliers = FAR_SUPPLIERS.computeIfAbsent(level, l -> new HashMap<>());
        if (suppliers.size() > 256) {
            suppliers.values().removeIf(seen -> level.getGameTime() - seen > FAR_SUPPLIER_TICKS);
        }
        suppliers.put(bearing.immutable(), level.getGameTime());
    }

    /**
     * @return whether {@code pos}, a supplier position {@code level}'s mining manager syncs under, is that of a bearing
     * of another level ({@link #markFarSupplier})
     */
    public static boolean isFarSupplier(final ServerLevel level, final BlockPos pos) {
        final Map<BlockPos, Long> suppliers = FAR_SUPPLIERS.get(level);
        if (suppliers == null) {
            return false;
        }
        final Long seen = suppliers.get(pos);
        if (seen == null) {
            return false;
        }
        if (level.getGameTime() - seen > FAR_SUPPLIER_TICKS) {
            suppliers.remove(pos);
            return false;
        }
        return true;
    }

    /**
     * @return a sink that sends Offroad's crack progress for blocks of {@code level} to the players who have their
     * chunks: as is to players in {@code level}, dimension-tagged to players who see it through a portal
     */
    public static VeilPacketManager.PacketSink blockViewers(final ServerLevel level) {
        return new VeilPacketManager.PacketSink() {
            @Override
            public void sendPacket(final CustomPacketPayload... payloads) {
                for (final CustomPacketPayload payload : payloads) {
                    if (payload instanceof final ClientboundMultiMiningSync sync) {
                        sendToViewers(level, sync.inData.keySet(), new ClientboundCustomPayloadPacket(payload));
                    } else {
                        this.sendPacket(new ClientboundCustomPayloadPacket(payload));
                    }
                }
            }

            @Override
            public void sendPacket(final Packet<?> packet) {
                // no block positions to go by: the players in that level
                VeilPacketManager.level(level).sendPacket(packet);
            }
        };
    }

    private static void sendToViewers(final ServerLevel level, final Collection<BlockPos> blocks, final Packet<?> packet) {
        final LongOpenHashSet chunks = new LongOpenHashSet();
        final Set<ServerPlayer> sent = new HashSet<>();
        for (final BlockPos block : blocks) {
            final int chunkX = block.getX() >> 4, chunkZ = block.getZ() >> 4;
            if (!chunks.add(ChunkPos.asLong(chunkX, chunkZ))) {
                continue;
            }
            final var records = ImmPtlChunkTracking.getWatchRecordForChunk(level.dimension(), chunkX, chunkZ);
            if (records == null) {
                continue;
            }
            for (final ImmPtlChunkTracking.PlayerWatchRecord record : records.values()) {
                if (record.isLoadedToPlayer && sent.add(record.player)) {
                    IPShipThroughPortalSync.sendToViewer(record.player, level.dimension(), packet);
                }
            }
        }
    }
}
