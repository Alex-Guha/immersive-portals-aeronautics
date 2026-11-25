/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * IP disables {@link PlayerChunkSender}'s queue ({@link MixinPlayerChunkSender}) but sends each chunk through
 * vanilla's own {@code sendChunk}, so everything hooked into it still runs for chunks IP sends: NeoForge's auxiliary
 * light data and chunk-sent event, and other mods' mixins on it (map mods mark explored chunks there).
 */
@Mixin(PlayerChunkSender.class)
public interface IEPlayerChunkSender {
    @Invoker("sendChunk")
    static void ip_sendChunk(ServerGamePacketListenerImpl packetListener, ServerLevel level, LevelChunk chunk) {
        throw new AssertionError();
    }
}
