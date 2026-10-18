/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import java.util.Set;

/**
 * SodiumDynamicLights sources that must not light anything right now: Create Dynamic Lights' lights of lamps on the
 * far half of a straddling ship, which are drawn in the destination and lit there by {@code IPStraddleDynLights}
 * instead. Written on the render thread, read by Sodium's meshing threads
 * ({@code MixinSodiumDynamicLights_StraddleLights}); each value is a new set, never changed after it is published.
 * No SodiumDynamicLights or Sable types here, so the mixin can read it without either.
 */
public final class IPDynLightsSuppression {
    public static volatile Set<Object> suppressed = Set.of();

    private IPDynLightsSuppression() {
    }
}
