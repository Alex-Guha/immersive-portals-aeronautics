/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import foundry.veil.api.network.handler.ClientPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.sable_integration.IPRopePacketLevel;

/**
 * Handles Create Aeronautics (Simulated) rope packets for a world seen through a portal with the player in that world,
 * so that every mod hooking these handlers looks the rope holder up where the packet is for
 * ({@link IPRopePacketLevel}). A method wrap, so other mods' injections inside {@code handle} are covered.
 */
@Mixin(
    targets = {
        "dev.simulated_team.simulated.network.packets.rope.ClientboundRopeDataPacket",
        "dev.simulated_team.simulated.network.packets.rope.ClientboundRopeStoppedPacket"
    },
    remap = false
)
public class MixinRopePackets_PlayerLevel {
    @WrapMethod(method = "handle")
    private void ip_handleInPacketLevel(final ClientPacketContext context, final Operation<Void> original) {
        IPRopePacketLevel.run(() -> original.call(context));
    }
}
