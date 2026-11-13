/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's four-entry cache of the last chunks looked up, for {@link MixinSableLevelPlot_ChunkCache}. */
@Mixin(ServerChunkCache.class)
public interface IPServerChunkCacheInvoker {
    @Invoker("clearCache")
    void ip_clearCache();
}
