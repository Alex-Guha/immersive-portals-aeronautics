/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.lang.ref.WeakReference;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossing;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateRiderHandOver;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateRiderTeleport;
import qouteall.imm_ptl.core.network.PacketRedirection;

/**
 * When a carriage carries a player across a portal, Create dismounts the player, moves them
 * to the other dimension with the vanilla {@code ServerPlayer.teleportTo} (which shows the
 * dimension-change loading screen) and re-seats them on the far-side carriage entity. That
 * call in {@code Carriage.DimensionalCarriageEntity.dismountPlayer} is routed through IP's
 * seamless player teleport instead; the vanilla call remains the fallback. The carriage
 * entity the player just left ({@code this.entity}) is handed over so the client is told
 * about the dismount before the dimension change.
 * <p>
 * Create sends the player to the far portal-track node, at track level. While the carriage crosses an IP portal
 * ({@link IPCreateCarriageCrossing}) the player goes to their seat's position mapped through the portal instead, and
 * is re-seated on the far copy in the same tick if it exists (Create would wait for the far copy's cut to move): the
 * camera stays where it was (1.24.5). The look is mapped through the portal, and the far copy's passengers are sent
 * at once, so the client is never a tick unseated (1.24.12).
 * <p>
 * While the carriage crosses an IP portal, a seat belongs to the copy on whose side of the plane its rider's head is,
 * not to Create's whole-block cut ({@link IPCreateRiderHandOver}, 1.24.12).
 */
@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity", remap = false)
public class MixinCarriageDimensionalEntity_SeamlessRiderTeleport {
    @Shadow
    public WeakReference<CarriageContraptionEntity> entity;

    /** The player's position while still seated, captured as Create starts dismounting them. */
    @Unique
    @Nullable
    private Vec3 ip_seatedPosition;

    @WrapOperation(
        method = "updatePassengerLoadout",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;isLocalCoordWithin(Lnet/minecraft/core/BlockPos;II)Z"
        ),
        require = 0,
        expect = 0
    )
    private boolean ip_seatOnThisSideOfPlane(final CarriageContraptionEntity carriage, final BlockPos seat, final int min,
                                             final int max, final Operation<Boolean> original) {
        final IPCreateCarriageCrossing.Crossing crossing = seat == null ? null
            : IPCreateCarriageCrossing.of(carriage, (Carriage.DimensionalCarriageEntity) (Object) this);
        if (crossing == null || crossing.farHalf()) {
            return original.call(carriage, seat, min, max);
        }
        return IPCreateRiderHandOver.isOnOwnSide(carriage, crossing, seat);
    }

    @Inject(method = "dismountPlayer", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_rememberSeat(final ServerLevel level, final ServerPlayer player, final Integer seat, final boolean capture,
                                 final CallbackInfo ci) {
        ip_seatedPosition = player.position();
    }

    @WrapOperation(
        method = "dismountPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V"
        ),
        require = 0,
        expect = 0
    )
    private void ip_teleportRiderSeamlessly(
        ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch,
        Operation<Void> original
    ) {
        CarriageContraptionEntity leftCarriage = entity != null ? entity.get() : null;
        Vec3 target = new Vec3(x, y, z);
        final IPCreateCarriageCrossing.Crossing crossing = leftCarriage != null
            ? IPCreateCarriageCrossing.of(leftCarriage, (Carriage.DimensionalCarriageEntity) (Object) this) : null;
        final Vec3 seated = ip_seatedPosition;
        ip_seatedPosition = null;
        if (crossing != null && seated != null && crossing.portal().getDestDim().equals(level.dimension())) {
            target = crossing.portal().transformPoint(seated);
            final Vec3 look = crossing.portal().transformLocalVecNonScale(player.getLookAngle());
            player.setYRot((float) Math.toDegrees(Math.atan2(-look.x, look.z)));
            player.setXRot((float) Math.toDegrees(Math.atan2(-look.y, Math.sqrt(look.x * look.x + look.z * look.z))));
        }
        if (!IPCreateRiderTeleport.teleport(player, leftCarriage, level, target.x, target.y, target.z)) {
            original.call(player, level, x, y, z, yaw, pitch);
            return;
        }
        if (crossing != null && leftCarriage.getCarriage() != null) {
            final Carriage.DimensionalCarriageEntity far =
                ((IPCarriageAccessor) leftCarriage.getCarriage()).ip_getEntities().get(crossing.otherKey());
            final CarriageContraptionEntity farEntity = far != null ? far.entity.get() : null;
            if (farEntity != null) {
                far.updatePassengerLoadout();
                if (farEntity.hasPassenger(player)) {
                    // vanilla would send it with the next entity-tracker tick: one tick standing in the air
                    PacketRedirection.sendRedirectedMessage(
                        player, level.dimension(), new ClientboundSetPassengersPacket(farEntity));
                }
            }
        }
    }
}
