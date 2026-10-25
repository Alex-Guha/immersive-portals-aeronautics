/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.layer.BlockEntityRenderStateShard;
import net.irisshaders.iris.layer.BufferSourceWrapper;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Iris-touching half of {@link IPIrisCompat}; only loaded when Iris is present.
 * Wraps {@code IrisApi.getInstance().isShaderPackInUse()}.
 */
final class IPIrisCompatImpl {
    private IPIrisCompatImpl() {
    }

    static boolean isShaderPackInUse() {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    /** Iris's {@code MixinBlockEntityRenderDispatcher.iris$wrapBufferSource}, for {@link IPIrisCompat#beginBlockEntity}. */
    static MultiBufferSource beginBlockEntity(MultiBufferSource source, BlockState state) {
        Object2IntMap<BlockState> blockStateIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        if (blockStateIds == null || !ImmediateState.isRenderingLevel) {
            return source;
        }
        CapturedRenderingState.INSTANCE.setCurrentBlockEntity(blockStateIds.getOrDefault(state, -1));
        return new BufferSourceWrapper(
            source, renderType -> OuterWrappedRenderType.wrapExactlyOnce("iris:block_entity", renderType, BlockEntityRenderStateShard.INSTANCE)
        );
    }

    /** Iris's {@code iris$afterRender}. */
    static void endBlockEntity() {
        CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
    }
}
