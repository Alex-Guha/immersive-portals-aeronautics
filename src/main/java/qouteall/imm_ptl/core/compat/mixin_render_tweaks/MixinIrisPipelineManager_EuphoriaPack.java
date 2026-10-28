/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.pipeline.PipelineManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.iris_integration.IPEuphoriaPacks;

import java.util.function.Function;

/** Each new Iris pipeline is built from Euphoria Patcher's pack for its dimension ({@link IPEuphoriaPacks}). */
@Mixin(value = PipelineManager.class, remap = false)
public class MixinIrisPipelineManager_EuphoriaPack {
    @WrapOperation(
        method = "preparePipeline",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"
        ),
        require = 0
    )
    private Object ip_createWithDimensionPack(
        Function<Object, Object> factory, Object dimension, Operation<Object> original
    ) {
        return IPEuphoriaPacks.createWithDimensionPack(() -> original.call(factory, dimension));
    }
}
