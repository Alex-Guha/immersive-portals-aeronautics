/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Punchy! Moves' shoulder camera hides the local player, or fades it, when its camera comes within about half a block
 * of the player's head ({@code ShoulderCameraController}, read by its {@code LivingEntityRenderer} hook). It works that
 * out at the end of every {@code Camera.setup}, portal views included, from the player's position in its own world but
 * against the viewed world's blocks ({@code Minecraft.level} in a portal pass), where that position is often inside
 * terrain: the player then vanished from the view through a portal, and from the whole screen in the cross-portal view.
 * <p>
 * A portal view's camera is IP's (MixinCamera), never at the player's head, so in portal views the player is drawn as
 * IP decides, and the state computed for the player's own camera is left as it was.
 */
@Mixin(targets = "punchymoves.client.camera.ShoulderCameraController", remap = false)
public class MixinPunchyShoulderCamera_PortalViews {
    @Inject(method = "updateLocalAvatarCameraVisibility", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ip_keepOwnCameraState(CallbackInfo ci) {
        if (WorldRenderInfo.isRendering()) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldSkipLocalAvatarForCameraCollision", at = @At("RETURN"), cancellable = true, require = 0)
    private static void ip_drawInPortalViews(CallbackInfoReturnable<Boolean> cir) {
        if (WorldRenderInfo.isRendering()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "localAvatarAlpha", at = @At("RETURN"), cancellable = true, require = 0)
    private static void ip_opaqueInPortalViews(CallbackInfoReturnable<Float> cir) {
        if (WorldRenderInfo.isRendering()) {
            cir.setReturnValue(1.0F);
        }
    }
}
