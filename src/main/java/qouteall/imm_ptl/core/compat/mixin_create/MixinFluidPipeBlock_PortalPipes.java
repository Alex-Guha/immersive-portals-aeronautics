/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalPipes;

/**
 * Portal pipes ({@link IPCreatePortalPipes}): a copper pipe opens towards a portal block next to it, as towards a
 * tank, so corners and junctions placed against a portal connect into it. By block only, so client and server agree.
 */
@Mixin(value = FluidPipeBlock.class, remap = false)
public abstract class MixinFluidPipeBlock_PortalPipes {
    @Inject(method = "canConnectTo", at = @At("HEAD"), cancellable = true)
    private static void ip$connectToPortal(
        BlockAndTintGetter world, BlockPos neighbourPos, BlockState neighbour, Direction direction, CallbackInfoReturnable<Boolean> cir
    ) {
        if (IPCreatePortalPipes.isPlaceholderFacing(neighbour, direction)) {
            cir.setReturnValue(true);
        }
    }
}
