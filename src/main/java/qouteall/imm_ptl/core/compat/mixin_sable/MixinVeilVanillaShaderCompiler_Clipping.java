/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.impl.client.render.shader.processor.VanillaShaderProcessor;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sable_integration.IPVeilShaderClipFix;

/**
 * Veil's dynamic-buffer pipeline recompiles vanilla shaders from their original source,
 * which drops IP's injected {@code iportal_ClippingEquation} clip code. This redirects
 * {@code VanillaShaderProcessor.modify} inside {@code VanillaShaderCompiler.compileShader}
 * and re-applies IP's shader transformation via {@link IPVeilShaderClipFix#apply}.
 * Targets Veil 4.3.x internals.
 */
@Mixin(
    targets = "foundry.veil.impl.client.render.dynamicbuffer.VanillaShaderCompiler",
    remap = false
)
public class MixinVeilVanillaShaderCompiler_Clipping {
    @Redirect(
        method = "compileShader",
        at = @At(
            value = "INVOKE",
            target = "Lfoundry/veil/impl/client/render/shader/processor/VanillaShaderProcessor;modify(Ljava/util/Map;Ljava/lang/String;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;IILjava/lang/String;Lorg/lwjgl/opengl/GLCapabilities;)Ljava/lang/String;"
        ),
        require = 0
    )
    private String ip_reapplyClippingTransform(
        Map<String, Object> customProgramData, String shaderName, ResourceLocation shaderLocation,
        VertexFormat vertexFormat, int activeBuffers, int shaderType, String source, GLCapabilities capabilities
    ) throws Exception {
        String modified = VanillaShaderProcessor.modify(
            customProgramData, shaderName, shaderLocation, vertexFormat, activeBuffers, shaderType, source, capabilities
        );
        return IPVeilShaderClipFix.apply(shaderName, shaderLocation, modified);
    }
}
