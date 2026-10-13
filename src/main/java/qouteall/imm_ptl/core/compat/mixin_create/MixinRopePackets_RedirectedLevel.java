/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies Create Aeronautics (Simulated) rope packets to the world they are for.
 * <p>
 * The rope data and rope stopped handlers look the rope holder up in {@code context.player().level()}, the
 * player's own dimension. Rope packets for a holder seen through a portal arrive dimension-tagged
 * ({@code MixinRopeStrandHolder_PortalViewers}) and IP handles them with {@code Minecraft.level} switched to
 * that world, so the handlers read {@code Minecraft.level} instead; for untagged packets the two are the same.
 */
@Mixin(
    targets = {
        "dev.simulated_team.simulated.network.packets.rope.ClientboundRopeDataPacket",
        "dev.simulated_team.simulated.network.packets.rope.ClientboundRopeStoppedPacket"
    },
    remap = false
)
public class MixinRopePackets_RedirectedLevel {
    @Redirect(
        method = "handle",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;level()Lnet/minecraft/world/level/Level;"),
        require = 0
    )
    private Level ip_redirectedLevel(LocalPlayer player) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null ? level : player.level();
    }
}
