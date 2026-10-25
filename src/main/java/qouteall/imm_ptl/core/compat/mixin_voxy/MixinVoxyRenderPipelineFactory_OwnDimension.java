/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.RenderPipelineFactory;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyIrisPipelines;

/**
 * A new Voxy render system takes the Iris pipeline of its own dimension, not the one Iris last made current, and none
 * that another render system is bound to (Voxy would throw and switch shaders off). See {@link IPVoxyIrisPipelines}.
 */
@Mixin(value = RenderPipelineFactory.class, remap = false)
public class MixinVoxyRenderPipelineFactory_OwnDimension {
    @WrapOperation(
        method = "createIrisPipeline",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/PipelineManager;getPipelineNullable()Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;"
        ),
        require = 0
    )
    private static WorldRenderingPipeline ip_ownDimensionPipeline(
        PipelineManager manager, Operation<WorldRenderingPipeline> original
    ) {
        return IPVoxyIrisPipelines.pipelineForNewRenderSystem();
    }
}
