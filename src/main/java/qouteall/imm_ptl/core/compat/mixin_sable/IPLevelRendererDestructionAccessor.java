/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SortedSet;

/** The block-breaking progress a level renderer draws cracks for (each IP client world has its own renderer). */
@Mixin(LevelRenderer.class)
public interface IPLevelRendererDestructionAccessor {
    @Accessor("destructionProgress")
    Long2ObjectMap<SortedSet<BlockDestructionProgress>> ip_getDestructionProgress();
}
