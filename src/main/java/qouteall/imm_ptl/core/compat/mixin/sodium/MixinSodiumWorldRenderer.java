/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;
import qouteall.imm_ptl.core.render.IPFrustumCapture;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    @Inject(
        method = "setupTerrain",
        at = @At("HEAD")
    )
    private void onUpdateChunks(
        Camera camera, Viewport viewport, boolean spectator, boolean updateChunksImmediately, CallbackInfo ci
    ) {
        SodiumInterface.frustumCuller = new FrustumCuller();
        // Immersive Aeronautics: with a frustum captured (F3+U) the viewport is the captured camera's, and the boxes
        // tested against the portal rim are relative to it (IPFrustumCapture)
        if (IPFrustumCapture.isCulling()) {
            CameraTransform transform = viewport.getTransform();
            SodiumInterface.frustumCuller.update(transform.x, transform.y, transform.z);
            return;
        }
        Vec3 cameraPos = camera.getPosition();
        SodiumInterface.frustumCuller.update(cameraPos.x, cameraPos.y, cameraPos.z);
    }
}
