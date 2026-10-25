/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.CarriageContraption;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCarriageCrossingHolder;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossing;

/**
 * While a carriage copy crosses an IP portal on the client, none of its blocks count as hidden in the portal: no
 * whole-block cut and no purple glass seam ({@code withinVisible}/{@code atSeam} feed every
 * {@code isHiddenInPortal} check, the rendered blocks and the seam particles). The copy is cut at the portal plane
 * instead ({@code IPCreateCarriageCrossingClient}). The crossing is only ever set on the client, so the server keeps
 * Create's cut (collisions, passengers, actors).
 */
@Mixin(value = CarriageContraption.class, remap = false)
public class MixinCarriageContraption_SeamlessCrossing implements IPCarriageCrossingHolder {
    @Unique
    @Nullable
    private volatile IPCreateCarriageCrossing.Crossing ip_crossing;

    @Override
    public IPCreateCarriageCrossing.Crossing ip_getCrossing() {
        return ip_crossing;
    }

    @Override
    public void ip_setCrossing(final IPCreateCarriageCrossing.Crossing crossing) {
        ip_crossing = crossing;
    }

    /** Not built for anything yet: the first check rebuilds once. */
    @Unique
    private volatile long ip_renderedKey = Long.MAX_VALUE;

    @Override
    public long ip_getRenderedKey() {
        return ip_renderedKey;
    }

    @Override
    public void ip_setRenderedKey(final long key) {
        ip_renderedKey = key;
    }

    @Inject(method = "withinVisible(I)Z", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ip_allVisible(final int posAlongMovementAxis, final CallbackInfoReturnable<Boolean> cir) {
        if (ip_ignoresCreateCut()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "atSeam(I)Z", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ip_noSeam(final int posAlongMovementAxis, final CallbackInfoReturnable<Boolean> cir) {
        if (ip_ignoresCreateCut()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * On the client Create's cut never applies (1.24.11): a copy is drawn whole, cut at the portal plane while it
     * crosses ({@code IPCreateCarriageCrossingClient}). A copy that showed Create's cut without a crossing of ours
     * kept it, purple glass and half hidden, for reasons not found yet; drawn whole it is at worst uncut for a moment.
     * The same at portals within the copy's dimension (side copies, {@code IPCreateTrainSides}).
     */
    @Unique
    private boolean ip_ignoresCreateCut() {
        if (ip_crossing != null) {
            return true;
        }
        final com.simibubi.create.content.contraptions.AbstractContraptionEntity entity =
            ((com.simibubi.create.content.contraptions.Contraption) (Object) this).entity;
        return !IPCreateCarriageCrossing.STOCK && entity != null && entity.level() != null && entity.level().isClientSide();
    }
}
