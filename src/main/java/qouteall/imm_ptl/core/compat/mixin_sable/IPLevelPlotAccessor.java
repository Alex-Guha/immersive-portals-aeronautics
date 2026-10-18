/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * A plot's chunk holders, which {@code IPSableClientPlotChunks} keeps in step with IP's client chunk map (Sable has
 * no way to replace or remove a single holder).
 */
@Mixin(
    value = LevelPlot.class,
    remap = false
)
public interface IPLevelPlotAccessor {
    @Accessor("chunks")
    PlotChunkHolder[] ip_getChunkHolders();

    @Accessor("loadedChunks")
    List<PlotChunkHolder> ip_getLoadedChunkList();

    @Accessor("blockEntityActors")
    Object2ObjectOpenHashMap<BlockPos, BlockEntitySubLevelActor> ip_getBlockEntityActors();
}
