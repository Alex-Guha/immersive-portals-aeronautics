/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateSeatedView;

/**
 * Client: a position packet for a player seated on a carriage crossing a portal carries the server's seat position,
 * ahead of the carriage this client draws; the player goes back on the seat at once ({@link IPCreateSeatedView#onSeated}).
 * The rider hand-over sends such packets after the passengers packet too (the dismount's tracker update).
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener_CarriageSeat {
    @Inject(method = "handleMovePlayer", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_backOnCarriageSeat(final ClientboundPlayerPositionPacket packet, final CallbackInfo ci) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getVehicle() instanceof final CarriageContraptionEntity carriage) {
            IPCreateSeatedView.onSeated(player, carriage);
        }
    }
}
