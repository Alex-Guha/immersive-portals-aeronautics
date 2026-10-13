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
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IETrackedEntity;

/**
 * After Sable sends a full sub-level sync to a player ({@code MixinSableTracking_EntityRespawn}), forces IP's
 * entity tracker ({@link IETrackedEntity#ip_onDimensionRemove}) for every entity whose chunk is in plot space
 * and belongs to that plot, so entities on the ship are re-sent to viewers. Disables itself on the first error.
 */
public class IPShipEntityRespawn {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean broken = false;

    public static void onFullSync(ServerLevel level, Object subLevel) {
        if (!broken) {
            try {
                IPShipEntityRespawn.Impl.run(level, subLevel);
            } catch (Throwable e) {
                broken = true;
                LOGGER.warn("[IPShipEntityRespawn] disabled by error: {}", e.toString());
            }
        }
    }

    private static class Impl {
        static void run(ServerLevel level, Object subLevelObj) {
            ServerSubLevel subLevel = (ServerSubLevel) subLevelObj;
            ServerLevelPlot plot = subLevel.getPlot();
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && plot != null) {
                Int2ObjectMap<ChunkMap.TrackedEntity> trackerMap = ((IEChunkMap) level.getChunkSource().chunkMap).ip_getEntityTrackerMap();
                int respawned = 0;

                for (Entity entity : level.getAllEntities()) {
                    ChunkPos chunkPos = entity.chunkPosition();
                    if (IPSableCompatHelper.isPlotSpaceChunk(chunkPos)
                        && container.getPlot(chunkPos) == plot
                        && trackerMap.get(entity.getId()) instanceof IETrackedEntity tracked) {
                        tracked.ip_onDimensionRemove();
                        respawned++;
                    }
                }

                if (respawned > 0) {
                    LOGGER.debug(
                        "[IPShipEntityRespawn] respawning {} ship entities for fresh visuals (plot {}, {})",
                        respawned,
                        plot.plotPos.x,
                        plot.plotPos.z
                    );
                }
            }
        }
    }
}
