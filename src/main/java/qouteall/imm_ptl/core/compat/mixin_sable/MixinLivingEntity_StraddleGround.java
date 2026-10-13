/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;

/**
 * Walking on the far half of a straddling ship uses that block's friction ({@link IPStraddleCut#farHalfBlockUnder}).
 */
@Mixin(LivingEntity.class)
public class MixinLivingEntity_StraddleGround {
    @WrapOperation(
        method = "travel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 0),
        require = 0
    )
    private BlockState ip_farHalfFriction(final Level level, final BlockPos pos, final Operation<BlockState> original) {
        final BlockState state = original.call(level, pos);
        if (state.isAir()) {
            final BlockState farHalf = IPStraddleCut.farHalfBlockUnder((LivingEntity) (Object) this, 0.5);
            if (farHalf != null) {
                return farHalf;
            }
        }
        return state;
    }
}
