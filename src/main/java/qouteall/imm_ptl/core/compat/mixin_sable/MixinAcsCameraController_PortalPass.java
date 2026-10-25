/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Aeronautics Camera Sync tilts the camera with the ship the player stands on, at the end of {@code Camera.setup},
 * for the main camera only ({@code CameraController.forMainCamera}: {@code camera == gameRenderer.getMainCamera()}).
 * A portal pass swaps its own camera in as the main camera ({@code MyGameRenderer.switchAndRenderTheWorld}), so ACS
 * took every portal view for the player's eye: it moved the portal camera about the player's feet (tens of blocks off
 * through a nether portal on a tilted ship) and recomputed its shared state from there (its wall check pulled the
 * player's own tilt towards level while a portal was in view; one extra smoothing step per portal).
 * <p>
 * In a portal pass ACS is told this is not the main camera, and the portal camera takes the main camera's rotation
 * instead, ACS's tilt included, so the view through the portal turns with the player's view. Its position already
 * comes from the main camera ({@code PortalRendering.getRenderingCameraPos}). ACS 1.3.6 checks the camera inline and
 * is not covered.
 */
@Mixin(targets = "com.playsi.aero_cam_sync.client.tilt.CameraController", remap = false)
public class MixinAcsCameraController_PortalPass {
    @Inject(method = "forMainCamera", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ip_notMainInPortalPass(Camera camera, CallbackInfoReturnable<Object> cir) {
        if (!WorldRenderInfo.isRendering()) {
            return;
        }
        Camera mainCamera = RenderStates.originalCamera;
        if (mainCamera == null || camera == mainCamera || camera != Minecraft.getInstance().gameRenderer.getMainCamera()) {
            return;
        }
        camera.rotation().set(mainCamera.rotation());
        cir.setReturnValue(null);
    }
}
