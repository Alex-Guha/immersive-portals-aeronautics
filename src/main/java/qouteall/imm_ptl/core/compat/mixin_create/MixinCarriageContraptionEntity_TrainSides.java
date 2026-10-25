/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.level.Level;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.IPPortalTeleportExempt;
import qouteall.imm_ptl.core.compat.create_integration.IPCarriageSideHolder;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainSides;

/**
 * A carriage copy's own record ({@link IPCreateTrainSides#recordOf}) where Create looks up the record of the copy's
 * level: two copies of a carriage in one level (both sides of a portal within it) have different records. The copy's
 * side key is synced to clients, which need it before their own carriage reaches the portal. IP leaves carriage copies
 * to Create at its portals ({@link IPPortalTeleportExempt}).
 */
@Mixin(value = CarriageContraptionEntity.class, remap = false)
public class MixinCarriageContraptionEntity_TrainSides implements IPCarriageSideHolder, IPPortalTeleportExempt {
    @Shadow
    private UUID trainId;
    @Shadow
    private int carriageIndex;

    /**
     * Client: Create binds a copy to its carriage's record on every synced value it receives
     * ({@code onSyncedDataUpdated}), its own carriage data first, before the side the server gave the copy (ours is
     * defined later). A copy the server has just created on a portal's far side thus took the record of the copy
     * already there, which was then drawn from the far copy for a frame as a crossing started (2.5.7.2-2.5.7.5). A copy
     * with no side yet waits while another live copy holds the record of its level; its side arrives with the next
     * value of the same packet, and Create binds again.
     */
    @Inject(method = "bindCarriage", at = @At("HEAD"), cancellable = true)
    private void ip_waitForSide(final CallbackInfo ci) {
        if (IPCreateTrainSides.waitsForSide((CarriageContraptionEntity) (Object) this, trainId, carriageIndex)) {
            ci.cancel();
        }
    }

    @Unique
    private static final EntityDataAccessor<String> IP_TRAIN_SIDE =
        SynchedEntityData.defineId(CarriageContraptionEntity.class, EntityDataSerializers.STRING);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void ip_defineSide(final SynchedEntityData.Builder builder, final CallbackInfo ci) {
        builder.define(IP_TRAIN_SIDE, "");
    }

    @Override
    public String ip_getTrainSide() {
        return ((CarriageContraptionEntity) (Object) this).getEntityData().get(IP_TRAIN_SIDE);
    }

    @Override
    public void ip_setTrainSide(final String side) {
        ((CarriageContraptionEntity) (Object) this).getEntityData().set(IP_TRAIN_SIDE, side);
    }

    @WrapOperation(
        method = {"tickContraption", "bindCarriage", "setCarriage", "onClientRemoval"},
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/Carriage;getDimensional(Lnet/minecraft/world/level/Level;)Lcom/simibubi/create/content/trains/entity/Carriage$DimensionalCarriageEntity;"
        )
    )
    private Carriage.DimensionalCarriageEntity ip_ownRecord(final Carriage carriage, final Level level,
                                                            final Operation<Carriage.DimensionalCarriageEntity> original) {
        final Carriage.DimensionalCarriageEntity own = IPCreateTrainSides.recordOf(carriage, (CarriageContraptionEntity) (Object) this);
        return own != null ? own : original.call(carriage, level);
    }
}
