/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies a Shield Generators impact to the world it is for: the handler looks the generator up in
 * {@code context.player().level()}, the player's own dimension. Impacts on a shield seen through a portal arrive
 * dimension-tagged ({@code MixinShieldImpactPayload_PortalViewers}) and IP handles them with {@code Minecraft.level}
 * switched to that world; for untagged packets the two are the same.
 */
@Mixin(targets = "com.anton.shieldgenerators.ShieldImpactPayload", remap = false)
public class MixinShieldImpactPayload_RedirectedLevel {
    @Redirect(method = "handle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;level()Lnet/minecraft/world/level/Level;"), require = 0)
    private static Level ip_redirectedLevel(final Player player) {
        final ClientLevel level = Minecraft.getInstance().level;
        return level != null && player instanceof LocalPlayer ? level : player.level();
    }
}
