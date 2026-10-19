/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.IPPortalPassDepthClear;

/**
 * Depth clears while a sky is drawn in a stencil portal pass stay inside the portal
 * ({@link IPPortalPassDepthClear}); Cirrus issues one after masking the sun behind its clouds.
 */
@Mixin(value = RenderSystem.class, remap = false)
public class MixinRenderSystem_PortalPassDepthClear {
    @Inject(method = "clear(IZ)V", at = @At("HEAD"), cancellable = true)
    private static void ip_limitDepthClearToPortal(int mask, boolean checkError, CallbackInfo ci) {
        if (!IPPortalPassDepthClear.shouldReplace(mask)) {
            return;
        }
        RenderSystem.assertOnRenderThreadOrInit();
        IPPortalPassDepthClear.clearDepthWithinStencil();
        int rest = mask & ~GL11.GL_DEPTH_BUFFER_BIT;
        if (rest != 0) {
            GlStateManager._clear(rest, checkError);
        }
        ci.cancel();
    }
}
