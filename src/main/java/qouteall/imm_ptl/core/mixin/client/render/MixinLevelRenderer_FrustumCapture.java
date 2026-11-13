/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.IPFrustumCapture;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/** Vanilla's frustum capture (F3+U) followed into portal passes: {@link IPFrustumCapture}. */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer_FrustumCapture {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void ip_frameStart(
        DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci
    ) {
        if (!WorldRenderInfo.isRendering()) {
            IPFrustumCapture.onFrameStart((LevelRenderer) (Object) this);
        }
    }

    @Inject(
        method = "captureFrustum(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;DDDLnet/minecraft/client/renderer/culling/Frustum;)V",
        at = @At("HEAD")
    )
    private void ip_onCapture(
        Matrix4f viewMatrix, Matrix4f projectionMatrix, double camX, double camY, double camZ, Frustum captured,
        CallbackInfo ci
    ) {
        IPFrustumCapture.onCapture((LevelRenderer) (Object) this, viewMatrix, projectionMatrix, camX, camY, camZ);
    }

    @Inject(method = "killFrustum", at = @At("HEAD"))
    private void ip_onKill(CallbackInfo ci) {
        IPFrustumCapture.onKill((LevelRenderer) (Object) this);
    }
}
