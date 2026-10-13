/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla {@code ChunkMap.playerMap} and {@code ChunkMap.isChunkTracked} so
 * {@code IPSableCompatHelper.getPlayersMerged} can merge vanilla-tracked players into IP's
 * {@code getPlayers} override.
 */
@Mixin(ChunkMap.class)
public interface IPChunkMapAccessor {
    @Accessor("playerMap")
    PlayerMap ip_getPlayerMap();

    @Invoker("isChunkTracked")
    boolean ip_isChunkTracked(ServerPlayer player, int chunkX, int chunkZ);
}
