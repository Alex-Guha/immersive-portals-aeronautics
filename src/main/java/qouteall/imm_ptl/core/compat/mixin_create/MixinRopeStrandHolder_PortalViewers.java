/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_integration.IPRopePortalSync;

import java.util.List;
import java.util.UUID;

/**
 * Sends Create Aeronautics (Simulated) rope strands to players who see the rope holder through a portal.
 * <p>
 * {@code RopeStrandHolderBehavior} sends its strand only to players tracking its chunk in its own dimension,
 * raw, and drops tracked players that {@code level.getPlayerByUUID} cannot find. Here the tracking list also
 * holds portal viewers ({@link IPRopePortalSync#withPortalViewers}), packets to them are dimension-tagged, and
 * tracked players are looked up server-wide so a portal viewer is not dropped and re-sent the strand every
 * tick.
 */
@Mixin(
    targets = "dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior",
    remap = false
)
public abstract class MixinRopeStrandHolder_PortalViewers {
    @Shadow
    public abstract List<ServerPlayer> getStrandTrackingPlayers();

    @Inject(method = "getStrandTrackingPlayers", at = @At("RETURN"), cancellable = true, require = 0)
    private void ip_addPortalViewers(CallbackInfoReturnable<List<ServerPlayer>> cir) {
        BlockEntityBehaviour self = (BlockEntityBehaviour) (Object) this;
        if (self.getWorld() instanceof ServerLevel level) {
            cir.setReturnValue(IPRopePortalSync.withPortalViewers(level, self.getPos(), cir.getReturnValue()));
        }
    }

    @Inject(method = "getStrandPacketSink", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_routedPacketSink(CallbackInfoReturnable<VeilPacketManager.PacketSink> cir) {
        BlockEntityBehaviour self = (BlockEntityBehaviour) (Object) this;
        if (self.getWorld() instanceof ServerLevel level) {
            cir.setReturnValue(IPRopePortalSync.sinkFor(this.getStrandTrackingPlayers(), level));
        }
    }

    @Redirect(
        method = "tickStrandTrackingPlayers",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"),
        require = 0
    )
    private Player ip_findPlayerServerWide(Level level, UUID uuid) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getServer().getPlayerList().getPlayer(uuid);
        }
        return level.getPlayerByUUID(uuid);
    }

    @Redirect(
        method = "tickStrandTrackingPlayers",
        at = @At(value = "INVOKE", target = "Lfoundry/veil/api/network/VeilPacketManager;player(Lnet/minecraft/server/level/ServerPlayer;)Lfoundry/veil/api/network/VeilPacketManager$PacketSink;"),
        require = 0
    )
    private VeilPacketManager.PacketSink ip_routedPlayerSink(ServerPlayer player) {
        BlockEntityBehaviour self = (BlockEntityBehaviour) (Object) this;
        if (self.getWorld() instanceof ServerLevel level) {
            return IPRopePortalSync.sinkFor(player, level);
        }
        return VeilPacketManager.player(player);
    }
}
