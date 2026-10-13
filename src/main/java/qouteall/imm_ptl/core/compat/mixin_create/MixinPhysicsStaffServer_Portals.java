/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortals;

import java.util.Map;
import java.util.UUID;

/**
 * Keeps a Create Aeronautics (Simulated) physics staff drag alive while its player is in another world than the ship
 * (through a portal): the handler of the ship's level looks the player up server-wide ({@link IPPhysicsStaffPortals}).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler", remap = false)
public class MixinPhysicsStaffServer_Portals {
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"),
        require = 0
    )
    private Player ip_dragPlayer(final ServerLevel level, final UUID player, final Operation<Player> original,
                                 @Local final Map.Entry<UUID, Object> entry) {
        final Player own = original.call(level, player);
        if (entry.getValue() instanceof final IPPhysicsStaffDragSessionAccessor drag) {
            return IPPhysicsStaffPortals.dragPlayer(level, player, drag.ip_getSubLevel().getUniqueId(), own);
        }
        return own;
    }
}
