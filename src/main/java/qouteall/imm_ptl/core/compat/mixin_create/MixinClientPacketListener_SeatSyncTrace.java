/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPSeatSyncTrace;
import qouteall.imm_ptl.core.compat.create_integration.IPSeatSyncTraceClient;

/**
 * Diagnostics ({@link IPSeatSyncTrace}): passengers and removal packets about carriages or the local player's vehicle,
 * with the level they are applied to (IP redirects them by dimension).
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener_SeatSyncTrace {
    @Shadow
    private ClientLevel level;

    @Inject(method = "handleSetEntityPassengersPacket", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_tracePassengers(final ClientboundSetPassengersPacket packet, final CallbackInfo ci) {
        if (IPSeatSyncTrace.ON) {
            IPSeatSyncTraceClient.onPassengers(level, packet);
        }
    }

    @Inject(
        method = "handleRemoveEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
        ),
        require = 0, expect = 0
    )
    private void ip_traceRemoveEntities(final ClientboundRemoveEntitiesPacket packet, final CallbackInfo ci) {
        if (IPSeatSyncTrace.ON) {
            IPSeatSyncTraceClient.onRemoveEntities(level, packet.getEntityIds());
        }
    }
}
