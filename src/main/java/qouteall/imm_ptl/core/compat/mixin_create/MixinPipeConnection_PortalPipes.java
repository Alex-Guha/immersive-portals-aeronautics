/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.PipeConnection;
import java.util.Optional;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalPipes;

/** Portal pipes ({@link IPCreatePortalPipes}): a pipe face opening onto a portal gets a portal source. */
@Mixin(value = PipeConnection.class, remap = false)
public abstract class MixinPipeConnection_PortalPipes {
    @Shadow
    public Direction side;
    @Shadow
    Optional<FlowSource> source;
    @Shadow
    Optional<FlowSource> previousSource;

    @Inject(method = "determineSource", at = @At("HEAD"), cancellable = true)
    private void ip$portalSource(Level world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FluidPropagator.getPipe(world, pos.relative(side)) != null || !IPCreatePortalPipes.isPortalFace(world, pos, side)) {
            return;
        }
        source = previousSource.orElse(null) instanceof IPCreatePortalPipes.PortalSource
            ? previousSource
            : Optional.of(new IPCreatePortalPipes.PortalSource(new BlockFace(pos, side)));
        cir.setReturnValue(true);
    }
}
