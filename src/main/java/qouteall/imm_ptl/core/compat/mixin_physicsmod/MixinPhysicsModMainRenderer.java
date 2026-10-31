/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_physicsmod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Physics Mod draws for the main view only: skipped in portal passes. It turns its ocean, snow and vine physics off by
 * itself when it finds Immersive Portals.
 */
@Mixin(targets = "net.diebuddies.render.MainRenderer", remap = false)
public class MixinPhysicsModMainRenderer {
    @Inject(method = {"render", "renderAll"}, at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ip_skipDuringPortalRendering(CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            ci.cancel();
        }
    }
}
