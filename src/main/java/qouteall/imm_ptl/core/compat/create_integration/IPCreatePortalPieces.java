/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceWalk;

/** Create's parts as kinds of the portal pieces ({@code compat.portal_pieces}). */
public final class IPCreatePortalPieces {
    private IPCreatePortalPieces() {
    }

    /** Common setup: Create's kinds facing portals ({@link IPCreatePortalEnds}). */
    public static void init() {
        IPCreatePortalEnds.init();
    }

    /** Client setup: Create's kinds in the walk over the portal blocks, in the order they always ran. */
    public static void initClient() {
        IPPortalPieceWalk.register(IPCreatePortalShaftHalves.ShaftScan::new);
        IPPortalPieceWalk.register(IPCreatePortalPipeStubs.PipeScan::new);
        IPPortalPieceWalk.register(IPCreatePortalTrackStubs.SCANNER);
        IPPortalPieceWalk.register(IPCreatePortalBeltLook.SCANNER);
    }
}
