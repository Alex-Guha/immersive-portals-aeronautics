/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisPortalViewPipelines;

import java.util.Map;

/**
 * Portal views get their own Iris pipeline where they would share one (IPIrisPortalViewPipelines): while Iris prepares
 * one, its map of pipelines by dimension is swapped for ours, so Iris finds or makes the pipeline there the way it
 * does for any dimension (Euphoria's pack included, {@code MixinIrisPipelineManager_EuphoriaPack}).
 */
@Mixin(value = PipelineManager.class, remap = false)
public class MixinIrisPipelineManager_PortalViews {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    @Mutable
    private Map<NamespacedId, WorldRenderingPipeline> pipelinesPerDimension;

    @Shadow
    private void resetTextureState() {
        throw new AssertionError();
    }

    @WrapMethod(method = "preparePipeline")
    private WorldRenderingPipeline ip_portalViewPipeline(
        NamespacedId dimension, Operation<WorldRenderingPipeline> original
    ) {
        Map<NamespacedId, WorldRenderingPipeline> views = IPIrisPortalViewPipelines.pipelinesOfPass(dimension);
        if (views == null) {
            return original.call(dimension);
        }
        Map<NamespacedId, WorldRenderingPipeline> own = pipelinesPerDimension;
        boolean existed = views.containsKey(dimension);
        pipelinesPerDimension = views;
        WorldRenderingPipeline pipeline;
        try {
            pipeline = original.call(dimension);
        }
        finally {
            pipelinesPerDimension = own;
        }
        if (!existed && views.get(dimension) == pipeline) {
            IPIrisPortalViewPipelines.onCreated(dimension, pipeline);
        }
        return pipeline;
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void ip_destroyPortalViewPipelines(CallbackInfo ci) {
        IPIrisPortalViewPipelines.PIPELINES.forEach(views -> views.forEach((dimension, pipeline) -> {
            ip_LOGGER.info("[IPIris] Destroying a portal-view pipeline of {}", dimension);
            resetTextureState();
            pipeline.destroy();
        }));
        IPIrisPortalViewPipelines.onDestroyed();
    }
}
