/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.mc_utils.ServerTaskList;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.MyTaskList;

/**
 * Create trains through a Create Teleporters portal pair within one dimension. Create links two portal-track nodes only
 * across dimensions (a same-dimension pair would be an ordinary straight edge through the world); Create Teleporters
 * makes its own pairs work by giving each side of the portal a dimension key of its own, which switches on for a portal
 * track carrying its two tags ({@code CTPTrainBase}, {@code CTPTrainTargetBase}: the portal bases at this end and the
 * other, as its {@code QuantumTrainPortals.stamp} writes them). Its immersive portals have no portal blocks of their
 * own, so its trains never see them; our portal blocks ({@link qouteall.imm_ptl.core.compat.IPFramedPortalBlocks}) give
 * Create a portal track there, and this tags the pair the way Create Teleporters tags its own. If the edge is then not
 * a portal edge (Create Teleporters changed), the pair is unlinked, as a train would otherwise drive straight through
 * the world.
 */
public final class IPCreateTeleportersTrains {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPCreateTeleportersTrains] ";
    private static final String MOD_ID = "createteleporters";
    private static final String OWNER_PREFIX = "createteleporters:";
    private static final ResourceLocation BASE_BLOCK = ResourceLocation.fromNamespaceAndPath(MOD_ID, "custom_portal_base");
    private static final String CLUSTER_ID = "immersivePortalClusterId";
    private static final String BASE = "CTPTrainBase";
    private static final String TARGET_BASE = "CTPTrainTargetBase";
    /** Ticks after linking until Create has added both tracks to its graph (their scheduled ticks run first). */
    private static final int VERIFY_TICKS = 3;
    /** How far from a portal's centre its base can be: Create Teleporters frames are at most 23 blocks across. */
    private static final double BASE_SEARCH = 13;

    private IPCreateTeleportersTrains() {
    }

    /** Whether {@code portal} is one of Create Teleporters' immersive portals. */
    public static boolean isTheirs(final Portal portal) {
        return portal.portalTag != null && portal.portalTag.startsWith(OWNER_PREFIX) && ModList.get().isLoaded(MOD_ID);
    }

    /** The portal bases at both ends of a Create Teleporters portal: this end first. */
    public record Bases(BlockPos here, BlockPos there) {
    }

    public static @Nullable Bases bases(final ServerLevel level, final Portal portal) {
        if (!isTheirs(portal)) {
            return null;
        }
        final String clusterId = portal.portalTag.substring(OWNER_PREFIX.length());
        final BlockPos here = findBase(level, portal.getOriginPos(), clusterId, null);
        final BlockPos there = here == null ? null : findBase(level, portal.getDestPos(), clusterId, here);
        return there == null ? null : new Bases(here, there);
    }

    /**
     * {@link IPCreatePortalTracks} has just linked the portal tracks at {@code track} and {@code exitTrack}: tags both now,
     * before Create adds them to its graph (their scheduled ticks), and checks the edge a few ticks later.
     */
    public static void tag(final ServerLevel level, final BlockPos track, final BlockPos exitTrack, final Bases bases) {
        if (!(level.getBlockEntity(track) instanceof final TrackBlockEntity first)
            || !(level.getBlockEntity(exitTrack) instanceof final TrackBlockEntity second)) {
            return;
        }
        stamp(first, bases.here(), bases.there());
        stamp(second, bases.there(), bases.here());
        final BlockPos a = track.immutable();
        final BlockPos b = exitTrack.immutable();
        ServerTaskList.of(level.getServer()).addTask(MyTaskList.withDelay(VERIFY_TICKS, MyTaskList.oneShotTask(() -> verify(level, a, b, bases))));
    }

    /** Removes our Create Teleporters tags from a track that is no longer a portal track. */
    public static void untag(final TrackBlockEntity track) {
        track.getPersistentData().remove(BASE);
        track.getPersistentData().remove(TARGET_BASE);
    }

    private static void verify(final ServerLevel level, final BlockPos track, final BlockPos exitTrack, final Bases bases) {
        if (!(level.getBlockEntity(track) instanceof final TrackBlockEntity first) || !boundTo(first, level, exitTrack)) {
            return;
        }
        if (isPortalEdge(level, track, level.getBlockState(track), exitTrack, level.getBlockState(exitTrack))) {
            LOGGER.info(TAG + "trains cross the Create Teleporters portal between tracks {} and {} in {} (bases {}, {})",
                track, exitTrack, level.dimension().location(), bases.here(), bases.there());
            return;
        }
        LOGGER.warn(TAG + "Create Teleporters did not take the portal tracks at {} and {} in {} as a portal (its train code"
            + " changed?): unlinking them, or trains would run straight through the world", track, exitTrack, level.dimension().location());
        IPCreatePortalTracks.unlink(level, track);
    }

    private static boolean boundTo(final TrackBlockEntity track, final ServerLevel level, final BlockPos other) {
        return track.boundLocation != null && track.boundLocation.getFirst().equals(level.dimension())
            && track.boundLocation.getSecond().equals(other);
    }

    private static void stamp(final TrackBlockEntity track, final BlockPos base, final BlockPos targetBase) {
        final CompoundTag data = track.getPersistentData();
        data.putLong(BASE, base.asLong());
        data.putLong(TARGET_BASE, targetBase.asLong());
        track.setChanged();
    }

    /** Create's graph joins the two portal-plane nodes with a portal (zero-length) edge. */
    private static boolean isPortalEdge(
        final ServerLevel level, final BlockPos first, final BlockState firstState, final BlockPos second, final BlockState secondState
    ) {
        final TrackNodeLocation a = endpoint(level, first, firstState);
        final TrackNodeLocation b = endpoint(level, second, secondState);
        if (a == null || b == null) {
            return false;
        }
        for (final TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            final TrackNode nodeA = graph.locateNode(a);
            final TrackNode nodeB = graph.locateNode(b);
            if (nodeA != null && nodeB != null) {
                final TrackEdge edge = graph.getConnectionsFrom(nodeA).get(nodeB);
                return edge != null && edge.isInterDimensional();
            }
        }
        return false;
    }

    /** The node at the portal face of a portal track: its centre plus half its axis, at track height. */
    private static @Nullable TrackNodeLocation endpoint(final ServerLevel level, final BlockPos pos, final BlockState state) {
        if (!(state.getBlock() instanceof final TrackBlock block) || !state.getValue(TrackBlock.SHAPE).isPortal()) {
            return null;
        }
        final Vec3 plane = Vec3.atBottomCenterOf(pos).add(0, block.getElevationAtCenter(level, pos, state), 0)
            .add(block.getTrackAxes(level, pos, state).getFirst().scale(0.5));
        return new TrackNodeLocation(plane).in(level);
    }

    private static @Nullable BlockPos findBase(final ServerLevel level, final Vec3 near, final String clusterId, final @Nullable BlockPos except) {
        final AABB box = new AABB(near, near).inflate(BASE_SEARCH);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int cx = ((int) Math.floor(box.minX)) >> 4; cx <= ((int) Math.floor(box.maxX)) >> 4; cx++) {
            for (int cz = ((int) Math.floor(box.minZ)) >> 4; cz <= ((int) Math.floor(box.maxZ)) >> 4; cz++) {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    final BlockPos pos = blockEntity.getBlockPos();
                    if (pos.equals(except) || !box.contains(Vec3.atCenterOf(pos))
                        || !BASE_BLOCK.equals(BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()))
                        || !clusterId.equals(blockEntity.getPersistentData().getString(CLUSTER_ID))) {
                        continue;
                    }
                    final double distance = pos.distToCenterSqr(near);
                    if (distance < bestDistance) {
                        best = pos;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }
}
