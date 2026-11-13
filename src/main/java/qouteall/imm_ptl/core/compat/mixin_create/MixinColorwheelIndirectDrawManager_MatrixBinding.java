/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPColorwheelMatrixBinding;

/** Binds the engine's own matrix buffer before Colorwheel places its bounding spheres ({@link IPColorwheelMatrixBinding}). */
@Mixin(targets = "dev.djefrey.colorwheel.indirect.ClrwlIndirectDrawManager", remap = false)
public class MixinColorwheelIndirectDrawManager_MatrixBinding {
    @Inject(
        method = "prepareFrame",
        at = @At(
            value = "INVOKE",
            target = "Ldev/djefrey/colorwheel/engine/uniform/ClrwlUniforms;bind(Z)V",
            shift = At.Shift.AFTER
        ),
        require = 0,
        expect = 0
    )
    private void ip_bindOwnMatrices(final CallbackInfo ci) {
        IPColorwheelMatrixBinding.bindOwnMatrices(this);
    }
}
