/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPHandlePortals;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;

import java.util.UUID;

/**
 * A Create Aeronautics (Simulated) handle held from another world than its ship's ({@link IPHandlePortals}) keeps its
 * player: Simulated looks the player up in the handle's level and lets go of one it cannot find.
 */
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.handle.HandleBlockEntity", remap = false)
public class MixinHandleBlockEntity_Portals {
    @WrapOperation(
        method = "checkPlayers",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"),
        require = 0
    )
    private Player ip_holdingPlayer(final Level level, final UUID player, final Operation<Player> original) {
        final Player own = original.call(level, player);
        if (!(level instanceof final ServerLevel serverLevel)) {
            return own;
        }
        return IPHandlePortals.holdingPlayer(serverLevel, IPStraddleCut.shipAt(level, ((BlockEntity) (Object) this).getBlockPos()), player, own);
    }
}
