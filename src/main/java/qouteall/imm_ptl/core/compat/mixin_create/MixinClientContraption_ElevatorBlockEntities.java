/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ClientContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.util.BitSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/**
 * Block entities Create draws with their vanilla renderer on an elevator car through a portal, or on its far copy: those
 * whose block centre is past the copy's plane are left out (the other copy draws them;
 * {@code IPElevatorPortalsClient}). Carriages have their own hook ({@code MixinCarriageClientContraption_SeamlessBlockEntities}).
 */
@Mixin(value = ClientContraption.class, remap = false)
public class MixinClientContraption_ElevatorBlockEntities {
    @Unique
    private final BitSet ip_scratch = new BitSet();

    @Inject(method = "getAndAdjustShouldRenderBlockEntities", at = @At("RETURN"), cancellable = true)
    private void ip_hideBeyondElevatorPlane(final CallbackInfoReturnable<BitSet> cir) {
        final ClientContraption self = (ClientContraption) (Object) this;
        final AbstractContraptionEntity entity = ((IPClientContraptionAccessor) self).ip_getContraption().entity;
        if (entity == null || entity instanceof CarriageContraptionEntity) {
            return;
        }
        final BitSet adjusted = IPCreateCarriageCrossingClient.withoutBeyondPlane(cir.getReturnValue(), self.renderedBlockEntityView, entity, ip_scratch);
        if (adjusted != cir.getReturnValue()) {
            cir.setReturnValue(adjusted);
        }
    }
}
