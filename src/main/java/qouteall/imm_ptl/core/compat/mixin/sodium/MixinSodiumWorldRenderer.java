/*
 * Immersive Portals Revamped - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals Revamped, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;
import qouteall.imm_ptl.core.render.IPFrustumCapture;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.Objects;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    // Immersive Aeronautics: the outer culling the player's own view's section lists were last built with
    @Unique
    private FrustumCuller.@Nullable OuterCullingKey ip_lastOuterCulling;
    
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
        }
        else {
            Vec3 cameraPos = camera.getPosition();
            SodiumInterface.frustumCuller.update(cameraPos.x, cameraPos.y, cameraPos.z);
        }
        ip_rebuildOnOuterCullingChange();
    }
    
    /**
     * Immersive Aeronautics: Sodium rebuilds its section lists only when the camera moves or turns or chunks change,
     * and the outer culling drops sections from them (MixinSodiumSectionCollector). A portal that moves while the
     * camera stands still (on a ship), appears, goes or stops being cullable would leave the lists cut where it was:
     * a hole beside the portal, or terrain drawn behind it for nothing. The player's own view only: portal passes and
     * the shadow pass have no outer culling, and a same-dimension portal pass rebuilds the lists after itself anyway.
     */
    @Unique
    private void ip_rebuildOnOuterCullingChange() {
        if (PortalRendering.isRendering() || IrisInterface.invoker.isRenderingShadowMap()) {
            return;
        }
        FrustumCuller.OuterCullingKey outerCulling = SodiumInterface.frustumCuller.getOuterCulling();
        if (!Objects.equals(outerCulling, ip_lastOuterCulling)) {
            ip_lastOuterCulling = outerCulling;
            ((IESodiumWorldRenderer) (Object) this).ip_getRenderSectionManager().markGraphDirty();
        }
    }
}
