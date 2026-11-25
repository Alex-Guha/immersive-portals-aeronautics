/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An elevator contact saves its column's target as a height ({@code ColumnTarget}) and sets it again when loaded. Blocks
 * moved with their data (a ship assembled or disassembled: Sable saves each block entity where it was and loads it where
 * it goes) keep that height in the old place's frame, so the column's target pointed at no floor. Through a portal the
 * column is linked to the pulley's, which then sent the car on past every floor, as far as the assembly moved the
 * blocks. The target is saved relative to the contact too, and read back relative to where the contact is now.
 */
@Mixin(value = ElevatorContactBlockEntity.class, remap = false)
public class MixinElevatorContactBlockEntity_RelativeTarget {
    @Unique
    private static final String IP$RELATIVE = "IPColumnTargetRelative";

    @Inject(method = "write", at = @At("TAIL"))
    private void ip$writeRelativeTarget(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (tag.contains("ColumnTarget")) {
            tag.putInt(IP$RELATIVE, tag.getInt("ColumnTarget") - ((ElevatorContactBlockEntity) (Object) this).getBlockPos().getY());
        }
    }

    /** The one {@code getInt} of {@code read}: the saved target, which the contact applies now or at its first tick. */
    @ModifyExpressionValue(
        method = "read",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;getInt(Ljava/lang/String;)I"),
        require = 1
    )
    private int ip$readRelativeTarget(int saved, CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        return tag.contains(IP$RELATIVE) ? ((ElevatorContactBlockEntity) (Object) this).getBlockPos().getY() + tag.getInt(IP$RELATIVE) : saved;
    }
}
