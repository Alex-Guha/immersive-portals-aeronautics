/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Keeps Databank's post-processing effects (Halcyon's progression, ping, ore ping, machine output and effect
 * shaders) out of IP portal passes, like {@link MixinLodestonePostProcess_PortalPassSkip}: Databank runs each active
 * {@code PostShaderInstance}, a vanilla {@code PostChain} drawn back onto the main framebuffer, from NeoForge's
 * render-stage event ({@code AFTER_LEVEL} by default), which also fires in the {@code renderLevel} IP calls for a
 * portal's view. Its passes clear their output (colour and depth) ignoring IP's portal stencil, so the frame around a
 * visible portal was wiped while an effect was active. The main pass applies the effects to the whole screen.
 */
@Mixin(
    targets = "com.cmdpro.databank.shaders.PostShaderManager",
    remap = false
)
public class MixinDatabankPostShaders_PortalPassSkip {
    @Inject(
        method = "onRenderLevelStage",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void ip_skipEffectsInPortalPass(CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            ci.cancel();
        }
    }
}
