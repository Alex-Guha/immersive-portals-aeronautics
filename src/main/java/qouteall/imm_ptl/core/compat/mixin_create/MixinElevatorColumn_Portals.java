/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import java.util.List;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.IntAttached;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;

/**
 * The column of an elevator pulley and the column past its shaft's portal act as one ({@link IPElevatorPortals#linkOf}):
 * each lists the other's floors in its own frame, shows the floor reached and the active state on both, tells the
 * other when names change, numbers their floors as one column, and keeps their targets the same.
 */
@Mixin(value = ElevatorColumn.class, remap = false)
public class MixinElevatorColumn_Portals {
    @Inject(method = "compileNamesList", at = @At("RETURN"), cancellable = true)
    private void ip$mergeFloors(CallbackInfoReturnable<List<IntAttached<Couple<String>>>> cir) {
        cir.setReturnValue(IPElevatorPortals.mergedNames((ElevatorColumn) (Object) this, cir.getReturnValue()));
    }

    @Inject(method = "floorReached", at = @At("TAIL"))
    private void ip$floorReachedBoth(LevelAccessor level, String name, CallbackInfo ci) {
        IPElevatorPortals.mirror((ElevatorColumn) (Object) this, (other, link) -> other.floorReached(link.other(), name));
    }

    @Inject(method = "markDirty", at = @At("TAIL"))
    private void ip$markDirtyBoth(CallbackInfo ci) {
        IPElevatorPortals.mirror((ElevatorColumn) (Object) this, (other, link) -> other.markDirty());
    }

    @Inject(method = "namesChanged", at = @At("TAIL"))
    private void ip$namesChangedBoth(CallbackInfo ci) {
        IPElevatorPortals.mirror((ElevatorColumn) (Object) this, (other, link) -> other.namesListVersion++);
    }

    @Inject(method = "setActive", at = @At("TAIL"))
    private void ip$activeBoth(boolean isActive, CallbackInfo ci) {
        IPElevatorPortals.mirror((ElevatorColumn) (Object) this, (other, link) -> other.setActive(isActive));
    }

    @Inject(method = "initNames", at = @At("HEAD"), cancellable = true)
    private void ip$numberBothColumns(Level level, CallbackInfo ci) {
        if (IPElevatorPortals.initNamesMerged((ElevatorColumn) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "target", at = @At("TAIL"))
    private void ip$farCallTargetsHome(int yLevel, CallbackInfo ci) {
        IPElevatorPortals.onTarget((ElevatorColumn) (Object) this, yLevel);
    }
}
