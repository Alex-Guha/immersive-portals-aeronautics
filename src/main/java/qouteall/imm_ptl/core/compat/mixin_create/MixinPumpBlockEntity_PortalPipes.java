/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import java.util.Map;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalPipes;

/**
 * Portal pipes ({@link IPCreatePortalPipes}): a pump's search ends at a portal end like at a tank, and the pressure it
 * gives a portal end carries on from the far end.
 */
@Mixin(value = PumpBlockEntity.class, remap = false)
public abstract class MixinPumpBlockEntity_PortalPipes {
    @Inject(method = "hasReachedValidEndpoint", at = @At("HEAD"), cancellable = true)
    private void ip$portalEndpoint(LevelAccessor world, BlockFace blockFace, boolean pull, CallbackInfoReturnable<Boolean> cir) {
        if (FluidPropagator.getPipe(world, blockFace.getConnectedPos()) == null
            && IPCreatePortalPipes.isPortalFace(world, blockFace.getPos(), blockFace.getFace())) {
            cir.setReturnValue(true);
        }
    }

    @WrapOperation(
        method = "distributePressureTo",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/fluids/FluidTransportBehaviour;addPressure(Lnet/minecraft/core/Direction;ZF)V"
        )
    )
    private void ip$mirrorThroughPortals(
        FluidTransportBehaviour pipe, Direction side, boolean inbound, float pressure, Operation<Void> original,
        @Local(ordinal = 0) Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph
    ) {
        original.call(pipe, side, inbound, pressure);
        Pair<Integer, Map<Direction, Boolean>> node = pipeGraph.get(pipe.getPos());
        if (node != null) {
            IPCreatePortalPipes.afterPumpPressure(pipe, side, inbound, pressure, node.getFirst());
        }
    }
}
