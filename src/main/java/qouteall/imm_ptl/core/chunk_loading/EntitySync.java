/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.chunk_loading;

import de.nick1st.imm_ptl.events.DimensionEvents;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IETrackedEntity;
import qouteall.imm_ptl.core.network.PacketRedirection;
import net.minecraft.world.level.ChunkPos;

public class EntitySync {
    
    public static void init() {
        NeoForge.EVENT_BUS.addListener(DimensionEvents.BeforeRemovingDimensionEvent.class,
                beforeRemovingDimensionEvent -> EntitySync.forceRemoveDimension(beforeRemovingDimensionEvent.dimension));
    }
    
    /**
     * regarding the players in all dimensions
     */
    public static void update(MinecraftServer server) {
        server.getProfiler().push("ip_entity_tracking_update");
        
        for (ServerLevel world : server.getAllLevels()) {
            PacketRedirection.withForceRedirect(
                world,
                () -> {
                    ChunkMap chunkMap = world.getChunkSource().chunkMap;
                    Int2ObjectMap<ChunkMap.TrackedEntity> entityTrackerMap =
                        ((IEChunkMap) chunkMap).ip_getEntityTrackerMap();
                    DistanceManager distanceManager = chunkMap.getDistanceManager();
                    
                    for (ChunkMap.TrackedEntity trackedEntity : entityTrackerMap.values()) {
                        IETrackedEntity ieTrackedEntity = (IETrackedEntity) trackedEntity;
                        ieTrackedEntity.ip_updateEntityTrackingStatus();
                    }
                }
            );
        }
        
        server.getProfiler().pop();
    }
    
    public static void tick(MinecraftServer server) {
        server.getProfiler().push("ip_entity_tracking_tick");
        
        for (ServerLevel world : server.getAllLevels()) {
            PacketRedirection.withForceRedirect(
                world,
                () -> {
                    ChunkMap chunkMap = world.getChunkSource().chunkMap;
                    Int2ObjectMap<ChunkMap.TrackedEntity> entityTrackerMap =
                        ((IEChunkMap) chunkMap).ip_getEntityTrackerMap();
                    DistanceManager distanceManager = chunkMap.getDistanceManager();
                    
                    for (ChunkMap.TrackedEntity trackedEntity : entityTrackerMap.values()) {
                        IETrackedEntity ieTrackedEntity = (IETrackedEntity) trackedEntity;
                        
                        long chunkPos = ieTrackedEntity.ip_getEntity().chunkPosition().toLong();
                        // Immersive Aeronautics: entities in Sable plot space (|chunk| > 100000)
                        // are never in entity-ticking range, but still need their changes sent
                        boolean shouldSend = distanceManager.inEntityTickingRange(chunkPos)
                            || ChunkPos.getX(chunkPos) > 100000
                            || ChunkPos.getX(chunkPos) < -100000
                            || ChunkPos.getZ(chunkPos) > 100000
                            || ChunkPos.getZ(chunkPos) < -100000;
                        if (shouldSend) {
                            ieTrackedEntity.ip_sendChanges();
                        }
                    }
                }
            );
            
            
        }
        
        server.getProfiler().pop();
    }
    
    private static void forceRemoveDimension(ServerLevel world) {
    
    }
    
}
