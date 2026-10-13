/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

/**
 * Moves a train rider to the carriage's far-side position with IP's seamless player teleport
 * (dimension change without the loading screen, position packet carrying the dimension,
 * immediate chunk-tracking update). Used by
 * {@code MixinCarriageDimensionalEntity_SeamlessRiderTeleport}.
 * <p>
 * Create has already dismounted the player server-side, but vanilla only tells the client on
 * the next entity-tracker tick, after IP's position packet. IP's client-side dimension change
 * carries the player's current vehicle into the new world, so the client would drag the
 * carriage it still believes it rides (the copy of the dimension being left, which shares its
 * UUID with the far-side copy) into the destination world as a ghost that then drives the
 * shared carriage state. The passengers update for the carriage the player left is therefore
 * sent first, redirected to that carriage's dimension.
 */
public final class IPCreateRiderTeleport {
    private static final Logger LOGGER = LogUtils.getLogger();

    private IPCreateRiderTeleport() {
    }

    /**
     * @param leftCarriage the carriage entity the player was just dismounted from, if known
     * @return false if IP's teleport could not be used; the caller then falls back to vanilla
     */
    public static boolean teleport(
        ServerPlayer player, @Nullable Entity leftCarriage, ServerLevel level, double x, double y, double z
    ) {
        try {
            if (leftCarriage != null && !leftCarriage.hasPassenger(player)) {
                PacketRedirection.sendRedirectedMessage(
                    player, leftCarriage.level().dimension(), new ClientboundSetPassengersPacket(leftCarriage)
                );
            }
            ServerTeleportationManager.of(player.server)
                .forceTeleportPlayer(player, level.dimension(), new Vec3(x, y, z));
            LOGGER.info(
                "[IPTrainRider] moved {} to {} {} {} {} with IP's seamless teleport (left carriage {})",
                player.getName().getString(), level.dimension().location(), x, y, z,
                leftCarriage != null ? leftCarriage.getId() : "unknown"
            );
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[IPTrainRider] seamless rider teleport failed; using vanilla teleport", t);
            return false;
        }
    }
}
