/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap;
import qouteall.imm_ptl.core.compat.mixin_sable.IPLevelPlotAccessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Registers the plot chunks IP's client chunk map receives with the ship owning them, as Sable does without IP.
 * <p>
 * Sable stores a client ship's chunks in its plot ({@code LevelPlot.getLoadedChunks}, one {@code PlotChunkHolder} per
 * chunk) through {@code @Inject}s on {@code ClientChunkCache}'s {@code replaceWithPacketData}, {@code getChunk} and
 * {@code drop}. IP replaces each client level's chunk cache with {@code ImmPtlClientChunkMap}, whose overrides never
 * call super, so with IP the plot chunks only went into IP's map: ships drew fine (Sable reads their blocks through
 * the level), but every client plot stayed empty. Whatever walks a ship's chunks on the client found nothing: Create
 * Dynamic Lights found no lamps on any ship, so their light never reached the world or other ships; Sable's client
 * block-entity actors of a ship were never registered either.
 * <p>
 * Here the plot's holders follow IP's map: a plot chunk that loads is added to its ship's plot (or its holder is
 * replaced when IP made a new chunk object for it), one IP drops is removed, and a ship that arrives after some of its
 * chunks (IP keeps chunks whose plot does not exist yet, Sable drops them) picks those up when it is added. A plot's
 * holders are still cleared by Sable when the ship is removed ({@code LevelPlot.onRemove}). Render thread only, like
 * the chunk map's packet handling.
 */
public final class IPSableClientPlotChunks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean SABLE = LoadingModList.get().getModFileById("sable") != null;

    private static final Set<SubLevelContainer> OBSERVED = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean loggedFirst;
    private static int replacedLogs;

    private IPSableClientPlotChunks() {
    }

    private static boolean isPlotSpace(int cx, int cz) {
        return Math.abs(cx) > IPSableCompatHelper.PLOT_SPACE_CHUNK_THRESHOLD
            || Math.abs(cz) > IPSableCompatHelper.PLOT_SPACE_CHUNK_THRESHOLD;
    }

    /** After IP's chunk map stored a chunk received from the server. */
    public static void onChunkLoaded(ClientLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (!SABLE || !isPlotSpace(pos.x, pos.z)) {
            return;
        }
        Impl.onChunkLoaded(level, chunk);
    }

    /** After IP's chunk map removed a chunk. */
    public static void onChunkDropped(ClientLevel level, ChunkPos pos) {
        if (!SABLE || !isPlotSpace(pos.x, pos.z)) {
            return;
        }
        Impl.onChunkDropped(level, pos);
    }

    /** Separate class so Sable's types are only loaded once a plot-space chunk shows up. */
    private static final class Impl {
        static void onChunkLoaded(ClientLevel level, LevelChunk chunk) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null || !container.inBounds(chunk.getPos())) {
                return;
            }
            observe(container, level);
            LevelPlot plot = container.getPlot(chunk.getPos());
            if (plot == null) {
                // the ship is not there yet; it picks the chunk up from IP's map when it is added
                return;
            }
            register(plot, chunk);
        }

        static void onChunkDropped(ClientLevel level, ChunkPos pos) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null || !container.inBounds(pos)) {
                return;
            }
            LevelPlot plot = container.getPlot(pos);
            if (plot == null) {
                return;
            }
            PlotChunkHolder holder = plot.getChunkHolder(plot.toLocal(pos));
            if (holder != null) {
                remove(plot, plot.toLocal(pos), holder);
            }
        }

        private static void register(LevelPlot plot, LevelChunk chunk) {
            ChunkPos pos = chunk.getPos();
            ChunkPos local = plot.toLocal(pos);
            PlotChunkHolder existing = plot.getChunkHolder(local);
            if (existing != null) {
                if (existing.getChunk() == chunk) {
                    return;
                }
                if (replacedLogs < 5) {
                    replacedLogs++;
                    LOGGER.info("[IPSablePlotChunks] replacing the holder of plot chunk {} of ship {} (IP's map has a new chunk object for it)",
                        pos, plot.getSubLevel().getUniqueId());
                }
                remove(plot, local, existing);
            }
            PlotChunkHolder holder = PlotChunkHolder.create(chunk.getLevel(), pos, plot.getLightEngine(), chunk);
            plot.addChunkHolder(local, holder, false);
            if (!loggedFirst) {
                loggedFirst = true;
                LOGGER.info("[IPSablePlotChunks] registering client plot chunks with their ships (first: chunk {} of ship {} in {})",
                    pos, plot.getSubLevel().getUniqueId(), chunk.getLevel().dimension().location());
            }
        }

        private static void remove(LevelPlot plot, ChunkPos local, PlotChunkHolder holder) {
            IPLevelPlotAccessor accessor = (IPLevelPlotAccessor) plot;
            accessor.ip_getLoadedChunkList().remove(holder);
            PlotChunkHolder[] holders = accessor.ip_getChunkHolders();
            // the array is the plot's square of chunks, row by row (LevelPlot.getChunkHolder)
            int side = (int) Math.round(Math.sqrt(holders.length));
            int index = local.z * side + local.x;
            if (index >= 0 && index < holders.length && holders[index] == holder) {
                holders[index] = null;
            }
            ChunkPos pos = holder.getPos();
            accessor.ip_getBlockEntityActors().keySet().removeIf(blockPos ->
                (blockPos.getX() >> 4) == pos.x && (blockPos.getZ() >> 4) == pos.z);
        }

        /** Picks up, for each ship added to the container, the chunks of its plot IP's map already holds. */
        private static void observe(SubLevelContainer container, ClientLevel level) {
            if (!OBSERVED.add(container)) {
                return;
            }
            container.addObserver(new SubLevelObserver() {
                @Override
                public void onSubLevelAdded(SubLevel subLevel) {
                    if (subLevel instanceof ClientSubLevel ship) {
                        backfill(level, ship);
                    }
                }
            });
        }

        private static void backfill(ClientLevel level, ClientSubLevel ship) {
            if (!(level.getChunkSource() instanceof ImmPtlClientChunkMap chunkMap)) {
                return;
            }
            LevelPlot plot = ship.getPlot();
            if (plot == null) {
                return;
            }
            ChunkPos min = plot.getChunkMin();
            ChunkPos max = plot.getChunkMax();
            List<LevelChunk> found = chunkMap.readChunkMap(map -> {
                List<LevelChunk> chunks = new ArrayList<>();
                for (int x = min.x; x <= max.x; x++) {
                    for (int z = min.z; z <= max.z; z++) {
                        LevelChunk chunk = map.get(ChunkPos.asLong(x, z));
                        if (chunk != null) {
                            chunks.add(chunk);
                        }
                    }
                }
                return chunks;
            });
            for (LevelChunk chunk : found) {
                register(plot, chunk);
            }
            if (!found.isEmpty()) {
                LOGGER.info("[IPSablePlotChunks] ship {} in {} picked up {} plot chunks that arrived before it",
                    ship.getUniqueId(), level.dimension().location(), found.size());
            }
        }
    }
}
