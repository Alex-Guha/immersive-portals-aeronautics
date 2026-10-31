/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

/**
 * Portal view distance ({@link IPPortalViewDistance}): records which fog is being set up, so only terrain fog is
 * clamped (sky fog clamped to a short end would turn the whole sky fog coloured).
 */
@Mixin(FogRenderer.class)
public class MixinFogRenderer_PortalViewFog {
    @Inject(method = "setupFog", at = @At("HEAD"))
    private static void ip_recordFogMode(
        Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance, boolean shouldCreateFog,
        float partialTick, CallbackInfo ci
    ) {
        IPPortalViewDistance.onSetupFog(fogMode);
    }

    @Inject(method = "setupNoFog", at = @At("HEAD"))
    private static void ip_recordNoFog(CallbackInfo ci) {
        IPPortalViewDistance.onSetupNoFog();
    }
}
