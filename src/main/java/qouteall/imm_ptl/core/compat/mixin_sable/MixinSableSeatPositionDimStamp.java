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
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.network.PacketRedirection;

/**
 * Stamps outgoing {@code ClientboundPlayerPositionPacket}s (e.g. Sable seat position
 * updates) with the player's current dimension via IP's
 * {@link IEPlayerPositionLookS2CPacket#ip_setPlayerDimension} when it is not yet set,
 * so the client applies them to the right world.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class MixinSableSeatPositionDimStamp {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Inject(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void ip_stampPlayerDimension(Packet<?> packet, PacketSendListener sendListener, CallbackInfo ci) {
        try {
            // a seat clicked on a far half: the click runs inside IP's packet redirection
            Packet<?> inner = packet instanceof ClientboundCustomPayloadPacket custom
                && custom.payload() instanceof PacketRedirection.Payload redirected ? redirected.packet() : packet;
            if (inner instanceof ClientboundPlayerPositionPacket positionPacket) {
                IEPlayerPositionLookS2CPacket ipPacket = (IEPlayerPositionLookS2CPacket) positionPacket;
                if (ipPacket.ip_getPlayerDimension() == null
                    && (Object) this instanceof ServerGamePacketListenerImpl gameListener
                ) {
                    ipPacket.ip_setPlayerDimension(gameListener.player.level().dimension());
                }
            }
        } catch (Throwable t) {
            ip_LOGGER.warn("[IPPosPacketStamp] failed: {}", String.valueOf(t));
        }
    }
}
