/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
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

import java.util.ArrayList;

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
                    
                    // Immersive Aeronautics: over a snapshot, see snapshot()
                    for (ChunkMap.TrackedEntity trackedEntity : snapshot(entityTrackerMap)) {
                        IETrackedEntity ieTrackedEntity = (IETrackedEntity) trackedEntity;
                        if (!isStillTracked(entityTrackerMap, ieTrackedEntity)) {
                            continue;
                        }
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
                    
                    // Immersive Aeronautics: over a snapshot, see snapshot()
                    for (ChunkMap.TrackedEntity trackedEntity : snapshot(entityTrackerMap)) {
                        IETrackedEntity ieTrackedEntity = (IETrackedEntity) trackedEntity;
                        if (!isStillTracked(entityTrackerMap, ieTrackedEntity)) {
                            continue;
                        }
                        
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
    
    /**
     * Immersive Aeronautics: the passes go over a copy of the level's tracked entities. Iterating the live map crashed
     * the server ("this.wrapped" is null in Int2ObjectOpenHashMap's iterator) when
     * pairing an entity added or removed another: fastutil's iterators do not detect a change and run off the table.
     * Pairing and sending run other mods' code (spawn packets, startSeenByPlayer, NeoForge's StartTracking event): a
     * Create contraption with a deployer creates its fake player while writing its spawn packet, whose spawn search
     * loads a chunk synchronously and so runs pending chunk demotions, which stop tracking the entities there. An entity
     * added during a pass is handled in the next one; one removed during a pass is skipped ({@link #isStillTracked}),
     * so it is not paired again after its removal (a ghost on the client).
     */
    private static ArrayList<ChunkMap.TrackedEntity> snapshot(Int2ObjectMap<ChunkMap.TrackedEntity> map) {
        return new ArrayList<>(map.values());
    }
    
    private static boolean isStillTracked(Int2ObjectMap<ChunkMap.TrackedEntity> map, IETrackedEntity tracked) {
        return map.get(tracked.ip_getEntity().getId()) == tracked;
    }
    
    private static void forceRemoveDimension(ServerLevel world) {
    
    }
    
}
