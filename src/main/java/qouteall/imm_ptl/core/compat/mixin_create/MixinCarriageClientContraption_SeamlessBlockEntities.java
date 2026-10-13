/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.render.ClientContraption;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import java.util.BitSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/**
 * Block entities of a portal-crossing carriage copy drawn by their vanilla renderer: those past the portal plane are
 * left out ({@link IPCreateCarriageCrossingClient#withoutBeyondPlane}); Flywheel-drawn ones are cut exactly instead.
 */
@Mixin(targets = "com.simibubi.create.content.trains.entity.CarriageContraption$CarriageClientContraption", remap = false)
public class MixinCarriageClientContraption_SeamlessBlockEntities {
    @Shadow
    @Final
    CarriageContraption this$0;

    @Unique
    private final BitSet ip_scratch = new BitSet();

    @Inject(method = "getAndAdjustShouldRenderBlockEntities", at = @At("RETURN"), cancellable = true, require = 0, expect = 0)
    private void ip_hideBeyondPortalPlane(final CallbackInfoReturnable<BitSet> cir) {
        final BitSet adjusted = IPCreateCarriageCrossingClient.withoutBeyondPlane(
            cir.getReturnValue(), ((ClientContraption) (Object) this).renderedBlockEntityView, this$0.entity, ip_scratch);
        if (adjusted != cir.getReturnValue()) {
            cir.setReturnValue(adjusted);
        }
    }
}
