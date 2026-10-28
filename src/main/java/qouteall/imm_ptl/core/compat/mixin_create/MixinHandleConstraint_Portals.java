/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPHandlePortals;

import java.util.UUID;

/**
 * A Create Aeronautics (Simulated) handle held from another world than its ship's, or on its far half
 * ({@link IPHandlePortals}): the player is looked up server-wide, the goal the ship is pulled towards (the player's eye
 * plus look, in the player's world) is mapped into the ship's world, and a player standing on the ship's far half
 * stands on the ship (Simulated never pulls the ship its player stands on).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.handle.HandleBlockEntity$HandleConstraint", remap = false)
public class MixinHandleConstraint_Portals {
    @Shadow
    @Final
    private UUID playerId;

    @WrapOperation(
        method = "physicsTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"),
        require = 0
    )
    private Player ip_holdingPlayer(final Level level, final UUID player, final Operation<Player> original,
                                    @Local(argsOnly = true) final ServerSubLevel ship) {
        return IPHandlePortals.holdingPlayer((ServerLevel) level, ship, player, original.call(level, player));
    }

    @WrapOperation(
        method = "physicsTick",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getTrackingSubLevel(Lnet/minecraft/world/entity/Entity;)Ldev/ryanhcode/sable/sublevel/SubLevel;"),
        require = 0
    )
    private SubLevel ip_standingShip(final ActiveSableCompanion helper, final Entity entity, final Operation<SubLevel> original) {
        final SubLevel own = original.call(helper, entity);
        return entity instanceof final Player player ? IPHandlePortals.standingShip(player, own) : own;
    }

    @WrapOperation(
        method = "physicsTick",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/companion/math/JOMLConversion;toJOML(Lnet/minecraft/core/Position;)Lorg/joml/Vector3d;"),
        require = 0
    )
    private Vector3d ip_mapGoal(final Position goal, final Operation<Vector3d> original, @Local(argsOnly = true) final ServerSubLevel ship) {
        final Vector3d mapped = original.call(goal);
        IPHandlePortals.mapGoal(this.playerId, ship, mapped);
        return mapped;
    }
}
