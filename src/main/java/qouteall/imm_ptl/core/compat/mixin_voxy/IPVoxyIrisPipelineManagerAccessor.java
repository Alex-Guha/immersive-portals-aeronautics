/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Iris's pipelines by dimension, for Voxy's render systems ({@code IPVoxyIrisPipelines}). */
@Mixin(value = PipelineManager.class, remap = false)
public interface IPVoxyIrisPipelineManagerAccessor {
    @Accessor("pipelinesPerDimension")
    Map<NamespacedId, WorldRenderingPipeline> ip_getPipelinesPerDimension();
}
