/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderRegion;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.Helper;

@Mixin(value = RenderRegion.class, remap = false)
public class MixinSodiumRenderRegion implements IESodiumRenderRegion {
    @Shadow
    @Final
    private ChunkRenderList renderList;
    
    @Unique
    private @Nullable ObjectArrayList<ChunkRenderList> chunkRenderListsForPortalRendering = null;
    
    /** Immersive Aeronautics: the portal layers' lists for Iris's shadow pass. See {@link #getRenderList()}. */
    @Unique
    private @Nullable ObjectArrayList<ChunkRenderList> ip_shadowListsForPortalRendering = null;
    
    /** The render list the cached draw batches were last filled from. See {@link MixinSodiumDefaultChunkRenderer}. */
    @Unique
    private @Nullable ChunkRenderList ip_lastRenderedList = null;
    
    @Override
    public @Nullable ChunkRenderList ip_getLastRenderedList() {
        return ip_lastRenderedList;
    }
    
    @Override
    public void ip_setLastRenderedList(@Nullable ChunkRenderList list) {
        ip_lastRenderedList = list;
    }
    
    /**
     * @author qouteall
     * @reason With ImmPtl, the world rendering process is as follows:
     * 1. render solid things
     * 2. render portal recursively (will increase frame counter)
     * 3. render transparent things
     * When rendering the world in portal (to-same-world portal),
     * the frame counter increases, then in
     * {@link SortedRenderLists.Builder#add(RenderSection)} it will reset the ChunkRenderList,
     * which makes upcoming transparent block rendering in outer world to break.
     * So use separate ChunkRenderList for each portal rendering layer.
     */
    @Overwrite
    public ChunkRenderList getRenderList() {
        if (!PortalRendering.isRendering()) {
            return renderList;
        }
        
        RenderRegion this_ = (RenderRegion) (Object) this;
        
        // Immersive Aeronautics: with a shader pack a portal pass runs Iris's shadow pass too. Iris swaps renderList
        // (and the cached batches) to a shadow list of its own for it, but the layer's list was handed out anyway, so
        // the shadow pass overwrote the sections and face sets the portal view then drew (whole chunk surfaces missing
        // in portal views). Each layer gets a shadow list of its own, apart from the player's view's shadow list,
        // which Iris may reuse in the next frame.
        boolean shadow = IrisInterface.invoker.isRenderingShadowMap();
        if (shadow && ip_shadowListsForPortalRendering == null) {
            ip_shadowListsForPortalRendering = new ObjectArrayList<>();
        }
        if (!shadow && chunkRenderListsForPortalRendering == null) {
            chunkRenderListsForPortalRendering = new ObjectArrayList<>();
        }
        
        int layer = PortalRendering.getPortalLayer();
        int index = layer - 1;
        ChunkRenderList result = Helper.arrayListComputeIfAbsent(
            shadow ? ip_shadowListsForPortalRendering : chunkRenderListsForPortalRendering,
            index,
            () -> new ChunkRenderList(this_)
        );
        
        return result;
    }
}
