/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Keeps Lodestone's post-processing effects out of IP portal passes.
 * <p>
 * Lodestone runs every registered {@code PostProcessor} (Jaden's Nether Expansion's Nether heat haze, Malum's
 * effects...) at NeoForge's {@code AFTER_LEVEL} stage, which also fires at the end of the {@code renderLevel} IP calls
 * for a portal's view. Each effect is a vanilla {@code PostChain} whose last pass writes back to the main framebuffer,
 * and vanilla's {@code PostPass} clears its output target (colour and depth) before drawing. A clear ignores IP's
 * portal stencil, so inside a portal pass it wiped the whole frame and the effect's blit only came back inside the
 * portal's area: the player's own terrain vanished around a visible portal while the heat haze was active (in the
 * Nether, and for a few seconds after leaving it while the haze faded out).
 * <p>
 * Skipping the effects in portal passes leaves them to the main pass, which applies them to the whole screen
 * (portal contents included). The depth copy is skipped too: Lodestone copies the main depth buffer once per frame
 * ({@code didCopyDepth}), and a copy taken inside a portal pass would stand in for the main view's.
 */
@Mixin(
    targets = "team.lodestar.lodestone.systems.postprocess.PostProcessHandler",
    remap = false
)
public class MixinLodestonePostProcess_PortalPassSkip {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged = false;

    @Inject(
        method = "onWorldRenderLast",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void ip_skipEffectsInPortalPass(CallbackInfo ci) {
        ip_skipInPortalPass(ci);
    }

    @Inject(
        method = "copyDepthBuffer",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void ip_skipDepthCopyInPortalPass(CallbackInfo ci) {
        ip_skipInPortalPass(ci);
    }

    @Unique
    private static void ip_skipInPortalPass(CallbackInfo ci) {
        if (!PortalRendering.isRendering()) {
            return;
        }
        if (!ip_logged) {
            ip_logged = true;
            ip_LOGGER.debug("[IPLodestone] skipping Lodestone post-processing in a portal pass");
        }
        ci.cancel();
    }
}
