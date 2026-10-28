/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Sway updates its bends once a frame, in the player's own pass ({@code IPFoliageCompat}).
 * <p>
 * Sway runs {@code SwayEngine.update} at the head of every {@code LevelRenderer.renderLevel}, portal passes included. It
 * finds the entities around the player in {@code Minecraft.level} (the portal's world there), keeps the bends in one
 * map keyed by block position, and marks every section whose bend changed for a rebuild. With a portal in view the
 * portal pass found no entities there and let the player's bends decay, the main pass bent them again, and both
 * worlds' sections around the player were rebuilt every frame. A same-world portal pass also stepped the smoothing
 * twice a frame.
 */
@Mixin(targets = "com.github.razorplay01.sway.client.SwayEngine", remap = false)
public class MixinSwayEngine_OncePerFrame {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private static void ip_skipInPortalPass(CallbackInfo ci) {
        if (WorldRenderInfo.isRendering()) {
            ci.cancel();
        }
    }
}
