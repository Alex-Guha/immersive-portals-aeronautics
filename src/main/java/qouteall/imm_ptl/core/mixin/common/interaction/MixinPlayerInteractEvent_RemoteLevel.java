/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.common.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

/**
 * A click through a portal (or on the far half of a straddling ship) is in another world than the player's: NeoForge's
 * interaction events report the player's level, so handlers that act in {@code event.getLevel()} (Create's
 * {@code ItemUseOverrides}, which runs the use itself and cancels vanilla's) acted on whatever was at those coordinates
 * in the player's world. During IP's remote interaction they report the world clicked in: the server's redirected world,
 * the client's switched world.
 */
@Mixin(value = PlayerInteractEvent.class, remap = false)
public class MixinPlayerInteractEvent_RemoteLevel {
    @Inject(method = "getLevel", at = @At("HEAD"), cancellable = true)
    private void ip_remoteLevel(final CallbackInfoReturnable<Level> cir) {
        final Player player = ((PlayerEvent) (Object) this).getEntity();
        if (player instanceof ServerPlayer) {
            final BlockManipulationServer.Context redirect = BlockManipulationServer.REDIRECT_CONTEXT.get();
            if (redirect != null) {
                cir.setReturnValue(redirect.world());
            }
        } else if (player.level().isClientSide()) {
            final Level switched = BlockManipulationClient.switchedLevelFor(player);
            if (switched != null) {
                cir.setReturnValue(switched);
            }
        }
    }
}
