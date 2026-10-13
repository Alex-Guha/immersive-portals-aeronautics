/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import org.jetbrains.annotations.Nullable;

/**
 * Duck interface on Sodium's {@code RenderRegion}: remembers which {@link ChunkRenderList}
 * the region's cached multi-draw batches were last filled from, so that a different list
 * (another portal layer rendering the same region from another camera) invalidates them.
 */
public interface IESodiumRenderRegion {
    @Nullable ChunkRenderList ip_getLastRenderedList();
    
    void ip_setLastRenderedList(@Nullable ChunkRenderList list);
}
