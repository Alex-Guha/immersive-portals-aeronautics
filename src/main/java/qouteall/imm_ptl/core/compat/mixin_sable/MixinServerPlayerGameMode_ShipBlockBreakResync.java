/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

/**
 * Keeps the client in sync when a player breaks a block on a Sable ship: blocks in plot
 * space are far (>64 blocks) from the player's world position, so after vanilla's
 * {@code handleBlockBreakAction} runs, the resulting block state is re-sent directly with a
 * {@code ClientboundBlockUpdatePacket}. Without this the client's view of the far block can
 * stay stale.
 */
@Mixin(ServerPlayerGameMode.class)
public class MixinServerPlayerGameMode_ShipBlockBreakResync {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(
        method = "handleBlockBreakAction",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void ip_syncFarBreakResult(
        BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction face, int maxBuildHeight, int sequence, CallbackInfo ci
    ) {
        double dx = this.player.getX() - (pos.getX() + 0.5);
        double dy = this.player.getY() - (pos.getY() + 0.5);
        double dz = this.player.getZ() - (pos.getZ() + 0.5);
        if (dx * dx + dy * dy + dz * dz > 4096.0) {
            // the world the action ran in: IP's cross-portal block manipulation redirects it (the packet is then
            // dimension-tagged for that world too)
            BlockManipulationServer.Context redirect = BlockManipulationServer.REDIRECT_CONTEXT.get();
            ServerLevel world = redirect != null ? redirect.world() : this.player.serverLevel();
            this.player.connection.send(new ClientboundBlockUpdatePacket(pos, world.getBlockState(pos)));
            ip_LOGGER.debug("[IPShipBreakFix2] direct-synced {} after {}", pos, action);
        }
    }
}
