/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import org.jetbrains.annotations.Nullable;

/** An elevator pulley's shaft through a portal ({@code MixinElevatorPulleyBlockEntity_Portals}); synced to clients. */
public interface IPElevatorPulley {
    @Nullable IPElevatorShaft ip$shaft();

    void ip$setShaft(@Nullable IPElevatorShaft shaft);

    /** Server tick of the last shaft search. */
    long ip$lastScan();

    void ip$setLastScan(long tick);
}
