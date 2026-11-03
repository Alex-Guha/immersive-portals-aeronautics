/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.common.debug;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.List;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.IPUnloadSpinTrace;

/**
 * Diagnostics ({@link IPUnloadSpinTrace}): a chunk queued for unloading again and again because it is never ready for
 * saving, and the generation tasks started while the server stops.
 */
@Mixin(ChunkMap.class)
public class MixinChunkMap_UnloadSpinTrace {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Shadow
    @Final
    private List<ChunkGenerationTask> pendingGenerationTasks;

    @Unique
    private final Long2IntOpenHashMap ip_unloadAttempts = new Long2IntOpenHashMap();

    @Inject(method = "scheduleUnload", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_traceUnloadSpin(final long chunkPos, final ChunkHolder holder, final CallbackInfo ci) {
        if (IPUnloadSpinTrace.ON) {
            IPUnloadSpinTrace.onScheduleUnload((ChunkMap) (Object) this, level, ip_unloadAttempts, chunkPos, holder,
                updatingChunkMap, pendingUnloads, pendingGenerationTasks);
        }
    }

    @Inject(method = "scheduleGenerationTask", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_traceGenerationWhileStopping(final ChunkStatus targetStatus, final ChunkPos pos,
                                                 final CallbackInfoReturnable<ChunkGenerationTask> cir) {
        if (IPUnloadSpinTrace.ON) {
            IPUnloadSpinTrace.onScheduleGenerationTask(level, targetStatus, pos);
        }
    }
}
