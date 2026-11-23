/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A pass's render lists cut down to some of their regions (IPNearTranslucents). */
@Mixin(value = SortedRenderLists.class, remap = false)
public interface IESodiumSortedRenderLists {
    @Accessor("lists")
    ObjectArrayList<ChunkRenderList> ip_getLists();

    @Invoker("<init>")
    static SortedRenderLists ip_create(ObjectArrayList<ChunkRenderList> lists) {
        throw new AssertionError();
    }
}
