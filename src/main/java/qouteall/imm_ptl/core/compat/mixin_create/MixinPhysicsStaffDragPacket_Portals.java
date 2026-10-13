/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import foundry.veil.api.network.handler.ServerPacketContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortals;

import java.util.UUID;

/**
 * Routes Create Aeronautics (Simulated) physics staff drag packets to the staff handler of the dragged ship's level
 * (Simulated uses the player's), with the orientation mapped from the player's world into the ship's
 * ({@link IPPhysicsStaffPortals}).
 */
@Mixin(targets = "dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffDragPacket", remap = false)
public class MixinPhysicsStaffDragPacket_Portals {
    @WrapOperation(
        method = "handle",
        at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler;drag(Ljava/util/UUID;Ljava/util/UUID;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Quaterniondc;)V"),
        require = 0
    )
    private void ip_routeDrag(final PhysicsStaffServerHandler handler, final UUID player, final UUID ship, final Vector3dc relativeGoal,
                              final Vector3dc anchor, final Quaterniondc orientation, final Operation<Void> original,
                              @Local(argsOnly = true) final ServerPacketContext context) {
        final ServerPlayer serverPlayer = context.player();
        final ServerLevel level = IPPhysicsStaffPortals.dragLevel(serverPlayer, ship);
        if (level == null) {
            return;
        }
        final PhysicsStaffServerHandler target = PhysicsStaffServerHandler.get(level);
        original.call(target, player, ship, relativeGoal, IPPhysicsStaffPortals.dragAnchor(target, player, ship, anchor),
            IPPhysicsStaffPortals.dragOrientation(serverPlayer, ship, orientation));
    }
}
