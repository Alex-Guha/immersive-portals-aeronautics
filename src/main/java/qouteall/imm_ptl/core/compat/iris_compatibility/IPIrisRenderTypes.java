/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.layer.BlockEntityRenderStateShard;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/** Iris's render type wrappers, for code that loads without Iris (only call with a shader pack in use). */
public final class IPIrisRenderTypes {
    private IPIrisRenderTypes() {
    }

    /**
     * {@code source} with every render type wrapped as Iris wraps block entities' draws (its
     * {@code MixinBlockEntityRenderDispatcher}): equal wrappers share one segment of Iris's fully buffered source, whose
     * translucent quads are sorted together, while a plain and a wrapped type are drawn as separate groups.
     */
    public static MultiBufferSource asBlockEntity(final MultiBufferSource source) {
        return type -> source.getBuffer(OuterWrappedRenderType.wrapExactlyOnce("iris:block_entity", type, BlockEntityRenderStateShard.INSTANCE));
    }
}
