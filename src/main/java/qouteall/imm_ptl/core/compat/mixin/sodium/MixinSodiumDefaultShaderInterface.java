/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;

@Pseudo
@Mixin(value = DefaultShaderInterface.class, remap = false)
public class MixinSodiumDefaultShaderInterface {
    @Unique
    private GlUniformFloat4v uIPClippingEquation;
    
    @Inject(
        method = "<init>",
        at = @At("RETURN"),
//        require = 0,
        remap = false
    )
    private void onInit(
        ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci
    ) {
        this.uIPClippingEquation = context.bindUniformOptional("iportal_ClippingEquation", GlUniformFloat4v::new);
    }

    @Inject(
        method = "setupState",
        at = @At("RETURN"),
        remap = false
    )
    private void onSetup(CallbackInfo ci) {
        if (uIPClippingEquation != null) {
            if (FrontClipping.isClippingEnabled) {
                // Immersive Aeronautics: world terrain's own plane (no adjustment band at mid-block portal planes)
                double[] equation = FrontClipping.getActiveTerrainClipPlaneEquationAfterModelView();
                uIPClippingEquation.set(new float[]{
                    (float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]
                });
            }
            else {
                uIPClippingEquation.set(new float[]{0, 0, 0, 1});
            }
        }
    }
}
