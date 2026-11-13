/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.ducks;

import com.mojang.blaze3d.shaders.Uniform;
import org.jetbrains.annotations.Nullable;

public interface IEShader {
    @Nullable
    Uniform ip_getClippingEquationUniform();

    // Immersive Aeronautics: the lightmap's unit, for far halves lit by the destination's sky (IPFarHalfSkyLightmap)
    /**
     * @return the texture unit {@code apply()} binds sampler {@code name} to (its index among the shader's samplers), or
     * -1 when the shader has no such sampler
     */
    int ip_samplerUnit(String name);
}
