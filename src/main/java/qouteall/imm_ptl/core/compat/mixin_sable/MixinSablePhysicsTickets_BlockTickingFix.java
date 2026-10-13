/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Widens Sable's {@code PhysicsChunkTicketManager.isChunkLoadedEnough}: when it would
 * return false, returns true if any player is within
 * {@code max(simulationDistance, ceil(SUB_LEVEL_TRACKING_RANGE / 16) + 1)} chunks, so ship
 * chunks and physics stay active out to ship visibility range (ships seen through portals
 * keep moving). Not portal-specific; changes Sable physics semantics globally.
 */
@Mixin(
    value = PhysicsChunkTicketManager.class,
    remap = false
)
public class MixinSablePhysicsTickets_BlockTickingFix {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged = false;

    @Inject(
        method = "isChunkLoadedEnough",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private static void ip_alsoCountSimulationDistance(
        ServerLevel level, int chunkX, int chunkZ, CallbackInfoReturnable<Boolean> cir
    ) {
        try {
            if (cir.getReturnValueZ()) {
                return;
            }

            int range = level.getServer().getPlayerList().getSimulationDistance();

            try {
                double trackingRange = SableConfig.SUB_LEVEL_TRACKING_RANGE.getAsDouble();
                range = Math.max(range, (int) Math.ceil(trackingRange / 16.0) + 1);
            } catch (Throwable ignored) {
            }

            for (ServerPlayer player : level.players()) {
                ChunkPos playerChunk = player.chunkPosition();
                if (Math.max(Math.abs(playerChunk.x - chunkX), Math.abs(playerChunk.z - chunkZ)) <= range) {
                    if (!ip_logged) {
                        ip_logged = true;
                        ip_LOGGER.debug(
                            "[IPSableTicketFix] keeping ship chunks loaded out to {} chunks around players (ship visibility range)",
                            range
                        );
                    }

                    cir.setReturnValue(true);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
