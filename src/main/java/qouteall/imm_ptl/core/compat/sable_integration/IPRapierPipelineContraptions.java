/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import dev.ryanhcode.sable.api.sublevel.KinematicContraption;

/** Sable's Rapier pipeline: whether a contraption is in it ({@code MixinSableRapierPipeline_ElevatorCars}). */
public interface IPRapierPipelineContraptions {
    boolean ip_hasContraption(KinematicContraption contraption);
}
