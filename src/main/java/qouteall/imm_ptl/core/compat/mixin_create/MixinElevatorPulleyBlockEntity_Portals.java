/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPulley;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorShaft;

/**
 * An elevator pulley whose shaft runs through a portal ({@link IPElevatorPortals}): its shaft, synced to clients with
 * the pulley's data; after each tick the far copy is placed where the car moved; a car arriving at a far floor lights
 * the far contact; a stop is refused while the car is partly past the plane.
 */
@Mixin(value = ElevatorPulleyBlockEntity.class, remap = false)
public class MixinElevatorPulleyBlockEntity_Portals implements IPElevatorPulley {
    @Unique
    private @Nullable IPElevatorShaft ip$shaft;
    @Unique
    private long ip$lastScan = Long.MIN_VALUE / 2;

    @Override
    public @Nullable IPElevatorShaft ip$shaft() {
        return ip$shaft;
    }

    @Override
    public void ip$setShaft(@Nullable IPElevatorShaft shaft) {
        ip$shaft = shaft;
    }

    @Override
    public long ip$lastScan() {
        return ip$lastScan;
    }

    @Override
    public void ip$setLastScan(long tick) {
        ip$lastScan = tick;
    }

    /** At every return: the tick returns early while the car moves (not arrived). */
    @Inject(method = "tick", at = @At("RETURN"))
    private void ip$placeFarCopy(CallbackInfo ci) {
        IPElevatorPortals.tick((ElevatorPulleyBlockEntity) (Object) this);
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void ip$writeShaft(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket && ip$shaft != null) {
            compound.put("IPElevatorShaft", ip$shaft.write());
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void ip$readShaft(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) {
            ip$shaft = compound.contains("IPElevatorShaft") ? IPElevatorShaft.read(compound.getCompound("IPElevatorShaft")) : null;
        }
    }

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void ip$refuseStopPastPlane(CallbackInfo ci) {
        if (IPElevatorPortals.refusesStop((ElevatorPulleyBlockEntity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "triggerContact", at = @At("HEAD"), cancellable = true)
    private void ip$triggerFarContact(ElevatorContraption ec, int targetLevel, CallbackInfo ci) {
        if (IPElevatorPortals.triggerFarContact((ElevatorPulleyBlockEntity) (Object) this, ec, targetLevel)) {
            ci.cancel();
        }
    }
}
