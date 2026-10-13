/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;

/**
 * Landing on the far half of a straddling ship gives that block's landing effects (fall damage, bouncing, step-on):
 * in {@code Entity.move} the block under the feet is read from the entity's level, where there is only air under the
 * far half ({@link IPStraddleCut#farHalfBlockUnder}). Same for the step-sound read.
 */
@Mixin(Entity.class)
public class MixinEntity_StraddleGround {
    @WrapOperation(
        method = "move",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 0),
        require = 0
    )
    private BlockState ip_farHalfGround(final Level level, final BlockPos pos, final Operation<BlockState> original) {
        final BlockState state = original.call(level, pos);
        if (state.isAir()) {
            final BlockState farHalf = IPStraddleCut.farHalfBlockUnder((Entity) (Object) this, 0.2);
            if (farHalf != null) {
                return farHalf;
            }
        }
        return state;
    }

    /**
     * The second read of the block under the feet in {@code Entity.move}: step sounds, climbing.
     */
    @WrapOperation(
        method = "move",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 1),
        require = 0
    )
    private BlockState ip_farHalfStepSound(final Level level, final BlockPos pos, final Operation<BlockState> original) {
        final BlockState state = original.call(level, pos);
        if (state.isAir()) {
            final BlockState farHalf = IPStraddleCut.farHalfBlockUnder((Entity) (Object) this, 0.2);
            if (farHalf != null) {
                return farHalf;
            }
        }
        return state;
    }
}
