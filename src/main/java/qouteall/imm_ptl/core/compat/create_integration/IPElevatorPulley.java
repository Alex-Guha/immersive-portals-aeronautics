/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/** An elevator pulley's shaft through portals ({@code MixinElevatorPulleyBlockEntity_Portals}); synced to clients. */
public interface IPElevatorPulley {
    /** The portals the shaft runs through, top down ({@link IPElevatorShaft#findChain}); empty if none. */
    List<IPElevatorShaft> ip$chain();

    void ip$setChain(List<IPElevatorShaft> chain);

    /** The first portal of the shaft, or null. */
    default @Nullable IPElevatorShaft ip$shaft() {
        final List<IPElevatorShaft> chain = ip$chain();
        return chain.isEmpty() ? null : chain.get(0);
    }

    /** Server tick of the last shaft search. */
    long ip$lastScan();

    void ip$setLastScan(long tick);
}
