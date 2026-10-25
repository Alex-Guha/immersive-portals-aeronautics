/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCarriageSideHolder;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainSides;

/**
 * A carriage copy's record on a side key ({@link IPCreateTrainSides}): its portal node is the side's face; a copy it
 * creates gets a UUID of its own when another copy in the level has the carriage's (Create builds every copy from one
 * saved entity, and a level refuses a second entity with the same UUID) and is told its side; a rider handed over to a
 * copy on another side is moved within the real level.
 */
@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity", remap = false)
public class MixinCarriageDimensionalEntity_TrainSides {
    @Shadow
    public TrackNodeLocation pivot;
    @Shadow
    @Final
    Carriage this$0;

    @Inject(method = "findPivot", at = @At("HEAD"), cancellable = true)
    private void ip_sideFace(final ResourceKey<Level> dimension, final boolean leading,
                             final CallbackInfoReturnable<TrackNodeLocation> cir) {
        if (IPCreateTrainSides.isSide(dimension)) {
            pivot = IPCreateTrainSides.face(dimension);
            cir.setReturnValue(pivot);
        }
    }

    @WrapOperation(
        method = "createEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;"
        )
    )
    private Optional<Entity> ip_copyOfASide(final CompoundTag tag, final Level level, final Operation<Optional<Entity>> original) {
        final Optional<Entity> created = original.call(tag, level);
        created.ifPresent(entity -> {
            if (!(entity instanceof final CarriageContraptionEntity copy) || !(level instanceof final ServerLevel serverLevel)) {
                return;
            }
            if (serverLevel.getEntity(copy.getUUID()) != null) {
                copy.setUUID(UUID.randomUUID());
            }
            final ResourceKey<Level> key = IPCreateTrainSides.keyOfRecord(this$0, (Carriage.DimensionalCarriageEntity) (Object) this);
            ((IPCarriageSideHolder) copy).ip_setTrainSide(key != null && IPCreateTrainSides.isSide(key) ? key.location().toString() : "");
        });
        return created;
    }

    @WrapOperation(
        method = "dismountPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"
        )
    )
    private ServerLevel ip_realLevel(final MinecraftServer server, final ResourceKey<Level> key, final Operation<ServerLevel> original) {
        return original.call(server, IPCreateTrainSides.realDimension(key));
    }
}
