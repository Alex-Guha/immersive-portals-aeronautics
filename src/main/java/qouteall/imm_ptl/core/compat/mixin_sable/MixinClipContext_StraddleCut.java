/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The approach follows IPSable 0.5.0's IplProjectionAwareClipMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleInteraction;

/**
 * Raycasts do not hit the part of a straddling ship past its portal plane in the ship's own world
 * ({@link IPStraddleInteraction#raycastShape}). Every block a raycast visits gets its shape here, also inside Sable's
 * own raycast through a ship's plot (Sable's {@code BlockGetter.clip} overwrite).
 */
@Mixin(ClipContext.class)
public class MixinClipContext_StraddleCut {
    @ModifyReturnValue(method = "getBlockShape", at = @At("RETURN"), require = 0)
    private VoxelShape ip_cutBlockShape(final VoxelShape shape, final BlockState state, final BlockGetter getter, final BlockPos pos) {
        if (shape.isEmpty() || !(getter instanceof final Level level) || !IPSableCompatHelper.isPlotSpacePosition(pos.getX(), pos.getZ())) {
            return shape;
        }
        return IPStraddleInteraction.raycastShape(level, pos, shape, false);
    }

    @ModifyReturnValue(method = "getFluidShape", at = @At("RETURN"), require = 0)
    private VoxelShape ip_cutFluidShape(final VoxelShape shape, final FluidState state, final BlockGetter getter, final BlockPos pos) {
        if (shape.isEmpty() || !(getter instanceof final Level level) || !IPSableCompatHelper.isPlotSpacePosition(pos.getX(), pos.getZ())) {
            return shape;
        }
        return IPStraddleInteraction.raycastShape(level, pos, shape, true);
    }
}
