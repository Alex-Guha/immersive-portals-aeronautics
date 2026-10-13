/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortals;

import java.util.UUID;

/**
 * A Create Aeronautics (Simulated) physics staff drag whose player is in another world than the ship (through a
 * portal, {@link IPPhysicsStaffPortals}): the player is looked up server-wide and the goal point, the player's eye
 * plus the player-relative goal in the player's world, is mapped into the ship's world before Simulated expresses it
 * in the drag's frame.
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler$DragSession", remap = false)
public class MixinPhysicsStaffDragSession_Portals {
    @WrapOperation(
        method = "physicsTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"),
        require = 0
    )
    private Player ip_dragPlayer(final ServerLevel level, final UUID player, final Operation<Player> original) {
        final IPPhysicsStaffDragSessionAccessor self = (IPPhysicsStaffDragSessionAccessor) this;
        return IPPhysicsStaffPortals.dragPlayer(level, player, self.ip_getSubLevel().getUniqueId(), original.call(level, player));
    }

    @WrapOperation(
        method = "physicsTick",
        at = @At(value = "INVOKE", target = "Lorg/joml/Quaterniond;transformInverse(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"),
        require = 0
    )
    private Vector3d ip_mapGoal(final Quaterniond orientation, final Vector3d goal, final Operation<Vector3d> original) {
        final IPPhysicsStaffDragSessionAccessor self = (IPPhysicsStaffDragSessionAccessor) this;
        IPPhysicsStaffPortals.mapGoal(self.ip_getPlayerUUID(), self.ip_getSubLevel().getUniqueId(), goal);
        return original.call(orientation, goal);
    }

    @Inject(method = "onRemoved", at = @At("HEAD"), require = 0)
    private void ip_onRemoved(final CallbackInfo ci) {
        final IPPhysicsStaffDragSessionAccessor self = (IPPhysicsStaffDragSessionAccessor) this;
        IPPhysicsStaffPortals.onDragRemoved(self.ip_getPlayerUUID(), self.ip_getSubLevel().getUniqueId());
    }
}
