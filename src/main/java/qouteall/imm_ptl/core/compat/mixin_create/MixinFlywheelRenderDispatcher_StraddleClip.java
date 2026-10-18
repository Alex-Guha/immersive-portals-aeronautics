/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.api.backend.RenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelFarHalf;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelStraddleClip;

/**
 * Ends each Flywheel pass's straddle cut of its ships' parts ({@link IPFlywheelStraddleClip}; it begins in
 * {@code MixinFlywheelVisualizationManager_StraddleClip}), then draws the far halves of ships straddling into its level
 * ({@link IPFlywheelFarHalf}).
 */
@Mixin(
    targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl$RenderDispatcherImpl",
    remap = false
)
public class MixinFlywheelRenderDispatcher_StraddleClip {
    @Inject(method = "afterEntities", at = @At("RETURN"), require = 0, expect = 0)
    private void ip_endStraddleClip(final RenderContext context, final CallbackInfo ci) {
        IPFlywheelStraddleClip.endPass();
        // then the far halves of ships straddling into this level, drawn by their own dimension's engine
        IPFlywheelFarHalf.afterEntities(context);
    }

    @Inject(method = "onStartLevelRender", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_noteFrameStart(final RenderContext context, final CallbackInfo ci) {
        IPFlywheelFarHalf.onStartLevelRender(context);
    }
}
