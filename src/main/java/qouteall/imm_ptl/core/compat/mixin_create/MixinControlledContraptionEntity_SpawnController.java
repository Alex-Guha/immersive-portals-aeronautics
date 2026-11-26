/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends a controlled contraption's controller to clients as an absolute position. Create writes it relative to the
 * entity's block position ({@code ControllerRelative}) and the client adds it back to the position of the entity's
 * spawn packet, but that packet carries the position last sent to clients, which for these entities is refreshed only
 * every 40 ticks. A player who starts seeing a moving pulley, elevator or piston contraption (an elevator car whose
 * rider goes through a portal is seen again from the other side) gets a controller blocks away from the real one, and
 * Create's client discards the contraption for having no controller: the blocks vanish until it stops. Saved data keeps
 * Create's relative form.
 */
@Mixin(value = ControlledContraptionEntity.class, remap = false)
public class MixinControlledContraptionEntity_SpawnController {
    @Shadow
    protected BlockPos controllerPos;

    @Inject(method = "writeAdditional", at = @At("TAIL"))
    private void ip$writeController(CompoundTag compound, HolderLookup.Provider registries, boolean spawnPacket, CallbackInfo ci) {
        if (spawnPacket && controllerPos != null) {
            compound.put("IPController", NbtUtils.writeBlockPos(controllerPos));
        }
    }

    @Inject(method = "readAdditional", at = @At("TAIL"))
    private void ip$readController(CompoundTag compound, boolean spawnPacket, CallbackInfo ci) {
        if (spawnPacket && compound.contains("IPController")) {
            NbtUtils.readBlockPos(compound, "IPController").ifPresent(pos -> controllerPos = pos);
        }
    }
}
