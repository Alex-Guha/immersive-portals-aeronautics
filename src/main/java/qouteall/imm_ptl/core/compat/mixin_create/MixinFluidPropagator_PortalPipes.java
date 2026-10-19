/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.fluids.FluidPropagator;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalPipes;

/**
 * Portal pipes ({@link IPCreatePortalPipes}): a pipe face opening onto a portal is never an open end (it would spill
 * into the portal block, or in front of a frameless portal), and a pipe change also updates the pipes past any portal
 * end the update reached.
 */
@Mixin(value = FluidPropagator.class, remap = false)
public abstract class MixinFluidPropagator_PortalPipes {
    @Inject(method = "isOpenEnd", at = @At("HEAD"), cancellable = true)
    private static void ip$portalIsNoOpenEnd(BlockGetter reader, BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> cir) {
        if (FluidPropagator.getPipe(reader, pos.relative(side)) == null && IPCreatePortalPipes.isPortalFace(reader, pos, side)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "propagateChangedPipe", at = @At("TAIL"))
    private static void ip$propagateAcrossPortals(
        LevelAccessor world, BlockPos pipePos, BlockState pipeState, CallbackInfo ci, @Local(ordinal = 0) Set<BlockPos> visited
    ) {
        IPCreatePortalPipes.propagateAcross(world, pipePos, pipeState, visited);
    }
}
