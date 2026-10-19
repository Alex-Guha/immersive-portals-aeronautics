/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.simulated_team.simulated.content.blocks.lasers.LaserBehaviour;
import dev.simulated_team.simulated.content.blocks.lasers.laser_sensor.LaserSensorInteractorBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPLaserPortals;

/**
 * A laser pointer's beam through a portal lights the laser sensor it hits on the other side
 * ({@link IPLaserPortals}): the hit block is looked up in the last leg's level, and the distance (a path length on the
 * unfolded beam) is measured in the pointer's own level.
 */
@Mixin(value = LaserSensorInteractorBehaviour.class, remap = false)
public abstract class MixinLaserSensorInteractor_Portals {
    @WrapOperation(
        method = "checkAndUpdateSensor",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"),
        require = 0
    )
    private BlockEntity ip_sensorThrough(final Level level, final BlockPos pos, final Operation<BlockEntity> original) {
        final IPLaserPortals.Trace trace = ((IPLaserPortals.Holder) this).ip$portalTrace();
        return original.call(trace != null ? trace.level() : level, pos);
    }

    @WrapOperation(
        method = "updateHitSensor",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;distanceSquaredWithSubLevels(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;Lnet/minecraft/core/Position;)D"),
        require = 0
    )
    private double ip_distanceInOwnLevel(final ActiveSableCompanion helper, final Level level, final Position a, final Position b,
                                         final Operation<Double> original) {
        final IPLaserPortals.Trace trace = ((IPLaserPortals.Holder) this).ip$portalTrace();
        return original.call(helper, trace != null ? ((LaserBehaviour) (Object) this).getWorld() : level, a, b);
    }
}
