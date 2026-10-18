/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import toni.sodiumdynamiclights.DynamicLightSource;

import java.util.function.Consumer;

/**
 * Read access to SodiumDynamicLights' light sources ({@code MixinSodiumDynamicLights_StraddleLights}).
 */
public interface IPSodiumDynamicLightsExt {
    /** Calls {@code action} for every source, under SodiumDynamicLights' read lock. */
    void ip_forEachSource(Consumer<DynamicLightSource> action);
}
