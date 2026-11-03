/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Neo Voxy freezes the Create machines of a client chunk as it unloads (distant kinetics), for the player's world only:
 * it tells that world by {@code Minecraft.level}. IP forgets a portal-side world's chunk with {@code Minecraft.level}
 * switched to that world, so its machines were captured into the player's world's distant kinetics and drawn there at
 * the other world's coordinates. Only the player's world is captured now. A class Roxy's Voxy does not have.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.compat.create.KineticSnapshots", remap = false)
public class MixinVoxyKineticSnapshots_PlayerWorldOnly {
    @Inject(method = "captureUnloadingChunk", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ip_playerWorldOnly(ClientLevel level, LevelChunk chunk, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || level != minecraft.player.level()) {
            ci.cancel();
        }
    }
}
