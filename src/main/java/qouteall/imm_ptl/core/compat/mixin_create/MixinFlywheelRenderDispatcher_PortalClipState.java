/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.api.backend.RenderContext;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelFarHalf;

/**
 * Makes sure IP's clip plane is enabled while Flywheel draws its visuals in a portal pass. Flywheel
 * draws between the entity loop and the block entities, where IP's entity clipping is normally still
 * active; this covers any ordering where it is not, and restores the previous state afterwards.
 */
@Mixin(
    targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl$RenderDispatcherImpl",
    remap = false
)
public class MixinFlywheelRenderDispatcher_PortalClipState {
    @Unique
    private boolean ip_enabledClippingHere;

    @Inject(
        method = "afterEntities",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void ip_enablePortalClipping(RenderContext context, CallbackInfo ci) {
        ip_enabledClippingHere = false;
        // Immersive Aeronautics: a far-half draw sets up IP's plane itself, in its own frame
        if (PortalRendering.isRendering() && !FrontClipping.isClippingEnabled && !IPFlywheelFarHalf.isDrawing()) {
            FrontClipping.setupInnerClipping(
                PortalRendering.getActiveClippingPlane(), new Matrix4f(context.modelView()), 0
            );
            ip_enabledClippingHere = FrontClipping.isClippingEnabled;
        }
    }

    @Inject(
        method = "afterEntities",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void ip_restoreClippingState(RenderContext context, CallbackInfo ci) {
        if (ip_enabledClippingHere) {
            ip_enabledClippingHere = false;
            FrontClipping.disableClipping();
        }
    }
}
