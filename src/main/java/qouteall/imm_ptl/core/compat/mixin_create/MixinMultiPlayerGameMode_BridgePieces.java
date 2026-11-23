/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * A Create part's lone bridge piece is in a portal block ({@code IPCreatePortalPieceTargets}): the crosshair hits it so
 * blocks can be placed against it, but hitting it must not break the portal block under it.
 */
@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode_BridgePieces {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true, require = 1)
    private void ip_keepPortalUnderPiece(final BlockPos pos, final Direction face, final CallbackInfoReturnable<Boolean> cir) {
        if (isPiece(pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true, require = 1)
    private void ip_keepPortalUnderPieceHeld(final BlockPos pos, final Direction face, final CallbackInfoReturnable<Boolean> cir) {
        if (isPiece(pos)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean isPiece(final BlockPos pos) {
        final var level = Minecraft.getInstance().level;
        return level != null && level.getBlockState(pos).getBlock() == PortalPlaceholderBlock.instance
            && PortalPlaceholderBlock.bridgePiece(level, pos) != null;
    }
}
