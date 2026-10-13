/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.mojang.logging.LogUtils;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Flywheel renders inside IP portal passes, which clobber its frame uniforms (camera,
 * matrices) with the portal view. At the head of
 * {@code VisualizationManagerImpl$RenderDispatcherImpl.afterEntities}, when not inside a
 * portal pass, re-uploads {@code FrameUniforms} from the main-view context so the main
 * draw uses the correct state again.
 */
@Mixin(
    targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl$RenderDispatcherImpl",
    remap = false
)
public class MixinFlywheelRenderDispatcher_PortalPassSkip {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged = false;
    @Unique
    private static boolean ip_restoreFailed = false;

    @Inject(
        method = "afterEntities",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void ip_restoreMainFrameState(RenderContext context, CallbackInfo ci) {
        try {
            if (PortalRendering.isRendering()) {
                return;
            }

            FrameUniforms.update(context);
            if (!ip_logged) {
                ip_logged = true;
                ip_LOGGER.debug(
                    "[IPFlywheelPortalFix] flywheel now renders inside portal views; main-view frame uniforms restored before each main draw"
                );
            }
        } catch (Throwable t) {
            if (!ip_restoreFailed) {
                ip_restoreFailed = true;
                ip_LOGGER.debug("[IPFlywheelPortalFix] frame-uniform restore unavailable: {}", String.valueOf(t));
            }
        }
    }
}
