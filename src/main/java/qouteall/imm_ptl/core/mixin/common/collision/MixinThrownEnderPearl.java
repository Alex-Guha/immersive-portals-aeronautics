/*
 * Immersive Portals Revamped - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals Revamped, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.common.collision;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.Helper;

@Mixin(ThrownEnderpearl.class)
public class MixinThrownEnderPearl {
    // Immersive Aeronautics: since 1.21 vanilla itself teleports a pearl's owner into the pearl's dimension, through
    // changeDimension: for a player that is a respawn with a loading screen, ahead of IP's own seamless teleport
    // (which ran at discard() and then found the player already there). Vanilla still decides whether the owner may
    // go (isAllowedToTeleportOwner, NeoForge's pearl event and its target); only the dimension change itself is IP's.
    @WrapOperation(
        method = "onHit",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;changeDimension(Lnet/minecraft/world/level/portal/DimensionTransition;)Lnet/minecraft/world/entity/Entity;"
        )
    )
    private Entity ip_teleportOwnerSeamlessly(
        Entity entity, DimensionTransition transition, Operation<Entity> original
    ) {
        if (!(entity instanceof ServerPlayer player) || transition.newLevel() == player.level()) {
            return original.call(entity, transition);
        }
        // the travel event vanilla's changeDimension fires first, so mods that keep players out of a dimension still can
        if (!CommonHooks.onTravelToDimension(player, transition.newLevel().dimension())) {
            return null;
        }
        Helper.log("Doing cross-dimensional ender pearl teleportation");
        return ServerTeleportationManager.teleportEntityGeneral(player, transition.pos(), transition.newLevel());
    }
}
