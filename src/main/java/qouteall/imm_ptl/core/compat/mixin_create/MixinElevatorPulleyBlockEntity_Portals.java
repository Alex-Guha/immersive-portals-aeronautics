/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import java.util.List;
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
    private List<IPElevatorShaft> ip$chain = List.of();
    @Unique
    private long ip$lastScan = Long.MIN_VALUE / 2;

    @Override
    public List<IPElevatorShaft> ip$chain() {
        return ip$chain;
    }

    @Override
    public void ip$setChain(List<IPElevatorShaft> chain) {
        ip$chain = List.copyOf(chain);
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

    /**
     * In the pulley's data for clients, and in the save: Create keeps the offset within the rope's range, which ends at
     * the world's floor unless the shaft is known ({@code MixinPulleyBlockEntity_ElevatorRange}). Not saved, a car parked
     * past the world's floor through a portal was pulled up to it at the first tick after loading (the shaft is found at
     * the end of that tick), and went back down while clients got it.
     */
    @Inject(method = "write", at = @At("RETURN"))
    private void ip$writeShaft(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!ip$chain.isEmpty()) {
            compound.put("IPElevatorChain", IPElevatorShaft.writeChain(ip$chain));
        }
    }

    /** At every return: Create's read returns early for saved data (not a client packet). */
    @Inject(method = "read", at = @At("RETURN"))
    private void ip$readShaft(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket || compound.contains("IPElevatorChain")) {
            ip$chain = compound.contains("IPElevatorChain") ? IPElevatorShaft.readChain(compound.getCompound("IPElevatorChain")) : List.of();
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
