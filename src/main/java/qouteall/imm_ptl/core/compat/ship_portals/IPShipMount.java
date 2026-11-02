/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/**
 * What the rest of the mod may ask of a ship-mounted portal's driver without loading Sable classes
 * ({@link IPShipPortalDriver} implements it).
 */
public interface IPShipMount {
    /** The UUID of the ship the portal is mounted on. */
    UUID carrier();

    /** The origin of the portal side it drives, in the carrier's plot. */
    Vec3 plotOrigin();
}
