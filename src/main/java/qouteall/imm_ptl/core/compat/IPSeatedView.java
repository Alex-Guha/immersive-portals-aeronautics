/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateRiderTrace;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateSeatedView;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipBlockInteraction;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Client: a seated player whose head, as drawn, is past the portal plane of the vehicle it rides, before the server
 * has moved it through (a seat on a straddling ship, {@code IPStraddleSeatView}; a seat on a train,
 * {@link IPCreateSeatedView}). IP's cross-portal view ({@code CrossPortalViewRendering}) and the crosshair
 * ({@code BlockManipulationClient}) then go through that portal. Safe to call without Sable or Create.
 */
public final class IPSeatedView {
    private static final boolean CREATE = ModList.get().isLoaded("create");

    /**
     * @param portal the portal the view and the crosshair go through
     * @param origin a point just in front of the portal plane, level with the head
     */
    public record View(Portal portal, Vec3 origin) {
    }

    private IPSeatedView() {
    }

    @Nullable
    public static View of(final float partialTick) {
        final Portal shipPortal = IPShipBlockInteraction.seatedViewPortal(partialTick);
        if (shipPortal != null) {
            final Vec3 origin = IPShipBlockInteraction.seatedViewOrigin(partialTick);
            if (origin != null) {
                return new View(shipPortal, origin);
            }
        }
        return CREATE ? IPCreateSeatedView.of(partialTick) : null;
    }

    /** After each frame was drawn; {@code crossPortal}: whether IP's cross-portal view drew it (train rider trace). */
    public static void afterFrame(final boolean crossPortal) {
        if (CREATE) {
            IPCreateRiderTrace.afterFrame(crossPortal);
            qouteall.imm_ptl.core.compat.create_integration.IPCreateCrossingTrace.afterFrame();
        }
    }
}
