/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * A Create kinetic block entity as an end of a shaft through a portal ({@link IPCreatePortalShafts}), added by
 * {@code MixinKineticBlockEntity_PortalShafts}. All saved with the block entity, so a driven end keeps turning, and
 * both networks keep their shared stress, across a reload.
 */
public interface IPPortalShaftEnd {
    /** The speed the portal turns a driven end's face with (the far shaft's, mapped through the portal), else 0. */
    float ip$getPortalSpeed();

    void ip$setPortalSpeed(float speed);

    /** The face of a driven end that opens onto the portal, null when not driven. */
    @Nullable Direction ip$getPortalSide();

    void ip$setPortalSide(@Nullable Direction side);

    /** Stress capacity (SU) a driven end adds to its network: what the far network has to spare. */
    float ip$getPortalCapacity();

    void ip$setPortalCapacity(float capacity);

    /** Stress (SU) a driving end adds to its network: what the far networks need beyond their own capacity. */
    float ip$getPortalLoad();

    void ip$setPortalLoad(float load);

    /** Create's copy of the network's total capacity (SU), as last synced to this block entity. */
    float ip$getNetworkCapacity();

    /** Create's copy of the network's total stress (SU), as last synced to this block entity. */
    float ip$getNetworkStress();

    /** Driven by the portal and still with the portal block as its Create source (not taken over by its own side). */
    boolean ip$isPortalDriven();

    default boolean ip$hasPortalState() {
        return ip$getPortalSpeed() != 0 || ip$getPortalLoad() != 0 || ip$getPortalCapacity() != 0;
    }
}
