/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.simulated_team.simulated.content.blocks.lasers.LaserBehaviour;
import dev.simulated_team.simulated.content.blocks.lasers.optical_sensor.OpticalSensorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPLaserPortals;

/**
 * An optical sensor looking through a portal tests the block it sees on the other side against its filter
 * ({@link IPLaserPortals}): the hit block and fluid are read in the last leg's level.
 */
@Mixin(value = OpticalSensorBlockEntity.class, remap = false)
public abstract class MixinOpticalSensor_Portals {
    @Shadow
    public LaserBehaviour laser;

    @WrapOperation(
        method = "checkFilter",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 0
    )
    private BlockState ip_blockThrough(final Level level, final BlockPos pos, final Operation<BlockState> original) {
        return original.call(this.ip_hitLevel(level), pos);
    }

    @WrapOperation(
        method = "checkFilter",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
        require = 0
    )
    private FluidState ip_fluidThrough(final Level level, final BlockPos pos, final Operation<FluidState> original) {
        return original.call(this.ip_hitLevel(level), pos);
    }

    @Unique
    private Level ip_hitLevel(final Level own) {
        final IPLaserPortals.Trace trace = this.laser == null ? null : ((IPLaserPortals.Holder) this.laser).ip$portalTrace();
        return trace != null ? trace.level() : own;
    }
}
