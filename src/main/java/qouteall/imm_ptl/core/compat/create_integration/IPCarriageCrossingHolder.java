/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import org.jetbrains.annotations.Nullable;

/**
 * The client-side crossing of a carriage copy, kept on its {@code CarriageContraption}
 * ({@code MixinCarriageContraption_SeamlessCrossing}); null while Create's own portal cut applies.
 */
public interface IPCarriageCrossingHolder {
    @Nullable
    IPCreateCarriageCrossing.Crossing ip_getCrossing();

    void ip_setCrossing(@Nullable IPCreateCarriageCrossing.Crossing crossing);

    /**
     * What the carriage's rendered blocks were last built for ({@code IPCreateCarriageCrossingClient.refreshRendered}):
     * whole, or Create's cut with its cutoffs.
     */
    long ip_getRenderedKey();

    void ip_setRenderedKey(long key);
}
