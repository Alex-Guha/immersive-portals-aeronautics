/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;

/**
 * Neo Voxy on a server sends each player the chunks under the ships they track (their light lights the ship), skipping
 * those the player's vanilla chunk tracking view holds. IP sends chunks itself and leaves that view empty, so every
 * chunk under every tracked ship was sent again in full (once per player and ship: around each ship a player starts
 * tracking, a ship that just crossed into another world included), and chunks IP never sent were loaded on the client
 * and never forgotten, since only IP tells the client to forget a chunk. The chunks IP has sent the player are now the
 * tracked ones ({@link ImmPtlChunkTracking#isPlayerWatchingChunk}), and the others get their light only, as Neo Voxy
 * already does outside the client's chunk storage. A class Roxy's Voxy does not have.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.commonImpl.compat.sable.SableParentChunkLightSync", remap = false)
public class MixinVoxySableParentChunkLightSync_IPChunkTracking {
    @WrapOperation(
        method = "syncFootprint",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkTrackingView;contains(II)Z"),
        require = 0
    )
    private static boolean ip_sentByIP(
        ChunkTrackingView view, int x, int z, Operation<Boolean> original,
        @Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) ServerPlayer player
    ) {
        return ImmPtlChunkTracking.isPlayerWatchingChunk(player, level.dimension(), x, z);
    }

    @WrapOperation(
        method = "syncFootprint",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"
        ),
        require = 0
    )
    private static void ip_lightOnly(
        ServerGamePacketListenerImpl connection, Packet<?> packet, Operation<Void> original,
        @Local(argsOnly = true) ServerLevel level
    ) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            packet = new ClientboundLightUpdatePacket(
                new ChunkPos(chunkPacket.getX(), chunkPacket.getZ()), level.getLightEngine(), null, null
            );
        }
        original.call(connection, packet);
    }
}
