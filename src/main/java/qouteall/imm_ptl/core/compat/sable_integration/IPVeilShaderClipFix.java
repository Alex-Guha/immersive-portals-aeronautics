/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.blaze3d.shaders.Program.Type;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

/**
 * Re-applies IP's {@link ShaderCodeTransformation} to vanilla shaders recompiled by Veil's dynamic-buffer
 * pipeline, which otherwise lose IP's injected {@code iportal_ClippingEquation} front-clipping code.
 * Wired from {@code MixinVeilVanillaShaderCompiler_Clipping}.
 */
public class IPVeilShaderClipFix {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean logged = false;

    public static String apply(String shaderName, ResourceLocation shaderLocation, String source) {
        if (source != null && shaderName != null && shaderLocation != null) {
            try {
                Type type = shaderLocation.getPath().endsWith(".vsh") ? Type.VERTEX : Type.FRAGMENT;
                String transformed = ShaderCodeTransformation.transform(type, shaderName, source);
                if (transformed != null && !transformed.equals(source)) {
                    if (!logged) {
                        logged = true;
                        LOGGER.debug(
                            "[IPVeilShaderClipFix] re-applied IP's clipping transform to veil-recompiled vanilla shaders (first: {}) - portal front clipping restored on the vanilla renderer path",
                            shaderName
                        );
                    }

                    return transformed;
                } else {
                    return source;
                }
            } catch (Throwable e) {
                LOGGER.debug("[IPVeilShaderClipFix] transform failed for {}: {}", shaderName, e.toString());
                return source;
            }
        } else {
            return source;
        }
    }
}
