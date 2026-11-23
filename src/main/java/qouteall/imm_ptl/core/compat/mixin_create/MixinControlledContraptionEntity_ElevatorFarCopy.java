/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorFarCopy;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorShaft;

/**
 * A controlled contraption as an elevator car's far copy ({@link IPElevatorPortals}). Which car it stands for travels
 * to clients with the entity's spawn data; far copies are never saved ({@code MixinEntity_ElevatorFarCopy}).
 */
@Mixin(value = ControlledContraptionEntity.class, remap = false)
public class MixinControlledContraptionEntity_ElevatorFarCopy implements IPElevatorFarCopy {
    @Unique
    private @Nullable FarInfo ip$farInfo;
    @Unique
    private @Nullable Vec3 ip$target;
    @Unique
    private @Nullable Vec3 ip$prevTarget;
    @Unique
    private long ip$lastPush;

    @Override
    public @Nullable FarInfo ip$farInfo() {
        return ip$farInfo;
    }

    @Override
    public void ip$setFarInfo(@Nullable FarInfo info) {
        ip$farInfo = info;
    }

    @Override
    public @Nullable Vec3 ip$target() {
        return ip$target;
    }

    @Override
    public @Nullable Vec3 ip$prevTarget() {
        return ip$prevTarget;
    }

    @Override
    public void ip$setTargets(Vec3 target, Vec3 prev) {
        ip$target = target;
        ip$prevTarget = prev;
    }

    @Override
    public long ip$lastPush() {
        return ip$lastPush;
    }

    @Override
    public void ip$setLastPush(long tick) {
        ip$lastPush = tick;
    }

    /** A far copy has no controller: Create would write its position relative to the entity without a null check. */
    @WrapOperation(
        method = "writeAdditional",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;subtract(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos ip$noController(BlockPos controllerPos, Vec3i entityPos, Operation<BlockPos> original) {
        return controllerPos == null ? BlockPos.ZERO : original.call(controllerPos, entityPos);
    }

    @Inject(method = "writeAdditional", at = @At("TAIL"))
    private void ip$writeFarInfo(CompoundTag compound, HolderLookup.Provider registries, boolean spawnPacket, CallbackInfo ci) {
        if (ip$farInfo != null) {
            // read back, a controller at the copy's own position would make it discard itself (no controller there)
            compound.remove("ControllerRelative");
        }
        if (spawnPacket && ip$farInfo != null) {
            final CompoundTag tag = new CompoundTag();
            tag.putString("HomeDim", ip$farInfo.homeDim().location().toString());
            tag.put("Pulley", NbtUtils.writeBlockPos(ip$farInfo.pulleyPos()));
            tag.put("Shaft", ip$farInfo.shaft().write());
            tag.putInt("Index", ip$farInfo.index());
            tag.putDouble("BottomPlane", ip$farInfo.bottomPlane());
            compound.put("IPElevatorFarCopy", tag);
        }
    }

    @Inject(method = "readAdditional", at = @At("TAIL"))
    private void ip$readFarInfo(CompoundTag compound, boolean spawnPacket, CallbackInfo ci) {
        if (!spawnPacket || !compound.contains("IPElevatorFarCopy")) {
            return;
        }
        final CompoundTag tag = compound.getCompound("IPElevatorFarCopy");
        final ResourceLocation dim = ResourceLocation.tryParse(tag.getString("HomeDim"));
        final IPElevatorShaft shaft = IPElevatorShaft.read(tag.getCompound("Shaft"));
        if (dim == null || shaft == null) {
            return;
        }
        ip$farInfo = new FarInfo(ResourceKey.create(Registries.DIMENSION, dim), NbtUtils.readBlockPos(tag, "Pulley").orElseThrow(),
            tag.getInt("Index"), shaft, tag.contains("BottomPlane") ? tag.getDouble("BottomPlane") : Double.NEGATIVE_INFINITY);
        final ControlledContraptionEntity self = (ControlledContraptionEntity) (Object) this;
        if (self.level().isClientSide()) {
            IPElevatorPortals.onClientFarCopy(self, ip$farInfo);
        }
    }
}
