/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderRegion;

/**
 * Sodium caches the multi-draw batch of each {@link RenderRegion} per render pass and only
 * refills it when the region's {@link ChunkRenderList} notices that its own contents or
 * camera-relative position changed. The batch is built from one specific render list and one
 * camera (block-face culling is evaluated against the camera position).
 * <p>
 * With Immersive Portals the same region can be drawn several times per frame from different
 * cameras (e.g. the player's world at layer 0 and again behind a portal-in-portal at layer 2,
 * or any same-dimension portal / mirror). {@link MixinSodiumRenderRegion} already gives every
 * portal layer its own render list, but the batch cache stayed shared, so the passes drew each
 * other's face set and terrain flickered around the portal. Clear the cached batches whenever a
 * region is rendered from a different list than the one that filled them.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class MixinSodiumDefaultChunkRenderer {
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList;getRegion()Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
        )
    )
    private RenderRegion ip_invalidateBatchesOnListChange(ChunkRenderList renderList) {
        RenderRegion region = renderList.getRegion();
        IESodiumRenderRegion ieRegion = (IESodiumRenderRegion) region;
        if (ieRegion.ip_getLastRenderedList() != renderList) {
            region.clearAllCachedBatches();
            ieRegion.ip_setLastRenderedList(renderList);
        }
        return region;
    }
}
