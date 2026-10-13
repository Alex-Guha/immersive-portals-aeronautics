/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable pushes a ship a player starts breaking a block of ("punching"): its packet names the block by plot position and
 * the server looks the ship up in the player's level. A block targeted in another dimension through IP's block
 * manipulation (a ship seen through a portal, the far half of a straddling ship seen from its destination) is in that
 * dimension's plot space, where the server would find another ship or none and push the one the player stands on.
 * Such punches are skipped.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.client.ClientSubLevelPunchHelper", remap = false)
public class MixinSablePunch_CrossDimension {
    @Inject(method = "clientTryPunch", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ip_skipOtherDimension(final BlockHitResult hitResult, final Level level, final boolean testCreativeBreaking,
                                              final CallbackInfo ci) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && level != minecraft.player.level()) {
            ci.cancel();
        }
    }
}
