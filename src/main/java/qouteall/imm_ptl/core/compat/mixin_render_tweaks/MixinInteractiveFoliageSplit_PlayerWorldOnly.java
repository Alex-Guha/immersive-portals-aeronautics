/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.IPFoliageCompat;

/**
 * Other worlds' chunk meshes keep their foliage ({@code IPFoliageCompat}).
 * <p>
 * Interactive Foliage leaves foliage out of a chunk mesh by the section's position alone ({@code leaveToGpu}, on chunk
 * build threads) and draws it itself, for the player's world only. Another world's meshes in that area lost their
 * foliage, so it was missing from portal views. Their mesh uploads are not reported either: its table of sections
 * meshed without foliage is the player's world's (Sodium uploads a world's meshes in that world's pass, with IP's
 * {@code Minecraft.level} switched to it).
 */
@Mixin(targets = "net.karto.mc2.mc2_interactivefoliage.gpu.GpuFoliageSplit", remap = false)
public class MixinInteractiveFoliageSplit_PlayerWorldOnly {
    @Inject(method = "leaveToGpu", at = @At("HEAD"), cancellable = true)
    private static void ip_keepOtherWorldFoliage(
        BlockAndTintGetter getter, BlockPos pos, CallbackInfoReturnable<Boolean> cir
    ) {
        if (IPFoliageCompat.isOtherWorldBuild(getter)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onChunkMeshSwapped", at = @At("HEAD"), cancellable = true)
    private static void ip_skipOtherWorldMeshSwap(int x, int y, int z, CallbackInfo ci) {
        if (IPFoliageCompat.isOtherWorld()) {
            ci.cancel();
        }
    }
}
