/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisViewPack;

/**
 * While the small view pack's pipeline is built ({@link IPIrisViewPack}), Iris's global world settings keep the player's
 * pack's values: the entity and item ids, ambient occlusion, shading and light voxelization the chunk meshes and entity
 * draws were made for (a changed value rebuilds every chunk). The binding points of shader storage buffers are left
 * alone too: Iris binds a pack's buffers once, when its pipeline is made, and unbinds them all for a pack without any.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline_ViewPack {
    @WrapWithCondition(
        method = "<init>",
        at = {
            @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings;setEntityIds(Lit/unimi/dsi/fastutil/objects/Object2IntFunction;)V"),
            @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings;setItemIds(Lit/unimi/dsi/fastutil/objects/Object2IntFunction;)V")
        }
    )
    private boolean ip_keepIds(WorldRenderingSettings settings, Object2IntFunction<?> ids) {
        return !IPIrisViewPack.isBuilding();
    }

    @WrapWithCondition(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings;setAmbientOcclusionLevel(F)V")
    )
    private boolean ip_keepAo(WorldRenderingSettings settings, float level) {
        return !IPIrisViewPack.isBuilding();
    }

    @WrapWithCondition(
        method = "<init>",
        at = {
            @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings;setDisableDirectionalShading(Z)V"),
            @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings;setUseSeparateAo(Z)V"),
            @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings;setVoxelizeLightBlocks(Z)V"),
            @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings;setSeparateEntityDraws(Z)V")
        }
    )
    private boolean ip_keepFlags(WorldRenderingSettings settings, boolean value) {
        return !IPIrisViewPack.isBuilding();
    }

    @WrapWithCondition(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;bindBufferBase(ILjava/lang/Integer;I)V")
    )
    private boolean ip_keepStorageBuffers(int target, Integer index, int buffer) {
        return !IPIrisViewPack.isBuilding();
    }
}
