/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Keeps Create Aeronautics' hot-air balloon overlay out of IP portal passes.
 * <p>
 * The overlay is drawn at Veil's {@code AFTER_SOLID_BLOCKS} stage for the balloons of
 * {@code Minecraft.level} into its own framebuffer, with its own shader (no IP clip plane), and
 * then composited onto the main framebuffer by a full-screen blit that only depth-tests against the
 * main depth buffer. Inside a portal pass that composite ignores the portal's stencil area, so the
 * portal-side dimension's balloons were painted over the player's own dimension wherever they lay in
 * front of its terrain (the hot-air texture "ghosting" into the other dimension). Skipping the effect
 * in portal passes leaves it for the main view only; balloons seen through a portal show their blocks
 * without the heat overlay.
 */
@Mixin(
    targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.ClientBalloonEffectRenderer",
    remap = false
)
public class MixinAeronauticsBalloonEffect_PortalPassSkip {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged = false;

    @Inject(
        method = "onRenderLevelStage",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void ip_skipInPortalPass(CallbackInfo ci) {
        if (!PortalRendering.isRendering()) {
            return;
        }
        if (!ip_logged) {
            ip_logged = true;
            ip_LOGGER.debug("[IPBalloonEffect] skipping Aeronautics' hot-air overlay in a portal pass");
        }
        ci.cancel();
    }
}
