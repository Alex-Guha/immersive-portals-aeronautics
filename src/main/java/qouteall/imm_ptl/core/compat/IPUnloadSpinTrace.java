/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.mixin.common.chunk_sync.IEDistanceManager;
import qouteall.imm_ptl.core.mixin.common.debug.IPChunkGenerationTaskAccessor;
import qouteall.imm_ptl.core.mixin.common.debug.IPGenerationChunkHolderAccessor;

/**
 * Diagnostics (2.8.8.1-seat-sync, user 2026-11-02: the game hung on leaving the world). The server spent its stop in one
 * {@code ChunkMap.processUnloads}: {@code scheduleUnload} queues itself again while a chunk is not ready for saving
 * ({@code ChunkHolder.isReadyForSaving}: a generation task still holds it, or its save future is not done), and the loop
 * runs that queue until it is empty, so a task that needs the server thread to finish never does. Names the chunk once it
 * has been queued {@value #REPORT_AT} times, with its tickets and the generation tasks around it, and logs each generation
 * task started while the server stops, with its callers. {@code [IPUnloadSpin]}; on in branch test jars and with
 * {@code -Dip_aeronautics.debug.unloadSpin=true}.
 */
public final class IPUnloadSpinTrace {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ON = Boolean.getBoolean("ip_aeronautics.debug.unloadSpin") || IPStallRecorder.isTestJar();
    /** A chunk is queued once per unload, a few times if it waits for a save; a spin queues it millions of times. */
    private static final int REPORT_AT = 5000;
    private static final int MAX_REPORTS = 6;
    private static final int MAX_STOPPING_TASKS = 20;
    /** Generation tasks hold chunks up to their pyramid's radius from their centre. */
    private static final int TASK_RADIUS = 12;

    private static int reports;
    private static int stoppingTasks;

    private IPUnloadSpinTrace() {
    }

    public static void onScheduleUnload(final ChunkMap map, final ServerLevel level, final Long2IntOpenHashMap attempts,
                                        final long chunkPos, final ChunkHolder holder,
                                        final Long2ObjectLinkedOpenHashMap<ChunkHolder> updating,
                                        final Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads,
                                        final List<ChunkGenerationTask> pendingTasks) {
        final int count = attempts.addTo(chunkPos, 1) + 1;
        if ((count != REPORT_AT && count != REPORT_AT * 100) || reports >= MAX_REPORTS) {
            return;
        }
        reports++;
        final ChunkPos pos = new ChunkPos(chunkPos);
        final List<String> tasks = new ArrayList<>();
        for (int dx = -TASK_RADIUS; dx <= TASK_RADIUS; dx++) {
            for (int dz = -TASK_RADIUS; dz <= TASK_RADIUS; dz++) {
                final long key = ChunkPos.asLong(pos.x + dx, pos.z + dz);
                ChunkHolder near = updating.get(key);
                if (near == null) {
                    near = pendingUnloads.get(key);
                }
                final ChunkGenerationTask task = near == null ? null : ((IPGenerationChunkHolderAccessor) near).ip_getTask().get();
                if (task != null) {
                    tasks.add(describe(task) + " (centre holds " + near.getGenerationRefCount() + " refs)");
                }
            }
        }
        LOGGER.warn("[IPUnloadSpin] chunk {} (blocks {} {}) in {} queued for unloading {} times: not ready for saving"
                + " (generation refs {}, save future done {}, ticket level {}, latest status {}, full status {}, in the chunk map {},"
                + " pending unload {}, server running {}); tickets: {}; generation tasks centred within {} chunks: {};"
                + " generation tasks not started yet: {}",
            pos, pos.getMinBlockX(), pos.getMinBlockZ(), level.dimension().location(), count, holder.getGenerationRefCount(),
            holder.getSaveSyncFuture().isDone(), holder.getTicketLevel(), holder.getLatestStatus(), holder.getFullStatus(),
            updating.containsKey(chunkPos), pendingUnloads.containsKey(chunkPos), level.getServer().isRunning(),
            ((IEDistanceManager) map.getDistanceManager()).ip_getTickets().get(chunkPos), TASK_RADIUS,
            tasks.isEmpty() ? "none" : tasks,
            pendingTasks.stream().limit(10).map(IPUnloadSpinTrace::describe).toList() + " of " + pendingTasks.size());
    }

    public static void onScheduleGenerationTask(final ServerLevel level, final ChunkStatus targetStatus, final ChunkPos pos) {
        if (level.getServer().isRunning() || stoppingTasks >= MAX_STOPPING_TASKS) {
            return;
        }
        stoppingTasks++;
        LOGGER.warn("[IPUnloadSpin] generation task to {} for chunk {} (blocks {} {}) in {} while the server stops: {}",
            targetStatus, pos, pos.getMinBlockX(), pos.getMinBlockZ(), level.dimension().location(),
            Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(3)
                .map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName())
                .filter(frame -> !frame.contains("ip_trace") && !frame.contains("$$"))
                .limit(14)
                .collect(Collectors.joining(" < ")));
    }

    private static String describe(final ChunkGenerationTask task) {
        final IPChunkGenerationTaskAccessor access = (IPChunkGenerationTaskAccessor) task;
        return access.ip_getPos() + " to " + task.targetStatus + " (at " + access.ip_getScheduledStatus()
            + (access.ip_isMarkedForCancellation() ? ", cancelled" : "") + ")";
    }
}
