/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clears vanilla's cache of the last four chunks looked up ({@code ServerChunkCache.getChunk}) whenever a plot gains or
 * loses a chunk, as vanilla does whenever its own chunk map changes. Sable puts plot chunks into the chunk map and
 * takes them out without it, so a plot chunk looked up before a ship is loaded there (Sable answers with its empty
 * chunk) or before a ship leaves (the old copy's chunk) stays the answer for that position until the level's chunk
 * tick ends. A ship loaded into such a plot then has its block entities looked up in the wrong chunk while Sable
 * registers them ({@code LevelPlot.onBlockChange}): its springs, propellers and other physics actors never act, though
 * the blocks are there and drawn. Same-dimension hand-offs load ships in the level they left, often into the plot a
 * ship of the same group left a moment before.
 */
@Mixin(
    value = ServerLevelPlot.class,
    remap = false
)
public abstract class MixinSableLevelPlot_ChunkCache {
    @Inject(method = "addChunkHolder", at = @At("RETURN"))
    private void ip_clearChunkCacheOnAdd(ChunkPos localChunkPos, PlotChunkHolder holder, boolean initializeLighting, CallbackInfo ci) {
        ip_clearChunkCache();
    }

    @Inject(method = "onRemoveChunkHolder", at = @At("RETURN"))
    private void ip_clearChunkCacheOnRemove(LevelChunk levelChunk, CallbackInfo ci) {
        ip_clearChunkCache();
    }

    @Unique
    private void ip_clearChunkCache() {
        ServerLevel level = ((ServerLevelPlot) (Object) this).getSubLevel().getLevel();
        ((IPServerChunkCacheInvoker) level.getChunkSource()).ip_clearCache();
    }
}
