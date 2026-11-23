/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.peripheral.dim_stack.IPStackFallColumns;

/**
 * Sodium's side of {@code MixinLiquidBlockRenderer_StackFloor}: a fluid in the bottom layer of a dimension with a stack
 * floor counts as covered below, so Sodium draws no bottom face for it.
 */
@Mixin(value = DefaultFluidRenderer.class, remap = false)
public class MixinSodiumDefaultFluidRenderer_StackFloor {
    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/DefaultFluidRenderer;isFullBlockFluidOccluded(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Z"
        )
    )
    private boolean ip_hideBottomAtStackFloor(
        DefaultFluidRenderer self, BlockAndTintGetter getter, BlockPos pos, Direction side, BlockState state,
        FluidState fluid, Operation<Boolean> original
    ) {
        if (side == Direction.DOWN && getter instanceof IESodiumLevelSlice slice
            && IPStackFallColumns.hidesBottomFace(slice.ip_getLevel(), pos.getY())
        ) {
            return true;
        }
        return original.call(self, getter, pos, side, state, fluid);
    }
}
