/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sable_integration.IPRopePortalSync;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipThroughPortalSync;

import java.util.List;

/**
 * Shield Generators sends a shield impact (the flash and ripple on the field) to the players tracking the generator's
 * chunk, raw. For a generator on a ship that is a plot chunk of the ship's dimension, so a player who sees the ship
 * through a portal never got it, or got it applied to their own world. Here it also goes to the players the ship is
 * synced to through a portal, dimension-tagged (as rope strands, {@link IPRopePortalSync}); the client handler reads
 * the redirected world ({@code MixinShieldImpactPayload_RedirectedLevel}).
 */
@Mixin(targets = "com.anton.shieldgenerators.ShieldImpactPayload", remap = false)
public class MixinShieldImpactPayload_PortalViewers {
    @Redirect(method = "send", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToPlayersTrackingChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"),
            require = 0)
    private static void ip_sendToPortalViewers(final ServerLevel level, final ChunkPos chunk, final CustomPacketPayload payload,
                                               final CustomPacketPayload[] more) {
        if (more.length > 0) {
            PacketDistributor.sendToPlayersTrackingChunk(level, chunk, payload, more);
            return;
        }
        final List<ServerPlayer> trackers = level.getChunkSource().chunkMap.getPlayers(chunk, false);
        final List<ServerPlayer> players = IPRopePortalSync.withPortalViewers(level, chunk.getWorldPosition(), trackers);
        final Packet<?> packet = new ClientboundCustomPayloadPacket(payload);
        for (final ServerPlayer player : players) {
            IPShipThroughPortalSync.sendToViewer(player, level.dimension(), packet);
        }
    }
}
