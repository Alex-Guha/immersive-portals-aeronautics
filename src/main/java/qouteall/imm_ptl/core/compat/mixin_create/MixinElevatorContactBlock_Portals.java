/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;

/**
 * A floor called in a column linked through a portal ({@link IPElevatorPortals#onCalled}): the other column's
 * contacts stop showing a call too. Create clears the other contacts of the caller's own column only.
 */
@Mixin(value = ElevatorContactBlock.class, remap = false)
public class MixinElevatorContactBlock_Portals {
    @Inject(method = "callToContactAndUpdate", at = @At("TAIL"))
    private void ip$clearOtherColumn(ElevatorColumn column, BlockState state, Level level, BlockPos pos, boolean powered, CallbackInfo ci) {
        IPElevatorPortals.onCalled(column);
    }
}
