/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The Iris pipeline data a Voxy render system draws through ({@code IPVoxyIrisPipelines}). */
@Mixin(value = IrisVoxyRenderPipeline.class, remap = false)
public interface IPVoxyIrisRenderPipelineAccessor {
    @Accessor("data")
    IrisVoxyRenderPipelineData ip_getData();
}
