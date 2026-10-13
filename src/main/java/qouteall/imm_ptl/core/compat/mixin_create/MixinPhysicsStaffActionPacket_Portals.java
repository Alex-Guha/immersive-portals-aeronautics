/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortals;

import java.util.UUID;

/**
 * Create Aeronautics (Simulated) physics staff actions for a ship of another level (seen through a portal,
 * {@link IPPhysicsStaffPortals}): the lock is toggled by the ship's own level, and a drag ends in whichever level it is.
 */
@Mixin(targets = "dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffActionPacket", remap = false)
public class MixinPhysicsStaffActionPacket_Portals {
    @WrapOperation(
        method = "handle",
        at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler;toggleLock(Ljava/util/UUID;)V"),
        require = 0
    )
    private void ip_routeLock(final PhysicsStaffServerHandler handler, final UUID ship, final Operation<Void> original,
                              @Local(argsOnly = true) final PacketContext context) {
        if (context.player() instanceof final ServerPlayer player) {
            original.call(IPPhysicsStaffPortals.lockHandler(player, ship, handler), ship);
        } else {
            original.call(handler, ship);
        }
    }

    @WrapOperation(
        method = "handle",
        at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler;stopDragging(Ljava/util/UUID;)V"),
        require = 0
    )
    private void ip_stopEverywhere(final PhysicsStaffServerHandler handler, final UUID player, final Operation<Void> original,
                                   @Local(argsOnly = true) final PacketContext context) {
        if (context.player() instanceof final ServerPlayer serverPlayer) {
            IPPhysicsStaffPortals.stopDragging(serverPlayer.server, player);
        } else {
            original.call(handler, player);
        }
    }
}
