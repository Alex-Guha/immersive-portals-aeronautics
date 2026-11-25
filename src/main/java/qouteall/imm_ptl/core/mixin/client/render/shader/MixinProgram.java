/*
 * Immersive Portals Revamped - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals Revamped, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.io.InputStream;
import java.nio.charset.Charset;

@Mixin(value = Program.class)
public class MixinProgram {
    // Immersive Aeronautics: a wrapper, not a @Redirect: other mods redirect this same read to patch shader sources
    // (Shadered), and a second @Redirect of one call is skipped, which fails our required injection.
    // The wrapper stacks on theirs and transforms the source they return.
    @WrapOperation(
        method = "compileShaderInternal",
        at = @At(
            value = "INVOKE",
            target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/InputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;",
            remap = false
        )
    )
    private static String wrapReadShaderSource(
        InputStream inputStream, Charset charset, Operation<String> original,
        @Local(argsOnly = true) Program.Type type,
        @Local(argsOnly = true, ordinal = 0) String name
    ) {
        String shaderCode = original.call(inputStream, charset);
        return ShaderCodeTransformation.transform(type, name, shaderCode);
    }
}
