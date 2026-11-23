/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.mixin.client.accessor.IERenderChunkRegion;
import qouteall.imm_ptl.peripheral.dim_stack.IPStackFallColumns;

/**
 * A fluid in the bottom layer of a dimension with a stack floor draws no bottom face: the fall goes on below the plane
 * ({@link IPStackFallColumns}), and the face showed there as a pane across it. Vanilla's chunk builder; Sodium's in
 * {@code MixinSodiumDefaultFluidRenderer_StackFloor}.
 */
@Mixin(LiquidBlockRenderer.class)
public class MixinLiquidBlockRenderer_StackFloor {
    @WrapOperation(
        method = "tesselate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/LiquidBlockRenderer;isFaceOccludedByNeighbor(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;FLnet/minecraft/world/level/block/state/BlockState;)Z"
        )
    )
    private boolean ip_hideBottomAtStackFloor(
        BlockGetter getter, BlockPos pos, Direction side, float height, BlockState neighbor, Operation<Boolean> original
    ) {
        if (side == Direction.DOWN) {
            Level level = getter instanceof Level direct ? direct
                : getter instanceof IERenderChunkRegion region ? region.ip_getLevel() : null;
            if (level != null && IPStackFallColumns.hidesBottomFace(level, pos.getY())) {
                return true;
            }
        }
        return original.call(getter, pos, side, height, neighbor);
    }
}
