/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelStraddleClip;

/**
 * Begins each Flywheel pass's cuts ({@link IPFlywheelStraddleClip#beginPass}) right before the engine draws, after
 * Flywheel has waited for its frame plan. Visuals of new entities are created by that plan on Flywheel's worker threads,
 * so at the head of {@code afterEntities} a carriage copy the server had just spawned could still be missing: it got
 * no plane and was drawn whole, uncut and not hidden, for its first frame (BUGS #5: the front of a carriage flashed
 * ahead of the train, at the server's position, as its front wheels reached a portal).
 */
@Mixin(value = VisualizationManagerImpl.class, remap = false)
public class MixinFlywheelVisualizationManager_StraddleClip {
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Ldev/engine_room/flywheel/api/backend/Engine;render(Ldev/engine_room/flywheel/api/backend/RenderContext;)V"
        ),
        require = 0,
        expect = 0
    )
    private void ip_beginStraddleClip(final RenderContext context, final CallbackInfo ci) {
        IPFlywheelStraddleClip.beginPass(context);
    }
}
