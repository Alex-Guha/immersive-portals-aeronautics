/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossing;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/**
 * Create rebuilds a carriage's rendered blocks whenever its whole-block portal cut moves (every block travelled while
 * crossing). Rebuilds go through {@link IPCreateCarriageCrossingClient#refreshRendered} instead, which rebuilds only
 * when what the blocks should show changed: a copy cut at an IP portal's plane is drawn whole whatever Create's cut.
 */
@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity", remap = false)
public class MixinCarriageDimensionalEntity_SeamlessNoRebuild {
    @Inject(method = "invalidate", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ip_rebuildWhenNeeded(final CarriageContraptionEntity entity, final CallbackInfo ci) {
        if (!IPCreateCarriageCrossing.STOCK) {
            ci.cancel();
            IPCreateCarriageCrossingClient.refreshRendered(entity);
        }
    }
}
